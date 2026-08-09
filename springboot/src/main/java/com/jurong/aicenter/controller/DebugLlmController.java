package com.jurong.aicenter.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurong.aicenter.client.NewApiClient;
import com.jurong.aicenter.service.impl.MediaServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;

/**
 * 2026-08-09 新增:LLM 诊断端点
 *
 * 用途:不依赖外部 curl,直接在 IDEA 启动后浏览器一行就能测:
 *   - GET http://localhost:8080/api/debug/llm-list           列出当前 yml 配置的 LLM 配置
 *   - POST http://localhost:8080/api/debug/llm-test          body: {model, prompt, imageUrl?} 测单个模型
 *   - GET http://localhost:8080/api/debug/llm-batch-test     一次性测 5 个模型(text-only)
 *
 * 加在 /api/debug/** 下,CanvasController 的 @RequestMapping("/api/canvas") 不冲突
 *
 * 注意:此 controller 暂未加权限拦截,仅供本地调试用
 */
@Slf4j
@RestController
@RequestMapping("/api/debug")
@RequiredArgsConstructor
public class DebugLlmController {

    private final NewApiClient newApiClient;
    private final WebClient.Builder webClientBuilder;

    @Value("${newapi.base-url}")
    private String baseUrl;

    @Value("${newapi.token}")
    private String token;

    @Value("${newapi.vision-model:qwen-vl-max}")
    private String visionModel;

    /**
     * 列出当前 LLM 配置(方便确认改完 yml 重启生效了)
     */
    @GetMapping("/llm-list")
    public Map<String, Object> llmList() {
        return Map.of(
            "baseUrl", baseUrl,
            "tokenPrefix", token.length() > 8 ? token.substring(0, 8) + "..." : token,
            "visionModel", visionModel
        );
    }

    /**
     * 测单个模型 + 可选图片
     * body: {model: "xxx", prompt: "hi", imageUrl?: "https://..."}
     */
    @PostMapping(value = "/llm-test", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> llmTest(@RequestBody Map<String, String> req) {
        String model = req.getOrDefault("model", visionModel);
        String prompt = req.getOrDefault("prompt", "say hi in 5 words");
        String imageUrl = req.getOrDefault("imageUrl", "");

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);

            if (imageUrl != null && !imageUrl.isBlank()) {
                // 多模态(text + image_url)
                var content = new ArrayList<Map<String, Object>>();
                content.add(Map.of("type", "text", "text", prompt));
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", imageUrl)));
                body.put("messages", List.of(Map.of("role", "user", "content", content)));
            } else {
                // 纯文本
                body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            }
            body.put("max_tokens", 50);

            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .block();

            String result = response.at("/choices/0/message/content").asText("");
            return Map.of(
                "ok", true,
                "model", model,
                "withImage", !imageUrl.isBlank(),
                "result", result,
                "durationMs", System.currentTimeMillis() - start
            );
        } catch (Exception e) {
            log.warn("[Debug] llmTest failed: {}", e.getMessage());
            return Map.of(
                "ok", false,
                "model", model,
                "withImage", !imageUrl.isBlank(),
                "error", e.getMessage(),
                "durationMs", System.currentTimeMillis() - start
            );
        }
    }

    /**
     * 一次性测 5 个候选模型(text-only + 1 图)
     * 不需要传参
     */
    @GetMapping("/llm-batch-test")
    public Map<String, Object> batchTest() {
        String[] textModels = {
            "gemini-3.1-flash-lite-antigravity",
            "qwen-vl-max",
            "deepseek-v4-flash",
            "gpt-4o",
            "gpt-4-vision-preview"
        };

        String testImage = "https://upload.wikimedia.org/wikipedia/commons/thumb/3/3a/Cat03.jpg/640px-Cat03.jpg";

        List<Map<String, Object>> results = new ArrayList<>();
        for (String model : textModels) {
            results.add(testOne(model, "say hi in 3 words", null));
            results.add(testOne(model, "图中有什么?(中文回答)", testImage));
        }

        return Map.of(
            "visionModelCurrent", visionModel,
            "baseUrl", baseUrl,
            "results", results
        );
    }

    private Map<String, Object> testOne(String model, String prompt, String imageUrl) {
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", model);

            if (imageUrl != null) {
                var content = new ArrayList<Map<String, Object>>();
                content.add(Map.of("type", "text", "text", prompt));
                content.add(Map.of("type", "image_url", "image_url", Map.of("url", imageUrl)));
                body.put("messages", List.of(Map.of("role", "user", "content", content)));
            } else {
                body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
            }
            body.put("max_tokens", 30);

            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(20))
                .block();

            String result = response.at("/choices/0/message/content").asText("");
            return Map.of(
                "ok", true,
                "model", model,
                "withImage", imageUrl != null,
                "result", result.length() > 100 ? result.substring(0, 100) : result,
                "durationMs", System.currentTimeMillis() - start
            );
        } catch (Exception e) {
            return Map.of(
                "ok", false,
                "model", model,
                "withImage", imageUrl != null,
                "error", String.valueOf(e.getMessage()).substring(0, Math.min(200, String.valueOf(e.getMessage()).length())),
                "durationMs", System.currentTimeMillis() - start
            );
        }
    }
}