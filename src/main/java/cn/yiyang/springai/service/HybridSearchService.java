package cn.yiyang.springai.service;

import cn.yiyang.repository.IndexVersionRepository;
import cn.yiyang.springai.model.dto.MetadataFilter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 多路召回 + RRF 融合排序
 *
 * 三路召回通道：
 *   ① 向量检索（VECTOR）    语义匹配，找"意思相近"的文档
 *   ② 关键词检索（KEYWORD）  ILIKE 模糊匹配，找"包含关键词"的文档
 *   ③ 元数据检索（METADATA） 按 source 过滤后的向量检索，限定来源范围
 *
 * RRF 融合算法：
 *   score(d) = Σ 1/(k + rankᵢ(d))
 *   被多个通道共同命中的文档，RRF 分数更高，排名更靠前
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
    private static final int RRF_K = 60;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final IndexVersionRepository indexVersionRepository;
    private final QueryRewriteService queryRewriteService;
    private final RerankerService rerankerService;

    public HybridSearchService(VectorStore vectorStore, JdbcTemplate jdbcTemplate,
                               IndexVersionRepository indexVersionRepository,
                               QueryRewriteService queryRewriteService,
                               RerankerService rerankerService) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.indexVersionRepository = indexVersionRepository;
        this.queryRewriteService = queryRewriteService;
        this.rerankerService = rerankerService;
    }

    // ========== 内部结果类 ==========

    private static class DocResult {
        final String id;
        final String content;
        final Map<String, Object> metadata;
        final String channel;

        DocResult(String id, String content, Map<String, Object> metadata, String channel) {
            this.id = id;
            this.content = content;
            this.metadata = metadata;
            this.channel = channel;
        }
    }

    // ========== 公开方法 ==========

    /**
     * 多路召回 + RRF 融合排序（用于语义检索页面）
     *
     * @param query              用户查询
     * @param topK               每路召回数量
     * @param similarityThreshold 向量检索相似度阈值
     * @param filter              元数据过滤（按来源/分类/作者/日期过滤，可选）
     * @return 融合排序后的结果（含通道统计信息）
     */
    public Map<String, Object> hybridSearch(String query, int topK, double similarityThreshold,
                                             MetadataFilter filter, boolean rerank) {
        Long versionId = getActiveVersionId();
        long startTime = System.currentTimeMillis();

        // ① 三路召回
        List<DocResult> vectorResults = vectorSearch(query, topK, similarityThreshold, versionId, filter);
        List<DocResult> keywordResults = keywordSearch(query, topK, versionId, filter);
        List<DocResult> metadataResults = metadataSearch(query, topK, similarityThreshold, versionId, filter);

        // ② RRF 融合排序
        List<Map<String, Object>> fused = rrfFuse(
                List.of(vectorResults, keywordResults, metadataResults), topK);

        // ②.5 重排序（可选）：RRF 融合后用 Cross-Encoder 二次精排
        boolean reranked = false;
        if (rerank && !fused.isEmpty()) {
            List<Map<String, Object>> rerankedResults = applyRerank(query, fused, topK);
            if (rerankedResults != null) {
                fused = rerankedResults;
                reranked = true;
            }
        }

        log.info("[多路召回] query='{}', vector={}, keyword={}, metadata={}, fused={}, reranked={}",
                query, vectorResults.size(), keywordResults.size(), metadataResults.size(), fused.size(), reranked);

        // ③ 返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("results", fused);
        result.put("count", fused.size());
        result.put("searchMode", "HYBRID");
        result.put("channels", Map.of(
                "vector", vectorResults.size(),
                "keyword", keywordResults.size(),
                "metadata", metadataResults.size()
        ));
        result.put("reranked", reranked);
        if (reranked) {
            result.put("rerankModel", "qwen3-rerank");
        }
        result.put("elapsedMs", System.currentTimeMillis() - startTime);
        result.put("cached", false);
        result.put("degradeLevel", "NORMAL");
        return result;
    }

    /**
     * 多路召回（用于 RAG 问答，返回 Document 列表供 LLM 拼接上下文）
     *
     * @return 融合排序后的 Document 列表
     */
    public List<Document> hybridRetrieve(String query, int topK, double similarityThreshold,
                                          MetadataFilter filter, boolean rerank) {
        Long versionId = getActiveVersionId();

        List<DocResult> vectorResults = vectorSearch(query, topK, similarityThreshold, versionId, filter);
        List<DocResult> keywordResults = keywordSearch(query, topK, versionId, filter);
        List<DocResult> metadataResults = metadataSearch(query, topK, similarityThreshold, versionId, filter);

        List<Map<String, Object>> fused = rrfFuse(
                List.of(vectorResults, keywordResults, metadataResults), topK);

        // 重排序（可选）
        if (rerank && !fused.isEmpty()) {
            List<Map<String, Object>> rerankedResults = applyRerank(query, fused, topK);
            if (rerankedResults != null) {
                fused = rerankedResults;
            }
        }

        // 转换为 Spring AI Document 列表
        return fused.stream()
                .map(item -> new Document(
                        (String) item.get("content"),
                        (Map<String, Object>) item.get("metadata")))
                .collect(Collectors.toList());
    }

    // ========== Query 改写 + 多 Query 召回 + RRF 融合 ==========

    /**
     * Query 改写 + 多路召回 + RRF 融合（用于语义检索页面）
     *
     * 流程：原始 Query → LLM 改写出 N 个子 Query → 每个 Query 各做向量+关键词检索
     *       → 所有结果用 RRF 融合排序 → 被多个 Query 命中的文档排名更高
     *
     * @param query              用户原始查询
     * @param topK               每路召回数量
     * @param similarityThreshold 向量检索相似度阈值
     * @param filter              元数据过滤（按来源/分类/作者/日期过滤，可选）
     * @return 融合排序后的结果（含改写 Query 列表和通道统计信息）
     */
    public Map<String, Object> rewriteSearch(String query, int topK, double similarityThreshold,
                                              MetadataFilter filter, boolean rerank) {
        Long versionId = getActiveVersionId();
        long startTime = System.currentTimeMillis();

        // ① LLM 改写 Query
        QueryRewriteService.RewriteResult rewriteResult = queryRewriteService.rewrite(query);
        List<String> allQueries = rewriteResult.getAllQueries();

        log.info("[Query改写+多路召回] 原始='{}' → {} 条 Query: {}", query, allQueries.size(), allQueries);

        // ② 对每个 Query 做向量 + 关键词检索，收集所有通道结果
        List<List<DocResult>> allChannels = new ArrayList<>();
        for (String q : allQueries) {
            List<DocResult> vecResults = vectorSearch(q, topK, similarityThreshold, versionId, filter);
            List<DocResult> kwResults = keywordSearch(q, topK, versionId, filter);
            allChannels.add(vecResults);
            allChannels.add(kwResults);
        }

        // ③ RRF 融合所有通道
        List<Map<String, Object>> fused = rrfFuse(allChannels, topK);

        // ③.5 重排序（可选）
        boolean reranked = false;
        if (rerank && !fused.isEmpty()) {
            List<Map<String, Object>> rerankedResults = applyRerank(query, fused, topK);
            if (rerankedResults != null) {
                fused = rerankedResults;
                reranked = true;
            }
        }

        // ④ 统计各 Query 的召回数量
        List<Map<String, Object>> queryStats = new ArrayList<>();
        for (int i = 0; i < allQueries.size(); i++) {
            Map<String, Object> stat = new LinkedHashMap<>();
            stat.put("query", allQueries.get(i));
            stat.put("isOriginal", i == 0);
            stat.put("vectorCount", allChannels.get(i * 2).size());
            stat.put("keywordCount", allChannels.get(i * 2 + 1).size());
            queryStats.add(stat);
        }

        log.info("[Query改写+多路召回] fused={}, reranked={}, elapsed={}ms", fused.size(), reranked,
                System.currentTimeMillis() - startTime);

        // ⑤ 返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("results", fused);
        result.put("count", fused.size());
        result.put("searchMode", "REWRITE_HYBRID");
        result.put("originalQuery", query);
        result.put("rewrittenQueries", rewriteResult.getRewrittenQueries());
        result.put("allQueries", allQueries);
        result.put("rewriteSuccess", rewriteResult.isSuccess());
        result.put("rewriteElapsedMs", rewriteResult.getElapsedMs());
        result.put("queryStats", queryStats);
        result.put("reranked", reranked);
        if (reranked) {
            result.put("rerankModel", "qwen3-rerank");
        }
        result.put("elapsedMs", System.currentTimeMillis() - startTime);
        result.put("cached", false);
        result.put("degradeLevel", "NORMAL");
        return result;
    }

    /**
     * Query 改写 + 多路召回（用于 RAG 问答，返回 Document 列表供 LLM 拼接上下文）
     *
     * @return 融合排序后的 Document 列表 + 改写信息
     */
    public RewriteRetrieveResult rewriteRetrieve(String query, int topK, double similarityThreshold,
                                                   MetadataFilter filter, boolean rerank) {
        Long versionId = getActiveVersionId();

        // ① LLM 改写 Query
        QueryRewriteService.RewriteResult rewriteResult = queryRewriteService.rewrite(query);
        List<String> allQueries = rewriteResult.getAllQueries();

        // ② 对每个 Query 做向量 + 关键词检索
        List<List<DocResult>> allChannels = new ArrayList<>();
        for (String q : allQueries) {
            allChannels.add(vectorSearch(q, topK, similarityThreshold, versionId, filter));
            allChannels.add(keywordSearch(q, topK, versionId, filter));
        }

        // ③ RRF 融合
        List<Map<String, Object>> fused = rrfFuse(allChannels, topK);

        // ③.5 重排序（可选）
        if (rerank && !fused.isEmpty()) {
            List<Map<String, Object>> rerankedResults = applyRerank(query, fused, topK);
            if (rerankedResults != null) {
                fused = rerankedResults;
            }
        }

        // ④ 转换为 Document 列表
        List<Document> docs = fused.stream()
                .map(item -> new Document(
                        (String) item.get("content"),
                        (Map<String, Object>) item.get("metadata")))
                .collect(Collectors.toList());

        RewriteRetrieveResult result = new RewriteRetrieveResult();
        result.setDocuments(docs);
        result.setRewriteResult(rewriteResult);
        return result;
    }

    // ========== Reranker 重排序 ==========

    /**
     * 对 RRF 融合后的结果进行重排序
     *
     * @param query  用户查询
     * @param fused  RRF 融合后的结果列表
     * @param topN   返回前N个结果
     * @return 重排序后的结果列表（含 rerankScore），失败返回 null
     */
    private List<Map<String, Object>> applyRerank(String query, List<Map<String, Object>> fused, int topN) {
        try {
            List<String> documents = fused.stream()
                    .map(item -> (String) item.get("content"))
                    .collect(Collectors.toList());

            List<RerankerService.RerankResult> rerankResults = rerankerService.rerank(query, documents, topN);
            if (rerankResults == null) {
                return null;
            }

            List<Map<String, Object>> reranked = new ArrayList<>();
            for (RerankerService.RerankResult rr : rerankResults) {
                if (rr.getIndex() < 0 || rr.getIndex() >= fused.size()) {
                    log.warn("[applyRerank] 越界 index={}, fused.size={}", rr.getIndex(), fused.size());
                    continue;
                }
                Map<String, Object> item = fused.get(rr.getIndex());
                Map<String, Object> newItem = new LinkedHashMap<>(item);
                newItem.put("rerankScore", Math.round(rr.getRelevanceScore() * 10000.0) / 10000.0);
                reranked.add(newItem);
            }
            return reranked.isEmpty() ? null : reranked;
        } catch (Exception e) {
            log.warn("[applyRerank] 重排序异常，降级为原始排序: {}", e.getMessage());
            return null;
        }
    }

    // ========== 多 Query 召回结果包装类 ==========

    public static class RewriteRetrieveResult {
        private List<Document> documents;
        private QueryRewriteService.RewriteResult rewriteResult;

        public List<Document> getDocuments() { return documents; }
        public void setDocuments(List<Document> documents) { this.documents = documents; }

        public QueryRewriteService.RewriteResult getRewriteResult() { return rewriteResult; }
        public void setRewriteResult(QueryRewriteService.RewriteResult rewriteResult) { this.rewriteResult = rewriteResult; }
    }

    // ========== 通道 1：向量检索（语义匹配） ==========

    private List<DocResult> vectorSearch(String query, int topK, double threshold,
                                          Long versionId, MetadataFilter filter) {
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(threshold);

            // 按版本 + 元数据过滤
            Filter.Expression filterExp = buildFilter(versionId, filter);
            if (filterExp != null) {
                builder.filterExpression(filterExp);
            }

            List<Document> docs = vectorStore.similaritySearch(builder.build());
            return docs.stream()
                    .map(d -> new DocResult(d.getId(), d.getText(), d.getMetadata(), "VECTOR"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[多路召回-向量] 检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ========== 通道 2：关键词检索（ILIKE 模糊匹配） ==========

    private List<DocResult> keywordSearch(String query, int topK, Long versionId, MetadataFilter filter) {
        try {
            // 按空格拆分关键词，每个关键词独立匹配
            String[] keywords = query.trim().split("\\s+");
            if (keywords.length == 0 || keywords[0].isEmpty()) {
                return Collections.emptyList();
            }

            StringBuilder sql = new StringBuilder("SELECT id, content, metadata FROM kb_vector WHERE (");
            List<Object> params = new ArrayList<>();

            for (int i = 0; i < keywords.length; i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("content ILIKE ?");
                params.add("%" + keywords[i] + "%");
            }
            sql.append(")");

            if (versionId != null) {
                sql.append(" AND metadata->>'version' = ?");
                params.add(String.valueOf(versionId));
            }
            if (filter != null && filter.hasSource()) {
                sql.append(" AND metadata->>'source' = ?");
                params.add(filter.source());
            }
            if (filter != null) {
                if (filter.hasCategory()) {
                    sql.append(" AND metadata->>'category' = ?");
                    params.add(filter.category());
                }
                if (filter.hasAuthor()) {
                    sql.append(" AND metadata->>'author' = ?");
                    params.add(filter.author());
                }
                if (filter.hasDateFrom()) {
                    sql.append(" AND metadata->>'docDate' >= ?");
                    params.add(filter.dateFrom());
                }
                if (filter.hasDateTo()) {
                    sql.append(" AND metadata->>'docDate' <= ?");
                    params.add(filter.dateTo());
                }
            }
            sql.append(" LIMIT ?");
            params.add(topK);

            return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
                String id = rs.getString("id");
                String content = rs.getString("content");
                Map<String, Object> metadata = parseMetadata(rs.getString("metadata"));
                return new DocResult(id, content, metadata, "KEYWORD");
            }, params.toArray());
        } catch (Exception e) {
            log.warn("[多路召回-关键词] 检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ========== 通道 3：元数据过滤检索（按 source 限定范围后做向量检索） ==========

    private List<DocResult> metadataSearch(String query, int topK, double threshold,
                                            Long versionId, MetadataFilter filter) {
        // 没有任何元数据过滤条件时跳过这一路
        if (filter == null || !filter.hasAny()) {
            return Collections.emptyList();
        }

        try {
            // 组合 version + 所有元数据过滤条件（Op 链式组合，最后才 build）
            Filter.Expression filterExp = buildFilter(versionId, filter);
            if (filterExp == null) {
                return Collections.emptyList();
            }

            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .similarityThreshold(threshold)
                            .filterExpression(filterExp)
                            .build()
            );
            return docs.stream()
                    .map(d -> new DocResult(d.getId(), d.getText(), d.getMetadata(), "METADATA"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[多路召回-元数据] 检索失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ========== RRF 融合排序 ==========

    /**
     * Reciprocal Rank Fusion
     * score(d) = Σ 1/(k + rankᵢ(d))
     * 被多个通道共同命中的文档，分数累加，排名更高
     */
    private List<Map<String, Object>> rrfFuse(List<List<DocResult>> channels, int topK) {
        Map<String, DocResult> docMap = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Set<String>> hitChannels = new LinkedHashMap<>();

        for (List<DocResult> channel : channels) {
            for (int rank = 0; rank < channel.size(); rank++) {
                DocResult doc = channel.get(rank);
                String docId = doc.id;

                double rrfScore = 1.0 / (RRF_K + rank + 1);
                scores.merge(docId, rrfScore, Double::sum);
                docMap.putIfAbsent(docId, doc);
                hitChannels.computeIfAbsent(docId, k -> new LinkedHashSet<>()).add(doc.channel);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    String docId = e.getKey();
                    DocResult r = docMap.get(docId);
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("content", r.content);
                    item.put("metadata", r.metadata);
                    item.put("rrfScore", Math.round(e.getValue() * 10000.0) / 10000.0);
                    item.put("hitChannels", hitChannels.get(docId));
                    item.put("score", r.metadata.get("distance"));
                    return item;
                })
                .collect(Collectors.toList());
    }

    // ========== 辅助方法 ==========

    private Long getActiveVersionId() {
        var active = indexVersionRepository.findActive();
        return active != null ? active.getId() : null;
    }

    /**
     * 构建 version + 元数据（source/category/author/docDate）组合过滤表达式
     */
    private Filter.Expression buildFilter(Long versionId, MetadataFilter filter) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op combined = null;
        if (versionId != null) {
            combined = builder.eq("version", String.valueOf(versionId));
        }
        if (filter != null) {
            if (filter.hasSource()) {
                FilterExpressionBuilder.Op op = builder.eq("source", filter.source());
                combined = (combined != null) ? builder.and(combined, op) : op;
            }
            if (filter.hasCategory()) {
                FilterExpressionBuilder.Op op = builder.eq("category", filter.category());
                combined = (combined != null) ? builder.and(combined, op) : op;
            }
            if (filter.hasAuthor()) {
                FilterExpressionBuilder.Op op = builder.eq("author", filter.author());
                combined = (combined != null) ? builder.and(combined, op) : op;
            }
            if (filter.hasDateFrom()) {
                FilterExpressionBuilder.Op op = builder.gte("docDate", filter.dateFrom());
                combined = (combined != null) ? builder.and(combined, op) : op;
            }
            if (filter.hasDateTo()) {
                FilterExpressionBuilder.Op op = builder.lte("docDate", filter.dateTo());
                combined = (combined != null) ? builder.and(combined, op) : op;
            }
        }
        return combined != null ? combined.build() : null;
    }

    private Map<String, Object> parseMetadata(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[HybridSearch] metadata JSON 解析失败: {}", json);
            return new HashMap<>();
        }
    }
}
