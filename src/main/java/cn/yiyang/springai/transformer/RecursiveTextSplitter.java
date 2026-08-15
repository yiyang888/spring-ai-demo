package cn.yiyang.springai.transformer;

import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 递归字符切分器：优先按语义边界切分，超长文本再降级到更细的分隔符，最后才硬截断。
 *
 * 分隔符优先级（从粗到细）：
 *   段落 -> 换行 -> 中文句号 -> 英文句号 -> 分号 -> 逗号 -> 空格 -> 单字符
 *
 * 适用场景：
 *   RAG 知识库入库前的文本切分，特别是 TikaDocumentReader、TextReader 提取出来的大段文本。
 *   相比 TokenTextSplitter 的硬截断，递归切分能保留更多语义边界，提高检索命中率。
 *
 * 参数：
 *   chunkSize  每个 chunk 的最大字符长度
 *   overlap    相邻 chunk 之间的重叠字符数，保证跨边界的上下文不被切断
 */
public class RecursiveTextSplitter extends TextSplitter {

    private final int chunkSize;
    private final int overlap;
    private final List<String> separators;

    public RecursiveTextSplitter(int chunkSize, int overlap) {
        this.chunkSize = chunkSize;
        this.overlap = overlap;
        this.separators = List.of("\n\n", "\n", "。", ". ", "；", ";", "，", ",", " ", "");
    }

    @Override
    protected List<String> splitText(String text) {
        return recursiveSplit(text, 0);
    }

    private List<String> recursiveSplit(String text, int sepIndex) {
        if (sepIndex >= separators.size()) {
            return hardSplit(text);
        }

        String sep = separators.get(sepIndex);
        List<String> parts;
        if (sep.isEmpty()) {
            parts = new ArrayList<>();
            for (char c : text.toCharArray()) {
                parts.add(String.valueOf(c));
            }
        } else {
            parts = Arrays.stream(text.split(Pattern.quote(sep), -1))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        }

        List<String> result = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentLen = 0;

        for (String part : parts) {
            if (part.length() > chunkSize) {
                if (!current.isEmpty()) {
                    result.add(String.join(sep, current));
                    current.clear();
                    currentLen = 0;
                }
                result.addAll(recursiveSplit(part, sepIndex + 1));
                continue;
            }

            int addLen = current.isEmpty() ? part.length() : part.length() + sep.length();
            if (currentLen + addLen > chunkSize) {
                result.add(String.join(sep, current));
                String lastChunk = result.get(result.size() - 1);
                current.clear();
                currentLen = 0;
                if (overlap > 0 && !lastChunk.isEmpty()) {
                    String overlapText = lastChunk.length() <= overlap ? lastChunk : lastChunk.substring(lastChunk.length() - overlap);
                    current.add(overlapText);
                    currentLen = overlapText.length();
                }
            }

            current.add(part);
            currentLen += addLen;
        }

        if (!current.isEmpty()) {
            result.add(String.join(sep, current));
        }

        return result;
    }

    private List<String> hardSplit(String text) {
        List<String> result = new ArrayList<>();
        int step = Math.max(1, chunkSize - overlap);
        for (int i = 0; i < text.length(); i += step) {
            result.add(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
        return result;
    }
}
