package cn.yiyang.springai.controller.demo;

import cn.yiyang.springai.transformer.RecursiveTextSplitter;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 文本切分策略实战：固定长度、按句子、递归切分
 *
 * 关键事实：
 *   Spring AI 1.0.0-M6（当前项目实际解析版本）只内置了 TokenTextSplitter。
 *   固定字符长度、按句子、递归字符切分没有现成类，需继承 TextSplitter 自行实现。
 *
 * TextSplitter 接口：
 *   继承 DocumentTransformer，核心只需实现 splitText(String text) -> List<String>
 *   对外提供 apply(List<Document>) 和 split(Document)，自动把 Document 内容切好再封装回 Document。
 *
 * 三种策略：
 *   1. TokenTextSplitter（Spring AI 内置）：按 token 数切，固定长度，实现最简单。
 *   2. FixedLengthTextSplitter（自定义）：按字符数切，chunkSize + overlap。
 *   3. SentenceTextSplitter（自定义）：按句子切（中文/英文句号）。
 *   4. RecursiveTextSplitter（自定义）：递归切分，优先级：段落 > 句子 > 逗号/分号 > 空格 > 硬截断。
 *
 * 接口：
 *   POST /split/token?chunkSize=300&overlap=50
 *   POST /split/fixed?chunkSize=200&overlap=30
 *   POST /split/sentence
 *   POST /split/recursive?chunkSize=200&overlap=30
 *
 * Body：纯文本字符串（text/plain）
 */
@RestController
@RequestMapping("/split")
public class TextSplitterController {

    // ========== 1. 固定长度切分：按 token 数切（Spring AI 内置） ==========

    @PostMapping(value = "/token", consumes = "text/plain", produces = "application/json")
    public Map<String, Object> tokenSplit(@RequestBody String text,
                                          @RequestParam(defaultValue = "300") int chunkSize,
                                          @RequestParam(defaultValue = "50") int overlap) {
        // TokenTextSplitter 的 5 参数构造器：
        // defaultChunkSize, minChunkSizeChars, minChunkLengthToEmbed, maxNumChunks, keepSeparator
        // 这里简化为按 chunkSize + overlap 近似配置
        TokenTextSplitter splitter = new TokenTextSplitter(chunkSize, 100, 30, 100, true);
        return splitAndWrap(text, "TokenTextSplitter（按 token 固定长度）", Map.of(
                "chunkSize", chunkSize,
                "overlap", overlap
        ), splitter);
    }

    // ========== 2. 固定长度切分：按字符数切（自定义） ==========

    @PostMapping(value = "/fixed", consumes = "text/plain", produces = "application/json")
    public Map<String, Object> fixedSplit(@RequestBody String text,
                                          @RequestParam(defaultValue = "200") int chunkSize,
                                          @RequestParam(defaultValue = "30") int overlap) {
        return splitAndWrap(text, "FixedLengthTextSplitter（按字符固定长度）", Map.of(
                "chunkSize", chunkSize,
                "overlap", overlap
        ), new FixedLengthTextSplitter(chunkSize, overlap));
    }

    // ========== 3. 按句子切分（自定义） ==========

    @PostMapping(value = "/sentence", consumes = "text/plain", produces = "application/json")
    public Map<String, Object> sentenceSplit(@RequestBody String text) {
        return splitAndWrap(text, "SentenceTextSplitter（按句子切分）", Map.of(), new SentenceTextSplitter());
    }

    // ========== 4. 递归切分（自定义：段落→句子→逗号/分号→空格→硬截断） ==========

    @PostMapping(value = "/recursive", consumes = "text/plain", produces = "application/json")
    public Map<String, Object> recursiveSplit(@RequestBody String text,
                                              @RequestParam(defaultValue = "200") int chunkSize,
                                              @RequestParam(defaultValue = "30") int overlap) {
        return splitAndWrap(text, "RecursiveTextSplitter（递归切分）", Map.of(
                "chunkSize", chunkSize,
                "overlap", overlap,
                "separators", List.of("\n\n", "\n", "。", ". ", "；", ";", "，", ",", " ", "")
        ), new RecursiveTextSplitter(chunkSize, overlap));
    }

    // ========== 公共：执行切分并组装返回结果 ==========

    private Map<String, Object> splitAndWrap(String text, String strategy, Map<String, Object> params, TextSplitter splitter) {
        Document doc = new Document(text);
        List<Document> chunks = splitter.apply(List.of(doc));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", strategy);
        result.put("params", params);
        result.put("inputLength", text.length());
        result.put("chunkCount", chunks.size());

        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("index", i);
            String t = chunks.get(i).getText();
            c.put("length", t.length());
            c.put("preview", t.substring(0, Math.min(80, t.length())) + (t.length() > 80 ? "..." : ""));
            list.add(c);
        }
        result.put("chunks", list);
        return result;
    }

    // ========== 自定义切分器实现 ==========

    /**
     * 固定字符长度切分
     */
    public static class FixedLengthTextSplitter extends TextSplitter {
        private final int chunkSize;
        private final int overlap;

        public FixedLengthTextSplitter(int chunkSize, int overlap) {
            this.chunkSize = chunkSize;
            this.overlap = overlap;
        }

        @Override
        protected List<String> splitText(String text) {
            List<String> result = new ArrayList<>();
            int step = Math.max(1, chunkSize - overlap);
            for (int i = 0; i < text.length(); i += step) {
                result.add(text.substring(i, Math.min(i + chunkSize, text.length())));
            }
            return result;
        }
    }

    /**
     * 按句子切分：中文句号、英文句号、问号、感叹号
     */
    public static class SentenceTextSplitter extends TextSplitter {
        // 保留分隔符，作为句子结尾
        private static final Pattern SENTENCE = Pattern.compile("(?<=[。！？.?!])");

        @Override
        protected List<String> splitText(String text) {
            String[] sentences = SENTENCE.split(text);
            List<String> result = new ArrayList<>();
            for (String s : sentences) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
            return result;
        }
    }

}
