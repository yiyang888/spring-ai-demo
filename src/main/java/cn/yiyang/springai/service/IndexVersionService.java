package cn.yiyang.springai.service;

import cn.yiyang.repository.ChunkRepository;
import cn.yiyang.repository.DocumentRepository;
import cn.yiyang.repository.IndexVersionRepository;
import cn.yiyang.springai.model.entity.KbChunk;
import cn.yiyang.springai.model.entity.KbDocument;
import cn.yiyang.springai.model.entity.KbIndexVersion;
import cn.yiyang.springai.model.enums.IndexVersionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 向量索引版本管理服务
 *
 * 三大核心能力：
 *   1. 版本管理：创建版本、查看版本列表、查看当前活跃版本
 *   2. 灰度切换：新版本构建完成后，原子切换 ACTIVE 状态（旧版本自动归档）
 *   3. 一键回滚：将指定 ARCHIVED 版本重新激活，当前 ACTIVE 版本自动归档
 *
 * 设计要点：
 *   - 灰度重建时不删除旧版本向量，新旧版本向量共存于 kb_vector 中（通过 metadata.version 区分）
 *   - 原子切换通过数据库唯一索引保证同一时间只有一个 ACTIVE 版本
 *   - 回滚是秒级操作（只改状态，不重建向量）
 */
@Service
public class IndexVersionService {

    private final IndexVersionRepository indexVersionRepository;
    private final VectorIndexService vectorIndexService;
    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final KbCacheService kbCacheService;

    public IndexVersionService(IndexVersionRepository indexVersionRepository,
                               VectorIndexService vectorIndexService,
                               DocumentRepository documentRepository,
                               ChunkRepository chunkRepository,
                               KbCacheService kbCacheService) {
        this.indexVersionRepository = indexVersionRepository;
        this.vectorIndexService = vectorIndexService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.kbCacheService = kbCacheService;
    }

    // ========== 1. 版本管理 ==========

    /**
     * 创建新版本（状态为 BUILDING）
     * 场景：准备灰度重建，先创建一个空版本，然后向其中构建向量
     *
     * @param description 版本描述（如 "embedding模型升级到v3"）
     * @return 新版本信息
     */
    public KbIndexVersion createVersion(String description) {
        String label = indexVersionRepository.nextVersionLabel();
        return indexVersionRepository.save(label, description);
    }

    /**
     * 列出所有版本（附加实时向量数量）
     */
    public List<Map<String, Object>> listVersions() {
        List<KbIndexVersion> versions = indexVersionRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (KbIndexVersion v : versions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", v.getId());
            item.put("versionLabel", v.getVersionLabel());
            item.put("status", v.getStatus().name());
            item.put("description", v.getDescription());
            // 实时统计该版本的向量数和文档数
            int actualVectorCount = vectorIndexService.countVectorsByVersion(v.getId());
            int actualDocCount = vectorIndexService.countDocumentsByVersion(v.getId());
            item.put("vectorCount", actualVectorCount);
            item.put("recordedVectorCount", v.getVectorCount());
            item.put("documentCount", actualDocCount);
            item.put("createdAt", v.getCreatedAt());
            item.put("activatedAt", v.getActivatedAt());
            item.put("archivedAt", v.getArchivedAt());
            result.add(item);
        }
        return result;
    }

    /**
     * 获取当前 ACTIVE 版本
     */
    public KbIndexVersion getActiveVersion() {
        return indexVersionRepository.findActive();
    }

    // ========== 2. 灰度切换 ==========

    /**
     * 激活指定版本（灰度切换的核心操作）
     *
     * 流程：
     *   ① 当前 ACTIVE 版本 → ARCHIVED（记录归档时间）
     *   ② 目标版本（BUILDING 或 ARCHIVED）→ ACTIVE（记录激活时间）
     *
     * 原子性：通过数据库唯一索引保证同一时间只有一个 ACTIVE
     *
     * @param versionId 要激活的版本ID
     * @return 切换结果
     */
    @Transactional
    public Map<String, Object> activateVersion(Long versionId) {
        KbIndexVersion target = indexVersionRepository.findById(versionId);
        if (target == null) {
            throw new RuntimeException("版本不存在: " + versionId);
        }
        if (target.getStatus() == IndexVersionStatus.ACTIVE) {
            throw new RuntimeException("版本 " + target.getVersionLabel() + " 已经是 ACTIVE 状态");
        }

        KbIndexVersion current = indexVersionRepository.findActive();
        String fromLabel = current != null ? current.getVersionLabel() : "无";

        // ★ 原子切换
        indexVersionRepository.activateVersion(versionId);

        // 统计新版本向量数
        int vectorCount = vectorIndexService.countVectorsByVersion(versionId);
        indexVersionRepository.updateCounts(versionId, vectorCount, target.getDocumentCount());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "activate");
        result.put("fromVersion", fromLabel);
        result.put("toVersion", target.getVersionLabel());
        result.put("toVersionId", versionId);
        result.put("vectorCount", vectorCount);
        result.put("message", "灰度切换完成：" + fromLabel + " → " + target.getVersionLabel());

