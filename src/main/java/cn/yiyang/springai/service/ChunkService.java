package cn.yiyang.springai.service;

import cn.yiyang.repository.ChunkRepository;
import cn.yiyang.repository.DocumentRepository;
import cn.yiyang.springai.model.entity.KbChunk;
import cn.yiyang.springai.model.entity.KbDocument;
import cn.yiyang.springai.model.enums.DocumentStatus;
import cn.yiyang.springai.model.vo.ChunkVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 分块管理：查看分块列表/详情、修改分块内容（触发重新向量化）、删除单个分块
 */
@Service
public class ChunkService {

    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final VectorIndexService vectorIndexService;
    private final KbCacheService kbCacheService;

    public ChunkService(ChunkRepository chunkRepository, DocumentRepository documentRepository,
                        VectorIndexService vectorIndexService, KbCacheService kbCacheService) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.vectorIndexService = vectorIndexService;
        this.kbCacheService = kbCacheService;
    }

    /**
     * 查看文档的所有分块（列表模式，content 截断前 200 字符）
     */
    public List<ChunkVO> listByDocument(Long documentId) {
        return chunkRepository.findByDocumentId(documentId).stream()
                .map(c -> ChunkVO.from(c, true))
                .toList();
    }

    /**
     * 查看单个分块详情（完整 content）
     */
    public ChunkVO detail(Long chunkId) {
        KbChunk chunk = chunkRepository.findById(chunkId);
        if (chunk == null) {
            throw new RuntimeException("分块不存在: " + chunkId);
        }
        return ChunkVO.from(chunk, false);
    }

    /**
     * 修改分块内容：更新 kb_chunk.content → 重新向量化该分块
     * 场景：用户发现某段文本切分不好，手动修改后重新 embedding
     */
    @Transactional
    public ChunkVO updateContent(Long chunkId, String newContent) {
        KbChunk chunk = chunkRepository.findById(chunkId);
        if (chunk == null) {
            throw new RuntimeException("分块不存在: " + chunkId);
        }

        // ① 更新分块内容
        chunkRepository.updateContent(chunkId, newContent, newContent.length());
        chunk.setContent(newContent);
        chunk.setContentLength(newContent.length());

        // ② 重新向量化（仅当文档状态为 READY 时）
        KbDocument doc = documentRepository.findById(chunk.getDocumentId());
        if (doc != null && doc.getStatus() == DocumentStatus.READY) {
            vectorIndexService.reembedChunk(chunk, doc);
        }

        // 知识库已变更，清除缓存
        kbCacheService.evictAll();

        return ChunkVO.from(chunk, false);
    }

    /**
     * 删除单个分块：删除向量 → 删除分块记录 → 更新文档 chunk_count
     */
    @Transactional
    public void delete(Long chunkId) {
        KbChunk chunk = chunkRepository.findById(chunkId);
        if (chunk == null) {
            throw new RuntimeException("分块不存在: " + chunkId);
        }

        // ① 删除向量
        vectorIndexService.deleteByVectorId(chunk.getVectorId());

        // ② 删除分块记录
        chunkRepository.deleteById(chunkId);

        // ③ 更新文档的 chunk_count
        int newCount = chunkRepository.countByDocumentId(chunk.getDocumentId());
        documentRepository.updateChunkInfo(chunk.getDocumentId(), newCount, 0);

        // 知识库已变更，清除缓存
        kbCacheService.evictAll();
    }

    /**
     * 追加分块到文档末尾（实时更新）
     * 场景：用户手动补充一段内容到已有文档
     * 流程：获取当前最大 chunkIndex → 保存新分块 → 向量化 → 更新文档统计
     */
    @Transactional
    public ChunkVO addChunk(Long documentId, String content) {
        KbDocument doc = documentRepository.findById(documentId);
        if (doc == null) {
            throw new RuntimeException("文档不存在: " + documentId);
        }
        if (content == null || content.trim().isEmpty()) {
            throw new RuntimeException("分块内容不能为空");
        }

        // ① 获取当前最大 chunkIndex，新分块序号 +1
        List<KbChunk> existingChunks = chunkRepository.findByDocumentId(documentId);
        int nextIndex = existingChunks.isEmpty() ? 0 :
                existingChunks.stream().mapToInt(KbChunk::getChunkIndex).max().getAsInt() + 1;

        // ② 保存分块到 kb_chunk
        KbChunk chunk = new KbChunk();
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(nextIndex);
        chunk.setContent(content);
        chunk.setContentLength(content.length());
        chunk = chunkRepository.save(chunk);

        // ③ 实时向量化（仅当文档状态为 READY 时）
        if (doc.getStatus() == DocumentStatus.READY) {
            vectorIndexService.reembedChunk(chunk, doc);
        }

        // ④ 更新文档的 chunk_count
        int newCount = chunkRepository.countByDocumentId(documentId);
        documentRepository.updateChunkInfo(documentId, newCount, 0);

        // 知识库已变更，清除缓存
        kbCacheService.evictAll();

        return ChunkVO.from(chunk, false);
    }
}
