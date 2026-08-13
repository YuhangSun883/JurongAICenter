package com.jurong.aicenter.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.dto.video.VideoOptions;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

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

    /**
     * 2026-08-11 新增:submit 视频任务的完整结果。
     * - taskId:NewAPI 任务 ID(必填)
     * - url:视频 URL(可能为 null,某些场景 submit 响应里就有,某些需要后续 poll 拿)
     */
    public record SubmitResult(String taskId, String url) {
        public boolean hasUrl() { return url != null && !url.isBlank(); }
    }

    /**
     * 2026-08-13 DEBUG:递归收集 JSON 顶层 + 二级字段名,排查"字段名变了"类问题
     */
    private static String collectFieldNames(JsonNode node) {
        if (node == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        collectFieldNamesRecursive(node, sb, 0, 3);  // 最多 3 层
        return sb.toString();
    }

    private static void collectFieldNamesRecursive(JsonNode node, StringBuilder sb, int depth, int maxDepth) {
        if (depth >= maxDepth || node == null || !node.isObject()) return;
        boolean first = true;
        for (java.util.Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            if (!first) sb.append(", ");
            first = false;
            String name = it.next();
            sb.append(name);
            JsonNode child = node.get(name);
            if (child != null && child.isObject()) {
                sb.append("{");
                StringBuilder childSb = new StringBuilder();
                collectFieldNamesRecursive(child, childSb, depth + 1, maxDepth);
                sb.append(childSb);
                sb.append("}");
            } else if (child != null && child.isArray() && child.size() > 0) {
                sb.append("[]");
            }
        }
    }


    // 2026-08-09 显式 log 字段(替代 @Slf4j,兼容 lombok 不跑的环境)
    private static final Logger log = LoggerFactory.getLogger(NewApiClient.class);

    // 2026-08-09 14:32 静态 ObjectMapper(解析 gpt-5.5 返回的 JSON 数组)
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${newapi.base-url}")
    private String baseUrl;

    // 2026-08-12 added:视频生成/查询专用端点,走 aicoming-proxy 8080(持久化,元数据不清理)
    //   baseUrl = NewAPI 中转站 3000 (task_xxx 格式, 5 retries 后元数据被清理 -> 拿到不了 video URL)
    //   videoBaseUrl = aicoming-proxy 8080 (vid_xxx 格式, 元数据持久化 -> 每次都能拿到)
    //   两者 token 共享。参考文档 §5.5:资产 CRUD 必走 8080,视频生成走 NewAPI 中转站;
    //   这里反向优化:视频也走 8080 避免元数据 TTL 清理问题。
    @Value("${newapi.video-base-url:http://192.140.163.161:3000}")
    private String videoBaseUrl;

    @Value("${newapi.token}")
    private String token;

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
        // 2026-08-12 根治:先走 aicoming-proxy 8080 (拿即时状态,如 in_progress),
        //   如果返回 4xx (元数据被清理) 或 5xx,立即 fallback 到 NewAPI 3000 (真实视频服务,视频持久化)
        //   修复原因:用户澄清 "aicoming 控制台看到的视频" 都是在 NewAPI 3000 上的,aicoming-proxy 8080
        //     只持久化元数据,视频生成完成后元数据被清理。真实视频 URL 一直在 NewAPI 3000 + CDN。
        // 2026-08-13 16:30 修正:实测 3000 中转站 token 全部 401(curl /api/user/self),
        //   走 8080 task_not_exist 不再 fallback 到 3000(反正 401 也拿不到),
        //   直接抛 NEWAPI_TASK_NOT_FOUND 让 @Scheduled 走 TNF 保留 RUNNING 路径。
        try {
            return pollVideoFromUrl(videoBaseUrl, taskId);
        } catch (BusinessException primary) {
            boolean isFallback = primary.getCode() == ErrorCode.NEWAPI_UNREACHABLE.getCode();
            if (!isFallback) {
                throw primary;
            }
            log.warn("[NewAPI] pollVideo 8080 网络错误, fallback 到 NewAPI 3000: taskId={}, err={}",
                taskId, primary.getMessage());
            try {
                return pollVideoFromUrl(baseUrl, taskId);
            } catch (BusinessException fallback) {
                log.error("[NewAPI] pollVideo 3000 也失败: taskId={}, err={}", taskId, fallback.getMessage());
                throw primary;
            }
        }
    }

    /**
     * 内部方法:对指定 base URL 发起 /v1/videos/{taskId} 查询。
     */
    private JsonNode pollVideoFromUrl(String base, String taskId) {
        long pollStart = System.currentTimeMillis();
        log.info("┌─ [POLL-DEBUG] pollVideoFromUrl START: base={}, taskId={}", base, taskId);
        JsonNode pollResult = null;
        try {
            pollResult = webClientBuilder.baseUrl(base).build()
                .get()
                .uri("/v1/videos/{taskId}", taskId)
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(30))
                .onErrorMap(WebClientResponseException.class, e -> {
                    String respBody = e.getResponseBodyAsString();
                    log.error("NewAPI /v1/videos/{} failed: {} body={}",
                        taskId, e.getStatusCode(), respBody);
                    ErrorCode code = (e.getStatusCode().is4xxClientError())
                        ? ErrorCode.NEWAPI_TASK_NOT_FOUND
                        : ErrorCode.NEWAPI_UNREACHABLE;
                    return new BusinessException(code,
                        "NewAPI query failed: HTTP " + e.getStatusCode()
                            + (respBody != null ? " body=" + respBody : ""));
                })
                .onErrorMap(e -> {
                    if (e instanceof BusinessException) return e;
                    log.error("NewAPI /v1/videos/{} error: {}", taskId, e.getMessage());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
                })
                .block();
        } catch (Exception e) {
            long pollElapsed = System.currentTimeMillis() - pollStart;
            log.info("└─ [POLL-DEBUG] pollVideoFromUrl FAILED: taskId={}, 耗时 {}ms, err={}", taskId, pollElapsed, e.getMessage());
            if (e instanceof BusinessException) throw e;
            log.error("NewAPI pollVideo({}) failed: {}", taskId, e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
        }
        long pollElapsed = System.currentTimeMillis() - pollStart;
        if (pollResult == null) {
            log.info("└─ [POLL-DEBUG] pollVideoFromUrl DONE: taskId={}, 耗时 {}ms, result=null", taskId, pollElapsed);
        } else {
            String status = pollResult.path("status").asText("(no status field)");
            String fullStr = pollResult.toString();
            String preview = fullStr.length() < 1000 ? fullStr : fullStr.substring(0, 1000) + "...";
            // 2026-08-13 加日志:列出所有顶层字段名,排查 status/url 在哪
            String fieldNames = collectFieldNames(pollResult);
            log.info("└─ [POLL-DEBUG] pollVideoFromUrl DONE: taskId={}, 耗时 {}ms, status={}, fields=[{}], body={}",
                taskId, pollElapsed, status, fieldNames, preview);
        }
        return pollResult;
    }

    public String audioTranscription(byte[] audioBytes, String filename, String language) {
        if (audioBytes == null || audioBytes.length == 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "audioTranscription: audioBytes 为空");
        }
        if (audioBytes.length > 25 * 1024 * 1024) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "audioTranscription: 音频/视频文件超 25MB (size=" + audioBytes.length + ")");
        }
        final String useLang = (language == null || language.isBlank()) ? "zh" : language;
        final String fname = (filename != null && !filename.isBlank()) ? filename : "transcribe.mp4";

        log.info("[NewAPI] audioTranscription: {} 字节, filename={}, language={}",
            audioBytes.length, fname, useLang);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        final String finalFname = fname;
        builder.part("file", new org.springframework.core.io.ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() { return finalFname; }
        });
        builder.part("model", "gpt-4o-transcribe");
        builder.part("language", useLang);

        JsonNode response = webClientBuilder.baseUrl(videoBaseUrl).build()
            .post()
            .uri("/v1/audio/transcriptions")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(builder.build()))
            .retrieve()
            .bodyToMono(JsonNode.class)
            .timeout(Duration.ofSeconds(120))
            .onErrorMap(WebClientResponseException.class, e -> {
                String respBody = e.getResponseBodyAsString();
                log.error("[NewAPI] audioTranscription failed: {} body={}", e.getStatusCode(), respBody);
                String friendly = translateNewApiError(respBody);
                return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                    "NewAPI audioTranscription failed: HTTP " + e.getStatusCode() + " " + friendly);
            })
            .block();

        if (response == null) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "NewAPI audioTranscription: 响应为空");
        }
        String text = response.path("text").asText("");
        if (text.isBlank()) {
            log.error("[NewAPI] audioTranscription: 响应 text 字段为空: {}", response);
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "NewAPI audioTranscription: 转写结果为空");
        }
        log.info("[NewAPI] audioTranscription OK: {} 字符", text.length());
        return text;
    }

    /**
     * 同步等待 NewAPI 视频任务完成
     * @param taskId NewAPI task_id
     * @param timeoutSec 最大等待秒数
     * @return 最终状态（completed 时含 metadata.url）
     *
     * <p>2026-08-11 升级：加假完成检测（aicoming 偶发 status=completed 但响应里没 URL），
     * 文档 §7 / Python wait_for_video 都有此逻辑。最多重试 3 次（间隔 2×poll_interval）。
     */
    public JsonNode waitForVideo(String taskId, int timeoutSec) {
        long start = System.currentTimeMillis();
        long timeoutMs = timeoutSec * 1000L;
        int pollInterval = 10;  // 秒（文档示例 15s，项目 10s 折中）
        int maxRetryAfterCompleted = 3;
        int completedNoUrlRetries = 0;
        String lastStatus = "";
        JsonNode lastSuccessResult = null;

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
                lastSuccessResult = result;
                // 假完成检测：completed 但没有 URL
                String url = extractVideoUrl(result);
                if (url != null && !url.isBlank()) {
                    return result;
                }
                completedNoUrlRetries++;
                log.warn("NewAPI video task {} status=completed but no URL (retry {}/{}). Response: {}",
                    taskId, completedNoUrlRetries, maxRetryAfterCompleted, result);
                if (completedNoUrlRetries >= maxRetryAfterCompleted) {
                    throw new BusinessException(ErrorCode.NEWAPI_TASK_FAILED,
                        "aicoming 假完成（" + maxRetryAfterCompleted + " 次 completed 都无 URL）。最后响应: " + result);
                }
                try {
                    Thread.sleep(pollInterval * 2 * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "轮询被中断");
                }
                continue;
            }
            if ("failed".equalsIgnoreCase(status)
                || "error".equalsIgnoreCase(status)
                || "cancelled".equalsIgnoreCase(status)) {
                throw new BusinessException(ErrorCode.NEWAPI_TASK_FAILED,
                    "NewAPI 视频任务失败: " + (result != null ? result.toString() : "null"));
            }

            try {
                Thread.sleep(pollInterval * 1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "轮询被中断");
            }
        }
        throw new BusinessException(ErrorCode.NEWAPI_TASK_TIMEOUT,
            "NewAPI 视频任务超时 (" + timeoutSec + "s, taskId=" + taskId + ")");
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
                        "LLM 调用失败:" + e.getStatusCode());
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
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "LLM 响应格式错误:缺少 message.content");
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
     * 多模态 chat completion（支持图片理解）。
     *
     * <p>调用 /v1/chat/completions，传 user message 时使用 OpenAI 多模态格式：
     * <pre>
     * content: [
     *   {type:"text", text:"..."},
     *   {type:"image_url", image_url:{url:"https://..."}}
     * ]
     * </pre>
     *
     * <p>如果 NewAPI 中转服务器访问不到公网 URL（实测 claude-sonnet-4-6 这种情况），
     * 本方法会自动下载图片 → 转 Base64 data URI 后再传，避免中转服务器去访问外网。
     *
     * @param model        多模态模型（如 claude-sonnet-4-6）
     * @param systemPrompt 系统 prompt
     * @param userText     用户文本（不含图片）
     * @param imageUrls    公网图片 URL 列表（可以是 MinIO/OSS 内部地址，会被本服务下载）
     * @param maxTokens    最大输出 token
     * @return LLM 回复内容
     */
    public String chatCompletionWithImages(String model, String systemPrompt, String userText,
                                            List<String> imageUrls, int maxTokens) {
        // 组装 user content: [text, image_url, image_url, ...]
        List<Map<String, Object>> userContent = new ArrayList<>();
        userContent.add(Map.of("type", "text", "text", userText == null ? "" : userText));
        if (imageUrls != null) {
            for (String url : imageUrls) {
                if (url == null || url.isBlank()) continue;
                String finalUrl = url;
                try {
                    // 尝试下载 + 转 base64（应对 NewAPI 中转服务器无法访问公网 URL 的情况）
                    finalUrl = downloadAsDataUri(url);
                } catch (Exception e) {
                    log.warn("[chatCompletionWithImages] failed to download image as base64, fallback to URL: url={}, err={}",
                        url, e.getMessage());
                    // 下载失败就退回到直接传 URL（部分模型/中转可能能访问）
                }
                userContent.add(Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", finalUrl)
                ));
            }
        }

        List<Map<String, Object>> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userContent));

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
                .timeout(Duration.ofSeconds(180))
                .onErrorMap(WebClientResponseException.class, e -> {
                    log.error("NewAPI multimodal chat failed: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        "LLM multimodal failed: " + e.getStatusCode());
                })
                .onErrorMap(e -> {
                    if (e instanceof BusinessException) return e;
                    log.error("NewAPI multimodal error: {}", e.getMessage());
                    return new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
                })
                .block();

            if (response == null || !response.has("choices") || response.get("choices").size() == 0) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "LLM multimodal response is empty");
            }
            JsonNode first = response.get("choices").get(0);
            JsonNode msg = first.get("message");
            if (msg == null || !msg.has("content")) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "LLM multimodal content missing");
            }
            String content = msg.get("content").asText();
            log.info("LLM multimodal OK: model={}, images={}, inputTextLen={}, outputLen={}",
                model, imageUrls == null ? 0 : imageUrls.size(), userText == null ? 0 : userText.length(), content.length());
            return content;
        } catch (Exception e) {
            if (e instanceof BusinessException) throw e;
            log.error("NewAPI chatCompletionWithImages failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
        }
    }

    // downloadAsDataUri 已在文件下方定义（line 1019 附近），直接复用。

    /**
     * 调 NewAPI /v1/videos（走 aicoming-video-proxy），multipart 单图直传。
     *
     * <p>调用链：Java → NewAPI (jurong) → aicoming-video-proxy
     *
     * <p>关键坑（已踩）：
     * <ul>
     *   <li>body.prompt 必须顶层（aicoming 强制要求）</li>
     *   <li>input_reference 支持多张（同名 multipart part）</li>
     *   <li>duration 是字符串 "4" 不是 int</li>
     *   <li>aicoming-video-proxy 要求至少一个 multipart file，文生视频也要传占位</li>
     * </ul>
     *
     * @param prompt         用户输入提示词
     * @param imageBytes     上游图片字节（文生视频时传 null，内部用占位图）
     * @param imageFilename  文件名（aicoming 用来识别格式）
     * @param imageMime      MIME 类型，如 image/png
     * @param duration       视频时长（秒）
     * @param resolution     分辨率，如 480p（小写）
     * @return               NewAPI 返回的 task_id
     */
    public String submitVideo(String prompt, byte[] imageBytes, String imageFilename,
                              String imageMime, int duration, String resolution) {
        SubmitResult r = submitVideoFull(prompt, imageBytes, imageFilename, imageMime, duration, resolution, null);
        return r.taskId();
    }

    /**
     * 2026-08-11 新增:多图版 submitVideo(主图 multipart + 附加 URL 列表)。
     * 用于视频节点有多个上游 image 节点的场景(三视图+换装帧图+其他)。
     */
    public String submitVideo(String prompt, byte[] imageBytes, String imageFilename,
                              String imageMime, int duration, String resolution,
                              java.util.List<String> additionalImageUrls) {
        // 走 submitVideoMultiImage 方法(直接构造 multipart + 附加 URL)
        return submitVideoMultiImage(prompt, imageBytes, imageFilename, imageMime,
            duration, resolution, additionalImageUrls).taskId();
    }

    /**
     * 2026-08-11 新增:多图版 submitVideo 内部实现。
     * 主图走 multipart input_reference,附加 URL 通过 image_urls JSON 字段附加。
     */
    private SubmitResult submitVideoMultiImage(String prompt, byte[] imageBytes, String imageFilename,
                              String imageMime, int duration, String resolution,
                              java.util.List<String> additionalImageUrls) {
        try {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("model", "doubao-seedance-2.0");
            builder.part("prompt", prompt);
            builder.part("duration", String.valueOf(duration));
            // 2026-08-13 14:25 修复:严格对齐聚融 v2.1 文档 §7(resolution 必须小写 "480p"/"720p"/"1080p")
            //   之前原样转发,如果上游传 "480P" 会被原样发出,aicoming 上游会拒
            builder.part("resolution", resolution == null ? "480p" : resolution.toLowerCase());

            // 主图(如果有 bytes)走 multipart input_reference
            if (imageBytes != null && imageBytes.length > 0) {
                final String fname = imageFilename != null ? imageFilename : "canvas_input.png";
                final String mime = imageMime != null ? imageMime : "image/png";
                // 2026-08-13 FIX: 同步发 image/input_reference/image_url 3 个字段名(模仿 Python api_client.submit_video)
                // 原因:aicoming-proxy 8/13 改了期望字段名,只发 input_reference 会被忽略
                ByteArrayResource imgResource1 = new ByteArrayResource(imageBytes) {
                    @Override public String getFilename() { return fname; }
                };
                ByteArrayResource imgResource2 = new ByteArrayResource(imageBytes) {
                    @Override public String getFilename() { return fname; }
                };
                ByteArrayResource imgResource3 = new ByteArrayResource(imageBytes) {
                    @Override public String getFilename() { return fname; }
                };
                builder.part("image", imgResource1, MediaType.parseMediaType(mime));
                builder.part("input_reference", imgResource2, MediaType.parseMediaType(mime));
                builder.part("image_url", imgResource3, MediaType.parseMediaType(mime));
            }

            // 附加 URL 通过 image_urls JSON 字段附加(2026-08-11 新增多图)
            if (additionalImageUrls != null && !additionalImageUrls.isEmpty()) {
                try {
                    String imageUrlsJson = MAPPER.writeValueAsString(additionalImageUrls);
                    builder.part("image_urls", imageUrlsJson);
                } catch (Exception e) {
                    log.warn("Failed to serialize image_urls: {}", e.getMessage());
                }
            }

            JsonNode response = webClientBuilder.baseUrl(videoBaseUrl).build()
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
                    log.error("NewAPI /v1/videos (multi) failed: {} body={}", e.getStatusCode(), body);
                    String friendly = translateNewApiError(body);
                    ErrorCode code = (e.getStatusCode().is4xxClientError())
                        ? ErrorCode.NEWAPI_REQUEST_INVALID
                        : ErrorCode.NEWAPI_UNREACHABLE;
                    return new BusinessException(code,
                        "NewAPI /v1/videos " + e.getStatusCode() + ": " + friendly);
                })
                .block();

            if (response == null) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                    "NewAPI /v1/videos (multi) 返回空响应");
            }

            String taskId = response.path("task_id").asText(response.path("id").asText(""));
            if (taskId.isEmpty()) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                    "NewAPI /v1/videos (multi) 响应缺 id/task_id: " + response);
            }
            log.info("NewAPI /v1/videos (multi) task submitted: {} (primary={}B, additionalUrls={})",
                taskId,
                imageBytes != null ? imageBytes.length : 0,
                additionalImageUrls);
            return new SubmitResult(taskId, null);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "NewAPI /v1/videos (multi) 调用失败: " + e.getMessage());
        }
    }

    /**
     * 兼容旧签名（画布模块仍在使用）：把基础参数组装成 VideoOptions 后委托到新方法。
     * 2026-08-11 保留以避免破坏画布等模块的调用方。
     */
    public SubmitResult submitVideoFull(String prompt, byte[] imageBytes, String imageFilename,
                              String imageMime, int duration, String resolution, String ratio) {
        VideoOptions opts = VideoOptions.builder()
            .duration(duration)
            .resolution(resolution)
            .ratio(ratio)
            .build();
        List<byte[]> imgs = (imageBytes != null && imageBytes.length > 0)
            ? List.of(imageBytes) : null;
        return submitVideo(prompt, imgs, opts);
    }

    /**
     * 图生视频：调 NewAPI /v1/videos（走 aicoming-video-proxy），使用 {@link VideoOptions} 统一参数。
     *
     * <p>所有视频生成方法（image-to-video / text-to-video / multi-image-to-video）的
     * 公共入口都在这里：组装 multipart body → 调 /v1/videos → 拿 task_id。
     *
     * <p>必填字段（从 jurong-api-nodes/api_client.py 确认，跟能跑通的 Python 版本一致）：
     *   - model = "doubao-seedance-2.0"（唯一实测能处理 image ref 的模型）
     *   - prompt （顶层）
     *   - duration 字符串（如 "4"）
     *   - resolution "480p" / "720p" / "1080p" / "4k"（小写）
     *   - input_reference 第一帧图（multipart file，可多张）
     *
     * <p>2026-08-11 重构：所有 submitVideo* 方法都委托到这里，避免代码重复。
     * 新增 ratio / generate_audio / watermark / return_last_frame / seed 等可选参数。
     *
     * @param prompt        用户输入提示词
     * @param imageFiles    多张上游图片（每张发 3 个字段名：image / input_reference / image_url 兼容 doubao / aicoming-proxy）。
     *                      文生视频传 null，内部用占位图。
     * @param options       视频生成参数（duration / resolution / ratio / audio / watermark / seed / model）
     * @return              NewAPI 返回的 task_id
     */
    public String submitVideo(String prompt, List<byte[]> imageFiles, VideoOptions options) {
        if (options == null) {
            options = VideoOptions.builder().build();
        }
        try {
            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            String useModel = options.getModel() != null && !options.getModel().isBlank()
                ? options.getModel() : "doubao-seedance-2.0";
            // aicoming 只接受小写 resolution（480p/720p/1080p/4k）
            String useResolution = (options.getResolution() != null && !options.getResolution().isBlank())
                ? options.getResolution().toLowerCase() : "480p";

            builder.part("model", useModel);
            builder.part("prompt", prompt);
            builder.part("duration", String.valueOf(duration));
            // 2026-08-13 14:25 修复:严格对齐聚融 v2.1 文档 §7(resolution 必须小写 "480p"/"720p"/"1080p")
            //   之前原样转发,如果上游传 "480P" 会被原样发出,aicoming 上游会拒
            builder.part("resolution", resolution == null ? "480p" : resolution.toLowerCase());
            // ratio / watermark 濠电姷鏁告慨鐑藉极閸涘﹥鍙忛柣鎴ｆ閺嬩線鏌涘☉姗堟敾闁告瑥绻橀弻锝夊箣閿濆棭妫勯梺鍝勵儎缁舵岸寮婚悢鍏尖拻閻庨潧澹婂Σ顔剧磼閻愵剙鍔ゆい顓犲厴瀵鏁愭径濠勭杸濡炪倖甯婇悞锕傚磿閹剧粯鈷戦柟鑲╁仜婵″ジ鏌涙繝鍌涘仴鐎殿喛顕ч埥澶愬閳哄倹娅囬梻浣瑰缁诲倸螞濞戔懞鍥Ψ瑜忕壕钘壝归敐鍛儓鐏忓繘姊洪崨濠庢畷濠电偛锕ら锝囨嫚濞村顫嶅┑鈽嗗灦閺€閬嶅棘閳ь剟姊绘担鍛婂暈婵炶绠撳畷鎴﹀礋椤掍礁寮块梺闈涚箞閸婃牠鍩涢幋鐐电闁煎ジ顤傞崵娆愵殽閻愭惌娈滈柡宀€鍠栭獮鏍ㄦ媴閾忚姣囬梻浣虹《閺備線宕戦幘鎰佹富闁靛牆妫楃粭鎺楁煕閻樺疇澹樻い顓炴喘楠炲洭顢橀悩娈垮晭闂備礁鎲￠悷銉┧囨潏銊︽珷妞ゅ繐鐗婇崑鍌炴煏閸繍妲归柣鎾卞劦閺岋繝宕堕埡浣风捕婵炲瓨绮嶆竟鍡欐閹炬剚鍚嬮柛鈩冪懃閳峰矂姊洪崫鍕効缂佺粯绻傞悾鐑藉醇閺囩倣銊╂煏婢诡垰鍊诲Λ顖炴⒒閸屾瑨鍏岀紒顕呭灦楠炴劗鎷犵憗浣告惈椤粓鍩€椤掍椒绻嗛柣銏㈩焾缁€瀣亜閺嶃劍鐨戦柣銈傚亾闂傚倷绀侀幉锟犲箰閻戣姤鍤勯柟顖滃閹冲瞼绱撻崒姘偓鎼佸磹妞嬪孩濯奸柡灞诲劚绾惧鏌熼崜褏甯涢柣鎾存礋閺岀喐瀵肩€涙ɑ閿梺鍝勵儑閸犳牠寮婚敐澶婄閻庨潧鎲￠崚娑㈡⒑閸濆嫭婀扮紒瀣灴閳ワ箓濡搁埡浣哄姦濡炪倖甯掗崐濠氭儗閸℃褰掓晲閸偅缍堝┑鐐叉噽婵炩偓闁哄瞼鍠撶槐鎺楀閻樺磭浜堕梻浣虹帛閹稿鎮烽敃鍌毼﹂柛鏇ㄥ灠缁秹鏌嶈閸撶喎顕ｉ崨濠勭瘈婵﹩鍘煎▓宀勬⒑缁夊棗瀚峰▓鏇㈡煟閹惧鎳勯柕鍥у瀵噣宕掑☉娆戝涧闂備胶鎳撻崯鍨洪銏犺摕闁绘柨鍚嬮幆鐐淬亜閹扳晛鈧鎮￠埀顒勬⒒娴ｅ摜锛嶇紒顕呭灦楠炴垿宕堕鍌氱ウ闂佸綊鍋婇崢浠嬪磿閻旀悶浜滈柡鍐ㄥ€婚幗鍌涗繆椤愩垹顏╅柍瑙勫灴閹晠宕归锝嗙槑濠电姵顔栭崰姘跺礂濮椻偓婵℃挳宕掗悙鏉戠檮婵犮垹鍘滈弲顏嗙礊娴ｅ摜鏆﹂柕濞炬櫅缁狙囨煙鐎电顎撶紒閬嶄憾濮婄粯鎷呴崨濠傛殘缂備礁顑嗛崹鍧楀箖濞差亜惟闁宠桨鑳堕弻褍鈹戦悩缁樻锭妞ゆ垵妫濋幃陇绠涘☉姘絼闂佹悶鍎滅仦钘夊闂備線鈧偛鑻晶顖涚箾閼碱剙鏋涙鐐茬箻楠炲鏁傞挊澶夌盎闂備胶顭堢换妤呭磻閹版澘鍌ㄦい蹇撶墛閳锋垿鏌涢幘鏉戠祷濞存粍绻勭槐鎺旀嫚閼碱儷銏ゅ础闁秵鐓曟繝闈涘閸斻倗鐥幆褋鍋㈤柡宀嬬到閳诲酣骞囬钘夋珣婵犵數鍋犻婊呯不閹捐绠栭柨鐔哄Т閸楁娊鏌ｉ弮鍌滅瘈缂併劏顕ч—鍐Χ閸℃ê鏆楅梺鍝ュТ闁帮綁骞冨鈧俊鐑藉煛閸屾粌骞愰梺璇插嚱缂嶅棝宕滃▎鎾冲嚑闁瑰濮风壕鑲╃磽娴ｈ鐒芥繛鎻掝嚟閳ь剝顫夊ú鏍Χ閹间礁绠栭柕蹇嬪€曠粻褰掓煟閹邦厼顎滄俊鍓ь焾閳规垿鎮╅幇浣告櫛闂佸摜濮甸悧鐘诲极閸愵喖惟闁靛鍨洪悗娲⒑閹稿海绠撴繛灞傚€濆畷鐟扳攽閸モ晝顔曢梺绯曞墲閿氶柣蹇ュ閳ь剝顫夊ú鏍囬悽绋胯摕闁哄洨鍠撶粻鍓ф喐瀹ュ鍤愭い鏍仜閺嬩線鏌ｉ幘宕囧哺闁衡偓娴犲鐓ユ繛鎴灻鈺伱瑰鍐﹀仮闁哄本绋掔换娑㈠垂椤旂懓浜炬繝闈涙閺嗭箓鏌曡箛瀣偓鏍磻閸屾侗娈介柣鎰版涧閺嬫垶淇婇悙鎵煓闁靛棔绀侀～婊堝焵椤掍焦鍙忛柍褜鍓熼弻鏇＄疀閺囩倫銉╂煏閸剛鐣垫慨濠勭帛閹峰懏绗熼娑欐殲闂備浇顫夊鎸庣閻愰潧鍨濆┑鐘宠壘缁狅綁鏌ｅΟ鍏兼毄闁绘帒銈搁弻锝嗘償椤栨粎校闂佺顑勯悞锔剧矉瀹ュ拋鐓ラ柛顐ゅ枔閸樻悂鎮楅獮鍨姎闁哥噥鍋呮穱濠冪鐎ｎ偆鍘介梺闈涱煭缁犳垿鎮橀敃鍌涚厪闁搞儜鍐句純濡ょ姷鍋為…鍥焵椤掍胶鈯曢懣褍霉閻橆喖鐏╅柍瑙勫灴椤㈡瑧娑甸柨瀣毎婵犵绱曢崑妯煎垝濞嗘挻鍋樻い鏇楀亾妤犵偛娲、姗€鎮㈠畡鏉课ら梻鍌欑閸熷潡鎮橀崼銉ョ柧婵犲﹤鎳夐崑鎾愁潩椤愩倗鐓撳┑顔硷功缁垶骞忛崨顔剧懝妞ゆ牗绋掗弳鐐寸節閻㈤潧浠滈柟鍐茬箰鐓ら柣鏃囧亹瀹撲線鏌熼幍顔碱暭闁搞倖甯￠弻鏇㈠醇濠靛洤绐涢梺缁樺笒濞硷繝骞冨Δ鍛祦闁割煈鍠栨慨搴☆渻閵堝繒绱伴柛妤€鍟块悾鐑藉箛閻楀牏鍙嗛柣搴祷閸斿鑺辨繝姘拺闁荤喓澧楅幆鍫㈢磼婢跺﹦鍩ｉ挊婵嬫煥閺冨牊鏆滈柛瀣尭閳绘捇宕归鐣屼邯闂備浇顕х换鎴犳崲閸儱鏄ラ柣鎰惈缁狅綁鏌ㄩ弴妤€浜鹃梺缁樻惈缁绘繈寮诲☉銏犵労闁告劗鍋撻悾鍏肩箾鐎电袥闁哄懏鐩崺鐐哄箣閿旇棄鈧兘鏌ｉ幇顒€甯ㄩ柛瀣尵閳ь剨缍嗛崜姘暦閸欏绡€闂傚牊绋掗ˉ鐘绘煛閸☆參妾柕鍥у楠炲洭濡搁敃鈧妯衡攽閻愬弶鈻曞ù婊冪埣瀵偊宕掗悙瀵稿幈濠电偞鍨靛畷顒勬倶閻樻剚娈?Python api_client.py

            if (imageBytes != null && imageBytes.length > 0) {
                // 闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌熼梻瀵割槮缁炬儳缍婇弻鐔兼⒒鐎靛壊妲紒鐐劤缂嶅﹪寮婚敐澶婄闁挎繂鎲涢幘缁樼厱濠电姴鍊归崑銉╂煛鐏炶濮傜€殿喗鎸抽幃娆徝圭€ｎ亙澹曢梺鍛婄缚閸庤櫕绋夊澶嬬厸鐎广儱楠搁獮妤呮煟閹惧瓨绀冮柕鍥у楠炲洭宕滄担鑽锋垹绱撴担鎻掍壕闂侀€炲苯澧扮紒杈ㄥ浮閹瑩顢楅埀顒勫礉閵堝棛绠鹃悘蹇旂墤閸嬫捇骞囨担鍛婎吙闂備礁澹婇崑鍛洪弽顓熺厑闁搞儯鍔庣粻楣冩煙鐎甸晲绱虫い蹇撶墐閳ь剚鐗楀鍕箾閻愵剚鏉搁梻浣虹帛閸旀洖顕ｉ崼鏇為棷闁芥ê顦弨鑺ャ亜閺冨洤袚閻忓骏闄勭换婵嬪焵椤掍胶鐟归柍褜鍓欓～蹇涙惞閸︻厾锛滃┑鈽嗗灠濞存碍绂嶅鍡欎航濠电姷鏁告慨鏉懨洪妶鍥ь棜闁秆勵殘閸欐捇鏌涢妷锝呭闁愁垱娲熼弻锝夊箻鐎靛憡鍒涢梺璇″枟椤ㄥ﹪寮幇鏉跨＜婵炴垶鐟цぐ鍥╃磽閸屾瑧鍔嶉柛鏃€鐗曡灋闁告劦鍠栭拑鐔兼煃閵夈儳锛嶉柡鍡楁閺屽秷顧侀柛鎾跺枎閻ｉ攱瀵奸弶鎴濆敤濡炪倖鎸炬慨瀵哥矈閿曞倹鈷戠痪顓炴噺瑜把呯磼閻樺啿鐏╃紒顔款嚙閳藉鈻庡鍕泿闂備礁婀遍崕銈夊垂閻㈢鐒垫い鎺嗗亾闁硅姤绮撳顐︻敋閳ь剙鐣风粙璇炬梹鎷呴崣澶婎伜婵犵數鍋犻幓顏嗗緤娴犲绠熼柨鐔哄Т缁犵喓绱掔€ｎ亞姘ㄩ柡鈧懞銉ｄ簻闁哄啫娲よ缂傚倸绉甸崹鍧楀箺閸洘鍊烽悗闈涙憸閻﹀牓姊婚崒姘卞濞撴碍顨婂畷鏇＄疀濞戞瑧鍙冮梺鍛婂姦娴滄粓寮搁幋鐘电＜缂備焦顭囧ú瀛橆殽閻愬樊鍎忛柍璇叉唉缁犳盯寮村顓炰簼闂傚倸鍊烽懗鍓佸垝椤栨粍鏆滄俊銈傚亾妞ゎ亜鍟粋鎺斺偓锝庝海閹芥洖鈹戦悙鏉戠仧闁搞劌婀辩划璇测槈閵忊€斥偓鍫曟煟閹邦垱纭剧悮姘舵⒑闂堚晝鎮奸柡鍜佸亞濡叉劙骞掑Δ浣镐汗闂佸憡鍔曞鍓佹嫚閻愭祴鏀芥い鏃傘€嬮崝鐔虹磼椤曞懎鐏︽鐐茬箻瀹曘劑寮堕幋婵堢崺濠电姷鏁告慨鎾疮椤愶箑绀堥柛顭戝亞缁♀偓缂佸墽澧楄摫妞ゎ偄锕弻娑氣偓锝庝簻椤忣參鏌＄仦鏂よ含闁轰焦鍔欏畷銊╊敍濞戞瑯鍟庨梻鍌欑閹碱偄煤閵忋倕鍨傛繛宸簻绾惧鏌曟繛褍鎳愰敍婊堟煟鎼搭垳绉甸柛妯恒偢瀹曟繈鎮介崨濠勫幍闂佸吋浜介崕鑼矆鐎ｎ偅鍙忓┑鐘插暞閵囨繃淇婇銏犳殭闁宠棄顦板蹇涘煛娴ｆ劅顏堟⒒閸屾瑨鍏屾い顓炵墢閳ь剙鐏氱敮鈥崇暦娴兼潙鍐€鐟滃秶绮婇锔解拻濞达絿顭堥ˉ蹇涙煕鐎ｎ亝顥㈢€规洑鍗抽獮姗€宕滄担椋庣憹濠德板€х徊浠嬪疮椤栫偞鍋傞柡鍥ュ灪閻撳啴鏌嶆潪鎵槮妤犵偞蓱閵囧嫯绠涢幘璺侯杸闂佺锕ら悥濂稿蓟瀹ュ浼犻柛鏇ㄥ墮濞呫倝姊虹紒妯诲鞍婵炲弶顭囬幑銏犫槈閵忕姴鑰垮┑鈽嗗灠閹碱偊锝炴惔锝囩＝濞达絽鎼牎濡炪値鍘煎ú銊ノｉ幇鏉跨闁规儳顕粔鍫曟⒑闂堟侗鐓紒鐘冲灴濡嫬顓兼径瀣ф嫼闂佽崵鍠愬妯何ｆ繝姘厵闁惧浚鍋撻懓鎸庮殽閻愭彃鏆ｆ鐐叉椤︽挳鏌￠崱妤侇棦闁哄苯绉烽¨渚€鏌涢幘瀵搞€掓俊鍙夊姇閳诲酣骞樼€电濮搁柣搴＄畭閸庡崬螞瀹€鍕闁惧繐婀辩壕钘夈€掑顒佹悙闁诲繆鍓濈换娑㈠矗婢规繍浜崺銏狀吋婢跺﹤鑰垮┑鐐村灦閻熝囧储閽樺鏀介幒鎶藉磹閹剧粯鍤勯柛顐ｆ礃閸庢鈧厜鍋撻柛鏇ㄥ墰閸橀亶姊洪崷顓炲妺闁圭鎽滅划顓㈠箳濡や胶鍘撻柣鐘叉处閻擄繝宕ｉ崟顒夋闁绘劕寮堕崰妯汇亜閵忊槅娈滅€规洘甯掗…銊╁川椤撶姰鍋婇梻鍌氬€搁崐宄懊归崶顒夋晪鐟滄柨鐣峰▎鎾村仼鐎光偓閳ь剛绮堟繝鍥ㄧ厱闁斥晛鍟伴埥澶岀磼閳ь剟宕奸悢铏诡啎闂佺懓鐡ㄩ悷銉╂倶椤忓牊鐓曢幖绮规闊剟鏌＄仦鍓ф创妞ゃ垺娲熼幃鈺呭箵閹烘埈娼ラ梻鍌欑劍婵炲﹪寮ㄩ柆宥呭瀭闁秆勵殔閽冪喐绻涢幋鐐冩艾危閸喐鍙忔俊銈傚亾闁绘妫欑€靛ジ骞囬鐘电槇濠电偛鐗嗛悘婵嗏枍濞嗘垹纾奸柣妯哄暱閻忓瓨绻濋埀顒佹媴缁洘鏂€闂佺粯顭堥婊冾啅閵夆晜鍊垫慨妯煎帶濞呭秹鏌熼姘辩劯妤犵偞甯掕灃濞达絽鎼獮宥嗕繆閻愵亜鈧牕煤閺嶎灛娑樷槈濮橆剙袣闂侀€炲苯澧摶鏍煟濮椻偓濞佳勭濠婂嫨浜滈柟瀛樼箥濡偓閻庢鍣崑濠傜暦閹烘埈鐓ラ柛鏇ㄥ亝閻庮參姊绘担鐟邦嚋缂佽鍊歌灋婵°倕鎷嬮弫鍌滄喐閻楀牆绗氶柍閿嬪浮閺屾稓浠﹂崜褎鍣梺绋跨箰閺堫剟濡甸崟顖氼潊闁绘瑥鎳撻崥顐︽倵鐟欏嫭绀冮柛銊ユ健閻涱噣宕堕鈧痪褔鎮规笟顖滃帨缂佽精椴哥换婵嬫偨闂堟稐娌梺鍓茬厛閸ㄨ泛鐣疯ぐ鎺戞嵍妞ゆ挾濮烽悞鍏肩節閵忥絾纭炬い鎴濇瀹曪綀绠涢弮鈧崣蹇斾繆閵堝倸浜惧┑鈽嗗亝椤ㄥ棝寮查懜鐢电瘈婵﹩鍘鹃崢浠嬫⒑閸濆嫬鈧湱鈧瑳鍥佸鎮╃紒妯煎幍闂佸憡鐟ラˇ浼村磹閹邦収娈介柣鎰▕閸庢棃鏌熼鐣屾噰鐎殿喖鐖奸獮瀣攽閸涱垳顦伴梻鍌氬€搁崐椋庢濮橆剦鐒界憸鏃堝箖瑜斿畷鍗灻归弶鎸庡枠妞ゃ垺鐩幃娆撳级閹存粎妫?
                final String fname = imageFilename != null ? imageFilename : "canvas_input.png";
                final String mime = imageMime != null ? imageMime : "image/png";
                builder.part("input_reference",
                    new ByteArrayResource(imageBytes) {
                        @Override
                        public String getFilename() { return fname; }
                    },
                    MediaType.parseMediaType(mime));
            } else {
                // 闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌熼梻瀵割槮缁炬儳缍婇弻鐔兼⒒鐎靛壊妲紒鎯у⒔閹虫捇鈥旈崘顏佸亾閿濆簼绨奸柟鐧哥秮閺岋綁顢橀悙鎼闂侀潧妫欑敮鎺楋綖濠靛鏅查柛娑卞墮椤ユ艾鈹戞幊閸婃鎱ㄩ悜钘夌；闁绘劗鍎ら崑瀣煟濡崵婀介柍褜鍏涚欢姘嚕閹绢喖顫呴柍鈺佸暞閻濇牠姊绘笟鈧埀顒傚仜閼活垱鏅堕弶娆剧唵閻熸瑥瀚粈瀣偓瑙勬礈閸忔﹢銆佸鈧幃鈺冨枈婢跺苯绨ラ梻鍌欐祰椤曆囧礄閻ｅ瞼绀婇柛鈩冪☉绾惧鏌熼幑鎰厫妞ゎ偅娲熼弻宥夊传閸曨偀鍋撻懡銈囦笉闁告挆鈧崑鎾绘偡閺夋妫岄梺鍝ュУ濞叉粓鎳炴潏銊х瘈婵﹩鍓涢悾楣冩⒑缂佹ɑ鐓ラ柛姘儔閸╂盯骞嬮敂钘夆偓鐢告煕閿旇骞栭弽锟犳⒑闂堟稒顥滈柛鐔告尦瀵鏁愭径濠勵唺闂佺粯鍔楅弫鎼佸汲閵堝鈷戦悹鍥ｂ偓铏亶濡炪們鍔岄敃顏堝Υ娴ｈ倽鏃堝川椤撶媭妲规俊鐐€栭崹鍏兼叏閵堝洠鍋撳顑惧仮婵﹥妞介幊锟犲Χ閸涱喚鈧箖姊洪懡銈呮瀭闁稿孩濞婇崺鈧い鎺嶇閸ゎ剟鏌涢幘瀵搞€掗柛鎺撳浮瀹曞ジ濡烽妷褜妲版俊鐐€栧濠氬疾椤愶箑鍌ㄩ梺顒€绉甸埛鎴︽煕閹邦剙绾ч柟顖氱墦閺屾稒绻濋崟顓炵闂佸搫鎳庨悥濂稿箖閻ｅ苯鏋堟俊顖濇〃婢规洟鏌ｉ悢鍝ユ噧閻庢凹鍘炬竟鏇熺節濮橆厾鍘卞┑掳鍊愰崑鎾绘煕閻旈攱鍋ラ柟顕€绠栭幃婊堟寠婢跺矈鏀ㄩ梻浣虹帛閸斿繘寮插鍫稏鐎广儱鎳夐弨浠嬫煟閹邦剙绾фい銉у仱閺屾盯濡歌閺嗩剟鏌ｅ☉鍗炴珝鐎规洖銈告俊鐑芥晜鐟欏嫬顏烘繝鐢靛仩閹活亞绱為埀顒併亜椤愩埄妯€闁诡喗锕㈤弻鍡楊吋閸℃瑥骞愰梻浣告啞娓氭宕板顑炶櫣鈧數纭堕崑鎾舵喆閸曨剙顦╅梺鎼炲妼閻栫厧鐣峰ú顏呮櫢闁绘灏欓ˇ銊╂⒑閸愬弶鎯堥柨鏇樺€栫粋鎺懨洪鍛嫽闂佺鏈悷褏鎷规导瀛樼厱闁规儳顕幊鍛磼椤旇姤顥堥柟顔荤矙瀹曘劍绻濋崒娆戞殫濠电姷鏁搁崑鐐哄垂椤栫偛鍨傛繛宸簼閸嬪倿鏌￠崶銉ョ仾闁绘挸鍟撮弻宥嗘姜閹殿噮妲梺鍝勬閻熴儵鍩為幋锔绘晩闁稿繒鍘ч弸鐘绘⒑閸濆嫭婀伴柣鈺婂灠椤曪綁顢氶埀顒勭嵁濮椻偓瀹曟粍鎷呯憴鍕靛晫闂傚倸鍊风粈渚€骞栭锔藉剹濠㈣泛鏈～鏇㈡煛閸モ晛鏋旀い鈺冨厴閺屻劑寮崒姘闁诲孩纰嶅畝鎼佸蓟閻旇櫣纾兼俊顖濇〃閸掑﹪姊虹拠鎻掝劉濠电偛锕ら～蹇曠磼濡顎撻梺鍛婄缚閸庢煡鎮楅灏栨斀闁宠棄妫楁禍婊堟倵濮橆厼顎滄俊顐ゅ枎椤啴濡堕崱娆忊拡闂佺顑嗛惄顖炲箖閳ユ枼鏀介悗锝庝簽椤旀劕鈹戦悜鍥╃У闁告挻鐟︽穱濠囨嚃閳哄啰锛滈梺缁樼懃閹虫劗绮旈鈧弻鈥崇暆鐎ｎ剛袦濡ょ姷鍋涘ú顓€佸鈧幃銏ゆ惞閸忓鐎兼繝鐢靛Х閺佹悂宕戝☉銏犵疇閹艰揪绲鹃弳婊呯磼閺傝法鐛杕ing 濠电姷鏁告慨鐑藉极閸涘﹥鍙忛柣鎴ｆ閺嬩線鏌涘☉姗堟敾闁告瑥绻橀弻锝夊箣閿濆棭妫勯梺鍝勵儎缁舵岸寮婚悢鍏尖拻閻庨潧澹婂Σ顔剧磼閻愵剙鍔ゆい顓犲厴瀵鏁愭径濠勭杸濡炪倖甯婇悞锕傚磿閹剧粯鈷戦柟鑲╁仜婵″ジ鏌涙繝鍌涘仴妤犵偛鍟伴幉鎾礋椤掆偓椤繝姊洪悷鏉挎Щ闁活厼鐗撳畷婵嬪川椤撴稒鏂€闂佺粯鍔栬ぐ鍐箖閹达附鐓熸俊銈勭劍缁€瀣煕閳哄啫浠辨鐐差儔閺佸倿鎸婃径澶嬬潖闂備浇顕ф绋匡耿鏉堛劍鍙忛柟缁㈠暉婢跺ň鏀介柛顐犲灮閿涙繃绻涙潏鍓ф偧闁硅櫕鎸婚幈銊╁醇閻旂繝绨婚梺闈涱焾閸庡鐓鍌楀亾濞堝灝鏋涙い顓犲厴瀵偊骞樼紒妯轰汗闂佽偐鈷堥崜娆撳焵椤掑倸浠辨慨濠冩そ瀹曟宕楅悡搴樺亾閹邦厾绠惧ù锝呭暱濞层倝鎮″┑瀣彄闁搞儯鍔庨埊鏇㈡煃闁垮鐏存慨濠冩そ椤㈡洟濡堕崨顒傛崟闂備礁鍚嬪鍧楀垂闁秴鐤鹃柛顐ｆ处閺佸鏌嶈閸撶喎顕ｆ繝姘亜闁稿繐鍚嬮崕顏勵渻閵堝棗濮﹂柛鎾寸箞閸┾偓妞ゆ帒鍟涵鍫曟煃瑜滈崜姘额敊閺嶎厼闂い鏇楀亾鐎规洘鍨剁换婵嬪磼濠婂嫭顔曟繝娈垮枟閵囨盯宕戦幘娣簻闁靛骏绱曢幊鍥煙閾忣偆澧甸柛鈹惧墲閹峰懘骞囬悢鍛婄闁宠鍨块弫宥夊礋椤愨剝婢€闂備胶顭堥敃銉╂偋濠婂牆鏋佹い鏃傛櫕缁♀偓闂佹悶鍎崝宀勫焵椤掆偓濞硷繝寮婚妸鈺佸嵆婵°倐鍋撳ù婊堢畺濮婅櫣鈧湱濯崵娆戠磼婢舵劦妫戠紒顔款嚙閳藉螣闁垮娼旀繝鐢靛仜濡寮甸鍌楀亾濮樿櫕顥夐柍瑙勫灴閹瑩鎳滈棃娑欓敪缂傚倷娴囩亸顏堝磻閹版澘绠柛鎰靛枟閺呮繈鏌涚仦鍓р槈闁逞屽墮閻忔繆鐏冮梺鎸庣箓閹冲酣寮抽悙鐑樼厽闁规儳顕ú鎾煛鐏炲墽娲存い銏℃礋椤㈡洟锝為鐘垫晨婵犵數濮幏鍐礋椤掆偓閸炲姊洪崫鍕伇闁哥姵鐗犻妴浣糕枎閹炬潙鈧攱銇勯幒鎴濃偓鍛婃叏閼恒儰绻嗛柣鎰典簻閳ь儸鍛笉闁圭儤顨愮紞鏍ь熆閼搁潧濮囧鍛存⒑閸涘﹥澶勯柛銊ゅ嵆閿濈偤宕ㄧ€涙鍘撻梺瀹犳〃缁€渚€寮搁妶鍡曠箚妞ゆ劑鍨归弳锝夋煙椤旂瓔娈橀柟鍙夋尦瀹曠喖顢楅崒妤佹櫖缂傚倸鍊烽懗鍓佸垝椤栨粍宕查柛顐犲劤瀹撲線鏌涢幇鈺佸Ψ闁哄閰ｉ弻鐔煎箚瑜忛敍宥夋煕濡粯鍊愭慨濠呮缁瑩鎳楅锝嗚晧闂備胶顭堥敃銉╂偋濠婂懏顫?file 闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌熼梻瀵割槮缁炬儳婀遍埀顒傛嚀鐎氼參宕崇壕瀣ㄤ汗闁圭儤鍨归崐鐐差渻閵堝棗绗掓い锔垮嵆瀵煡顢旈崼鐔蜂画濠电姴锕ら崯鎵不婵犳碍鐓曢柍瑙勫劤娴滅偓淇婇悙顏勨偓鏍暜婵犲洦鍤勯柛顐ｆ礀閻撴繈鏌熼崜褏甯涢柣鎾寸洴閺屾稑鈽夐崡鐐寸亾缂備胶濮甸敃銏ゅ蓟濞戙垹绠抽柟鎯х－閻熴劑姊虹€圭媭鍤欓梺甯秮閻涱喖螣閾忚娈鹃梺鎼炲劥濞夋盯寮挊澶嗘斀闁绘ɑ顔栭弳婊呯磼鏉堛劍绀嬬€规洘鍨甸埥澶愬閳ュ啿澹勯梻浣虹帛閸ㄧ厧螞閸曨厼顥氬┑鐘崇閻撴瑩鏌熺憴鍕Е闁搞倖鐟х槐鎺楀焵椤掑嫬绀冮柍鐟般仒缁ㄥ姊洪崫鍕殭闁稿﹤鎽滈弫顕€宕奸弴鐔哄幘闂佸搫顦冲▔鏇熺閵忋倖鐓冮悷娆忓閻忔挳鏌熼鐣屾噰鐎殿喖鐖奸獮瀣偐鏉堚晝顦ㄥ┑鐘殿暜缁辨洟宕戝☉銏″仱闁靛ň鏅涚粻鏍煕鐏炴儳鍤柛銈嗘礋閺岋紕浠︾拠鎻掑闂佺粯鎸婚惄顖炲蓟濞戞ǚ妲堥柛妤冨仦閻忓牓姊洪柅鐐茶嫰婢т即鏌℃担鍓茬吋鐎殿喛顕ч埥澶婎煥閸涱垱婢戦梻浣烘嚀閻忔繈宕婊呮噮濠电姷鏁搁崑娑㈡偤閵娧冨灊闁规儳澧庢稉宥夋煛瀹擃喖鏈紞搴♀攽閻愬弶鈻曞ù婊勭矊椤斿繐鈹戦崱蹇旀杸闂佺粯蓱瑜板啴寮冲▎鎰╀簻闁挎棁顫夊▍濠冩叏婵犲啯銇濈€规洏鍔嶇换婵嬪礃閵娾晝鈧椽姊绘担鍝勫付缂傚秴锕︾划濠氬冀椤撶偞妲梺闈涚箳婵參宕ョ€ｎ亶鐔嗛悹铏瑰皑瀹搞儵鏌涢悙顏勫妞ゎ亜鍟存俊鍫曞幢濡儤娈梻浣告憸婵敻骞戦崶褏鏆﹂柕蹇ョ磿椤╃兘鎮楅敐搴′航婵☆偄鎳庨—鍐Χ閸℃顫囬梺鎼炲妿閸庛倕顕ラ崟顖氱妞ゆ帒鍊婚鏇㈡⒑閸涘﹦鎳冩い锕侀哺閺呭爼顢楅崒婊咃紲闂佺鏈粙鎴澝归鈧弻娑㈠煛鐎ｎ剛鐦堥悗瑙勬磸閸旀垿銆佸▎鎾崇畾鐟滃秶绮婚悙鐑樷拻濞达絿鐡旈崵娆撴煕閹寸姵娅曠紒杈╁仱瀹曞崬鈽夊Ο纭风幢闂備礁鎲″ú锕傚礈濮樿泛鐭楅煫鍥ㄦ磵閸嬫捇鐛崹顔煎濡炪倧缂氶崡鎶藉箖?16x16 闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌熼梻瀵割槮缁炬儳缍婇弻鐔兼⒒鐎靛壊妲紒鐐劤缂嶅﹪寮婚悢鍏尖拻閻庨潧澹婂Σ顔剧磼閹冣挃闁硅櫕鎹囬垾鏃堝礃椤忎礁浜鹃柨婵嗙凹缁ㄧ粯銇勯幒瀣仾闁靛洤瀚伴獮鍥敂閸℃瑧鍘梻浣告惈鐞氼偊宕濋幋锕€绠栭柕蹇嬪€曟导鐘绘煕閺囩喎鐏熼柛銊ヮ煼閹偓妞ゅ繐鐗嗙粻姘辨喐濠婂牊鍋傚┑鍌氭啞閻撴盯鎮橀悙鎻掆挃婵炴彃顕埀顒侇問閸犳骞愰搹顐＄箚闁归棿绀佸敮闂侀潧锛忕仦鑺ユ珡闂傚倷娴囬褍顫濋敃鍌︾稏濠㈣泛鈯曢崫鍕垫建闁逞屽墴楠炲啴鏁撻悩铏闂佺粯顭堢亸顏堝箺閺囥垺鈷戠紓浣股戦ˉ鍡涙煏閸″繐浜鹃梻浣侯焾椤戝棝骞愰幖浣哥叀濠㈣泛艌閺嬪秹鏌ц箛锝呬簻闁诲繐顕埀?PNG 闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌熼梻瀵割槮缁炬儳缍婇弻鐔兼⒒鐎靛壊妲紒鎯у⒔閹虫捇鈥旈崘顏佸亾閿濆簼绨奸柟鐧哥秮閺岋綁顢橀悙鎼闂侀潧妫欑敮鎺楋綖濠靛鏅查柛娑卞墮椤ユ艾鈹戞幊閸婃鎱ㄩ悜钘夌；婵炴垟鎳為崶顒佸仺缂佸瀵ч悗顒勬⒑閻熸澘鈷旂紒顕呭灦瀹曟垿骞囬婊€绨婚梺鍝勫暙閸婂綊宕甸埀顒佺箾鐎涙鐭掔紒鐘崇墵瀵鈽夐姀鐘电杸闂傚倸鐗婄粙鎺楁倶閸儲鈷掑ù锝囶焾閼歌绻涘顔煎籍鐎殿喖顭烽弫鎰緞婵犲嫮娼夐梻浣侯焾鐞氼偊宕愬Δ鍛闁归棿鐒﹂崐鍨箾閸繄浠㈤柡瀣⊕缁绘稓娑垫搴ｇ槇濡ょ姷鍋涢崯顐ョ亙闂佸憡鍔忛弲娑㈠几閸岀偞鈷戦柛娑橈攻婢跺嫰鏌涢幘瀵告创閽樻繈鏌曟径鍫濆姉闁衡偓娴犲鐓熼柟閭﹀灠閻ㄧ儤绻濋埀顒勫箻椤旂晫鍘遍梺鍝勫€圭€笛囧箲閿濆鐓?
                final String placeholderName = "_placeholder.png";
                // 2026-08-13 FIX: 占位图也同时发 3 个字段名(模仿 Python api_client.submit_video)
                ByteArrayResource ph1 = new ByteArrayResource(DUMMY_PNG_BYTES) {
                    @Override public String getFilename() { return placeholderName; }
                };
                ByteArrayResource ph2 = new ByteArrayResource(DUMMY_PNG_BYTES) {
                    @Override public String getFilename() { return placeholderName; }
                };
                ByteArrayResource ph3 = new ByteArrayResource(DUMMY_PNG_BYTES) {
                    @Override public String getFilename() { return placeholderName; }
                };
                builder.part("image", ph1, MediaType.IMAGE_PNG);
                builder.part("input_reference", ph2, MediaType.IMAGE_PNG);
                builder.part("image_url", ph3, MediaType.IMAGE_PNG);
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
                    // 2026-08-11 修复:4xx 是业务错误(图片敏感/参数错),不应该重试。
                    // 用 translateNewApiError 把上游错误翻译成中文友好提示。
                    // 5xx 才视为 NEWAPI_UNREACHABLE 重试。
                    String friendly = translateNewApiError(body);
                    ErrorCode code = (e.getStatusCode().is4xxClientError())
                        ? ErrorCode.NEWAPI_REQUEST_INVALID
                        : ErrorCode.NEWAPI_UNREACHABLE;
                    return new BusinessException(code,
                        "NewAPI /v1/videos " + e.getStatusCode() + ": " + friendly);
                })
                .block();

            if (response == null) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "NewAPI 视频提交返回空");
            }
            // 2026-08-10 修复:优先用 task_id(NewAPI 真实任务 ID)而不是 id(任务包装 ID)。
            // 之前用 id,但 NewAPI /v1/videos/{id} 接口可能只认 task_id,导致后续 poll 一直 404,
            // 然后被 markFailed 误判任务不存在。
            // NewAPI 兼容 OpenAI Sora API,这两个字段语义不同:id 是请求包装 ID,task_id 才是 NewAPI 内部真实任务 ID。
            String taskId = response.path("task_id").asText(response.path("id").asText(""));
            if (taskId.isEmpty()) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                    "NewAPI /v1/videos 响应格式错误, 缺少 id/task_id:" + response);
            }
            log.info("NewAPI video task submitted: {} (image={}, size={}B, duration={}s, resolution={})",
                taskId,
                imageBytes != null ? imageFilename : "placeholder",
                imageBytes != null ? imageBytes.length : 0,
                duration, resolution);
            // 2026-08-11 修复:submit 响应里常常已含 video url(同步返回 + task_id 同一响应)。
            // 之前不提取就丢了,只能等 poll 30 秒后取到 → 如果 poll 出现 400 索引延迟就会被误判 FAILED。
            // 现在提取后,即使后续 poll 失败,job 也已保存 url,前端能直接播放。
            String directUrl = null;
            JsonNode topUrlNode = response.get("url");
            if (topUrlNode != null && topUrlNode.isTextual() && !topUrlNode.asText().isBlank()) {
                directUrl = topUrlNode.asText();
            } else if (response.path("metadata").path("url").isTextual()) {
                directUrl = response.path("metadata").path("url").asText();
            }
            if (directUrl != null) {
                log.info("NewAPI submit 响应已含 video url: {}", directUrl);
            }
            return new SubmitResult(taskId, directUrl);
        } catch (Exception e) {
            if (e instanceof BusinessException) throw e;
            log.error("NewAPI submitVideo failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
        }
    }

    /**
     * 文生视频：调 NewAPI /v1/videos（不传图，内部用占位图）。
     *
     * <p>调用链：Java → NewAPI (jurong) → aicoming-video-proxy
     *
     * @param prompt  用户提示词
     * @param options 视频参数（duration / resolution / ratio / audio / seed）
     * @return NewAPI 返回的 task_id
     */
    public String submitTextToVideo(String prompt, VideoOptions options) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "prompt 不能为空");
        }
        log.info("[VIDEO-T2V] 文生视频: promptLen={}, options={}", prompt.length(), options);
        return submitVideo(prompt, null, options);
    }

    /**
     * 多图生视频：传多张参考图（1-4 张），调 NewAPI /v1/videos。
     *
     * <p>调用链：Java → NewAPI (jurong) → aicoming-video-proxy
     *
     * @param prompt    用户提示词
     * @param imageBytesList 多张图片字节（按顺序，每张图同名发 3 个字段兼容 doubao / aicoming）
     * @param options   视频参数
     * @return NewAPI 返回的 task_id
     */
    public String submitMultiImageToVideo(String prompt, List<byte[]> imageBytesList, VideoOptions options) {
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "prompt 不能为空");
        }
        if (imageBytesList == null || imageBytesList.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "至少需要 1 张参考图");
        }
        // 过滤 null/空
        List<byte[]> valid = new ArrayList<>();
        for (byte[] b : imageBytesList) {
            if (b != null && b.length > 0) valid.add(b);
        }
        if (valid.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "参考图均为空");
        }
        log.info("[VIDEO-MI2V] 多图生视频: promptLen={}, images={}, options={}",
            prompt.length(), valid.size(), options);
        return submitVideo(prompt, valid, options);
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
                        parseErrorMessage(respBody, e.getStatusCode().value()));
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

        // 2026-08-13 DEBUG:逐个路径打印命中情况,排查 url 在哪个字段
        // 路径 1: data.output
        JsonNode dataOutput = pollResult.path("data").path("output");
        if (dataOutput.isTextual() && !dataOutput.asText().isBlank()) {
            String url = sanitizeUrl(dataOutput.asText());
            if (url != null) {
                log.info("[extractVideoUrl] ✓ 命中路径 [data.output]: {}", url);
                return url;
            }
        }

        // 路径 2:顶层 metadata.url
        JsonNode metadata = pollResult.get("metadata");
        if (metadata != null && metadata.isObject()) {
            JsonNode url = metadata.get("url");
            if (url != null && url.isTextual()) {
                String cleanUrl = sanitizeUrl(url.asText());
                log.info("[extractVideoUrl] ✓ 命中路径 [metadata.url]: {}", cleanUrl);
                return cleanUrl;
            }
        }

        // 闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧湱鈧懓瀚崳纾嬨亹閹烘垹鍊為悷婊冪箻瀵娊鏁冮崒娑氬幗闂侀潧绻堥崺鍕倿閸撗呯＜闁归偊鍙庡▓婊堟煛瀹€鈧崰鏍蓟閸ヮ剚鏅濋柍褜鍓熷绋库槈閵忥紕鍘遍梺闈涱煭婵″洨绮婚悙鐑樼厽闁瑰灝鍟禍鎵偓瑙勬礀閻栧吋淇婂宀婃Х濠碘剝褰冮悧鎾愁潖濞差亜浼犻柛鏇ㄥ亽娴犳挳姊虹粙鎸庢崳闁轰浇顕ч锝囨嫚濞村顫嶉梺闈涚箳婵牓濡搁敂杞扮盎闂佸搫鍟崐鍝ユ暜閼哥偣浜滄い鎰枑濞呭﹥鎱ㄦ繝鍐┿仢妤犵偞鐗犻幃娆撳箵閹烘繄鈧娊姊绘担渚劸闁挎洏鍊曢敃銏ゆ焼瀹ュ懐顔夐梺闈涚箳婵厼危閸喐鍙忔俊銈傚亾婵☆偅顨婂鏌ユ偐瀹割喗瀵岄梺闈涚墕濡瑩藟閸℃瑢鍋撶憴鍕闁轰礁顭烽獮鍡涘礋椤掍礁鍔呴梺闈涱煭閼靛綊骞?2闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌熼梻瀵割槮缁炬儳缍婇弻鐔兼⒒鐎靛壊妲紒鐐劤缂嶅﹪寮婚敐澶婄闁挎繂鎲涢幘缁樼厱濠电姴鍊归崑銉╂煛鐏炶濮傜€殿噮鍣ｅ畷濂告偄閸涘鍞堕梻鍌欒兌椤牓顢栭崱娑樼闁告挆鍐ㄧ亰濡炪倖鎸鹃崑鎰ｉ崼鐔剁箚妞ゆ牗绻嶉崵娆愮箾閸涘洤娲﹂埛鎴炵箾閼奸鍤欐鐐搭殜閺岋綁鎮㈤崣澶嬬彋閻庢鍠栭…鐑藉箖閵忋倕绀傞柣鎾崇岸閸嬫挾绱掑Ο璇插伎濠德板€愰崑鎾翠繆椤愶絿鎳囨い銏☆焾閵囨劙骞掗幘顖涘闂佸搫顦遍崑鐐寸珶閸℃蛋鍥晜閹存帞绠氬銈嗗姧缁茶法绮婚悙纰樺亾濞堝灝鏋涙い顓犲厴瀵偊骞囬鐐电獮闁诲函缍嗘禍鏍磻閹剧粯鍊婚柤鎭掑劗閹峰姊洪崜鎻掍簽闁哥姵鎹囨俊鎾箳閹惧彉绨婚梺鍝勫€圭€笛囶敁濞撴悤.metadata.url
        // 路径 3: result.metadata.url
        JsonNode result = pollResult.get("result");
        if (result != null && result.isObject()) {
            JsonNode innerMeta = result.get("metadata");
            if (innerMeta != null && innerMeta.isObject()) {
                JsonNode url = innerMeta.get("url");
                if (url != null && url.isTextual()) {
                    String cleanUrl = sanitizeUrl(url.asText());
                    log.info("[extractVideoUrl] ✓ 命中路径 [result.metadata.url]: {}", cleanUrl);
                    return cleanUrl;
                }
            }
        }

        // 闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧湱鈧懓瀚崳纾嬨亹閹烘垹鍊為悷婊冪箻瀵娊鏁冮崒娑氬幗闂侀潧绻堥崺鍕倿閸撗呯＜闁归偊鍙庡▓婊堟煛瀹€鈧崰鏍蓟閸ヮ剚鏅濋柍褜鍓熷绋库槈閵忥紕鍘遍梺闈涱煭婵″洨绮婚悙鐑樼厽闁瑰灝鍟禍鎵偓瑙勬礀閻栧吋淇婂宀婃Х濠碘剝褰冮悧鎾愁潖濞差亜浼犻柛鏇ㄥ亽娴犳挳姊虹粙鎸庢崳闁轰浇顕ч锝囨嫚濞村顫嶉梺闈涚箳婵牓濡搁敂杞扮盎闂佸搫鍟崐鍝ユ暜閼哥偣浜滄い鎰枑濞呭﹥鎱ㄦ繝鍐┿仢妤犵偞鐗犻幃娆撳箵閹烘繄鈧娊姊绘担渚劸闁挎洏鍊曢敃銏ゆ焼瀹ュ懐顔夐梺闈涚箳婵厼危閸喐鍙忔俊銈傚亾婵☆偅顨婂鏌ユ偐瀹割喗瀵岄梺闈涚墕濡瑩藟閸℃瑢鍋撶憴鍕闁轰礁顭烽獮鍡涘礋椤掍礁鍔呴梺闈涱煭閼靛綊骞?3闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌熼梻瀵割槮缁炬儳缍婇弻鐔兼⒒鐎靛壊妲紒鐐劤缂嶅﹪寮婚敐澶婄闁挎繂鎲涢幘缁樼厱濠电姴鍊归崑銉╂煛鐏炶濮傜€殿噮鍣ｅ畷濂告偄閸涘鍞堕梻鍌欒兌椤牓顢栭崱娑樼闁告挆鍐ㄧ亰濡炪倖鎸鹃崑鎰ｉ崼鐔剁箚妞ゆ牗绻嶉崵娆愮箾閸涘洤娲﹂埛鎴炵箾閼奸鍤欐鐐搭殜閺岋綁鎮㈤崣澶嬬彋閻庢鍠栭…鐑藉箖閵忋倕绀傞柣鎾崇岸閸嬫挾绱掑Ο鍦畾濡炪倖鐗楃换鍐敂閻樼粯鐓曢柡鍐ㄥ亞閻掗箖鏌嶇憴鍕伌闁诡喗鐟╅幊鐘活敆娴ｇ儤顎囬梻鍌欑閹碱偊骞婇幇顓犵闁逞屽墰閳ь剚顔栭崰鏍€﹀畡閭﹀殨闁圭虎鍠楅崑鍕煣韫囨凹鍤冮柛鐔烽叄濮婄粯鎷呯粙娆炬闂佺粯鎸搁悧鎾崇暦娴兼潙鍐€闁靛ě鍛獎闂備礁鎲″ú锕傚磻閳ь剟鏌￠埀顒佺鐎ｎ偆鍘介梺褰掑亰閸撴瑧鐥閺屽秶绱掑Ο鑽ゎ槬闂傚洤顦扮换婵囩節閸屾凹浼€闂佹椿鍘界敮锟犲蓟閿涘嫪娌悹鍥ㄥ絻椤洦绻濈喊妯峰亾瀹曞洤鐓熼悗瑙勬礋娴滆泛顕ｉ幘顔藉亹闁汇垹鐏氬В搴ㄦ⒒閸屾艾鈧娆㈠顒夌劷鐟滄棃骞冭瀹曞崬鈽夊Ο纭风串闂備礁鎲＄粙鎴︽晝閿曞倶鈧懘寮婚妷锔惧幈闂佸湱鍋撻〃鍛村箠閹扮増鏅繝闈涙川缁犻箖寮堕崼婵嗏挃闁告帊鍗抽弻鐔哄枈閸楃偘绨奸梺缁橆殔妤犳悂鍩為幋锔藉亹闁割煈鍋呭В鍕⒑缁嬫鍎愰柣鈺婂灦瀵偊宕橀鍛櫆闂佸憡娲熷褍鈻撻妸锔剧瘈闁汇垽娼ф牎闂佺厧缍婄粻鏍х暦閿熺姴绠柤鎭掑劤閸樹粙姊洪崫鍕殭闁绘绮岃灋闁瑰濮风壕濂告煙闁箑鏋涘ù鐘洪哺閹便劍绻濋崘鈹夸虎閻庤娲樼划鎾荤嵁閹捐绠崇€广儱娲ら崵顒傜磽閸屾艾鈧嘲霉閸ヮ剦鏁嬬憸鏂跨暦閹邦垬浜归柟鐑樺灩閻ゅ嫰姊洪棃娴ュ牓寮插☉銏犵闁规儼濮ら悡蹇涚叓閸パ嶆敾妞ゅ骸妫濋弻?result 闂?
        // 路径 4: result.url
        if (result != null) {
            JsonNode url = result.get("url");
            if (url != null && url.isTextual()) {
                String cleanUrl = sanitizeUrl(url.asText());
                log.info("[extractVideoUrl] ✓ 命中路径 [result.url]: {}", cleanUrl);
                return cleanUrl;
            }
        }

        // 路径 5: 顶层 url (文档 §七 示例)
        JsonNode topUrl = pollResult.get("url");
        if (topUrl != null && topUrl.isTextual()) {
            String cleanUrl = sanitizeUrl(topUrl.asText());
            log.info("[extractVideoUrl] ✓ 命中路径 [顶层 url]: {}", cleanUrl);
            return cleanUrl;
        }

        log.warn("[extractVideoUrl] ✗ 所有 5 个路径都没找到 url, 响应 fields=[{}], body={}",
            collectFieldNames(pollResult),
            pollResult.toString().length() < 500 ? pollResult.toString() : pollResult.toString().substring(0, 500) + "...");
        return null;
    }


    /**
     * 清洗 URL:去除反引号、前后空格、markdown 符号
     */
    private static String sanitizeUrl(String url) {
        if (url == null || url.isBlank()) return null;
        String cleaned = url.trim();
        cleaned = cleaned.replaceAll("^`+|`+$", "");
        cleaned = cleaned.replaceAll("^['\"]+|['\"]+$", "");
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) return null;
        if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
            log.warn("[NewAPI] 清洗后的 URL 格式异常: 原始={}, 清洗后={}", url, cleaned);
        }
        return cleaned;
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
                        "NewAPI 调用失败:" + e.getStatusCode() + " " + e.getStatusText());
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
            log.info("NewAPI 响应解析, topFields={}", topFields);

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
                // 闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌熼梻瀵割槮缁惧墽鎳撻—鍐偓锝庝簼閹癸綁鏌ｉ鐐搭棞闁靛棙甯掗～婵嬫晲閸涱剙顥氬┑掳鍊楁慨鐑藉磻閻愮儤鍋嬮柣妯荤湽閳ь兛绶氬鏉戭潩鏉堚敩銏ゆ⒒娴ｈ鍋犻柛搴㈡そ瀹曟粓鏁冮崒姘€梺鍛婂姦閸犳鎮￠妷鈺傜厸闁搞儺鐓堝▓鏂棵瑰鍫㈢暫婵﹤鎼晥闁搞儜鈧崑鎾澄旈崨顓狅紱闂佽宕橀崺鏍х暦閸欏绡€闂傚牊绋掑婵堢磼閳锯偓閸嬫捇姊绘担渚劸闁哄牜鍓涢崚鎺戠暆閸曗斁鍋撻崒姣椽顢旈崨顏呭闂備浇濮ら敋妞わ富鍨跺鎶芥偄閸忚偐鍘遍梺缁樏壕顓熸櫠閻㈢鍋撳▓鍨灈妞ゎ厼鍢查锝夊箻椤旇棄浜滈梺鎯х箺椤曟牠宕惔銊︹拻濞达絿顭堥ˉ蹇涙煟閹惧磭澧︾€规洑鍗冲浠嬪Ω瑜忚ぐ楣冩⒑閸涘﹥澶勯柛瀣у亾闂佽　鍋撳ù鐘差儐閻撶喖鏌熼柇锕€澧紒鐙欏洦鐓冪紓浣股戠粈鈧梻鍥ь槹缁绘繃绻濋崒姘间紑闂佹椿鍘界敮鐐哄焵椤掑喚娼愭繛鍙夛耿閺佸啴濮€閵堝懏妲梺閫炲苯澧柕鍥у楠炴帡宕卞鎯ь棜濠碉紕鍋戦崐銈嗙濠婂牆鐤悗娑櫭肩换鍡涙煕椤愶絾绀€妤犵偑鍨烘穱濠囶敍濠婂啫濡哄┑鐐茬墱閸嬪﹤顫忕紒妯诲闁告稑锕ラ崰鎰節濞堝灝鏋ら柡浣割煼閻涱噣骞嬮敃鈧悙濠勬喐韫囨稑姹查柨鏇炲€归悡銉︾節闂堟稒锛嶆俊鎻掓啞椤ㄣ儵鎮欓鍕痪婵烇絽娲ら敃顏堝箖濞嗘挻鍋ㄩ梻鍫熺〒椤愬ジ姊绘担鍛婃儓闁稿﹦鏁诲畷鎴﹀箻缂佹ǚ鎷婚梺绋挎湰閻熴劑宕楀畝鈧槐鎺楊敋閸涱厾浠搁悗瑙勬礃缁诲牆顕ｉ幘顔藉€婚柛鈩冾殕椤撳潡姊绘担绋款棌闁稿鎳庨埢鏂库枎閹邦剛绐為梺褰掑亰閸樺ジ鍩€椤掑倸鍘撮柡宀嬬秮楠炲鎮樺ú璁崇凹缂佸倹甯″畷顐﹀礋閸偄鐦滈梻渚€娼ч悧鍡椢涘☉娆愭珷妞ゆ帊闄嶆禍婊堟煏婵炲灝鍔滄い銉︾矒閺屽秶鎷犻懠顑絿绱掔紒妯肩疄鐎规洜鍠栨俊鎼佸Ψ閵堝拋妫滈梻浣藉吹閸犳劙鎮烽妷褉鍋撳鐓庡箻缂侇喖鐗撳畷濂稿Ψ閿旀儳骞愬┑鐐舵彧缁插潡鎮洪弮鍫濆惞婵炲棙鍔戞禍婊堟煛閸ユ湹绨界紒瀣吹缁辨帡宕掑☉妯碱儌闂侀€炲苯澧鹃柟顑惧劦閸┾偓妞ゆ帒瀚壕濠氭煙閹规劦鍤欓柛鎰ㄥ亾婵＄偑鍊栭幐楣冨磹閿濆惟闁冲搫鍊婚崢顏呯節閵忥絾纭炬俊顐ｇ懃閳诲秹宕ㄩ妤€浜炬繛鍫濈仢閺嬫瑩鏌涘Δ浣糕枙妤犵偛鍟灃闁告劏鏅涢弸鍌炴⒑閸涘﹥澶勯柛鎾寸洴钘濋柕澶嗘櫆閳锋垹绱撴担璇＄劷濠⒀屼邯閺屾洟宕奸姀鈺冨姼濡炪倖娲╃紞渚€銆侀弴銏℃櫇闁逞屽墰缁鈽夐姀锛勫幐婵犮垼娉涢敃锔界閵忋垻纾奸柟閭﹀幘閳藉銇勯鍕殻濠碘€崇埣瀹曞崬螣濞差亞鈧櫣绱撻崒娆戭槮妞ゆ垵妫涢弫顕€鎮欓崫鍕姦濡炪倖甯婇悞锕傛儍閹达附鐓曢柣妯哄暱婵鏌?
                String errMsg = response.has("error") ? response.get("error").toString() : "Image generation failed";
                log.error("NewAPI generateImage 失败: {}, response={}", errMsg, response.toString());
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "NewAPI generateImage 失败:" + errMsg);
            }

            JsonNode dataArray = response.get("data");
            if (!dataArray.isArray() || dataArray.size() == 0) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "图片生成 data 数组为空");
            }

            JsonNode first = dataArray.get(0);
            // 打印 data[0] 字段名用于调试
            java.util.List<String> firstFieldNames = new java.util.ArrayList<>();
            first.fieldNames().forEachRemaining(firstFieldNames::add);
            log.info("NewAPI data[0] 字段名={}", firstFieldNames);

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
            log.error("NewAPI data[0] 缺少字段, 字段={}, 内容={}", firstFieldNames, dataContent);
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "NewAPI data[0] 缺少字段:" + firstFieldNames);
        } catch (Exception e) {
            if (e instanceof BusinessException) throw e;
            log.error("NewAPI generateImage failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "NewAPI 调用失败:" + e.getMessage());
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

        // 闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌熼梻瀵割槮缁炬儳缍婇弻鐔兼⒒鐎靛壊妲紒鎯у⒔閹虫捇鈥旈崘顏佸亾閿濆簼绨奸柟鐧哥秮閺岋綁顢橀悙鎼闂侀潧妫欑敮鎺楋綖濠靛鏅查柛娑卞墮椤ユ艾鈹戞幊閸婃鎱ㄩ悜钘夌；闁绘劗鍎ら崑瀣煟濡崵婀介柍褜鍏涚欢姘嚕閹绢喖顫呴柍鈺佸暞閻濇洜绱撻崒姘偓鐑芥倿閿曚焦鎳屾繝鐢靛仜閹冲酣鎮ч幘鎰佹綎缂備焦蓱婵挳鏌涘☉姗堝伐闁哄棗鐗婄换娑㈠箻鐎靛壊鏆″銈冨妼閿曘倝鎮鹃悿顖樹汗闁圭儤绻冮弲婊堟⒑閸撴彃浜濈紒璇插閺佸秴鈽夐姀鈾€鎷洪梺闈╁瘜閸樺吋绂嶆ィ鍐╃厱闁瑰墽顥愭竟妯汇亜椤撶偞鍠橀柛鈺嬬節瀹曘劑顢欓幆褍绠洪梻鍌欑濠€閬嶅磻閹惧绠惧┑鐘叉祩閺佷焦淇婇妶鍕濞存粍绮嶉妵鍕箛闂堟稐绨绘繛瀛樼矌閸嬬喓妲?multipart 闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌熼梻瀵割槮缁炬儳婀遍埀顒傛嚀鐎氼參宕崇壕瀣ㄤ汗闁圭儤鍨归崐鐐烘偡濠婂啰绠荤€殿喗濞婇弫鍐磼濞戞艾骞堟俊鐐€ら崢浠嬪垂閸偆顩叉繝闈涱儐閻撴洘绻涢崱妤冪缂佺姴顭烽弻锛勪沪缁嬪灝鈷夐悗鍨緲鐎氼噣鍩€椤掑﹦绉靛ù婊勭箞椤㈡瑩宕ㄩ娑欐杸闂佺粯鍔曞鍫曞煝閺囩伝鐟邦煥閸愵亜鐓熼悗娈垮櫘閸嬪﹤鐣烽崼鏇ㄦ晢濞达絽鎼敮楣冩⒒婵犲骸浜滄繛璇х畱鐓ら柡宓嫭鐦庨梻鍌氬€风粈渚€骞夐敍鍕床闁告劦鍠撻埀顒€鍟换婵嬪磼閵堝棛绋佺紓鍌氬€烽悞锕傗€﹂崶顒佸仭鐟滅増甯楅悡鏇㈡煏婢跺鐏ラ柛鐘宠壘椤洭鎳￠妶鍥╋紳闂佺鏈悷褔藝閿斿浜滈柨鏇炲€烽幉鍓р偓娈垮櫘閸嬪棝骞忛悩缁樺殤妞ゆ帊鐒﹂鏇㈡⒒娴ｅ憡鎯堟繛灞傚灲瀹曞綊宕烽鐘辩瑝闂佹寧绻傞ˇ浼存偂閵夆晜鐓涢柛鎰╁妼閳ь剛鎳撻埢宥夊即閵忥紕鍘卞┑鈽嗗灡鐎笛囁夋径鎰厓閻熸瑥瀚悘锔筋殽閻愯韬柡灞剧⊕缁绘繈宕橀妸銉綒闁诲氦顫夊ú姗€宕归崸妤冨祦婵☆垵鍋愮壕鍏间繆椤栨粌甯舵鐐搭殕缁绘繂顕ラ柨瀣凡闁逞屽墯閸旀瑥鐣烽幋锕€绠荤紓浣诡焽閸欏棝姊洪崫鍕闁挎岸鏌涢弮鎾愁洭闁?
        // 2026-08-13 FIX: WebClient multipart -> RestTemplate multipart (avoid chunked encoding issue)
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

        // 婵犵數濮烽弫鍛婃叏閻戣棄鏋侀柛娑橈攻閸欏繘鏌ｉ幋锝嗩棄闁哄绶氶弻娑樷槈濮楀牊鏁鹃梺鍛婄懃缁绘﹢寮婚敐澶婄闁挎繂妫Λ鍕⒑閸濆嫷鍎庣紒鑸靛哺瀵鎮㈤崗灏栨嫽闁诲酣娼ф竟濠偽ｉ鍓х＜闁绘劦鍓欓崝銈嗙箾绾绡€鐎殿喖顭烽幃銏㈡偘閳ュ厖澹曢梺姹囧灪椤旀牠鎮炴ィ鍐ㄧ柈闁告縿鍎崇壕钘壝归敐鍡楃祷濞存粎鍋撶换婵嬫偨闂堟刀銏＄箾鐠囇呯暤闁诡噯绻濆畷姗€顢旈崨顓熺€炬繝鐢靛Т閿曘倝鎮ч崱娆戠焼闁割偆鍠愰崣蹇斾繆椤栨稑顕滅痪顓熷劤椤╁ジ宕ㄧ€涙ǚ鎷洪梺鍛婄☉閿曘儲寰勯崟顖涚厱闁靛鍊曞畵鍡欌偓瑙勬穿缁绘繈鐛惔銊﹀殟闁靛／鍐ㄧ疄闂傚倷鐒﹂弸濂稿疾濞戙垹绐楁慨姗嗗厴閺嬫棃鏌￠崘锝呬壕闂侀潧娲ょ€氼垳绮诲☉銏℃櫜闁告洦鍨版禒鎰版⒒娴ｅ憡鎯堥柡鍫墰缁瑩骞嬮敃鈧悡婵嬪箹濞ｎ剙鈧鎮块埀顒勬⒑閸濆嫭宸濆┑顖ｅ弮瀹曨垳鈧綆鍠楅埛鎺懨归敐鍛暈闁诡垰鐗撻弻锝夘敇閻戝棙楔缂備浇浜崑鐐电箔閻旂厧鐒垫い鎺戝閸嬫ɑ銇勯弴妤€浜鹃悗瑙勬礀閻栧ジ銆佸Δ浣哥窞閻庯綆鍋呴悵婊勭節閻㈤潧浠╅柟娲讳簽瀵板﹪宕稿Δ鈧粻鐘绘煙閹规劦鍤欑紒鈧崼銉︾厱妞ゎ厽鍨垫禍鐐电磼閳锯偓閸嬫挾绱撴担绋库挃濠⒀勵殙閹筋偄顪冮妶搴′簻闁挎洦浜璇测槈濮橈絽浜鹃柨婵嗙凹缁ㄥジ鏌涢妶鍛枠闁哄备鍓濋幏鍛矙閹稿孩顔掑┑鐘愁問閸犳帡宕戦幘缁樷拺闂傚牊绋撴晶鏇㈡煙閾忣偅宕屾鐐搭殜瀵挳鎮欓埡鍌涙澑闂備胶绮崝鏍ь焽濞嗘挻鍊堕柣鏂垮悑閻撴洟鏌曟繛褍瀚▓宀勬⒑鏉炴壆璐伴柛锝忕到椤繒绱掑Ο璇差€撻梺鑽ゅ枛閸嬪﹪宕电€ｎ剛纾藉ù锝囩摂閸ゆ瑩鏌涙繝鍌涘仴闁绘侗鍠楃换婵嬪礃閳轰礁濡抽梻渚€娼ц墝闁瑰啿娲畷鎴﹀箻鐠囨彃鍞ㄩ梺闈涱焾閸庡磭绮ｉ悙鐑樷拺鐟滅増甯掓禍浼存煕濡粯鍊愭鐐茬箰鐓ゆい蹇撴噳閹锋椽姊婚崒姘卞闁哄懏鐩幆浣割煥閸喓鍘卞┑鈽嗗灡娴滀粙宕戦姀鈶╁亾鐟欏嫭纾搁柛銊ㄦ椤曪綁宕奸弴鐐电枃闂侀潧臎閸曨偅鐣俊鐐€ら崢褰掑礉閹存繄鏆︽慨妞诲亾妞ゃ垺妫冨畷鐔碱敇閻愯尙顔戦梻鍌欐祰椤曆呪偓娑掓櫊椤㈡瑩寮介鐐电崶濠电偞鍨崺鍕极鐎ｎ剚鍠愰柡鍐ㄧ墕閺勩儵鏌曟径娑氱暠缂佸墎鍋涢埞鎴︽倷椤忓嫮浼勯梺鍝ュУ閻楃姴顕ｆ繝姘╃憸澶愬磻閹剧粯鏅查幖绮光偓鍐茬闂備胶顭堥鍡涘箰閼姐倖宕叉繝闈涙－濞尖晜銇勯幘妤€瀚峰Λ鍛攽閿涘嫬浜奸柛濠冪墵瀹曟繈骞嬮敃鈧崹鍌炴煟閹寸伝顏嗘閻愮儤鐓曢柡鍥ュ妼閻忥繝鏌ｉ幘瀵告噰闁哄本绋戦埥澶婎潨閸喐鏆伴梺璇茬箰妤犲繑淇婇崶顒€鐒垫い鎺戝枤濞兼劖绻涢崣澶涜€跨€规洖缍婂畷绋课旈崘銊с偊婵犳鍠楅妵娑㈠磻閹剧粯鐓熸繛鎴濆船閺嬬喓鈧灚婢樼€氭澘鐣烽妸锔剧瘈闁告洦鍓涚粙渚€姊婚崒姘偓鎼佸磹妞嬪海鐭嗗ù锝夋交閼板潡姊洪鈧粔瀵稿閸ф鐓忛柛顐ｇ箥濡叉悂鏌涢妸銉モ偓鍧楀蓟閵堝洨鐭欓悹鎭掑妺缁數绱?
        body.add("model", "gpt-image-2-2k");
        body.add("prompt", prompt);
        body.add("size", size != null ? size : "1024x1024");
        if (quality != null) body.add("quality", quality);
        if (style != null) body.add("style", style);
        body.add("response_format", "b64_json");

        // 解码 base64 引用图片并添加为 multipart 文件
        int imgIndex = 0;
        for (String dataUri : referenceImages) {
            try {
                byte[] imageBytes = decodeDataUri(dataUri);
                String mimeType = getMimeTypeFromDataUri(dataUri);
                String ext = mimeType.equals("image/jpeg") ? ".jpg" : ".png";
                final int currentIdx = imgIndex;

                // gpt-image 婵犵數濮烽弫鍛婃叏閻戣棄鏋侀柛娑橈攻閸欏繘鏌ｉ幋锝嗩棄闁哄绶氶弻娑樷槈濮楀牊鏁鹃梺鍛婄懃缁绘﹢寮婚敐澶婄婵犲灚鍔栫紞妤呮⒑闁偛鑻晶顕€鏌涙繝鍌涘仴妤犵偞鍔栫换婵嬪礃椤忓棗楠勯梻浣稿暱閹碱偊顢栭崶鈺冪煋妞ゆ棃鏁崑鎾舵喆閸曨剛锛橀梺鍛婃⒐閸ㄧ敻顢氶敐澶婇唶闁哄洨鍋熼鍝勨攽閻樼粯娑ч柣妤€鍟村畷鎴﹀箻濞茬粯鏅ｉ梺缁樺灥濡瑧鈧潧鐭傚娲濞戞艾顣洪梺纭呮珪閸旀鍒掔紒妯侯嚤閻庢稒顭囬崢钘夆攽鎺抽崐鎰板磻閹剧繝绻嗘い鎰剁悼缁犵偞顨ラ悙鎻掓殻闁诡喗鐟╁畷顐﹀礋椤愩倐鍋撻鐑嗘富闁靛牆妫楁慨澶娾攽椤旇偐锛嶉柤楦块哺缁绘繂顫濋娑欏闂備浇宕甸崰鎾存櫠濡ゅ懎绠氶柛顐ゅ枍缁诲棙銇勯幇鍓佺У婵炲牊娲滅槐鎺楀磼濮樻瘷褏鈧娲樼划蹇浰囩€靛摜妫柟顖嗕礁浠梺鍝勬湰閻╊垶鐛Ο浣曟棃鍩€椤掆偓铻炴繛鍡樻惄閺佷焦淇婇妶鍛櫤闁抽攱鍨块弻娑樷槈濮楀牆濮涢梺鐟板暱閸熸挳寮诲☉銏″亜闁告稑锕︾粙鍥⒑娴兼瑧鍒伴柛銏ｅ皺閸欏懎顪冮妶鍛閻庢凹鍣ｅ畷婵嬫晝閳ь剟鈥旈崘顔嘉ч柛鈩冾殘閻熸劙姊婚崒姘仼缂佸鏁哥划瀣吋閸滀胶鍙嗛梺鍓插亞閸犳捇宕㈤悽鍛娾拺缂備焦锚閻忥箓鏌ㄥ鑸电厽闊洤顑呴崝锕傛煛鐏炵晫啸妞ぱ傜窔閺屾盯骞橀弶鎴濇懙濡ょ姷鍋涢崯鏉戠暦閹烘埈娼╅弶鍫涘妽椤旀洟姊绘笟鈧褏鎹㈤崱娑樼疇闁搞儺鍓欑壕濠氭煙閸撗呭笡闁稿鍔戦弻锝夊閵忕姳鍖栭梺閫炲苯澧柨鏇ㄤ邯瀵鈽夊锝呬壕闁挎繂楠告禍婵嬫倶韫囷絽寮柡灞界Ф缁辨帒螣鐠囪尙锛撻柣搴ゎ潐濞叉牜绱炴繝鍥モ偓浣糕枎閹炬潙浠奸柣蹇曞仩閸嬫劙骞愭径鎰拻闁稿本鑹鹃埀顒勵棑缁牊绗熼埀顒勭嵁婢舵劕鐏抽柟棰佺劍缂嶅酣鎮峰鍛暭閻㈩垱甯″畷褰掑磼閻愬鍘遍悷婊冮叄閵嗗啴宕ㄩ幍顔绢啍濠电姷鏁搁崑鐘诲箵椤忓棗绶ら悹鎭掑妽閸忔粓鎮规潪鎵Э闁挎繂顦柋鍥煟閺傚灝顣崇紒鐘宠壘椤啴濡堕崱娆忊拡闂佺顑囬崑銈咁嚕椤愶絾缍囬柕濠忕导缁ㄨ顪冮妶鍡楀闁搞劏顕ч悺顓㈡⒒娴ｅ摜鏋冩い顐㈩樀瀹曞綊宕稿Δ鈧弰銉╂煏婢跺棙娅呮鐐灪娣囧﹪濡堕崨顓熸闂佸憡绻冮〃濠傤潖缂佹ɑ濯撮柛娑橈工閺嗗牓姊洪悡搴ｇШ缂佺姵鐗犻妴渚€寮介鐐茬獩闂佸搫顦伴崹鑸垫綇閸儲鈷戦悗鍦У閵嗗啴鎮规担鍦弨鐎殿喓鍔嶇粋鎺斺偓锝庡亞閸樿棄鈹戦埥鍡楃仴婵炲拑绲剧粋鎺戔槈閵忥紕鍘搁梺绯曗偓宕囩婵炲懎鎳橀弻宥囨喆閸曨偆浼屽銈冨灪閻熝囧箯閻樿绠甸柟鐑樻煟閸嬫牠姊虹拠鍙夊攭妞ゎ偄顦叅婵犻潧顑戠紞鏍ь熆鐠鸿櫣鐏辩紒鎲嬬畵閺岋綁鏁愰崨顖滀紘闂佹椿鍘介悷鈺呭蓟閻旇櫣鐭欓柛顭戝櫘閸斿鎮跺鍓хМ婵﹤顭峰畷鎺戔枎閹搭厽袦闂備礁婀遍埛鍫ュ磻閸℃瑥鍨濇繛鍡樺姉閻熷綊鏌嶈閸撴瑩鎮鹃悜钘夘潊闁挎稑瀚峰ù鍕煟鎼搭垳绉靛ù婊勭矒楠炲棝鎮欓悜妯衡偓鐢告煕韫囨搩妲稿ù婊堢畺濮婃椽宕ㄦ繝鍐槱闂佸憡鎸婚惄顖氱暦閵忋倖鐒肩€广儱妫岄幏娲⒑闂堚晛鐦滈柛妯哄悑缁傚秹鎮欓鍙ョ盎闂侀潧顭堥崕鏌ュ闯娴犲鐓冪憸婊堝礈濮樿埖鍤屽Δ锝呭暙鎼村﹪鏌＄仦璇插姉闁逞屽墾缁犳挸鐣烽崼鏇ㄦ晢濞达綁鏅茬花濠氭⒒娴ｄ警鐒剧紒缁樺姍閹啴鎮滈挊澶岊唵濠电偛妯婃禍婵嬪煕閹达附鐓曟繛鎴烇公閺€濠氭倶韫囨柨顥嬮柟鍙夋倐瀵爼宕归鑺ヮ唹缂傚倷绀侀崐鍝ョ矓閹绢喖鐓橀柟杈剧畱閻愬﹪鏌嶉崫鍕灓闁哥喎閰ｅ缁樻媴閸涘﹤鏆堥梺鑽ゅ枂閸庝絻妫熼柡澶婄墑閸斿秴鈻嶉悩缁樼厽闁靛繒濮甸崯鐐烘煟閹惧鎳勯柕鍥у瀵粙濡搁妶鍕劉闂佽瀛╅惌顕€宕￠幎钘夌闁割偅娲栭崘鈧銈嗘尵閸犳捇宕㈤崡鐐╂斀闁绘劖娼欓悘銉р偓瑙勬处閸撶喎顕ｉ幖浣肝у璺侯儑閸樺崬鈹戦濮愪粶闁稿鎸搁湁婵犲﹤鍟伴崺锝団偓娈垮枛椤兘寮幇鏉块唶闁靛繈鍨哄鎴︽⒒娴ｅ憡鎯堟繛灞傚姂瀹曟垵螣閻撳骸鐏婇梺鐓庢憸閺佸摜寮ч埀顒勬⒑閸愯尙娈遍柛瀣崌閺屾盯鍩勯崘锔跨凹闂佽鍎抽悡鍕償閵娿儳鍊為悷婊勭箞閻擃剟顢楅崒妤€浜鹃悷娆忓缁€鈧梺缁樼墪閵堟悂濡存担鑲濇梹鎷呴悷閭︹偓鎾绘⒑閸涘﹦绠撻悗姘煎墰缁鎮欓悜妯锋嫽婵炶揪绲块悺鏃堝吹濞嗘挻鐓曢柟瀵稿У濞呮洜绱掓潏鈺佷槐鐎规洖宕埥澶娢熺涵椋庡耿闂傚倷绀侀幉鈩冪瑹濡ゅ懎鍌ㄥΔ锝呭暙濮规煡鏌ㄩ弮鍫熸殰闁稿鎹囧畷妤佸緞婵犱礁顥氶梻鍌欑窔閳ь剛鍋涢懟顖涙櫠閹绢喗鐓曢柍瑙勫劤娴滅偓淇婇悙顏勨偓鏍暜閹烘绐楁慨姗嗗墻閻掍粙鏌熼柇锕€骞樼紒鐘荤畺閺屾稑鈻庤箛锝嗩€嗛梺鍏兼緲濞硷繝寮婚埄鍐╁缂佸瀵у▓缁樼節濞堝灝鏋撻柛瀣崌濮婃椽鎮欓挊澶婂Г闁诲繐绻戦悷褏鍒掔拠宸僵闁煎摜顣介幏娲偡濠婂懎顣奸悽顖涱殜閺佸秹鎮㈤崗鑲╁幗闂佺娅ｉ崑鐔兼偩閻㈢鍋撳▓鍨灍鐟滄澘鍟撮垾锕傚Ω閳轰礁绐涘銈嗙墬缁絿妲愰崘娴嬫斀闁绘劘鍩栬ぐ褏绱撳鍕槮妞ゎ厼娲╅ˇ褰掓煙椤旀枻鑰挎い銏℃瀹曞ジ鎮㈤崫鍕闂傚倷绶氬褔鎮ч崱姗嗘缂佸绨遍弸宥団偓骞垮劚濡瑩宕ｈ箛鎾斀闁绘ɑ褰冩禍鐐烘煟閹剧懓浜归柍褜鍓濋～澶娒哄Ο鍏兼殰闁圭儤顨呴悡婵嬪箹濞ｎ剙濡肩紒鐙呯稻缁绘繈妫冨☉娆欑礊闂佽瀛╅幐鍐差潖缂佹ɑ濯寸紒娑橆儏濞堫厼鈹戦悙宸殶闁稿繑锕㈤獮鍐倻閽樺顔呴梺鑺ッˇ顖滅玻濞戞﹩娓婚柕鍫濇婢ь剛绱掗濂稿弰鐎规洏鍨介、娑㈡倷缁瀚藉┑鐐舵彧缁插潡宕曢妶澶婂惞闁逞屽墮椤啴濡堕崱妯侯槱闂佸憡鐟ラ崯顐︽偩閻戣棄绠ｉ柨鏃囨娴滄粓姊虹粙璺ㄧ闁汇劎鍏橀獮鎰板礃椤旇В鎷洪梺鍛婄☉閿曘儲寰勯崟顖涚厱闁靛ň鏅欓幉楣冩煙椤斿厜鍋撻弬銉︻潔闂侀潧楠忕槐鏇㈠储闁秵鈷戦悷娆忓缁舵彃顭胯闁帮絽鐣峰璺虹骇婵☆偆鏁搁幊鎾烩€﹂妸鈺佺闁靛鍨虹€垫牜绱撻崒娆愮グ濡炲瓨鎮傞獮鎰節濮橆剛顔嗛梺鍛婄☉閻°劑骞嗛悙鐑樼厽闁绘梻顭堥ˉ瀣亜閹邦兙鍋㈡慨濠勭帛閹峰懘宕崟顐⑿曢梻浣告惈閹冲寮查悩鑼殾閻熸瑥瀚閬嶆倵濞戞顏呯婵傚憡鈷戠紓浣姑悘杈ㄤ繆椤愩垹顏柡灞筋儔瀹曞爼顢楁担鍝勫箰闂備焦鎮堕崕顖炲磿鏉堛劋绻嗗ù鐘差儐閻撴盯鏌涘☉鍗炰簻闁诲浚浜炵槐鎺旂磼濡皷濮囧┑鐐靛帶缁绘ê鐣峰鍡╂Ь閻炴熬绠撳缁樻媴閸涘﹥鍎撻梺纭呮珪閹哥偓绂嶇粙搴撴瀻闁瑰鍎愬?image 闂傚倸鍊搁崐鎼佸磹閹间礁纾归柟闂寸绾惧綊鏌熼梻瀵割槮缁炬儳婀遍埀顒傛嚀鐎氼參宕崇壕瀣ㄤ汗闁圭儤鍨归崐鐐差渻閵堝棗绗掓い锔垮嵆瀵煡顢旈崼鐔蜂画濠电姴锕ら崯鎵不婵犳碍鐓曢柍瑙勫劤娴滅偓淇婇悙顏勨偓鏍暜婵犲洦鍤勯柛顐ｆ礀閻撴繈鏌熼崜褏甯涢柣鎾寸洴閺屾稑鈽夐崡鐐寸亾缂備胶濮甸敃銏ゅ蓟濞戙垹绠抽柟鎯х－閻熴劑姊虹€圭媭鍤欓梺甯秮閻涱喖螣閾忚娈鹃梺鎼炲劥濞夋盯寮挊澶嗘斀闁绘ɑ顔栭弳婊呯磼鏉堛劍绀嬬€规洘鍨甸埥澶愬閳ュ啿澹勯梻浣虹帛閸ㄧ厧螞閸曨厼顥氬┑鐘崇閻撴瑩鏌熺憴鍕Е闁搞倖鐟х槐鎺楀焵椤掑嫬绀冮柍鐟般仒缁ㄥ姊洪崫鍕殭闁稿﹤鎽滈弫顕€宕奸弴鐔哄幘闂佸搫顦冲▔鏇熺閵忋倖鐓冮悷娆忓閻忔挳鏌熼鐣屾噰鐎殿喖鐖奸獮瀣偐鏉堚晝顦ㄥ┑鐘殿暜缁辨洟宕戝☉銏″仱闁靛ň鏅涚粻鏍煕鐏炴儳鍤柛?
                // 2026-08-13 FIX: LinkedMultiValueMap.add 返回 void,不能链式 .contentType()
                // RestTemplate 也不需要显式 contentType(用 application/octet-stream 默认即可)
                body.add("image", new ByteArrayResource(imageBytes) {
                    @Override
                    public String getFilename() {
                        return "reference_" + currentIdx + ext;
                    }
                });
                imgIndex++;
            } catch (Exception e) {
                log.warn("编辑第{}张图片失败: {}", imgIndex, e.getMessage());
                imgIndex++;
            }
        }

        if (imgIndex == 0) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "引用图片解码失败，无法进行图片编辑");
        }

        log.info("NewAPI /v1/images/edits: promptLen={}, refImageCount={}", prompt.length(), imgIndex);

        try {
            // 2026-08-13 FIX: 用 RestTemplate 替换 WebClient,避免 Transfer-Encoding: chunked 400
            RestTemplate editRestTemplate = new RestTemplate();
            // 设置 10 分钟 timeout
            editRestTemplate.getMessageConverters().stream()
                .filter(c -> c instanceof org.springframework.http.converter.FormHttpMessageConverter)
                .findFirst()
                .ifPresent(c -> ((org.springframework.http.converter.FormHttpMessageConverter) c)
                    .setCharset(java.nio.charset.StandardCharsets.UTF_8));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set("Authorization", "Bearer " + token);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            ResponseEntity<String> respEntity = editRestTemplate.exchange(
                baseUrl + "/v1/images/edits",
                HttpMethod.POST,
                requestEntity,
                String.class
            );

            String respBody = respEntity.getBody();
            if (respBody == null || respBody.isEmpty()) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "Image edit returned empty body");
            }

            JsonNode response = new ObjectMapper().readTree(respBody);

            if (response == null) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "图片编辑返回为空（响应为 null）");
            }

            // 打印响应结构用于调试
            java.util.List<String> topFields = new java.util.ArrayList<>();
            response.fieldNames().forEachRemaining(topFields::add);
            log.info("NewAPI editImage 响应字段: {}", topFields);

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
            log.error("NewAPI editImage data[0] 缺少字段, 字段={}, 内容={}", firstFieldNames, dataContent);
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "NewAPI data[0] 缺少字段:" + firstFieldNames);
        } catch (Exception e) {
            // 2026-08-13 FIX: 用 instanceof pattern matching,避免 throw e 编译错误 (Java 21 严格)
            if (e instanceof BusinessException be) throw be;
            if (e instanceof org.springframework.web.client.HttpClientErrorException he) {
                log.error("NewAPI /v1/images/edits failed: {} {}", he.getStatusCode(), he.getResponseBodyAsString());
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                    "NewAPI 调用失败:" + he.getStatusCode() + " " + he.getStatusText());
            }
            log.error("NewAPI editImage failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "NewAPI 调用失败:" + e.getMessage());
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
     * 从 NewAPI 错误响应中提取用户可读的错误信息。
     * NewAPI 的错误体有多层嵌套 JSON，比如：
     * <pre>
     * {"code":"fail_to_fetch_task","message":"{\"error\":{\"message\":\"insufficient balance\",\"type\":\"insufficient_quota\"}}"}
     * </pre>
     *
     * <p>也支持 aicoming 直接返回顶层 code + type 的格式：
     * <pre>
     * {"code":"asset_line_unavailable","type":"asset_line_unavailable","message":"..."}
     * </pre>
     */
    private String parseErrorMessage(String responseBody, int statusCode) {
        if (responseBody == null || responseBody.isBlank()) {
            return "视频任务提交失败: HTTP " + statusCode;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // aicoming 顶层 code 直接是错误类型（如 asset_line_unavailable）
            String topCode = root.path("code").asText(null);
            if (topCode != null && !topCode.isBlank()
                && !"0".equals(topCode) && !"200".equals(topCode)) {
                String topMsg = root.path("message").asText(null);
                return translateErrorType(topCode)
                    + (topMsg != null && !topMsg.isBlank() ? "（" + topMsg + "）" : "");
            }

            // 尝试解析嵌套的 message 字段
            String rawMessage = root.path("message").asText(null);
            if (rawMessage != null && !rawMessage.isBlank()) {
                try {
                    JsonNode inner = objectMapper.readTree(rawMessage);
                    JsonNode errorNode = inner.path("error");
                    String type = errorNode.path("type").asText(null);
                    String detail = errorNode.path("message").asText(null);
                    if (type != null) {
                        String chineseMsg = translateErrorType(type);
                        return chineseMsg + (detail != null ? "（" + detail + "）" : "");
                    }
                    if (detail != null) {
                        return "视频任务提交失败: " + detail;
                    }
                } catch (Exception ignored) {
                    // message 不是 JSON，直接用
                }
                return "视频任务提交失败: " + rawMessage;
            }
            // 尝试顶层 error 字段
            JsonNode errorNode = root.path("error");
            if (!errorNode.isMissingNode()) {
                String type = errorNode.path("type").asText(null);
                String msg = errorNode.path("message").asText(null);
                if (type != null) {
                    return translateErrorType(type) + (msg != null ? "（" + msg + "）" : "");
                }
                if (msg != null) return "视频任务提交失败: " + msg;
            }
        } catch (Exception ignored) {}
        return "视频任务提交失败: HTTP " + statusCode;
    }

    /** 将 NewAPI / aicoming 错误类型映射为用户可读的中文提示
     *
     *  <p>完整错误码见视频接入中转站文档 §7。新增的错误码：
     *  <ul>
     *    <li>asset_line_unavailable —— provider 11 间歇性故障（最常见）</li>
     *    <li>asset_not_ready —— 素材还在 processing</li>
     *    <li>asset_invalid_reference —— asset://aic_xxx 格式错误</li>
     *    <li>model_not_supported_by_selected_providers —— 模型名 aicoming 不识别</li>
     *    <li>fail_to_fetch_task —— proxy 包装错误</li>
     *  </ul>
     */
    private String translateErrorType(String type) {
        if (type == null) return "视频任务提交失败";
        return switch (type) {
            case "insufficient_quota" -> "账户余额不足，无法提交视频任务";
            case "insufficient_balance" -> "账户余额不足，请充值后重试";
            case "invalid_resolution" -> "不支持的分辨率参数（仅支持 480p/720p/1080p/4k 小写）";
            case "invalid_param" -> "请求参数有误";
            case "rate_limit_exceeded" -> "请求过于频繁，请稍后重试";
            case "content_policy_violation" -> "内容不符合安全策略，请修改提示词";
            case "asset_line_unavailable" -> "素材线路暂不可用（provider 11 间歇性故障），请稍后重试或换用其它素材";
            case "asset_not_ready" -> "素材还在处理中，请稍后再试";
            case "asset_invalid_reference" -> "素材引用格式错误（应为 asset://aic_xxx）或素材已失效";
            case "model_not_supported_by_selected_providers" -> "当前模型未被所选 provider 支持，请改用 doubao-seedance-2.0 基础名";
            case "fail_to_fetch_task" -> "查询任务状态失败，请稍后重试";
            default -> "视频任务提交失败（" + type + "）";
        };
    }

    /**
     * 音频转文字（ASR），调 NewAPI /v1/audio/transcriptions。
     *
     * @param audioBytes 音频二进制（wav/mp3）
     * @param mimeType   MIME 类型，如 audio/wav
     * @return 识别结果列表，每段含 start (秒), end (秒), text (文本)
     */
    public List<Map<String, Object>> audioTranscribe(byte[] audioBytes, String mimeType) {
        if (audioBytes == null || audioBytes.length == 0) {
            log.warn("[ASR] audioBytes 为空，返回空列表");
            return List.of();
        }
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("model", "whisper-1");
        final String filename = "audio." + (mimeType != null && mimeType.contains("wav") ? "wav" : "mp3");
        builder.part("file", new ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() { return filename; }
        }, MediaType.parseMediaType(mimeType != null ? mimeType : "audio/wav"));

        try {
            JsonNode resp = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/audio/transcriptions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(120))
                .block();

            // 响应的 segments 字段是数组 [{start, end, text}, ...]
            JsonNode segments = resp != null ? resp.path("segments") : null;
            if (segments == null || !segments.isArray() || segments.size() == 0) {
                log.warn("[ASR] segments 为空，resp={}", resp);
                return List.of();
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode seg : segments) {
                Map<String, Object> m = new HashMap<>();
                m.put("start", seg.path("start").asDouble(0.0));
                m.put("end", seg.path("end").asDouble(0.0));
                m.put("text", seg.path("text").asText(""));
                result.add(m);
            }
            log.info("[ASR] 识别完成: {} segments, {} bytes", result.size(), audioBytes.length);
            return result;
        } catch (Exception e) {
            log.error("[ASR] 失败: {}", e.getMessage());
            throw e instanceof BusinessException ? (BusinessException) e
                : new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "ASR 失败: " + e.getMessage());
        }
    }

    /**
     * 批量视觉 caption：一次传多张图片 URL 给 VL 模型，返回每张图的描述。
     *
     * @param imageUrls 图片公网 URL 列表（1-3 张）
     * @param prompt    给 VL 模型的指令
     * @return 每张图的 caption，顺序与 imageUrls 一致
     */
    public List<Map<String, String>> visionCaptionBatch(List<String> imageUrls, String prompt) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return List.of();
        }
        // 构建多图 content 数组：[{type:"text", text:prompt}, {type:"image_url", image_url:{url:url1}}, ...]
        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> textPart = new HashMap<>();
        textPart.put("type", "text");
        textPart.put("text", prompt);
        content.add(textPart);
        for (String url : imageUrls) {
            Map<String, Object> imgPart = new HashMap<>();
            imgPart.put("type", "image_url");
            imgPart.put("image_url", Map.of("url", url));
            content.add(imgPart);
        }

        Map<String, Object> userMsg = Map.of("role", "user", "content", content);
        Map<String, Object> body = new HashMap<>();
        body.put("model", visionModel);
        body.put("messages", List.of(userMsg));
        body.put("max_tokens", 2048);

        try {
            JsonNode resp = webClientBuilder.baseUrl(baseUrl).build()
                .post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(180))
                .block();

            if (resp == null || !resp.has("choices") || resp.get("choices").size() == 0) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "VL 模型返回为空");
            }
            String raw = resp.get("choices").get(0).path("message").path("content").asText("");
            if (raw.isBlank()) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "VL 模型返回空 content");
            }
            // 尝试解析为 JSON 数组 [{camera, action}, ...]
            String jsonStr = raw.trim();
            // 去掉可能的 markdown 代码块包裹
            if (jsonStr.startsWith("```")) {
                int end = jsonStr.indexOf("\n");
                jsonStr = jsonStr.substring(end + 1);
                if (jsonStr.endsWith("```")) {
                    jsonStr = jsonStr.substring(0, jsonStr.length() - 3);
                }
            }
            List<Map<String, String>> result = objectMapper.readValue(jsonStr,
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, String>>>() {});
            log.info("[VL-BATCH] {} images → {} captions, model={}", imageUrls.size(), result.size(), visionModel);
            return result;
        } catch (Exception e) {
            log.error("[VL-BATCH] 失败: model={}, err={}", visionModel, e.getMessage());
            throw e instanceof BusinessException ? (BusinessException) e
                : new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "VL 批量 caption 失败: " + e.getMessage());
        }
    }

    /** 把超长 JSON 字符串截断到指定长度，方便日志查看 */
    private String truncateForLog(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...(truncated, totalLen=" + s.length() + ")";
    }

    /**
     * 2026-08-09 新增:图生视频(asset URL 版)
     * 2026-08-10 v2 修复:之前 placeholder 兜底会让 NewAPI 收到空字节 → FAILED
     * 现在直接复用 submitVideo 路径,把 imageBytes 当 input_reference 多部分上传。
     *
     * @param prompt       视频生成 prompt
     * @param assetUrl aicoming proxy assetUrl(仅用于日志/debug,实际不传给 NewAPI)
     * @param imageBytes   原图字节(由调用方持有;NewAPI 不接受 asset:// URL,必须以 multipart 重新提交)
     * @param imageFilename 上传时的文件名(用于 Content-Disposition)
     * @param imageMime    图片 MIME
     * @param duration     时长(秒)
     * @param resolution   分辨率
     */
    public SubmitResult submitVideoWithAsset(String prompt, String assetUrl, byte[] imageBytes,
                                       String imageFilename, String imageMime,
                                       int duration, String resolution) {
        log.info("[NewAPI] submitVideoWithAsset: 复用 submitVideoFull multipart 路径(原图 {} bytes, assetUrl={})",
            imageBytes == null ? 0 : imageBytes.length, assetUrl);
        if (imageBytes == null || imageBytes.length == 0) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "submitVideoWithAsset: imageBytes 为空,无法提交 NewAPI 视频任务");
        }
        return submitVideoFull(prompt, imageBytes, imageFilename, imageMime, duration, resolution, null);
    }

    /**
     * 2026-08-11:新增 JSON body + asset_url 引用方式提交。
     *
     * 原理:上游(如火山引擎 doubao-seedance-2.0)对新鲜图片会做敏感检测拒真人,
     *    但 aicoming-proxy 已将资产状态置为 active 表示已通过初步审核。
     *    用 image_urls=["asset://aic_xxx"] 引用 aicoming-proxy 素材时,
     *    NewAPI/上游 跳过敏感检测(走白名单路径)。
     *
     * 上游反馈:"用素材库中的模型可以规避人脸风险"——就是这个机制。
     *
     * @param prompt   视频生成 prompt
     * @param assetUrl aicoming-proxy 的 asset_url(如 asset://aic_srhnfSwDNwSKkqzBc7FiXO)
     * @param duration 时长(秒)
     * @param resolution 分辨率(480p/720p/1080p/4k)
     * @return NewAPI 任务 ID
     */
    public SubmitResult submitVideoByAssetRef(String prompt, String assetUrl,
                                        int duration, String resolution) {
        if (assetUrl == null || assetUrl.isBlank()) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "submitVideoByAssetRef: assetUrl 为空,无法提交");
        }
        // 调用下面多图版本(向后兼容,additionalUrls=null)
        return submitVideoByAssetRefList(prompt, java.util.List.of(assetUrl), duration, resolution);
    }

    /**
     * 2026-08-13 新增:带 fallback 兜底的 asset_url 提交
     *
     * <p>场景:NewAPI 中转站(3000 / 8080)某些 model 配置的 channels 都不支持 asset_url 方式
     * (返回 asset_line_unavailable: "该模型暂不支持虚拟人物素材")。
     * 此时必须改用公网 URL 提交(走普通 image_url=公网URL 路径)。
     * 素材库 thumbnail_url 是 aicoming 审核过的公网 CDN URL,本身已经过审核,
     * 不会触发 PrivacyInformation 二次拦截。</p>
     *
     * <p>实现:
     * 1. 先调 submitVideoByAssetRef 用 asset_url
     * 2. 如果抛 BusinessException 且 message 含 asset_line_unavailable → 自动用 fallbackPublicUrl 重试
     * 3. fallbackPublicUrl 是 aicoming 素材库的 thumbnail_url(公网 CDN,已在素材审核中)</p>
     *
     * @param fallbackPublicUrl 公网可访问的图片 URL(通常 aicoming thumbnail_url),为 null 时不做 fallback
     */
    public SubmitResult submitVideoByAssetRefWithFallback(String prompt, String assetUrl,
                                                          int duration, String resolution,
                                                          String fallbackPublicUrl) {
        try {
            return submitVideoByAssetRef(prompt, assetUrl, duration, resolution);
        } catch (BusinessException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            boolean isAssetLineError = msg.contains("asset_line_unavailable")
                || msg.contains("暂不支持虚拟人物素材");
            if (!isAssetLineError) {
                throw e;  // 其他错误不 fallback,直接抛
            }
            if (fallbackPublicUrl == null || fallbackPublicUrl.isBlank()) {
                log.warn("[NewAPI-FALLBACK] 检测到 asset_line_unavailable 但无 fallbackPublicUrl,直接抛错");
                throw e;
            }
            log.warn("[NewAPI-FALLBACK] NewAPI 不支持 asset_url 方式,自动 fallback 到公网 URL 重试: "
                + "assetUrl={} → fallbackUrl={}", assetUrl, fallbackPublicUrl);
            return submitVideoByPublicUrl(prompt, fallbackPublicUrl, duration, resolution);
        }
    }

    /**
     * 2026-08-13 新增:用公网 URL 提交(非 asset_url 方式)
     *
     * <p>用于 asset_url 被 NewAPI 拒绝(asset_line_unavailable)时的 fallback。
     * 公网 URL 走普通 image_url= 路径,可能触发 PrivacyInformation(但 aicoming 素材库 thumbnail_url
     * 已经过审核,通常不会再被拦)。</p>
     */
    public SubmitResult submitVideoByPublicUrl(String prompt, String publicImageUrl,
                                                int duration, String resolution) {
        if (publicImageUrl == null || publicImageUrl.isBlank()) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "submitVideoByPublicUrl: publicImageUrl 为空");
        }
        if (!publicImageUrl.startsWith("http://") && !publicImageUrl.startsWith("https://")
            && !publicImageUrl.startsWith("asset://")) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "submitVideoByPublicUrl: URL 格式不正确,必须 http/https/asset://, got=" + publicImageUrl);
        }
        log.info("[NewAPI-FALLBACK] → submitVideoByPublicUrl: url={}", publicImageUrl);
        return submitVideoByAssetRefList(prompt, java.util.List.of(publicImageUrl), duration, resolution);
    }

    /**
     * 2026-08-11 新增:多图版 submitVideo。
     * 同时传 image_bytes(主图,multipart)和 image_urls(附加 URL 列表,JSON 字段)。
     *
     * <p>场景:视频节点上游连多个 image 节点(三视图 + 换装帧图 + 其他),主图用 multipart,
     * 其余附加 URL 走 image_urls 字段,NewAPI /v1/videos 原生支持多个参考图。</p>
     */
    public SubmitResult submitVideoByAssetRefList(String prompt, java.util.List<String> imageUrls,
                                                  int duration, String resolution) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "submitVideoByAssetRefList: imageUrls 为空,无法提交");
        }
        Map<String, Object> body = new HashMap<>();
        // 2026-08-13 严格对齐聚融中转 API 接口手册 v2.1 §7(图生视频 I2V):
        //   - image_url 单数(取第一张)
        //   - duration 字符串("4",不是 int — new-api schema 严格校验)
        //   - resolution 顶层(不在 metadata 里),强制小写 p
        if (imageUrls.size() > 1) {
            log.warn("[NewAPI] submitVideoByAssetRefList: 收到 {} 张图,文档只支持 image_url 单数,仅取第一张;后续如需多图请改用 video_urls[] 字段",
                imageUrls.size());
        }
        body.put("model", "doubao-seedance-2.0");
        body.put("prompt", prompt == null ? "" : prompt);
        body.put("image_url", imageUrls.get(0));
        // v2.1 文档明确写 duration/resolution 在顶层(JSON 字段,不是 metadata 嵌套)
        body.put("duration", String.valueOf(duration));
        body.put("resolution", resolution == null ? "480p" : resolution.toLowerCase());

        // 2026-08-13 DEBUG:详细打印 NewAPI 请求前后的所有信息,方便定位卡在哪一步
        long submitStart = System.currentTimeMillis();
        log.info("┌─ [NewAPI-DEBUG] submitVideoByAssetRefList START");
        log.info("│  videoBaseUrl = {}", videoBaseUrl);
        log.info("│  full URL = {}/v1/videos", videoBaseUrl);
        log.info("│  request body = {}", body);
        log.info("│  imageUrls = {}", imageUrls);
        log.info("│  prompt length = {}", prompt == null ? 0 : prompt.length());
        log.info("│  duration = {}s, resolution = {}", duration, resolution);
        log.info("│  token prefix = {}...", token == null ? "null" : token.substring(0, Math.min(10, token.length())));

        JsonNode response = null;
        Exception submitException = null;
        try {
            // 2026-08-13 17:20 根治:aicoming-proxy 8080 收到请求后会立即返回 HTTP 400 + body={status:queued, id:null}
            //   的"占位响应",表示任务已入队但还没拿到真 id。这其实不算真错误,只是异步任务的前置。
            //   修复:用 exchangeToMono,所有 HTTP 状态码都把 body 解析为 JsonNode 返回,
            //         不再让 WebClient 4xx 抛 WebClientResponseException。
            response = webClientBuilder.baseUrl(videoBaseUrl).build()
                .post()
                .uri("/v1/videos")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(clientResponse -> {
                    int status = clientResponse.statusCode().value();
                    if (status >= 200 && status < 300) {
                        return clientResponse.bodyToMono(JsonNode.class);
                    }
                    if (status >= 400) {
                        // 4xx/5xx 也读 body,看 body 是不是 "queued 占位响应" (有 status=queued 但 id=null)
                        return clientResponse.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(bodyStr -> {
                                log.warn("│  ⚠️ [NewAPI-DEBUG] /v1/videos 收到 HTTP {} 但仍解析 body: {}",
                                    status, bodyStr.length() < 500 ? bodyStr : bodyStr.substring(0, 500) + "...");
                                try {
                                    if (bodyStr != null && !bodyStr.isBlank()) {
                                        return new ObjectMapper().readTree(bodyStr);
                                    }
                                } catch (Exception parseEx) {
                                    log.warn("│  [NewAPI-DEBUG] body 不是 JSON: {}", parseEx.getMessage());
                                }
                                // 解析失败:构造一个 Node 表示这次失败
                                return null;
                            });
                    }
                    return clientResponse.bodyToMono(JsonNode.class);
                })
                .timeout(Duration.ofSeconds(600))
                .block();
        } catch (Exception e) {
            submitException = e;
            log.error("│  ❌ [NewAPI-DEBUG] /v1/videos 抛异常: {} ({}ms)", e.getMessage(),
                System.currentTimeMillis() - submitStart);
        }

        long submitElapsed = System.currentTimeMillis() - submitStart;
        log.info("│  [NewAPI-DEBUG] /v1/videos 提交完成,耗时 {}ms", submitElapsed);

        if (submitException != null) {
            log.info("└─ [NewAPI-DEBUG] submitVideoByAssetRefList FAILED (exception)");
            if (submitException instanceof BusinessException) throw (BusinessException) submitException;
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, submitException.getMessage());
        }
        if (response == null) {
            log.info("└─ [NewAPI-DEBUG] submitVideoByAssetRefList FAILED (response null)");
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "NewAPI /v1/videos (asset_url) 返回空响应");
        }
        // 2026-08-13 17:20 检测"queued 占位响应"(id/task_id 是 null 但 status=queued)
        //   这种情况虽然 HTTP 是 4xx,但实际上 aicoming 上游接受了任务,只是 id 还没生成
        //   不应该 markFailed。直接抛"aicoming 还没返回 id"提示调用方走兜底(轮询)
        String respStatus = response.path("status").asText("");
        String respId = response.path("id").asText("");
        String respTaskId = response.path("task_id").asText("");
        if ((respId.isEmpty() && respTaskId.isEmpty())
            && ("queued".equalsIgnoreCase(respStatus) || "pending".equalsIgnoreCase(respStatus))) {
            log.warn("│  ⚠️ [NewAPI-DEBUG] aicoming-proxy 返回 queued 占位响应 (id/task_id=null),"
                + " 表示任务已入队但 id 尚未生成。建议调用方等待并轮询。");
            throw new BusinessException(ErrorCode.NEWAPI_REQUEST_INVALID,
                "aicoming 上游已入队但 id 尚未生成 (status=" + respStatus + "),请稍后重试或查询");
        }
        log.info("│  [NewAPI-DEBUG] 响应: fields=[{}]", collectFieldNames(response));
        log.info("│  [NewAPI-DEBUG] 响应 body(前 1000 字符): {}", response.toString().length() < 1000 ? response.toString() : response.toString().substring(0, 1000) + "...");
        String taskId = response.path("task_id").asText(response.path("id").asText(""));
        // 2026-08-13 18:30 修复:NewAPI 3000 中转站对部分模型(如 doubao-seedance-2.0)返回
        //   {"id":"none", "task_status":"submitted", ...} 占位响应。
        //   "none" 字符串非空,会被误当成真 taskId 存到 job,后续 poll 404。
        //   修复:把 "none" / "null" / 空字符串都视为无效 id。
        if ("none".equalsIgnoreCase(taskId) || "null".equalsIgnoreCase(taskId)) {
            log.warn("│  ⚠️ [NewAPI-DEBUG] 3000 中转站返回占位 taskId='{}',视为无效,"
                + " 可能是模型不被中转站支持(或中转站没真提交到 aicoming)。", taskId);
            taskId = "";
        }
        if (taskId.isEmpty()) {
            log.info("└─ [NewAPI-DEBUG] submitVideoByAssetRefList FAILED (no task_id)");
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "NewAPI /v1/videos (asset_url) 响应缺 id/task_id: " + response);
        }
        // 2026-08-11:顺便提取 url(响应里可能有,避免后续 30 秒 poll 拿不到)
        String directUrl = null;
        JsonNode topUrlNode = response.get("url");
        if (topUrlNode != null && topUrlNode.isTextual() && !topUrlNode.asText().isBlank()) {
            directUrl = topUrlNode.asText();
        } else if (response.path("metadata").path("url").isTextual()) {
            directUrl = response.path("metadata").path("url").asText();
        }
        if (directUrl != null) {
            log.info("│  [NewAPI-DEBUG] 响应已含 video url: {}", directUrl);
        } else {
            log.info("│  [NewAPI-DEBUG] 响应未含 video url,只能等后续 poll");
        }
        log.info("│  [NewAPI-DEBUG] taskId={}, imageUrls={}, duration={}s, resolution={}",
            taskId, imageUrls, duration, resolution);
        log.info("└─ [NewAPI-DEBUG] submitVideoByAssetRefList OK, taskId={}", taskId);
        return new SubmitResult(taskId, directUrl);
    }


    

    /**
     * 2026-08-11:把 NewAPI 上游错误 body 翻译成中文友好提示。
     * 上游(豆包 Seedance / Sora 等)的错误通常嵌在嵌套 JSON 里:
     *   {"code":"fail_to_fetch_task","message":"{\"ErrorCode\":\"...\",\"ErrorMessage\":\"...\"}"}
     * 识别已知错误并翻译,无法识别时回退到 raw message(截断到 500 字防爆)。
     *
     * @param body NewAPI 错误响应原文
     * @return 中文友好提示
     */
    public static String translateNewApiError(String body) {
        if (body == null || body.isBlank()) return "NewAPI 返回空错误响应";
        try {
            // 解析外层
            JsonNode outer = new ObjectMapper().readTree(body);
            // 上游错误信息通常在 outer.message 里(嵌套 JSON 字符串)
            String inner = outer.path("message").asText("");
            String innerErrorCode = "";
            String innerErrorMessage = "";
            if (!inner.isBlank() && inner.startsWith("{")) {
                try {
                    JsonNode innerNode = new ObjectMapper().readTree(inner);
                    innerErrorCode = innerNode.path("ErrorCode").asText("");
                    innerErrorMessage = innerNode.path("ErrorMessage").asText("");
                } catch (Exception ignore) {
                    // inner 不是 JSON,就用原文
                }
            }
            // 兜底:外层的 code
            String outerCode = outer.path("code").asText("");

            // 已知错误映射
            if (innerErrorCode.contains("InputImageSensitiveContentDetected.PrivacyInformation")
                || innerErrorMessage.contains("real person")
                || innerErrorMessage.contains("may contain real person")) {
                return "图片含真人,被 NewAPI 上游(豆包 Seedance 等)拒绝生成。请换用不含真人的换装总图(纯服装/模特身体轮廓图),或在 NewAPI 控制台切换到支持真人换装的模型";
            }
            if (innerErrorCode.contains("InputImageSensitiveContentDetected")
                || innerErrorMessage.contains("sensitive content")
                || innerErrorMessage.contains("sensitive")) {
                return "图片含敏感内容,被 NewAPI 上游拒绝生成。请换用普通商品/服装图,或联系管理员在 NewAPI 控制台配置敏感词白名单";
            }
            if (innerErrorCode.contains("InvalidParameter")
                || outerCode.contains("invalid_parameter")) {
                return "NewAPI 上游参数无效:" + (innerErrorMessage.isBlank() ? "图片或 prompt 不符合要求" : innerErrorMessage);
            }
            if (innerErrorCode.contains("QuotaExceeded") || innerErrorCode.contains("InsufficientQuota")) {
                return "NewAPI 账户额度不足,请联系管理员充值";
            }
            // 2026-08-11 新增:检测 upstream 402 + "insufficient balance" / "402" 等关键字
            // (NewAPI 余额耗尽时返回的格式跟 QuotaExceeded 不同,fallback 抓不到)
            if (body.contains("insufficient balance")
                || body.contains("insufficient_quota")
                || body.contains("PAYMENT_REQUIRED")
                || body.contains("\"code\":402")
                || body.contains("upstream 402")) {
                return "NewAPI 账户余额不足,视频生成被拒(HTTP 402)。请到 NewAPI 后台充值,或联系管理员";
            }
            if (innerErrorCode.contains("RateLimit")) {
                return "NewAPI 请求频率过高,请稍后重试";
            }

            // 兜底:用 innerErrorMessage 或 outer.message,截断到 500 字符
            String fallback = !innerErrorMessage.isBlank() ? innerErrorMessage :
                              !inner.isBlank() ? inner : body;
            if (fallback.length() > 500) fallback = fallback.substring(0, 500) + "...";
            return "NewAPI 上游拒绝:" + fallback;
        } catch (Exception e) {
            // 解析失败,截断 raw body 返回
            String s = body.length() > 500 ? body.substring(0, 500) + "..." : body;
            return "NewAPI 调用失败:" + s;
        }
    }
}
