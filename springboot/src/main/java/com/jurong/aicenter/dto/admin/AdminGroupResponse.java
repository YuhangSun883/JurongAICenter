package com.jurong.aicenter.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理员视图中分组的完整信息（与 {@link com.jurong.aicenter.dto.user.UserGroupResponse} 不同，
 * 这里多 isDefault / createdAt / memberCount 字段）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminGroupResponse {
    private Long id;
    private String name;
    private String description;
    private String color;
    private Boolean isDefault;
    /** 当前成员数（实时统计；可能与历史数据有微妙漂移，正常） */
    private Long memberCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
