package com.storeai.knowledge.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.common.util.CurrentUser;
import com.storeai.knowledge.entity.KnowledgeDocument;
import com.storeai.knowledge.service.KnowledgeRetrieveService.RetrievedChunk;
import com.storeai.knowledge.service.KnowledgeService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

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
            @RequestParam(required = false) String remark) {
        return ApiResponse.ok(knowledgeService.upload(
            file, title, category,
            visibleRoles != null ? visibleRoles : List.of("owner", "manager", "consultant", "beautician", "receptionist", "operator"),
            tags, remark));
    }

    @PostMapping("/manual")
    public ApiResponse<KnowledgeDocument> createManual(@RequestBody ManualRequest req) {
        return ApiResponse.ok(knowledgeService.createManual(
            req.title(), req.category(), req.content(), req.visibleRoles()));
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

    @GetMapping("/search")
    public ApiResponse<List<RetrievedChunk>> search(
            @RequestParam @NotBlank String q,
            @RequestParam(defaultValue = "5") int topN) {
        return ApiResponse.ok(knowledgeService.search(q, topN));
    }

    public record ManualRequest(
        @NotBlank String title,
        @NotBlank String category,
        @NotBlank String content,
        List<String> visibleRoles
    ) {}
}
