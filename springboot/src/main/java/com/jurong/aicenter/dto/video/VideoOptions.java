package com.jurong.aicenter.dto.video;

import lombok.Builder;
import lombok.Data;

/**
 * 视频生成选项 —— 封装文档 §2 metadata 字段
 *
 * <p>对应 aicoming / newapi 中转站 `metadata.*` 字段：
 * <ul>
 *   <li>duration —— 视频时长（秒）</li>
 *   <li>resolution —— 480p / 720p / 1080p / 4k（小写 p）</li>
 *   <li>ratio —— 16:9 / 9:16 / 1:1 / 4:3 / 21:9 / 3:4</li>
 *   <li>generateAudio —— 是否同时生成音频</li>
 *   <li>watermark —— 是否带水印</li>
 *   <li>returnLastFrame —— 是否返回最后一帧（用作下一轮 i2v 输入）</li>
 *   <li>seed —— 随机种子（0=随机，>0 时复现）</li>
 *   <li>model —— 实际下发的模型 ID（默认 doubao-seedance-2.0）</li>
 * </ul>
 */
@Data
@Builder
public class VideoOptions {
    /** 视频时长（秒），合法值 1-600，默认 4 */
    @Builder.Default
    private int duration = 4;

    /** 分辨率（统一小写 480p/720p/1080p/4k），默认 480p */
    @Builder.Default
    private String resolution = "480p";

    /** 宽高比，null 则不传（aicoming 默认 16:9） */
    private String ratio;

    /** 是否同时生成音频 */
    @Builder.Default
    private boolean generateAudio = false;

    /** 是否带水印 */
    @Builder.Default
    private boolean watermark = false;

    /** 是否返回最后一帧（用于级联 I2V） */
    @Builder.Default
    private boolean returnLastFrame = true;

    /** 随机种子，0=随机 */
    @Builder.Default
    private long seed = 0;

    /** 实际下发的模型 ID，默认 doubao-seedance-2.0 */
    @Builder.Default
    private String model = "doubao-seedance-2.0";

    /** 上传图片的文件名（aicoming 用来识别格式），默认 canvas_input.png */
    @Builder.Default
    private String imageFilename = "canvas_input.png";

    /** 上传图片的 MIME 类型，如 image/png、image/jpeg，默认 image/png */
    @Builder.Default
    private String imageMime = "image/png";

    /**
     * 把 options 规整后写入 multipart form 的 metadata 字段。
     * aicoming 上游需要 metadata.content 数组 + 顶层 image，与 newapi 不同；
     * 兼容层 aicoming-video-proxy 会自动转换，所以这里只填 OpenAI 风格字段即可。
     */
    public String toMetadataJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"duration\":").append(duration).append(",");
        sb.append("\"resolution\":\"").append(safeStr(resolution.toLowerCase())).append("\",");
        if (ratio != null && !ratio.isBlank()) {
            sb.append("\"ratio\":\"").append(safeStr(ratio)).append("\",");
        }
        sb.append("\"generate_audio\":").append(generateAudio).append(",");
        sb.append("\"watermark\":").append(watermark).append(",");
        sb.append("\"return_last_frame\":").append(returnLastFrame).append(",");
        if (seed > 0) {
            sb.append("\"seed\":").append(seed).append(",");
        }
        // 去掉末尾逗号
        if (sb.charAt(sb.length() - 1) == ',') {
            sb.setLength(sb.length() - 1);
        }
        sb.append("}");
        return sb.toString();
    }

    private static String safeStr(String s) {
        return s == null ? "" : s.replace("\"", "\\\"");
    }
}
