package com.jurong.aicenter.dto.admin;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员修改分组的请求（PATCH）。
 *
 * <p>所有字段可选；前端只传需要改的字段。</p>
 *
 * <p><b>修改 is_default</b>的特殊规则：
 * <ul>
 *   <li>从 true → false 会被拒绝（6006 GROUP_IS_DEFAULT_CANNOT_UNSET）</li>
 *   <li>从 false → true 会自动把其他"非本分组"的 is_default 重置为 0</li>
 * </ul>
 */
@Data
public class UpdateGroupRequest {

    @Size(max = 100, message = "name 长度不能超过 100")
    private String name;

    @Size(max = 500)
    private String description;

    @Size(max = 20)
    private String color;

    /** 注意：从 true 设为 false 会被拒绝；从 false 设为 true 会"独占"默认身份 */
    private Boolean isDefault;
}
