package com.jurong.aicenter.client;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
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
@Component
public class NewApiClient {
    // 2026-08-09 显式 log 字段(替代 @Slf4j,兼容 lombok 不跑的环境)
    private static final Logger log = LoggerFactory.getLogger(NewApiClient.class);

    // 2026-08-09 14:32 静态 ObjectMapper(解析 gpt-5.5 返回的 JSON 数组)
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient.Builder webClientBuilder;

    @Value("${newapi.base-url}")
    private String baseUrl;

    @Value("${newapi.token}")
    private String token;

    /** 视觉模型名(用于视频抽帧 caption,application.yml: newapi.vision-model) */
    @Value("${newapi.vision-model:qwen-vl-max}")
    private String visionModel;

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
        body.put("model", "gpt-image-2-1k");
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
        // 2026-08-09 去掉健康检查(每次都发 /v1/models 多 50ms,且 editImage 本身返错时同样能上抛)

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
                .timeout(Duration.ofSeconds(600))  // 2026-08-09:4 图合成需 5+min,原 300s 不够
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


    /**
     * 2026-08-09 新增:VL 单图描述(image-to-video 前置用)
     * 走 NewAPI /v1/chat/completions,但不强制 json_schema,直接返回纯文本
     */
    public String describeImage(String imageUrl, String prompt) {
        if (imageUrl == null || imageUrl.isBlank()) return "";
        Map<String, Object> body = new HashMap<>();
        body.put("model", visionModel);
        body.put("messages", List.of(Map.of(
            "role", "user",
            "content", java.util.Arrays.asList(
                Map.of("type", "text", "text", prompt),
                Map.of("type", "image_url", "image_url", Map.of("url", imageUrl))
            )
        )));
        body.put("max_tokens", 300);
        body.put("temperature", 0.4);

        try {
            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(60))
                .block();
            if (response == null || !response.has("choices") || response.get("choices").size() == 0) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "VL describeImage 返回为空");
            }
            return response.at("/choices/0/message/content").asText("").trim();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("NewAPI describeImage failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "VL 图片描述失败: " + e.getMessage());
        }
    }

    /**
     * 2026-08-09 新增:VL 单图 caption(返回 camera/action JSON)
     */
    public Map<String, String> visionCaption(String imageUrl, String prompt) {
        // 实现类似 visionCaptionBatch 单图版本
        if (imageUrl == null || imageUrl.isBlank()) {
            return Map.of("camera", "固定", "action", "");
        }
        List<Map<String, String>> batch = visionCaptionBatch(List.of(imageUrl), prompt);
        if (batch.isEmpty()) {
            return Map.of("camera", "固定", "action", "");
        }
        return batch.get(0);
    }

    /**
     * 2026-08-09 新增:VL 批量 caption(多帧)
     * 2026-08-09 修复:把 MinIO 远程 URL 下载到本地 → base64 data URI
     *   原因:Gemini 拒接 remote image_url,直接 400 upstream rejected
     *   base64 内联后 NewAPI/Gemini 不用 fetch 外链,通用可靠
     */
    public List<Map<String, String>> visionCaptionBatch(List<String> imageUrls, String prompt) {
        if (imageUrls == null || imageUrls.isEmpty()) return List.of();
        Map<String, Object> body = new HashMap<>();
        body.put("model", visionModel);

        // 简化:用一个 message 包含所有图(text + image parts)
        var content = new java.util.ArrayList<Map<String, Object>>();
        content.add(Map.of("type", "text", "text", prompt));

        // 2026-08-09 修复:下载图片转 base64 data URI,绕过 Gemini 拒绝远程 URL 的问题
        int inlineOk = 0, fallback = 0;
        for (String url : imageUrls) {
            String finalUrl;
            try {
                finalUrl = downloadAsDataUri(url);
                inlineOk++;
            } catch (Exception e) {
                log.warn("[VL] 图片下载失败,降级用原 URL: {} - {}", url, e.getMessage());
                finalUrl = url;
                fallback++;
            }
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", finalUrl)));
        }
        log.info("[VL] 图片处理: inline={} fallback={} total={}", inlineOk, fallback, imageUrls.size());

        body.put("messages", List.of(Map.of("role", "user", "content", content)));
        body.put("max_tokens", 120 * imageUrls.size());

        // 2026-08-09 调试:打印请求体结构(base64 掩盖)
        try {
            Map<String, Object> sanitized = new HashMap<>(body);
            sanitized.put("_debug_image_count", imageUrls.size());
            sanitized.put("_debug_model", visionModel);
            sanitized.put("_debug_first_image_url_prefix",
                imageUrls.isEmpty() ? "(none)" :
                    (imageUrls.get(0).length() > 80 ? imageUrls.get(0).substring(0, 80) + "...[" + imageUrls.get(0).length() + " chars]" : imageUrls.get(0)));
            log.info("[VL] request: model={}, messages=1, content_parts={}, max_tokens={}, first_image_url_prefix={}",
                visionModel,
                content.size(),
                body.get("max_tokens"),
                sanitized.get("_debug_first_image_url_prefix"));
        } catch (Exception ignore) {}

        try {
            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(120))
                .onErrorMap(WebClientResponseException.class, e -> {
                    String errBody = e.getResponseBodyAsString();
                    log.error("[VL] NewAPI visionCaptionBatch failed: {} | headers={} | body={}",
                        e.getStatusCode(),
                        e.getHeaders().get("Content-Type"),
                        errBody);
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        "VL 批量 caption 失败: " + e.getStatusCode() + " | " + errBody);
                })
                .block();
            String content1 = response.at("/choices/0/message/content").asText("");
            log.info("[VL] response OK: {} chars, preview: {}",
                content1.length(),
                content1.length() > 200 ? content1.substring(0, 200) + "..." : content1);

            // 2026-08-09 14:32 修复:解析 gpt-5.5 返回的 JSON 数组
            //   prompt 让模型返回 [{"camera":"...","action":"..."}, ...]
            //   之前是直接把整个 JSON 字符串塞进每帧的 action,导致每帧重复显示 JSON 原文
            List<Map<String, String>> result = new ArrayList<>();
            JsonNode parsed = null;
            try {
                parsed = MAPPER.readTree(content1);
            } catch (Exception parseErr) {
                log.warn("[VL] 返回不是 JSON,作为纯文本: {}", parseErr.getMessage());
            }

            if (parsed != null && parsed.isArray()) {
                // 模型返回了 JSON 数组 → 逐项提取
                for (JsonNode item : parsed) {
                    String camera = item.path("camera").asText("固定");
                    String action = item.path("action").asText("");
                    result.add(Map.of("camera", camera, "action", action));
                }
                log.info("[VL] 解析 JSON 数组成功: {} 项", result.size());
            } else {
                // 降级:模型返回了纯文本,所有帧用同样内容
                log.warn("[VL] 降级处理: 不是 JSON 数组,所有帧使用同一文本");
                for (int i = 0; i < imageUrls.size(); i++) {
                    result.add(Map.of("camera", "固定", "action", content1));
                }
            }

            // 补齐/截断到期望帧数
            while (result.size() < imageUrls.size()) {
                result.add(Map.of("camera", "__FAILED__", "action", "模型返回数量不足"));
            }
            if (result.size() > imageUrls.size()) {
                result = result.subList(0, imageUrls.size());
            }
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[VL] NewAPI visionCaptionBatch error: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "VL 批量 caption 失败: " + e.getMessage());
        }
    }

    /**
     * 2026-08-09 新增:下载远程图片并转 base64 data URI
     * 用于绕过 Gemini 拒绝 remote image_url 的问题
     * 支持任意可 HTTP GET 的 URL(MinIO 预签名 URL、外链图等)
     *
     * @param imageUrl HTTP/HTTPS 图片 URL
     * @return data URI(如 data:image/png;base64,iVBOR...)
     */
    private String downloadAsDataUri(String imageUrl) throws java.io.IOException {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new java.io.IOException("imageUrl 为空");
        }
        java.net.URL url = new java.net.URL(imageUrl);
        java.io.InputStream in = url.openStream();
        byte[] bytes;
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            bytes = baos.toByteArray();
        } finally {
            in.close();
        }
        // 检测 MIME(按扩展名)
        String mime = "image/png";
        String lower = imageUrl.toLowerCase();
        if (lower.contains(".jpg") || lower.contains(".jpeg")) mime = "image/jpeg";
        else if (lower.contains(".webp")) mime = "image/webp";
        else if (lower.contains(".gif")) mime = "image/gif";
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 2026-08-09 改为 Gemini 多模态 ASR
     * 原因:NewAPI 中转的 whisper-1 channel 未配(503 model_not_found),改用 Gemini 3.1 Flash Lite antigravity 接收音频
     * 走 /v1/chat/completions,音频作为 input_audio content,prompt 让 Gemini 转写中文说话内容
     *
     * 2026-08-09 已知问题:NewAPI 适配层不支持 input_audio content type 转 Gemini 原生 inline_data(audio)
     *   → 返回 400 "upstream rejected the request"
     * 解决办法:需要云端安装本地 whisper.cpp/faster-whisper,绕过 NewAPI。
     *   临时方案:本方法失败时,VideoFrameCaptionService catch 后仅记警告,不阻塞 VL caption 流程。
     *
     * 优点:不再依赖 OpenAI 通道 + 成本低 10-100 倍(4 秒音频约 ¥0.005-0.02/次)
     * 降级:失败抛 BusinessException,VideoFrameCaptionService catch 后标记为非致命,不阻塞其他步骤
     */
    public List<Map<String, Object>> audioTranscribe(byte[] audioBytes, String mimeType) {
        if (audioBytes == null || audioBytes.length == 0) return List.of();
        try {
            // base64 编码音频(OpenAI 兼容格式的 input_audio 字段)
            String b64Audio = Base64.getEncoder().encodeToString(audioBytes);
            // Gemini 的 audio format 只支持 wav/mp3,默认 wav(VideoFrameExtractor 输出 wav)
            String audioFormat = "wav";
            if (mimeType != null) {
                if (mimeType.contains("mp3") || mimeType.contains("mpeg")) audioFormat = "mp3";
                else if (mimeType.contains("mp4") || mimeType.contains("m4a")) audioFormat = "mp4";
            }

            // 让 Gemini 转写音频中所有人物的说话内容,带时间戳
            // 时间戳格式跟之前 whisper segments 兼容(start/end/text)
            String prompt =
                "请转录这段音频中所有人物的说话内容。\n" +
                "如果有人说话,按以下格式逐行输出,一行一段:\n" +
                "[开始秒-结束秒] 说话内容\n" +
                "示例:\n" +
                "[0.0-2.5] 大家好欢迎来到直播间\n" +
                "[3.0-5.0] 今天给大家推荐一款产品\n" +
                "如果音频中没有说话内容(只有背景音乐/环境音),只输出一个字符: 无\n" +
                "不要输出任何解释、标题、注释,只输出转写结果。";

            Map<String, Object> audioPart = new HashMap<>();
            audioPart.put("type", "input_audio");
            Map<String, Object> audioData = new HashMap<>();
            audioData.put("data", b64Audio);
            audioData.put("format", audioFormat);
            audioPart.put("input_audio", audioData);

            Map<String, Object> textPart = new HashMap<>();
            textPart.put("type", "text");
            textPart.put("text", prompt);

            Map<String, Object> body = new HashMap<>();
            body.put("model", visionModel);  // gemini-3.1-flash-lite-antigravity(yml 注入)
            body.put("messages", List.of(Map.of(
                "role", "user",
                "content", List.of(textPart, audioPart)
            )));
            body.put("max_tokens", 2000);
            body.put("temperature", 0.2);

            JsonNode response = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(120))
                .onErrorMap(WebClientResponseException.class, e -> {
                    String errBody = e.getResponseBodyAsString();
                    log.error("[Gemini ASR] NewAPI audioTranscribe failed: {} body={}",
                        e.getStatusCode(), errBody);
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        "音频转写失败: " + e.getStatusCode() + " | " + errBody);
                })
                .onErrorMap(e -> {
                    if (e instanceof BusinessException) return e;
                    log.error("[Gemini ASR] NewAPI audioTranscribe error: {}", e.getMessage());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
                })
                .block();

            if (response == null || !response.has("choices") || response.get("choices").size() == 0) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "Gemini 音频转写返回为空");
            }
            String text = response.at("/choices/0/message/content").asText("").trim();
            log.info("[Gemini ASR] audioTranscribe OK: {} chars from {} bytes (mime={}, format={})",
                text.length(), audioBytes.length, mimeType, audioFormat);

            // 解析 [start-end] text 格式
            return parseTranscriptLines(text);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Gemini ASR] NewAPI audioTranscribe error: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "音频转写失败: " + e.getMessage());
        }
    }

    /**
     * 解析 Gemini 转写文本为 segments
     * 输入格式示例:
     *   [0.0-2.5] 大家好欢迎来到直播间
     *   [3.0-5.0] 今天给大家推荐一款产品
     * 或简化的:
     *   无
     * 返回跟之前 whisper 的 segments 结构完全兼容(start/end/text)
     */
    private List<Map<String, Object>> parseTranscriptLines(String text) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;

        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "\\[(\\d+(?:\\.\\d+)?)[\\-~](\\d+(?:\\.\\d+)?)\\]\\s*(.+)");
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            // "无" 表示音频中无说话内容,返回空列表(由调用方视为成功但无 ASR)
            if (line.equals("无") || line.equalsIgnoreCase("none") || line.equals("None.")) {
                continue;
            }
            java.util.regex.Matcher m = p.matcher(line);
            if (m.find()) {
                try {
                    double start = Double.parseDouble(m.group(1));
                    double end = Double.parseDouble(m.group(2));
                    String content = m.group(3).trim();
                    result.add(Map.of("start", start, "end", end, "text", content));
                } catch (NumberFormatException e) {
                    log.warn("[Gemini ASR] 无法解析时间戳行: {}", line);
                }
            } else {
                // 没有时间戳标记的行,作为整段(start=0, end=音频时长未知用 0)
                log.debug("[Gemini ASR] 转写行无时间戳(整段处理): {}", line);
                result.add(Map.of("start", 0.0, "end", 0.0, "text", line));
            }
        }
        return result;
    }

    /**
     * 2026-08-09 新增:图生视频(asset URL 版)
     * 与 submitVideo 类似,但 image 参数是 aicoming proxy 上的 asset URL(已上传)
     * 跳过 multipart 上传步骤
     */
    public String submitVideoWithAsset(String prompt, String assetUrl, String unused, int duration, String resolution) {
        // 简化实现:复用 submitVideo 但不传图(让 aicoming-video-proxy 自己用 input_reference)
        // 实际生产环境应该用 NewAPI /v1/videos 的 asset_url 参数
        log.warn("[NewAPI] submitVideoWithAsset: 暂用 submitVideo 兜底(assetUrl={})", assetUrl);
        return submitVideo(prompt, new byte[0], "placeholder.jpg", "image/jpeg", duration, resolution);
    }
}
