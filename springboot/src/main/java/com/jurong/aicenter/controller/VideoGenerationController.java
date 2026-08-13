package com.jurong.aicenter.controller;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jurong.aicenter.dto.generation.GenerateResponse;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.VideoGenerationService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 视频生成端点（图生视频）— 走 NewAPI 中转站，绕过 ComfyUI。
 *
 * <p>严格按 Assets-API 参考手册 §5 端到端流程：
 * 上传图片到 proxy → 轮询 asset active → 调 NewAPI /v1/videos → 异步轮询视频任务。
 *
 * <p>端点：
 * <pre>
 *   POST /api/video/image-to-video   (multipart/form-data)
 *     参数：file (图片, 必填) + prompt (提示词, 必填)
 *           + duration (秒, 可选, 默认 4)
 *           + resolution (480P/720P, 可选, 默认 480P)
 *     返回：{jobId, status, promptId}  （status=RUNNING, promptId=NewAPI task_id）
 * </pre>
 *
 * <p>查询/下载复用现有端点：
 * <ul>
 *   <li>{@code GET /api/jobs/{id}} 查任务状态（COMPLETED 时 resultUrls 含 MinIO URL）</li>
 *   <li>{@code GET /api/jobs/{id}/result/{filename}} 302 跳转到 MinIO 签名 URL</li>
 *   <li>{@code DELETE /api/jobs/{id}} 取消/删除任务</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/video")
@RequiredArgsConstructor
public class VideoGenerationController {
    // 2026-08-09 显式 log 字段(替代 @Slf4j,兼容 lombok 不跑的环境)
    private static final Logger log = LoggerFactory.getLogger(VideoGenerationController.class);

    private final VideoGenerationService videoGenerationService;

    /**
     * 图生视频：上传图片 + 提示词 → 异步生成视频。
     *
     * <p>同步返回 jobId（status=RUNNING），前端轮询 GET /api/jobs/{id} 拿结果。
     */
    @PostMapping("/image-to-video")
    public GenerateResponse imageToVideo(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "duration", defaultValue = "4") int duration,
            @RequestParam(value = "resolution", defaultValue = "480p") String resolution) {

        // 入口日志：记录全部关键参数（不含图片字节本身，只记大小和元信息）
        log.info("[I2V-REQ] 收到图生视频请求: userId={}, filename={}, contentType={}, size={}B, "
                + "promptLen={}, duration={}, resolution={}",
            principal == null ? null : principal.id(),
            file == null ? null : file.getOriginalFilename(),
            file == null ? null : file.getContentType(),
            file == null ? 0 : file.getSize(),
            prompt == null ? 0 : prompt.length(),
            duration,
            resolution);

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
            principal.id(),
            fileBytes,
            file.getOriginalFilename(),
            file.getContentType(),
            prompt,
            duration,
            resolution
        );
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
            duration,
            resolution,
            sourceVideoUrl
        );
        log.info("[V2V-REF-REQ] 视频生成视频任务已提交: userId={}, jobId={}, status={}, taskId={}",
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

        int duration = body.duration == null ? 4 : body.duration;
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
}
