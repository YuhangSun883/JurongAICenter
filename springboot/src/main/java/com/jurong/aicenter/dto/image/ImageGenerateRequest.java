package com.jurong.aicenter.dto.image;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * AI 图片生成请求 DTO
 * 对应 da-ai.cc 网站的 AI 图片生成功能
 */
@Data
public class ImageGenerateRequest {

    /** 图片生成提示词（必填） */
    @NotBlank(message = "提示词不能为空")
    private String prompt;

    /** 图片尺寸，默认 1024x1024 */
    private String size = "1024x1024";

    /** 图片质量，默认 standard */
    private String quality = "standard";

    /** 图片风格，默认 vivid */
    private String style = "vivid";

    /**
     * 引用图片列表（base64 data URI 格式，如 data:image/png;base64,...）
     * 用户通过"@"引用的上方已上传图片，作为素材结合提示词进行图片生成/编辑
     * 为空时调用纯文本生成接口，非空时调用图片编辑接口
     */
    private List<String> referenceImages;
}
