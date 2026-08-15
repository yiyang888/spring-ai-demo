package cn.yiyang.springai.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 向量库统计信息
 */
@Schema(description = "向量库统计信息")
public class VectorStatsVO {

    @Schema(description = "文档总数", example = "5")
    private int totalDocuments;

    @Schema(description = "分块总数", example = "50")
    private int totalChunks;

    @Schema(description = "向量总数", example = "50")
    private int totalVectors;

    @Schema(description = "状态为 READY 的文档数", example = "4")
    private int readyDocuments;

    @Schema(description = "状态为 FAILED 的文档数", example = "1")
    private int failedDocuments;

    @Schema(description = "状态为 PROCESSING 的文档数", example = "0")
    private int processingDocuments;

    public int getTotalDocuments() { return totalDocuments; }
    public void setTotalDocuments(int totalDocuments) { this.totalDocuments = totalDocuments; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

    public int getTotalVectors() { return totalVectors; }
    public void setTotalVectors(int totalVectors) { this.totalVectors = totalVectors; }

    public int getReadyDocuments() { return readyDocuments; }
    public void setReadyDocuments(int readyDocuments) { this.readyDocuments = readyDocuments; }

    public int getFailedDocuments() { return failedDocuments; }
    public void setFailedDocuments(int failedDocuments) { this.failedDocuments = failedDocuments; }

    public int getProcessingDocuments() { return processingDocuments; }
    public void setProcessingDocuments(int processingDocuments) { this.processingDocuments = processingDocuments; }
}
