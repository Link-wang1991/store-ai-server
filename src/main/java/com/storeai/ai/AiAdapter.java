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
     * 调用 AI 模型生成回答。
     * @param system 系统提示词
     * @param user 用户提示词
     * @param history 历史消息（可选）
     * @return AI 回答文本；失败返回 null
     */
    public String call(String system, String user, List<Map<String, String>> history) {
        return switch (provider) {
            case "deepseek" -> callDeepSeek(system, user, history);
            case "qwen" -> callQwen(system, user, history);
            default -> null;
        };
    }

    private String callDeepSeek(String system, String user, List<Map<String, String>> history) {
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
            body.put("model", deepseekModel);
            body.set("messages", messages);
            body.put("temperature", 0.6);

            var request = HttpRequest.newBuilder()
                    .uri(URI.create(deepseekUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + deepseekKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("DeepSeek API 返回 {}", response.statusCode());
                return null;
            }
            return extractContent(mapper.readTree(response.body()));
        } catch (Exception e) {
            log.error("DeepSeek 调用失败: {}", e.getMessage());
            return null;
        }
    }

    private String callQwen(String system, String user, List<Map<String, String>> history) {
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
            body.put("model", qwenModel);
            body.set("messages", messages);
            body.put("temperature", 0.6);

            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + qwenKey)
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Qwen API 返回 {}", response.statusCode());
                return null;
            }
            return extractContent(mapper.readTree(response.body()));
        } catch (Exception e) {
            log.error("Qwen 调用失败: {}", e.getMessage());
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
