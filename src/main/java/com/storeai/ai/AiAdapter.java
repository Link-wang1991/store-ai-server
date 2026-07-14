package com.storeai.ai;

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
 * AI 厂商适配器（原 TypeScript lib/ai/adapter.ts）
 * 通过 AI_PROVIDER 切换 mock / deepseek / qwen。
 * 失败自动降级到 mock 兜底。
 */
@Slf4j
@Service
public class AiAdapter {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String provider;
    private final String deepseekKey;
    private final String deepseekUrl;
    private final String deepseekModel;
    private final String qwenKey;
    private final String qwenModel;

    public AiAdapter(
            @Value("${ai.provider:mock}") String provider,
            @Value("${ai.deepseek.api-key:}") String deepseekKey,
            @Value("${ai.deepseek.base-url:https://api.deepseek.com}") String deepseekUrl,
            @Value("${ai.deepseek.model:deepseek-chat}") String deepseekModel,
            @Value("${ai.qwen.api-key:}") String qwenKey,
            @Value("${ai.qwen.text-model:qwen-plus}") String qwenModel) {
        this.provider = provider;
        this.deepseekKey = deepseekKey;
        this.deepseekUrl = deepseekUrl;
        this.deepseekModel = deepseekModel;
        this.qwenKey = qwenKey;
        this.qwenModel = qwenModel;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    public boolean isConfigured() {
        return switch (provider) {
            case "deepseek" -> deepseekKey != null && !deepseekKey.isBlank();
            case "qwen" -> qwenKey != null && !qwenKey.isBlank();
            default -> false;
        };
    }

    /**
     * 调用 AI 模型生成回答（自由文本）。
     */
    public String call(String system, String user, List<Map<String, String>> history) {
        return post(system, user, history, false);
    }

    /**
     * 调用 AI 模型并要求返回严格 JSON（结构化输出）。
     * 借助厂商的 response_format=json_object，把解析失败率降到最低。
     * 失败（mock 或未配置）返回 null。
     */
    public String callJson(String system, String user) {
        String sys = system + "\n【输出要求】你必须且只能输出一个合法的 JSON 对象，禁止包含 ``` 代码块标记、禁止输出任何额外解释文字，直接以 { 开头、} 结尾。";
        return post(sys, user, null, true);
    }

    private String post(String system, String user, List<Map<String, String>> history, boolean json) {
        return switch (provider) {
            case "deepseek" -> postTo(deepseekUrl + "/chat/completions", deepseekKey, deepseekModel, system, user, history, json);
            case "qwen" -> postTo("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", qwenKey, qwenModel, system, user, history, json);
            default -> null;
        };
    }

    private String postTo(String url, String key, String model, String system, String user,
                          List<Map<String, String>> history, boolean json) {
        try {
            var messages = mapper.createArrayNode();
            messages.add(mapper.createObjectNode()
                    .put("role", "system").put("content", system));
            if (history != null) {
                for (var h : history) {
                    messages.add(mapper.createObjectNode()
                            .put("role", h.get("role")).put("content", h.get("content")));
                }
            }
            messages.add(mapper.createObjectNode()
                    .put("role", "user").put("content", user));

            var body = mapper.createObjectNode();
            body.put("model", model);
            body.set("messages", messages);
            body.put("temperature", json ? 0.3 : 0.6);
            if (json) {
                body.putObject("response_format").put("type", "json_object");
            }

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .timeout(Duration.ofSeconds(90))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("AI API 返回 {}", response.statusCode());
                return null;
            }
            return extractContent(mapper.readTree(response.body()));
        } catch (Exception e) {
            log.error("AI 调用失败: {}", e.getMessage());
            return null;
        }
    }

    private String extractContent(JsonNode root) {
        try {
            String text = root.path("choices").get(0)
                    .path("message").path("content").asText(null);
            if (text != null) text = text.trim();
            return (text != null && !text.isEmpty()) ? text : null;
        } catch (Exception e) {
            return null;
        }
    }
}
