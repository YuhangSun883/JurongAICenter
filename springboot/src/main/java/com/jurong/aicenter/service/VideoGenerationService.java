package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.generation.GenerateResponse;
import com.jurong.aicenter.dto.video.VideoOptions;

import java.util.List;

import java.util.List;

/**
 * 视频生成服务（文字 / 图片 / 多图）— 全部走 NewAPI 中转站，绕过 ComfyUI。
 *
 * <p>2026-08-11 重构：所有视频生成都从 ComfyUI 切换到 NewAPI：
 * <ul>
 *   <li>文生视频：NewAPI /v1/videos（multipart，占位图）</li>
 *   <li>图生视频：NewAPI /v1/videos（multipart，input_reference）</li>
 *   <li>多图生视频：NewAPI /v1/videos（multipart，多个 input_reference）</li>
 * </ul>
 *
 * <p>异步轮询策略：用 @Scheduled 每 2 秒扫一次 RUNNING 的 job，
 * 调 NewAPI 查状态，完成后下载 → MinIO → 标 COMPLETED。
 *
 * <p>job 字段借用约定：
 * <ul>
 *   <li>{@code templateId} 标记类型（text-to-video / image-to-video / multi-image-to-video）</li>
 *   <li>{@code comfyuiPromptId} 存 NewAPI task_id（字段名借用，语义偏移）</li>
 *   <li>{@code inputsSnapshot} 存 JSON 输入参数</li>
 *   <li>{@code resultUrls} 存 MinIO URL 数组 JSON</li>
 * </ul>
 */
public interface VideoGenerationService {

    /** templateId：文生视频 */
    String TEMPLATE_TEXT_TO_VIDEO = "text-to-video";
    /** templateId：图生视频（兼容旧名 image-to-video） */
    String TEMPLATE_IMAGE_TO_VIDEO = "image-to-video";
    /** templateId：多图生视频 */
    String TEMPLATE_MULTI_IMAGE_TO_VIDEO = "multi-image-to-video";

    /**
     * 提交图生视频任务（兼容旧 API 行为）。
     *
     * @param userId      当前用户 ID
     * @param fileBytes   图片二进制
     * @param filename    原始文件名
     * @param contentType MIME 类型
     * @param prompt      用户提示词
     * @param duration    时长（秒），默认 4
     * @param resolution  分辨率，如 480p / 720p
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
     * @param userId  当前用户 ID
     * @param prompt  用户提示词
     * @param options 视频参数（duration / resolution / ratio / audio / watermark / seed）
     * @return GenerateResponse（jobId / status="RUNNING" / promptId=NewAPI task_id）
     */
    GenerateResponse submitTextToVideo(Long userId, String prompt, VideoOptions options);

    /**
     * 提交多图生视频任务（NewAPI 直调，1-4 张参考图）。
     *
     * @param userId          当前用户 ID
     * @param prompt          用户提示词
     * @param imageBytesList  多张图片字节（按顺序）
     * @param options         视频参数
     * @return GenerateResponse
     */
    GenerateResponse submitMultiImageToVideo(Long userId, String prompt,
                                             List<byte[]> imageBytesList, VideoOptions options);

    /**
     * 内部：定时轮询所有 RUNNING 的视频生成 job（每 2 秒一次）。
     * 涵盖 text-to-video / image-to-video / multi-image-to-video 三种 templateId。
     */
    void pollRunningVideoJobs();

    /**
     * 内部：事后补刀（每 10 分钟一次）。扫 FAILED 但 NewAPI 实际已完成的视频任务，
     * 适用于 NewAPI 中转站状态更新延迟的 bug。
     */
    void retryFailedVideoJobs();

    /**
     * 手动补刀单个任务：查 NewAPI 看是不是其实已经完成了。
     * 如果是，自动下载入库并标 COMPLETED。
     *
     * @param jobId 任务 ID
     * @return true 表示补刀成功（任务从 FAILED 转为 COMPLETED）
     */
    boolean retryJobById(Long jobId);
}
