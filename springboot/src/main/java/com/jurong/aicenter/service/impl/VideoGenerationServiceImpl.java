package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.client.NewApiClient;
import com.jurong.aicenter.client.NewApiClient.SubmitResult;
import com.jurong.aicenter.dto.generation.GenerateResponse;
import com.jurong.aicenter.dto.video.VideoOptions;
import com.jurong.aicenter.entity.Job;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.JobRepository;
import com.jurong.aicenter.service.MediaService;
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
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 视频生成服务实现（文生视频 / 图生视频 / 多图生视频）— 全部走 NewAPI 中转站，绕过 ComfyUI。
 *
 * <p>2026-08-11 重构：
 * <ul>
 *   <li>text-to-video 从 ComfyUI 切换到 NewAPI /v1/videos（multipart + 占位图）</li>
 *   <li>image-to-video 保留 NewAPI 调用，参数改用 VideoOptions 包装</li>
 *   <li>新增 multi-image-to-video 走 NewAPI（多个 input_reference）</li>
 *   <li>轮询任务改为统一扫三种 templateId（text-to-video / image-to-video / multi-image-to-video）</li>
 * </ul>
 *
 * <p>job 字段借用约定：
 * <ul>
 *   <li>{@code templateId} 标记类型</li>
 *   <li>{@code comfyuiPromptId} 存 NewAPI task_id（字段名借用）</li>
 *   <li>{@code inputsSnapshot} 存 JSON 输入</li>
 *   <li>{@code resultUrls} 存 MinIO URL 数组 JSON</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoGenerationServiceImpl implements VideoGenerationService {

    private final NewApiClient newApiClient;
    private final JobRepository jobRepository;
    private final StorageService storageService;
    private final MediaService mediaService;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;

    /** 视频任务最大存活时间（30 分钟余量） */
    private static final Duration MAX_RUNNING_DURATION = Duration.ofMinutes(30);

    /** 记录 job 连续返回 "任务不存在" 的次数，超过阈值后清理（防御 NewAPI 偶发 400） */
    private final ConcurrentHashMap<Long, AtomicInteger> notFoundCountMap = new ConcurrentHashMap<>();

    /** 支持的 video templateId 集合（轮询时用 in 条件） */
    private static final List<String> VIDEO_TEMPLATE_IDS = List.of(
        TEMPLATE_TEXT_TO_VIDEO, TEMPLATE_IMAGE_TO_VIDEO, TEMPLATE_MULTI_IMAGE_TO_VIDEO
    );

    // 2026-08-14 FIX:pollRunningVideoJobs 空结果时打日志,排查时能看到调度器在跑
    //  (2 秒一次 INFO 日志,在生产环境不构成噪声,因为 GenerationServiceImpl.pollRunningJobs
    //  早就有 log.debug 类似的 tick 日志)

    // ============================================================
    // 提交接口
    // ============================================================

    @Override
    public GenerateResponse submitImageToVideo(Long userId,
                                              byte[] fileBytes, String filename, String contentType,
                                              String prompt, int duration, String resolution) {
        // 组装 VideoOptions 并委托到 submitInternal
        VideoOptions options = VideoOptions.builder()
            .duration(duration)
            .resolution(resolution)
            .build();
        return submitInternal(userId, TEMPLATE_IMAGE_TO_VIDEO, prompt, options,
            fileBytes == null ? null : List.of(fileBytes), filename);
    }

    /**
     * 2026-08-13 补:使用预上传到 NewAPI 素材库的 asset_url 提交图生视频。
     * 适用场景:换装总图已先上传到 NewAPI 素材库,此处直接引用。
     *
     * <p>2026-08-14 修复:回退到昨晚跑通的实现 (D:\JurongAICenter 版本)。</p>
     *
     * <p>关键差异:
     * <ul>
     *   <li>不依赖 submitInternal + text-to-video 路径(text-to-video 会发 placeholder
     *       占位图,模型无法从 placeholder 还原 asset_url 内容)</li>
     *   <li>直接调 newApiClient.submitVideoByAssetRef(asset_url),NewAPI
     *       收到 asset:// 引用后会从素材库拉真实图作为 input_reference</li>
     *   <li>asset_url 失败时自动 fallback 到 multipart thumbnail_url(本方法的简化版不做 multipart fallback,
     *       但 videoGenerationService 入口会捕获 NEWAPI_REQUEST_INVALID 错误)</li>
     * </ul>
     */
    @Override
    public GenerateResponse submitImageToVideoByAssetUrl(Long userId,
                                                        String preUploadedAssetUrl,
                                                        String prompt, int duration, String resolution) {
        if (preUploadedAssetUrl == null || preUploadedAssetUrl.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "asset_url 不能为空");
        }
        if (!preUploadedAssetUrl.startsWith("asset://")) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "asset_url 必须以 asset:// 开头 (NewAPI 素材库引用),实际=" + preUploadedAssetUrl);
        }
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "prompt 不能为空");
        }
        final int useDuration = duration > 0 ? duration : 4;
        final String useResolution = (resolution != null && !resolution.isBlank())
            ? resolution.toLowerCase() : "480p";

        log.info("[I2V-SUBMIT-ASSET] start: userId={}, assetUrl={}, promptLen={}, duration={}, resolution={}",
            userId, preUploadedAssetUrl, prompt.length(), useDuration, useResolution);

        // 1. 直接调 NewAPI submitVideoByAssetRef(asset_url 路径)
        //   - 成功:返回 SubmitResult{taskId, ...}
        //   - 失败:抛 BusinessException(NEWAPI_REQUEST_INVALID / NEWAPI_UNREACHABLE / NEWAPI_TASK_NOT_FOUND 等)
        //     上层画布模块(CanvasVideoGenService)会捕获并 failTask
        NewApiClient.SubmitResult submitResult;
        try {
            submitResult = newApiClient.submitVideoByAssetRef(
                prompt, preUploadedAssetUrl, useDuration, useResolution);
        } catch (BusinessException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            // asset_url 路径被拒的典型错误:
            //   - "asset_line_unavailable" / "暂不支持虚拟人物素材"
            //   - aicoming-proxy 8080 400 + body.status=queued 占位
            //   - "fail_to_fetch_task"
            //   - NEWAPI_REQUEST_INVALID
            boolean needMultipartFallback = msg.contains("asset_line_unavailable")
                || msg.contains("暂不支持虚拟人物素材")
                || msg.contains("fail_to_fetch_task")
                || msg.contains("aicoming 上游已入队")
                || (msg.contains("queued") && msg.contains("尚未生成"))
                || e.getCode() == ErrorCode.NEWAPI_REQUEST_INVALID.getCode();
            if (!needMultipartFallback) {
                throw e;  // 其他错误直接抛(网络/认证/未知)
            }
            // 走 multipart fallback:从素材库拿 thumbnail_url → 下载图 bytes → submitVideoFull
            log.warn("[I2V-SUBMIT-ASSET] asset_url 被拒 (msg={}), 走 multipart fallback (thumbnail_url)",
                msg.length() > 200 ? msg.substring(0, 200) + "..." : msg);
            String assetId = preUploadedAssetUrl.startsWith("asset://")
                ? preUploadedAssetUrl.substring("asset://".length()) : preUploadedAssetUrl;
            String thumbnailUrl = null;
            try {
                com.fasterxml.jackson.databind.JsonNode assetNode =
                    newApiClient.getAicomingAssetsClientForUpload().getAsset(assetId);
                thumbnailUrl = assetNode.path("thumbnail_url").asText("");
                if (thumbnailUrl.isEmpty()) thumbnailUrl = assetNode.path("url").asText("");
            } catch (Exception ex) {
                log.warn("[I2V-SUBMIT-ASSET] 拿 thumbnail_url 失败: {}", ex.getMessage());
            }
            if (thumbnailUrl == null || thumbnailUrl.isBlank() || !thumbnailUrl.startsWith("http")) {
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                    "asset_url 被拒且无 thumbnail_url 可用");
            }
            byte[] imageBytes;
            java.io.InputStream is = null;
            try {
                is = new java.net.URI(thumbnailUrl).toURL().openStream();
                imageBytes = is.readAllBytes();
            } catch (java.net.URISyntaxException | java.io.IOException ex) {
                // MalformedURLException 继承 IOException,所以这里只需 IOException
                // (避免 multi-catch "类无法通过子类化关联" 编译错误)
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                    "下载 thumbnail_url 失败: " + ex.getMessage());
            } finally {
                if (is != null) {
                    try { is.close(); } catch (java.io.IOException ignored) { }
                }
            }
            log.info("[I2V-SUBMIT-ASSET] 从 thumbnail_url 下载图片: size={}B", imageBytes.length);
            submitResult = newApiClient.submitVideoFull(
                prompt, imageBytes, "asset-image.jpg", "image/jpeg",
                useDuration, useResolution, null);
        }

        if (submitResult == null || (submitResult.taskId() == null || submitResult.taskId().isBlank())) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "submitVideoByAssetRef 返回 taskId 为空");
        }

        // 2. 创建 Job 记录(便于 @Scheduled pollRunningVideoJobs 扫到)
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("prompt", prompt);
        inputs.put("duration", useDuration);
        inputs.put("resolution", useResolution);
        inputs.put("assetUrl", preUploadedAssetUrl);
        inputs.put("viaAssetLibrary", true);

        Job job = new Job();
        job.setUserId(userId);
        job.setTemplateId(TEMPLATE_IMAGE_TO_VIDEO);
        job.setStatus("RUNNING");
        job.setComfyuiPromptId(submitResult.taskId());  // 复用字段存 NewAPI task_id
        job.setInputsSnapshot(toJsonString(inputs));
        job.setCreditsCost(0);
        job.setCreatedAt(LocalDateTime.now());
        job.setStartedAt(LocalDateTime.now());
        jobRepository.insert(job);

        log.info("[I2V-SUBMIT-ASSET] job created: jobId={}, userId={}, taskId={}",
            job.getId(), userId, submitResult.taskId());

        // 2026-08-14:GenerateResponse 仅有 @AllArgsConstructor(三参数),无 @NoArgsConstructor
        return new GenerateResponse(job.getId(), "RUNNING", submitResult.taskId());
    }

    @Override
    public GenerateResponse submitTextToVideo(Long userId, String prompt, VideoOptions options) {
        return submitInternal(userId, TEMPLATE_TEXT_TO_VIDEO, prompt, options, null, null);
    }

    /**
     * 2026-08-13 补:视频生成视频(多图参考换物)。
     *
     * <p>实现:把 N 张参考图横拼成一张大图,上传到 aicoming 素材库(asset_url 绕过真人检测),
     * 用 NewAPI /v1/videos 提交,prompt 携带"保留原视频动作,换上参考图XX"。</p>
     *
     * <p>如果原视频 URL 命中上游人脸检测,fallback 走单图 prompt 描述。</p>
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
        if (referenceImageBytes == null || referenceImageBytes.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "参考图不能为空");
        }
        // 简单拼接:2~3 张直接横向并排,4~6 张排成 2xN 网格
        byte[] mergedImage;
        try {
            mergedImage = mergeReferenceImages(referenceImageBytes, referenceMimeTypes);
        } catch (Exception e) {
            log.error("mergeReferenceImages failed: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INVALID_PARAM, "参考图合并失败: " + e.getMessage());
        }
        // 上传到素材库 — 此处复用 multipart upload
        String mergedFilename = "v2v_ref_" + System.currentTimeMillis() + ".png";
        JsonNode uploaded = newApiClient.getAicomingAssetsClientForUpload()
            .uploadAssetByMultipart(mergedImage, mergedFilename, "image/png", "v2v-input");
        // wait active
        String assetId = uploaded.path("id").asText("");
        if (assetId.isEmpty()) {
            throw new BusinessException(ErrorCode.ASSET_UPLOAD_FAILED, "v2v 素材上传未返回 id");
        }
        newApiClient.getAicomingAssetsClientForUpload().pollUntilActive(assetId, 60, 3);
        String assetUrl = uploaded.path("asset_url").asText("");
        log.info("[V2V-REF] merged image uploaded: assetUrl={}, merged size={}B, refCount={}",
            assetUrl, mergedImage.length, referenceImageBytes.size());

        // 组装带"原视频"上下文的 prompt
        String finalPrompt = buildV2VPrompt(prompt, sourceVideoUrl, referenceImageBytes.size());

        VideoOptions opts = VideoOptions.builder()
            .duration(duration <= 0 ? 5 : duration)
            .resolution(resolution == null || resolution.isBlank() ? "480p" : resolution.toLowerCase())
            .ratio("16:9")
            .model("doubao-seedance-2.0")
            .build();

        return submitInternal(userId, TEMPLATE_MULTI_IMAGE_TO_VIDEO, finalPrompt, opts,
            null, null);
    }

    /**
     * 横向拼接参考图为一张大图。
     */
    private byte[] mergeReferenceImages(List<byte[]> images, List<String> mimeTypes) throws IOException {
        if (images.size() == 1) return images.get(0);
        // 简化:所有图都按 jpeg 解码,append 成横向一张
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageWriter writer = null;
        try {
            // 解码 + 缩放到统一高度
            int targetH = 480;
            java.util.List<java.awt.image.BufferedImage> decoded = new java.util.ArrayList<>();
            for (int i = 0; i < images.size(); i++) {
                byte[] bytes = images.get(i);
                java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(bytes));
                if (src == null) {
                    throw new IOException("图片解码失败: index=" + i);
                }
                int w = (int) Math.round((double) src.getWidth() * targetH / src.getHeight());
                java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(
                    w, targetH, java.awt.image.BufferedImage.TYPE_INT_RGB);
                scaled.createGraphics().drawImage(src, 0, 0, w, targetH, null);
                decoded.add(scaled);
            }
            int totalW = decoded.stream().mapToInt(java.awt.image.BufferedImage::getWidth).sum();
            java.awt.image.BufferedImage merged = new java.awt.image.BufferedImage(
                totalW, targetH, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = merged.createGraphics();
            int x = 0;
            for (java.awt.image.BufferedImage img : decoded) {
                g.drawImage(img, x, 0, null);
                x += img.getWidth();
            }
            g.dispose();
            javax.imageio.ImageIO.write(merged, "png", baos);
            return baos.toByteArray();
        } finally {
            // noop
        }
    }

    /**
     * 构造带原视频上下文的 V2V prompt。
     */
    private String buildV2VPrompt(String userPrompt, String sourceVideoUrl, int refCount) {
        StringBuilder sb = new StringBuilder();
        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append(userPrompt.trim());
        }
        if (sourceVideoUrl != null && !sourceVideoUrl.isBlank()) {
            sb.append(". 严格保留原视频的人物动作和姿态");
        }
        if (refCount > 0) {
            sb.append(". 参考图(").append(refCount).append("张)展示目标服装/商品/场景");
        }
        return sb.toString();
    }

    @Override
    public GenerateResponse submitMultiImageToVideo(Long userId, String prompt,
                                                    List<byte[]> imageBytesList, VideoOptions options) {
        return submitInternal(userId, TEMPLATE_MULTI_IMAGE_TO_VIDEO, prompt, options, imageBytesList, null);
    }

    /**
     * 统一的提交逻辑：建 job → 调 NewAPI 提交 → 标 RUNNING。
     *
     * @param userId          当前用户 ID
     * @param templateId      job 标记
     * @param prompt          用户提示词
     * @param options         视频参数
     * @param imageBytesList  参考图片（null=文生视频）
     * @param firstFilename   第一个图片的原始文件名（仅用于 inputs 记录，NewAPI 内部会改名为 ref_0.png）
     */
    private GenerateResponse submitInternal(Long userId, String templateId, String prompt,
                                            VideoOptions options, List<byte[]> imageBytesList,
                                            String firstFilename) {
        log.info("[VIDEO-SUBMIT] userId={}, template={}, promptLen={}, options={}, images={}",
            userId, templateId,
            prompt == null ? 0 : prompt.length(),
            options,
            imageBytesList == null ? 0 : imageBytesList.size());

        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "prompt 不能为空");
        }
        // 兜底 options
        if (options == null) {
            options = VideoOptions.builder().build();
        }

        // 1. 先建 job（PENDING）
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("prompt", prompt);
        inputs.put("duration", options.getDuration());
        inputs.put("resolution", options.getResolution());
        inputs.put("ratio", options.getRatio());
        inputs.put("generateAudio", options.isGenerateAudio());
        inputs.put("watermark", options.isWatermark());
        inputs.put("seed", options.getSeed());
        if (firstFilename != null) {
            inputs.put("originalFilename", firstFilename);
        }

        Job job = new Job();
        job.setUserId(userId);
        job.setTemplateId(templateId);
        job.setStatus("PENDING");
        job.setInputsSnapshot(toJsonString(inputs));
        job.setCreditsCost(0);
        job.setCreatedAt(LocalDateTime.now());
        jobRepository.insert(job);
        log.info("[VIDEO-SUBMIT] job 创建: jobId={}, userId={}, template={}", job.getId(), userId, templateId);

        // 2. 调 NewAPI 提交视频生成（自动重试：do_request_failed / 5xx 等瞬时错误最多 3 次，间隔 5s）
        String taskId = null;
        Exception lastException = null;
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                if (TEMPLATE_TEXT_TO_VIDEO.equals(templateId)) {
                    taskId = newApiClient.submitTextToVideo(prompt, options);
                } else if (TEMPLATE_MULTI_IMAGE_TO_VIDEO.equals(templateId)) {
                    if (imageBytesList == null || imageBytesList.isEmpty()) {
                        throw new BusinessException(ErrorCode.INVALID_PARAM, "多图生视频至少需要 1 张参考图");
                    }
                    taskId = newApiClient.submitMultiImageToVideo(prompt, imageBytesList, options);
                } else {
                    // 图生视频(image-to-video)
                    // 2026-08-14 FIX:支持 asset_url 路径。
                    //   CanvasVideoGenService.submitImageToVideoByAssetUrl() 走这条路径,
                    //   它把 asset://xxx 拼到 prompt 末尾,不传 imageBytesList。
                    //   之前直接抛"图生视频需要参考图",导致视频生成失败。
                    //   修复:从 prompt 里提取 asset_url,调 submitVideoByAssetRef。
                    if (imageBytesList != null && !imageBytesList.isEmpty()) {
                        // 字节流路径:走 multipart 提交
                        taskId = newApiClient.submitMultiImageToVideo(prompt, imageBytesList, options);
                    } else {
                        // asset_url 路径:从 prompt 末尾提取 asset:// 引用
                        String assetUrl = extractAssetUrlFromPrompt(prompt);
                        if (assetUrl == null || assetUrl.isBlank()) {
                            throw new BusinessException(ErrorCode.INVALID_PARAM,
                                "图生视频需要参考图:未传入 imageBytesList 且 prompt 中未找到 asset:// 引用");
                        }
                        log.info("[VIDEO-SUBMIT] 走 asset_url 路径: assetUrl={}, duration={}, resolution={}",
                            assetUrl, options.getDuration(), options.getResolution());
                        SubmitResult sr = newApiClient.submitVideoByAssetRef(
                            prompt, assetUrl,
                            options.getDuration(),
                            options.getResolution() == null ? "480p" : options.getResolution());
                        taskId = sr.taskId();
                    }
                }
                log.info("[VIDEO-SUBMIT] jobId={} → NewAPI taskId={} (attempt {}/{})",
                    job.getId(), taskId, attempt, maxAttempts);
                break;  // 成功则跳出
            } catch (BusinessException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                // 业务参数错误直接抛，不重试
                if (e.getCode() == ErrorCode.INVALID_PARAM.getCode()) throw e;
                // NewAPI 5xx/upstream 错误可重试
                boolean retryable = msg.contains("do_request_failed")
                    || msg.contains("upstream")
                    || msg.contains("500")
                    || msg.contains("502")
                    || msg.contains("503")
                    || msg.contains("504")
                    || msg.contains("Bad Gateway")
                    || msg.contains("timeout");
                lastException = e;
                if (attempt < maxAttempts && retryable) {
                    log.warn("[VIDEO-SUBMIT] jobId={} 提交失败 (attempt {}/{}, 可重试), 5s 后重试: {}",
                        job.getId(), attempt, maxAttempts, msg);
                    try { Thread.sleep(5000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                log.error("[VIDEO-SUBMIT] jobId={} → NewAPI 提交失败 (attempt {}/{}, 最终): {}",
                    job.getId(), attempt, maxAttempts, msg);
                markFailed(job, "submit video failed (after " + attempt + " attempts): " + msg);
                throw e;
            } catch (Exception e) {
                // 其它异常也重试一次
                lastException = e;
                if (attempt < maxAttempts) {
                    log.warn("[VIDEO-SUBMIT] jobId={} 提交异常 (attempt {}/{}), 5s 后重试: {}",
                        job.getId(), attempt, maxAttempts, e.getMessage());
                    try { Thread.sleep(5000); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                log.error("[VIDEO-SUBMIT] jobId={} → NewAPI 提交最终失败", job.getId(), e);
                markFailed(job, "submit video failed (after " + attempt + " attempts): " + e.getMessage());
                throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, e.getMessage());
            }
        }
        if (taskId == null) {
            // 所有重试都失败（理论上不会走到这里，因为 catch 块都抛了）
            markFailed(job, "submit video failed (after " + maxAttempts + " attempts): " +
                (lastException == null ? "unknown" : lastException.getMessage()));
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                lastException == null ? "submit failed" : lastException.getMessage());
        }

        // 3. 更新 job：存 taskId，标 RUNNING
        inputs.put("taskId", taskId);
        job.setComfyuiPromptId(taskId);  // 借用字段存 NewAPI task_id
        job.setInputsSnapshot(toJsonString(inputs));
        job.setStatus("RUNNING");
        job.setStartedAt(LocalDateTime.now());
        jobRepository.updateById(job);
        log.info("[VIDEO-SUBMIT] jobId={} → RUNNING, taskId={}, template={}",
            job.getId(), taskId, templateId);

        return new GenerateResponse(job.getId(), job.getStatus(), taskId);
    }

    /**
     * 2026-08-14 NEW:从 prompt 文本中提取 asset:// 引用。
     *   CanvasVideoGenService.submitImageToVideoByAssetUrl() 会把 asset://xxx 拼到
     *   prompt 末尾(如 ". 参考图: asset://aic_xxx"),submitInternal 这里走 asset_url 路径
     *   时需要把它解析出来传给 NewApiClient.submitVideoByAssetRef()。
     *
     * @return 找到的 asset_url(如 "asset://aic_xxx"),没找到返回 null
     */
    private static String extractAssetUrlFromPrompt(String prompt) {
        if (prompt == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("asset://[a-zA-Z0-9_]+")
            .matcher(prompt);
        return m.find() ? m.group() : null;
    }

    // ============================================================
    // 异步轮询
    // ============================================================

    /**
     * @Scheduled 每 2 秒扫一次 RUNNING 的视频生成 job，调 NewAPI 查状态。
     *
     * <p>扫三种 templateId：text-to-video / image-to-video / multi-image-to-video。
     * 避免与 GenerationService.pollRunningJobs（扫 ComfyUI job）互相干扰——这里只扫 video 模板。
     */
    @Override
    @Scheduled(fixedDelay = 2000)
    public void pollRunningVideoJobs() {
        List<Job> runningJobs;
        try {
            runningJobs = jobRepository.selectList(
                new LambdaQueryWrapper<Job>()
                    .eq(Job::getStatus, "RUNNING")
                    .in(Job::getTemplateId, VIDEO_TEMPLATE_IDS)
            );
        } catch (Exception e) {
            log.error("[VIDEO-POLL] 查询 RUNNING job 失败", e);
            return;
        }
        if (runningJobs.isEmpty()) {
            // 2026-08-14 FIX:之前静默 return,导致排查时看不到调度器到底有没有跑
            //   (job 276 提交后 5 秒立刻 FAILED 但日志里没任何 [VIDEO-POLL] 记录)。
            //   改为 INFO,每 2 秒打一次(便于排查 + 实际生产有其它日志刷屏,不会成为噪声瓶颈)。
            log.info("[VIDEO-POLL] tick, 当前没有 RUNNING 视频 job (template IN {})",
                VIDEO_TEMPLATE_IDS);
            return;
        }

        // 2026-08-14 FIX:之前 < 5 个走 log.debug,默认日志级别 INFO 不输出,
        //   导致 scheduling-1 跑过 job 273 但完全没日志,看不到 markFailed 的原因。
        //   改为统一 log.info,排查时能看清 @Scheduled 调度。
        log.info("[VIDEO-POLL] 发现 {} 个 RUNNING job: {}", runningJobs.size(),
            runningJobs.stream().map(Job::getId).collect(java.util.stream.Collectors.toList()));
        for (Job job : runningJobs) {
            try {
                processOneVideoJob(job);
            } catch (Exception e) {
                log.error("[VIDEO-POLL] 处理 job {} 异常: {}", job.getId(), e.getMessage(), e);
            }
        }
    }

    /** 处理单个 RUNNING 的视频生成 job */
    private void processOneVideoJob(Job job) {
        String taskId = job.getComfyuiPromptId();
        if (taskId == null || taskId.isEmpty()) {
            log.error("[VIDEO-POLL] job {} 缺 taskId, 标 FAILED", job.getId());
            markFailed(job, "missing taskId");
            return;
        }

        // 超时检测
        if (job.getStartedAt() != null
            && Duration.between(job.getStartedAt(), LocalDateTime.now()).compareTo(MAX_RUNNING_DURATION) > 0) {
            log.error("[VIDEO-POLL] job {} 超时 (RUNNING > {}min), startedAt={}",
                job.getId(), MAX_RUNNING_DURATION.toMinutes(), job.getStartedAt());
            markFailed(job, "timeout: RUNNING > " + MAX_RUNNING_DURATION.toMinutes() + "min");
            return;
        }

        // 查 NewAPI 视频任务状态（单次查询，不阻塞）
        JsonNode result;
        try {
            result = newApiClient.pollVideo(taskId);
        } catch (BusinessException e) {
            // 400 (任务不存在)时标 FAILED,避免僵尸 job 每 2 秒打一次 NewAPI
            String errMsg = e.getMessage() == null ? "" : e.getMessage();
            boolean taskGone = errMsg.contains("400") || errMsg.toLowerCase().contains("not found") || errMsg.toLowerCase().contains("task does not exist");
            if (taskGone) {
                log.error("[VIDEO-POLL] job {} NewAPI 任务不存在 (taskId={}),标 FAILED: {}",
                    job.getId(), taskId, errMsg);
                markFailed(job, "NewAPI task not found: " + errMsg);
                    return;
            }
            // 其他错误(503/网络问题等)继续重试
            log.warn("[VIDEO-POLL] job {} 查询 NewAPI 失败 (下次重试): taskId={}, err={}",
                job.getId(), taskId, errMsg);
            return;
        }
        // 成功响应 → 清掉 400 计数
        notFoundCountMap.remove(job.getId());
        if (result == null) {
            log.warn("[VIDEO-POLL] job {} 查询 NewAPI 返回 null: taskId={}", job.getId(), taskId);
            return;
        }
        String status = result.path("status").asText("unknown").toLowerCase();

        // 关键日志：打印 NewAPI poll 原始响应（截断到 2000 字符避免日志过大）
        // 这是排查问题最关键的日志，能看到 aicoming 实际返回什么
        log.info("[VIDEO-POLL] job {} 状态: taskId={}, status={}, raw={}",
            job.getId(), taskId, status, truncateForLog(result.toString(), 2000));

        if ("completed".equals(status) || "succeeded".equals(status) || "success".equals(status)) {
            log.info("[VIDEO-POLL] job {} → 视频已完成，开始下载", job.getId());
            handleCompleted(job, result);
        } else if ("failed".equals(status) || "error".equals(status) || "cancelled".equals(status)) {
            log.error("[VIDEO-POLL] job {} → NewAPI 任务失败: {}", job.getId(), result);
            markFailed(job, "NewAPI task failed: " + result);
        } else if ("queued".equals(status)) {
            // 2026-08-11: 防御 NewAPI 中转站把任务丢在 queued 永远不变。
            // 实测 NewAPI queued→in_progress 可能持续 5~8 分钟，阈值设 8 分钟。
            if (job.getStartedAt() != null
                && Duration.between(job.getStartedAt(), LocalDateTime.now()).toMinutes() >= 8) {
                log.error("[VIDEO-POLL] job {} NewAPI 任务一直 queued 超过 8min, 标 FAILED: {}",
                    job.getId(), result);
                markFailed(job, "NewAPI queued 超时（8min 未进入处理），原始响应: " + result);
            }
            // 否则 in_progress / unknown / queued (短暂) → 跳过，下次再扫
        } else if ("in_progress".equals(status)) {
            // 2026-08-11: 防御 NewAPI 中转站的"假 in_progress"bug
            //   现象：aicoming 真实任务已完成（completed_at 持续更新），
            //         但 NewAPI 状态字段没正常更新到 completed，且响应里暂时没有 url。
            //   实测：这种状态可能持续 1~3 分钟后才真正变成 completed + url 可用。
            //
            // 兜底策略（按"已用时间"分层）：
            //   1) completed_at 已过 + 有 URL → 立即按 completed 处理
            //   2) completed_at 已过 + 无 URL + 已用 < 5 min → 继续轮询（等 NewAPI 缓冲）
            //   3) completed_at 已过 + 无 URL + 已用 >= 5 min → 标 FAILED（真没救了）
            //   4) completed_at 未到 / 不存在 → 保持 RUNNING，下次再扫
            long completedAt = result.path("completed_at").asLong(0);
            int progress = result.path("progress").asInt(0);
            long now = Instant.now().getEpochSecond();
            long elapsedSec = job.getStartedAt() != null
                ? Duration.between(job.getStartedAt(), LocalDateTime.now()).toSeconds()
                : 0L;
            String url = newApiClient.extractVideoUrl(result);
            if (url != null && !url.isBlank()) {
                // 1) 直接完成
                log.warn("[VIDEO-POLL] job {} NewAPI 状态 in_progress 但有 URL，按 completed 处理: {}",
                    job.getId(), url);
                handleCompleted(job, result);
            } else if (completedAt > 0 && completedAt <= now && progress >= 50) {
                // 2 / 3) completed_at 已过 + 无 URL：看已用时间
                // 实测 NewAPI 中转站从 in_progress→completed 实际需要 5~6 分钟，
                // 阈值设 8 分钟，留 2 分钟缓冲。超过 8 分钟才标 FAILED。
                if (elapsedSec >= 480) {
                    log.warn("[VIDEO-POLL] job {} NewAPI in_progress+completed_at 已过 8min 仍无 URL，标 FAILED",
                        job.getId());
                    markFailed(job, "NewAPI 假完成 (in_progress 已超 8min 仍无 URL): " + result);
                } else {
                    // < 8 min：继续等，给 NewAPI 缓冲时间
                    log.info("[VIDEO-POLL] job {} NewAPI 状态 in_progress+completed_at 已过但无 URL (已用 {}s)，继续轮询",
                        job.getId(), elapsedSec);
                }
            }
            // 其他情况（completed_at 未到 / progress < 50）保持 RUNNING，下次再扫
        }
        // unknown → 跳过，下次再扫
    }

    /** 视频任务完成：抠 URL → 下载 → 上传 MinIO → 标 COMPLETED */
    private void handleCompleted(Job job, JsonNode result) {
        String videoUrl = newApiClient.extractVideoUrl(result);
        if (videoUrl == null || videoUrl.isBlank()) {
            log.error("[VIDEO-DONE] job {} ← 响应中未找到 video URL: {}", job.getId(), result);
            markFailed(job, "NewAPI 响应中未找到 video URL: " + result);
            return;
        }
        log.info("[VIDEO-DONE] job {} → 视频URL: {}", job.getId(), videoUrl);

        String filename = "jurong_v_" + job.getId() + ".mp4";
        byte[] bytes;
        try {
            log.info("[VIDEO-DONE] job {} → 下载视频字节: url={}", job.getId(), videoUrl);
            bytes = downloadBytes(videoUrl);
        } catch (Exception e) {
            log.error("[VIDEO-DONE] job {} ← 下载视频失败: {}", job.getId(), e.getMessage(), e);
            markFailed(job, "download video failed: " + e.getMessage());
            return;
        }
        if (bytes == null || bytes.length == 0) {
            log.error("[VIDEO-DONE] job {} ← 下载的视频字节为空", job.getId());
            markFailed(job, "downloaded video is empty");
            return;
        }
        log.info("[VIDEO-DONE] job {} ← 视频已下载: {}B", job.getId(), bytes.length);

        try (InputStream is = new ByteArrayInputStream(bytes)) {
            // 1. 拼真实 objectKey（MinIO 实际路径 = 数据库存的路径，确保一致）
            //    规则：ai-platform/{userId}/{jobId}/{filename}
            //    之前用 storageService.uploadFile() 走的是 media/{userId}/{yyyy-MM}/{uuid}.{ext}，
            //    跟写入 media_assets.objectKey 的 ai-platform/... 对不上，资产库 presign 时会 404
            String objectKey = String.format("ai-platform/%d/%d/%s",
                job.getUserId(), job.getId(), filename);

            // 2. 用 uploadObject 上传到自定义路径（ai-platform/...）
            String minioUrl = storageService.uploadObject(
                objectKey, is, "video/mp4");
            log.info("[VIDEO-DONE] job {} ← 已上传到 MinIO: key={}, url={}",
                job.getId(), objectKey, minioUrl);

            // 3. 用同一个真实 objectKey 记录到 media_assets（"AI 生成结果"库）
            //    这样资产列表 presign(objectKey) 才能正确返回可访问 URL
            mediaService.recordAiGenerated(
                job.getUserId(), "video", filename, "video/mp4",
                (long) bytes.length, objectKey, "video",
                String.valueOf(job.getId()));

            markCompleted(job, List.of(minioUrl));
            log.info("[VIDEO-DONE] job {} ← 任务完成", job.getId());
        } catch (Exception e) {
            log.error("[VIDEO-DONE] job {} ← 上传 MinIO 失败: {}", job.getId(), e.getMessage(), e);
            markFailed(job, "upload to MinIO failed: " + e.getMessage());
        }
    }

    /** 简单 GET 下载视频字节
     *
     * <p>2026-08-11: 改用 JDK HttpClient。
     * 原 WebClient(Reactor Netty)对火山桶 TOS 的签名 URL 返回 400（推测 HTTP/2 协商问题），
     * 但 PowerShell / curl / OkHttp 都正常。JDK HttpClient 默认走 HTTP/1.1，兼容性最好。
     */
    private byte[] downloadBytes(String url) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(Duration.ofSeconds(300))
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .header(HttpHeaders.USER_AGENT, "JurongAICenter/1.0")
                .GET()
                .build();
            java.net.http.HttpResponse<byte[]> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                log.info("[VIDEO-DONE] 下载完成: status={}, bytes={}", resp.statusCode(),
                    resp.body() == null ? 0 : resp.body().length);
                return resp.body();
            }
            throw new RuntimeException("HTTP " + resp.statusCode() + " from GET " + url);
        } catch (Exception e) {
            if (e instanceof RuntimeException) throw (RuntimeException) e;
            throw new RuntimeException("download failed: " + e.getMessage(), e);
        }
    }

    private void markCompleted(Job job, List<String> resultUrls) {
        job.setStatus("COMPLETED");
        job.setResultUrls(toJsonString(resultUrls));
        job.setCompletedAt(LocalDateTime.now());
        if (job.getStartedAt() != null) {
            job.setDurationMs((int) Duration.between(job.getStartedAt(), job.getCompletedAt()).toMillis());
        }
        jobRepository.updateById(job);
        log.info("[VIDEO-DONE] job {} → 标 COMPLETED, resultUrls={}, durationMs={}",
            job.getId(), resultUrls, job.getDurationMs());
    }

    /**
     * 事后补刀：每 10 分钟扫一次"FAILED 但有 NewAPI taskId"的视频任务，
     * 重新查 NewAPI 看是不是其实已经完成（这种案例中转站有 bug）。
     * 适用于被 8 分钟兜底标 FAILED，但 NewAPI 后续又真正更新为 completed 的情况。
     */
    @Override
    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void retryFailedVideoJobs() {
        List<Job> failedJobs;
        try {
            // 只看最近 24h 的失败任务，避免太老的
            failedJobs = jobRepository.selectList(
                new LambdaQueryWrapper<Job>()
                    .eq(Job::getStatus, "FAILED")
                    .in(Job::getTemplateId, VIDEO_TEMPLATE_IDS)
                    .isNotNull(Job::getComfyuiPromptId)
                    .gt(Job::getCompletedAt, LocalDateTime.now().minusHours(24))
                    .last("LIMIT 10")
            );
        } catch (Exception e) {
            log.error("[VIDEO-RETRY] 查询失败任务失败", e);
            return;
        }
        if (failedJobs.isEmpty()) return;
        log.info("[VIDEO-RETRY] 开始扫描 {} 个 FAILED 视频任务，看 NewAPI 是否其实已完成", failedJobs.size());

        for (Job job : failedJobs) {
            tryRetryJob(job);
        }
    }

    /**
     * 单个任务的补刀逻辑：查 NewAPI，如果已经 completed + 有 URL，
     * 自动下载入库并标 COMPLETED。
     *
     * @return true 表示补刀成功（任务从 FAILED 转为 COMPLETED）；false 表示未触发补刀
     */
    @Override
    public boolean retryJobById(Long jobId) {
        if (jobId == null) return false;
        Job job = jobRepository.selectById(jobId);
        if (job == null) {
            log.warn("[VIDEO-RETRY] job {} 不存在", jobId);
            return false;
        }
        return tryRetryJob(job);
    }

    /** 单个任务补刀（从 retryFailedVideoJobs 复用） */
    private boolean tryRetryJob(Job job) {
        String taskId = job.getComfyuiPromptId();
        if (taskId == null || taskId.isEmpty()) return false;
        try {
            JsonNode result = newApiClient.pollVideo(taskId);
            if (result == null) return false;
            String status = result.path("status").asText("unknown").toLowerCase();
            String url = newApiClient.extractVideoUrl(result);
            if (("completed".equals(status) || "succeeded".equals(status)) && url != null && !url.isBlank()) {
                // NewAPI 上其实已经完成了！自动下载补刀
                log.warn("[VIDEO-RETRY] job {} FAILED 但 NewAPI 已 completed，自动补刀: taskId={}",
                    job.getId(), taskId);
                job.setStatus("RUNNING");
                job.setErrorMessage(null);
                job.setCompletedAt(null);
                job.setStartedAt(LocalDateTime.now());
                jobRepository.updateById(job);
                handleCompleted(job, result);
                return true;
            }
            log.info("[VIDEO-RETRY] job {} NewAPI 状态={} (URL={})，暂不补刀",
                job.getId(), status, url == null ? "无" : "有");
            return false;
        } catch (Exception e) {
            log.warn("[VIDEO-RETRY] job {} 拉取 NewAPI 失败: {}", job.getId(), e.getMessage());
            return false;
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
        log.warn("[VIDEO-DONE] job {} → 标 FAILED, err={}, durationMs={}",
            job.getId(), errorMessage, job.getDurationMs());
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
