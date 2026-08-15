package cn.yiyang.springai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 模型不可用时的降级处理
 *
 * 三层降级策略（按优先级递减）：
 *
 *   第一层  检索兜底    LLM 不可用时，跳过生成，直接把向量检索到的文档片段返回给用户
 *                      用户至少能看到相关内容，而不是报错白屏
 *
 *   第二层  本地小模型   可选：配置了备用模型（如 Ollama）时，用小模型生成简化回答
 *                      需在 application.yml 中开启 kb.backup-model.enabled=true
 *
 *   第三层  引导留言    连向量检索也失败时，返回友好的离线提示，引导用户稍后重试
 *
 * 使用方式：
 *   在 VectorIndexService.ask() 中，大模型调用 try-catch → degradeHandler.retrievalFallback()
 *   在向量检索 try-catch → degradeHandler.offlineFallback()
 */
@Service
public class DegradeHandler {

    private static final Logger log = LoggerFactory.getLogger(DegradeHandler.class);

    @Value("${kb.backup-model.enabled:false}")
    private boolean backupModelEnabled;

    // ========== 第一层：检索兜底 ==========

    /**
     * 检索兜底：大模型不可用时，仅返回向量检索到的相关文档片段
     *
     * @param question  用户问题
     * @param docs      向量检索到的文档列表（非空）
     * @param modelError 大模型错误信息
     * @return 降级响应（degradeLevel=RETRIEVAL_FALLBACK）
     */
    public Map<String, Object> retrievalFallback(String question, List<Document> docs, String modelError) {
        if (docs == null) {
            docs = Collections.emptyList();
        }
        log.warn("[降级-检索兜底] LLM 不可用，返回检索结果: question={}, chunks={}, error={}",
                question, docs.size(), modelError);

        StringBuilder answer = new StringBuilder();
        answer.append("⚠️ AI 模型暂时不可用，无法生成智能回答。\n\n");
        answer.append("以下是根据您的问题检索到的相关文档内容：\n\n");
        for (int i = 0; i < docs.size(); i++) {
            answer.append("【段落").append(i + 1).append("】\n");
            answer.append(docs.get(i).getText()).append("\n\n");
        }
        answer.append("—— 以上内容来自知识库检索，AI 模型恢复后将提供更精准的回答。");

        List<Map<String, Object>> sources = new ArrayList<>();
        for (Document doc : docs) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("content", doc.getText());
            s.put("score", doc.getMetadata().get("distance"));
            s.put("source", doc.getMetadata().get("source"));
            sources.add(s);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("answer", answer.toString());
        result.put("retrievedChunks", docs.size());
        result.put("sources", sources);
        result.put("degradeLevel", "RETRIEVAL_FALLBACK");
        result.put("degradeMessage", "AI 模型不可用，已返回检索结果作为参考");
        // 安全策略：原始错误信息仅记录到日志，不透传给前端（防止泄露 API key、堆栈等敏感信息）
        result.put("cached", false);
        return result;
    }

    // ========== 第二层：本地小模型应急（可选） ==========

    /**
     * 判断是否配置了备用本地模型
     */
    public boolean isBackupModelEnabled() {
        return backupModelEnabled;
    }

    // ========== 第三层：引导留言 ==========

    /**
     * 引导留言：连向量检索也失败时，返回友好的离线提示
     *
     * @param question 用户问题
     * @param error    错误信息
     * @return 降级响应（degradeLevel=OFFLINE）
     */
    public Map<String, Object> offlineFallback(String question, String error) {
        log.error("[降级-离线] 向量检索也不可用: question={}, error={}", question, error);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("answer",
                "⚠️ 知识库服务暂时不可用，请稍后重试。\n\n" +
                "您可以尝试以下操作：\n" +
                "  1. 等待几分钟后重新提问\n" +
                "  2. 检查向量数据库（PgVector）是否正常运行\n" +
                "  3. 检查 AI 模型服务是否可用\n" +
                "  4. 联系管理员排查问题");
        result.put("retrievedChunks", 0);
        result.put("sources", Collections.emptyList());
        result.put("degradeLevel", "OFFLINE");
        result.put("degradeMessage", "知识库服务不可用，请稍后重试");
        // 安全策略：原始错误信息仅记录到日志，不透传给前端
        result.put("cached", false);
        return result;
    }

    /**
     * 检索服务降级：向量检索不可用时返回空结果 + 引导信息
     */
    public Map<String, Object> searchOfflineFallback(String query, String error) {
        log.error("[降级-检索离线] 向量检索不可用: query={}, error={}", query, error);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("results", Collections.emptyList());
        result.put("count", 0);
        result.put("degradeLevel", "OFFLINE");
        result.put("degradeMessage", "向量检索服务不可用，请稍后重试");
        // 安全策略：原始错误信息仅记录到日志，不透传给前端
        result.put("cached", false);
        return result;
    }
}
