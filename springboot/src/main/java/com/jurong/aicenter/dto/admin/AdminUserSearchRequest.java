package com.jurong.aicenter.dto.admin;

import lombok.Data;

/**
 * 管理员搜索用户的请求参数。
 *
 * <p>全部可选参数；同时设置时按"displayName LIKE + role 精确 + 分页"组合查询，
 * 无任何参数时等价于"分页列所有非管理员用户"（displayName 模糊查询模式默认排除自己）。</p>
 *
 * @see com.jurong.aicenter.controller.AdminController
 */
@Data
public class AdminUserSearchRequest {

    /** displayName 模糊匹配（LIKE '%xxx%'，全小写不敏感）；为空则不参与过滤 */
    private String displayName;

    /** 角色精确过滤：USER / ADMIN；为空则不过滤 */
    private String role;

    /** 是否禁用：true=已禁用 false=未禁用 null=不过滤 */
    private Boolean disabled;

    /** 页码，默认 1 */
    private Integer page = 1;

    /** 每页条数，默认 20，上限 100 */
    private Integer pageSize = 20;
}
