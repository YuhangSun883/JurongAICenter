package com.jurong.aicenter.dto.media;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上传完成后立即返回给前端的素材信息
 * （结构跟 MediaAssetDto 对齐，省一次 select）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UploadMediaResponse {
    private Long id;
    private Long libraryId;
    private String type;
    private String source;
    private String name;
    private String mimeType;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private Double durationSec;
    private String url;
}