package com.storeai.admin.controller;

import com.storeai.admin.service.StoreDataResetService;
import com.storeai.common.dto.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Tag(name = "数据切换")
@RestController
@RequestMapping("/api/admin/data-reset")
@RequiredArgsConstructor
public class StoreDataResetController {

    private final StoreDataResetService dataResetService;

    @GetMapping("/preview")
    public ApiResponse<StoreDataResetService.Preview> preview() {
        return ApiResponse.ok(dataResetService.preview());
    }

    @PostMapping("/backup")
    public ApiResponse<StoreDataResetService.Backup> backup() {
        return ApiResponse.ok(dataResetService.backup());
    }

    /** 本店已生成的备份文件列表。 */
    @GetMapping("/backups")
    public ApiResponse<List<Map<String, Object>>> backups() {
        return ApiResponse.ok(dataResetService.listBackups());
    }

    /** 下载本店备份文件（仅允许下载本店前缀匹配的文件）。 */
    @GetMapping("/backup/{fileName}")
    public ResponseEntity<InputStreamResource> download(@PathVariable String fileName) {
        try {
            Path file = dataResetService.openBackup(fileName);
            String safeName = file.getFileName().toString().replace("\"", "_");
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + safeName + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(file))
                .body(new InputStreamResource(Files.newInputStream(file)));
        } catch (com.storeai.common.exception.BizException e) {
            throw e;
        } catch (Exception e) {
            throw new com.storeai.common.exception.BizException("备份文件读取失败");
        }
    }

    @PostMapping("/clear")
    public ApiResponse<StoreDataResetService.ClearResult> clear(@Valid @RequestBody ClearRequest request) {
        return ApiResponse.ok(dataResetService.clear(request.confirmation()));
    }

    public record ClearRequest(@NotBlank String confirmation) {}
}
