package com.jurong.aicenter.dto.image;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * AI 图片生成响应 DTO
 */
@Data
@AllArgsConstructor
public class ImageGenerateResponse {

    /** 生成的图片 URL（MinIO 存储的可访问地址） */
    private String imageUrl;

    /** 使用的模型名称 */
    private String model;

    /** 原始 NewAPI 返回的 URL（用于调试） */
    private String originalUrl;
}