        // 版本切换后清除缓存（不同版本的检索结果不同）
        kbCacheService.evictAll();
        return result;
    }

    // ========== 3. 一键回滚 ==========

    /**
     * 回滚到指定版本
     *
     * 场景：新版本上线后发现有问题，需要快速切回旧版本
     * 原理：旧版本的向量仍在 kb_vector 中（metadata.version 区分），只需切换状态即可
     * 速度：秒级（只改状态，不重建向量）
     *
     * @param versionId 要回滚到的版本ID（必须是 ARCHIVED 状态）
     * @return 回滚结果
     */
    @Transactional
    public Map<String, Object> rollbackToVersion(Long versionId) {
        KbIndexVersion target = indexVersionRepository.findById(versionId);
        if (target == null) {
            throw new RuntimeException("版本不存在: " + versionId);
        }
        if (target.getStatus() != IndexVersionStatus.ARCHIVED) {
            throw new RuntimeException("只能回滚到 ARCHIVED 状态的版本，当前状态: " + target.getStatus());
        }

        KbIndexVersion current = indexVersionRepository.findActive();
        if (current == null) {
            throw new RuntimeException("当前没有 ACTIVE 版本，无法回滚");
        }

        // ★ 原子切换（与灰度切换相同的底层操作，只是语义不同）
        indexVersionRepository.activateVersion(versionId);

        // 统计回滚后版本的向量数
        int vectorCount = vectorIndexService.countVectorsByVersion(versionId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "rollback");
        result.put("fromVersion", current.getVersionLabel());
        result.put("toVersion", target.getVersionLabel());
        result.put("toVersionId", versionId);
        result.put("vectorCount", vectorCount);
        result.put("message", "回滚完成：" + current.getVersionLabel() + " → " + target.getVersionLabel()
                + "（" + vectorCount + " 条向量已生效）");

        // 版本回滚后清除缓存
        kbCacheService.evictAll();
        return result;
    }

    // ========== 4. 灰度重建并切换 ==========

    /**
     * 灰度重建：创建新版本 → 构建向量 → 原子切换
     *
     * 完整流程：
     *   ① 创建新版本（BUILDING）
     *   ② 遍历所有文档，在新版本下重新向量化（不删除旧版本向量）
     *   ③ 统计新版本向量数量
     *   ④ 原子切换：旧版本 ACTIVE→ARCHIVED，新版本 BUILDING→ACTIVE
     *   ⑤ 此后检索/RAG 自动使用新版本
     *
     * 优势：
     *   - 构建期间不影响线上检索（旧版本仍为 ACTIVE）
     *   - 构建失败不会切换（新版本保持 BUILDING，可删除后重试）
     *   - 切换后可一键回滚（旧版本向量仍在）
     *
     * @param description 版本描述
     * @return 重建结果
     */
    public Map<String, Object> rebuildWithNewVersion(String description) {
        // ① 创建新版本
        KbIndexVersion newVersion = createVersion(description);

        // ② 遍历所有文档，在新版本下重新向量化
        List<Long> docIds = documentRepository.findAllIds(null);
        int totalVectors = 0;
        int successDocs = 0;
        int failedDocs = 0;
        List<Map<String, Object>> failures = new ArrayList<>();

        for (Long docId : docIds) {
            try {
                KbDocument doc = documentRepository.findById(docId);
                if (doc == null) continue;

                List<KbChunk> chunks = chunkRepository.findByDocumentId(docId);
                if (chunks.isEmpty()) {
                    failedDocs++;
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("documentId", docId);
                    f.put("fileName", doc.getFileName());
                    f.put("error", "文档没有分块，跳过");
                    failures.add(f);
                    continue;
                }

                // ★ 在新版本下向量化（不删除旧版本向量）
                int count = vectorIndexService.embedDocumentForVersion(doc, chunks, newVersion.getId());
                totalVectors += count;
                successDocs++;

            } catch (Exception e) {
                failedDocs++;
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("documentId", docId);
                f.put("error", e.getMessage());
                failures.add(f);
            }
        }

        // ③ 更新版本统计
        indexVersionRepository.updateCounts(newVersion.getId(), totalVectors, successDocs);

        // ④ 如果有成功向量化的文档，执行原子切换
        if (totalVectors > 0) {
            KbIndexVersion old = indexVersionRepository.findActive();
            String oldLabel = old != null ? old.getVersionLabel() : "无";

            indexVersionRepository.activateVersion(newVersion.getId());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", "rebuild_and_switch");
            result.put("newVersion", newVersion.getVersionLabel());
            result.put("newVersionId", newVersion.getId());
            result.put("oldVersion", oldLabel);
            result.put("totalDocuments", docIds.size());
            result.put("successDocuments", successDocs);
            result.put("failedDocuments", failedDocs);
            result.put("totalVectors", totalVectors);
            result.put("switched", true);
            result.put("message", "灰度重建完成并已切换：" + oldLabel + " → " + newVersion.getVersionLabel()
                    + "（" + totalVectors + " 条向量，" + successDocs + " 个文档）");
            result.put("failures", failures);

            // 灰度重建并切换后清除缓存
            kbCacheService.evictAll();
            return result;
        } else {
            // 没有成功向量化的文档，不切换
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", "rebuild_and_switch");
            result.put("newVersion", newVersion.getVersionLabel());
            result.put("newVersionId", newVersion.getId());
            result.put("totalDocuments", docIds.size());
            result.put("successDocuments", 0);
            result.put("failedDocuments", failedDocs);
            result.put("totalVectors", 0);
            result.put("switched", false);
            result.put("message", "灰度重建失败：没有成功向量化的文档，未执行切换");
            result.put("failures", failures);
            return result;
        }
    }

    // ========== 5. 清理旧版本 ==========

    /**
     * 清理指定版本的向量数据
     * 场景：确认不再需要回滚到某个旧版本后，清理其向量以释放存储空间
     *
     * 注意：只能清理 ARCHIVED 状态的版本，不能清理 ACTIVE 版本
     *
     * @param versionId 要清理的版本ID
     * @return 清理结果
     */
    public Map<String, Object> cleanupVersion(Long versionId) {
        KbIndexVersion version = indexVersionRepository.findById(versionId);
        if (version == null) {
            throw new RuntimeException("版本不存在: " + versionId);
        }
        if (version.getStatus() == IndexVersionStatus.ACTIVE) {
            throw new RuntimeException("不能清理 ACTIVE 版本");
        }

        int deletedCount = vectorIndexService.countVectorsByVersion(versionId);
        vectorIndexService.deleteVectorsByVersion(versionId);
        indexVersionRepository.deleteById(versionId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "cleanup");
        result.put("version", version.getVersionLabel());
        result.put("versionId", versionId);
        result.put("deletedVectors", deletedCount);
        result.put("message", "已清理版本 " + version.getVersionLabel() + "（" + deletedCount + " 条向量已删除）");

        // 清理旧版本向量后清除缓存
        kbCacheService.evictAll();
        return result;
    }

    // ========== 6. 初始化首个版本 ==========

    /**
     * 初始化首个版本：将当前已有的向量纳入版本管理
     *
     * 场景：首次启用版本管理时，已有向量数据但没有版本记录
     * 流程：创建 v1 版本 → 直接激活 → 将已有向量的 metadata 补上 version=v1
     *
     * @return 初始化结果
     */
    public Map<String, Object> initializeFirstVersion(String description) {
        // 检查是否已有版本
        if (indexVersionRepository.findActive() != null) {
            throw new RuntimeException("已存在 ACTIVE 版本，无需初始化");
        }

        // 创建并直接激活 v1
        KbIndexVersion v1 = createVersion(description != null ? description : "初始化首个版本");

        // 原子切换（没有旧版本，直接激活）
        indexVersionRepository.activateVersion(v1.getId());

        // ★ 给已有向量补上 version 字段（迁移旧数据，否则版本过滤后旧向量搜不到）
        int taggedCount = vectorIndexService.tagExistingVectors(v1.getId());
        indexVersionRepository.updateCounts(v1.getId(), taggedCount, 0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "initialize");
        result.put("version", v1.getVersionLabel());
        result.put("versionId", v1.getId());
        result.put("taggedVectors", taggedCount);
        result.put("message", "首个版本 " + v1.getVersionLabel() + " 已创建并激活"
                + (taggedCount > 0 ? "（" + taggedCount + " 条旧向量已自动迁移）" : ""));
        result.put("note", "此后新上传的文档向量将自动标记版本号。");

        // 版本初始化后清除缓存
        kbCacheService.evictAll();
        return result;
    }
}
