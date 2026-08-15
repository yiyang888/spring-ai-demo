package cn.yiyang.repository;

import cn.yiyang.springai.model.entity.KbChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 分块内容数据访问（JdbcTemplate）
 */
@Repository
public class ChunkRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<KbChunk> rowMapper = (rs, rowNum) -> {
        KbChunk chunk = new KbChunk();
        chunk.setId(rs.getLong("id"));
        chunk.setDocumentId(rs.getLong("document_id"));
        chunk.setChunkIndex(rs.getInt("chunk_index"));
        chunk.setContent(rs.getString("content"));
        chunk.setContentLength(rs.getInt("content_length"));
        chunk.setTokenCount(rs.getInt("token_count"));
        chunk.setVectorId(rs.getString("vector_id"));
        chunk.setMetadata(rs.getString("metadata"));
        Timestamp created = rs.getTimestamp("created_at");
        chunk.setCreatedAt(created != null ? created.toLocalDateTime() : null);
        return chunk;
    };

    /** 新增单个分块，返回带 id 的实体 */
    public KbChunk save(KbChunk chunk) {
        String sql = """
                INSERT INTO kb_chunk (document_id, chunk_index, content, content_length, token_count, vector_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setLong(1, chunk.getDocumentId());
            ps.setInt(2, chunk.getChunkIndex());
            ps.setString(3, chunk.getContent());
            ps.setInt(4, chunk.getContentLength() != null ? chunk.getContentLength() : 0);
            ps.setInt(5, chunk.getTokenCount() != null ? chunk.getTokenCount() : 0);
            ps.setString(6, chunk.getVectorId());
            return ps;
        }, keyHolder);
        chunk.setId(keyHolder.getKey().longValue());
        return chunk;
    }

    /** 按文档 id 查询所有分块（按 chunk_index 排序） */
    public List<KbChunk> findByDocumentId(Long documentId) {
        String sql = """
                SELECT id, document_id, chunk_index, content, content_length, token_count,
                    vector_id, metadata, created_at
                FROM kb_chunk WHERE document_id = ? ORDER BY chunk_index
                """;
        return jdbcTemplate.query(sql, rowMapper, documentId);
    }

    /** 按 id 查询单个分块 */
    public KbChunk findById(Long id) {
        String sql = """
                SELECT id, document_id, chunk_index, content, content_length, token_count,
                    vector_id, metadata, created_at
                FROM kb_chunk WHERE id = ?
                """;
        List<KbChunk> list = jdbcTemplate.query(sql, rowMapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 更新分块内容（修改文本后调用） */
    public void updateContent(Long id, String content, int contentLength) {
        jdbcTemplate.update(
                "UPDATE kb_chunk SET content = ?, content_length = ? WHERE id = ?",
                content, contentLength, id);
    }

    /** 更新分块关联的向量 id */
    public void updateVectorId(Long id, String vectorId) {
        jdbcTemplate.update(
                "UPDATE kb_chunk SET vector_id = ? WHERE id = ?",
                vectorId, id);
    }

    /** 删除单个分块 */
    public void deleteById(Long id) {
        jdbcTemplate.update("DELETE FROM kb_chunk WHERE id = ?", id);
    }

    /** 删除文档的所有分块 */
    public void deleteByDocumentId(Long documentId) {
        jdbcTemplate.update("DELETE FROM kb_chunk WHERE document_id = ?", documentId);
    }

    /** 统计文档的分块数 */
    public int countByDocumentId(Long documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk WHERE document_id = ?",
                Integer.class, documentId);
        return count != null ? count : 0;
    }

    /** 统计全部分块数 */
    public int countAll() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_chunk", Integer.class);
        return count != null ? count : 0;
    }
}
