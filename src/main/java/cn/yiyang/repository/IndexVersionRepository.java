package cn.yiyang.repository;

import cn.yiyang.springai.model.entity.KbIndexVersion;
import cn.yiyang.springai.model.enums.IndexVersionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 向量索引版本数据访问（JdbcTemplate）
 */
@Repository
public class IndexVersionRepository {

    private final JdbcTemplate jdbcTemplate;

    public IndexVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<KbIndexVersion> rowMapper = (rs, rowNum) -> {
        KbIndexVersion v = new KbIndexVersion();
        v.setId(rs.getLong("id"));
        v.setVersionLabel(rs.getString("version_label"));
        String status = rs.getString("status");
        if (status != null) {
            v.setStatus(IndexVersionStatus.valueOf(status));
        }
        v.setDescription(rs.getString("description"));
        v.setVectorCount(rs.getInt("vector_count"));
        v.setDocumentCount(rs.getInt("document_count"));
        Timestamp created = rs.getTimestamp("created_at");
        v.setCreatedAt(created != null ? created.toLocalDateTime() : null);
        Timestamp activated = rs.getTimestamp("activated_at");
        v.setActivatedAt(activated != null ? activated.toLocalDateTime() : null);
        Timestamp archived = rs.getTimestamp("archived_at");
        v.setArchivedAt(archived != null ? archived.toLocalDateTime() : null);
        return v;
    };

    /** 创建新版本记录，返回带 id 的实体 */
    public KbIndexVersion save(String versionLabel, String description) {
        String sql = """
                INSERT INTO kb_index_version (version_label, status, description)
                VALUES (?, 'BUILDING', ?)
                """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, versionLabel);
            ps.setString(2, description);
            return ps;
        }, keyHolder);
        KbIndexVersion v = new KbIndexVersion();
        v.setId(keyHolder.getKey().longValue());
        v.setVersionLabel(versionLabel);
        v.setDescription(description);
        v.setStatus(IndexVersionStatus.BUILDING);
        return v;
    }

    /** 按 id 查询 */
    public KbIndexVersion findById(Long id) {
        String sql = """
                SELECT id, version_label, status, description, vector_count, document_count,
                    created_at, activated_at, archived_at
                FROM kb_index_version WHERE id = ?
                """;
        List<KbIndexVersion> list = jdbcTemplate.query(sql, rowMapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询当前 ACTIVE 版本（同一时间最多一个） */
    public KbIndexVersion findActive() {
        String sql = """
                SELECT id, version_label, status, description, vector_count, document_count,
                    created_at, activated_at, archived_at
                FROM kb_index_version WHERE status = 'ACTIVE'
                """;
        List<KbIndexVersion> list = jdbcTemplate.query(sql, rowMapper);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 查询所有版本（按 id 倒序，最新的在前） */
    public List<KbIndexVersion> findAll() {
        String sql = """
                SELECT id, version_label, status, description, vector_count, document_count,
                    created_at, activated_at, archived_at
                FROM kb_index_version ORDER BY id DESC
                """;
        return jdbcTemplate.query(sql, rowMapper);
    }

    /** 查询所有 ARCHIVED 版本（可用于回滚） */
    public List<KbIndexVersion> findArchived() {
        String sql = """
                SELECT id, version_label, status, description, vector_count, document_count,
                    created_at, activated_at, archived_at
                FROM kb_index_version WHERE status = 'ARCHIVED' ORDER BY id DESC
                """;
        return jdbcTemplate.query(sql, rowMapper);
    }

    /**
     * 原子切换版本：将当前 ACTIVE 版本归档，将目标版本激活
     * 在事务中执行，保证同一时间只有一个 ACTIVE 版本
     */
    public void activateVersion(Long targetVersionId) {
        // ① 当前 ACTIVE → ARCHIVED
        jdbcTemplate.update(
                "UPDATE kb_index_version SET status = 'ARCHIVED', archived_at = CURRENT_TIMESTAMP " +
                        "WHERE status = 'ACTIVE'"
        );
        // ② 目标版本 → ACTIVE
        jdbcTemplate.update(
                "UPDATE kb_index_version SET status = 'ACTIVE', activated_at = CURRENT_TIMESTAMP " +
                        "WHERE id = ?",
                targetVersionId
        );
    }

    /** 更新版本的向量数量和文档数量 */
    public void updateCounts(Long id, int vectorCount, int documentCount) {
        jdbcTemplate.update(
                "UPDATE kb_index_version SET vector_count = ?, document_count = ? WHERE id = ?",
                vectorCount, documentCount, id
        );
    }

    /** 删除版本记录 */
    public void deleteById(Long id) {
        jdbcTemplate.update("DELETE FROM kb_index_version WHERE id = ?", id);
    }

    /** 查询下一个版本标签（如当前最大是 v2，则返回 v3） */
    public String nextVersionLabel() {
        Integer maxId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM kb_index_version", Integer.class);
        int next = (maxId != null ? maxId : 0) + 1;
        return "v" + next;
    }
}
