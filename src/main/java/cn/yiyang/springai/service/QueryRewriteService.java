package cn.yiyang.springai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Query 改写服务：用 LLM 将用户问题改写/扩展为多个视角的子查询
 *
 * 核心思路（RAG-Fusion 的第一步）：
 *   用户问"易杨毕业于哪里？" → LLM 改写出多个视角：
 *     ① 易杨的教育背景是什么？
 *     ② 易杨的毕业院校是哪所？
 *     ③ 易杨在学校学的是什么专业？
 *     ④ 易杨什么时候毕业的？
 *
 * 每个 Query 各自做向量检索，再用 RRF 融合 → 召回率大幅提升
 *
 * ★ 降级策略：LLM 改写失败时返回只含原始 Query 的列表，不影响主流程
 */
@Service
public class QueryRewriteService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteService.class);

    /** 最大改写 Query 数量（不含原始 Query） */
    private static final int MAX_REWRITES = 4;

    private final ChatClient chatClient;

    public QueryRewriteService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 改写用户问题，生成多个视角的子查询
     *
     * @param originalQuery 用户原始问题
     * @return 改写后的 Query 列表（包含原始 Query，放在第一位）
     */
    public RewriteResult rewrite(String originalQuery) {
        long startTime = System.currentTimeMillis();
        RewriteResult result = new RewriteResult();
        result.setOriginalQuery(originalQuery);

        List<String> rewritten;
        try {
            rewritten = callLlmRewrite(originalQuery);
        } catch (Exception e) {
            log.warn("Query 改写失败，降级为仅使用原始 Query: {}", e.getMessage());
            rewritten = List.of();
        }

        // 组装最终 Query 列表：原始 Query + 改写 Query（去重）
        Set<String> querySet = new LinkedHashSet<>();
        querySet.add(originalQuery);
        querySet.addAll(rewritten);

        List<String> allQueries = new ArrayList<>(querySet);
        result.setRewrittenQueries(rewritten);
        result.setAllQueries(allQueries);
        result.setElapsedMs(System.currentTimeMillis() - startTime);
        result.setSuccess(!rewritten.isEmpty());

        return result;
    }

    /**
     * 调用 LLM 改写 Query
     * Prompt 策略：要求 LLM 从不同视角改写问题，每行一个，不输出多余内容
     */
    private List<String> callLlmRewrite(String query) {
        String prompt = """
                你是一个搜索查询改写助手。请将用户的原始问题从不同角度改写成 %d 个等价的搜索查询，用于提升向量检索的召回率。

                改写规则：
                1. 每个改写查询必须和原始问题表达相同的意图，但用不同的措辞/视角
                2. 可以扩展同义词、换一种问法、拆解为子问题
                3. 保持简洁，每个改写不超过 30 个字
                4. 严格每行输出一个改写查询，不要编号、不要解释、不要多余文字

                原始问题：%s

                请直接输出 %d 个改写查询，每行一个：
                """.formatted(MAX_REWRITES, query, MAX_REWRITES);

        String response = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        if (response == null || response.isBlank()) {
            return List.of();
        }

        // 解析 LLM 输出：按行分割，去除空行和编号前缀
        List<String> queries = Arrays.stream(response.split("\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.replaceAll("^\\d+[.、)\\]]\\s*", ""))  // 去除 "1. " 等编号前缀
                .filter(s -> s.length() >= 2)  // 过滤太短的
                .distinct()
                .limit(MAX_REWRITES)
                .toList();

        log.info("Query 改写完成: 原始='{}' → 改写出 {} 条: {}", query, queries.size(), queries);
        return queries;
    }

    // ========== 结果类 ==========

    public static class RewriteResult {
        private String originalQuery;
        private List<String> rewrittenQueries;  // LLM 改写出的 Query（不含原始）
        private List<String> allQueries;         // 原始 + 改写，去重后的完整列表
        private long elapsedMs;
        private boolean success;

        public String getOriginalQuery() { return originalQuery; }
        public void setOriginalQuery(String originalQuery) { this.originalQuery = originalQuery; }

        public List<String> getRewrittenQueries() { return rewrittenQueries; }
        public void setRewrittenQueries(List<String> rewrittenQueries) { this.rewrittenQueries = rewrittenQueries; }

        public List<String> getAllQueries() { return allQueries; }
        public void setAllQueries(List<String> allQueries) { this.allQueries = allQueries; }

        public long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }

        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }
    }
}
