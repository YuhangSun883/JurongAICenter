package com.jurong.aicenter.dto.media;

import com.jurong.aicenter.entity.MediaLibrary;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MediaLibraryDto {
    private Long id;
    private String name;
    /** system-uploaded / system-ai / custom */
    private String type;
    private String iconKey;
    private String description;
    private Integer sortOrder;
    /** 该库内素材数（聚合） */
    private Long assetCount;
    private Long createdAt;
    private Long updatedAt;

    // ============== V18 资产库升级字段 ==============
    /** 业务类型：normal / virtual_human / real_person */
    private String bizType;
    /** 授权用途说明（仅 real_person） */
    private String authPurpose;
    /** 授权有效期（仅 real_person） */
    private LocalDate authExpireAt;
    /** 授权状态：valid / expired / none（前端按 authExpireAt 计算） */
    private String authStatus;

    public static MediaLibraryDto from(MediaLibrary lib, Long assetCount) {
        if (lib == null) return null;
        return new MediaLibraryDto(
            lib.getId(),
            lib.getName(),
            lib.getType(),
            lib.getIconKey(),
            lib.getDescription(),
            lib.getSortOrder(),
            assetCount,
            lib.getCreatedAt() != null ? lib.getCreatedAt().toInstant(java.time.ZoneOffset.ofHours(8)).toEpochMilli() : null,
            lib.getUpdatedAt() != null ? lib.getUpdatedAt().toInstant(java.time.ZoneOffset.ofHours(8)).toEpochMilli() : null,
            lib.getBizType(),
            lib.getAuthPurpose(),
            lib.getAuthExpireAt(),
            lib.getAuthStatus()
        );
    }
}