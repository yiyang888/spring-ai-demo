package cn.yiyang.springai.controller.kb;

import cn.yiyang.springai.model.dto.DocumentUpdateDTO;
import cn.yiyang.springai.model.vo.ChunkVO;
import cn.yiyang.springai.model.vo.DocumentVO;
import cn.yiyang.springai.service.DocumentService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 文档管理 REST API
 *
 * 路由前缀：/kb/document
 *
 * 端点一览：
 *   POST   /kb/document/upload           上传文档（提取文本→切分→向量化→入库）
 *   GET    /kb/document/list             文档列表（分页 + 状态过滤）
 *   GET    /kb/document/{id}             文档详情（含分块预览）
 *   PUT    /kb/document/{id}             更新文档标题/描述
 *   DELETE /kb/document/{id}             删除文档（级联删除分块 + 向量）
 *   POST   /kb/document/{id}/reindex     重新向量化（切分参数不变）
 *   POST   /kb/document/{id}/resplit     重新切分（用新参数切分原始文本）
 */
@RestController
@RequestMapping("/kb/document")
@Validated
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    // ========== 1. 上传文档 ==========

    @PostMapping("/upload")
    public DocumentVO upload(@RequestParam("file") MultipartFile file,
                             @RequestParam(defaultValue = "500") int chunkSize,
                             @RequestParam(defaultValue = "100") int overlap,
                             @RequestParam(required = false) String category,
                             @RequestParam(required = false) String author,
                             @RequestParam(required = false) String docDate) throws Exception {
        return documentService.upload(file, chunkSize, overlap, category, author, docDate);
    }

    // ========== 2. 文档列表（分页） ==========

    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size,
                                    @RequestParam(required = false) String status) {
        return documentService.list(page, size, status);
    }

    /** 查询所有不重复的分类（用于前端下拉选择） */
    @GetMapping("/categories")
    public List<String> categories() {
        return documentService.listCategories();
    }

    /** 查询所有不重复的来源/作者（用于前端下拉选择） */
    @GetMapping("/authors")
    public List<String> authors() {
        return documentService.listAuthors();
    }

    // ========== 3. 文档详情（含分块预览） ==========

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return documentService.detail(id);
    }

    // ========== 4. 更新文档信息（标题/描述） ==========

    @PutMapping("/{id}")
    public DocumentVO update(@PathVariable Long id, @Validated @RequestBody DocumentUpdateDTO dto) {
        return documentService.update(id, dto);
    }

    // ========== 5. 删除文档（级联删除分块 + 向量） ==========

    @DeleteMapping("/{id}")
    public String delete(@PathVariable Long id) {
        documentService.delete(id);
        return "文档已删除: " + id;
    }

    // ========== 6. 重新向量化（切分参数不变） ==========

    @PostMapping("/{id}/reindex")
    public String reindex(@PathVariable Long id) {
        documentService.reindex(id);
        return "文档重新向量化完成: " + id;
    }

    // ========== 7. 重新切分（用新参数切分原始文本） ==========

    @PostMapping("/{id}/resplit")
    public List<ChunkVO> reSplit(@PathVariable Long id,
                                 @RequestParam(defaultValue = "500") int chunkSize,
                                 @RequestParam(defaultValue = "100") int overlap) {
        return documentService.reSplit(id, chunkSize, overlap);
    }
}
