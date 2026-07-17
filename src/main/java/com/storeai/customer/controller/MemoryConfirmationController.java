package com.storeai.customer.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.customer.service.MemoryConfirmationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "记忆确认")
@RestController
@RequestMapping("/api/memory-confirmations")
@RequiredArgsConstructor
public class MemoryConfirmationController {

    private final MemoryConfirmationService memoryConfirmationService;

    @PostMapping("/{id}/confirm")
    public ApiResponse<Map<String, Object>> confirm(@PathVariable String id,
                                                     @RequestBody ConfirmRequest req) {
        return ApiResponse.ok(memoryConfirmationService.confirmFromTask(id, req.confirmed(), req.correctedValue()));
    }

    public record ConfirmRequest(boolean confirmed, String correctedValue) {}
}
