package com.storeai.knowledge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Qwen Embedding 服务（对应前端 lib/ai/embedding.ts）
 * 将文本转为 1024 维向量，用于语义检索。
 */
@Slf4j
@Service
public class EmbeddingService {

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;

    public EmbeddingService(@Value("${ai.qwen.api-key:}") String apiKey) {
        this.apiKey = apiKey;
        this.model = "text-embedding-v3";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = new ObjectMapper();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 将单条文本向量化。
     */
    public float[] embed(String text) {
        if (!isConfigured() || text == null || text.isBlank()) return null;
        try {
            var body = mapper.createObjectNode();
            body.put("model", model);
            body.put("input", text);

            var request = HttpRequest.newBuilder()
                    .uri(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Embedding API 返回 {}", response.statusCode());
                return null;
            }

            var root = mapper.readTree(response.body());
            var embedding = root.path("data").get(0).path("embedding");
            var arr = new float[embedding.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = (float) embedding.get(i).asDouble();
            return arr;
        } catch (Exception e) {
            log.error("Embedding 调用失败: {}", e.getMessage());
            return null;
        }
    }
}
