package com.jurong.aicenter.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员创建分组的请求。
 *
 * <p>name 必须全局唯一（uk_name 唯一索引约束），name 重名时返 6004 (GROUP_NAME_DUPLICATE)。</p>
 *
 * <p>isDefault=true 的"默认分组"在系统中<b>全局唯一</b>：创建第二个 isDefault=true 的分组
 * 会自动把第一个的 isDefault 设为 0（避免误导）。删除时拒绝删 Default
 * （6005 GROUP_IS_DEFAULT_CANNOT_DELETE）。</p>
 *
 * <p>color 字段接受任何 #RRGGBB 形式的 hex 字符串；前端可选 HEX picker，不做正则强制。</p>
 */
@Data
public class CreateGroupRequest {

    @NotBlank(message = "name 不能为空")
    @Size(max = 100, message = "name 长度不能超过 100")
    private String name;

    @Size(max = 500)
    private String description;

    /** 可选，默认 "#909399"。长度 <= 20 让前端能塞更多自定义标签文本 */
    @Size(max = 20)
    private String color = "#909399";

    /** 可选，默认 false。true 表示这是"新用户自动加入"的默认分组 */
    private Boolean isDefault = false;
}
