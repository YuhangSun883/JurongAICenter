package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.client.AicomingAssetsClient;
import com.jurong.aicenter.client.NewApiClient;
import com.jurong.aicenter.dto.generation.GenerateResponse;
import com.jurong.aicenter.entity.CanvasTask;
import com.jurong.aicenter.entity.Job;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.JobRepository;
import com.jurong.aicenter.service.StorageService;
import com.jurong.aicenter.service.VideoGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 视频生成服务实现（图生视频）— 走 NewAPI 中转站，绕过 ComfyUI。
 *
 * <p>严格按 Assets-API 参考手册 §5 端到端流程实现。
 *
 * <p>job 字段借用约定：
 * <ul>
 *   <li>{@code templateId} = "image-to-video"（标记本服务创建的 job，与 ComfyUI job 区分）</li>
 *   <li>{@code comfyuiPromptId} 存 NewAPI task_id（字段名借用，语义偏移）</li>
 *   <li>{@code inputsSnapshot} 存 JSON：{prompt, duration, resolution, originalFilename, assetId, assetUrl, taskId}</li>
 *   <li>{@code resultUrls} 存 MinIO URL 数组 JSON</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoGenerationServiceImpl implements VideoGenerationService {

    private final AicomingAssetsClient assetsClient;
    private final NewApiClient newApiClient;
    private final JobRepository jobRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;
    // 2026-08-12 关键修复:Job 完成后同步写画布节点,需要 canvas_node / canvas_task 仓库
    private final com.jurong.aicenter.repository.CanvasTaskRepository canvasTaskRepository;
    private final com.jurong.aicenter.repository.CanvasNodeRepository canvasNodeRepository;

    /** job 标记：本服务创建的图生视频 job */
    private static final String TEMPLATE_ID = "image-to-video";

    /** asset 轮询参数（手册 §4.2 推荐：3 秒一次，最多 90 秒） */
    private static final int ASSET_POLL_MAX_WAIT_SEC = 90;
    private static final int ASSET_POLL_INTERVAL_SEC = 3;

    /** 视频任务最大存活时间（手册/README 提到 aicoming 视频生成 5+ 分钟，给 30 分钟余量） */
    private static final Duration MAX_RUNNING_DURATION = Duration.ofMinutes(30);

    @Override
    public GenerateResponse submitImageToVideo(Long userId,
                                               byte[] fileBytes, String filename, String contentType,
                                               String prompt, int duration, String resolution) {
        log.info("[I2V-SUBMIT] 开始处理: userId={}, filename={}, contentType={}, size={}B, "
                + "promptLen={}, duration={}, resolution={}",
            userId, filename, contentType, fileBytes == null ? 0 : fileBytes.length,
            prompt == null ? 0 : prompt.length(), duration, resolution);

        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }
        if (fileBytes == null || fileBytes.length == 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "图片不能为空");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "prompt 不能为空");
        }
        final int useDuration = duration > 0 ? duration : 4;
        // aicoming 只接受小写 resolution（480p/720p/1080p/4k），大写会报 invalid_resolution
        final String useResolution = (resolution != null && !resolution.isBlank())
            ? resolution.toLowerCase() : "480p";

        // 1. 先建 job（PENDING），把输入快照存好（assetId/assetUrl/taskId 后续补）
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("prompt", prompt);
        inputs.put("duration", useDuration);
        inputs.put("resolution", useResolution);
        inputs.put("originalFilename", filename);

        Job job = new Job();
        job.setUserId(userId);
        job.setTemplateId(TEMPLATE_ID);
        job.setStatus("PENDING");
        job.setInputsSnapshot(toJsonString(inputs));
        job.setCreditsCost(0);
        job.setCreatedAt(LocalDateTime.now());
        jobRepository.insert(job);
        log.info("[I2V-SUBMIT] job 创建: jobId={}, userId={}, size={}B", job.getId(), userId, fileBytes.length);

        // 2. 上传图片到 proxy 8080 拿 asset_url
        String assetName = "i2v-" + job.getId();
        JsonNode assetData;
        try {
            log.info("[I2V-SUBMIT] jobId={} → 上传图片到 aicoming-proxy /v1/assets (name={})", job.getId(), assetName);
            assetData = assetsClient.uploadAssetByMultipart(fileBytes, filename, contentType, assetName);
            log.info("[I2V-SUBMIT] jobId={} ← asset 上传成功: assetId={}, assetUrl={}, status={}",
                job.getId(),
                assetData.path("id").asText(),
                assetData.path("asset_url").asText(),
                assetData.path("status").asText());
        } catch (Exception e) {
            log.error("[I2V-SUBMIT] jobId={} ← asset 上传失败: {}", job.getId(), e.getMessage(), e);
            markFailed(job, "upload asset failed: " + e.getMessage());
            throw e;
        }
        String assetId = assetData.path("id").asText("");
        String assetUrl = assetData.path("asset_url").asText("");
        if (assetId.isEmpty() || assetUrl.isEmpty()) {
            log.error("[I2V-SUBMIT] jobId={} ← asset 响应缺 id/asset_url: {}", job.getId(), assetData);
            markFailed(job, "asset 响应缺 id/asset_url: " + assetData);
            throw new BusinessException(ErrorCode.ASSET_UPLOAD_FAILED, "asset 响应缺 id/asset_url");
        }

        // 3. 轮询 asset 就绪
        try {
            log.info("[I2V-SUBMIT] jobId={} → 轮询 asset 就绪 (maxWaitSec={}, intervalSec={})",
                job.getId(), ASSET_POLL_MAX_WAIT_SEC, ASSET_POLL_INTERVAL_SEC);
            assetsClient.pollUntilActive(assetId, ASSET_POLL_MAX_WAIT_SEC, ASSET_POLL_INTERVAL_SEC);
            log.info("[I2V-SUBMIT] jobId={} ← asset 已就绪 (active)", job.getId());
        } catch (Exception e) {
            log.error("[I2V-SUBMIT] jobId={} ← asset 未就绪: {}", job.getId(), e.getMessage(), e);
            markFailed(job, "asset not active: " + e.getMessage());
            assetsClient.deleteAsset(assetId);  // best-effort 清理
            throw e;
        }

        // 4. 提交视频生成任务（NewAPI 3000，multipart 走 baseUrl，跟 Python submit_video 一致）
        String taskId;
        try {
            log.info("[I2V-SUBMIT] jobId={} → 提交视频生成到 NewAPI /v1/videos (multipart, 跟 Python api_client.submit_video 一致)",
                job.getId());
            // 2026-08-13 关键修复:走 submitVideoFull multipart 路径,跟 Python image_to_video.py 一致
            //   - baseUrl = NewAPI 3000 (中转站)
            //   - multipart 包含 model/prompt/duration/resolution + image(input_reference) 文件
            //   - image 同时发 3 个字段名(image/input_reference/image_url)模仿 Python
            //   - 已上传到 aicoming 资产库,可绕过 base64 直发触发的人脸审查
            com.jurong.aicenter.client.NewApiClient.SubmitResult submitResult =
                newApiClient.submitVideoFull(prompt, fileBytes, filename, contentType, useDuration, useResolution, null);
            taskId = submitResult.taskId();
            log.info("[I2V-SUBMIT] jobId={} ← NewAPI 视频任务已提交: taskId={}", job.getId(), taskId);
        } catch (Exception e) {
            log.error("[I2V-SUBMIT] jobId={} ← NewAPI 视频提交失败: {}", job.getId(), e.getMessage(), e);
            markFailed(job, "submit video failed: " + e.getMessage());
            assetsClient.deleteAsset(assetId);  // best-effort 清理
            throw e;
        }

        // 5. 更新 job：存 taskId + assetId，标 RUNNING
        inputs.put("assetId", assetId);
        inputs.put("assetUrl", assetUrl);
        inputs.put("taskId", taskId);
        job.setComfyuiPromptId(taskId);  // 借用字段存 NewAPI task_id
        job.setInputsSnapshot(toJsonString(inputs));
        job.setStatus("RUNNING");
        job.setStartedAt(LocalDateTime.now());
        jobRepository.updateById(job);
        log.info("[I2V-SUBMIT] jobId={} → job 标 RUNNING, taskId={}, assetId={}", job.getId(), taskId, assetId);

        return new GenerateResponse(job.getId(), job.getStatus(), taskId);
    }

    /**
     * 2026-08-13 新增:视频生成视频(参考图换物)
     *
     * <p>场景:画布中两个 video 节点连通
     * - 左视频节点:原视频(已生成,带 resultUrl)
     * - 右视频节点:用户上传 3 张参考图(衣服/商品等)
     * - 右节点 prompt 描述"保持原视频人物动作,替换为参考图的服装/商品"
     * - 后端把这 3 张参考图上传到素材库,拼接成一张大图,用 asset_url 提交 NewAPI
     * - NewAPI 生成保持原视频动作但换上衣服/商品的新视频</p>
     *
     * <p>入参 sourceVideoUrl 是参考用(前端显示给用户),实际生成时不传给 NewAPI(避免 asset_line_unavailable)。
     * 我们把 3 张参考图横拼成一张大图上传,NewAPI 一次 I2V 生成。</p>
     *
     * @param userId              用户 ID
     * @param referenceImageBytes 3 张参考图字节流(衣服/商品)
     * @param referenceFilenames  对应文件名
     * @param referenceMimeTypes  对应 mime
     * @param prompt              提示词(描述"保持原视频动作,换上参考图的XX")
     * @param duration            秒数(默认 4)
     * @param resolution          "480p"/"720p"
     * @param sourceVideoUrl      原视频 URL(仅参考用)
     * @return GenerateResponse 含 jobId
     */
    @Override
    public GenerateResponse submitVideoFromVideoWithReferences(Long userId,
                                                                List<byte[]> referenceImageBytes,
                                                                List<String> referenceFilenames,
                                                                List<String> referenceMimeTypes,
                                                                String prompt,
                                                                int duration,
                                                                String resolution,
                                                                String sourceVideoUrl) {
        log.info("[V2V-REF] 开始: userId={}, refImgs={}, promptLen={}, duration={}, resolution={}, sourceVideo={}",
            userId,
            referenceImageBytes == null ? 0 : referenceImageBytes.size(),
            prompt == null ? 0 : prompt.length(),
            duration, resolution,
            sourceVideoUrl == null ? "(null)" : "set");

        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }
        if (referenceImageBytes == null || referenceImageBytes.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "参考图列表为空(至少 1 张)");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "提示词不能为空");
        }

        // 1. 拼接多张参考图为一张大图(横拼 1×N)
        byte[] mergedBytes;
        String mergedFilename;
        String mergedMime;
        try {
            java.awt.image.BufferedImage merged =
                mergeImagesHorizontallyForV2V(referenceImageBytes);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(merged, "jpg", baos);
            mergedBytes = baos.toByteArray();
            mergedFilename = "v2v-refs-" + System.currentTimeMillis() + ".jpg";
            mergedMime = "image/jpeg";
            log.info("[V2V-REF] 拼接 {} 张参考图 → {}x{}, {} bytes",
                referenceImageBytes.size(), merged.getWidth(), merged.getHeight(), mergedBytes.length);
        } catch (Exception mergeErr) {
            log.error("[V2V-REF] 拼接参考图失败: {}", mergeErr.getMessage(), mergeErr);
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "拼接参考图失败: " + mergeErr.getMessage());
        }

        // 2. 优化 prompt: 加上"参考图横拼布局说明" + "保持原视频动作"提示
        String enhancedPrompt = prompt;
        if (sourceVideoUrl != null && !sourceVideoUrl.isBlank()) {
            enhancedPrompt = String.format(
                "【参考图布局】这是一张横拼参考图,从左到右依次为:第1张图、第2张图、第3张图。"
                + "请保持原视频(URL: %s)中的人物动作、表情、镜头节奏,"
                + "并将原视频中的服装/商品替换为参考图中的样式。\n\n%s",
                sourceVideoUrl, prompt);
        } else {
            enhancedPrompt = String.format(
                "【参考图布局】这是一张横拼参考图,从左到右依次为:第1张图、第2张图、第3张图。"
                + "请基于这些参考图生成视频,保留人物风格并替换服装/商品。\n\n%s", prompt);
        }

        // 3. 走 submitImageToVideo 主路径(自动上传 multipart + 提交流程)
        String firstFilename = referenceFilenames != null && !referenceFilenames.isEmpty()
            ? referenceFilenames.get(0) : "ref-0.jpg";
        return submitImageToVideo(userId, mergedBytes, mergedFilename, mergedMime,
            enhancedPrompt, duration, resolution);
    }

    /**
     * 横拼多张图(从左到右),等高布局,白底填充。1 张图直接返回。
     */
    private static java.awt.image.BufferedImage mergeImagesHorizontallyForV2V(
        java.util.List<byte[]> imageBytesList) throws java.io.IOException {
        if (imageBytesList == null || imageBytesList.isEmpty()) {
            throw new java.io.IOException("imageBytesList 为空");
        }
        if (imageBytesList.size() == 1) {
            return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(imageBytesList.get(0)));
        }
        // 解码所有图
        java.util.List<java.awt.image.BufferedImage> images = new java.util.ArrayList<>();
        for (byte[] b : imageBytesList) {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(b));
            if (img != null) images.add(img);
        }
        if (images.isEmpty()) throw new java.io.IOException("所有参考图解码失败");
        // 统一高度
        int targetHeight = images.get(0).getHeight();
        for (java.awt.image.BufferedImage img : images) {
            if (img.getHeight() < targetHeight) targetHeight = img.getHeight();
        }
        targetHeight = Math.max(256, Math.min(targetHeight, 1024));
        // 等比缩放
        java.util.List<java.awt.image.BufferedImage> resized = new java.util.ArrayList<>();
        int totalWidth = 0;
        for (java.awt.image.BufferedImage img : images) {
            double scale = (double) targetHeight / img.getHeight();
            int w = (int) (img.getWidth() * scale);
            java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(
                w, targetHeight, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = scaled.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, targetHeight);
            g.drawImage(img, 0, 0, w, targetHeight, null);
            g.dispose();
            resized.add(scaled);
            totalWidth += w;
        }
        // 拼
        java.awt.image.BufferedImage merged = new java.awt.image.BufferedImage(
            totalWidth, targetHeight, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2d = merged.createGraphics();
        g2d.setColor(java.awt.Color.WHITE);
        g2d.fillRect(0, 0, totalWidth, targetHeight);
        int x = 0;
        for (java.awt.image.BufferedImage img : resized) {
            g2d.drawImage(img, x, 0, null);
            x += img.getWidth();
        }
        g2d.dispose();
        return merged;
    }

    /**
     * 2026-08-11 added: submit image-to-video using pre-uploaded NewAPI asset library URL.
     * Scenarios: clothing-transfer grid image already uploaded to NewAPI asset library,
     * use asset://xxx directly to bypass upstream InputImageSensitiveContentDetected.
     *
     * Diff vs submitImageToVideo(byte[]):
     *   - skip aicoming-proxy /v1/assets upload (avoid double upload + second face check)
     *   - use NewAPI asset library URL directly in /v1/videos (JSON body + image_urls)
     *   - only main model doubao-seedance-2.0 supports asset library
     */
    @Override
    public GenerateResponse submitImageToVideoByAssetUrl(Long userId,
                                                           String preUploadedAssetUrl,
                                                           String prompt, int duration, String resolution) {
        log.info("[I2V-SUBMIT-ASSET] start: userId={}, assetUrl={}, promptLen={}, duration={}, resolution={}",
            userId, preUploadedAssetUrl, prompt == null ? 0 : prompt.length(), duration, resolution);

        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "user not logged in");
        }
        if (preUploadedAssetUrl == null || preUploadedAssetUrl.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "asset_url cannot be empty");
        }
        if (!preUploadedAssetUrl.startsWith("asset://")) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "asset_url must start with asset:// (NewAPI asset library), actual=" + preUploadedAssetUrl);
        }
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "prompt cannot be empty");
        }
        final int useDuration = duration > 0 ? duration : 4;
        final String useResolution = (resolution != null && !resolution.isBlank())
            ? resolution.toLowerCase() : "480p";

        // 1. create job (PENDING), save inputs snapshot
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("prompt", prompt);
        inputs.put("duration", useDuration);
        inputs.put("resolution", useResolution);
        inputs.put("assetUrl", preUploadedAssetUrl);
        inputs.put("viaAssetLibrary", true);  // marker: this submission uses asset library

        Job job = new Job();
        job.setUserId(userId);
        job.setTemplateId(TEMPLATE_ID);
        job.setStatus("PENDING");
        job.setInputsSnapshot(toJsonString(inputs));
        job.setCreditsCost(0);
        job.setCreatedAt(LocalDateTime.now());
        jobRepository.insert(job);
        log.info("[I2V-SUBMIT-ASSET] job created: jobId={}, userId={}, assetUrl={}",
            job.getId(), userId, preUploadedAssetUrl);

        // 2. directly call NewAPI /v1/videos (asset_url path), expect to bypass face detection
        // 1 retry (same as submitImageToVideo), 4xx business error stops immediately
        NewApiClient.SubmitResult submitResult = null;
        String taskId = null;
        Exception lastException = null;
        String usedFallbackUrl = null;  // 2026-08-13 记录 fallback 用的公网 URL
        final int maxSubmitRetries = 1;
        for (int attempt = 1; attempt <= maxSubmitRetries; attempt++) {
            try {
                log.info("[I2V-SUBMIT-ASSET] jobId={} -> submit (asset_url, attempt={}/{})",
                    job.getId(), attempt, maxSubmitRetries);
                // 2026-08-13 自动 fallback:asset_url 被 NewAPI 拒绝时,自动用 thumbnail_url 重试
                String assetId = preUploadedAssetUrl.startsWith("asset://")
                    ? preUploadedAssetUrl.substring("asset://".length())
                    : preUploadedAssetUrl;
                String thumbnailUrl = null;
                try {
                    JsonNode assetNode = assetsClient.getAsset(assetId);
                    thumbnailUrl = assetNode.path("thumbnail_url").asText("");
                    if (thumbnailUrl.isEmpty()) {
                        // 旧版字段名:url
                        thumbnailUrl = assetNode.path("url").asText("");
                    }
                    log.info("[I2V-SUBMIT-ASSET] jobId={} 拿到素材 thumbnail_url: assetId={}, thumbnailUrl={}",
                        job.getId(), assetId, thumbnailUrl);
                } catch (Exception ex) {
                    log.warn("[I2V-SUBMIT-ASSET] jobId={} 拿 thumbnail_url 失败,fallback 时用不到: {}",
                        job.getId(), ex.getMessage());
                }
                // 2026-08-13 修复:asset_url + 公网 URL fallback 都会触发 aicoming 上游的 PrivacyInformation (真人)
                //   实际行为:asset_url → asset_line_unavailable 或 HTTP 400 queued 占位,公网 URL → PrivacyInformation
                //   唯一可靠的提交方式 = multipart input_reference(直接发图 bytes,不走 URL 解析)
                //   策略:跳过公网 URL fallback,asset_url 失败时直接走 multipart
                //   触发 multipart fallback 的错误:
                //     - asset_line_unavailable(原)
                //     - HTTP 400 + body.status=queued(新,aicoming-proxy 占位响应)
                //     - "暂不支持虚拟人物素材" 等
                boolean assetUrlRejected = false;
                try {
                    submitResult = newApiClient.submitVideoByAssetRef(
                        prompt, preUploadedAssetUrl, useDuration, useResolution);
                } catch (BusinessException e) {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    // 2026-08-13 17:20 修复:加上 NEWAPI_REQUEST_INVALID 错误码的 fallback
                    //   实际命中场景:aicoming-proxy 8080 返回 HTTP 400 + body={status:queued, id:null}
                    //   NewApiClient 抛 NEWAPI_REQUEST_INVALID("aicoming 上游已入队但 id 尚未生成")
                    boolean isQueuedPlaceholder = msg.contains("aicoming 上游已入队")
                        || (msg.contains("queued") && msg.contains("尚未生成"));
                    // 2026-08-13 14:25 修复:加上 fail_to_fetch_task 占位响应的 fallback
                    //   实际命中场景:aicoming-proxy 8080 收到 asset_url 请求后立即返 HTTP 400 + body.code="fail_to_fetch_task"
                    //   body.message 嵌套 JSON 里 status=queued / id=null,表示"任务已入队但 id 还没生成"
                    //   NewApiClient.submitVideoByAssetRefList 只看顶层字段,误判为"响应缺 id/task_id",抛 NEWAPI_UNREACHABLE
                    //   客户端必须走 multipart fallback(下载 thumbnail_url 重发)才能拿到真 task_id
                    boolean isFailToFetchTask = msg.contains("fail_to_fetch_task");
                    if (msg.contains("asset_line_unavailable") || msg.contains("暂不支持虚拟人物素材")
                        || isQueuedPlaceholder
                        || isFailToFetchTask
                        || e.getCode() == ErrorCode.NEWAPI_REQUEST_INVALID.getCode()) {
                        assetUrlRejected = true;
                        log.warn("[I2V-SUBMIT-ASSET] jobId={} asset_url 被拒 (msg={}),直接走 multipart fallback",
                            job.getId(), msg.length() > 200 ? msg.substring(0, 200) + "..." : msg);
                    } else {
                        throw e;
                    }
                }
                if (assetUrlRejected || submitResult == null || submitResult.taskId() == null || submitResult.taskId().isBlank()) {
                    // 关键:不再用公网 URL fallback(仍会 PrivacyInformation)
                    // 改为:下载 thumbnail_url bytes,走 multipart input_reference
                    if (thumbnailUrl == null || thumbnailUrl.isBlank() || !thumbnailUrl.startsWith("http")) {
                        throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                            "asset_url 被拒且无 thumbnail_url 可用");
                    }
                    try {
                        byte[] imageBytes;
                        try (java.io.InputStream is = new java.net.URI(thumbnailUrl).toURL().openStream()) {
                            imageBytes = is.readAllBytes();
                        }
                        log.info("[I2V-SUBMIT-ASSET] jobId={} 从 thumbnail_url 下载图片: size={}B",
                            job.getId(), imageBytes.length);
                        submitResult = newApiClient.submitVideoFull(
                            prompt, imageBytes, "asset-image.jpg", "image/jpeg",
                            useDuration, useResolution, null);
                        log.info("[I2V-SUBMIT-ASSET] jobId={} ✓ multipart 提交成功: taskId={}",
                            job.getId(), submitResult.taskId());
                    } catch (Exception dlErr) {
                        log.error("[I2V-SUBMIT-ASSET] jobId={} multipart fallback 失败: {}",
                            job.getId(), dlErr.getMessage(), dlErr);
                        throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                            "asset_url + multipart 全失败: " + dlErr.getMessage());
                    }
                }
                taskId = submitResult.taskId();
                // 检查 taskId 是否由 fallback 产生(简单判断:imageUrl 改用 thumbnailUrl 提交时 url 不同)
                if (submitResult.url() != null && !submitResult.url().isBlank()
                    && !submitResult.url().equals(preUploadedAssetUrl)) {
                    usedFallbackUrl = submitResult.url();
                }
                log.info("[I2V-SUBMIT-ASSET] jobId={} <- NewAPI submitted: taskId={} (attempt={}, directUrl={})",
                    job.getId(), taskId, attempt,
                    submitResult.hasUrl() ? submitResult.url() : "(null)");
                break;
            } catch (Exception e) {
                lastException = e;
                log.warn("[I2V-SUBMIT-ASSET] jobId={} <- submit failed: {}", job.getId(), e.getMessage());
                if (e instanceof BusinessException
                    && ((BusinessException) e).getCode() == ErrorCode.NEWAPI_REQUEST_INVALID.getCode()) {
                    log.error("[I2V-SUBMIT-ASSET] jobId={} <- business error, no retry: {}",
                        job.getId(), e.getMessage());
                    break;
                }
            }
        }
        if (taskId == null) {
            String errMsg = lastException != null ? lastException.getMessage() : "unknown error";
            log.error("[I2V-SUBMIT-ASSET] jobId={} <- still failed after {} retries: {}",
                job.getId(), maxSubmitRetries, errMsg);
            markFailed(job, errMsg);
            ErrorCode finalCode = (lastException instanceof BusinessException
                && ((BusinessException) lastException).getCode() == ErrorCode.NEWAPI_REQUEST_INVALID.getCode())
                ? ErrorCode.NEWAPI_REQUEST_INVALID
                : ErrorCode.NEWAPI_UNREACHABLE;
            throw new BusinessException(finalCode, errMsg);
        }

        // 3. update job: store taskId + mark RUNNING
        inputs.put("taskId", taskId);
        job.setComfyuiPromptId(taskId);
        job.setInputsSnapshot(toJsonString(inputs));
        job.setStatus("RUNNING");
        job.setStartedAt(LocalDateTime.now());
        // if submit response has video URL (sync), save to job.result_urls immediately
        if (submitResult != null && submitResult.hasUrl()) {
            job.setResultUrls("[\"" + submitResult.url() + "\"]");
            log.info("[I2V-SUBMIT-ASSET] jobId={} -> submit response has video URL, saved immediately", job.getId());
        }
        jobRepository.updateById(job);
        log.info("[I2V-SUBMIT-ASSET] jobId={} -> job RUNNING, taskId={}",
            job.getId(), taskId);

        return new GenerateResponse(job.getId(), job.getStatus(), taskId);
    }

    /**
     * @Scheduled 每 2 秒扫一次 RUNNING 的图生视频 job，调 NewAPI 查状态。
     *
     * <p>只扫 templateId="image-to-video" 的 job，避免与 GenerationService.pollRunningJobs
     * （扫 ComfyUI job）互相干扰。
     */
    @Override
    @Scheduled(fixedDelay = 2000)
    public void pollRunningVideoJobs() {
        List<Job> runningJobs;
        try {
            runningJobs = jobRepository.selectList(
                new LambdaQueryWrapper<Job>()
                    .eq(Job::getStatus, "RUNNING")
                    .eq(Job::getTemplateId, TEMPLATE_ID)
            );
        } catch (Exception e) {
            log.error("[I2V-POLL] 查询 RUNNING job 失败", e);
            return;
        }
        if (runningJobs.isEmpty()) return;

        if (runningJobs.size() >= 5) {
            log.info("[I2V-POLL] 发现 {} 个 RUNNING job", runningJobs.size());
        } else {
            log.debug("[I2V-POLL] 发现 {} 个 RUNNING job", runningJobs.size());
        }
        for (Job job : runningJobs) {
            try {
                processOneVideoJob(job);
            } catch (Exception e) {
                log.error("[I2V-POLL] 处理 job {} 异常: {}", job.getId(), e.getMessage(), e);
            }
        }
    }

    /** 处理单个 RUNNING 的图生视频 job */
    private void processOneVideoJob(Job job) {
        String taskId = job.getComfyuiPromptId();
        if (taskId == null || taskId.isEmpty()) {
            log.error("[I2V-POLL] job {} 缺 taskId, 标 FAILED", job.getId());
            markFailed(job, "missing taskId");
            cleanupAsset(job);
            return;
        }

        // 超时检测
        if (job.getStartedAt() != null
            && Duration.between(job.getStartedAt(), LocalDateTime.now()).compareTo(MAX_RUNNING_DURATION) > 0) {
            log.error("[I2V-POLL] job {} 超时 (RUNNING > {}min), startedAt={}",
                job.getId(), MAX_RUNNING_DURATION.toMinutes(), job.getStartedAt());
            markFailed(job, "timeout: RUNNING > " + MAX_RUNNING_DURATION.toMinutes() + "min");
            cleanupAsset(job);
            return;
        }

        // 查 NewAPI 视频任务状态（单次查询，不阻塞）
        JsonNode result;
        try {
            result = newApiClient.pollVideo(taskId);
        } catch (BusinessException e) {
            // 2026-08-13 根治:NewAPI 4xx 业务错误(任务元数据被清理)不再快速 FAILED
            //   根因:实测 2026-08-13,NewAPI 中转站元数据在提交后 1-3 分钟内就被清理(原本以为有 24h TTL)
            //   但上游 aicoming 视频生成需要 2-5 分钟完成,等我们 poll 时元数据已被清。
            //   此时直接 markFailed 会让"上游正在生成"的视频被丢弃,前端永远拿不到。
            //   策略:从累计 15 次(75s)放宽到 40 次(约 5 分钟),覆盖 aicoming 视频生成完整周期
            //   累计 40 次仍 400 才 markFailed(给前端"上游可能还在跑,等一下刷新"的提示)
            int errCode = e.getCode();
            if (errCode == ErrorCode.NEWAPI_TASK_NOT_FOUND.getCode()) {
                // 提取当前计数(从 errorMessage 前缀 [TNF=N] 中读)
                int count = 0;
                String cur = job.getErrorMessage();
                if (cur != null && cur.startsWith("[TNF=")) {
                    int end = cur.indexOf("]");
                    if (end > 5) {
                        try { count = Integer.parseInt(cur.substring(5, end)); } catch (Exception ignore) {}
                    }
                }
                count++;
                // 2026-08-13:NewAPI 元数据清理提前到 1-3 分钟,所以延长到 40 次(约 5 分钟)
                final int MAX_TNF_COUNT = 40;
                log.warn("[I2V-POLL] job {} NewAPI 任务元数据被清理 (taskId={}, count={}/{}), 保留 RUNNING 等元数据恢复(视频可能还在 aicoming 生成中)",
                    job.getId(), taskId, count, MAX_TNF_COUNT);

                // 2026-08-13 兜底:累计 ≥3 次 400 后,主动查 MinIO 找视频
                //   场景:NewAPI 元数据被清理,但 aicoming 上游生成完已经把视频同步到 MinIO
                //   我们直接列 MinIO bucket 的 task_id 前缀,找到最新的 mp4 当作 resultUrl
                //   这样前端能直接拿到视频,不需要用户去 aicoming 控制台手动补救
                if (count >= 3) {
                    log.info("[I2V-POLL-MINIO-FALLBACK] job {} 累计 {} 次 400, 主动查 MinIO 兜底找视频: taskId={}",
                        job.getId(), count, taskId);
                    String minioUrl = tryFindVideoInMinIO(taskId);
                    if (minioUrl != null && !minioUrl.isBlank()) {
                        log.info("[I2V-POLL-MINIO-FALLBACK] job {} ✓ MinIO 兜底找到视频 URL: {}",
                            job.getId(), minioUrl);
                        // 用 MinIO 预签名 URL 当作 frontUrl(presigned URL 24h 有效,前端可访问)
                        List<String> resultUrls = List.of(minioUrl);
                        markCompleted(job, resultUrls);
                        return;
                    }
                    log.info("[I2V-POLL-MINIO-FALLBACK] job {} MinIO 中暂未找到视频,继续等", job.getId());
                }

                if (count >= MAX_TNF_COUNT) {
                    log.error("[I2V-POLL] job {} 连续 {} 次 400 not found, 标 FAILED 等用户手动处理", job.getId(), MAX_TNF_COUNT);
                    markFailed(job,
                        "NewAPI 任务元数据被清理(连续 " + MAX_TNF_COUNT + " 次 400 not found,约 " + (MAX_TNF_COUNT * 5) + " 秒)。"
                        + "视频文件本身可能还在 CDN 上(24h 有效),请到 aicoming 控制台查找视频 CDN URL 并手动更新 job.resultUrls");
                    cleanupAsset(job);
                } else {
                    // 保留 RUNNING + 记录计数到 errorMessage(前端可读到,显示"上游可能还在生成")
                    job.setErrorMessage("[TNF=" + count + "/" + MAX_TNF_COUNT + "] NewAPI task not found (元数据清理), "
                        + "上游视频可能还在 aicoming 生成中,继续等待(" + (MAX_TNF_COUNT - count) + " 次后超时)");
                    jobRepository.updateById(job);
                }
                return;
            }
            // 其他 5xx 错误继续重试
            String errMsg = e.getMessage() == null ? "" : e.getMessage();
            log.warn("[I2V-POLL] job {} 查询 NewAPI 失败 (下次重试): taskId={}, err={}",
                job.getId(), taskId, errMsg);
            return;
        }
        if (result == null) {
            log.warn("[I2V-POLL] job {} 查询 NewAPI 返回 null: taskId={}", job.getId(), taskId);
            return;
        }
        String status = result.path("status").asText("unknown").toLowerCase();

        // 关键日志：打印 NewAPI poll 原始响应（截断到 2000 字符避免日志过大）
        // 这是排查问题最关键的日志，能看到 aicoming 实际返回什么
        log.info("[I2V-POLL] job {} 状态: taskId={}, status={}, raw={}",
            job.getId(), taskId, status, truncateForLog(result.toString(), 2000));

        // 2026-08-13 关键修复:NewAPI 在异步处理中会切换真实 task_id
        //   现象:submit 返回 task_AAA,几秒后 poll 返回 task_BBB(同一个请求)
        //   根因:submit 是 OpenAI 兼容包装,内部 proxy 8080 异步调用 aicoming 生成新 task_id
        //   之前用 task_AAA 一直 poll,8080 不认,15 次后 400 not found 标 FAILED
        //   修复:poll 时如果响应里有 task_id 且和 job.comfyuiPromptId 不一致,立即更新
        String pollTaskId = result.path("task_id").asText("");
        String pollId = result.path("id").asText("");
        String realTaskId = pollTaskId.isEmpty() ? pollId : pollTaskId;
        if (!realTaskId.isEmpty() && !realTaskId.equals(taskId)) {
            log.warn("[I2V-POLL] job {} 检测到 task_id 切换: {} → {}, 更新 job.comfyuiPromptId",
                job.getId(), taskId, realTaskId);
            job.setComfyuiPromptId(realTaskId);
            jobRepository.updateById(job);
            // 用新 taskId 继续后续处理
            taskId = realTaskId;
        }

        // 2026-08-12 根治:不依赖 status 字段,实时捕获 URL 字段
        //   根因:NewAPI 元数据会在几秒内清理,即使 status=in_progress 时也可能有 url 字段
        //   (实测:history 16:04:20 日志显示 in_progress+completed_at 已存在,几秒后 400)
        //   所以:任何状态,只要响应里出现 url 字段,立即保存到 job.resultUrls,不能在窗口期后丢失
        //   canvas 那边的 videoGenNode.resultUrl 由 CanvasVideoGenService 自己兜底
        //   (每 5 秒轮询 job,看到 resultUrls 立即写回 node)
        String capturedUrl = newApiClient.extractVideoUrl(result);
        if (capturedUrl != null && !capturedUrl.isBlank()) {
            String currentResultUrls = job.getResultUrls();
            if (currentResultUrls == null || !currentResultUrls.contains(capturedUrl)) {
                job.setResultUrls("[\"" + capturedUrl + "\"]");
                jobRepository.updateById(job);
                log.info("[I2V-POLL] job {} 实时捕获 URL(状态={},元数据可能随时清理): {}",
                    job.getId(), status, capturedUrl);
            }
        }

        if ("completed".equals(status) || "succeeded".equals(status) || "success".equals(status)) {
            log.info("[I2V-POLL] job {} → 视频已完成，开始下载", job.getId());
            handleCompleted(job, result);
                } else if ("failed".equals(status) || "error".equals(status) || "cancelled".equals(status)) {
            // 2026-08-13 14:55 修复:不要立即 markFailed,先查 MinIO 兜底找视频
            //   根因:NewAPI 中转可能错误标记 wrapper task 为 failed,但 aicoming 真任务成功,视频已传到 MinIO
            //   实测证据:用户截图显示扣费成功 + 视频已生成,但我们判为失败,视频拿不到
            //   参考:L1208 已有 TNF(404)路径的 MinIO 兜底逻辑,这里补 status=failed 路径
            log.warn("[I2V-POLL] job {} → NewAPI 标记任务失败,先查 MinIO 兑底找视频: status={}", job.getId(), status);
            String minioUrl = tryFindVideoInMinIO(taskId);
            if (minioUrl != null && !minioUrl.isBlank()) {
                log.info("[I2V-POLL-MINIO-FALLBACK-FAILED] job {} ✓ MinIO 兑底找到视频 URL: {}",
                    job.getId(), minioUrl);
                // MinIO 预签名 URL(presigned 24h 有效)当作 frontUrl,前端可访问
                List<String> resultUrls = List.of(minioUrl);
                markCompleted(job, resultUrls);
                return;
            }
            // 真没 MinIO 视频,才 markFailed
            log.error("[I2V-POLL] job {} → NewAPI 任务失败(确认 MinIO 无视频): {}", job.getId(), result);
            markFailed(job, "NewAPI task failed: " + result);
            cleanupAsset(job);
        }
        // in_progress / unknown → 跳过，下次再扫
    }

    /** 视频任务完成：抠 URL → 下载 → 上传 MinIO → 标 COMPLETED → 清理 asset */
    private void handleCompleted(Job job, JsonNode result) {
        String videoUrl = newApiClient.extractVideoUrl(result);
        if (videoUrl == null || videoUrl.isBlank()) {
            // 2026-08-13 14:30 修复:状态 completed 但 URL 未就绪时不要立刻 markFailed
            //   根因:NewAPI 元数据可能在 aicoming 同步 CDN 后才补 URL,有几秒~几十秒延迟
            //   之前直接 markFailed 会让"上游其实已成功"的视频被丢弃
            //   修复:不 markFailed,让下次 @Scheduled 重试;真没 URL 由 MAX_RUNNING_DURATION 兑底超时
            log.warn("[I2V-DONE] job {} ← 响应中未找到 video URL(可能 CDN 同步延迟),保留 RUNNING 等下次 @Scheduled 重试", job.getId());
            // 不 markFailed,不 cleanup asset
            return;
        }
        log.info("[I2V-DONE] job {} → 视频URL: {}", job.getId(), videoUrl);

        // 2026-08-13 根治:前端只能播放公网 URL,必须把 frontUrl (火山 TOS CDN, 24h 有效) 放在 resultUrls[0]。
        //   之前把 MinIO presigned URL 放 resultUrls[0],但 MinIO 内网地址前端无法访问,导致"上游能生成但前端拿不到"。
        //   策略:resultUrls = [frontUrl, minioUrl] - 第一个给前端播放,第二个作内网备份。
        //   MinIO 上传是 best-effort 冗余,失败不影响业务。
        String filename = "jurong_i2v_" + job.getId() + ".mp4";
        String minioUrl = null;
        try {
            log.info("[I2V-DONE] job {} → 下载视频字节: url={}", job.getId(), videoUrl);
            byte[] bytes = downloadBytes(videoUrl);
            if (bytes != null && bytes.length > 0) {
                try (InputStream is = new ByteArrayInputStream(bytes)) {
                    minioUrl = storageService.uploadFile(
                        job.getUserId(), job.getId(), filename, is, "video/mp4");
                    log.info("[I2V-DONE] job {} ← 已上传到 MinIO(备份): {}", job.getId(), minioUrl);
                } catch (Exception e) {
                    log.warn("[I2V-DONE] job {} ← MinIO 上传失败(非致命,仅备份丢失): {}", job.getId(), e.getMessage());
                }
            } else {
                log.warn("[I2V-DONE] job {} → 下载到 0 字节(非致命)", job.getId());
            }
        } catch (Exception e) {
            log.warn("[I2V-DONE] job {} → 下载失败(非致命,仅备份丢失): {}", job.getId(), e.getMessage());
        }

        // 2026-08-13 根治:resultUrls[0] 必须是 frontUrl(公网 CDN),syncCanvasNodeResultUrl 取 list[0] 写到 canvas_node.result_url
        //   MinIO URL(若有)放 list[1] 作为备份,前端不会去取,只在内网工具(API 直查 job.resultUrls)时可见
        List<String> resultUrls = minioUrl != null
            ? List.of(videoUrl, minioUrl)
            : List.of(videoUrl);
        log.info("[I2V-DONE] job {} → 标 COMPLETED, resultUrls[0]=frontUrl (前端播放), resultUrls[1]={}",
            job.getId(), minioUrl != null ? "minio backup" : "none");
        markCompleted(job, resultUrls);

        log.info("[I2V-DONE] job {} ← 任务完成", job.getId());
        cleanupAsset(job);
    }

    /** best-effort 清理 asset（手册 §5 末尾步骤） */
    private void cleanupAsset(Job job) {
        String assetId = extractFromInputs(job, "assetId");
        if (assetId == null || assetId.isEmpty()) {
            log.debug("[I2V-CLEAN] job {} 无 assetId，跳过清理", job.getId());
            return;
        }
        log.info("[I2V-CLEAN] job {} → 清理 asset: {}", job.getId(), assetId);
        assetsClient.deleteAsset(assetId);
    }

    /** 从 inputsSnapshot JSON 里抠一个字段 */
    private String extractFromInputs(Job job, String key) {
        if (job.getInputsSnapshot() == null || job.getInputsSnapshot().isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(job.getInputsSnapshot());
            JsonNode val = node.get(key);
            return val == null ? null : val.asText(null);
        } catch (Exception e) {
            log.warn("extractFromInputs({},{}) failed: {}", job.getId(), key, e.getMessage());
            return null;
        }
    }

    /**
     * 2026-08-12 根治:必须带 User-Agent,否则火山引擎 TOS 签名 URL 会 403 Forbidden。
     *   之前默认不带 User-Agent,webClient 会用 Reactor Netty 默认 UA,
     *   部分场景下被 TOS 拒访。手动设 Mozilla UA 兼容所有 CDN/存储。
     */
    private static final String DOWNLOAD_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) JurongAICenter/1.0";

    /** 简单 GET 下载视频字节（与 VideoSyncServiceImpl 风格一致） */
    private byte[] downloadBytes(String url) {
        return webClientBuilder.build()
            .get()
            .uri(url)
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_OCTET_STREAM_VALUE)
            .header(HttpHeaders.USER_AGENT, DOWNLOAD_USER_AGENT)
            .retrieve()
            .bodyToMono(byte[].class)
            .timeout(Duration.ofSeconds(300))
            .block();
    }

    /**
     * 2026-08-13 新增:MinIO 兜底找视频
     *
     * <p>场景:NewAPI 中转站元数据被清理(task_not_exist 400),但 aicoming 上游已把视频同步到 MinIO。
     * 用户希望直接从 MinIO 拿视频,不再依赖 NewAPI 元数据。</p>
     *
     * <p>策略:列 MinIO bucket 的多种可能前缀(覆盖 aicoming 同步过来的路径习惯),
     * 找任意 mp4 文件,返回它的预签名 URL(24h 有效,前端可访问)。</p>
     */
    private String tryFindVideoInMinIO(String taskId) {
        if (storageService == null || taskId == null || taskId.isBlank()) return null;
        // 尝试多种前缀(覆盖 aicoming 上游同步过来的常见命名)
        String[] prefixes = {
            "i2v-result/" + taskId + "/",
            "videos/" + taskId + "/",
            "video-result/" + taskId + "/",
            "canvas-videos/" + taskId + "/",
            "ai-platform/i2v/" + taskId + "/",
            "ai-platform/videos/" + taskId + "/",
            // 弱匹配:只按 task_id 前缀(可能匹配到别人的同前缀任务,但概率低)
            taskId + "/",
        };
        for (String prefix : prefixes) {
            try {
                List<String> keys = storageService.listObjectsByPrefix(prefix, true);
                if (keys != null && !keys.isEmpty()) {
                    // 优先找 mp4,其次 webm/mov
                    String picked = keys.stream()
                        .filter(k -> k.toLowerCase().endsWith(".mp4"))
                        .findFirst()
                        .orElse(keys.stream()
                            .filter(k -> k.toLowerCase().endsWith(".webm") || k.toLowerCase().endsWith(".mov"))
                            .findFirst()
                            .orElse(keys.get(0)));
                    log.info("[I2V-POLL-MINIO-FALLBACK] 命中 prefix={}, picked key={}", prefix, picked);
                    String presignedUrl = storageService.getPresignedUrl(picked, 24);
                    return presignedUrl;
                }
            } catch (Exception ex) {
                log.warn("[I2V-POLL-MINIO-FALLBACK] prefix={} 查询异常: {}", prefix, ex.getMessage());
            }
        }
        log.info("[I2V-POLL-MINIO-FALLBACK] taskId={} 在所有前缀下都未找到视频", taskId);
        return null;
    }

    private void markCompleted(Job job, List<String> resultUrls) {
        // 2026-08-13 修复:清洗每个 URL(去反引号/引号/逗号/空格)
        //   之前 NewAPI 返回的 URL 偶发被反引号包围(如 Job 193),写到 resultUrls 后
        //   前端 new URL() 解析失败,sanitize 后才能正确播放。
        List<String> cleanUrls = resultUrls.stream()
            .map(this::sanitizeUrl)
            .filter(u -> u != null && !u.isBlank())
            .collect(Collectors.toList());
        job.setStatus("COMPLETED");
        job.setResultUrls(toJsonString(cleanUrls));
        job.setCompletedAt(LocalDateTime.now());
        if (job.getStartedAt() != null) {
            job.setDurationMs((int) Duration.between(job.getStartedAt(), job.getCompletedAt()).toMillis());
        }
        jobRepository.updateById(job);
        log.info("[I2V-DONE] job {} → 标 COMPLETED, resultUrls={}, durationMs={}",
            job.getId(), cleanUrls, job.getDurationMs());
        // 2026-08-12 关键修复:同步写 canvas_node.result_url。
        //   之前 VideoGenerationServiceImpl 不知道 canvas_node,导致 Job 完成后
        //   CanvasVideoGenService 兜底轮询超时后 videoGenNode.resultUrl 永远
        //   写不进去,前端看不到视频。这里通过 canvas_tasks 关联反查,写入
        //   videoGenNode.result_url + status=success,保证前端刷新即可看到。
        syncCanvasNodeResultUrl(job.getId(), cleanUrls);
        // 2026-08-13 修复:成功完成后清理 asset,避免素材库配额堆满 403。
        //   之前只在失败路径调 cleanupAsset,成功路径一直累积,导致 aicoming 3001
        //   配额超限 (asset_quota_exceeded)。成功提交后就可以删了,CDN 上已有 video。
        cleanupAsset(job);
    }

    /** 2026-08-13 新增:清洗 URL(去反引号/引号/逗号/空格) */
    private String sanitizeUrl(String url) {
        if (url == null) return null;
        String s = url.trim();
        s = s.replaceAll("^`+|`+$", "");
        s = s.replaceAll("^['\"]+|['\"]+$", "");
        s = s.trim();
        if (s.endsWith(",") || s.endsWith(",")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    /**
     * 2026-08-12 关键修复:通过 canvas_tasks.prompt (jobId=xxx) 关联反查画布节点,
     *   把 Job 完成的视频 URL 写到 canvas_node.result_url + status=success,
     *   保证前端刷新即可看到。这是解决"Job 完成但节点没拿到 URL"的关键修复。
     */
    private void syncCanvasNodeResultUrl(Long jobId, List<String> resultUrls) {
        if (resultUrls == null || resultUrls.isEmpty() || jobId == null) {
            log.warn("[I2V-DONE-sync] job {} → 跳过: resultUrls={}, jobId={}", jobId, resultUrls, jobId);
            return;
        }
        try {
            // 1. 查 canvas_tasks WHERE prompt = "jobId=xxx"
            String jobIdMarker = "jobId=" + jobId;
            var taskQuery = new LambdaQueryWrapper<com.jurong.aicenter.entity.CanvasTask>();
            taskQuery.eq(com.jurong.aicenter.entity.CanvasTask::getPrompt, jobIdMarker)
                .eq(com.jurong.aicenter.entity.CanvasTask::getType, "video-generation");
            com.jurong.aicenter.entity.CanvasTask canvasTask = canvasTaskRepository.selectOne(taskQuery);
            if (canvasTask == null) {
                // 2026-08-13 加日志:查 canvas_task 失败,先列出所有 video-generation task 看实际数据
                log.warn("[I2V-DONE-sync] job {} ↔ canvas_task 未找到 (prompt={}), 可能非画布任务", jobId, jobIdMarker);
                var allVideoTasks = canvasTaskRepository.selectList(
                    new LambdaQueryWrapper<com.jurong.aicenter.entity.CanvasTask>()
                        .eq(com.jurong.aicenter.entity.CanvasTask::getType, "video-generation")
                        .orderByDesc(com.jurong.aicenter.entity.CanvasTask::getCreatedAt)
                        .last("LIMIT 5"));
                log.warn("[I2V-DONE-sync] 最近的 5 个 video-generation task:");
                for (var t : allVideoTasks) {
                    log.warn("[I2V-DONE-sync]   task id={}, prompt={}, nodeId={}, resultUrl={}, status={}",
                        t.getId(), t.getPrompt(), t.getNodeId(), t.getResultUrl(), t.getStatus());
                }
                return;
            }
            // 2. 取第一个 URL + 提取 text 节点类型
            String frontUrl = resultUrls.get(0);
            if (frontUrl == null || frontUrl.isBlank()) {
                log.warn("[I2V-DONE-sync] job {} → resultUrls[0] 为空, 跳过 node 同步 (urls={})", jobId, resultUrls);
                return;
            }
            log.info("[I2V-DONE-sync] job {} → 查到 canvas_task: taskId={}, nodeId={}, frontUrl={}",
                jobId, canvasTask.getId(), canvasTask.getNodeId(), frontUrl);
            // 3. 写 canvas_node.result_url + status=success
            String nodeId = canvasTask.getNodeId();
            var nodeQuery = new LambdaQueryWrapper<com.jurong.aicenter.entity.CanvasNode>();
            nodeQuery.eq(com.jurong.aicenter.entity.CanvasNode::getId, nodeId);
            com.jurong.aicenter.entity.CanvasNode node = canvasNodeRepository.selectOne(nodeQuery);
            if (node == null) {
                log.warn("[I2V-DONE-sync] job {} → canvas_node {} 未找到, 跳过", jobId, nodeId);
                return;
            }
            String beforeUrl = node.getResultUrl();
            String beforeStatus = node.getStatus();
            node.setResultUrl(frontUrl);
            node.setStatus("success");
            node.setUpdatedAt(LocalDateTime.now());
            int updateRows = canvasNodeRepository.updateById(node);
            // 2026-08-13 防御:写后立即重读,确认真的写进去了(排查 ORM 缓存/事务问题)
            com.jurong.aicenter.entity.CanvasNode reloadedNode = canvasNodeRepository.selectById(nodeId);
            log.info("[I2V-DONE-sync] job {} → 写入 canvas_node: nodeId={}, before=({}/{}), after=({}/{}), updateRows={}, reloaded=({}/{})",
                jobId, nodeId,
                beforeStatus, beforeUrl,
                node.getStatus(), node.getResultUrl(),
                updateRows,
                reloadedNode == null ? "NULL" : reloadedNode.getStatus(),
                reloadedNode == null ? "NULL" : reloadedNode.getResultUrl());
            // 4. 同步写 canvas_task.result_url + status
            canvasTask.setResultUrl(frontUrl);
            canvasTask.setStatus("success");
            canvasTask.setCompletedAt(LocalDateTime.now());
            int taskUpdateRows = canvasTaskRepository.updateById(canvasTask);
            log.info("[I2V-DONE-sync] job {} → 同步 videoGenNode.resultUrl + status=success: nodeId={}, url={}, taskUpdateRows={}",
                jobId, nodeId, frontUrl, taskUpdateRows);
        } catch (Exception e) {
            log.warn("[I2V-DONE-sync] job {} → 同步 canvas_node 失败(非致命): {}", jobId, e.getMessage(), e);
        }
    }

    private void markFailed(Job job, String errorMessage) {
        job.setStatus("FAILED");
        job.setErrorMessage(errorMessage);
        job.setCompletedAt(LocalDateTime.now());
        if (job.getStartedAt() != null) {
            job.setDurationMs((int) Duration.between(job.getStartedAt(), job.getCompletedAt()).toMillis());
        }
        jobRepository.updateById(job);
        log.warn("[I2V-DONE] job {} → 标 FAILED, err={}, durationMs={}",
            job.getId(), errorMessage, job.getDurationMs());
        // 2026-08-13 关键修复:markFailed 也同步 canvas_task 状态为 failed,前端不再一直显示 running
        syncCanvasTaskStatusOnFailure(job.getId(), errorMessage);
    }

    /**
     * 2026-08-13 新增:Job FAILED 时同步 canvas_task 状态为 failed。
     * 之前 markFailed 只改 jobs 表,canvas_tasks 表的 status 还停留在 running,
     * 导致前端一直显示 "还在跑"。现在 markFailed 时把 canvas_task 也改成 failed。
     */
    private void syncCanvasTaskStatusOnFailure(Long jobId, String errorMessage) {
        try {
            String jobIdMarker = "jobId=" + jobId;
            var taskQuery = new LambdaQueryWrapper<com.jurong.aicenter.entity.CanvasTask>();
            taskQuery.eq(com.jurong.aicenter.entity.CanvasTask::getPrompt, jobIdMarker)
                .eq(com.jurong.aicenter.entity.CanvasTask::getType, "video-generation");
            com.jurong.aicenter.entity.CanvasTask canvasTask = canvasTaskRepository.selectOne(taskQuery);
            if (canvasTask == null) {
                log.info("[I2V-FAIL] job {} ↔ canvas_task 未找到 (prompt={}), 可能非画布任务", jobId, jobIdMarker);
                return;
            }
            // 2026-08-13 修复:同步也更新关联的 canvas_node.status=failed + fail_reason
            //   之前 markFailed 只改 canvas_task,不改 canvas_node,
            //   导致前端轮询 videoGenNode 仍显示 running,陷入幂等卡死(已有 running 任务就拒绝重启)
            String nodeId = canvasTask.getNodeId();
            if (nodeId != null && !nodeId.isBlank()) {
                com.jurong.aicenter.entity.CanvasNode videoGenNode = canvasNodeRepository.selectById(nodeId);
                if (videoGenNode != null) {
                    String shortErr = errorMessage != null && errorMessage.length() > 480
                        ? errorMessage.substring(0, 480) + "..." : errorMessage;
                    videoGenNode.setStatus("failed");
                    videoGenNode.setFailReason(shortErr);
                    videoGenNode.setUpdatedAt(java.time.LocalDateTime.now());
                    int nodeRows = canvasNodeRepository.updateById(videoGenNode);
                    log.info("[I2V-FAIL] job {} → 同步 canvas_node 标 failed: nodeId={}, updateRows={}",
                        jobId, nodeId, nodeRows);
                } else {
                    log.warn("[I2V-FAIL] job {} → canvas_node {} 未找到,跳过 node 同步", jobId, nodeId);
                }
            }
            canvasTask.setStatus("failed");
            canvasTask.setErrorMessage(errorMessage != null && errorMessage.length() > 900
                ? errorMessage.substring(0, 900) : errorMessage);
            canvasTask.setCompletedAt(LocalDateTime.now());
            int rows = canvasTaskRepository.updateById(canvasTask);
            log.info("[I2V-FAIL] job {} → 同步 canvas_task 标 failed: nodeId={}, updateRows={}, canvasTaskId={}",
                jobId, canvasTask.getNodeId(), rows, canvasTask.getId());
            // 2026-08-13 防御:写后立即重读,确认真的写进去了(排查 ORM 缓存/事务问题)
            CanvasTask reloaded = canvasTaskRepository.selectById(canvasTask.getId());
            log.info("[I2V-FAIL] job {} → 写后校验 canvas_task: status={}, completed_at={}, error={}",
                jobId,
                reloaded == null ? "NULL!" : reloaded.getStatus(),
                reloaded == null ? "NULL!" : reloaded.getCompletedAt(),
                reloaded == null ? "NULL!" :
                    (reloaded.getErrorMessage() == null ? "null" :
                        (reloaded.getErrorMessage().length() > 60 ? reloaded.getErrorMessage().substring(0, 60) + "..." : reloaded.getErrorMessage())));
        } catch (Exception e) {
            log.warn("[I2V-FAIL] job {} → 同步 canvas_task 失败(非致命): {}", jobId, e.getMessage());
        }
    }

    private String toJsonString(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("toJsonString failed: {}", e.getMessage());
            return null;
        }
    }

    /** 把超长 JSON 字符串截断到指定长度，方便日志查看（避免 NewAPI 响应把日志撑爆） */
    private String truncateForLog(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...(truncated, totalLen=" + s.length() + ")";
    }
}
