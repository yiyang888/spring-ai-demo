package cn.yiyang.springai.model.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 文档信息更新参数（只改标题/描述，不涉及文件内容）
 */
@Schema(description = "文档信息更新参数")
public class DocumentUpdateDTO {

    @Schema(description = "文档标题", example = "Java开发指南.pdf")
    @Size(max = 255, message = "标题长度不能超过 255 个字符")
    private String title;

    @Schema(description = "文档描述", example = "这是一份Java后端开发参考指南")
    @Size(max = 2000, message = "描述长度不能超过 2000 个字符")
    private String description;

    @Schema(description = "文档分类", example = "技术")
    @Size(max = 100, message = "分类长度不能超过 100 个字符")
    private String category;

    @Schema(description = "文档来源/作者", example = "公司官网")
    @Size(max = 200, message = "来源长度不能超过 200 个字符")
    private String author;

    @Schema(description = "文档日期（yyyy-MM-dd）", example = "2024-06-01")
    private String docDate;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getDocDate() { return docDate; }
    public void setDocDate(String docDate) { this.docDate = docDate; }
}
