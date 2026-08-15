package com.jurong.aicenter.dto.image;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 图片收藏响应 DTO
 */
@Data
@AllArgsConstructor
public class FavoriteImageResponse {

    /** MinIO objectKey（用于取消收藏时传给后端） */
    private String objectKey;

    /** MinIO 中存储的可访问 URL */
    private String url;

    /** 收藏时间（毫秒时间戳） */
    private long createdAt;
}
