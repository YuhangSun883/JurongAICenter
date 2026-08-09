package com.jurong.aicenter.dto.image;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 图片收藏请求 DTO
 * 用户点击收藏按钮时，将 base64 图片数据上传到 MinIO 持久化存储
 */
@Data
public class FavoriteImageRequest {

    /** 图片数据（base64 data URI 格式，如 data:image/png;base64,...） */
    @NotBlank(message = "图片数据不能为空")
    private String imageData;
}
