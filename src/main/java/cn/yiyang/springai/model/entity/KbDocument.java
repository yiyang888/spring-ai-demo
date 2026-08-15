package cn.yiyang.springai.model.entity;

import cn.yiyang.springai.model.enums.DocumentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 文档元数据实体（对应 kb_document 表）
 */
@Schema(description = "文档元数据实体")
public class KbDocument {

    @Schema(description = "主键ID", example = "1")
    private Long id;

    @Schema(description = "文档标题", example = "Java开发指南.pdf")
    private String title;

    @Schema(description = "文件名", example = "Java开发指南.pdf")
    private String fileName;

    @Schema(description = "文件格式", example = "pdf")
    private String fileFormat;

    @Schema(description = "文件大小（字节）", example = "102400")
    private Long fileSize;

    @Schema(description = "内容MD5哈希（用于去重）", example = "d41d8cd98f00b204e9800998ecf8427e")
    private String contentHash;

    @Schema(description = "文档描述")
    private String description;

    @Schema(description = "文档状态", example = "READY",
            allowableValues = {"PENDING", "PROCESSING", "READY", "FAILED"})
    private DocumentStatus status;

    @Schema(description = "分块数量", example = "10")
    private Integer chunkCount;

    @Schema(description = "总Token数", example = "5000")
    private Integer totalTokens;

    @Schema(description = "分块大小（字符数）", example = "500")
    private Integer chunkSize;

    @Schema(description = "重叠大小（字符数）", example = "100")
    private Integer overlap;

    @Schema(description = "切分器类型", example = "RECURSIVE")
    private String splitterType;

    @Schema(description = "原始文本内容（用于重新切分）")
    private String rawText;

    @Schema(description = "处理失败时的错误信息")
    private String errorMessage;

    @Schema(description = "文档分类（如：技术/产品/管理）", example = "技术")
    private String category;

    @Schema(description = "文档来源/作者", example = "公司官网")
    private String author;

    @Schema(description = "文档日期（内容所属时间）", example = "2024-06-01")
    private LocalDate docDate;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFileFormat() { return fileFormat; }
    public void setFileFormat(String fileFormat) { this.fileFormat = fileFormat; }

    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public DocumentStatus getStatus() { return status; }
    public void setStatus(DocumentStatus status) { this.status = status; }

    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }

    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }

    public Integer getChunkSize() { return chunkSize; }
    public void setChunkSize(Integer chunkSize) { this.chunkSize = chunkSize; }

    public Integer getOverlap() { return overlap; }
    public void setOverlap(Integer overlap) { this.overlap = overlap; }

    public String getSplitterType() { return splitterType; }
    public void setSplitterType(String splitterType) { this.splitterType = splitterType; }

    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public LocalDate getDocDate() { return docDate; }
    public void setDocDate(LocalDate docDate) { this.docDate = docDate; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
