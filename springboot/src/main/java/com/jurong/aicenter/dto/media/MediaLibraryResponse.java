package com.jurong.aicenter.dto.media;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MediaLibraryResponse {
    private Long id;
    private String name;
    /** system-uploaded / system-ai / custom */
    private String type;
    /** folder / star / heart / sparkles */
    private String iconKey;
    private String description;
    private Integer sortOrder;
    private Long assetCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
