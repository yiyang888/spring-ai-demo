package cn.yiyang.springai.controller.kb;

import cn.yiyang.springai.model.entity.KbIndexVersion;
import cn.yiyang.springai.service.IndexVersionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 向量索引版本管理 REST API
 *
 * 路由前缀：/kb/version
 *
 * 端点一览：
 *   GET    /kb/version/list                  列出所有版本
 *   GET    /kb/version/active                获取当前活跃版本
 *   POST   /kb/version/init                  初始化首个版本（首次启用版本管理）
 *   POST   /kb/version/create                创建新版本（BUILDING 状态）
 *   POST   /kb/version/rebuild               灰度重建并切换（创建新版本→构建→原子切换）
 *   POST   /kb/version/{versionId}/activate  激活版本（灰度切换）
 *   POST   /kb/version/{versionId}/rollback  回滚到指定版本
 *   DELETE /kb/version/{versionId}           清理指定版本的向量数据
 */
@RestController
@RequestMapping("/kb/version")
public class IndexVersionController {

    private final IndexVersionService indexVersionService;

    public IndexVersionController(IndexVersionService indexVersionService) {
        this.indexVersionService = indexVersionService;
    }

    // ========== 1. 列出所有版本 ==========

    @GetMapping("/list")
    public List<Map<String, Object>> listVersions() {
        return indexVersionService.listVersions();
    }

    // ========== 2. 获取当前活跃版本 ==========

    @GetMapping("/active")
    public KbIndexVersion getActiveVersion() {
        return indexVersionService.getActiveVersion();
    }

    // ========== 3. 初始化首个版本 ==========

    /**
     * 首次启用版本管理时调用，创建 v1 并激活
     */
    @PostMapping("/init")
    public Map<String, Object> initializeFirstVersion(@RequestParam(required = false) String description) {
        return indexVersionService.initializeFirstVersion(description);
    }

    // ========== 4. 创建新版本 ==========

    /**
     * 创建新版本（BUILDING 状态），后续可手动向量化后激活
     */
    @PostMapping("/create")
    public KbIndexVersion createVersion(@RequestParam(required = false) String description) {
        return indexVersionService.createVersion(description);
    }

    // ========== 5. 灰度重建并切换 ==========

    /**
     * 一键灰度重建：创建新版本 → 全量重新向量化 → 原子切换
     * 场景：embedding 模型升级、向量维度变更
     */
    @PostMapping("/rebuild")
    public Map<String, Object> rebuildWithNewVersion(@RequestParam(required = false) String description) {
        return indexVersionService.rebuildWithNewVersion(description);
    }

    // ========== 6. 激活版本（灰度切换） ==========

    /**
     * 将指定版本激活为 ACTIVE，当前 ACTIVE 版本自动归档
     * 前提：目标版本必须是 BUILDING 或 ARCHIVED 状态
     */
    @PostMapping("/{versionId}/activate")
    public Map<String, Object> activateVersion(@PathVariable Long versionId) {
        return indexVersionService.activateVersion(versionId);
    }

    // ========== 7. 一键回滚 ==========

    /**
     * 回滚到指定 ARCHIVED 版本
     * 当前 ACTIVE 版本自动归档，目标 ARCHIVED 版本重新激活
     * 秒级完成（旧版本向量仍在 kb_vector 中）
     */
    @PostMapping("/{versionId}/rollback")
    public Map<String, Object> rollbackToVersion(@PathVariable Long versionId) {
        return indexVersionService.rollbackToVersion(versionId);
    }

    // ========== 8. 清理旧版本 ==========

    /**
     * 删除指定版本的向量数据，释放存储空间
     * 只能清理 ARCHIVED 状态的版本
     */
    @DeleteMapping("/{versionId}")
    public Map<String, Object> cleanupVersion(@PathVariable Long versionId) {
        return indexVersionService.cleanupVersion(versionId);
    }
}
