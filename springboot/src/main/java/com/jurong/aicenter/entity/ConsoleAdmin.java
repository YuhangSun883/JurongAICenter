package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("console_admins")
public class ConsoleAdmin {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String email;

    private String passwordHash;

    private String displayName;

    /** ADMIN / FINANCE / OPERATOR / VIEWER */
    private String role;

    private Integer disabled;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
