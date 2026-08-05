package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String email;

    private String passwordHash;

    private String displayName;

    /** USER / ADMIN */
    private String role;

    private Integer credits;

    private Integer monthlyQuota;

    private Integer quotaUsed;

    private LocalDate quotaPeriodStart;

    /** FREE / PRO / PRO+ / ENTERPRISE */
    private String plan;

    private LocalDateTime planExpiresAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}