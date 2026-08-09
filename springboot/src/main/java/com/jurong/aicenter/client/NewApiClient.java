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
import java.util.Base64;
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

    /**
     * 通过 asset_url 引用素材提交视频生成任务（严格按 Assets-API 参考手册 §5）。
     *
     * <p>与 {@link #submitVideo(String, byte[], String, String, int, String)} 的区别：
     * <ul>
     *   <li>后者：multipart 直传图片字节到 /v1/videos（一段式）</li>
     *   <li>本方法：图片先上传到 proxy 8080 /v1/assets 拿 asset_url，再以 JSON body 引用（两段式）</li>
     * </ul>
     *
     * <p>请求体（手册 §5 端到端流程，请求体字段名 image_urls 与 asset_url 二选一，已确认用 image_urls）：
     * <pre>{@code
     * {
     *   "model": "doubao-seedance-2.0",
     *   "prompt": "用户提示词",
     *   "image_urls": ["asset://aic_xxx"],
     *   "duration": "4",        // 字符串，不是 int（api_client.py 已踩坑）
     *   "resolution": "480P"
     * }
     * }</pre>
     *
     * <p>走 NewAPI 3000（不是 proxy 8080）。Header: Authorization: Bearer ${newapi.token}。
     *
     * @param prompt     用户提示词（原样传，不做 enhance）
     * @param assetUrl   形如 asset://aic_xxx（必须 status=active 之后才能引用）
     * @param model      模型名，默认 doubao-seedance-2.0
     * @param duration   时长（秒）
     * @param resolution 分辨率，如 480P / 720P
     * @return NewAPI 返回的 task_id（id 或 task_id 字段）
     */
    public String submitVideoWithAsset(String prompt, String assetUrl,
                                       String model, int duration, String resolution) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "prompt 不能为空");
        }
        if (assetUrl == null || assetUrl.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "assetUrl 不能为空");
        }
        final String useModel = (model != null && !model.isBlank()) ? model : "doubao-seedance-2.0";
        // aicoming 只接受小写 resolution（480p/720p/1080p/4k），大写会报 invalid_resolution
        final String useResolution = (resolution != null && !resolution.isBlank())
            ? resolution.toLowerCase() : "480p";

        // 请求体：image_urls 数组引用 asset_url（手册 §5）
        Map<String, Object> body = new HashMap<>();
        body.put("model", useModel);
        body.put("prompt", prompt);
        body.put("image_urls", List.of(assetUrl));
        body.put("duration", String.valueOf(duration));   // 字符串，不是 int
        body.put("resolution", useResolution);

        // 关键日志：打印完整请求体（不含 token），方便排查 image_urls vs asset_url 字段名问题
        log.info("[VIDEO-SUBMIT] → POST {}/v1/videos (JSON): model={}, promptLen={}, image_urls=[{}], "
                + "duration={}, resolution={}",
            baseUrl, useModel, prompt.length(), assetUrl, duration, useResolution);
        log.info("[VIDEO-SUBMIT] → 完整请求体: {}", body);

        try {
            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/videos")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(600))
                .onErrorMap(WebClientResponseException.class, e -> {
                    String respBody = e.getResponseBodyAsString();
                    log.error("[VIDEO-SUBMIT] ← HTTP {}: body={}", e.getStatusCode(), respBody);
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        "视频任务提交失败: " + e.getStatusCode() + " | " + respBody);
                })
                .block();

            // 关键日志：打印 NewAPI 完整响应，能看到 aicoming 实际接受/拒绝的字段
            log.info("[VIDEO-SUBMIT] ← 响应: {}", truncateForLog(
                response == null ? "null" : response.toString(), 2000));

            if (response == null) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                    "NewAPI 视频提交（asset 模式）返回空");
            }
            String taskId = response.path("id").asText(response.path("task_id").asText(""));
            if (taskId.isEmpty()) {
                log.error("[VIDEO-SUBMIT] ← 响应中没找到 task_id: {}", response);
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                    "NewAPI 响应里没找到 task_id: " + response);
            }
            log.info("[VIDEO-SUBMIT] ← 提交成功: taskId={}, assetUrl={}, duration={}s, resolution={}",
                taskId, assetUrl, duration, useResolution);
            return taskId;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[VIDEO-SUBMIT] ← 异常: {}", e.getMessage(), e);
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

    /**
     * 快速健康检查 —— 检测 NewAPI 服务是否可用
     * <p>
     * GET /v1/models，超时 5 秒。
     * 用于在正式调用前快速判断 NewAPI 是否可达。
     *
     * @return true 表示服务可用
     */
    public boolean checkHealth() {
        try {
            webClientBuilder.baseUrl(baseUrl).build()
                .get()
                .uri("/v1/models")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(5))
                .block();
            return true;
        } catch (Exception e) {
            log.warn("NewAPI health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 调用 NewAPI 图片生成接口（gpt-image-2-2k）
     * <p>
     * POST /v1/images/generations
     * 超时：5 分钟（300 秒）。
     * 降级：调用前先检查 NewAPI 健康状态，不可达时直接抛异常。
     *
     * @param prompt  图片生成提示词
     * @param size    图片尺寸，默认 1024x1024
     * @param quality 图片质量，默认 standard
     * @param style   图片风格，默认 vivid
     * @return 生成的图片 URL
     */
    public String generateImage(String prompt, String size, String quality, String style) {
        // 快速健康检查，5 秒超时，避免 NewAPI 不可达时长时间挂起
        if (!checkHealth()) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "NewAPI 服务不可用，请稍后再试");
        }

        // 构建请求体
        // gpt-image-2-2k 模型使用 b64_json 格式返回图片数据
        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-image-2-2k");
        body.put("prompt", prompt);
        body.put("size", size != null ? size : "1024x1024");
        body.put("quality", quality != null ? quality : "standard");
        body.put("style", style != null ? style : "vivid");
        body.put("response_format", "b64_json");

        try {
            // 调用 NewAPI 图片生成接口，超时 5 分钟
            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/images/generations")
                .header("Authorization", "Bearer " + token)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(300))  // 5 分钟超时
                .onErrorMap(WebClientResponseException.class, e -> {
                    log.error("NewAPI /v1/images/generations failed: {} {}",
                        e.getStatusCode(), e.getResponseBodyAsString());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        "图片生成失败: " + e.getStatusCode() + " " + e.getStatusText());
                })
                .onErrorMap(e -> {
                    if (e instanceof BusinessException) return e;
                    log.error("NewAPI generateImage error: {}", e.getMessage());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
                })
                .block();

            if (response == null) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "图片生成返回为空（响应为 null）");
            }

            // 打印响应结构用于调试
            java.util.List<String> topFields = new java.util.ArrayList<>();
            response.fieldNames().forEachRemaining(topFields::add);
            log.info("NewAPI 响应顶层字段: {}", topFields);

            // 检查 data 数组
            if (!response.has("data")) {
                // 有些 NewAPI 可能直接返回 URL 在顶层
                if (response.has("url") && response.get("url").isTextual()) {
                    String imageUrl = response.get("url").asText();
                    log.info("NewAPI image generated OK (top-level url): {}", imageUrl);
                    return imageUrl;
                }
                // 检查顶层 b64_json
                if (response.has("b64_json") && response.get("b64_json").isTextual()) {
                    String b64Data = response.get("b64_json").asText();
                    log.info("NewAPI image generated OK (top-level b64_json): b64Len={}", b64Data.length());
                    return "data:image/png;base64," + b64Data;
                }
                // 返回错误信息
                String errMsg = response.has("error") ? response.get("error").toString() : "未知错误";
                log.error("NewAPI generateImage 返回错误: {}, 完整响应: {}", errMsg, response.toString());
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "NewAPI 返回错误: " + errMsg);
            }

            JsonNode dataArray = response.get("data");
            if (!dataArray.isArray() || dataArray.size() == 0) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "图片生成 data 数组为空");
            }

            JsonNode first = dataArray.get(0);
            // 打印 data[0] 字段名用于调试
            java.util.List<String> firstFieldNames = new java.util.ArrayList<>();
            first.fieldNames().forEachRemaining(firstFieldNames::add);
            log.info("NewAPI data[0] 字段: {}", firstFieldNames);

            // 尝试多种可能的 URL 字段名
            String[] urlFields = {"url", "image_url", "imageUrl", "img_url", "imgUrl"};
            for (String field : urlFields) {
                if (first.has(field) && first.get(field).isTextual()) {
                    String imageUrl = first.get(field).asText();
                    log.info("NewAPI image generated OK ({}): promptLen={}", field, prompt.length());
                    return imageUrl;
                }
            }

            // 检查 b64_json 字段 — 返回 data URI 前缀的 base64 字符串
            if (first.has("b64_json") && first.get("b64_json").isTextual()) {
                String b64Data = first.get("b64_json").asText();
                log.info("NewAPI image generated OK (b64_json): promptLen={}, b64Len={}", prompt.length(), b64Data.length());
                return "data:image/png;base64," + b64Data;
            }

            // data[0] 中未找到图片数据字段，将实际字段名包含在错误信息中
            String dataContent = first.toString().length() > 500 ? first.toString().substring(0, 500) : first.toString();
            log.error("NewAPI data[0] 中未找到图片数据字段。data[0] 字段: {}, 内容: {}", firstFieldNames, dataContent);
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "图片生成响应中未找到图片数据字段，data[0] 包含字段: " + firstFieldNames);
        } catch (Exception e) {
            if (e instanceof BusinessException) throw e;
            log.error("NewAPI generateImage failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "图片生成失败: " + e.getMessage());
        }
    }

    /**
     * 简化版图片生成：使用默认参数
     *
     * @param prompt 图片生成提示词
     * @return 生成的图片 URL
     */
    public String generateImage(String prompt) {
        return generateImage(prompt, null, null, null);
    }

    /**
     * 调用 NewAPI 图片编辑接口（/v1/images/edits）
     * 将用户引用的图片作为素材，结合提示词生成新图片。
     * 使用 multipart/form-data 格式上传引用图片。
     *
     * @param prompt          生成提示词
     * @param referenceImages 引用图片列表（base64 data URI 格式，如 data:image/png;base64,...）
     * @param size            图片尺寸
     * @param quality         图片质量
     * @param style           图片风格
     * @return 生成的图片（base64 data URI 格式或 URL）
     */
    public String editImage(String prompt, List<String> referenceImages,
                            String size, String quality, String style) {
        if (!checkHealth()) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "NewAPI 服务不可用，请稍后再试");
        }

        // 构建 multipart 请求体
        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();

        // 添加文本参数
        bodyBuilder.part("model", "gpt-image-2-2k");
        bodyBuilder.part("prompt", prompt);
        bodyBuilder.part("size", size != null ? size : "1024x1024");
        if (quality != null) bodyBuilder.part("quality", quality);
        if (style != null) bodyBuilder.part("style", style);
        bodyBuilder.part("response_format", "b64_json");

        // 解码 base64 引用图片并添加为 multipart 文件
        int imgIndex = 0;
        for (String dataUri : referenceImages) {
            try {
                byte[] imageBytes = decodeDataUri(dataUri);
                String mimeType = getMimeTypeFromDataUri(dataUri);
                String ext = mimeType.equals("image/jpeg") ? ".jpg" : ".png";
                final int currentIdx = imgIndex;

                // gpt-image 模型支持多张参考图，使用 image 字段
                bodyBuilder.part("image", new ByteArrayResource(imageBytes) {
                    @Override
                    public String getFilename() {
                        return "reference_" + currentIdx + ext;
                    }
                }).contentType(MediaType.parseMediaType(mimeType));
                imgIndex++;
            } catch (Exception e) {
                log.warn("解码引用图片 {} 失败: {}", imgIndex, e.getMessage());
                imgIndex++;
            }
        }

        if (imgIndex == 0) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "引用图片解码失败，无法进行图片编辑");
        }

        log.info("调用 NewAPI /v1/images/edits: promptLen={}, refImageCount={}", prompt.length(), imgIndex);

        try {
            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/images/edits")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(bodyBuilder.build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(300))  // 5 分钟超时
                .onErrorMap(WebClientResponseException.class, e -> {
                    log.error("NewAPI /v1/images/edits failed: {} {}",
                        e.getStatusCode(), e.getResponseBodyAsString());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        "图片编辑失败: " + e.getStatusCode() + " " + e.getStatusText());
                })
                .onErrorMap(e -> {
                    if (e instanceof BusinessException) return e;
                    log.error("NewAPI editImage error: {}", e.getMessage());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
                })
                .block();

            if (response == null) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "图片编辑返回为空（响应为 null）");
            }

            // 打印响应结构用于调试
            java.util.List<String> topFields = new java.util.ArrayList<>();
            response.fieldNames().forEachRemaining(topFields::add);
            log.info("NewAPI editImage 响应顶层字段: {}", topFields);

            // 解析响应（与 generateImage 相同的逻辑）
            if (!response.has("data")) {
                if (response.has("url") && response.get("url").isTextual()) {
                    return response.get("url").asText();
                }
                if (response.has("b64_json") && response.get("b64_json").isTextual()) {
                    return "data:image/png;base64," + response.get("b64_json").asText();
                }
                String errMsg = response.has("error") ? response.get("error").toString() : "未知错误";
                log.error("NewAPI editImage 返回错误: {}, 完整响应: {}", errMsg, response.toString());
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "NewAPI 返回错误: " + errMsg);
            }

            JsonNode dataArray = response.get("data");
            if (!dataArray.isArray() || dataArray.size() == 0) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "图片编辑 data 数组为空");
            }

            JsonNode first = dataArray.get(0);
            java.util.List<String> firstFieldNames = new java.util.ArrayList<>();
            first.fieldNames().forEachRemaining(firstFieldNames::add);
            log.info("NewAPI editImage data[0] 字段: {}", firstFieldNames);

            // 检查 b64_json
            if (first.has("b64_json") && first.get("b64_json").isTextual()) {
                String b64Data = first.get("b64_json").asText();
                log.info("NewAPI editImage OK (b64_json): b64Len={}", b64Data.length());
                return "data:image/png;base64," + b64Data;
            }

            // 检查 URL 字段
            String[] urlFields = {"url", "image_url", "imageUrl"};
            for (String field : urlFields) {
                if (first.has(field) && first.get(field).isTextual()) {
                    String imageUrl = first.get(field).asText();
                    log.info("NewAPI editImage OK ({}): {}", field, imageUrl);
                    return imageUrl;
                }
            }

            String dataContent = first.toString().length() > 500 ? first.toString().substring(0, 500) : first.toString();
            log.error("NewAPI editImage data[0] 中未找到图片数据字段。字段: {}, 内容: {}", firstFieldNames, dataContent);
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "图片编辑响应中未找到图片数据字段，data[0] 包含字段: " + firstFieldNames);
        } catch (Exception e) {
            if (e instanceof BusinessException) throw e;
            log.error("NewAPI editImage failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "图片编辑失败: " + e.getMessage());
        }
    }

    /**
     * 从 base64 data URI 中解码图片字节
     * 支持格式：data:image/png;base64,xxxx 或 data:image/jpeg;base64,xxxx
     */
    private byte[] decodeDataUri(String dataUri) {
        String base64Data;
        if (dataUri.startsWith("data:")) {
            // data:image/png;base64,xxxx
            int commaIdx = dataUri.indexOf(",");
            if (commaIdx == -1) {
                throw new IllegalArgumentException("无效的 data URI 格式");
            }
            base64Data = dataUri.substring(commaIdx + 1);
        } else {
            base64Data = dataUri;
        }
        return Base64.getDecoder().decode(base64Data);
    }

    /**
     * 从 data URI 中提取 MIME 类型
     */
    private String getMimeTypeFromDataUri(String dataUri) {
        if (dataUri.startsWith("data:image/jpeg")) return "image/jpeg";
        if (dataUri.startsWith("data:image/jpg")) return "image/jpeg";
        if (dataUri.startsWith("data:image/webp")) return "image/webp";
        return "image/png"; // 默认 png
    }

    /** 把超长 JSON 字符串截断到指定长度，方便日志查看 */
    private String truncateForLog(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...(truncated, totalLen=" + s.length() + ")";
    }
}
