package cn.yiyang.springai.controller.demo;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义搜索 Demo：文本入库 → 向量检索
 *  Spring AI 的 VectorStore 会自动调用 EmbeddingModel 把文本转成向量再存入 PgVector，
 *  检索时也自动把查询文本转向量、按余弦相似度排序返回。
 */
@RestController
@RequestMapping("/vector")
public class VectorStoreController {

    private final VectorStore vectorStore;

    public VectorStoreController(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    // ========== 1. 入库：把几段文本存进向量库 ==========

    @PostMapping("/add")
    public String add(@RequestParam(defaultValue = "true") boolean useDefault,
                      @RequestParam(required = false) List<String> texts) {
        // 默认演示数据：10 段文本，涵盖编程、数据库、天气、体育、美食
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

        // 把文本包装成 Document（可以附带 metadata）
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            docs.add(new Document(texts.get(i), Map.of("index", String.valueOf(i))));
        }

        // ★ 核心一行：add() 内部自动调用 EmbeddingModel 把文本转成向量再存入 PgVector
        vectorStore.add(docs);

        return "成功存入 " + docs.size() + " 条文档";
    }

    // ========== 2. 检索：按语义搜索最相关的文本 ==========

    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String query,
                                             @RequestParam(defaultValue = "3") int topK,
                                             @RequestParam(defaultValue = "0.0") double similarityThreshold) {
        // ★ 核心一行：similaritySearch 内部自动：
        //   ① 把 query 转成向量
        //   ② 去 PgVector 里按余弦相似度排序
        //   ③ 返回最相似的 topK 条
        //   similarityThreshold: 相似度阈值（0~1），低于此值的结果不返回，避免"矮子里拔将军"
        List<Document> results = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThreshold(similarityThreshold)
                        .build()
        );

        // 整理返回结果
        List<Map<String, Object>> list = new ArrayList<>();
        for (Document doc : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("content", doc.getText());
            item.put("metadata", doc.getMetadata());
            item.put("score", doc.getMetadata().get("distance"));  // 距离越小越相似
            list.add(item);
        }
        return list;
    }

    // ========== 3. 清空：删除所有文档 ==========

    @DeleteMapping("/clear")
    public String clear() {
        // 删除向量库中所有文档（通过 filter 表达式匹配全部）
        vectorStore.delete(List.of());
        return "已清空向量库";
    }
}
