package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理员操作审计日志（V5 新建表）。
 *
 * <p>关键约束：detail 字段存 JSON 字符串，前端可读、可解析。admin_id + admin_email 双写，
 * 即使用户改名 / 删除，审计记录仍可追溯。</p>
 *
 * <p>不在 service 层暴露修改入口：审计日志只能 append，不能 update/delete。</p>
 */
@Data
@TableName("admin_audit_logs")
public class AdminAuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long adminId;

    private String adminEmail;

    /** AdminAuditAction 枚举：CHANGE_ROLE / DISABLE_USER / ENABLE_USER / CREATE_GROUP / ... */
    private String action;

    /** 目标类型：USER / GROUP / GROUP_MEMBER */
    private String targetType;

    /** 目标主键；可能为 null（如批量操作） */
    private Long targetId;

    /** 改动详情 JSON，例如 {"fromRole":"USER","toRole":"ADMIN"} 或 {"groupName":"VIP"} */
    private String detail;

    private LocalDateTime createdAt;
}
