package com.jurong.aicenter.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户-分组关联表（多对多中间表）。
 * 唯一约束 (user_id, group_id) 防止重复加入。
 */
@Data
@TableName("user_group_members")
public class UserGroupMember {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long groupId;

    private LocalDateTime joinedAt;

    @TableLogic
    private Integer deleted;
}
