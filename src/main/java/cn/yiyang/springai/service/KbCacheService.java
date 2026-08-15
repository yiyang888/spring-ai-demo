package cn.yiyang.springai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 知识库缓存服务
 *
 * 缓存两类高频查询结果：
 *   1. 语义检索结果（search）—— 缓存 query → 向量检索结果
 *   2. RAG 问答结果（ask）—— 缓存 question → AI 回答（更贵，缓存价值更高）
 *
 * 缓存 Key 设计：
 *   kb:cache:search:{versionId}:{md5(query)}:{topK}:{threshold}
 *   kb:cache:rag:{versionId}:{md5(question)}:{topK}:{threshold}
 *
 *   versionId 保证不同版本的缓存隔离（灰度切换/回滚后自动查新版本缓存）
 *
 * 缓存失效策略：
 *   - TTL 兜底：30 分钟自动过期（RAG 回答可能因为大模型随机性需要刷新）
 *   - 主动失效：知识库任何变更（上传/删除/修改文档/分块/版本切换）时调用 evictAll()
 *   - 随机抖动：TTL 加 0~5 分钟随机偏移，防止缓存雪崩（大量 key 同时过期）
 */
@Service
public class KbCacheService {

    private static final Logger log = LoggerFactory.getLogger(KbCacheService.class);

    /** 缓存 Key 前缀，evictAll 时按此前缀扫描清除 */
    private static final String CACHE_PREFIX = "kb:cache:";

    /** 检索缓存前缀 */
    private static final String SEARCH_PREFIX = "kb:cache:search:";

    /** RAG 问答缓存前缀 */
    private static final String RAG_PREFIX = "kb:cache:rag:";

    /** 基础 TTL（30 分钟） */
    private static final Duration BASE_TTL = Duration.ofMinutes(30);

    /** 随机抖动上限（5 分钟），防止缓存雪崩 */
    private static final Duration JITTER = Duration.ofMinutes(5);

    private final RedisTemplate<String, Object> redisTemplate;

    public KbCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ========== 1. 检索结果缓存 ==========

    /**
     * 获取检索缓存
     *
     * @param query              查询文本
     * @param topK               返回条数
     * @param similarityThreshold 相似度阈值
     * @param versionId          向量版本号（null 时用 "0"）
     * @param filterKey          元数据过滤签名（null/空 表示无过滤）
     * @return 缓存的检索结果，未命中返回 null
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSearchCache(String query, int topK, double similarityThreshold,
                                               Long versionId, String filterKey) {
        String key = buildSearchKey(query, topK, similarityThreshold, versionId, filterKey);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.info("[缓存命中] 检索缓存: query='{}', key={}", truncate(query), key);
            }
            return (Map<String, Object>) cached;
        } catch (Exception e) {
            log.warn("[缓存异常] 读取检索缓存失败，降级直查: {}", e.getMessage());
            return null;  // 缓存异常时降级，不影响主流程
        }
    }

    /**
     * 写入检索缓存
     */
    public void putSearchCache(String query, int topK, double similarityThreshold, Long versionId,
                               String filterKey, Map<String, Object> result) {
        String key = buildSearchKey(query, topK, similarityThreshold, versionId, filterKey);
        try {
            redisTemplate.opsForValue().set(key, result, randomTTL());
        } catch (Exception e) {
            log.warn("[缓存异常] 写入检索缓存失败: {}", e.getMessage());
        }
    }

    // ========== 2. RAG 问答缓存 ==========

    /**
     * 获取 RAG 问答缓存
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getRagCache(String question, int topK, double similarityThreshold,
                                            Long versionId, String filterKey) {
        String key = buildRagKey(question, topK, similarityThreshold, versionId, filterKey);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached != null) {
                log.info("[缓存命中] RAG缓存: question='{}', key={}", truncate(question), key);
            }
            return (Map<String, Object>) cached;
        } catch (Exception e) {
            log.warn("[缓存异常] 读取RAG缓存失败，降级直查: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 写入 RAG 问答缓存
     */
    public void putRagCache(String question, int topK, double similarityThreshold, Long versionId,
                            String filterKey, Map<String, Object> result) {
        String key = buildRagKey(question, topK, similarityThreshold, versionId, filterKey);
        try {
            redisTemplate.opsForValue().set(key, result, randomTTL());
        } catch (Exception e) {
            log.warn("[缓存异常] 写入RAG缓存失败: {}", e.getMessage());
        }
    }

