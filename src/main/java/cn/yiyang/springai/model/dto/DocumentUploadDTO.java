package cn.yiyang.springai.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 文档上传参数
 */
@Schema(description = "文档上传参数")
public class DocumentUploadDTO {

    @Schema(description = "分块大小（字符数）", example = "500", defaultValue = "500")
    @Min(value = 1, message = "chunkSize 最小为 1")
    @Max(value = 10000, message = "chunkSize 最大为 10000")
    private int chunkSize = 500;

    @Schema(description = "重叠大小（字符数）", example = "100", defaultValue = "100")
    @Min(value = 0, message = "overlap 最小为 0")
    @Max(value = 1000, message = "overlap 最大为 1000")
    private int overlap = 100;

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    public int getOverlap() { return overlap; }
    public void setOverlap(int overlap) { this.overlap = overlap; }
}
