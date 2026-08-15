package cn.yiyang.repository;

import cn.yiyang.springai.model.entity.KbDocument;
import cn.yiyang.springai.model.enums.DocumentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 文档元数据数据访问（JdbcTemplate）
 */
@Repository
public class DocumentRepository {

    private final JdbcTemplate jdbcTemplate;

    public DocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<KbDocument> rowMapper = (rs, rowNum) -> {
        KbDocument doc = new KbDocument();
        doc.setId(rs.getLong("id"));
        doc.setTitle(rs.getString("title"));
        doc.setFileName(rs.getString("file_name"));
        doc.setFileFormat(rs.getString("file_format"));
        doc.setFileSize(rs.getLong("file_size"));
        doc.setContentHash(rs.getString("content_hash"));
        doc.setDescription(rs.getString("description"));
        String status = rs.getString("status");
        if (status != null) {
            doc.setStatus(DocumentStatus.valueOf(status));
        }
        doc.setChunkCount(rs.getInt("chunk_count"));
        doc.setTotalTokens(rs.getInt("total_tokens"));
        doc.setChunkSize(rs.getObject("chunk_size") != null ? rs.getInt("chunk_size") : null);
        doc.setOverlap(rs.getObject("overlap") != null ? rs.getInt("overlap") : null);
        doc.setSplitterType(rs.getString("splitter_type"));
        doc.setRawText(rs.getString("raw_text"));
        doc.setErrorMessage(rs.getString("error_message"));
        doc.setCategory(rs.getString("category"));
        doc.setAuthor(rs.getString("author"));
        Date docDate = rs.getDate("doc_date");
        doc.setDocDate(docDate != null ? docDate.toLocalDate() : null);
        Timestamp created = rs.getTimestamp("created_at");
        doc.setCreatedAt(created != null ? created.toLocalDateTime() : null);
        Timestamp updated = rs.getTimestamp("updated_at");
        doc.setUpdatedAt(updated != null ? updated.toLocalDateTime() : null);
        return doc;
    };

