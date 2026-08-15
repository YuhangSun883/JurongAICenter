package com.jurong.aicenter.dto.media;

import com.jurong.aicenter.entity.MediaAsset;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MediaAssetDto {
    private Long id;
    private Long libraryId;
    /** image / video / audio */
    private String type;
    /** uploaded / ai-generated */
    private String source;
    private String name;
    private String mimeType;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private Double durationSec;
    /** MinIO 公网 URL,前端 <img src=...> 直接用 */
    private String url;
    private String sourceTool;
    private String sourceTaskId;
    private Long createdAt;

    public static MediaAssetDto from(MediaAsset a, String presignedUrl) {
        if (a == null) return null;
        return new MediaAssetDto(
            a.getId(),
            a.getLibraryId(),
            a.getType(),
            a.getSource(),
            a.getName(),
            a.getMimeType(),
            a.getSizeBytes(),
            a.getWidth(),
            a.getHeight(),
            a.getDurationSec() != null ? a.getDurationSec().doubleValue() : null,
            presignedUrl,
            a.getSourceTool(),
            a.getSourceTaskId(),
            a.getCreatedAt() != null ? a.getCreatedAt().toInstant(java.time.ZoneOffset.ofHours(8)).toEpochMilli() : null
        );
    }
}