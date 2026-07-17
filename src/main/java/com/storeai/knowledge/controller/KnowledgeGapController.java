package com.storeai.knowledge.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.knowledge.service.KnowledgeGapService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "知识缺口")
@RestController
@RequestMapping("/api/knowledge-gaps")
@RequiredArgsConstructor
public class KnowledgeGapController {

    private final KnowledgeGapService knowledgeGapService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(knowledgeGapService.list());
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<Map<String, Object>> resolve(@PathVariable String id,
                                                     @RequestBody ResolveRequest req) {
        return ApiResponse.ok(knowledgeGapService.resolve(id, req.answer()));
    }

    @PostMapping("/{id}/to-knowledge")
    public ApiResponse<Map<String, Object>> toKnowledge(@PathVariable String id,
                                                         @RequestBody ToKnowledgeRequest req) {
        return ApiResponse.ok(knowledgeGapService.toKnowledge(id, req.title(), req.content(), req.category()));
    }

    public record ResolveRequest(String answer) {}
    public record ToKnowledgeRequest(String title, String content, String category) {}
}
