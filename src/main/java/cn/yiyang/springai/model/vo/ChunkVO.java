package cn.yiyang.springai.model.vo;

import cn.yiyang.springai.model.entity.KbChunk;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 分块展示对象
 * 列表模式下 content 截断前 200 字符；详情模式下返回完整 content
 */
@Schema(description = "分块展示对象")
public class ChunkVO {

    @Schema(description = "主键ID", example = "1")
    private Long id;

    @Schema(description = "所属文档ID", example = "1")
    private Long documentId;

    @Schema(description = "分块序号（从0开始）", example = "0")
    private Integer chunkIndex;

    @Schema(description = "分块文本内容（列表模式下截断前200字符）")
    private String content;

    @Schema(description = "内容长度（字符数）", example = "500")
    private Integer contentLength;

    @Schema(description = "Token数量", example = "200")
    private Integer tokenCount;

    @Schema(description = "关联的向量ID", example = "550e8400-e29b-41d4-a716-446655440000")
    private String vectorId;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    /**
     * 详情模式：返回完整内容
     */
    public static ChunkVO from(KbChunk chunk) {
        return from(chunk, false);
    }

    /**
     * @param truncate true=列表模式截断 content，false=完整内容
     */
    public static ChunkVO from(KbChunk chunk, boolean truncate) {
        ChunkVO vo = new ChunkVO();
        vo.id = chunk.getId();
        vo.documentId = chunk.getDocumentId();
        vo.chunkIndex = chunk.getChunkIndex();
        String text = chunk.getContent();
        if (truncate && text != null && text.length() > 200) {
            vo.content = text.substring(0, 200) + "...";
        } else {
            vo.content = text;
        }
        vo.contentLength = chunk.getContentLength();
        vo.tokenCount = chunk.getTokenCount();
        vo.vectorId = chunk.getVectorId();
        vo.createdAt = chunk.getCreatedAt();
        return vo;
    }

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

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
