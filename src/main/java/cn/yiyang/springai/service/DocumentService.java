package cn.yiyang.springai.service;

import cn.yiyang.repository.ChunkRepository;
import cn.yiyang.repository.DocumentRepository;
import cn.yiyang.springai.exception.BusinessException;
import cn.yiyang.springai.model.dto.DocumentUpdateDTO;
import cn.yiyang.springai.model.entity.KbChunk;
import cn.yiyang.springai.model.entity.KbDocument;
import cn.yiyang.springai.model.enums.DocumentStatus;
import cn.yiyang.springai.model.vo.ChunkVO;
import cn.yiyang.springai.model.vo.DocumentVO;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 文档管理：上传/查询/更新/删除/重新向量化/重新切分
 *
 * 上传完整流程：
 *   文件 → 计算 hash 去重 → 创建文档记录(PROCESSING) → 提取文本 → 递归切分
 *   → 保存分块到 kb_chunk → 批量向量化入库 → 更新文档状态(READY)
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documentRepository;
    private final ChunkRepository chunkRepository;
    private final VectorIndexService vectorIndexService;
    private final KbCacheService kbCacheService;
    private final DocumentAsyncService documentAsyncService;

    public DocumentService(DocumentRepository documentRepository, ChunkRepository chunkRepository,
                           VectorIndexService vectorIndexService, KbCacheService kbCacheService,
                           DocumentAsyncService documentAsyncService) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.vectorIndexService = vectorIndexService;
        this.kbCacheService = kbCacheService;
        this.documentAsyncService = documentAsyncService;
    }

    // ========== 1. 上传文档（非阻塞，异步处理） ==========

    /**
     * 上传文档（非阻塞）
     *
     * 只做：校验 → 创建文档记录（PENDING）→ 保存临时文件 → 提交异步任务 → 立即返回
     * 实际处理（提取文本 → 切分 → 向量化）在 DocumentAsyncService 异步执行，不阻塞 HTTP 请求
     *
     * @param file       上传文件
     * @param chunkSize  切分大小
     * @param overlap    重叠大小
     * @param category   文档分类（可选）
     * @param author     文档来源/作者（可选）
     * @param docDate    文档日期（可选，yyyy-MM-dd）
     * @return DocumentVO（status=PENDING，前端通过轮询查看处理进度）
     */
    public DocumentVO upload(MultipartFile file, int chunkSize, int overlap,
                              String category, String author, String docDate) throws Exception {
        // ① 校验文件
        String filename = file.getOriginalFilename();
        if (filename == null || filename.isEmpty()) {
            throw new BusinessException("文件名不能为空");
        }
        String ext = extensionOf(filename);
        if (!isSupported(ext)) {
            throw new BusinessException("仅支持 .txt / .md / .pdf / .doc / .docx 文件");
        }

        // ② 计算内容哈希，检查是否已存在
        String hash = md5(file.getBytes());
        if (documentRepository.existsByContentHash(hash)) {
            throw new BusinessException("内容相同的文档已存在，请勿重复上传");
        }

        // ③ 创建文档记录，状态 PENDING（等待异步处理）
        KbDocument doc = new KbDocument();
        doc.setTitle(filename);
        doc.setFileName(filename);
        doc.setFileFormat(ext);
        doc.setFileSize(file.getSize());
        doc.setContentHash(hash);
        doc.setChunkSize(chunkSize);
        doc.setOverlap(overlap);
        doc.setSplitterType("RECURSIVE");
        doc.setStatus(DocumentStatus.PENDING);
        // ★ 设置文档元数据（分类/来源/日期）
        doc.setCategory(trim(category));
        doc.setAuthor(trim(author));
        doc.setDocDate(parseDate(docDate));
        doc = documentRepository.save(doc);

        // ④ 保存临时文件 + 提交异步任务（确保异常时清理临时文件）
        File temp = Files.createTempFile("kb-upload-", "." + ext).toFile();
        try {
            file.transferTo(temp);
            // ⑤ 提交异步处理任务，立即返回（不等待处理完成）
            documentAsyncService.processDocument(doc.getId(), temp.getAbsolutePath(),
                    filename, ext, chunkSize, overlap);
        } catch (Exception e) {
            // 异步任务提交失败时清理临时文件，防止磁盘泄露
            if (!temp.delete()) {
                log.warn("[upload] 临时文件删除失败: {}", temp.getAbsolutePath());
            }
            throw e;
        }

        // ⑥ 立即返回（状态为 PENDING，前端通过轮询/刷新查看处理结果）
        return DocumentVO.from(documentRepository.findById(doc.getId()));
    }

    // ========== 2. 文档列表 ==========

    public Map<String, Object> list(int page, int size, String status) {
        int offset = page * size;
        List<DocumentVO> docs = documentRepository.findAll(offset, size, status).stream()
                .map(DocumentVO::from)
                .toList();
        int total = documentRepository.count(status);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", page);
        result.put("size", size);
        result.put("total", total);
        result.put("documents", docs);
        return result;
    }

    /** 查询所有不重复的分类（用于前端下拉选择） */
    public List<String> listCategories() {
        return documentRepository.findCategories();
    }

    /** 查询所有不重复的来源/作者（用于前端下拉选择） */
    public List<String> listAuthors() {
        return documentRepository.findAuthors();
    }

    // ========== 3. 文档详情（含分块预览） ==========

    public Map<String, Object> detail(Long id) {
        KbDocument doc = documentRepository.findById(id);
        if (doc == null) {
            throw new RuntimeException("文档不存在: " + id);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("document", DocumentVO.from(doc));

        List<ChunkVO> chunks = chunkRepository.findByDocumentId(id).stream()
                .map(c -> ChunkVO.from(c, true))
                .toList();
        result.put("chunks", chunks);
        return result;
    }

    // ========== 4. 更新文档信息（标题/描述/分类/来源/日期） ==========

    public DocumentVO update(Long id, DocumentUpdateDTO dto) {
        KbDocument doc = documentRepository.findById(id);
        if (doc == null) {
            throw new RuntimeException("文档不存在: " + id);
        }

        String title = dto.getTitle() != null ? dto.getTitle() : doc.getTitle();
        String description = dto.getDescription() != null ? dto.getDescription() : doc.getDescription();
        String category = dto.getCategory() != null ? trim(dto.getCategory()) : doc.getCategory();
        String author = dto.getAuthor() != null ? trim(dto.getAuthor()) : doc.getAuthor();
        LocalDate docDate = dto.getDocDate() != null ? parseDate(dto.getDocDate()) : doc.getDocDate();
        documentRepository.updateMetadata(id, title, description, category, author, docDate);

        // ★ 元数据变更后需要重新向量化（向量 metadata 中的 category/author/docDate 需要更新）
        // 但这里只更新文档元数据表，向量中的旧元数据在下次重新向量化时才会更新
        // 如果需要即时同步向量元数据，可以在这里触发 reindex

        return DocumentVO.from(documentRepository.findById(id));
    }

    // ========== 5. 删除文档（级联删除分块 + 向量） ==========

    @Transactional
    public void delete(Long id) {
        KbDocument doc = documentRepository.findById(id);
        if (doc == null) {
            throw new RuntimeException("文档不存在: " + id);
        }

        // ① 删除 kb_vector 中的向量（按 metadata.document_id 过滤）
        vectorIndexService.deleteByDocumentId(id);

        // ② 删除 kb_document（kb_chunk 通过 FK CASCADE 自动删除）
        documentRepository.deleteById(id);

        // ③ 知识库已变更，清除缓存
        kbCacheService.evictAll();
    }

    // ========== 6. 重新向量化（切分参数不变，只重新生成 embedding） ==========

    @Transactional
    public void reindex(Long id) {
        KbDocument doc = documentRepository.findById(id);
        if (doc == null) {
            throw new RuntimeException("文档不存在: " + id);
        }

        List<KbChunk> chunks = chunkRepository.findByDocumentId(id);
        if (chunks.isEmpty()) {
            throw new RuntimeException("文档没有分块，无法重新向量化");
        }

        vectorIndexService.reembedDocument(doc, chunks);

        // 重新向量化后清除缓存
        kbCacheService.evictAll();
    }

    // ========== 7. 重新切分（用新参数重新切分原始文本） ==========

    @Transactional
    public List<ChunkVO> reSplit(Long documentId, int chunkSize, int overlap) {
        KbDocument doc = documentRepository.findByIdWithRawText(documentId);
        if (doc == null) {
            throw new RuntimeException("文档不存在: " + documentId);
        }
        if (doc.getRawText() == null || doc.getRawText().isEmpty()) {
            throw new RuntimeException("原始文本为空，无法重新切分");
        }

        // ① 删除旧分块 + 旧向量
        vectorIndexService.deleteByDocumentId(documentId);
        chunkRepository.deleteByDocumentId(documentId);

        // ② 重新切分原始文本
        List<Document> rawDocs = List.of(new Document(doc.getRawText()));
        TextSplitter splitter = new RecursiveTextSplitter(chunkSize, overlap);
        List<Document> springDocs = splitter.apply(rawDocs);

        // ③ 保存新分块
        List<KbChunk> chunkEntities = new ArrayList<>();
        for (int i = 0; i < springDocs.size(); i++) {
            KbChunk chunk = new KbChunk();
            chunk.setDocumentId(documentId);
            chunk.setChunkIndex(i);
            chunk.setContent(springDocs.get(i).getText());
            chunk.setContentLength(springDocs.get(i).getText().length());
            chunk = chunkRepository.save(chunk);
            chunkEntities.add(chunk);
        }

        // ④ 向量化入库
        vectorIndexService.embedAndStore(doc, chunkEntities, springDocs);

        // ⑤ 更新文档切分参数和分块数
        documentRepository.updateSplitParams(documentId, chunkSize, overlap);
        documentRepository.updateChunkInfo(documentId, chunkEntities.size(), 0);

        // ⑥ 知识库已变更，清除缓存
        kbCacheService.evictAll();

        return chunkEntities.stream().map(c -> ChunkVO.from(c, true)).toList();
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

    private String extensionOf(String filename) {
        if (filename == null) return "";
        int i = filename.lastIndexOf('.');
        return i < 0 ? "" : filename.substring(i + 1).toLowerCase();
    }

    private boolean isSupported(String ext) {
        return List.of("txt", "md", "markdown", "pdf", "doc", "docx").contains(ext);
    }

    /** 去空格，空字符串返回 null */
    private String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 解析日期字符串（yyyy-MM-dd），空/无效返回 null */
    private LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) return null;
        try {
            return LocalDate.parse(dateStr.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 计算文件内容的 MD5 哈希（用于去重）
     *
     * @throws RuntimeException MD5 计算失败时抛出异常（不应静默返回 null，否则去重逻辑失效）
     */
    private String md5(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("文件哈希计算失败", e);
        }
    }
}