    /** 新增文档记录，返回带 id 的实体 */
    public KbDocument save(KbDocument doc) {
        String sql = """
                INSERT INTO kb_document (title, file_name, file_format, file_size, content_hash,
                    description, status, chunk_count, total_tokens, chunk_size, overlap,
                    splitter_type, raw_text, error_message, category, author, doc_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, doc.getTitle());
            ps.setString(2, doc.getFileName());
            ps.setString(3, doc.getFileFormat());
            ps.setLong(4, doc.getFileSize() != null ? doc.getFileSize() : 0);
            ps.setString(5, doc.getContentHash());
            ps.setString(6, doc.getDescription());
            ps.setString(7, doc.getStatus() != null ? doc.getStatus().name() : DocumentStatus.PENDING.name());
            ps.setInt(8, doc.getChunkCount() != null ? doc.getChunkCount() : 0);
            ps.setInt(9, doc.getTotalTokens() != null ? doc.getTotalTokens() : 0);
            ps.setObject(10, doc.getChunkSize());
            ps.setObject(11, doc.getOverlap());
            ps.setString(12, doc.getSplitterType() != null ? doc.getSplitterType() : "RECURSIVE");
            ps.setString(13, doc.getRawText());
            ps.setString(14, doc.getErrorMessage());
            ps.setString(15, doc.getCategory());
            ps.setString(16, doc.getAuthor());
            ps.setDate(17, doc.getDocDate() != null ? Date.valueOf(doc.getDocDate()) : null);
            return ps;
        }, keyHolder);
        doc.setId(keyHolder.getKey().longValue());
        return doc;
    }

    /** 按 id 查询（不含 raw_text 大字段） */
    public KbDocument findById(Long id) {
        String sql = """
                SELECT id, title, file_name, file_format, file_size, content_hash, description,
                    status, chunk_count, total_tokens, chunk_size, overlap, splitter_type,
                    NULL AS raw_text, error_message, category, author, doc_date, created_at, updated_at
                FROM kb_document WHERE id = ?
                """;
        List<KbDocument> list = jdbcTemplate.query(sql, rowMapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按 id 查询（含 raw_text，用于重新切分） */
    public KbDocument findByIdWithRawText(Long id) {
        String sql = """
                SELECT id, title, file_name, file_format, file_size, content_hash, description,
                    status, chunk_count, total_tokens, chunk_size, overlap, splitter_type,
                    raw_text, error_message, category, author, doc_date, created_at, updated_at
                FROM kb_document WHERE id = ?
                """;
        List<KbDocument> list = jdbcTemplate.query(sql, rowMapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 分页查询（status 为 null 时查全部） */
    public List<KbDocument> findAll(int offset, int limit, String status) {
        String sql;
        if (status != null && !status.isEmpty()) {
            sql = """
                SELECT id, title, file_name, file_format, file_size, content_hash, description,
                    status, chunk_count, total_tokens, chunk_size, overlap, splitter_type,
                    NULL AS raw_text, error_message, category, author, doc_date, created_at, updated_at
                FROM kb_document WHERE status = ?
                ORDER BY created_at DESC LIMIT ? OFFSET ?
                """;
            return jdbcTemplate.query(sql, rowMapper, status, limit, offset);
        } else {
            sql = """
                SELECT id, title, file_name, file_format, file_size, content_hash, description,
                    status, chunk_count, total_tokens, chunk_size, overlap, splitter_type,
                    NULL AS raw_text, error_message, category, author, doc_date, created_at, updated_at
                FROM kb_document
                ORDER BY created_at DESC LIMIT ? OFFSET ?
                """;
            return jdbcTemplate.query(sql, rowMapper, limit, offset);
        }
    }

    /** 总数（status 为 null 时查全部） */
    public int count(String status) {
        if (status != null && !status.isEmpty()) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM kb_document WHERE status = ?",
                    Integer.class, status);
            return count != null ? count : 0;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_document", Integer.class);
        return count != null ? count : 0;
    }

    /** 更新文档元数据（标题/描述/分类/来源/日期） */
    public void updateMetadata(Long id, String title, String description,
                                String category, String author, LocalDate docDate) {
        jdbcTemplate.update(
                "UPDATE kb_document SET title = ?, description = ?, category = ?, author = ?, doc_date = ?, " +
                        "updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                title, description, category, author,
                docDate != null ? Date.valueOf(docDate) : null, id);
    }

    /** 查询所有不重复的分类（用于前端下拉选择） */
    public List<String> findCategories() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT category FROM kb_document WHERE category IS NOT NULL AND category != '' ORDER BY category",
                String.class);
    }

    /** 查询所有不重复的来源/作者（用于前端下拉选择） */
    public List<String> findAuthors() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT author FROM kb_document WHERE author IS NOT NULL AND author != '' ORDER BY author",
                String.class);
    }

    /** 删除（kb_chunk 通过 FK CASCADE 自动删除） */
    public void deleteById(Long id) {
        jdbcTemplate.update("DELETE FROM kb_document WHERE id = ?", id);
    }

    /** 检查内容哈希是否已存在 */
    public boolean existsByContentHash(String contentHash) {
        if (contentHash == null || contentHash.isEmpty()) return false;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_document WHERE content_hash = ?",
                Integer.class, contentHash);
        return count != null && count > 0;
    }

    /** 更新文档状态 */
    public void updateStatus(Long id, DocumentStatus status, String errorMessage) {
        jdbcTemplate.update(
                "UPDATE kb_document SET status = ?, error_message = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                status.name(), errorMessage, id);
    }

    /** 更新分块统计信息 */
    public void updateChunkInfo(Long id, int chunkCount, int totalTokens) {
        jdbcTemplate.update(
                "UPDATE kb_document SET chunk_count = ?, total_tokens = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                chunkCount, totalTokens, id);
    }

    /** 更新切分参数 */
    public void updateSplitParams(Long id, int chunkSize, int overlap) {
        jdbcTemplate.update(
                "UPDATE kb_document SET chunk_size = ?, overlap = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                chunkSize, overlap, id);
    }

    /** 更新原始文本（重新切分时使用） */
    public void updateRawText(Long id, String rawText) {
        jdbcTemplate.update(
                "UPDATE kb_document SET raw_text = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                rawText, id);
    }

    /** 查询所有文档ID（按状态过滤，null/空 时查全部，用于批量重建） */
    public List<Long> findAllIds(String status) {
        if (status != null && !status.isEmpty()) {
            return jdbcTemplate.queryForList(
                    "SELECT id FROM kb_document WHERE status = ? ORDER BY id",
                    Long.class, status);
        }
        return jdbcTemplate.queryForList(
                "SELECT id FROM kb_document ORDER BY id",
                Long.class);
    }
}
