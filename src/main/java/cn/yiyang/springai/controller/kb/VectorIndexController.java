package cn.yiyang.springai.controller.kb;

import cn.yiyang.springai.model.dto.MetadataFilter;
import cn.yiyang.springai.model.vo.VectorStatsVO;
import cn.yiyang.springai.service.VectorIndexService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 向量索引管理 REST API
 *
 * 路由前缀：/kb/vector
 *
 * 端点一览：
 *   GET    /kb/vector/search              纯向量语义检索（返回 topK 段落，不做生成）
 *   GET    /kb/vector/ask                 RAG 问答（检索段落 + 大模型生成回答，支持多轮对话）
 *   GET    /kb/vector/ask/stream          RAG 流式问答（SSE 逐 token 输出）
 *   DELETE /kb/vector/conversation/{id}   清除会话记忆
 *   GET    /kb/vector/stats               向量库统计信息
 *   DELETE /kb/vector/document/{documentId}  按 document_id 删除该文档的所有向量
 */
@RestController
@RequestMapping("/kb/vector")
public class VectorIndexController {

    private final VectorIndexService vectorIndexService;

    public VectorIndexController(VectorIndexService vectorIndexService) {
        this.vectorIndexService = vectorIndexService;
    }

    // ========== 1. 语义检索（单路 / 多路召回 / Query 改写） ==========

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam String query,
                                            @RequestParam(defaultValue = "10") int topK,
                                            @RequestParam(defaultValue = "0.3") double similarityThreshold,
                                            @RequestParam(defaultValue = "false") boolean hybrid,
                                            @RequestParam(defaultValue = "false") boolean rewrite,
                                            @RequestParam(defaultValue = "false") boolean rerank,
                                            @RequestParam(required = false) String sourceFilter,
                                            @RequestParam(required = false) String category,
                                            @RequestParam(required = false) String author,
                                            @RequestParam(required = false) String dateFrom,
                                            @RequestParam(required = false) String dateTo) {
        MetadataFilter filter = buildFilter(sourceFilter, category, author, dateFrom, dateTo);
        if (rewrite) {
            return vectorIndexService.rewriteSearch(query, topK, similarityThreshold, filter, rerank);
        }
        if (hybrid) {
            return vectorIndexService.hybridSearch(query, topK, similarityThreshold, filter, rerank);
        }
        return vectorIndexService.search(query, topK, similarityThreshold, filter);
    }

    // ========== 2. RAG 问答（单路 / 多路召回 / Query 改写 / 多轮对话） ==========

    @GetMapping("/ask")
    public Map<String, Object> ask(@RequestParam String question,
                                   @RequestParam(defaultValue = "10") int topK,
                                   @RequestParam(defaultValue = "0.3") double similarityThreshold,
                                   @RequestParam(defaultValue = "false") boolean hybrid,
                                   @RequestParam(defaultValue = "false") boolean rewrite,
                                   @RequestParam(defaultValue = "false") boolean rerank,
                                   @RequestParam(required = false) String conversationId,
                                   @RequestParam(required = false) String sourceFilter,
                                   @RequestParam(required = false) String category,
                                   @RequestParam(required = false) String author,
                                   @RequestParam(required = false) String dateFrom,
                                   @RequestParam(required = false) String dateTo) {
        MetadataFilter filter = buildFilter(sourceFilter, category, author, dateFrom, dateTo);
        if (rewrite) {
            return vectorIndexService.rewriteAsk(question, topK, similarityThreshold, filter, rerank, conversationId);
        }
        if (hybrid) {
            return vectorIndexService.hybridAsk(question, topK, similarityThreshold, filter, rerank, conversationId);
        }
        return vectorIndexService.ask(question, topK, similarityThreshold, filter, conversationId);
    }

    // ========== 2.5 RAG 流式问答（SSE 逐 token 输出） ==========

    @GetMapping(value = "/ask/stream", produces = "text/event-stream; charset=UTF-8")
    public Flux<String> askStream(@RequestParam String question,
                                  @RequestParam(defaultValue = "10") int topK,
                                  @RequestParam(defaultValue = "0.3") double similarityThreshold,
                                  @RequestParam(defaultValue = "false") boolean hybrid,
                                  @RequestParam(defaultValue = "false") boolean rewrite,
                                  @RequestParam(defaultValue = "false") boolean rerank,
                                  @RequestParam(required = false) String conversationId,
                                  @RequestParam(required = false) String sourceFilter,
                                  @RequestParam(required = false) String category,
                                  @RequestParam(required = false) String author,
                                  @RequestParam(required = false) String dateFrom,
                                  @RequestParam(required = false) String dateTo) {
        MetadataFilter filter = buildFilter(sourceFilter, category, author, dateFrom, dateTo);
        return vectorIndexService.askStream(question, topK, similarityThreshold, filter,
                hybrid, rewrite, rerank, conversationId);
    }

    // ========== 2.6 清除会话记忆 ==========

    @DeleteMapping("/conversation/{conversationId}")
    public String clearConversation(@PathVariable String conversationId) {
        vectorIndexService.clearConversation(conversationId);
        return "已清除会话 " + conversationId + " 的记忆";
    }

    // ========== 3. 向量库统计信息 ==========

    @GetMapping("/stats")
    public VectorStatsVO stats() {
        return vectorIndexService.stats();
    }

    // ========== 4. 按文档 ID 删除所有向量 ==========

    @DeleteMapping("/document/{documentId}")
    public String deleteByDocumentId(@PathVariable Long documentId) {
        vectorIndexService.deleteByDocumentId(documentId);
        return "文档向量已删除: " + documentId;
    }

    // ========== 5. 批量重建（全量/按状态） ==========

    @PostMapping("/rebuild")
    public Map<String, Object> rebuildAll() {
        return vectorIndexService.rebuildAll();
    }

    @PostMapping("/rebuild/status/{status}")
    public Map<String, Object> rebuildByStatus(@PathVariable String status) {
        return vectorIndexService.rebuildByStatus(status);
    }

    // ========== 辅助方法 ==========

    private MetadataFilter buildFilter(String sourceFilter, String category,
                                        String author, String dateFrom, String dateTo) {
        MetadataFilter filter = new MetadataFilter();
        filter.setSource(sourceFilter);
        filter.setCategory(category);
        filter.setAuthor(author);
        filter.setDateFrom(dateFrom);
        filter.setDateTo(dateTo);
        return filter.hasAny() ? filter : null;
    }
}
