package com.storeai.common.controller;

import com.storeai.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本机启动器和部署探针使用的最小健康检查。
 *
 * <p>仅访问 Swagger 页面只能证明 Spring 已经监听端口，无法证明业务必须依赖的
 * 数据库可用。这个端点不会返回连接串、账号、存储路径等敏感配置。</p>
 */
@RestController
@RequiredArgsConstructor
public class AppHealthController {

    private final JdbcTemplate jdbc;

    @Value("${ai.qwen.api-key:}")
    private String qwenApiKey;

    @GetMapping("/api/health")
    public ApiResponse<Map<String, Object>> health() {
        Integer database = jdbc.queryForObject("SELECT 1", Integer.class);
        if (database == null || database != 1) {
            throw new IllegalStateException("数据库健康检查未通过");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("database", "ok");
        // 只公布就绪状态，不暴露密钥、模型、连接地址等配置细节。
        result.put("audio_transcription", qwenApiKey == null || qwenApiKey.isBlank() ? "not_configured" : "configured");
        result.put("checked_at", OffsetDateTime.now().toString());
        return ApiResponse.ok(result);
    }
}