    // ========== 3. 缓存失效 ==========

    /**
     * 清除所有知识库缓存
     *
     * 触发时机：
     *   - 文档上传 / 删除 / 重新向量化 / 重新切分
     *   - 分块修改 / 删除 / 追加
     *   - 向量全量重建 / 重试失败
     *   - 版本灰度切换 / 回滚 / 清理 / 初始化
     *
     * 实现方式：SCAN 扫描 kb:cache:* 前缀的 key，批量删除
     * （不用 KEYS 命令，避免阻塞 Redis）
     *
     * @return 清除的缓存数量
     */
    public int evictAll() {
        try {
            Set<String> keys = scanKeys(CACHE_PREFIX + "*");
            if (keys.isEmpty()) {
                return 0;
            }
            redisTemplate.delete(keys);
            log.info("[缓存清除] 已清除 {} 个知识库缓存 key", keys.size());
            return keys.size();
        } catch (Exception e) {
            log.warn("[缓存异常] 清除缓存失败: {}", e.getMessage());
            return 0;
        }
    }

    // ========== 私有工具方法 ==========

    /**
     * 构建检索缓存 Key
     * 格式：kb:cache:search:{versionId}:{md5(query+filterKey)}:{topK}:{threshold}
     * filterKey 参与哈希，保证不同过滤条件的缓存互不干扰
     */
    private String buildSearchKey(String query, int topK, double threshold, Long versionId, String filterKey) {
        String ver = versionId != null ? String.valueOf(versionId) : "0";
        String fk = (filterKey != null && !filterKey.isEmpty()) ? filterKey : "none";
        return SEARCH_PREFIX + ver + ":" + md5(normalize(query) + "|" + fk) + ":" + topK + ":" + formatThreshold(threshold);
    }

    /**
     * 构建 RAG 缓存 Key
     */
    private String buildRagKey(String question, int topK, double threshold, Long versionId, String filterKey) {
        String ver = versionId != null ? String.valueOf(versionId) : "0";
        String fk = (filterKey != null && !filterKey.isEmpty()) ? filterKey : "none";
        return RAG_PREFIX + ver + ":" + md5(normalize(question) + "|" + fk) + ":" + topK + ":" + formatThreshold(threshold);
    }

    /**
     * 文本归一化：trim + toLowerCase，提高缓存命中率
     */
    private String normalize(String text) {
        return text != null ? text.trim().toLowerCase() : "";
    }

    /**
     * 格式化阈值：保留 2 位小数，避免浮点数精度问题导致 Key 不一致
     */
    private String formatThreshold(double threshold) {
        return String.format("%.2f", threshold);
    }

    /**
     * 计算字符串 MD5 哈希（用于缓存 Key，避免长文本作为 Key）
     */
    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16);  // 取前 16 位足够区分
        } catch (Exception e) {
            log.warn("[KbCache] MD5 计算失败，降级为 hashCode: {}", e.getMessage());
            return String.valueOf(text.hashCode());
        }
    }

    /**
     * 随机 TTL：基础 30 分钟 + 0~5 分钟随机抖动，防止缓存雪崩
     */
    private Duration randomTTL() {
        long jitter = ThreadLocalRandom.current().nextLong(0, JITTER.toMinutes());
        return BASE_TTL.plusMinutes(jitter);
    }

    /**
     * 截断文本用于日志输出
     */
    private String truncate(String text) {
        if (text == null) return "null";
        return text.length() > 30 ? text.substring(0, 30) + "..." : text;
    }

    /**
     * 使用 SCAN 命令扫描匹配的 key（避免 KEYS 阻塞 Redis）
     */
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        redisTemplate.execute((connection) -> {
            var options = ScanOptions.scanOptions()
                    .match(pattern)
                    .count(100)
                    .build();
            try (var cursor = connection.keyCommands().scan(options)) {
                cursor.forEachRemaining(bytes -> keys.add(new String(bytes, StandardCharsets.UTF_8)));
            }
            return null;
        }, true);
        return keys;
    }
}
