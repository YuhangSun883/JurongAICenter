package com.jurong.aicenter.dto.media;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MediaUploadResponse {
    private Long id;
    private String url;
    private String name;
    private String type;
    private Long sizeBytes;
}
