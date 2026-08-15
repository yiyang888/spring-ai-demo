package cn.yiyang.springai.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "岗位分析结果")
@JsonPropertyOrder({"jobTitle", "company", "salaryRange", "matchScore", "matchedSkills",
        "missingSkills", "suggestions"})
public record JobAnalysisResult(
        @Schema(description = "岗位名称", example = "Java后端开发工程师") String jobTitle,
        @Schema(description = "公司名称", example = "某互联网公司") String company,
        @Schema(description = "薪资范围", example = "15-25K·14薪") String salaryRange,
        @Schema(description = "匹配度评分 0-100", example = "75") int matchScore,
        @Schema(description = "已具备的技能") List<String> matchedSkills,
        @Schema(description = "缺少的技能") List<String> missingSkills,
        @Schema(description = "补强建议") List<String> suggestions
) {}
