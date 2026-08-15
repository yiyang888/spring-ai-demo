package cn.yiyang.springai.model.entity;

import cn.yiyang.springai.model.enums.IndexVersionStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 向量索引版本实体（对应 kb_index_version 表）
 */
@Schema(description = "向量索引版本")
public class KbIndexVersion {

    @Schema(description = "版本ID（自增）", example = "1")
    private Long id;

    @Schema(description = "版本标签", example = "v1")
    private String versionLabel;

    @Schema(description = "版本状态", example = "ACTIVE",
            allowableValues = {"BUILDING", "ACTIVE", "ARCHIVED"})
    private IndexVersionStatus status;

    @Schema(description = "版本描述", example = "embedding模型升级到v3")
    private String description;

    @Schema(description = "该版本向量数量", example = "50")
    private int vectorCount;

    @Schema(description = "该版本文档数量", example = "5")
    private int documentCount;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "激活时间（变为ACTIVE的时间）")
    private LocalDateTime activatedAt;

    @Schema(description = "归档时间（变为ARCHIVED的时间）")
    private LocalDateTime archivedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getVersionLabel() { return versionLabel; }
    public void setVersionLabel(String versionLabel) { this.versionLabel = versionLabel; }

    public IndexVersionStatus getStatus() { return status; }
    public void setStatus(IndexVersionStatus status) { this.status = status; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public int getVectorCount() { return vectorCount; }
    public void setVectorCount(int vectorCount) { this.vectorCount = vectorCount; }

    public int getDocumentCount() { return documentCount; }
    public void setDocumentCount(int documentCount) { this.documentCount = documentCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getActivatedAt() { return activatedAt; }
    public void setActivatedAt(LocalDateTime activatedAt) { this.activatedAt = activatedAt; }

    public LocalDateTime getArchivedAt() { return archivedAt; }
    public void setArchivedAt(LocalDateTime archivedAt) { this.archivedAt = archivedAt; }
}
