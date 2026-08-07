package com.jurong.aicenter.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NewAPI 中转站客户端
 * 用于主动查询视频任务状态并下载产物
 *
 * 背景：ComfyUI 节点的 JurongImageToVideo 在 save_video_file 阶段偶发失败，
 * 导致 outputs 为空，Spring Boot 端拿不到 video_path。
 * 此接口允许手动补救：传入 NewAPI task_id → 查状态 → 拿 URL → 下载上传到 MinIO。
 */
@Slf4j
@Component
public class NewApiClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${newapi.base-url}")
    private String baseUrl;

    @Value("${newapi.token}")
    private String token;

    public NewApiClient(WebClient.Builder webClientBuilder) {
        this.webClientBuilder = webClientBuilder;
    }

    /**
     * 查询 NewAPI 视频任务状态
     * @param taskId NewAPI 返回的 task_id
     * @return NewAPI 响应 JSON（含 status / metadata.url 等字段）
     */
    public JsonNode pollVideo(String taskId) {
        try {
            return webClientBuilder.baseUrl(baseUrl).build()
                .get()
                .uri("/v1/videos/{taskId}", taskId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .onErrorMap(WebClientResponseException.class, e -> {
                    log.error("NewAPI /v1/videos/{} failed: {} {}",
                        taskId, e.getStatusCode(), e.getMessage());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        "NewAPI query failed: " + e.getMessage());
                })
                .onErrorMap(e -> {
                    if (e instanceof BusinessException) return e;
                    log.error("NewAPI /v1/videos/{} error: {}", taskId, e.getMessage());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
                })
                .block();
        } catch (Exception e) {
            if (e instanceof BusinessException) throw e;
            log.error("NewAPI pollVideo({}) failed: {}", taskId, e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
        }
    }

    /**
     * 同步等待 NewAPI 视频任务完成
     * @param taskId NewAPI task_id
     * @param timeoutSec 最大等待秒数
     * @return 最终状态（completed 时含 metadata.url）
     */
    public JsonNode waitForVideo(String taskId, int timeoutSec) {
        long start = System.currentTimeMillis();
        long timeoutMs = timeoutSec * 1000L;
        int pollInterval = 5;  // 秒
        String lastStatus = "";

        while (System.currentTimeMillis() - start < timeoutMs) {
            JsonNode result = pollVideo(taskId);
            String status = result != null && result.has("status") ?
                result.get("status").asText("unknown") : "unknown";

            if (!status.equals(lastStatus)) {
                log.info("NewAPI video task {} status: {}", taskId, status);
                lastStatus = status;
            }

            if ("completed".equalsIgnoreCase(status)
                || "succeeded".equalsIgnoreCase(status)
                || "success".equalsIgnoreCase(status)) {
                return result;
            }
            if ("failed".equalsIgnoreCase(status)
                || "error".equalsIgnoreCase(status)
                || "cancelled".equalsIgnoreCase(status)) {
                throw new BusinessException(ErrorCode.NEWAPI_TASK_FAILED,
                    "NewAPI 视频任务失败: " + result.toString());
            }

            try {
                Thread.sleep(pollInterval * 1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "轮询被中断");
            }
        }
        throw new BusinessException(ErrorCode.NEWAPI_TASK_TIMEOUT,
            "NewAPI 视频任务超时 (" + timeoutSec + "s): " + taskId);
    }

    /**
     * 调用 NewAPI 的 Chat Completions（用于画布文本润色 / Agent 对话等）
     *
     * @param model        模型名（如 "deepseek-v4-flash"）
     * @param systemPrompt 系统提示词（可空）
     * @param userPrompt   用户输入
     * @param maxTokens    最大输出 token 数
     * @return 模型返回的文本内容
     */
    public String chatCompletion(String model, String systemPrompt, String userPrompt, int maxTokens) {
        List<Map<String, String>> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.7);

        try {
            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                // LLM 润色在 aicoming 队列里可能会等很久,60s 太少
                // 生产中会出现 "Did not observe any item within 60000ms" 导致文本节点失败
                .timeout(Duration.ofSeconds(180))
                .onErrorMap(WebClientResponseException.class, e -> {
                    log.error("NewAPI /v1/chat/completions failed: {} {}",
                        e.getStatusCode(), e.getResponseBodyAsString());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        "LLM 调用失败: " + e.getStatusCode());
                })
                .onErrorMap(e -> {
                    if (e instanceof BusinessException) return e;
                    log.error("NewAPI chat error: {}", e.getMessage());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
                })
                .block();

            if (response == null || !response.has("choices") || response.get("choices").size() == 0) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "LLM 返回为空");
            }
            JsonNode first = response.get("choices").get(0);
            JsonNode msg = first.get("message");
            if (msg == null || !msg.has("content")) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "LLM 返回无 content");
            }
            String content = msg.get("content").asText();
            log.info("LLM chat OK: model={}, inputLen={}, outputLen={}",
                model, userPrompt.length(), content.length());
            return content;
        } catch (Exception e) {
            if (e instanceof BusinessException) throw e;
            log.error("NewAPI chatCompletion failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
        }
    }

    /**
     * 提交 NewAPI 视频生成任务（图生视频 / 文生视频均可用）
     * 调用链：节点 → NewAPI → aicoming.top
     *
     * 关键坑（已踩）：
     *   - body.prompt 必须顶层（aicoming 强制要求）
     *   - input_reference 支持多张（同名 multipart part）
     *   - duration 是字符串 "4" 不是 int
     *   - aicoming-video-proxy 要求至少一个 multipart file，文生视频也要传占位
     *
     * @param prompt         用户输入提示词
     * @param imageBytes     上游图片字节（文生视频时传 null，内部用占位图）
     * @param imageFilename  文件名（aicoming 用来识别格式）
     * @param imageMime      MIME 类型，如 image/png
     * @param duration       视频时长（秒）
     * @param resolution     分辨率，如 480P
     * @return               NewAPI 返回的 task_id
     */
    public String submitVideo(String prompt, byte[] imageBytes, String imageFilename,
                              String imageMime, int duration, String resolution) {
        return submitVideo(prompt, imageBytes, imageFilename, imageMime, duration, resolution, null);
    }

    /**
     * 图生视频：调 NewAPI /v1/videos（走 aicoming-video-proxy）
     *
     * 必填字段（从 jurong-api-nodes/api_client.py 确认，跟能跑通的 Python 版本一致）：
     *   - model = "doubao-seedance-2.0"（唯一实测能处理 image ref 的模型）
     *   - prompt （顶层）
     *   - duration 字符串（如 "4"）
     *   - resolution "480P" / "720p" / "1080p" / "4k"
     *   - input_reference 第一帧图（multipart file）
     *
     * 注意：**不传 ratio 和 watermark**。Python 参考版本没这俩字段。
     * 我们之前多塞了可能让 aicoming 误判（已改回去）。
     */
    public String submitVideo(String prompt, byte[] imageBytes, String imageFilename,
                              String imageMime, int duration, String resolution, String ratio) {
        try {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("model", "doubao-seedance-2.0");
            builder.part("prompt", prompt);
            builder.part("duration", String.valueOf(duration));
            builder.part("resolution", resolution);
            // ratio / watermark 不传，参考能跑通的 Python api_client.py

            if (imageBytes != null && imageBytes.length > 0) {
                // 图生视频：传上游图片
                final String fname = imageFilename != null ? imageFilename : "canvas_input.png";
                final String mime = imageMime != null ? imageMime : "image/png";
                builder.part("input_reference",
                    new ByteArrayResource(imageBytes) {
                        @Override
                        public String getFilename() { return fname; }
                    },
                    MediaType.parseMediaType(mime));
            } else {
                // 文生视频：aicoming 也要求一个 file 字段，传 16x16 透明 PNG 占位
                final String placeholderName = "_placeholder.png";
                builder.part("input_reference",
                    new ByteArrayResource(DUMMY_PNG_BYTES) {
                        @Override
                        public String getFilename() { return placeholderName; }
                    },
                    MediaType.IMAGE_PNG);
            }

            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/videos")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(600))
                .onErrorMap(WebClientResponseException.class, e -> {
                    String body = e.getResponseBodyAsString();
                    log.error("NewAPI /v1/videos failed: {} body={}", e.getStatusCode(), body);
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        "视频任务提交失败: " + e.getStatusCode() + " | " + body);
                })
                .block();

            if (response == null) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "NewAPI 视频提交返回空");
            }
            String taskId = response.path("id").asText(response.path("task_id").asText(""));
            if (taskId.isEmpty()) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                    "NewAPI 响应里没找到 task_id: " + response);
            }
            log.info("NewAPI video task submitted: {} (image={}, size={}B, duration={}s, resolution={})",
                taskId,
                imageBytes != null ? imageFilename : "placeholder",
                imageBytes != null ? imageBytes.length : 0,
                duration, resolution);
            return taskId;
        } catch (Exception e) {
            if (e instanceof BusinessException) throw e;
            log.error("NewAPI submitVideo failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
        }
    }

    // 16x16 透明 PNG 占位图（文生视频时 aicoming-video-proxy 强制要求至少一个 file 字段）
    // 用 ImageIO 动态生成，避免手敲 hex / 拷 Python 语法错误
    private static final byte[] DUMMY_PNG_BYTES = createPlaceholderPng();

    private static byte[] createPlaceholderPng() {
        try {
            // BufferedImage 初始为全透明(0,0,0,0)，Aicoming 不会在意图片内容
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", baos);
            byte[] bytes = baos.toByteArray();
            log.info("Generated placeholder PNG: {} bytes", bytes.length);
            return bytes;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to generate placeholder PNG bytes", e);
        }
    }

    /**
     * 从 NewAPI 响应中提取视频下载 URL
     * 兼容多种返回格式
     */
    public String extractVideoUrl(JsonNode pollResult) {
        if (pollResult == null) return null;

        // 形态 1：metadata.url
        JsonNode metadata = pollResult.get("metadata");
        if (metadata != null && metadata.isObject()) {
            JsonNode url = metadata.get("url");
            if (url != null && url.isTextual()) {
                return url.asText();
            }
        }

        // 形态 2：result.metadata.url
        JsonNode result = pollResult.get("result");
        if (result != null && result.isObject()) {
            JsonNode innerMeta = result.get("metadata");
            if (innerMeta != null && innerMeta.isObject()) {
                JsonNode url = innerMeta.get("url");
                if (url != null && url.isTextual()) {
                    return url.asText();
                }
            }
        }

        // 形态 3：直接在 result 里
        if (result != null) {
            JsonNode url = result.get("url");
            if (url != null && url.isTextual()) {
                return url.asText();
            }
        }

        // 形态 4：顶层 url
        JsonNode topUrl = pollResult.get("url");
        if (topUrl != null && topUrl.isTextual()) {
            return topUrl.asText();
        }

        log.warn("Could not extract video URL from NewAPI response: {}", pollResult);
        return null;
    }
}
