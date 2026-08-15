package cn.yiyang.springai.controller.demo;

import cn.yiyang.springai.transformer.RecursiveTextSplitter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 文档 RAG：上传真实文件 → 提取内容 → 切分 → 向量化 → 入库 → 检索 → 大模型生成
 *
 * 完整闭环：
 *   /doc/upload  上传文件，按文件名增量更新（先删旧 chunks 再入库）
 *   /doc/search  纯向量检索（返回段落，不做生成）
 *   /doc/ask     RAG 问答（检索段落 + 大模型生成回答）
 *   /doc/list    查看已上传的文档列表
 *   /doc/clear   清空向量库
 */
@RestController
@RequestMapping("/doc")
public class DocumentRagController {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final JdbcTemplate jdbcTemplate;

    public DocumentRagController(VectorStore vectorStore, ChatClient.Builder chatClientBuilder,
                                 JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
        this.jdbcTemplate = jdbcTemplate;
    }

    // ========== 1. 上传文件入库：文件 → 提取文本 → 切分 → 向量化 → 存储 ==========

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam(defaultValue = "500") int chunkSize,
                                      @RequestParam(defaultValue = "100") int overlap) throws Exception {
        if (file.isEmpty()) {
            throw new RuntimeException("文件不能为空");
        }

        String filename = file.getOriginalFilename();
        String ext = extensionOf(filename);
        if (!isSupported(ext)) {
            throw new RuntimeException("仅支持 .txt / .md / .pdf / .doc / .docx 文件");
        }

        // ① 按格式选择 DocumentReader 提取文本
        // PDFBox / Tika 需要随机访问，MultipartFile 的 InputStream 只能读一次，先落盘为临时文件
        File temp = Files.createTempFile("rag-upload-", "." + ext).toFile();
        file.transferTo(temp);
        Resource resource = new FileSystemResource(temp);

        List<Document> documents;
        try {
            documents = createReader(resource, filename, ext).get();
        } finally {
            temp.delete();
        }

        if (documents.isEmpty()) {
            throw new RuntimeException("文件内容为空");
        }

        // ② 用递归切分器按语义边界切分（段落 -> 句子 -> 逗号 -> 空格 -> 硬截断）
        TextSplitter splitter = new RecursiveTextSplitter(chunkSize, overlap);
        List<Document> chunks = splitter.apply(documents);

        // ③ 给每个 chunk 补充 metadata：文件名 + chunkIndex + 保留 Reader 自带的 category/page_number 等
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            chunk.getMetadata().put("source", filename);
            chunk.getMetadata().put("chunkIndex", String.valueOf(i));
        }

        // ④ 按文件名删除该文档旧 chunks，实现增量更新（多文档互不干扰）
        deleteBySource(filename);

        // ⑤ 向量化并入库（百炼 embedding 限制单次 batch <= 10，必须分批）
        int batchSize = 10;
        for (int i = 0; i < chunks.size(); i += batchSize) {
            List<Document> batch = chunks.subList(i, Math.min(i + batchSize, chunks.size()));
            vectorStore.add(batch);
        }

        // 返回切分结果，方便观察
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filename", filename);
        result.put("format", ext);
        result.put("fileSize", file.getSize());
        result.put("rawDocumentCount", documents.size());
        result.put("chunkSize", chunkSize);
        result.put("overlap", overlap);
        result.put("chunkCount", chunks.size());
        result.put("embeddingBatchSize", batchSize);
        result.put("embeddingBatchCount", (chunks.size() + batchSize - 1) / batchSize);
        List<Map<String, Object>> chunkList = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("index", i);
            String text = chunks.get(i).getText();
            c.put("length", text.length());
            c.put("preview", text.substring(0, Math.min(80, text.length())) + (text.length() > 80 ? "..." : ""));
            c.put("metadata", chunks.get(i).getMetadata());
            chunkList.add(c);
        }
        result.put("chunks", chunkList);
        return result;
    }

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

    /**
     * 按 source=文件名 删除该文档已入库的旧 chunks
     */
    private void deleteBySource(String filename) {
        Filter.Expression filter = new FilterExpressionBuilder()
                .eq("source", filename)
                .build();
        vectorStore.delete(filter);
    }

    // ========== 2. 检索：提问 → 返回最相关的段落 ==========

    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String query,
                                             @RequestParam(defaultValue = "3") int topK,
                                             @RequestParam(defaultValue = "0.0") double similarityThreshold) {
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(similarityThreshold)
                        .build()
        );

        List<Map<String, Object>> list = new ArrayList<>();
        for (Document doc : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("content", doc.getText());
            item.put("metadata", doc.getMetadata());
            item.put("score", doc.getMetadata().get("distance"));
            list.add(item);
        }
        return list;
    }

    // ========== 3. RAG 问答：检索段落 + 大模型生成回答 ==========

    @GetMapping("/ask")
    public Map<String, Object> ask(@RequestParam String question,
                                    @RequestParam(defaultValue = "10") int topK,
                                    @RequestParam(defaultValue = "0.0") double similarityThreshold) {
        // ① 向量检索：找到最相关的段落（topK 调大，确保召回率）
        List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(question)
                        .topK(topK)
                        .similarityThreshold(similarityThreshold)
                        .build()
        );

        // ② 把检索到的段落拼接成上下文
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            context.append("【段落").append(i + 1).append("】")
                    .append(docs.get(i).getText()).append("\n\n");
        }

        // ③ 构造 RAG prompt：强调要全面列举，不要遗漏
        String prompt = """
                请根据以下参考资料回答用户的问题。
                重要规则：
                1. 只能基于参考资料中的内容回答，不要编造信息
                2. 如果参考资料中没有相关内容，明确说"根据文档，无法回答这个问题"
                3. 如果问题涉及"几个"、"哪些"等列举类问题，必须把参考资料中所有相关条目都列出来，不要只列一个
                4. 回答要准确完整，逐条列出，并在每条后标注来源段落编号

                参考资料：
                %s

                用户问题：%s
                """.formatted(context.toString(), question);

        // ④ 调用大模型生成回答
        String answer = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        // ⑤ 返回：答案 + 检索到的段落（方便对照）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("answer", answer);
        result.put("retrievedChunks", docs.size());
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Document doc : docs) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("content", doc.getText());
            s.put("score", doc.getMetadata().get("distance"));
            s.put("source", doc.getMetadata().get("source"));
            sources.add(s);
        }
        result.put("sources", sources);
        return result;
    }

    // ========== 4. 文档管理：列表 / 清空 ==========

    /**
     * 查看当前已上传的文档列表及每个文档的 chunk 数量
     */
    @GetMapping("/list")
    public List<Map<String, Object>> listDocuments() {
        String sql = """
                SELECT metadata->>'source' AS source, COUNT(*) AS chunk_count
                FROM kb_vector
                WHERE metadata->>'source' IS NOT NULL
                GROUP BY metadata->>'source'
                ORDER BY source
                """;
        return jdbcTemplate.queryForList(sql).stream()
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("filename", row.get("source"));
                    item.put("chunkCount", row.get("chunk_count"));
                    return item;
                })
                .collect(Collectors.toList());
    }

    /**
     * 清空整个向量库（多文档全部删除）
     */
    @DeleteMapping("/clear")
    public String clear() {
        // PgVectorStore.delete(List.of()) 不会清空全表，直接用 SQL 兜底
        jdbcTemplate.update("TRUNCATE TABLE kb_vector");
        return "已清空向量库";
    }
}
