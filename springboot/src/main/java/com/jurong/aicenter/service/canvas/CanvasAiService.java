package com.jurong.aicenter.service.canvas;

import java.util.Map;

/**
 * 画布 AI 生成服务接口。
 *
 * 三个方法对应画布三种节点的"生成"能力：
 *   - polishText       : 文本节点 → 调 LLM
 *   - generateImage    : 图片节点 → 调 ComfyUI workflow 01
 *   - generateVideo    : 视频节点 → 调 ComfyUI workflow 03
 *
 * 所有方法必须返回真实的 API 调用结果（不允许 mock / 占位）。
 */
public interface CanvasAiService {

    /**
     * 文本润色 / 生成（调 LLM）
     * @param userPrompt      用户原始输入
     * @param upstreamContent 上游节点输出（可空，作为上下文拼到 user 输入里）
     * @return LLM 真实输出
     */
    String polishText(String userPrompt, String upstreamContent);

    /**
     * 生成图片（调 ComfyUI text-to-image workflow）
     * @param prompt          最终拼好的 prompt（含上游上下文）
     * @param upstreamContent 上游节点输出（可空）
     * @return 图片公网 URL（MinIO presigned 或 ComfyUI /view 直链）
     */
    String generateImage(String prompt, String upstreamContent);

    /**
     * 2026-08-11 新增:图生图(调 ComfyUI image-to-image workflow + NewAPI /v1/images/edits)
     * 用于:用户上游是图片节点(抽帧总图/换装结果),在下游 image 节点输入描述修改图片。
     *
     * @param imageUrl        上游图片的公网 URL(会被上传到 ComfyUI input 目录)
     * @param prompt          用户的转换描述,如"把人物改成三视图"、"换沐浴露瓶"
     * @param upstreamContent 上游节点输出(可空,作为风格参考)
     * @return 新图片的公网 URL(MinIO)
     */
    String editImage(String imageUrl, String prompt, String upstreamContent);

    /**
     * 生成视频（调 ComfyUI image-to-video workflow + NewAPI 等结果）
     * @param prompt          最终 prompt
     * @param imageUrl        上游图片节点产物的公网 URL（单图，向后兼容）
     * @param upstreamContent 上游节点输出（可空）
     * @return 视频公网 URL
     */
    String generateVideo(String prompt, String imageUrl, String upstreamContent);

    /**
     * 2026-08-11 新增:多图版生成视频(支持三视图+换装帧图+其他参考图作为 image_urls 传给 NewAPI)
     *
     * <p>应用场景:视频节点上游连多个 image 节点(三视图角色参考 + 换装服装参考 + 其他),
     * 把所有 URL 传给 NewAPI /v1/videos 的 image_urls 参数,实现"多参考图"生视频。</p>
     *
     * @param prompt          最终 prompt(分镜描述 + 口播文案)
     * @param imageUrls       上游所有 image 节点产物的 URL 列表(可空,空则走纯文生视频)
     * @param upstreamContent 上游 text 节点输出(可空,作为风格参考)
     * @return 视频公网 URL
     */
    String generateVideoMulti(String prompt, java.util.List<String> imageUrls, String upstreamContent);

    /**
     * 合并用户输入与上游润色文案，生成最终 prompt。
     *
     * <p>规则：以用户输入的具体细节（颜色/动作/天气/场景等）为主，
     * 上游润色文案提供氛围/风格/细节描写参考；冲突时以用户输入为准。</p>
     *
     * <p>用于视频/图片节点的最终 prompt 智能合并，
     * 避免上游文案与用户描述冲突导致 AI 困惑。</p>
     *
     * @param userPrompt      用户在画布下方输入的提示词（主）
     * @param upstreamContent 上游节点的润色文案（参考，可空）
     * @return 合并后的最终 prompt
     */
    String mergePrompts(String userPrompt, String upstreamContent);

    /**
     * 预估积分消耗
     */
    Integer estimateCredits(String type, Map<String, Object> settings);
}