package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.generation.GenerateResponse;

import java.util.List;

/**
 * 视频生成服务（图生视频）— 走 NewAPI 中转站，绕过 ComfyUI。
 *
 * <p>严格按 Assets-API 参考手册 §5 端到端流程：
 * <ol>
 *   <li>POST /v1/assets (proxy 8080, multipart) 上传图片 → 拿 asset_url</li>
 *   <li>GET /v1/assets/{id} 轮询到 status=active</li>
 *   <li>POST /v1/videos (NewAPI 3000, JSON body, image_urls 引用 asset_url) 提交视频任务 → 拿 task_id</li>
 *   <li>GET /v1/videos/{task_id} 轮询到 completed → 拿 video URL</li>
 *   <li>下载视频 → 上传 MinIO → 写 job.resultUrls</li>
 *   <li>DELETE /v1/assets/{id} 清理素材（best-effort）</li>
 * </ol>
 *
 * <p>与 {@link GenerationService} 的关系：
 * <ul>
 *   <li>共用 jobs 表，用 {@code templateId="image-to-video"} 标记本服务创建的 job</li>
 *   <li>{@code comfyuiPromptId} 字段借用存 NewAPI task_id（语义稍微偏移，加注释说明）</li>
 *   <li>查询 / 下载 / 删除 job 复用 {@link GenerationService} 的方法</li>
 *   <li>本服务只负责 submit + 异步轮询</li>
 * </ul>
 *
 * <p>异步策略：用 @Scheduled（与 GenerationService.pollRunningJobs 风格一致），
 * 重启后能从 DB 恢复轮询。
 */
public interface VideoGenerationService {

    /**
     * 提交图生视频任务。
     *
     * <p>同步部分：创建 job → 上传 asset → 轮询 asset active → 提交视频任务 → 标 job RUNNING。
     * 异步部分：由 {@link #pollRunningVideoJobs()} 轮询 NewAPI 视频任务状态。
     *
     * @param userId      当前用户 ID
     * @param fileBytes   图片二进制（jpg/png/webp/gif）
     * @param filename    原始文件名（aicoming 用来识别格式）
     * @param contentType MIME 类型，如 image/png
     * @param prompt      用户提示词（原样传，不做 enhance）
     * @param duration    时长（秒），默认 4
     * @param resolution  分辨率，如 480P / 720P
     * @return GenerateResponse（jobId / status="RUNNING" / promptId=NewAPI task_id）
     */
    GenerateResponse submitImageToVideo(Long userId,
                                        byte[] fileBytes, String filename, String contentType,
                                        String prompt, int duration, String resolution);

    /**
     * 2026-08-11 新增：使用预上传到 NewAPI 素材库的 asset_url 提交图生视频任务。
     * 适用场景：换装总图已先上传到 NewAPI 素材库 (asset://xxx)，此处直接引用，
     * 期望绕过 NewAPI 上游的 InputImageSensitiveContentDetected 人脸检测。
     *
     * @param userId               用户 ID
     * @param preUploadedAssetUrl  已上传到 NewAPI 素材库的 asset_url (asset://aic_xxx)
     * @param prompt               用户提示词
     * @param duration             时长（秒），默认 4
     * @param resolution           分辨率，如 480P
     * @return GenerateResponse
     */
    GenerateResponse submitImageToVideoByAssetUrl(Long userId,
                                                   String preUploadedAssetUrl,
                                                   String prompt, int duration, String resolution);

    /**
     * 2026-08-13 新增:视频生成视频(多图参考换物)
     *
     * <p>画布场景:左视频节点(已生成的原视频) + 右视频节点(用户上传 N 张参考图:衣服/商品等)
     * 后端把参考图横拼成一张大图,上传到 aicoming 素材库(asset_url 路径)避免人脸检测,
     * 用 NewAPI 生成保持原视频动作 + 替换服装/商品的新视频。</p>
     *
     * @param userId              用户 ID
     * @param referenceImageBytes N 张参考图字节流(1-6 张)
     * @param referenceFilenames  对应文件名(可空)
     * @param referenceMimeTypes  对应 mime(可空)
     * @param prompt              提示词(描述"保持原视频动作,换上参考图的XX")
     * @param duration            秒数,默认 4
     * @param resolution          "480p"/"720p",默认 480p
     * @param sourceVideoUrl      原视频 URL(参考用,注入到 prompt 让模型知道是哪个原视频)
     * @return GenerateResponse
     */
    GenerateResponse submitVideoFromVideoWithReferences(Long userId,
                                                         List<byte[]> referenceImageBytes,
                                                         List<String> referenceFilenames,
                                                         List<String> referenceMimeTypes,
                                                         String prompt,
                                                         int duration,
                                                         String resolution,
                                                         String sourceVideoUrl);

    /**
     * 内部：定时轮询运行中的图生视频任务（每 2 秒一次）。
     *
     * <p>只扫 {@code templateId="image-to-video" AND status="RUNNING"} 的 job，
     * 避免与 GenerationService.pollRunningJobs（扫 ComfyUI job）互相干扰。
     */
    void pollRunningVideoJobs();
}
