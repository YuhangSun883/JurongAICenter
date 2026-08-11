package com.jurong.aicenter.dto.image;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 图片收藏请求 DTO
 * 用户点击收藏按钮时，把"AI 生成"记录（source_tool='image'）标记为"已收藏"（source_tool='favorite'），
 * 不再额外上传/复制图片（避免 MinIO 重复存储）。
 */
@Data
public class FavoriteImageRequest {

    /** 已生成的 AI 图片在 MinIO 中的 objectKey（如 media/123/2026-08/abc.png） */
    @NotBlank(message = "objectKey 不能为空")
    private String objectKey;
}
