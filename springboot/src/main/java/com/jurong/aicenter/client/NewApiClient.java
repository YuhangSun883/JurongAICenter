package com.jurong.aicenter.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.dto.video.VideoOptions;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.io.IOException;
import java.net.URI;
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
 *
 * 2026-08-13 FIX:删除 @Slf4j 注释,只保留 L84 手动 log 声明,避免 lombok annotationProcessor
 *   失败时出现 "log 变量重复声明" 编译错误。
 */
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

    // 2026-08-13:用于 editImage 多图图生图 — 把 base64 图片批量上传到 :8090 素材库
    //   拿到 asset_url 后,改用 /v1/images/generations + JSON body(image 字段)
    //   提交图生图。旧版 multipart /v1/images/edits 端点在新版本已下线。
    private final AicomingAssetsClient aicomingAssetsClient;

    public NewApiClient(WebClient.Builder webClientBuilder,
                        AicomingAssetsClient aicomingAssetsClient) {
        this.webClientBuilder = webClientBuilder;
        this.aicomingAssetsClient = aicomingAssetsClient;
    }

    /**
     * 2026-08-13 补:对外暴露 AicomingAssetsClient,方便其他 Service 复用素材上传/轮询等基础能力。
     * (避免在多个调用方重复注入 AicomingAssetsClient)
     */
    public AicomingAssetsClient getAicomingAssetsClientForUpload() {
        return aicomingAssetsClient;
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
     * 2026-08-14 改造:支持多模态(图片)
     *   - userContent 可以是 String(纯文本,旧用法)或 List(Map<String,Object>)(OpenAI 多模态格式)
     *   - 多模态格式:content = [{type:"text", text:"..."}, {type:"image_url", image_url:{url:"..."}}, ...]
     *   - 由调用方负责拼多模态 content 数组(本方法不感知图片,只负责转发)
     *
     * <p>协议:与 chatCompletion 相同,body 多一个 "stream": true;
     *      响应变成 SSE 格式,每行 "data: {...}" 是一个增量,直到 "data: [DONE]" 结束。
     *
     * @param onToken 每收到一段 token 时调用(可能 1-3 个字)
     */
    public void chatCompletionStream(
            String model, String systemPrompt, Object userContent,
            int maxTokens, java.util.function.Consumer<String> onToken) throws IOException {

        // 1) 构造 messages
        //   - system content 仍是 String
        //   - user content 由调用方传,可以是 String 或 List(Map)
        List<Map<String, Object>> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        // 2026-08-14:user content 改 Object(Map.of 不支持 null,改用 HashMap)
        Map<String, Object> userMsg = new HashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userContent == null ? "" : userContent);
        messages.add(userMsg);

        // 2) body 关键差异:stream: true
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.7);
        body.put("stream", true);

        // 3) JDK HttpClient 发请求(项目里 downloadAsDataUri 已有先例 line 955)
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();

        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(180))
                .build();

        try {
            java.net.http.HttpResponse<java.io.InputStream> resp = client.send(
                    request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());

            // 4) 非 200 直接抛
            if (resp.statusCode() != 200) {
                String errBody = new String(resp.body().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                log.error("NewAPI stream failed: {} {}", resp.statusCode(), errBody);
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        "LLM 流式调用失败:" + resp.statusCode());
            }

            // 5) 按行解析 SSE
            // 2026-08-14 临时调试:把 NewAPI 原始 SSE 响应 dump 到日志(前 20 行)
            StringBuilder rawDump = new StringBuilder();
            int dumpLineCount = 0;
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(resp.body(),
                            java.nio.charset.StandardCharsets.UTF_8))) {
                String line;  // 2026-08-14 修复:漏声明导致编译错误
                int chunkIdx = 0;
                while ((line = reader.readLine()) != null) {
                    if (dumpLineCount < 20) {
                        rawDump.append(line).append("\n");
                        dumpLineCount++;
                    }
                    if (!line.startsWith("data: ")) continue;
                    String payload = line.substring(6).trim();
                    if (payload.isEmpty() || "[DONE]".equals(payload)) continue;

                    try {
                        JsonNode node = objectMapper.readTree(payload);
                        JsonNode delta = node.path("choices").path(0).path("delta");
                        String token = delta.path("content").asText("");
                        if (token.isEmpty()) continue;

                        // 2026-08-14 兜底:某些 NewAPI 中转的 bug 会把
                        //   "data: " 前缀塞进 delta.content 字段(应该只在 SSE 行上,不在 content 里)
                        //   实际表现:前端看到 "data: 好 data: 的..." 这种串字符
                        //   而存进数据库的 LLM 真实输出是干净的(刷新页面后看到的是正常故事)
                        //   说明每个 chunk 的 content 都是 "data: <text>" 形式
                        //   - 先 strip 开头的 "data: "(最常见)
                        //   - 再 strip 末尾残留的(防御)
                        //   注:极端情况 LLM 真要输出 "data: " 字面量会被误剥,但概率极低,可接受
                        if (token.startsWith("data: ")) {
                            token = token.substring("data: ".length());
                        } else if (token.startsWith("data:")) {
                            // String.trimStart() 是 Java 21+,JDK 17 不可用
                            // 改用 replaceFirst 去前导空格
                            token = token.substring("data:".length()).replaceFirst("^\\s+", "");
                        }
                        if (token.endsWith("data: ")) {
                            token = token.substring(0, token.length() - "data: ".length());
                        }
                        if (token.endsWith("data:")) {
                            token = token.substring(0, token.length() - "data:".length());
                        }

                        if (token.isEmpty()) continue;

                        // 调试:头 3 个 chunk 打 INFO log,确认 NewAPI 实际返回格式
                        if (chunkIdx < 3) {
                            log.info("[NewAPI-stream] chunk#{} raw='{}' cleaned='{}'",
                                    chunkIdx, line, token);
                            chunkIdx++;
                        }

                        onToken.accept(token);
                    } catch (Exception e) {
                        // 单 chunk 解码失败不致命,继续读
                        log.debug("SSE chunk parse failed: {}", e.getMessage());
                    }
                }
            }
            // 2026-08-14 dump NewAPI 原始 SSE 响应(前 20 行)
            log.info("=== NewAPI raw SSE dump (first {} lines) ===\n{}",
                    dumpLineCount, rawDump);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("NewAPI chatCompletionStream failed: {}", e.getMessage());
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
            // 2026-08-13 FIX:补齐方法体内需要的局部变量
            VideoOptions options = VideoOptions.builder()
                .duration(duration)
                .resolution(resolution)
                .build();
            String useResolution = (resolution != null && !resolution.isBlank())
                ? resolution.toLowerCase() : "480p";
            List<byte[]> imageFiles = (imageBytes != null && imageBytes.length > 0)
                ? List.of(imageBytes) : null;

            MultipartBodyBuilder builder = new MultipartBodyBuilder();
            builder.part("model", "doubao-seedance-2.0");
            builder.part("prompt", prompt);
            builder.part("duration", String.valueOf(options.getDuration()));
            // 2026-08-13 14:25 修复:严格对齐聚融 v2.1 文档 §7(resolution 必须小写 "480p"/"720p"/"1080p")
            //   之前原样转发,如果上游传 "480P" 会被原样发出,aicoming 上游会拒
            builder.part("resolution", useResolution);

            // 主图(如果有 bytes)走 multipart input_reference
            if (imageFiles != null && !imageFiles.isEmpty()) {
                byte[] firstImageBytes = imageFiles.get(0);
                final String fname = "canvas_input.png";
                final String mime = "image/png";
                // 2026-08-13 FIX: 同步发 image/input_reference/image_url 3 个字段名(模仿 Python api_client.submit_video)
                // 原因:aicoming-proxy 8/13 改了期望字段名,只发 input_reference 会被忽略
                ByteArrayResource imgResource1 = new ByteArrayResource(firstImageBytes) {
                    @Override public String getFilename() { return fname; }
                };
                ByteArrayResource imgResource2 = new ByteArrayResource(firstImageBytes) {
                    @Override public String getFilename() { return fname; }
                };
                ByteArrayResource imgResource3 = new ByteArrayResource(firstImageBytes) {
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

            String taskId = response.path("id").asText(response.path("task_id").asText(""));
            if (taskId.isEmpty()) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                    "NewAPI /v1/videos (multi) 响应缺 id/task_id: " + response);
            }
            log.info("NewAPI /v1/videos (multi) task submitted: {} (primary={}B, additionalUrls={})",
                taskId,
                (imageFiles != null && !imageFiles.isEmpty() ? imageFiles.get(0).length : 0),
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
    public SubmitResult submitVideo(String prompt, List<byte[]> imageFiles, VideoOptions options) {
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
            int useDuration = options.getDuration() <= 0 ? 4 : options.getDuration();
            byte[] imageBytes = null;
            if (imageFiles != null) {
                for (byte[] file : imageFiles) {
                    if (file != null && file.length > 0) {
                        imageBytes = file;
                        break;
                    }
                }
            }
            String imageFilename = "input_reference.png";
            String imageMime = "image/png";

            builder.part("model", useModel);
            builder.part("prompt", prompt);
            builder.part("duration", String.valueOf(useDuration));
            // 2026-08-13 14:25 修复:严格对齐聚融 v2.1 文档 §7(resolution 必须小写 "480p"/"720p"/"1080p")
            //   之前原样转发,如果上游传 "480P" 会被原样发出,aicoming 上游会拒
            builder.part("resolution", useResolution);
            // ratio / watermark 濠电姷鏁告慨鐑藉极閸涘﹥鍙忛柣鎴ｆ閺嬩線鏌涘☉姗堟敾闁告瑥绻橀弻锝夊箣閿濆棭妫勯梺鍝勵儎缁舵岸寮婚悢鍏尖拻閻庨潧澹婂Σ顔剧磼閻愵剙鍔ゆい顓犲厴瀵鏁愭径濠勭杸濡炪倖甯婇悞锕傚磿閹剧粯鈷戦柟鑲╁仜婵″ジ鏌涙繝鍌涘仴鐎殿喛顕ч埥澶愬閳哄倹娅囬梻浣瑰缁诲倸螞濞戔懞鍥Ψ瑜忕壕钘壝归敐鍛儓鐏忓繘姊洪崨濠庢畷濠电偛锕ら锝囨嫚濞村顫嶅┑鈽嗗灦閺€閬嶅棘閳ь剟姊绘担鍛婂暈婵炶绠撳畷鎴﹀礋椤掍礁寮块梺闈涚箞閸婃牠鍩涢幋鐐电闁煎ジ顤傞崵娆愵殽閻愭惌娈滈柡宀€鍠栭獮鏍ㄦ媴閾忚姣囬梻浣虹《閺備線宕戦幘鎰佹富闁靛牆妫楃粭鎺楁煕閻樺疇澹樻い顓炴喘楠炲洭顢橀悩娈垮晭闂備礁鎲￠悷銉┧囨潏銊︽珷妞ゅ繐鐗婇崑鍌炴煏閸繍妲归柣鎾卞劦閺岋繝宕堕埡浣风捕婵炲瓨绮嶆竟鍡欐閹炬剚鍚嬮柛鈩冪懃閳峰矂姊洪崫鍕効缂佺粯绻傞悾鐑藉醇閺囩倣銊╂煏婢诡垰鍊诲Λ顖炴⒒閸屾瑨鍏岀紒顕呭灦楠炴劗鎷犵憗浣告惈椤粓鍩€椤掍椒绻嗛柣銏㈩焾缁€瀣亜閺嶃劍鐨戦柣銈傚亾闂傚倷绀侀幉锟犲箰閻戣姤鍤勯柟顖滃閹冲瞼绱撻崒姘偓鎼佸磹妞嬪孩濯奸柡灞诲劚绾惧鏌熼崜褏甯涢柣鎾存礋閺岀喐瀵肩€涙ɑ閿梺鍝勵儑閸犳牠寮婚敐澶婄閻庨潧鎲￠崚娑㈡⒑閸濆嫭婀扮紒瀣灴閳ワ箓濡搁埡浣哄姦濡炪倖甯掗崐濠氭儗閸℃褰掓晲閸偅缍堝┑鐐叉噽婵炩偓闁哄瞼鍠撶槐鎺楀閻樺磭浜堕梻浣虹帛閹稿鎮烽敃鍌毼﹂柛鏇ㄥ灠缁秹鏌嶈閸撶喎顕ｉ崨濠勭瘈婵﹩鍘煎▓宀勬⒑缁夊棗瀚峰▓鏇㈡煟閹惧鎳勯柕鍥у瀵噣宕掑☉娆戝涧闂備胶鎳撻崯鍨洪銏犺摕闁绘柨鍚嬮幆鐐淬亜閹扳晛鈧鎮￠埀顒勬⒒娴ｅ摜锛嶇紒顕呭灦楠炴垿宕堕鍌氱ウ闂佸綊鍋婇崢浠嬪磿閻旀悶浜滈柡鍐ㄥ€婚幗鍌涗繆椤愩垹顏╅柍瑙勫灴閹晠宕归锝嗙槑濠电姵顔栭崰姘跺礂濮椻偓婵℃挳宕掗悙鏉戠檮婵犮垹鍘滈弲顏嗙礊娴ｅ摜鏆﹂柕濞炬櫅缁狙囨煙鐎电顎撶紒閬嶄憾濮婄粯鎷呴崨濠傛殘缂備礁顑嗛崹鍧楀箖濞差亜惟闁宠桨鑳堕弻褍鈹戦悩缁樻锭妞ゆ垵妫濋幃陇绠涘☉姘絼闂佹悶鍎滅仦钘夊闂備線鈧偛鑻晶顖涚箾閼碱剙鏋涙鐐茬箻楠炲鏁傞挊澶夌盎闂備胶顭堢换妤呭磻閹版澘鍌ㄦい蹇撶墛閳锋垿鏌涢幘鏉戠祷濞存粍绻勭槐鎺旀嫚閼碱儷銏ゅ础闁秵鐓曟繝闈涘閸斻倗鐥幆褋鍋㈤柡宀嬬到閳诲酣骞囬钘夋珣婵犵數鍋犻婊呯不閹捐绠栭柨鐔哄Т閸楁娊鏌ｉ弮鍌滅瘈缂併劏顕ч—鍐Χ閸℃ê鏆楅梺鍝ュТ闁帮綁骞冨鈧俊鐑藉煛閸屾粌骞愰梺璇插嚱缂嶅棝宕滃▎鎾冲嚑闁瑰濮风壕鑲╃磽娴ｈ鐒芥繛鎻掝嚟閳ь剝顫夊ú鏍Χ閹间礁绠栭柕蹇嬪€曠粻褰掓煟閹邦厼顎滄俊鍓ь焾閳规垿鎮╅幇浣告櫛闂佸摜濮甸悧鐘诲极閸愵喖惟闁靛鍨洪悗娲⒑閹稿海绠撴繛灞傚€濆畷鐟扳攽閸モ晝顔曢梺绯曞墲閿氶柣蹇ュ閳ь剝顫夊ú鏍囬悽绋胯摕闁哄洨鍠撶粻鍓ф喐瀹ュ鍤愭い鏍仜閺嬩線鏌ｉ幘宕囧哺闁衡偓娴犲鐓ユ繛鎴灻鈺伱瑰鍐﹀仮闁哄本绋掔换娑㈠垂椤旂懓浜炬繝闈涙閺嗭箓鏌曡箛瀣偓鏍磻閸屾侗娈介柣鎰版涧閺嬫垶淇婇悙鎵煓闁靛棔绀侀～婊堝焵椤掍焦鍙忛柍褜鍓熼弻鏇＄疀閺囩倫銉╂煏閸剛鐣垫慨濠勭帛閹峰懏绗熼娑欐殲闂備浇顫夊鎸庣閻愰潧鍨濆┑鐘宠壘缁狅綁鏌ｅΟ鍏兼毄闁绘帒銈搁弻锝嗘償椤栨粎校闂佺顑勯悞锔剧矉瀹ュ拋鐓ラ柛顐ゅ枔閸樻悂鎮楅獮鍨姎闁哥噥鍋呮穱濠冪鐎ｎ偆鍘介梺闈涱煭缁犳垿鎮橀敃鍌涚厪闁搞儜鍐句純濡ょ姷鍋為…鍥焵椤掍胶鈯曢懣褍霉閻橆喖鐏╅柍瑙勫灴椤㈡瑧娑甸柨瀣毎婵犵绱曢崑妯煎垝濞嗘挻鍋樻い鏇楀亾妤犵偛娲、姗€鎮㈠畡鏉课ら梻鍌欑閸熷潡鎮橀崼銉ョ柧婵犲﹤鎳夐崑鎾愁潩椤愩倗鐓撳┑顔硷功缁垶骞忛崨顔剧懝妞ゆ牗绋掗弳鐐寸節閻㈤潧浠滈柟鍐茬箰鐓ら柣鏃囧亹瀹撲線鏌熼幍顔碱暭闁搞倖甯￠弻鏇㈠醇濠靛洤绐涢梺缁樺笒濞硷繝骞冨Δ鍛祦闁割煈鍠栨慨搴☆渻閵堝繒绱伴柛妤€鍟块悾鐑藉箛閻楀牏鍙嗛柣搴祷閸斿鑺辨繝姘拺闁荤喓澧楅幆鍫㈢磼婢跺﹦鍩ｉ挊婵嬫煥閺冨牊鏆滈柛瀣尭閳绘捇宕归鐣屼邯闂備浇顕х换鎴犳崲閸儱鏄ラ柣鎰惈缁狅綁鏌ㄩ弴妤€浜鹃梺缁樻惈缁绘繈寮诲☉銏犵労闁告劗鍋撻悾鍏肩箾鐎电袥闁哄懏鐩崺鐐哄箣閿旇棄鈧兘鏌ｉ幇顒€甯ㄩ柛瀣尵閳ь剨缍嗛崜姘暦閸欏绡€闂傚牊绋掗ˉ鐘绘煛閸☆參妾柕鍥у楠炲洭濡搁敃鈧妯衡攽閻愬弶鈻曞ù婊冪埣瀵偊宕掗悙瀵稿幈濠电偞鍨靛畷顒勬倶閻樻剚娈?Python api_client.py

            if (imageFiles != null && !imageFiles.isEmpty()) {
                byte[] inputImageBytes = imageFiles.get(0);
                final String fname = "canvas_input.png";
                final String mime = "image/png";
                builder.part("input_reference",
                    new ByteArrayResource(inputImageBytes) {
                        @Override
                        public String getFilename() { return fname; }
                    },
                    MediaType.parseMediaType(mime));
            } else {
                // else: imageFiles is null or empty, use placeholder PNGs
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
            // 2026-08-14 FIX:按 v3.0 接口手册"用 id 字段轮询,不要用 task_id"修正:
            //   id 是 NewAPI 中转站暴露给客户端的"请求 id",在 GET /v1/videos/{id} 时中转站做 ID 翻译,
            //   同一个 token 创建 + 同一个 token 轮询时,**id 字段是中转站自身持久化的**,
            //   而 task_id 是 aicoming 上游内部字段(中转站不暴露给客户端),用 task_id 轮询会 400 not_found。
            String taskId = response.path("id").asText(response.path("task_id").asText(""));
            if (taskId.isEmpty()) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                    "NewAPI /v1/videos 响应格式错误, 缺少 id/task_id:" + response);
            }
            log.info("NewAPI video task submitted: {} (image={}, size={}B, duration={}s, resolution={})",
                taskId,
                (imageFiles != null && !imageFiles.isEmpty()) ? "ref_0" : "placeholder",
                (imageFiles != null && !imageFiles.isEmpty()) ? imageFiles.get(0).length : 0,
                useDuration, useResolution);
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
        SubmitResult r = submitVideo(prompt, null, options);
        return r.taskId();
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
        SubmitResult r = submitVideo(prompt, valid, options);
        return r.taskId();
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
     * 2026-08-14 补回:下载图片 URL 并转为 base64 data URI,
     * 应对 NewAPI 中转服务器无法访问公网 URL 的情况(chatCompletionWithImages 用)。
     *
     * <p>如果 URL 已经是 data URI,直接返回;如果是 http(s),下载后 base64 编码。</p>
     *
     * @param url 图片 URL(可以是 data URI 或公网 URL)
     * @return data URI 形式的字符串(若失败则返回原 URL 让调用方 fallback)
     */
    // 2026-08-14:从 private 改 public,让 AgentServiceImpl.sendStream 复用(支持流式多模态)
    public String downloadAsDataUri(String url) {
        if (url == null || url.isBlank()) return url;
        if (url.startsWith("data:")) return url;  // 已经是 data URI,直接返回
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return url;  // 未知 scheme,原样返回(让调用方处理)
        }
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header(HttpHeaders.USER_AGENT, "JurongAICenter/1.0")
                .GET()
                .build();
            java.net.http.HttpResponse<byte[]> resp = client.send(req,
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300 || resp.body() == null) {
                log.warn("[downloadAsDataUri] 下载失败: status={}, url={}", resp.statusCode(), url);
                return url;
            }
            String contentType = resp.headers().firstValue("Content-Type").orElse("image/png");
            String b64 = Base64.getEncoder().encodeToString(resp.body());
            String dataUri = "data:" + contentType + ";base64," + b64;
            log.debug("[downloadAsDataUri] 下载成功: url={}, bytes={}, mime={}",
                url, resp.body().length, contentType);
            return dataUri;
        } catch (Exception e) {
            log.warn("[downloadAsDataUri] 异常: url={}, err={}", url, e.getMessage());
            return url;  // 失败时原样返回,让调用方 fallback
        }
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
     * 2026-08-13 FIX:多图生图 — 把所有参考图上传到 :8090/v1/assets,拿到 asset_url 数组,
     * 提交 /v1/images/generations 用 {@code images: [...]} 数组字段。
     *
     * <p>依据 2026-08-13 18:38 实测:聚融中转站 /v1/images/generations 实际支持以下字段:
     * <pre>
     *   "images": ["asset://aic_人物id", "asset://aic_衣服id"]  # 推荐(数组)
     *   "image":  ["url1","url2"]                                # 也支持(数组)
     *   "image":  "url"                                          # 单图(降级)
     * </pre>
     * 实测 2 张 asset:// 图成功生成(上游 200, 2.2 MB 图片),耗时约 60s。
     * client timeout 必须 ≥ 120s(实测 i2i 生成约 60s)。
     *
     * <p>流程:
     *   (1) 遍历 referenceImages,每张 base64 → uploadAssetByMultipart → pollUntilActive
     *   (2) POST /v1/images/generations body={model, prompt, images:[asset_url...], n:1}
     *
     * @param prompt          生成提示词(描述清楚每张参考图的角色: "图1穿图2的衣服...")
     * @param referenceImages 引用图片列表(base64 data URI),至少 1 张;上游图应放第一张
     * @param size            图片尺寸
     * @param quality         图片质量
     * @param style           图片风格(中转站暂不识别, 仅留接口签名)
     * @return 生成的图片(URL)
     */
    public String editImage(String prompt, List<String> referenceImages,
                            String size, String quality, String style) {
        if (referenceImages == null || referenceImages.isEmpty()) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "editImage: referenceImages 为空");
        }

        // 1) 把所有参考图(base64)上传到 :8090 素材库,拿到 asset_url 数组
        //    2026-08-14 FIX:支持失败回滚(上传成功的 asset_id 在异常时全部删除)+ 1 次重试,
        //    解决 aicoming 素材库配额满(403 asset_quota_exceeded)导致多图换装失败的 bug。
        java.util.List<String> assetUrls = new java.util.ArrayList<>(referenceImages.size());
        java.util.List<String> uploadedAssetIds = new java.util.ArrayList<>(referenceImages.size());
        BusinessException lastException = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            assetUrls.clear();
            uploadedAssetIds.clear();
            boolean allOk = true;
            for (int idx = 0; idx < referenceImages.size(); idx++) {
                String dataUri = referenceImages.get(idx);
                byte[] bytes;
                String mime;
                try {
                    bytes = decodeDataUri(dataUri);
                    mime = getMimeTypeFromDataUri(dataUri);
                } catch (Exception e) {
                    allOk = false;
                    lastException = new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                        "editImage: 解码参考图 #" + (idx + 1) + " 失败: " + e.getMessage());
                    break;
                }
                String ext = (mime != null && mime.contains("jpeg")) ? ".jpg" : ".png";
                String filename = "edit_" + idx + "_" + System.currentTimeMillis() + ext;
                try {
                    JsonNode raw = aicomingAssetsClient.uploadAssetByMultipart(
                        bytes, filename, mime, "edit-image-input");
                    String assetId = raw.path("id").asText("");
                    if (assetId.isEmpty()) {
                        allOk = false;
                        lastException = new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                            "editImage: 参考图 #" + (idx + 1) + " 上传未返回 id: " + raw);
                        break;
                    }
                    // 等素材入库到 active 状态(否则下游 image=asset://xxx 会被中转站 400)
                    JsonNode active = aicomingAssetsClient.pollUntilActive(assetId, 60, 3);
                    String assetUrl = active.path("asset_url").asText("");
                    if (assetUrl.isBlank()) {
                        allOk = false;
                        lastException = new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                            "editImage: 参考图 #" + (idx + 1) + " 入库后未返回 asset_url: " + active);
                        break;
                    }
                    assetUrls.add(assetUrl);
                    uploadedAssetIds.add(assetId);
                    log.info("editImage: 参考图 #{} 入库: asset_url={}", idx + 1, assetUrl);
                } catch (BusinessException e) {
                    allOk = false;
                    lastException = e;
                    log.error("editImage: 上传参考图 #{} 到素材库失败 (attempt {}/2): {}",
                        idx + 1, attempt, e.getMessage());
                    break;
                }
            }
            if (allOk) {
                // 全部上传成功,跳出重试循环
                break;
            }
            // 上传失败,回滚本轮已上传成功的素材(避免占配额)
            for (String aid : uploadedAssetIds) {
                try {
                    boolean deleted = aicomingAssetsClient.deleteAsset(aid);
                    log.info("editImage: 回滚删除素材: id={}, deleted={}", aid, deleted);
                } catch (Exception ignore) {
                    log.warn("editImage: 回滚删除素材失败: id={}, err={}", aid, ignore.getMessage());
                }
            }
            if (attempt == 2) {
                // 第 2 次仍失败,抛出最后一次异常
                log.error("editImage: 上传参考图失败,已重试 2 次,放弃");
                throw lastException != null
                    ? lastException
                    : new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "editImage: 素材上传失败");
            }
            // 第 1 次失败,稍等 2s 让服务端处理配额/限流,再重试
            log.warn("editImage: 第 1 次上传失败,等待 2s 后重试 (剩余配额大概率已回滚)");
            try {
                Thread.sleep(2000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "editImage: 重试被中断");
            }
        }

        // 2) 构造 /v1/images/generations JSON body
        //    2026-08-14 v5:按 v3.0 接口手册 §5.4 多图输入格式"实测过"的规范:
        //      - image: [...] JSON 数组(单字段多图,中转站自动翻译成 images: [{image_url:...}])
        //      - 单图场景:image 单字符串(降级兼容)
        //      - prompt 用"图1是主图,图2是参考图"自然语言引用
        //    08-13 18:38 实测:2 张 asset:// 图成功生成(2.2MB),client timeout ≥ 120s
        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-image-2-1k");
        body.put("prompt", prompt == null ? "" : prompt);
        // 多图场景用 image: [...] 数组(单字段多图,不是 images: [...])
        if (assetUrls.size() == 1) {
            body.put("image", assetUrls.get(0));
        } else {
            body.put("image", assetUrls);  // ★ v3.0 §5.4:用 image 字段+数组,中转站自动翻译
        }
        body.put("n", 1);
        body.put("size", (size != null && !size.isBlank()) ? size : "1024x1024");
        if (quality != null && !quality.isBlank()) {
            body.put("quality", quality);
        }
        // style 字段中转站不识别, 暂不传

        log.info("NewAPI /v1/images/generations: promptLen={}, refCount={}, size={}",
            prompt == null ? 0 : prompt.length(), assetUrls.size(), body.get("size"));

        // 3) POST /v1/images/generations (application/json)
        //    2026-08-13 FIX:实测 i2i 生成约 60s,client timeout 必须 ≥ 120s,
        //    这里设 180s 留余量。
        try {
            // 显式设 connect/read timeout,默认 RestTemplate 用 JRE URLConnection 无超时
            org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(30_000);   // 30s 建连超时
            factory.setReadTimeout(180_000);     // 180s 读超时(i2i 实测 ~60s)
            RestTemplate imgRestTemplate = new RestTemplate(factory);
            imgRestTemplate.getMessageConverters().stream()
                .filter(c -> c instanceof org.springframework.http.converter.StringHttpMessageConverter)
                .findFirst()
                .ifPresent(c -> ((org.springframework.http.converter.StringHttpMessageConverter) c)
                    .setDefaultCharset(java.nio.charset.StandardCharsets.UTF_8));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + token);

            HttpEntity<String> requestEntity = new HttpEntity<>(
                objectMapper.writeValueAsString(body), headers);

            ResponseEntity<String> respEntity = imgRestTemplate.exchange(
                baseUrl + "/v1/images/generations",
                HttpMethod.POST,
                requestEntity,
                String.class
            );

            String respBody = respEntity.getBody();
            if (respBody == null || respBody.isEmpty()) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "Image edit returned empty body");
            }

            JsonNode response = objectMapper.readTree(respBody);

            if (response == null) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "图片编辑返回为空(响应为 null)");
            }

            java.util.List<String> topFields = new java.util.ArrayList<>();
            response.fieldNames().forEachRemaining(topFields::add);
            log.info("NewAPI editImage 响应字段: {}", topFields);

            // 4) 解析响应(data[0].url / b64_json / error)
            if (!response.has("data") || !response.get("data").isArray()
                || response.get("data").size() == 0) {
                String errMsg = response.has("error") ? response.get("error").toString() : "未知错误";
                log.error("NewAPI editImage 返回错误: {}, 完整响应: {}", errMsg, response);
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "NewAPI 返回错误: " + errMsg);
            }

            JsonNode first = response.get("data").get(0);
            java.util.List<String> firstFieldNames = new java.util.ArrayList<>();
            first.fieldNames().forEachRemaining(firstFieldNames::add);
            log.info("NewAPI editImage data[0] 字段: {}", firstFieldNames);

            // 优先 b64_json
            if (first.has("b64_json") && first.get("b64_json").isTextual()) {
                String b64Data = first.get("b64_json").asText();
                log.info("NewAPI editImage OK (b64_json): b64Len={}", b64Data.length());
                return "data:image/png;base64," + b64Data;
            }
            // 其次 url
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
            if (e instanceof BusinessException be) throw be;
            if (e instanceof org.springframework.web.client.HttpClientErrorException he) {
                log.error("NewAPI /v1/images/generations failed: {} body={}",
                    he.getStatusCode(), he.getResponseBodyAsString());
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                    "NewAPI 调用失败:" + he.getStatusCode() + " " + he.getStatusText());
            }
            log.error("NewAPI editImage failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "NewAPI 调用失败:" + e.getMessage());
        }
    }

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
        // 2026-08-14 FIX:按 v3.0 接口手册"用 id 字段轮询,不要用 task_id"修正。
        //   之前 task_id 优先导致存到 job.comfyuiPromptId 的值,后续 @Scheduled 调
        //   pollVideo(task_id) 拿到 400 task_not_exist → markFailed → 视频生成失败。
        //   改:优先取 id 字段(task_id 仅作为兜底)。
        String taskId = response.path("id").asText(response.path("task_id").asText(""));
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

    // ========================================================================
    // 2026-08-15 新增:画质增强 — 严格按 v2v 视频生视频开发参考 §三
    //   POST /v1/videos, JSON body, references[] 传视频 URL
    //   doubao-seedance-2.0 接受 video reference 后会基于原视频重新生成
    //   一段更清晰的视频(模型本身是生成模型,实际效果是"重生成 + 提升"，
    //   不是真正的超分,但 NewAPI 中转站目前没有专用超分端点)。
    // ========================================================================

    /**
     * 画质增强 — 提交视频画质增强任务
     *
     * <p>按 v2v 文档 §三 references[] 协议:
     * <pre>
     * {
     *   "model": "doubao-seedance-2.0",
     *   "prompt": "...",
     *   "references": [{"media_type": "video", "url": "https://..."}],
     *   "resolution": "1080p",
     *   "seconds": "4",
     *   "audio": true   // 仅 "1080P · 原画 · 有声" 传 true,其余 false
     * }
     * </pre>
     *
     * @param videoUrl    源视频公网 URL(必须是 aicoming 内部或公网可 GET 的 URL,
     *                    文档 §五:参考 URL 用签名临时链接会因排队期 URL 过期导致
     *                    "400 参考图不可访问")
     * @param version     "标准版" / "专业版"
     * @param setting     "1080P · AIGC · 无" / "720P · AIGC · 无" / "1080P · 原画 · 有声"
     *                    映射规则:
     *                    <ul>
     *                      <li>setting 以 "720P" 开头 → resolution=720p, audio=false</li>
     *                      <li>setting 含 "原画"      → resolution=1080p, audio=true (保留原音)</li>
     *                      <li>其他 (默认)            → resolution=1080p, audio=false</li>
     *                    </ul>
     * @return SubmitResult(taskId, url)
     */
    public SubmitResult submitEnhanceVideo(String videoUrl, String version, String setting) {
        if (videoUrl == null || videoUrl.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "videoUrl 不能为空");
        }
        if (!videoUrl.startsWith("http://") && !videoUrl.startsWith("https://")
            && !videoUrl.startsWith("asset://")) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "videoUrl 必须是 http/https/asset://, got=" + videoUrl);
        }

        // 1. 根据 setting 解析 resolution + audio 标志
        String resolution;
        boolean preserveAudio;
        if (setting != null && setting.startsWith("720P")) {
            resolution = "720p";
            preserveAudio = false;
        } else if (setting != null && setting.contains("原画")) {
            resolution = "1080p";
            preserveAudio = true;
        } else {
            // 默认: 1080P · AIGC · 无
            resolution = "1080p";
            preserveAudio = false;
        }

        // 2. 构造 prompt
        StringBuilder promptSb = new StringBuilder();
        promptSb.append("视频画质增强任务。请把这段参考视频的画面质量提升至 ").append(resolution)
            .append(" 高清,保持原视频的人物动作、场景构图、镜头运动、风格色调和背景音乐。");
        if ("专业版".equals(version)) {
            promptSb.append("增强细节:锐化边缘、消除压缩噪点、修复低光照区域、提升人物皮肤质感、")
                .append("增强纹理细节(头发、布料、植被),保持画面自然不浮夸。");
        } else {
            promptSb.append("在保持原内容的基础上提升整体清晰度。");
        }
        if (preserveAudio) {
            promptSb.append("保留原视频的音轨(包括人声、配乐、环境音)。");
        }
        // 英文补充,提升模型理解
        promptSb.append(" Ultra HD ").append(resolution)
            .append(" video upscaling and quality enhancement. ")
            .append("Preserve the original motion, composition, characters, and visual style. ")
            .append("Reference video: @视频 1");
        String prompt = promptSb.toString();

        // 3. 构造 body — 严格按 v2v 文档 §三
        Map<String, Object> body = new HashMap<>();
        body.put("model", "doubao-seedance-2.0");
        body.put("prompt", prompt);
        Map<String, Object> reference = new HashMap<>();
        reference.put("media_type", "video");
        reference.put("url", videoUrl);
        body.put("references", java.util.List.of(reference));
        body.put("resolution", resolution);
        // 文档 §二:seconds 必须是字符串 "4",不能是整数
        body.put("seconds", "4");
        // 文档 §二:audio 控制输出是否带音频
        //   "1080P · AIGC · 无"     → audio=false (无音轨)
        //   "720P  · AIGC · 无"     → audio=false
        //   "1080P · 原画 · 有声"  → audio=true  (保留参考视频原音)
        // 仅 doubao-seedance-2.0 支持,fast 模型不带音频
        body.put("audio", preserveAudio);

        log.info("[Enhancer] submitEnhanceVideo START: videoUrl={}, version={}, setting={}, "
                + "resolution={}, preserveAudio={}",
            videoUrl, version, setting, resolution, preserveAudio);

        long submitStart = System.currentTimeMillis();
        JsonNode response = null;
        Exception submitException = null;
        try {
            // 复用 submitVideoByAssetRefList 已验证的 exchangeToMono 模式
            // 4xx 也能解析 body,识别"queued 占位响应"
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
                        return clientResponse.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .map(bodyStr -> {
                                log.warn("[Enhancer] /v1/videos HTTP {} body={}",
                                    status, bodyStr.length() < 500 ? bodyStr : bodyStr.substring(0, 500) + "...");
                                try {
                                    if (bodyStr != null && !bodyStr.isBlank()) {
                                        return new ObjectMapper().readTree(bodyStr);
                                    }
                                } catch (Exception parseEx) {
                                    log.warn("[Enhancer] body 不是 JSON: {}", parseEx.getMessage());
                                }
                                return null;
                            });
                    }
                    return clientResponse.bodyToMono(JsonNode.class);
                })
                .timeout(Duration.ofSeconds(600))
                .block();
        } catch (Exception e) {
            submitException = e;
            log.error("[Enhancer] submitEnhanceVideo 异常: {} ({}ms)", e.getMessage(),
                System.currentTimeMillis() - submitStart);
        }

        if (submitException != null) {
            if (submitException instanceof BusinessException) throw (BusinessException) submitException;
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, submitException.getMessage());
        }
        if (response == null) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "NewAPI /v1/videos (enhance) 返回空响应");
        }

        // 检测 queued 占位响应
        String respStatus = response.path("status").asText("");
        String respId = response.path("id").asText("");
        String respTaskId = response.path("task_id").asText("");
        if ((respId.isEmpty() && respTaskId.isEmpty())
            && ("queued".equalsIgnoreCase(respStatus) || "pending".equalsIgnoreCase(respStatus))) {
            log.warn("[Enhancer] aicoming-proxy 返回 queued 占位响应 (id/task_id=null)");
            throw new BusinessException(ErrorCode.NEWAPI_REQUEST_INVALID,
                "aicoming 上游已入队但 id 尚未生成 (status=" + respStatus + "),请稍后重试或查询");
        }

        // 提取 task_id (优先 id 字段,与 submitVideoByAssetRefList 保持一致)
        String taskId = response.path("id").asText(response.path("task_id").asText(""));
        if ("none".equalsIgnoreCase(taskId) || "null".equalsIgnoreCase(taskId)) {
            log.warn("[Enhancer] aicoming-proxy 返回占位 taskId='{}'", taskId);
            taskId = "";
        }
        if (taskId.isEmpty()) {
            log.error("[Enhancer] /v1/videos 响应缺少 id/task_id: {}", response);
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "NewAPI /v1/videos (enhance) 响应格式错误,缺少 id/task_id");
        }

        // 尝试从响应里直接拿 url(同步返回场景)
        String directUrl = null;
        JsonNode topUrlNode = response.get("url");
        if (topUrlNode != null && topUrlNode.isTextual() && !topUrlNode.asText().isBlank()) {
            directUrl = topUrlNode.asText();
        } else if (response.path("metadata").path("url").isTextual()) {
            directUrl = response.path("metadata").path("url").asText();
        }

        log.info("[Enhancer] submitEnhanceVideo 成功: taskId={}, directUrl={}, 耗时 {}ms",
            taskId, directUrl, System.currentTimeMillis() - submitStart);
        return new SubmitResult(taskId, directUrl);
    }
}
