package cn.yiyang.springai.controller.demo;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring AI DocumentReader 实战：用不同 Reader 读取 PDF / Word / TXT / Markdown
 *
 * 统一接口：DocumentReader extends Supplier<List<Document>>，核心方法 get()（别名 read()）
 * 所有 Reader 都返回 List<Document>，Document 里含 text + metadata（如 source、page_number）
 *
 * 格式 → Reader → artifactId：
 *   TXT       → TextReader              → spring-ai-core（自带，无需额外依赖）
 *   Markdown  → MarkdownDocumentReader  → spring-ai-markdown-document-reader
 *   PDF       → PagePdfDocumentReader   → spring-ai-pdf-document-reader（基于 PDFBox）
 *   Word      → TikaDocumentReader      → spring-ai-tika-document-reader（基于 Apache Tika，通吃 doc/docx/ppt/html）
 *
 * 接口：
 *   POST /reader/upload   统一上传，按扩展名自动选 Reader，返回提取的文档片段
 *   POST /reader/pdf      专门演示 PagePdfDocumentReader（可配置每几页切一个 Document）
 *
 * 对比 DocumentRagController：那个用 BufferedReader 手动读 txt/md；这里统一走 Spring AI 的 DocumentReader，
 * 多格式、自带 metadata、可直接接 TokenTextSplitter + VectorStore 完成 RAG 入库。
 */
@RestController
@RequestMapping("/reader")
public class DocumentReaderController {

    // ========== 1. 统一上传：按扩展名自动选择对应的 DocumentReader ==========

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws Exception {
        if (file.isEmpty()) {
            throw new RuntimeException("文件不能为空");
        }
        String filename = file.getOriginalFilename();
        String ext = extOf(filename);

        // 存为临时文件：PDFBox / Tika 需要随机访问，InputStreamResource 只能读一次会出问题
        File temp = Files.createTempFile("spring-ai-reader-", "." + ext).toFile();
        file.transferTo(temp);
        Resource resource = new FileSystemResource(temp);

        List<Document> documents;
        try {
            documents = readByExtension(resource, filename, ext);
        } finally {
            temp.delete(); // 读完即删，避免临时文件堆积
        }

        return buildResult(filename, ext, documents);
    }

    // ========== 2. PDF 专项：PagePdfDocumentReader，按页切分 ==========

    @PostMapping("/pdf")
    public Map<String, Object> readPdf(@RequestParam("file") MultipartFile file,
                                       @RequestParam(defaultValue = "1") int pagesPerDocument) throws Exception {
        if (file.isEmpty()) {
            throw new RuntimeException("文件不能为空");
        }
        File temp = Files.createTempFile("spring-ai-pdf-", ".pdf").toFile();
        file.transferTo(temp);
        Resource resource = new FileSystemResource(temp);

        List<Document> documents;
        try {
            // pagesPerDocument：每 N 页合并成一个 Document（默认 1 页一个，检索粒度更细）
            PdfDocumentReaderConfig config = PdfDocumentReaderConfig.builder()
                    .withPagesPerDocument(pagesPerDocument)
                    .build();
            PagePdfDocumentReader reader = new PagePdfDocumentReader(resource, config);
            documents = reader.get();
        } finally {
            temp.delete();
        }

        Map<String, Object> result = buildResult(file.getOriginalFilename(), "pdf", documents);
        result.put("pagesPerDocument", pagesPerDocument);
        return result;
    }

    // ========== 核心分派：扩展名 → 具体 Reader ==========

    private List<Document> readByExtension(Resource resource, String filename, String ext) {
        DocumentReader reader = switch (ext) {
            case "txt" -> {
                // TextReader：整篇文本读成 1 个 Document，可加自定义 metadata
                TextReader textReader = new TextReader(resource);
                textReader.getCustomMetadata().put("source", filename);
                yield textReader;
            }
            case "md", "markdown" -> {
                // MarkdownDocumentReader：按结构切分，比纯文本更尊重文档语义
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true) // 水平线 --- 切出新文档
                        .withIncludeCodeBlock(false)           // 代码块单独成段（便于代码检索）
                        .withIncludeBlockquote(false)          // 引用块单独成段
                        .withAdditionalMetadata("source", filename)
                        .build();
                yield new MarkdownDocumentReader(resource, config);
            }
            case "pdf" -> new PagePdfDocumentReader(resource); // 默认每页一个 Document
            case "doc", "docx" -> new TikaDocumentReader(resource); // Tika 通吃 Office 全家桶
            default -> throw new RuntimeException("不支持的格式：." + ext + "（支持 txt/md/pdf/doc/docx）");
        };
        return reader.get();
    }

    // ========== 工具方法 ==========

    private String extOf(String filename) {
        if (filename == null) return "";
        int i = filename.lastIndexOf('.');
        return i < 0 ? "" : filename.substring(i + 1).toLowerCase();
    }

    private Map<String, Object> buildResult(String filename, String ext, List<Document> documents) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("filename", filename);
        result.put("format", ext);
        result.put("documentCount", documents.size());
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("index", i);
            String text = documents.get(i).getText();
            d.put("length", text.length());
            d.put("preview", text.substring(0, Math.min(80, text.length())) + (text.length() > 80 ? "..." : ""));
            d.put("metadata", documents.get(i).getMetadata());
            list.add(d);
        }
        result.put("documents", list);
        return result;
    }
}
