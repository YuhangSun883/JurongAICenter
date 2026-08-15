package com.jurong.aicenter.dto.media;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
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

    // ============== V18 资产库升级字段 ==============
    /** 业务类型：normal / virtual_human / real_person */
    private String bizType;
    /** 授权用途说明（仅 real_person） */
    private String authPurpose;
    /** 授权有效期（仅 real_person） */
    private LocalDate authExpireAt;
    /** 授权状态：valid / expired / none（前端按 authExpireAt 计算） */
    private String authStatus;

    // ============== V19 父子库字段 ==============
    /** 父库 id，null = 根库 */
    private Long parentId;
    /** 是否存在子库（前端用于显示"打开子库"按钮） */
    private Boolean hasChildren;
}
