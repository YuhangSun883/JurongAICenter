package com.jurong.aicenter.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客户分组（多对多关系中的一个"组"）。
 * 一个分组可包含多个用户，一个用户也可以在多个分组里。
 */
@Data
@TableName("user_groups")
public class UserGroup {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String description;

    /** 前端标签颜色，hex 格式，例如 #909399 */
    private String color;

    /** 是否默认分组：新用户注册时自动加入 */
    private Boolean isDefault;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
