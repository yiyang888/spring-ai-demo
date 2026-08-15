package cn.yiyang.springai.model.entity;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 文档分块实体（对应 kb_chunk 表）
 */
@Schema(description = "文档分块实体")
public class KbChunk {

    @Schema(description = "主键ID", example = "1")
    private Long id;

    @Schema(description = "所属文档ID", example = "1")
    private Long documentId;

    @Schema(description = "分块序号（从0开始）", example = "0")
    private Integer chunkIndex;

    @Schema(description = "分块文本内容")
    private String content;

    @Schema(description = "内容长度（字符数）", example = "500")
    private Integer contentLength;

    @Schema(description = "Token数量", example = "200")
    private Integer tokenCount;

    @Schema(description = "关联的向量ID（kb_vector 表的 id）", example = "550e8400-e29b-41d4-a716-446655440000")
    private String vectorId;

    @Schema(description = "元数据（JSON）")
    private String metadata;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }

    public Integer getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(Integer chunkIndex) { this.chunkIndex = chunkIndex; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Integer getContentLength() { return contentLength; }
    public void setContentLength(Integer contentLength) { this.contentLength = contentLength; }

    public Integer getTokenCount() { return tokenCount; }
    public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }

    public String getVectorId() { return vectorId; }
    public void setVectorId(String vectorId) { this.vectorId = vectorId; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
