package cn.yiyang.springai.service;

import cn.yiyang.repository.ChunkRepository;
import cn.yiyang.repository.DocumentRepository;
import cn.yiyang.springai.model.entity.KbChunk;
import cn.yiyang.springai.model.entity.KbDocument;
import cn.yiyang.springai.model.enums.DocumentStatus;
import cn.yiyang.springai.transformer.RecursiveTextSplitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 异步文档处理服务
 *
 * 文档上传后的完整处理流程在独立线程池中执行，不阻塞 HTTP 请求：
 *   ① 更新状态 PROCESSING → ② 提取文本 → ③ 递归切分 → ④ 保存分块 → ⑤ 向量化入库 → ⑥ 更新状态 READY
 *
 * 线程池配置见 AsyncConfig（核心 2 线程，峰值 4 线程，队列 50）
 * 异常时更新状态为 FAILED 并记录错误信息
 */
@Service
public class DocumentAsyncService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAsyncService.class);

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final VectorIndexService vectorIndexService;
    private final KbCacheService kbCacheService;

    public DocumentAsyncService(DocumentRepository documentRepository,
                                ChunkRepository chunkRepository,
                                VectorIndexService vectorIndexService,
                                KbCacheService kbCacheService) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.vectorIndexService = vectorIndexService;
        this.kbCacheService = kbCacheService;
    }

    /**
     * 异步处理文档：提取文本 → 切分 → 向量化 → 入库
     *
     * @param docId        文档 ID
     * @param tempFilePath 临时文件路径（upload 时保存的，处理完后删除）
     * @param filename     原始文件名（用于创建 Reader）
     * @param ext          文件扩展名
     * @param chunkSize    切分大小
     * @param overlap      重叠大小
     */
    @Async("kbTaskExecutor")
    public void processDocument(Long docId, String tempFilePath, String filename,
                                 String ext, int chunkSize, int overlap) {
        log.info("[异步处理] 开始处理文档: id={}, file={}, 线程={}", docId, filename,
                Thread.currentThread().getName());

        try {
            // ① 更新状态为 PROCESSING
            documentRepository.updateStatus(docId, DocumentStatus.PROCESSING, null);

            // ② 提取文本
            File temp = new File(tempFilePath);
            Resource resource = new FileSystemResource(temp);
            List<Document> rawDocs;
            try {
                rawDocs = createReader(resource, filename, ext).get();
            } finally {
                temp.delete();  // 提取完文本就删临时文件
            }
            if (rawDocs.isEmpty()) {
                throw new RuntimeException("文件内容为空");
            }

            // ③ 保存原始文本（用于后续重新切分）
            String rawText = rawDocs.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
            documentRepository.updateRawText(docId, rawText);

            // ④ 递归切分
            TextSplitter splitter = new RecursiveTextSplitter(chunkSize, overlap);
            List<Document> springDocs = splitter.apply(rawDocs);

            // ⑤ 保存分块到 kb_chunk
            KbDocument doc = documentRepository.findById(docId);
            List<KbChunk> chunkEntities = new ArrayList<>();
            for (int i = 0; i < springDocs.size(); i++) {
                KbChunk chunk = new KbChunk();
                chunk.setDocumentId(docId);
                chunk.setChunkIndex(i);
                chunk.setContent(springDocs.get(i).getText());
                chunk.setContentLength(springDocs.get(i).getText().length());
                chunk = chunkRepository.save(chunk);
                chunkEntities.add(chunk);
            }

            // ⑥ 向量化入库
            vectorIndexService.embedAndStore(doc, chunkEntities, springDocs);

            // ⑦ 更新文档状态为 READY
            documentRepository.updateStatus(docId, DocumentStatus.READY, null);
            documentRepository.updateChunkInfo(docId, chunkEntities.size(), 0);

            // ⑧ 知识库已变更，清除缓存
            kbCacheService.evictAll();

            log.info("[异步处理] 文档处理完成: id={}, file={}, chunks={}", docId, filename, chunkEntities.size());

        } catch (Exception e) {
            log.error("[异步处理] 文档处理失败: id={}, file={}, error={}", docId, filename, e.getMessage());
            documentRepository.updateStatus(docId, DocumentStatus.FAILED, e.getMessage());
            // 清理临时文件（如果还存在）
            new File(tempFilePath).delete();
        }
    }

    // ========== 私有工具方法 ==========

    /**
     * 根据扩展名创建对应的 DocumentReader
     */
    private DocumentReader createReader(Resource resource, String filename, String ext) {
        return switch (ext) {
            case "txt" -> {
                TextReader reader = new TextReader(resource);
                reader.getCustomMetadata().put("source", filename);
                yield reader;
            }
            case "md", "markdown" -> {
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(false)
                        .withAdditionalMetadata("source", filename)
                        .build();
                yield new MarkdownDocumentReader(resource, config);
            }
            case "pdf" -> new PagePdfDocumentReader(resource);
            case "doc", "docx" -> new TikaDocumentReader(resource);
            default -> throw new RuntimeException("不支持的格式：." + ext);
        };
    }
}
