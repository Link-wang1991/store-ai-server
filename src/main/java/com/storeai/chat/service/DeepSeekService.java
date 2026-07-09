package com.storeai.chat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 调用 DeepSeek API（OpenAI 兼容协议）生成真实 AI 回答。
 * 未配置 DEEPSEEK_API_KEY 时返回 null，由调用方回退模板。
 */
@Slf4j
@Service
public class DeepSeekService {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String baseUrl;
    private final String model;

    public DeepSeekService(
            @Value("${ai.deepseek.api-key:}") String apiKey,
            @Value("${ai.deepseek.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${ai.deepseek.model:deepseek-chat}") String model) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    /** 是否已配置 API key */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 调用 DeepSeek 文本模型。
     *
     * @param system  系统提示词
     * @param user    用户问题
     * @param history 历史消息（可选）
     * @return AI 回答文本；失败或未配置时返回 null
     */
    public String call(String system, String user, List<Map<String, String>> history) {
        if (!isConfigured()) {
            log.warn("DEEPSEEK_API_KEY 未配置，跳过真实 AI 调用");
            return null;
        }

        try {
            // 构建 messages
            var messages = mapper.createArrayNode();
            messages.add(mapper.createObjectNode()
                    .put("role", "system")
                    .put("content", system));
            if (history != null) {
                for (var h : history) {
                    messages.add(mapper.createObjectNode()
                            .put("role", h.get("role"))
                            .put("content", h.get("content")));
                }
            }
            messages.add(mapper.createObjectNode()
                    .put("role", "user")
                    .put("content", user));

            // 请求体
            var requestBody = mapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.set("messages", messages);
            requestBody.put("temperature", 0.6);

            // HTTP 请求
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(requestBody)))
                    .build();

            var response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("DeepSeek API 返回 {}", response.statusCode());
                return null;
            }

            JsonNode root = mapper.readTree(response.body());
            String text = root.path("choices").get(0)
                    .path("message").path("content").asText(null);
            if (text != null) text = text.trim();
            return (text != null && !text.isEmpty()) ? text : null;

        } catch (Exception e) {
            log.error("DeepSeek API 调用失败: {}", e.getMessage());
            return null;
        }
    }
}
