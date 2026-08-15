package cn.yiyang.langchain4j.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * LangChain4j 结构化输出的 Bean
 * 和 Spring AI 的 record 不同，LangChain4j 需要用标准 Java Bean（getter/setter）
 * 或者用 record 也可以，但字段名要和 LLM 返回的 JSON key 对应
 */
@Schema(description = "代码审查结果（LangChain4j 版本）")
public record CodeReviewResult(
        @Schema(description = "审查总结") String summary,
        @Schema(description = "整体严重程度：高/中/低") String overallseverity,
        @Schema(description = "问题列表") List<Issue> issues
) {
    @Schema(description = "单个审查问题")
    public record Issue(
            @Schema(description = "问题简述") String issue,
            @Schema(description = "类别：安全漏洞/代码规范/性能问题/设计缺陷") String category,
            @Schema(description = "严重程度：高/中/低") String serverity,
            @Schema(description = "改进建议") String suggestion
    ) {}
}
