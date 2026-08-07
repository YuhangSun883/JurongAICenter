package com.jurong.aicenter.service.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.client.ComfyUIClient;
import com.jurong.aicenter.client.NewApiClient;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 画布 AI 生成服务真实实现。
 *
 * 三个能力的调用路径：
 *   - polishText    : NewApiClient.chatCompletion()（直接 HTTP 调 NewAPI）
 *   - generateImage : ComfyUIClient.submitWorkflow() + polling history + StorageService.uploadFile()
 *   - generateVideo : ComfyUIClient.submitWorkflow() + polling history + 提取 ui.newapi_task_id
 *                     → NewApiClient.waitForVideo() + download → StorageService.uploadFile()
 *
 * 严禁 mock / 占位 / placeholder URL。所有产物必须是真实 API 调用结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealCanvasAiService implements CanvasAiService {

    private final NewApiClient newApiClient;
    private final ComfyUIClient comfyUIClient;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    @Value("${llm.model:deepseek-v4-flash}")
    private String llmModel;

    @Value("${llm.max-tokens:2048}")
    private int llmMaxTokens;

    @Value("${llm.system-prompt:你是一个专业的文案润色师。请保持核心信息和意图不变，改善表达使文字更有感染力，适合用于电商详情页、社交媒体等场景。输出纯文本。}")
    private String llmSystemPrompt;

    @Value("${llm.image-poll-timeout-sec:600}")
    private int imagePollTimeoutSec;

    @Value("${llm.video-poll-timeout-sec:1200}")
    private int videoPollTimeoutSec;

    /** workflows 目录路径（jar 同级或 classpath:/workflows/） */
    @Value("${canvas.workflows-dir:classpath:/workflows/}")
    private String workflowsDir;

    /** 视频上传到 MinIO 后的 object key 前缀 */
    private static final String STORAGE_PREFIX = "canvas";

    // ============= 文本润色（真 LLM 调用） =============

    @Override
    public String polishText(String userPrompt, String upstreamContent) {
        // 拼接最终输入：上游上下文（若有）+ 用户原始输入
        String finalInput = (upstreamContent != null && !upstreamContent.isBlank())
            ? "【上游节点输出】\n" + upstreamContent + "\n\n【用户原始输入】\n" + userPrompt
            : userPrompt;

        String polished = newApiClient.chatCompletion(
            llmModel,
            llmSystemPrompt,
            finalInput,
            llmMaxTokens
        );
        log.info("Canvas text polished: inputLen={}, outputLen={}", finalInput.length(), polished.length());
        return polished;
    }

    /**
     * 智能合并：用户输入为主，上游文案作为风格参考；冲突时以用户为准。
     *
     * <p>当 upstreamContent 为空时，直接返回 userPrompt（不需要 LLM 调用），
     * 避免不必要的延迟和费用。</p>
     */
    @Override
    public String mergePrompts(String userPrompt, String upstreamContent) {
        // 安全校验
        if (userPrompt == null) userPrompt = "";
        if (upstreamContent == null) upstreamContent = "";

        String trimmedUser = userPrompt.trim();
        String trimmedUp = upstreamContent.trim();

        // 没上游或上游为空 → 直接用用户输入
        if (trimmedUp.isEmpty()) {
            log.info("Canvas mergePrompts: no upstream, use userPrompt directly (len={})", trimmedUser.length());
            return userPrompt;
        }

        // 用户没输入 → 用上游文案
        if (trimmedUser.isEmpty()) {
            log.info("Canvas mergePrompts: no userPrompt, use upstreamContent directly (len={})", trimmedUp.length());
            return upstreamContent;
        }

        // 两者都有 → LLM 智能合并
        String mergeSystemPrompt = "你是 AI 创作提示词工程师。请基于【用户输入】（主）和【上游润色文案】（参考），生成适合视频/图片生成的最终详细描述。\n\n" +
            "规则：\n" +
            "1. **以用户输入为主**：用户指定的具体细节（颜色、动作、天气、场景、角色特征等）优先级最高\n" +
            "2. **借鉴上游文案的氛围与风格**：光线、镜头、质感、节奏、整体调性\n" +
            "3. **解决冲突**：如果两者矛盾（如天气、颜色、场景、动作、风格），以用户输入为准，上游文案仅取其风格描写\n" +
            "4. **输出要求**：包含主体动作、镜头运动、光影变化、氛围细节；用中文；纯文本不用 markdown；100-200 字\n\n" +
            "请直接输出最终描述，不要任何解释、前缀或包装。";

        String mergeUserPrompt = "【用户输入】\n" + userPrompt + "\n\n【上游润色文案】\n" + upstreamContent;

        String merged = newApiClient.chatCompletion(
            llmModel,
            mergeSystemPrompt,
            mergeUserPrompt,
            llmMaxTokens
        );
        log.info("Canvas mergePrompts: userLen={}, upstreamLen={}, mergedLen={}",
            userPrompt.length(), upstreamContent.length(), merged.length());
        return merged;
    }

    // ============= 图片生成（真 ComfyUI 调用） =============

    @Override
    public String generateImage(String prompt, String upstreamContent) {
        // 1. 读 workflow JSON 模板
        String template = readWorkflowTemplate("01-product-photo.json");

        // 2. 智能合并：用户输入为主，上游文案作为风格参考；冲突时以用户为准
        String finalPrompt = mergePrompts(prompt, upstreamContent);
        String workflowJson = template.replace("{{prompt}}", escapeJson(finalPrompt));

        // 3. 提交 ComfyUI
        JsonNode workflow;
        try {
            workflow = objectMapper.readTree(workflowJson);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "workflow JSON 解析失败: " + e.getMessage());
        }

        String promptId = comfyUIClient.submit(workflow);
        log.info("Canvas image workflow submitted: promptId={}", promptId);

        // 4. 轮询 history 直到完成
        JsonNode history = pollUntilDone(promptId, imagePollTimeoutSec);

        // 5. 提取 outputs["2"].images[]（SaveImage 节点的输出）
        JsonNode entry = history.get(promptId);
        JsonNode outputs = entry.get("outputs");
        if (outputs == null || !outputs.has("2")) {
            throw new BusinessException(ErrorCode.COMFYUI_REJECTED, "ComfyUI 未返回 SaveImage 节点 outputs");
        }
        JsonNode saveNode = outputs.get("2");
        JsonNode images = saveNode.get("images");
        if (images == null || !images.isArray() || images.size() == 0) {
            throw new BusinessException(ErrorCode.COMFYUI_REJECTED, "SaveImage 节点 outputs.images 为空");
        }

        // 6. 下载 + 上传 MinIO
        JsonNode firstImage = images.get(0);
        String filename = firstImage.path("filename").asText("");
        String subfolder = firstImage.path("subfolder").asText("");
        String type = firstImage.path("type").asText("output");
        if (filename.isEmpty()) {
            throw new BusinessException(ErrorCode.COMFYUI_REJECTED, "SaveImage 输出无 filename");
        }

        String url = downloadAndUpload(promptId, filename, subfolder, type, "image", "image");
        log.info("Canvas image generated: promptId={}, url={}", promptId, url);
        return url;
    }

    // ============= 视频生成（真 ComfyUI + 真 NewAPI 调用） =============

    @Override
    public String generateVideo(String prompt, String imageUrl, String upstreamContent) {
        // 根据 imageUrl 是否为空选择 workflow：空则走纯文本 video，不空则走 image-to-video
        boolean isTextToVideo = (imageUrl == null || imageUrl.isBlank());
        String template;
        String nodeOutputKey;
        String imageFilename = null;

        if (isTextToVideo) {
            // 纯文本生成视频：workflow 02 (JurongTextToVideoV2)，输出节点是 "1"
            template = readWorkflowTemplate("02-text-to-video.json");
            nodeOutputKey = "1";
        } else {
            // 图片转视频：workflow 03 (LoadImage → JurongImageToVideo)，输出节点是 "2"
            template = readWorkflowTemplate("03-image-to-video.json");
            nodeOutputKey = "2";
            // 下载上游图片 + 上传到 ComfyUI input 目录
            imageFilename = uploadImageToComfyUiInput(imageUrl);
        }

        // 智能合并：用户输入为主，上游文案作为风格参考；冲突时以用户为准
        String finalPrompt = mergePrompts(prompt, upstreamContent);

        // 替换占位符
        String workflowJson = template
            .replace("{{prompt}}", escapeJson(finalPrompt));
        if (!isTextToVideo) {
            workflowJson = workflowJson.replace("{{image_filename}}", escapeJson(imageFilename));
        }

        // 提交 ComfyUI
        JsonNode workflow;
        try {
            workflow = objectMapper.readTree(workflowJson);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "workflow JSON 解析失败: " + e.getMessage());
        }

        String promptId = comfyUIClient.submit(workflow);
        log.info("Canvas video workflow submitted: promptId={}, mode={}, imageFilename={}",
            promptId, isTextToVideo ? "text-to-video" : "image-to-video", imageFilename);

        // 轮询 history
        JsonNode history = pollUntilDone(promptId, videoPollTimeoutSec);

        // 提取视频产物
        JsonNode entry = history.get(promptId);
        JsonNode outputs = entry.get("outputs");
        if (outputs == null || !outputs.has(nodeOutputKey)) {
            throw new BusinessException(ErrorCode.COMFYUI_REJECTED,
                "ComfyUI 未返回 " + nodeOutputKey + " 节点 outputs");
        }
        JsonNode outNode = outputs.get(nodeOutputKey);
        log.info("ComfyUI video outputs[{}] keys: {}", nodeOutputKey,
            outNode == null ? "null" : outNode.fieldNames().toString());

        String url = null;

        // 1) 优先 video_url[]（公网 URL，不依赖 ComfyUI 本地路径，直接下载上传 MinIO）
        if (outNode != null && outNode.has("video_url") && outNode.get("video_url").isArray()) {
            url = downloadVideoUrl(outNode.get("video_url"), promptId);
        }

        // 2) fallback: video_path[]（ComfyUI 本地路径，通过 /view 下载再上传 MinIO）
        if (url == null && outNode != null && outNode.has("video_path") && outNode.get("video_path").isArray()) {
            url = downloadVideoPath(outNode.get("video_path"), promptId);
        }

        // 3) fallback: newapi_task_id[] → 调 NewAPI 等结果（image-to-video 节点用）
        if (url == null && outNode != null && outNode.has("newapi_task_id") && outNode.get("newapi_task_id").isArray()) {
            url = waitAndDownloadFromNewApi(outNode.get("newapi_task_id"), promptId);
        }

        if (url == null) {
            // dump 整个 outNode 到日志，方便调试
            log.error("ComfyUI video outNode[{}] dump: {}", nodeOutputKey,
                outNode == null ? "null" : outNode.toString());
            throw new BusinessException(ErrorCode.COMFYUI_REJECTED,
                nodeOutputKey + " 节点 outputs 既无 video_url/video_path 也无 newapi_task_id");
        }

        log.info("Canvas video generated: promptId={}, url={}", promptId, url);
        return url;
    }

    /** 下载 video_url[] 里的远程 URL（公网视频），上传到 MinIO，返回公网 URL。 */
    private String downloadVideoUrl(JsonNode videoUrlArr, String promptId) {
        if (videoUrlArr == null || !videoUrlArr.isArray()) return null;
        for (JsonNode n : videoUrlArr) {
            if (!n.isTextual()) continue;
            String url = n.asText();
            if (url.isEmpty() || !url.startsWith("http")) continue;
            try {
                log.info("Downloading video URL (promptId={}): {}", promptId, url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(300_000); // 视频文件可能 10-50MB，给 5 分钟
                conn.setRequestProperty("User-Agent", "JurongAICenter/1.0");
                try (InputStream is = conn.getInputStream()) {
                    String ext = "mp4";
                    if (url.contains(".mov")) ext = "mov";
                    else if (url.contains(".webm")) ext = "webm";
                    else if (!url.contains(".mp4")) ext = "bin";
                    String filename = "video_" + System.currentTimeMillis() + "." + ext;
                    String objectKey = STORAGE_PREFIX + "/" + promptId + "/" + filename;
                    String minioUrl = storageService.uploadObject(objectKey, is, "video/" + ext);
                    log.info("Uploaded video to MinIO: key={}, url={}", objectKey, minioUrl);
                    return minioUrl;
                }
            } catch (Exception e) {
                log.warn("downloadVideoUrl 失败 {}: {}", url, e.getMessage());
            }
        }
        return null;
    }

    @Override
    public Integer estimateCredits(String type, Map<String, Object> settings) {
        // 简化：按节点类型固定价格（真实项目应按模型 + settings 查价目表）
        return switch (type == null ? "" : type) {
            case "text"  -> 1;
            case "image" -> 5;
            case "video" -> 20;
            default      -> 1;
        };
    }

    // ============= 内部工具方法 =============

    /** 读 workflow JSON 模板（从 classpath:/workflows/ 或绝对路径） */
    private String readWorkflowTemplate(String filename) {
        try {
            if (workflowsDir.startsWith("classpath:")) {
                String path = workflowsDir.substring("classpath:".length()) + filename;
                Resource res = new org.springframework.core.io.ClassPathResource(path);
                if (!res.exists()) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "workflow 模板不存在: classpath:" + path);
                }
                try (InputStream is = res.getInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            } else {
                Path p = Paths.get(workflowsDir, filename);
                if (!Files.exists(p)) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "workflow 模板不存在: " + p.toAbsolutePath());
                }
                return Files.readString(p, StandardCharsets.UTF_8);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读 workflow 失败: " + e.getMessage());
        }
    }

    /** JSON 字符串转义（用于塞进 workflow 模板） */
    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }

    /** 轮询 ComfyUI history 直到 status.completed=true 或超时 */
    private JsonNode pollUntilDone(String promptId, int timeoutSec) {
        long start = System.currentTimeMillis();
        long timeoutMs = timeoutSec * 1000L;
        while (System.currentTimeMillis() - start < timeoutMs) {
            JsonNode history;
            try {
                history = comfyUIClient.pollHistory(promptId);
            } catch (BusinessException e) {
                // COMFYUI_UNREACHABLE：留到下次重试
                log.warn("poll history failed, retry: {}", e.getMessage());
                sleep(3000);
                continue;
            }
            if (history == null) {
                sleep(2000);
                continue;
            }
            JsonNode entry = history.get(promptId);
            if (entry == null) {
                sleep(2000);
                continue;
            }
            // 检查是否完成
            JsonNode status = entry.get("status");
            boolean completed = status != null && status.path("completed").asBoolean(false);
            if (completed) {
                return history;
            }
            // 检查是否失败
            String statusStr = status != null ? status.path("status_str").asText("") : "";
            if ("error".equalsIgnoreCase(statusStr)) {
                // DEBUG: 把整个 entry dump 到日志，下次失败时能看到原始结构
                log.error("ComfyUI workflow FAILED. promptId={}, full entry={}",
                    promptId, entry.toString());
                // 提取错误信息。关键：取 status.exec_info.traceback 整个 list（不是取 message）
                JsonNode execInfo = status.get("exec_info");
                String tracebackJson = (execInfo != null && execInfo.has("traceback"))
                    ? execInfo.get("traceback").toString()
                    : "";
                if (!tracebackJson.isEmpty() && !"[]".equals(tracebackJson)) {
                    // 把整个 traceback 记录到后端日志，方便调试
                    log.error("ComfyUI workflow traceback (promptId={}): {}",
                        promptId, tracebackJson);
                    String snippet = tracebackJson.length() > 2000
                        ? tracebackJson.substring(0, 2000) + "..."
                        : tracebackJson;
                    throw new BusinessException(ErrorCode.COMFYUI_REJECTED, "ComfyUI traceback: " + snippet);
                }
                // fallback
                String detail = extractComfyUiError(entry);
                throw new BusinessException(ErrorCode.COMFYUI_REJECTED, detail);
            }
            sleep(2000);
        }
        throw new BusinessException(ErrorCode.NEWAPI_TASK_TIMEOUT,
            "ComfyUI workflow 超时: promptId=" + promptId);
    }

    /** 下载 ComfyUI /view 输出 + 上传到 MinIO + 返回公网 URL */
    private String downloadAndUpload(String promptId, String filename, String subfolder, String type,
                                     String kind, String mediaKind) {
        try (InputStream is = comfyUIClient.downloadStream(filename, subfolder, type)) {
            String contentType = inferContentType(filename, mediaKind);
            String objectKey = STORAGE_PREFIX + "/" + promptId + "/" + filename;
            String url = storageService.uploadObject(objectKey, is, contentType);
            log.info("Uploaded {} to MinIO: key={}, url={}", kind, objectKey, url);
            return url;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                kind + " 下载/上传失败: " + e.getMessage());
        }
    }

    /** 处理 ui.video_path[]（可能多个路径，取第一个能下载的） */
    private String downloadVideoPath(JsonNode videoPathArr, String promptId) {
        if (videoPathArr == null || !videoPathArr.isArray()) return null;
        for (JsonNode p : videoPathArr) {
            if (!p.isTextual()) continue;
            String path = p.asText();
            if (path == null || path.isEmpty()) continue;
            try {
                String filename = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                String subfolder = "";
                if (path.contains("/output/")) {
                    String after = path.substring(path.indexOf("/output/") + 8);
                    int idx = after.lastIndexOf('/');
                    if (idx > 0) subfolder = after.substring(0, idx);
                }
                return downloadAndUpload(promptId, filename, subfolder, "output", "video", "video");
            } catch (Exception e) {
                log.warn("downloadVideoPath 失败 {}: {}", path, e.getMessage());
            }
        }
        return null;
    }

    /** 处理 ui.newapi_task_id[]：NewAPI 同步等结果 → 下载视频 → 上传 MinIO */
    private String waitAndDownloadFromNewApi(JsonNode taskIdArr, String promptId) {
        for (JsonNode n : taskIdArr) {
            String taskId = n.asText("");
            if (taskId.isEmpty()) continue;
            try {
                JsonNode poll = newApiClient.waitForVideo(taskId, videoPollTimeoutSec);
                String url = newApiClient.extractVideoUrl(poll);
                if (url == null || url.isEmpty()) {
                    log.warn("NewAPI 视频 {} 无 URL", taskId);
                    continue;
                }
                // 下载 + 上传到 MinIO
                String filename = taskId + ".mp4";
                try (InputStream is = new URI(url).toURL().openStream()) {
                    String objectKey = STORAGE_PREFIX + "/" + promptId + "/" + filename;
                    String uploaded = storageService.uploadObject(objectKey, is, "video/mp4");
                    log.info("Video downloaded from NewAPI → MinIO: taskId={}, url={}", taskId, uploaded);
                    return uploaded;
                }
            } catch (Exception e) {
                log.warn("waitAndDownloadFromNewApi 失败 {}: {}", taskId, e.getMessage());
            }
        }
        return null;
    }

    /** 把上游图片 URL 下载下来 → 上传到 ComfyUI input 目录 → 返回 ComfyUI 给的 filename */
    private String uploadImageToComfyUiInput(String imageUrl) {
        try (InputStream is = new URI(imageUrl).toURL().openStream()) {
            byte[] data = is.readAllBytes();
            String originalName = imageUrl.contains("/")
                ? imageUrl.substring(imageUrl.lastIndexOf('/') + 1)
                : "canvas_input_" + System.currentTimeMillis();
            String mime = inferContentType(originalName, "image");
            String comfyFilename = comfyUIClient.uploadImage(data, originalName, mime);
            log.info("Uploaded image to ComfyUI input: url={} → filename={}", imageUrl, comfyFilename);
            return comfyFilename;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "上游图片上传到 ComfyUI 失败: " + e.getMessage());
        }
    }

    /** MIME 推断 */
    private String inferContentType(String filename, String mediaKind) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        return "application/octet-stream";
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms);} catch (InterruptedException e) {Thread.currentThread().interrupt();}
    }

    /**
     * 从 ComfyUI history entry 里抠出真正的错误原因
     * 优先级：status.exec_info.exception_message > status.messages > 整条 entry
     */
    private String extractComfyUiError(JsonNode entry) {
        JsonNode status = entry.get("status");
        if (status != null) {
            // 1. exec_info 里的 Python 异常（最重要）
            JsonNode execInfo = status.get("exec_info");
            if (execInfo != null && execInfo.isObject()) {
                String exType = execInfo.path("exception_type").asText("");
                String exMsg = execInfo.path("exception_message").asText("");
                // traceback 通常包含真正异常信息
                JsonNode tb = execInfo.get("traceback");
                String tbText = (tb != null && tb.isArray()) ? tb.toString() : "";

                if (exMsg != null && !exMsg.isBlank()) {
                    return "ComfyUI " + (exType.isEmpty() ? "error" : exType) + ": " + exMsg;
                }
                if (!tbText.isEmpty() && !"[]".equals(tbText) && !"null".equals(tbText)) {
                    // traceback 在后端日志里打印完整
                    log.error("ComfyUI workflow traceback: {}", tbText);
                    String shortTb = tbText.length() > 1500 ? tbText.substring(0, 1500) + "..." : tbText;
                    return "ComfyUI " + (exType.isEmpty() ? "error" : exType) + " (traceback 过长看后端日志): " + shortTb;
                }
                // exec_info 存在但内容空：返回 exec_info 整个对象
                String exInfoJson = execInfo.toString();
                if (exInfoJson.length() > 5 && !"{}".equals(exInfoJson)) {
                    return "ComfyUI exec: " + (exInfoJson.length() > 800 ? exInfoJson.substring(0, 800) + "..." : exInfoJson);
                }
            }
            // 2. messages 数组（节点报错日志）
            JsonNode messages = status.get("messages");
            if (messages != null && messages.isArray()) {
                StringBuilder sb = new StringBuilder("ComfyUI error:");
                for (JsonNode msg : messages) {
                    if (msg.isArray() && msg.size() >= 2) {
                        String type = msg.get(0).asText("");
                        if (type.contains("error") || type.contains("execution_error")) {
                            sb.append(" [").append(type).append("] ").append(msg.get(1).asText()).append(";");
                        }
                    }
                }
                String s = sb.toString();
                if (!s.equals("ComfyUI error:")) return s;
            }
        }
        // 3. 兜底：返回整条 entry（限制 800 字）
        String json = entry.toString();
        return "ComfyUI workflow error: " + (json.length() > 800 ? json.substring(0, 800) + "..." : json);
    }
}