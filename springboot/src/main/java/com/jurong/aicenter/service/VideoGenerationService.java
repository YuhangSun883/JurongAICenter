package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.generation.GenerateResponse;

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
     * 内部：定时轮询运行中的图生视频任务（每 2 秒一次）。
     *
     * <p>只扫 {@code templateId="image-to-video" AND status="RUNNING"} 的 job，
     * 避免与 GenerationService.pollRunningJobs（扫 ComfyUI job）互相干扰。
     */
    void pollRunningVideoJobs();
}
