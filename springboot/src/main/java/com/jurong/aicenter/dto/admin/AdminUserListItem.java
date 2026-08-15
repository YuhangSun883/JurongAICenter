package com.jurong.aicenter.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 管理员视图中"列表项"形态的 User 信息。
 *
 * <p>相比 {@link com.jurong.aicenter.dto.user.UserResponse}：
 * <ul>
 *   <li>多 {@code disabled} 字段（管理员必看）</li>
 *   <li>多 {@code createdAt}（便于按时间排序/筛选）</li>
 *   <li>多 {@code groups} 当前所属分组名字列表（前端可直接显示标签）</li>
 * </ul>
 *
 * <p><b>绝对不暴露 passwordHash 字段</b>。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserListItem {
    private Long id;
    private String email;
    private String displayName;
    private String role;
    private Integer disabled;
    private Integer credits;
    private Integer monthlyQuota;
    private Integer quotaUsed;
    private String plan;
    private LocalDateTime createdAt;
    /** 当前所属分组 id 列表（admin 搜索结果排序用） */
    private java.util.List<Long> groupIds;
    /** 当前所属分组名称列表（前端标签展示用） */
    private java.util.List<String> groupNames;
}
