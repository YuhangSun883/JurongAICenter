package com.jurong.aicenter.dto.media;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MediaAssetResponse {
    private Long id;
    private Long libraryId;
    private String libraryName;
    /** image / video / audio */
    private String type;
    /** uploaded / ai-generated */
    private String source;
    private String name;
    private String mimeType;
    private Long sizeBytes;
    private Integer width;
    private Integer height;
    private BigDecimal durationSec;
    private String url;
    private String objectKey;
    private String sourceTool;
    private String sourceTaskId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
