package cn.yiyang.springai.model.dto;

/**
 * 元数据过滤条件（用于按来源/分类/作者/日期范围精准检索）
 *
 * 字段说明（对应向量 metadata 中的字段）：
 *   source   - 文件名来源（metadata.source）
 *   category - 文档分类（metadata.category）
 *   author   - 文档来源/作者（metadata.author）
 *   dateFrom - 文档日期起（metadata.docDate >= dateFrom，格式 yyyy-MM-dd）
 *   dateTo   - 文档日期止（metadata.docDate <= dateTo，格式 yyyy-MM-dd）
 *
 * 所有字段均可选，为空表示该维度不过滤。多个维度间为 AND 关系。
 */
public class MetadataFilter {

    private String source;
    private String category;
    private String author;
    private String dateFrom;
    private String dateTo;

    /** 是否存在任意过滤条件 */
    public boolean hasAny() {
        return notBlank(source) || notBlank(category) || notBlank(author)
                || notBlank(dateFrom) || notBlank(dateTo);
    }

    public boolean hasSource() { return notBlank(source); }
    public boolean hasCategory() { return notBlank(category); }
    public boolean hasAuthor() { return notBlank(author); }
    public boolean hasDateFrom() { return notBlank(dateFrom); }
    public boolean hasDateTo() { return notBlank(dateTo); }

    /** 去空格后的值（用于构建过滤表达式） */
    public String source() { return trim(source); }
    public String category() { return trim(category); }
    public String author() { return trim(author); }
    public String dateFrom() { return trim(dateFrom); }
    public String dateTo() { return trim(dateTo); }

    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    private static String trim(String s) { return s == null ? null : s.trim(); }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public String getDateFrom() { return dateFrom; }
    public void setDateFrom(String dateFrom) { this.dateFrom = dateFrom; }

    public String getDateTo() { return dateTo; }
    public void setDateTo(String dateTo) { this.dateTo = dateTo; }
}
