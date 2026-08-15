package cn.yiyang.springai.controller.demo;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文本向量化（Embedding）演示：
 *  1. 把几段文字转成向量，观察维度和数值
 *  2. 计算两两之间的余弦相似度，体会“语义相近 = 向量距离近”
 */
@RestController
public class EmbeddingController {

    private final EmbeddingModel embeddingModel;

    public EmbeddingController(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @GetMapping("/embedding/demo")
    public Map<String, Object> demo(@RequestParam(defaultValue = "true") boolean useDefault) {

        // ① 准备几段文字：前两句讲编程，第三句讲天气，第四句讲体育
        List<String> texts = new ArrayList<>(List.of(
                "Java 是一门面向对象的编程语言",
                "Spring Boot 是 Java 生态最流行的框架",
                "今天天气真不错，适合出去玩",
                "昨晚那场足球比赛太精彩了"
        ));
        if (!useDefault) {
            texts = List.of("Hello World");
        }

        // ② 一次性把所有文本转向量
        EmbeddingResponse response = embeddingModel.embedForResponse(texts);

        // ③ 整理每段文本的向量信息：维度、前 10 个数值、最值、均值
        List<Map<String, Object>> embeddings = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            float[] vector = response.getResults().get(i).getOutput();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("text", texts.get(i));
            item.put("dimension", vector.length);          // 维度
            item.put("first10", preview(vector, 10));       // 前 10 个数值
            item.put("min", round(min(vector), 6));
            item.put("max", round(max(vector), 6));
            item.put("avg", round(avg(vector), 6));
            embeddings.add(item);
        }

        // ④ 计算两两余弦相似度，观察语义相关性
        List<Map<String, Object>> similarities = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            for (int j = i + 1; j < texts.size(); j++) {
                float[] v1 = response.getResults().get(i).getOutput();
                float[] v2 = response.getResults().get(j).getOutput();
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("text1", texts.get(i));
                pair.put("text2", texts.get(j));
                pair.put("cosine", round((float) cosine(v1, v2), 4));
                similarities.add(pair);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", "text-embedding-v3");
        result.put("embeddings", embeddings);
        result.put("similarities", similarities);
        return result;
    }

    // ==================== 工具方法 ====================

    private List<Float> preview(float[] v, int n) {
        List<Float> list = new ArrayList<>();
        for (int i = 0; i < Math.min(n, v.length); i++) {
            list.add(round(v[i], 6));
        }
        return list;
    }

    private float round(float v, int digits) {
        double p = Math.pow(10, digits);
        return (float) (Math.round(v * p) / p);
    }

    private float min(float[] v) {
        float m = v[0];
        for (float x : v) m = Math.min(m, x);
        return m;
    }

    private float max(float[] v) {
        float m = v[0];
        for (float x : v) m = Math.max(m, x);
        return m;
    }

    private float avg(float[] v) {
        float s = 0;
        for (float x : v) s += x;
        return s / v.length;
    }

    /** 余弦相似度：衡量两个向量方向的一致程度，范围 [-1, 1]，越接近 1 越相似 */
    private double cosine(float[] v1, float[] v2) {
        double dot = 0, n1 = 0, n2 = 0;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
            n1 += v1[i] * v1[i];
            n2 += v2[i] * v2[i];
        }
        return dot / (Math.sqrt(n1) * Math.sqrt(n2));
    }
}
