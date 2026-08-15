package cn.yiyang.springai.service;

import cn.yiyang.repository.ChunkRepository;
import cn.yiyang.repository.DocumentRepository;
import cn.yiyang.repository.IndexVersionRepository;
import cn.yiyang.springai.model.dto.MetadataFilter;
import cn.yiyang.springai.model.entity.KbChunk;
import cn.yiyang.springai.model.entity.KbDocument;
import cn.yiyang.springai.model.enums.DocumentStatus;
import cn.yiyang.springai.model.vo.VectorStatsVO;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * 向量索引管理：向量化入库、语义检索、RAG 问答、向量删除、统计
 *
 * ★ 版本管理：所有向量在 metadata 中带 version 字段，检索/RAG 按当前 ACTIVE 版本过滤
 *   - 向量化时自动注入 version = 当前 ACTIVE 版本号
 *   - 检索时自动按 version 过滤，只返回当前版本的向量
 *   - 如果没有 ACTIVE 版本（首次使用），兼容旧数据（不加 version 过滤）
 */
@Service
public class VectorIndexService {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexService.class);

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ChunkRepository chunkRepository;
    private final DocumentRepository documentRepository;
    private final IndexVersionRepository indexVersionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final KbCacheService kbCacheService;
    private final DegradeHandler degradeHandler;
    private final HybridSearchService hybridSearchService;

    public VectorIndexService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder,
                              ChatMemory chatMemory,
                              ChunkRepository chunkRepository, DocumentRepository documentRepository,
                              IndexVersionRepository indexVersionRepository,
                              JdbcTemplate jdbcTemplate, KbCacheService kbCacheService,
                              DegradeHandler degradeHandler, HybridSearchService hybridSearchService) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
        this.chatMemory = chatMemory;
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.indexVersionRepository = indexVersionRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.kbCacheService = kbCacheService;
        this.degradeHandler = degradeHandler;
        this.hybridSearchService = hybridSearchService;
    }

    // ========== 版本管理辅助方法 ==========

    /**
     * 获取当前 ACTIVE 版本号
     * @return 版本ID，如果没有 ACTIVE 版本则返回 null（兼容旧数据）
     */
    public Long getActiveVersionId() {
        var active = indexVersionRepository.findActive();
        return active != null ? active.getId() : null;
    }

    /**
     * 向 Spring AI Document 注入元数据（source/document_id/chunk_id/chunkIndex/version + category/author/docDate）
     * category/author/docDate 用于检索时按元数据精准过滤
     */
    private void injectMetadata(Document springDoc, KbDocument doc, String chunkId, int chunkIndex, Long versionId) {
        Map<String, Object> metadata = springDoc.getMetadata();
        metadata.put("source", doc.getFileName());
        metadata.put("document_id", String.valueOf(doc.getId()));
        metadata.put("chunk_id", chunkId);
        metadata.put("chunkIndex", String.valueOf(chunkIndex));
        if (versionId != null) {
            metadata.put("version", String.valueOf(versionId));
        }
        if (doc.getCategory() != null && !doc.getCategory().isEmpty()) {
            metadata.put("category", doc.getCategory());
        }
        if (doc.getAuthor() != null && !doc.getAuthor().isEmpty()) {
            metadata.put("author", doc.getAuthor());
        }
        if (doc.getDocDate() != null) {
            metadata.put("docDate", doc.getDocDate().toString());
        }
    }

    private Filter.Expression buildSearchFilter(MetadataFilter filter) {
        Long versionId = getActiveVersionId();
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

    // ========== 1. 批量向量化并入库 ==========

    /**
     * 批量向量化入库：
     * 1. 给每个 Spring AI Document 补充 metadata（source / document_id / chunk_id / chunkIndex / version）
     * 2. 分批调用 vectorStore.add()（百炼 embedding 限制单次 batch <= 10）
     * 3. 入库后读取 Document.getId() 回写 kb_chunk.vector_id
     */
    public void embedAndStore(KbDocument doc, List<KbChunk> chunkEntities, List<Document> springDocs) {
        Long versionId = getActiveVersionId();

        // ① 补充 metadata
        for (int i = 0; i < springDocs.size(); i++) {
            Document springDoc = springDocs.get(i);
            KbChunk chunkEntity = chunkEntities.get(i);
            injectMetadata(springDoc, doc, String.valueOf(chunkEntity.getId()), i, versionId);
        }

        // ② 分批向量化入库
        int batchSize = 10;
        for (int i = 0; i < springDocs.size(); i += batchSize) {
            List<Document> batch = springDocs.subList(i, Math.min(i + batchSize, springDocs.size()));
            vectorStore.add(batch);

            // ③ 回写 vector_id 到 kb_chunk
            for (int j = 0; j < batch.size(); j++) {
                int idx = i + j;
                String vectorId = batch.get(j).getId();
                chunkRepository.updateVectorId(chunkEntities.get(idx).getId(), vectorId);
                chunkEntities.get(idx).setVectorId(vectorId);
            }
        }
    }

    // ========== 2. 语义检索 ==========

    /**
     * 纯向量检索：query → embedding → PgVector 余弦相似度排序 → 返回 topK
     * ★ 自动按当前 ACTIVE 版本过滤
     * ★ Redis 缓存：高频查询直接返回缓存结果，跳过 embedding + 向量检索
     *
     * @return Map 包含 query / results / count / cached 字段
     */
    public Map<String, Object> search(String query, int topK, double similarityThreshold, MetadataFilter filter) {
        Long versionId = getActiveVersionId();

        // ① 查缓存
        Map<String, Object> cached = kbCacheService.getSearchCache(query, topK, similarityThreshold, versionId, filterSignature(filter));
        if (cached != null) {
            cached.put("cached", true);
            return cached;
        }

        // ② 未命中，执行向量检索（★ 带降级：检索失败 → 引导留言）
        List<Document> results;
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(similarityThreshold);

            // ★ 按版本 + 来源文档过滤
            Filter.Expression filterExpr = buildSearchFilter(filter);
            if (filterExpr != null) {
                builder.filterExpression(filterExpr);
            }

            results = vectorStore.similaritySearch(builder.build());
        } catch (Exception e) {
            // ★ 降级：向量检索不可用 → 返回引导信息
            return degradeHandler.searchOfflineFallback(query, e.getMessage());
        }

        List<Map<String, Object>> list = new ArrayList<>();
        for (Document doc : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("content", doc.getText());
            item.put("metadata", doc.getMetadata());
            item.put("score", doc.getMetadata().get("distance"));
            list.add(item);
        }

        // ③ 包装结果并写入缓存
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("results", list);
        result.put("count", list.size());
        result.put("degradeLevel", "NORMAL");
        result.put("cached", false);
        kbCacheService.putSearchCache(query, topK, similarityThreshold, versionId, filterSignature(filter), result);
        return result;
    }

    // ========== 3. RAG 问答 ==========

    /**
     * RAG 问答：向量检索相关段落 → 拼接上下文 → 大模型生成回答
     * ★ 自动按当前 ACTIVE 版本过滤
     * ★ Redis 缓存：高频问题直接返回缓存的 AI 回答，跳过 embedding + 检索 + LLM 调用（最贵）
     * ★ 多轮对话：传入 conversationId 时启用 ChatMemory，自动带入历史对话上下文
     *
     * @param conversationId 会话 ID（可选），传入时启用多轮对话记忆，跳过缓存
     */
    public Map<String, Object> ask(String question, int topK, double similarityThreshold,
                                   MetadataFilter filter, String conversationId) {
        Long versionId = getActiveVersionId();
        boolean useMemory = conversationId != null && !conversationId.isBlank();

        // ① 查缓存（多轮对话不缓存，因为答案依赖历史上下文）
        if (!useMemory) {
            Map<String, Object> cached = kbCacheService.getRagCache(question, topK, similarityThreshold, versionId, filterSignature(filter));
            if (cached != null) {
                cached.put("cached", true);
                return cached;
            }
        }

        // ② 未命中，执行向量检索（★ 带降级：检索失败 → 引导留言）
        List<Document> docs;
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                    .query(question)
                    .topK(topK)
                    .similarityThreshold(similarityThreshold);

            // ★ 按版本 + 来源文档过滤
            Filter.Expression filterExpr = buildSearchFilter(filter);
            if (filterExpr != null) {
                builder.filterExpression(filterExpr);
            }

            docs = vectorStore.similaritySearch(builder.build());
        } catch (Exception e) {
            // ★ 第三层降级：向量检索也失败 → 引导留言
            return degradeHandler.offlineFallback(question, e.getMessage());
        }

        // ③ 如果没有检索到相关内容，直接返回（也缓存空结果，避免重复检索）
        if (docs.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("question", question);
            result.put("answer", "根据文档，无法回答这个问题（未检索到相关内容，相似度阈值=" + similarityThreshold + "）");
            result.put("retrievedChunks", 0);
            result.put("sources", Collections.emptyList());
            result.put("cached", false);
            kbCacheService.putRagCache(question, topK, similarityThreshold, versionId, filterSignature(filter), result);
            return result;
        }

        // ④ 构造 RAG prompt（上下文拼接 + prompt 模板统一由辅助方法处理）
        String prompt = buildRagPrompt(buildContext(docs), question);

        // ⑤ 大模型生成（★ 带降级：LLM 失败 → 检索兜底）
        String answer;
        try {
            answer = generateAnswer(prompt, useMemory, conversationId);
        } catch (Exception e) {
            // ★ 第一层降级：LLM 不可用 → 跳过生成，直接返回检索到的文档片段
            return degradeHandler.retrievalFallback(question, docs, e.getMessage());
        }

        // ⑦ 返回结果并写入缓存（多轮对话不缓存）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("answer", answer);
        result.put("retrievedChunks", docs.size());
        result.put("sources", buildSources(docs));
        result.put("degradeLevel", "NORMAL");
        result.put("cached", false);
        if (!useMemory) {
            kbCacheService.putRagCache(question, topK, similarityThreshold, versionId, filterSignature(filter), result);
        }
        return result;
    }

    // ========== 3.5 多路召回（Hybrid Search） ==========

    /**
     * 多路召回检索（向量 + 关键词 + 元数据过滤 + RRF 融合排序）
     *
     * 三路并行召回 → RRF 融合排序 → 返回 topK 去重结果
     */
    public Map<String, Object> hybridSearch(String query, int topK, double similarityThreshold,
                                             MetadataFilter filter, boolean rerank) {
        return hybridSearchService.hybridSearch(query, topK, similarityThreshold, filter, rerank);
    }

    /**
     * 多路召回 RAG 问答（用多路召回替代单路向量检索，其余流程同 ask()）
     *
     * 多路召回 → RRF 融合 → 拼接上下文 → LLM 生成
     */
    public Map<String, Object> hybridAsk(String question, int topK, double similarityThreshold,
                                          MetadataFilter filter, boolean rerank, String conversationId) {
        Long versionId = getActiveVersionId();
        boolean useMemory = conversationId != null && !conversationId.isBlank();

        // ① 多路召回（向量 + 关键词 + 元数据，RRF 融合 + 可选重排序）
        List<Document> docs;
        try {
            docs = hybridSearchService.hybridRetrieve(question, topK, similarityThreshold, filter, rerank);
        } catch (Exception e) {
            // ★ 第三层降级：检索失败 → 引导留言
            return degradeHandler.offlineFallback(question, e.getMessage());
        }

        // ② 如果没有检索到相关内容
        if (docs.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("question", question);
            result.put("answer", "根据文档，无法回答这个问题（多路召回未检索到相关内容）");
            result.put("retrievedChunks", 0);
            result.put("sources", Collections.emptyList());
            result.put("degradeLevel", "NORMAL");
            result.put("searchMode", "HYBRID");
            result.put("cached", false);
            return result;
        }

        // ③ 构造 RAG prompt
        String prompt = buildRagPrompt(buildContext(docs), question);

        // ④ 大模型生成（★ 带降级：LLM 失败 → 检索兜底）
        String answer;
        try {
            answer = generateAnswer(prompt, useMemory, conversationId);
        } catch (Exception e) {
            // ★ 第一层降级：LLM 不可用 → 返回检索结果
            return degradeHandler.retrievalFallback(question, docs, e.getMessage());
        }

        // ⑤ 返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("answer", answer);
        result.put("retrievedChunks", docs.size());
        result.put("sources", buildSources(docs));
        result.put("degradeLevel", "NORMAL");
        result.put("searchMode", "HYBRID");
        result.put("reranked", rerank && !docs.isEmpty());
        result.put("cached", false);
        return result;
    }

    // ========== 3.6 Query 改写 + 多 Query 召回（RAG-Fusion） ==========

    /**
     * Query 改写检索（LLM 改写 + 多 Query 向量/关键词召回 + RRF 融合）
     */
    public Map<String, Object> rewriteSearch(String query, int topK, double similarityThreshold,
                                              MetadataFilter filter, boolean rerank) {
        return hybridSearchService.rewriteSearch(query, topK, similarityThreshold, filter, rerank);
    }

    /**
     * Query 改写 RAG 问答（LLM 改写 Query → 多 Query 召回 → RRF 融合 → LLM 生成回答）
     *
     * 这是 RAG-Fusion 的完整实现：
     *   原始问题 → LLM 改写出 N 个子问题 → 每个子问题各自检索 → RRF 融合 → LLM 生成
     */
    public Map<String, Object> rewriteAsk(String question, int topK, double similarityThreshold,
                                           MetadataFilter filter, boolean rerank, String conversationId) {
        Long versionId = getActiveVersionId();
        boolean useMemory = conversationId != null && !conversationId.isBlank();

        // ① Query 改写 + 多路召回 + RRF 融合 + 可选重排序
        HybridSearchService.RewriteRetrieveResult retrieveResult;
        List<Document> docs;
        try {
            retrieveResult = hybridSearchService.rewriteRetrieve(
                    question, topK, similarityThreshold, filter, rerank);
            docs = retrieveResult.getDocuments();
        } catch (Exception e) {
            // ★ 降级：检索失败 → 引导留言
            return degradeHandler.offlineFallback(question, e.getMessage());
        }

        // ② 如果没有检索到相关内容
        if (docs.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("question", question);
            result.put("answer", "根据文档，无法回答这个问题（Query 改写 + 多路召回未检索到相关内容）");
            result.put("retrievedChunks", 0);
            result.put("sources", Collections.emptyList());
            result.put("degradeLevel", "NORMAL");
            result.put("searchMode", "REWRITE_HYBRID");
            result.put("originalQuery", question);
            result.put("rewrittenQueries", retrieveResult.getRewriteResult().getRewrittenQueries());
            result.put("cached", false);
            return result;
        }

        // ③ 构造 RAG prompt（用原始问题提问，上下文来自多 Query 召回）
        String prompt = buildRagPrompt(buildContext(docs), question);

        // ④ 大模型生成（★ 带降级：LLM 失败 → 检索兜底）
        String answer;
        try {
            answer = generateAnswer(prompt, useMemory, conversationId);
        } catch (Exception e) {
            // ★ 第一层降级：LLM 不可用 → 返回检索结果
            return degradeHandler.retrievalFallback(question, docs, e.getMessage());
        }

        // ⑤ 返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);
        result.put("answer", answer);
        result.put("retrievedChunks", docs.size());
        result.put("sources", buildSources(docs));
        result.put("degradeLevel", "NORMAL");
        result.put("searchMode", "REWRITE_HYBRID");
        result.put("reranked", rerank && !docs.isEmpty());
        result.put("originalQuery", question);
        result.put("rewrittenQueries", retrieveResult.getRewriteResult().getRewrittenQueries());
        result.put("allQueries", retrieveResult.getRewriteResult().getAllQueries());
        result.put("rewriteSuccess", retrieveResult.getRewriteResult().isSuccess());
        result.put("cached", false);
        return result;
    }

    /**
     * 按 document_id 删除该文档的所有向量（所有版本，metadata 过滤）
     */
    public void deleteByDocumentId(Long documentId) {
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        vectorStore.delete(
                builder.eq("document_id", String.valueOf(documentId)).build()
        );
    }

    // ========== 4. RAG 流式问答（SSE） ==========

    /**
     * RAG 流式问答：检索同步完成 → LLM 逐 token 流式输出
     *
     * @param question        用户问题
     * @param topK            召回数量
     * @param similarityThreshold 相似度阈值
     * @param filter          元数据过滤
     * @param hybrid          是否多路召回
     * @param rewrite         是否 Query 改写
     * @param rerank          是否重排序
     * @param conversationId  会话 ID（可选，多轮对话）
     * @return Flux<String> 逐 token 流式输出的 AI 回答
     */
    public Flux<String> askStream(String question, int topK, double similarityThreshold,
                                  MetadataFilter filter, boolean hybrid, boolean rewrite,
                                  boolean rerank, String conversationId) {
        boolean useMemory = conversationId != null && !conversationId.isBlank();

        // ① 检索（同步）
        List<Document> docs;
        try {
            if (rewrite) {
                docs = hybridSearchService.rewriteRetrieve(
                        question, topK, similarityThreshold, filter, rerank).getDocuments();
            } else if (hybrid) {
                docs = hybridSearchService.hybridRetrieve(
                        question, topK, similarityThreshold, filter, rerank);
            } else {
                SearchRequest.Builder builder = SearchRequest.builder()
                        .query(question)
                        .topK(topK)
                        .similarityThreshold(similarityThreshold);
                Filter.Expression filterExpr = buildSearchFilter(filter);
                if (filterExpr != null) {
                    builder.filterExpression(filterExpr);
                }
                docs = vectorStore.similaritySearch(builder.build());
            }
        } catch (Exception e) {
            log.error("[askStream] 检索失败: question={}", question, e);
            return Flux.just("检索失败，请稍后重试");
        }

        if (docs == null || docs.isEmpty()) {
            return Flux.just("根据文档，无法回答这个问题（未检索到相关内容）");
        }

        // ② 拼接上下文 + 构造 prompt
        String prompt = buildRagPrompt(buildContext(docs), question);

        // ③ 流式生成（带异常降级：LLM 不可用时返回兜底提示）
        try {
            var promptSpec = chatClient.prompt().user(prompt);
            if (useMemory) {
                promptSpec = promptSpec.advisors(MessageChatMemoryAdvisor
                        .builder(chatMemory)
                        .conversationId(conversationId)
                        .build());
            }
            return promptSpec.stream().content();
        } catch (Exception e) {
            log.error("[askStream] LLM 流式生成失败: question={}", question, e);
            return Flux.just("AI 模型暂时不可用，请稍后重试。以下是检索到的相关文档片段供参考：\n\n"
                    + docs.get(0).getText());
        }
    }

    /**
     * 清除会话记忆
     */
    public void clearConversation(String conversationId) {
        chatMemory.clear(conversationId);
    }

    // ========== 辅助方法 ==========

    /**
     * 构造 RAG prompt（统一模板，避免重复代码）
     */
    private String buildRagPrompt(String context, String question) {
        return """
                请根据以下参考资料回答用户的问题。
                重要规则：
                1. 只能基于参考资料中的内容回答，不要编造信息
                2. 如果参考资料中没有相关内容，明确说"根据文档，无法回答这个问题"
                3. 如果问题涉及"几个"、"哪些"等列举类问题，必须把参考资料中所有相关条目都列出来，不要只列一个
                4. 回答要准确完整，逐条列出，并在每条后标注来源段落编号

                参考资料：
                %s

                用户问题：%s
                """.formatted(context, question);
    }

    /**
     * 拼接检索到的文档为上下文字符串（统一格式，避免重复代码）
     */
    private String buildContext(List<Document> docs) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            context.append("【段落").append(i + 1).append("】")
                    .append(docs.get(i).getText()).append("\n\n");
        }
        return context.toString();
    }

    /**
     * 从检索结果构建来源列表（统一格式，避免重复代码）
     */
    private List<Map<String, Object>> buildSources(List<Document> docs) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (Document doc : docs) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("content", doc.getText());
            s.put("score", doc.getMetadata().get("distance"));
            s.put("source", doc.getMetadata().get("source"));
            sources.add(s);
        }
        return sources;
    }

    /**
     * 调用 LLM 生成回答（统一 LLM 调用 + 记忆 + 降级逻辑，避免重复代码）
     *
     * @return LLM 生成的回答文本；LLM 不可用时抛出异常由调用方降级
     */
    private String generateAnswer(String prompt, boolean useMemory, String conversationId) {
        var promptSpec = chatClient.prompt().user(prompt);
        if (useMemory) {
            promptSpec = promptSpec.advisors(MessageChatMemoryAdvisor
                    .builder(chatMemory)
                    .conversationId(conversationId)
                    .build());
        }
        return promptSpec.call().content();
    }

    /**
     * 按 vector_id 删除单个向量
     */
    public void deleteByVectorId(String vectorId) {
        if (vectorId == null || vectorId.isEmpty()) return;
        vectorStore.delete(List.of(vectorId));
    }

    // ========== 5. 重新向量化 ==========

    /**
     * 重新向量化单个分块（修改分块内容后调用）
     * 删除旧向量 → 创建新 Document → 向量化 → 回写 vector_id
     */
    public String reembedChunk(KbChunk chunk, KbDocument doc) {
        Long versionId = getActiveVersionId();

        // ① 删除旧向量
        deleteByVectorId(chunk.getVectorId());

        // ② 创建新 Document（用 HashMap 保证 metadata 可变）
        Document springDoc = new Document(chunk.getContent(), new HashMap<>());
        injectMetadata(springDoc, doc, String.valueOf(chunk.getId()), chunk.getChunkIndex(), versionId);

        // ③ 向量化入库
        vectorStore.add(List.of(springDoc));

        // ④ 回写 vector_id
        String newVectorId = springDoc.getId();
        chunkRepository.updateVectorId(chunk.getId(), newVectorId);
        return newVectorId;
    }

    /**
     * 重新向量化整个文档的所有分块（reindex 时调用）
     * 删除旧向量 → 从 kb_chunk 读取内容 → 逐批向量化 → 回写 vector_id
     */
    public void reembedDocument(KbDocument doc, List<KbChunk> chunks) {
        Long versionId = getActiveVersionId();

        // ① 删除旧向量
        deleteByDocumentId(doc.getId());

        // ② 从 kb_chunk 内容创建新的 Spring AI Documents
        List<Document> springDocs = new ArrayList<>();
        for (KbChunk chunk : chunks) {
            Document springDoc = new Document(chunk.getContent(), new HashMap<>());
            injectMetadata(springDoc, doc, String.valueOf(chunk.getId()), chunk.getChunkIndex(), versionId);
            springDocs.add(springDoc);
        }

        // ③ 分批向量化
        int batchSize = 10;
        for (int i = 0; i < springDocs.size(); i += batchSize) {
            List<Document> batch = springDocs.subList(i, Math.min(i + batchSize, springDocs.size()));
            vectorStore.add(batch);

            for (int j = 0; j < batch.size(); j++) {
                int idx = i + j;
                String vectorId = batch.get(j).getId();
                chunkRepository.updateVectorId(chunks.get(idx).getId(), vectorId);
                chunks.get(idx).setVectorId(vectorId);
            }
        }
    }

    /**
     * 生成元数据过滤器的签名（用于缓存 Key，保证不同过滤条件缓存隔离）
     */
    private String filterSignature(MetadataFilter filter) {
        if (filter == null || !filter.hasAny()) return "none";
        StringBuilder sb = new StringBuilder();
        if (filter.hasSource()) sb.append("s:").append(filter.source()).append("|");
        if (filter.hasCategory()) sb.append("c:").append(filter.category()).append("|");
        if (filter.hasAuthor()) sb.append("a:").append(filter.author()).append("|");
        if (filter.hasDateFrom()) sb.append("df:").append(filter.dateFrom()).append("|");
        if (filter.hasDateTo()) sb.append("dt:").append(filter.dateTo()).append("|");
        return sb.toString();
    }

    // ========== 6. 统计 ==========

    /**
     * 向量库统计信息
     */
    public VectorStatsVO stats() {
        VectorStatsVO stats = new VectorStatsVO();
        stats.setTotalDocuments(documentRepository.count(null));
        stats.setReadyDocuments(documentRepository.count("READY"));
        stats.setFailedDocuments(documentRepository.count("FAILED"));
        stats.setProcessingDocuments(documentRepository.count("PROCESSING"));
        stats.setTotalChunks(chunkRepository.countAll());

        try {
            // ★ 如果有 ACTIVE 版本，只统计当前版本的向量数
            Long versionId = getActiveVersionId();
            Integer vectorCount;
            if (versionId != null) {
                vectorCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM kb_vector WHERE metadata->>'version' = ?",
                        Integer.class, String.valueOf(versionId));
            } else {
                vectorCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM kb_vector", Integer.class);
            }
            stats.setTotalVectors(vectorCount != null ? vectorCount : 0);
        } catch (Exception e) {
            stats.setTotalVectors(0);
        }

        return stats;
    }

    // ========== 7. 批量重建（原有功能，保留兼容） ==========

    /**
     * 批量重建所有文档的向量（全量重建）
     * 场景：embedding 模型升级、向量维度变更、数据迁移
     *
     * 注意：此方法在当前版本上重建，会删除旧向量。
     * 如需灰度切换，请使用 IndexVersionService.rebuildWithNewVersion()
     */
    public Map<String, Object> rebuildAll() {
        return rebuildByStatus(null);
    }

    /**
     * 按状态批量重建文档向量
     */
    public Map<String, Object> rebuildByStatus(String status) {
        List<Long> docIds = documentRepository.findAllIds(status);

        int success = 0;
        int failed = 0;
        List<Map<String, Object>> failures = new ArrayList<>();

        for (Long docId : docIds) {
            try {
                KbDocument doc = documentRepository.findById(docId);
                if (doc == null) continue;

                List<KbChunk> chunks = chunkRepository.findByDocumentId(docId);
                if (chunks.isEmpty()) {
                    failed++;
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("documentId", docId);
                    f.put("fileName", doc.getFileName());
                    f.put("error", "文档没有分块，跳过重建");
                    failures.add(f);
                    continue;
                }

                reembedDocument(doc, chunks);
                documentRepository.updateStatus(docId, DocumentStatus.READY, null);
                success++;

            } catch (Exception e) {
                failed++;
                documentRepository.updateStatus(docId, DocumentStatus.FAILED, e.getMessage());
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("documentId", docId);
                f.put("error", e.getMessage());
                failures.add(f);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", docIds.size());
        result.put("success", success);
        result.put("failed", failed);
        result.put("failures", failures);

        // 批量重建后清除缓存
        kbCacheService.evictAll();
        return result;
    }

    // ========== 8. 灰度重建支持方法 ==========

    /**
     * 为指定版本向量化文档（不删除旧版本向量，用于灰度重建）
     *
     * 流程：从 kb_chunk 读取内容 → 创建 Documents（metadata 带 version=新版本号）→ 分批向量化
     * 注意：不更新 kb_chunk.vector_id，不影响当前版本的关联
     *
     * @param doc       文档元数据
     * @param chunks    分块列表
     * @param versionId 目标版本号
     * @return 成功向量化的分块数
     */
    public int embedDocumentForVersion(KbDocument doc, List<KbChunk> chunks, Long versionId) {
        List<Document> springDocs = new ArrayList<>();
        for (KbChunk chunk : chunks) {
            Document springDoc = new Document(chunk.getContent(), new HashMap<>());
            injectMetadata(springDoc, doc, String.valueOf(chunk.getId()), chunk.getChunkIndex(), versionId);
            springDocs.add(springDoc);
        }

        int batchSize = 10;
        int count = 0;
        for (int i = 0; i < springDocs.size(); i += batchSize) {
            List<Document> batch = springDocs.subList(i, Math.min(i + batchSize, springDocs.size()));
            vectorStore.add(batch);
            count += batch.size();
        }
        return count;
    }

    /**
     * 删除指定版本的所有向量（用于清理旧版本数据）
     * @return 删除前的向量数量
     */
    public int deleteVectorsByVersion(Long versionId) {
        int count = countVectorsByVersion(versionId);
        FilterExpressionBuilder builder = new FilterExpressionBuilder();
        vectorStore.delete(builder.eq("version", String.valueOf(versionId)).build());
        return count;
    }

    /**
     * 统计指定版本的向量数量
     */
    public int countVectorsByVersion(Long versionId) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM kb_vector WHERE metadata->>'version' = ?",
                    Integer.class, String.valueOf(versionId));
            return count != null ? count : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 统计指定版本的文档数量（按 document_id 去重）
     */
    public int countDocumentsByVersion(Long versionId) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT metadata->>'document_id') FROM kb_vector WHERE metadata->>'version' = ?",
                    Integer.class, String.valueOf(versionId));
            return count != null ? count : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 给已有向量补上版本号（用于初始化版本管理时迁移旧数据）
     * 将 metadata 中没有 version 字段的向量，补上指定的版本号
     *
     * @param versionId 版本号
     * @return 被补充版本号的向量数量
     */
    public int tagExistingVectors(Long versionId) {
        try {
            return jdbcTemplate.update(
                    "UPDATE kb_vector SET metadata = metadata || jsonb_build_object('version', ?::text) " +
                            "WHERE metadata->>'version' IS NULL",
                    String.valueOf(versionId));
        } catch (Exception e) {
            return 0;
        }
    }
}
