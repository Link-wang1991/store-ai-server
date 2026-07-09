package com.storeai.common.controller;

import com.storeai.common.dto.ApiResponse;
import com.storeai.common.service.ProxyService;
import com.storeai.common.util.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import java.util.*;

import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用数据代理 API。
 * 用于 backend 模式下前端 lib/db 调 Spring Boot 获取 MySQL 数据。
 * 提供一个轻量级 REST 接口，覆盖所有表的 CRUD，无需为每张表写单独 controller。
 *
 * 查询风格类似 Supabase REST API: GET /api/proxy/{table}?field=eq.value&select=field1,field2&limit=20
 */
@Tag(name = "通用数据代理")
@RestController
@RequestMapping("/api/proxy")
@RequiredArgsConstructor
public class ProxyController {

    private final ProxyService proxyService;
    private final CurrentUser cur;

    /** 列表查询（GET） */
    @GetMapping("/{table}")
    public ApiResponse<List<Map<String, Object>>> list(
            @PathVariable String table,
            @RequestParam(defaultValue = "*") String select,
            @RequestParam(required = false) String id,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String order,
            @RequestParam(defaultValue = "asc") String dir,
            @RequestParam Map<String, String> allParams) {
        // 从 query params 提取 filter（排除保留 key）
        var FILTER_KEYS = Set.of("select", "id", "limit", "offset", "order", "dir", "_", "table");
        var filters = new HashMap<String, String>();
        for (var entry : allParams.entrySet()) {
            if (!FILTER_KEYS.contains(entry.getKey())) {
                filters.put(entry.getKey(), entry.getValue());
            }
        }
        return ApiResponse.ok(proxyService.query(table, select, id, limit, offset, order, dir, filters.isEmpty() ? null : filters, cur));
    }

    /** 单条查询（GET） */
    @GetMapping("/{table}/{id}")
    public ApiResponse<Map<String, Object>> get(@PathVariable String table, @PathVariable String id) {
        Map<String, Object> row = proxyService.getById(table, id, cur);
        return row != null ? ApiResponse.ok(row) : ApiResponse.fail(404, "记录不存在");
    }

    /** 插入 */
    @PostMapping("/{table}")
    public ApiResponse<Map<String, Object>> insert(@PathVariable String table, @RequestBody Map<String, Object> data) {
        return ApiResponse.ok(proxyService.insert(table, data, cur));
    }

    /** 更新 */
    @PutMapping("/{table}/{id}")
    public ApiResponse<Void> update(@PathVariable String table, @PathVariable String id, @RequestBody Map<String, Object> data) {
        proxyService.update(table, id, data, cur);
        return ApiResponse.ok();
    }

    /** 删除 */
    @DeleteMapping("/{table}/{id}")
    public ApiResponse<Void> delete(@PathVariable String table, @PathVariable String id) {
        proxyService.delete(table, id, cur);
        return ApiResponse.ok();
    }
}
