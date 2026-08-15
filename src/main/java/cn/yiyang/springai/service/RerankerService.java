package cn.yiyang.springai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 重排序服务（Reranker）
 *
 * 调用阿里云百炼 qwen3-rerank 模型，对召回的文档进行二次精排。
 * Cross-Encoder 模型联合编码 query 和 document，比双塔向量检索更精准。
 *
 * 使用场景：RRF 融合排序后、送入 LLM 生成前，对 topK 候选做二次重排序
 */
@Service
public class RerankerService {

    private static final Logger log = LoggerFactory.getLogger(RerankerService.class);
    private static final String RERANK_URL = "https://dashscope.aliyuncs.com/compatible-api/v1/reranks";
    private static final String MODEL = "qwen3-rerank";
    private static final String INSTRUCT = "Given a web search query, retrieve relevant passages that answer the query.";

    private final RestClient restClient;
    private final String apiKey;

    public RerankerService(@Value("${spring.ai.openai.api-key}") String apiKey) {
        this.apiKey = apiKey;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);   // 连接超时 5s
        factory.setReadTimeout(30000);     // 读取超时 30s
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 对文档列表进行重排序
     *
     * @param query     用户查询
     * @param documents 待排序的文档内容列表
     * @param topN      返回前N个结果
     * @return 重排序结果（按相关性降序），失败时返回 null（调用方保留原始顺序）
     */
    @SuppressWarnings("unchecked")
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }
        if (documents.size() == 1) {
            return List.of(new RerankResult(0, 1.0));
        }
        if (topN <= 0) {
            topN = documents.size();
        }

        try {
            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", MODEL);
            requestBody.put("query", query);
            requestBody.put("documents", documents);
            requestBody.put("top_n", Math.min(topN, documents.size()));
            requestBody.put("instruct", INSTRUCT);

            ResponseEntity<Map> response = restClient.post()
                    .uri(RERANK_URL)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body(requestBody)
                    .retrieve()
                    .toEntity(Map.class);

            Map<String, Object> body = response.getBody();
            if (body == null) {
                log.warn("[Reranker] 响应体为空");
                return null;
            }

            List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("results");
            if (results == null) {
                Map<String, Object> output = (Map<String, Object>) body.get("output");
                if (output != null) {
                    results = (List<Map<String, Object>>) output.get("results");
                }
            }

            if (results == null || results.isEmpty()) {
                log.warn("[Reranker] 结果列表为空");
                return null;
            }

            int docSize = documents.size();
            List<RerankResult> rerankResults = new ArrayList<>();
            for (Map<String, Object> r : results) {
                Object indexObj = r.get("index");
                Object scoreObj = r.get("relevance_score");
                if (indexObj == null || scoreObj == null) {
                    log.warn("[Reranker] 结果缺少 index 或 relevance_score 字段: {}", r);
                    continue;
                }
                int index = (indexObj instanceof Number)
                        ? ((Number) indexObj).intValue()
                        : Integer.parseInt(String.valueOf(indexObj));
                double score = (scoreObj instanceof Number)
                        ? ((Number) scoreObj).doubleValue()
                        : Double.parseDouble(String.valueOf(scoreObj));
                if (index < 0 || index >= docSize) {
                    log.warn("[Reranker] 越界 index={}, documents.size={}", index, docSize);
                    continue;
                }
                rerankResults.add(new RerankResult(index, score));
            }

            if (rerankResults.isEmpty()) {
                log.warn("[Reranker] 解析后结果为空");
                return null;
            }

            log.info("[Reranker] 重排序完成: {} 篇文档 → 返回 {} 篇, 首篇得分={}",
                    documents.size(), rerankResults.size(),
                    String.format("%.4f", rerankResults.get(0).getRelevanceScore()));

            return rerankResults;

        } catch (Exception e) {
            log.warn("[Reranker] 重排序失败，将使用原始排序: {}", e.getMessage());
            return null;
        }
    }

    public static class RerankResult {
        private final int index;
        private final double relevanceScore;

        public RerankResult(int index, double relevanceScore) {
            this.index = index;
            this.relevanceScore = relevanceScore;
        }

        public int getIndex() { return index; }
        public double getRelevanceScore() { return relevanceScore; }
    }
}
