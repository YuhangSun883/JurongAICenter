package com.jurong.aicenter.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jurong.aicenter.dto.generation.GenerateResponse;
import com.jurong.aicenter.dto.job.JobResponse;
import com.jurong.aicenter.dto.video.VideoOptions;
import com.jurong.aicenter.entity.Job;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.JobRepository;
import com.jurong.aicenter.repository.MediaAssetRepository;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.VideoGenerationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频生成端点 — 全部走 NewAPI 中转站（2026-08-11 重构：移除 ComfyUI 流程）。
 *
 * <p>端点：
 * <pre>
 *   POST /api/videos/text-to-video          (JSON body, 文字生成视频 → NewAPI)
 *   POST /api/videos/image-to-video         (multipart/form-data, 图片生成视频 → NewAPI)
 *   POST /api/videos/multi-image-to-video   (multipart/form-data, 多图生成视频 → NewAPI)
 *   GET  /api/videos                        (列出本用户的视频生成任务)
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class VideoGenerationController {

    private final VideoGenerationService videoGenerationService;
    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final MediaAssetRepository mediaAssetRepository;

    /**
     * 文字生成视频 — NewAPI 中转站。
     *
     * <p>2026-08-11：移除 ComfyUI 流程，统一走 NewAPI。
     * 请求体：{script, model, aspectRatio, resolution, duration, audioMode, seed?}
     */
    @PostMapping("/text-to-video")
    public GenerateResponse textToVideo(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestBody Map<String, Object> request) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);

        String script = (String) request.getOrDefault("script", "");
        if (script == null || script.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "script 不能为空");
        }

        String frontModel = (String) request.getOrDefault("model", "Seedance-2.0-VIP");
        String aspectRatio = (String) request.getOrDefault("aspectRatio", "16:9");
        // aicoming 只接受小写 resolution（480p/720p/1080p/4k）
        String resolution = (String) request.getOrDefault("resolution", "720p");
        int duration = request.get("duration") instanceof Number n ? n.intValue() : 4;
        String audioMode = (String) request.getOrDefault("audioMode", "mute");
        Long seed = request.get("seed") instanceof Number ns ? ns.longValue() : 0L;

        // 映射前端 videoModel 枚举到后端 model ID
        // 前端三档：Seedance-2.0-VIP / Seedance-2.0-Fast-VIP / Seedance-2.0-Mini-VIP
        // 后端 NewAPI 当前只支持 doubao-seedance-2.0 基础模型（NewAPI 文档 §2）
        String backendModel = mapFrontendModel(frontModel);

        // 组装 VideoOptions
        VideoOptions options = VideoOptions.builder()
            .duration(mapToValidDuration(duration))
            .resolution(resolution)
            .ratio(aspectRatio)
            .generateAudio(!"mute".equalsIgnoreCase(audioMode))
            .watermark(false)
            .returnLastFrame(true)
            .seed(seed == null ? 0L : seed)
            .model(backendModel)
            .build();

        log.info("[T2V-REQ] userId={}, frontModel={}, backendModel={}, duration={}, resolution={}, "
                + "ratio={}, audioMode={}, seed={}",
            principal.id(), frontModel, backendModel, options.getDuration(), options.getResolution(),
            options.getRatio(), audioMode, options.getSeed());

        GenerateResponse resp = videoGenerationService.submitTextToVideo(principal.id(), script, options);
        log.info("[T2V-REQ] 文生视频任务已提交: userId={}, jobId={}, status={}, taskId={}",
            principal.id(), resp.getJobId(), resp.getStatus(), resp.getComfyuiPromptId());
        return resp;
    }

    /**
     * 图生视频：上传图片 + 提示词 → NewAPI 中转站 → 异步生成视频。
     */
    @PostMapping("/image-to-video")
    public GenerateResponse imageToVideo(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "duration", defaultValue = "4") int duration,
            @RequestParam(value = "resolution", defaultValue = "480p") String resolution) {
        log.info("[I2V-REQ] 收到图生视频请求: userId={}, filename={}, contentType={}, size={}B, promptLen={}, duration={}, resolution={}",
            principal == null ? null : principal.id(),
            file == null ? null : file.getOriginalFilename(),
            file == null ? null : file.getContentType(),
            file == null ? 0 : file.getSize(),
            prompt == null ? 0 : prompt.length(),
            duration, resolution);

        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (file == null || file.isEmpty()) {
            log.warn("[I2V-REQ] 文件为空: userId={}", principal.id());
            throw new BusinessException(ErrorCode.INVALID_PARAM, "file 不能为空");
        }
        if (prompt == null || prompt.isBlank()) {
            log.warn("[I2V-REQ] prompt 为空: userId={}", principal.id());
            throw new BusinessException(ErrorCode.INVALID_PARAM, "prompt 不能为空");
        }

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            log.error("[I2V-REQ] 读取文件失败: userId={}, filename={}, err={}",
                principal.id(), file.getOriginalFilename(), e.getMessage(), e);
            throw new BusinessException(ErrorCode.INVALID_PARAM, "读取文件失败: " + e.getMessage());
        }
        GenerateResponse resp = videoGenerationService.submitImageToVideo(
            principal.id(), fileBytes, file.getOriginalFilename(), file.getContentType(),
            prompt, mapToValidDuration(duration), resolution);
        log.info("[I2V-REQ] 图生视频任务已提交: userId={}, jobId={}, status={}, taskId={}",
            principal.id(), resp.getJobId(), resp.getStatus(), resp.getComfyuiPromptId());
        return resp;
    }

    /**
     * 2026-08-13 新增:视频生成视频(多图参考换物)
     *
     * <p>画布场景:左边是已生成的"原视频"节点,右边是用户上传 3 张参考图(衣服/商品)的"目标视频"节点。
     * 后端把 3 张参考图横拼成一张大图上传到素材库(asset_url 绕过真人检测),用 NewAPI 生成
     * 保持原视频动作 + 换上参考图服装/商品的新视频。</p>
     *
     * <p>请求:multipart/form-data
     * <ul>
     *   <li>files[]: 3 张参考图(衣服/商品等)</li>
     *   <li>prompt: 提示词,描述"保持原视频动作,换上XX"</li>
     *   <li>duration: 秒数(默认 4)</li>
     *   <li>resolution: "480p"/"720p"(默认 480p)</li>
     *   <li>sourceVideoUrl: 原视频 URL(参考用,可空)</li>
     * </ul>
     * </p>
     */
    @PostMapping("/from-video-with-references")
    public GenerateResponse fromVideoWithReferences(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "duration", defaultValue = "4") int duration,
            @RequestParam(value = "resolution", defaultValue = "480p") String resolution,
            @RequestParam(value = "sourceVideoUrl", required = false) String sourceVideoUrl) {

        log.info("[V2V-REF-REQ] 收到视频生成视频(多图参考)请求: userId={}, files={}, promptLen={}, "
                + "duration={}, resolution={}, sourceVideo={}",
            principal == null ? null : principal.id(),
            files == null ? 0 : files.size(),
            prompt == null ? 0 : prompt.length(),
            duration, resolution,
            sourceVideoUrl == null ? "(null)" : sourceVideoUrl);

        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (files == null || files.isEmpty()) {
            log.warn("[V2V-REF-REQ] 参考图列表为空: userId={}", principal.id());
            throw new BusinessException(ErrorCode.INVALID_PARAM, "files 不能为空(至少 1 张参考图)");
        }
        if (files.size() > 6) {
            log.warn("[V2V-REF-REQ] 参考图超过 6 张: userId={}, count={}", principal.id(), files.size());
            throw new BusinessException(ErrorCode.INVALID_PARAM, "参考图最多 6 张");
        }
        if (prompt == null || prompt.isBlank()) {
            log.warn("[V2V-REF-REQ] prompt 为空: userId={}", principal.id());
            throw new BusinessException(ErrorCode.INVALID_PARAM, "prompt 不能为空");
        }

        // 读取所有参考图字节
        List<byte[]> fileBytesList = new java.util.ArrayList<>();
        List<String> filenames = new java.util.ArrayList<>();
        List<String> mimeTypes = new java.util.ArrayList<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            try {
                fileBytesList.add(f.getBytes());
                filenames.add(f.getOriginalFilename());
                mimeTypes.add(f.getContentType());
            } catch (IOException e) {
                log.error("[V2V-REF-REQ] 读取参考图失败: filename={}, err={}",
                    f.getOriginalFilename(), e.getMessage());
                throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "读取参考图失败: " + f.getOriginalFilename());
            }
        }
        if (fileBytesList.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "所有参考图均为空");
        }

        GenerateResponse resp = videoGenerationService.submitVideoFromVideoWithReferences(
            principal.id(),
            fileBytesList,
            filenames,
            mimeTypes,
            prompt,
            mapToValidDuration(duration),
            resolution,
            sourceVideoUrl
        );
        log.info("[V2V-REF-REQ] 视频生成视频任务已提交: userId={}, jobId={}, status={}, taskId={}",
            principal.id(), resp.getJobId(), resp.getStatus(), resp.getComfyuiPromptId());
        return resp;
    }

    /**
     * 2026-08-15 新增:多图生视频(references[] 格式) — 聚融中转站接口手册 v3.0 §6.4。
     *
     * <p>与 /multi-image-to-video 的区别:后者 multipart 直传图片字节,
     * 本端点先把每张图上传到素材库(:8090)拿 asset_url,再用 references[] 格式提交,
     * 绕过人脸检测,且每张图可设 alias 让 prompt 引用不同角色/场景。
     *
     * <p>请求:multipart/form-data
     * <ul>
     *   <li>files[]: 1-4 张参考图</li>
     *   <li>prompt: 提示词(可用 alias 引用不同图,如"Alice 走向 Bob")</li>
     *   <li>aliases: 可选,逗号分隔的别名列表(如 "Alice,Bob"),不传时自动生成 image_1/image_2/...</li>
     *   <li>duration: 秒数(默认 4)</li>
     *   <li>resolution: "480p"/"720p"(默认 480p)</li>
     * </ul>
     * </p>
     */
    @PostMapping("/multi-image-to-video-by-references")
    public GenerateResponse multiImageToVideoByReferences(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "aliases", required = false) String aliasesStr,
            @RequestParam(value = "duration", defaultValue = "4") int duration,
            @RequestParam(value = "resolution", defaultValue = "480p") String resolution) {

        log.info("[MI2V-REF-REQ] 收到多图生视频(references[])请求: userId={}, files={}, aliases={}, "
                + "promptLen={}, duration={}, resolution={}",
            principal == null ? null : principal.id(),
            files == null ? 0 : files.size(),
            aliasesStr,
            prompt == null ? 0 : prompt.length(),
            duration, resolution);

        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "files 不能为空(至少 1 张参考图)");
        }
        if (files.size() > 4) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "参考图最多 4 张");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "prompt 不能为空");
        }

        // 读取所有图片字节
        List<byte[]> fileBytesList = new java.util.ArrayList<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            try {
                fileBytesList.add(f.getBytes());
            } catch (IOException e) {
                throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "读取参考图失败: " + f.getOriginalFilename());
            }
        }
        if (fileBytesList.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "所有参考图均为空");
        }

        // 解析 aliases(逗号分隔)
        List<String> aliases = null;
        if (aliasesStr != null && !aliasesStr.isBlank()) {
            aliases = java.util.Arrays.stream(aliasesStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toList());
        }

        GenerateResponse resp = videoGenerationService.submitMultiImageToVideoByReferences(
            principal.id(), fileBytesList, aliases, prompt, mapToValidDuration(duration), resolution);
        log.info("[MI2V-REF-REQ] 多图生视频(references[])任务已提交: userId={}, jobId={}, status={}, taskId={}",
            principal.id(), resp.getJobId(), resp.getStatus(), resp.getComfyuiPromptId());
        return resp;
    }

    /**
     * 2026-08-13 新增:视频生成视频(URL 列表版)
     *
     * <p>与 /from-video-with-references 同功能,但接收的是参考图的公网 URL 列表(而非 multipart),
     * 后端下载 URL → 拼接上传素材库 → 提交 NewAPI。</p>
     *
     * <p>画布双视频节点连通场景:
     * <ul>
     *   <li>左视频节点的 resultUrl → sourceVideoUrl</li>
     *   <li>右视频节点上游 3 张 image 节点的 resultUrl → referenceImageUrls</li>
     *   <li>右视频节点的 prompt → prompt</li>
     * </ul>
     * </p>
     *
     * <p>请求:application/json
     * <pre>
     * {
     *   "sourceVideoUrl": "http://...",
     *   "referenceImageUrls": ["http://...", "http://...", "http://..."],
     *   "prompt": "保持原视频动作,换上参考图的衣服",
     *   "duration": 4,
     *   "resolution": "480p"
     * }
     * </pre>
     * </p>
     */
    @PostMapping("/from-video-with-reference-urls")
    public GenerateResponse fromVideoWithReferenceUrls(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @org.springframework.web.bind.annotation.RequestBody VideoFromVideoRequest body) {

        log.info("[V2V-URL-REQ] 收到视频生成视频(URL 列表版)请求: userId={}, refUrls={}, sourceVideoUrl={}, "
                + "promptLen={}, duration={}, resolution={}",
            principal == null ? null : principal.id(),
            body.referenceImageUrls == null ? 0 : body.referenceImageUrls.size(),
            body.sourceVideoUrl == null ? "(null)" : "set",
            body.prompt == null ? 0 : body.prompt.length(),
            body.duration, body.resolution);

        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (body.referenceImageUrls == null || body.referenceImageUrls.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "referenceImageUrls 不能为空");
        }
        if (body.referenceImageUrls.size() > 6) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "参考图最多 6 张");
        }
        if (body.prompt == null || body.prompt.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "prompt 不能为空");
        }

        // 下载所有 URL
        List<byte[]> fileBytesList = new java.util.ArrayList<>();
        List<String> filenames = new java.util.ArrayList<>();
        List<String> mimeTypes = new java.util.ArrayList<>();
        for (String url : body.referenceImageUrls) {
            if (url == null || url.isBlank()) continue;
            try {
                java.net.URI uri = java.net.URI.create(url);
                java.net.URL u = uri.toURL();
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                int code = conn.getResponseCode();
                if (code != 200) {
                    log.warn("[V2V-URL-REQ] URL 下载失败: url={}, code={}", url, code);
                    conn.disconnect();
                    continue;
                }
                java.io.InputStream in = conn.getInputStream();
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
                in.close();
                conn.disconnect();

                byte[] bytes = baos.toByteArray();
                if (bytes.length == 0) {
                    log.warn("[V2V-URL-REQ] URL 字节为 0: url={}", url);
                    continue;
                }

                fileBytesList.add(bytes);
                // 从 URL 推断文件名
                String path = uri.getPath();
                String filename = path != null && path.contains("/")
                    ? path.substring(path.lastIndexOf('/') + 1)
                    : "ref.jpg";
                if (!filename.contains(".")) filename = filename + ".jpg";
                filenames.add(filename);
                // mime 推断
                String mime = "image/jpeg";
                if (filename.toLowerCase().endsWith(".png")) mime = "image/png";
                else if (filename.toLowerCase().endsWith(".webp")) mime = "image/webp";
                mimeTypes.add(mime);
                log.info("[V2V-URL-REQ] URL 下载成功: url={}, bytes={}, mime={}", url, bytes.length, mime);
            } catch (Exception e) {
                log.error("[V2V-URL-REQ] URL 下载异常: url={}, err={}", url, e.getMessage());
            }
        }

        if (fileBytesList.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "所有参考图 URL 下载都失败(请检查 URL 是否公网可访问)");
        }

        int duration = mapToValidDuration(body.duration == null ? 4 : body.duration);
        String resolution = body.resolution == null || body.resolution.isBlank() ? "480p" : body.resolution();

        GenerateResponse resp = videoGenerationService.submitVideoFromVideoWithReferences(
            principal.id(),
            fileBytesList,
            filenames,
            mimeTypes,
            body.prompt,
            duration,
            resolution,
            body.sourceVideoUrl
        );
        log.info("[V2V-URL-REQ] 视频生成视频任务已提交: userId={}, jobId={}, status={}, taskId={}",
            principal.id(), resp.getJobId(), resp.getStatus(), resp.getComfyuiPromptId());
        return resp;
    }

    /**
     * 2026-08-13 新增:视频生成视频(URL 列表版)请求 DTO
     */
    public record VideoFromVideoRequest(
        String sourceVideoUrl,
        List<String> referenceImageUrls,
        String prompt,
        Integer duration,
        String resolution
    ) {}

    /**
     * 映射前端 videoModel 枚举到后端 NewAPI model ID。
     * 前端三档：Seedance-2.0-VIP / Seedance-2.0-Fast-VIP / Seedance-2.0-Mini-VIP
     * 后端当前只支持 doubao-seedance-2.0。
     */
    private String mapFrontendModel(String frontModel) {
        if (frontModel == null || frontModel.isBlank()) return "doubao-seedance-2.0";
        return switch (frontModel) {
            case "Seedance-2.0-VIP", "Seedance-2.0-Fast-VIP", "Seedance-2.0-Mini-VIP" -> "doubao-seedance-2.0";
            default -> frontModel;  // 透传，让 NewAPI 报错时能看到原始值
        };
    }

    /**
     * AI 视频前台最大支持 15 秒；超过 15 秒统一按 15 秒提交，低于 4 秒按 4 秒提交。
     */
    private int mapToValidDuration(int duration) {
        if (duration < 4) return 4;
        if (duration > 15) return 15;
        return duration;
    }
}
