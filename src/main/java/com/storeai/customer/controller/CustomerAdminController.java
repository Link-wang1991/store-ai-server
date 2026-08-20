package com.storeai.customer.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.common.exception.BizException;
import com.storeai.common.util.CurrentUser;
import com.storeai.customer.service.CustomerImportService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Tag(name = "客户管理后台（模板/导入）")
@RestController
@RequestMapping("/api/admin/customers")
@RequiredArgsConstructor
public class CustomerAdminController {

    private final CustomerImportService customerImportService;
    private final CurrentUser cur;

    /** 下载客户导入模板（CSV，UTF-8 带 BOM）。 */
    @GetMapping("/template")
    public ResponseEntity<ByteArrayResource> template() {
        if (!cur.isAdmin()) throw BizException.forbidden();
        byte[] data = customerImportService.buildTemplate();
        String fileName = "客户导入模板.csv";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20"))
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(new ByteArrayResource(data));
    }

    /** 批量导入客户（multipart file）。 */
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importCustomers(@RequestParam("file") MultipartFile file) {
        if (!cur.isAdmin()) throw BizException.forbidden();
        return ApiResponse.ok(customerImportService.importCsv(file));
    }
}
