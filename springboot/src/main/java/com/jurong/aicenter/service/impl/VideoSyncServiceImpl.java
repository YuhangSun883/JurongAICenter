package com.jurong.aicenter.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurong.aicenter.client.NewApiClient;
import com.jurong.aicenter.entity.Job;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.JobRepository;
import com.jurong.aicenter.service.MediaService;
import com.jurong.aicenter.service.StorageService;
import com.jurong.aicenter.service.VideoSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频同步服务实现
 *
 * 流程：
 *   1. 验证 job 归属（userId 匹配）
 *   2. 调 NewApiClient.waitForVideo 等任务完成
 *   3. 提取 video URL
 *   4. 下载视频字节流
 *   5. 上传到 MinIO
 *   6. 写回 job.resultUrls
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoSyncServiceImpl implements VideoSyncService {

    private final NewApiClient newApiClient;
    private final JobRepository jobRepository;
    private final StorageService storageService;
    private final MediaService mediaService;

    @Override
    public Job syncVideoFromNewApi(Long userId, Long jobId, String newApiTaskId) {
        if (newApiTaskId == null || newApiTaskId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "newApiTaskId 不能为空");
        }

        // 1. 鉴权（确保 job 属于当前用户）
        Job job = jobRepository.selectById(jobId);
        if (job == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Job 不存在: " + jobId);
        }
        if (!job.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问此 Job");
        }

        // 2. 同步视频（内部已完成 recordAiGenerated）
        String url = downloadAndUpload(userId, jobId, newApiTaskId);

        // 3. 写回 job（追加到 resultUrls，并标 COMPLETED）
        List<String> urls = parseResultUrls(job.getResultUrls());
        if (!urls.contains(url)) {
            urls.add(url);
        }
        job.setResultUrls(toJsonArray(urls));
        // 恢复被误判为 FAILED 的 job：直接标 COMPLETED
        if (!"COMPLETED".equalsIgnoreCase(job.getStatus())) {
            job.setStatus("COMPLETED");
            if (job.getStartedAt() != null) {
                job.setCompletedAt(java.time.LocalDateTime.now());
                job.setDurationMs((int) java.time.Duration.between(
                    job.getStartedAt(), job.getCompletedAt()).toMillis());
            } else {
                job.setCompletedAt(java.time.LocalDateTime.now());
            }
        }
        jobRepository.updateById(job);

        log.info("Video synced to MinIO: userId={}, jobId={}, newApiTaskId={}, url={}",
            userId, jobId, newApiTaskId, url);

        return job;
    }

    @Override
    public String syncVideoStandalone(Long userId, String newApiTaskId) {
        if (newApiTaskId == null || newApiTaskId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "newApiTaskId 不能为空");
        }
        // 独立模式：生成一个伪 jobId 用作目录
        long pseudoJobId = System.currentTimeMillis();
        return downloadAndUpload(userId, pseudoJobId, newApiTaskId);
    }

    /**
     * 核心逻辑：NewAPI 等任务 → 拿 URL → 下载 → 上传 MinIO
     */
    private String downloadAndUpload(Long userId, Long jobId, String newApiTaskId) {
        // 1. 等 NewAPI 任务完成
        JsonNode result = newApiClient.waitForVideo(newApiTaskId, 600);

        // 2. 提取视频 URL
        String videoUrl = newApiClient.extractVideoUrl(result);
        if (videoUrl == null || videoUrl.isBlank()) {
            throw new BusinessException(ErrorCode.NEWAPI_VIDEO_URL_MISSING,
                "NewAPI 响应中未找到 video URL: " + result.toString());
        }
        log.info("NewAPI video URL: {}", videoUrl);

        // 3. 下载视频字节
        String filename = "jurong_" + newApiTaskId.substring(Math.max(0, newApiTaskId.length() - 8)) + ".mp4";
        byte[] bytes = downloadBytes(videoUrl);
        if (bytes == null || bytes.length == 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载视频为空");
        }
        log.info("Downloaded video: {} bytes", bytes.length);

        // 4. 上传 MinIO（用 uploadAiMedia 拿真实 objectKey，与 media_assets 对齐）
        try (InputStream is = new ByteArrayInputStream(bytes)) {
            StorageService.UploadResult up = storageService.uploadAiMedia(
                userId, "mp4", is, "video/mp4");
            String objectKey = up.objectKey();
            String url = up.url();
            log.info("Uploaded to MinIO: objectKey={}, url={}", objectKey, url);

            // 5. 写入 media_assets（AI 生成结果库），独立模式下用伪 jobId 作 sourceTaskId
            try {
                mediaService.recordAiGenerated(
                    userId, "video", filename, "video/mp4",
                    (long) bytes.length, objectKey, "video", String.valueOf(jobId));
                log.info("Recorded AI media: objectKey={}", objectKey);
            } catch (Exception e) {
                log.warn("recordAiGenerated failed: {}", e.getMessage());
            }
            return url;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "上传 MinIO 失败: " + e.getMessage());
        }
    }

    /** 简单 GET 下载视频字节（火山 TOS 对 WebClient/ReactorNetty UA 返回 400，改用 JDK HttpClient 强制带 Chrome UA） */
    private byte[] downloadBytes(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(300))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .GET()
                .build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            log.info("Download video: status={}, bytes={}", resp.statusCode(), resp.body() == null ? 0 : resp.body().length);
            if (resp.statusCode() / 100 != 2) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "下载视频失败: HTTP " + resp.statusCode());
            }
            return resp.body();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Download video failed: {} - {}", url, e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载视频失败: " + e.getMessage());
        }
    }

    private List<String> parseResultUrls(String json) {
        List<String> list = new ArrayList<>();
        if (json == null || json.isBlank()) return list;
        try {
            // 简单解析 ["url1","url2"] 格式
            json = json.trim();
            if (json.startsWith("[") && json.endsWith("]")) {
                String inner = json.substring(1, json.length() - 1).trim();
                if (inner.isEmpty()) return list;
                for (String s : inner.split(",")) {
                    String u = s.trim().replaceAll("^\"|\"$", "");
                    if (!u.isEmpty()) list.add(u);
                }
            }
        } catch (Exception e) {
            log.warn("parseResultUrls failed: {}", e.getMessage());
        }
        return list;
    }

    private String toJsonArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(list.get(i).replace("\"", "\\\"")).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }
}
