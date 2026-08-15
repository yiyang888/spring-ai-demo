package cn.yiyang.springai.controller.kb;

import cn.yiyang.springai.model.vo.ChunkVO;
import cn.yiyang.springai.service.ChunkService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 分块管理 REST API
 *
 * 路由前缀：/kb/chunk
 *
 * 端点一览：
 *   GET    /kb/chunk/document/{documentId}   查看文档的所有分块（列表模式，content 截断）
 *   GET    /kb/chunk/{chunkId}              查看单个分块详情（完整 content）
 *   PUT    /kb/chunk/{chunkId}              修改分块内容（触发重新向量化）
 *   DELETE /kb/chunk/{chunkId}              删除单个分块（删除向量 + 更新文档计数）
 */
@RestController
@RequestMapping("/kb/chunk")
public class ChunkController {

    private final ChunkService chunkService;

    public ChunkController(ChunkService chunkService) {
        this.chunkService = chunkService;
    }

    // ========== 1. 查看文档的所有分块（列表模式，content 截断前 200 字符） ==========

    @GetMapping("/document/{documentId}")
    public List<ChunkVO> listByDocument(@PathVariable Long documentId) {
        return chunkService.listByDocument(documentId);
    }

    // ========== 2. 查看单个分块详情（完整 content） ==========

    @GetMapping("/{chunkId}")
    public ChunkVO detail(@PathVariable Long chunkId) {
        return chunkService.detail(chunkId);
    }

    // ========== 3. 修改分块内容（触发重新向量化） ==========

    @PutMapping("/{chunkId}")
    public ChunkVO updateContent(@PathVariable Long chunkId,
                                 @RequestBody Map<String, String> body) {
        String newContent = body.get("content");
        if (newContent == null || newContent.isEmpty()) {
            throw new RuntimeException("分块内容不能为空");
        }
        return chunkService.updateContent(chunkId, newContent);
    }

    // ========== 4. 删除单个分块 ==========

    @DeleteMapping("/{chunkId}")
    public String delete(@PathVariable Long chunkId) {
        chunkService.delete(chunkId);
        return "分块已删除: " + chunkId;
    }

    // ========== 5. 追加分块（实时更新） ==========

    /**
     * 向文档末尾追加一个新分块，并立即向量化
     */
    @PostMapping("/document/{documentId}")
    public ChunkVO addChunk(@PathVariable Long documentId,
                            @RequestBody Map<String, String> body) {
        String content = body.get("content");
        if (content == null || content.isEmpty()) {
            throw new RuntimeException("分块内容不能为空");
        }
        return chunkService.addChunk(documentId, content);
    }
}
