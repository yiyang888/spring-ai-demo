package cn.yiyang.langchain4j.controller;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * LangChain4j 版向量存取 Demo：和 Spring AI 的 VectorStoreController 做同样的事
 *
 * 对比 Spring AI：
 *   Spring AI:  vectorStore.add(docs)  /  vectorStore.similaritySearch(SearchRequest)
 *   LangChain4j: embeddingModel.embed(text) + embeddingStore.add(segment, embedding)
 *                /  embeddingStore.findRelevant(embedding, topK)
 *
 * LangChain4j 把"转向量"和"存储检索"拆成两步，更灵活但代码更多；
 * Spring AI 的 VectorStore 把两步封装在一起，更简洁。
 */
@RestController
@RequestMapping("/lc4j/vector")
public class LangChain4jVectorController {

    private final OpenAiEmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    public LangChain4jVectorController(OpenAiEmbeddingModel embeddingModel,
                                       EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    // ========== 1. 入库：存入 10 条文本 ==========

    @PostMapping("/add")
    public String add(@RequestParam(defaultValue = "true") boolean useDefault,
                      @RequestParam(required = false) List<String> texts) {
        if (useDefault || texts == null || texts.isEmpty()) {
            texts = List.of(
                    "Java 是一门面向对象的编程语言，跨平台运行",
                    "Spring Boot 是 Java 生态最流行的微服务框架",
                    "MyBatis 是一个优秀的 ORM 框架，简化数据库操作",
                    "Redis 是一个高性能的内存键值数据库，常用作缓存",
                    "MySQL 是最流行的开源关系型数据库，支持事务和索引",
                    "Docker 是容器化部署工具，让应用环境一致",
                    "今天北京天气晴朗，气温 25 度，适合户外运动",
                    "昨晚那场足球比赛太精彩了，补时绝杀",
                    "火锅是重庆的特色美食，麻辣鲜香",
                    "Python 在数据分析和人工智能领域应用广泛"
            );
        }

        // ★ LangChain4j 需要两步：先转向量，再存储
        //   对比 Spring AI 的 vectorStore.add(docs) 一步到位
        for (int i = 0; i < texts.size(); i++) {
            TextSegment segment = TextSegment.from(texts.get(i));
            // ① 文本 → 向量
            Embedding embedding = embeddingModel.embed(texts.get(i)).content();
            // ② 向量 + 文本 → 存入 PgVector（注意：Embedding 在前，TextSegment 在后）
            embeddingStore.add(embedding, segment);
        }

        return "LangChain4j 成功存入 " + texts.size() + " 条文档";
    }

    // ========== 2. 检索：语义搜索 ==========

    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String query,
                                             @RequestParam(defaultValue = "3") int topK,
                                             @RequestParam(defaultValue = "0.0") double minScore) {
        // ① 查询文本 → 向量
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        // ② 按余弦相似度检索最相关的 topK 条
        //    对比 Spring AI 的 vectorStore.similaritySearch(SearchRequest)
        //    LangChain4j 1.0.0-beta5 用 EmbeddingSearchRequest 替代了已废弃的 findRelevant
        //    minScore: 相似度阈值（0~1），低于此值的结果不返回
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore(minScore)
                .build();
        EmbeddingSearchResult<TextSegment> searchResult = embeddingStore.search(searchRequest);
        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>(searchResult.matches());

        // 整理返回
        List<Map<String, Object>> results = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("content", match.embedded().text());
            item.put("score", match.score());  // LangChain4j 直接返回 score（0~1，越大越相似）
            results.add(item);
        }
        return results;
    }

    // ========== 3. 对比两个框架的检索结果 ==========

    /**
     * 同一个查询，同时调 Spring AI 和 LangChain4j 两个向量库，对比结果
     * 需要先分别执行 /vector/add 和 /lc4j/vector/add 入库
     */
}
