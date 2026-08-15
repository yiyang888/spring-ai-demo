package cn.yiyang.springai.model.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 文档处理状态
 */
@Schema(description = "文档处理状态", allowableValues = {"PENDING", "PROCESSING", "READY", "FAILED"})
public enum DocumentStatus {
    PENDING,      // 已创建，等待处理
    PROCESSING,   // 处理中（提取/切分/向量化）
    READY,        // 处理完成，可检索
    FAILED        // 处理失败
}
