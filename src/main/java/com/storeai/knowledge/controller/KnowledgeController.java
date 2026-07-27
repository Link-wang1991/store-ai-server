package com.storeai.knowledge.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import com.storeai.knowledge.entity.KnowledgeDocument;
import com.storeai.knowledge.service.KnowledgeRetrieveService.RetrievedChunk;
import com.storeai.knowledge.service.KnowledgeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Tag(name = "知识库")
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final CurrentUser cur;

    @GetMapping
    public ApiResponse<List<KnowledgeDocument>> list(
            @RequestParam(required = false) String category) {
        return ApiResponse.ok(knowledgeService.listActive(category));
    }

    @PostMapping("/upload")
    public ApiResponse<KnowledgeDocument> upload(
            @RequestParam MultipartFile file,
            @RequestParam @NotBlank String title,
            @RequestParam @NotBlank String category,
            @RequestParam(required = false) List<String> visibleRoles,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) String remark,
            @RequestParam(defaultValue = "active") String status) {
        return ApiResponse.ok(knowledgeService.upload(
            file, title, category,
            visibleRoles != null ? visibleRoles : List.of("owner", "manager", "consultant", "beautician", "receptionist", "operator"),
            tags, remark, status));
    }

    @PostMapping("/manual")
    public ApiResponse<KnowledgeDocument> createManual(@RequestBody ManualRequest req) {
        return ApiResponse.ok(knowledgeService.createManual(
            req.title(), req.category(), req.content(), req.visibleRoles(), req.tags(), req.remark(), req.status()));
    }

    @PostMapping("/{id}/toggle")
    public ApiResponse<Void> toggle(@PathVariable String id) {
        knowledgeService.toggleStatus(id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/delete")
    public ApiResponse<Void> delete(@PathVariable String id) {
        knowledgeService.delete(id);
        return ApiResponse.ok();
    }

    /** 通过鉴权接口读取本机保留的知识原件。 */
    @GetMapping("/{id}/file")
    public ResponseEntity<InputStreamResource> getLocalOriginalFile(@PathVariable String id) {
        try {
            Path file = knowledgeService.localOriginalFile(id);
            InputStream input = Files.newInputStream(file);
            String contentType = Files.probeContentType(file);
            String fileName = file.getFileName().toString().replace("\"", "_");
            return ResponseEntity.ok()
                .contentType(contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + fileName + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(new InputStreamResource(input));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("读取原文件失败");
        }
    }

    @GetMapping("/search")
    public ApiResponse<List<RetrievedChunk>> search(
            @RequestParam @NotBlank String q,
            @RequestParam(defaultValue = "5") int topN) {
        return ApiResponse.ok(knowledgeService.search(q, topN));
    }

    /** 为旧知识片段补建语义检索向量；未配置向量服务时会返回清晰提示。 */
    @PostMapping("/reindex-embeddings")
    public ApiResponse<Map<String, Object>> reindexEmbeddings() {
        return ApiResponse.ok(knowledgeService.reindexEmbeddings());
    }

    public record ManualRequest(
        @NotBlank String title,
        @NotBlank String category,
        @NotBlank String content,
        List<String> visibleRoles,
        String tags,
        String remark,
        String status
    ) {}
}
