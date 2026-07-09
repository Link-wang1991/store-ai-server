package com.storeai.meeting.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.meeting.service.MeetingAnalysisService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "会谈复盘")
@RestController
@RequestMapping("/api/meetings")
@RequiredArgsConstructor
public class MeetingAnalysisController {

    private final MeetingAnalysisService analysisService;

    @PostMapping("/{id}/analyze")
    public ApiResponse<Map<String, Object>> analyze(@PathVariable String id) {
        return ApiResponse.ok(analysisService.process(id));
    }
}
