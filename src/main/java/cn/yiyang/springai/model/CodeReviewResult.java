package cn.yiyang.springai.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 代码审查结果（大模型返回的 JSON 会自动映射到这个类）
 * 使用 record 定义，简洁且不可变
 */
@Schema(description = "代码审查结果")
@JsonPropertyOrder({"summary", "overallSeverity", "issues"})
public record CodeReviewResult(
        @Schema(description = "审查总结") String summary,
        @Schema(description = "整体严重程度：高/中/低", example = "中") String overallSeverity,
        @Schema(description = "问题列表") List<Issue> issues
) {

    /**
     * 单个问题
     */
    @Schema(description = "单个审查问题")
    @JsonPropertyOrder({"issue", "category", "severity", "suggestion"})
    public record Issue(
            @Schema(description = "问题简述") String issue,
            @Schema(description = "类别：安全漏洞/代码规范/性能问题/设计缺陷") String category,
            @Schema(description = "严重程度：高/中/低") String severity,
            @Schema(description = "改进建议") String suggestion
    ) {}
}
