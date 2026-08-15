package com.jurong.aicenter.service;

/**
 * 管理员操作审计 — 写日志专用服务（仅 append，不允许 update / delete）。
 *
 * <p>由 AdminUserService / AdminGroupService 在每次管理员操作后调用。审计日志失败<b>不会</b>
 * 阻断业务操作（fail-open 策略）— 我们宁可丢一条审计也不能拒掉合法请求，但失败会被记录到 log。</p>
 */
public interface AdminAuditService {

    /** 字符串常量：审计 action 字段 */
    String ACTION_CHANGE_ROLE = "CHANGE_ROLE";
    String ACTION_DISABLE_USER = "DISABLE_USER";
    String ACTION_ENABLE_USER = "ENABLE_USER";
    String ACTION_CREATE_GROUP = "CREATE_GROUP";
    String ACTION_UPDATE_GROUP = "UPDATE_GROUP";
    String ACTION_DELETE_GROUP = "DELETE_GROUP";
    String ACTION_ADD_GROUP_MEMBER = "ADD_GROUP_MEMBER";
    String ACTION_REMOVE_GROUP_MEMBER = "REMOVE_GROUP_MEMBER";

    /** 字符串常量：target_type 字段 */
    String TARGET_USER = "USER";
    String TARGET_GROUP = "GROUP";
    String TARGET_GROUP_MEMBER = "GROUP_MEMBER";

    /**
     * 追加一条审计记录。失败仅 log.warn，不抛异常。
     *
     * @param adminId 操作者 user_id
     * @param adminEmail 冗余存邮箱
     * @param action 操作类型（{@code AdminAuditService.ACTION_*}）
     * @param targetType 目标类型（{@code AdminAuditService.TARGET_*}）
     * @param targetId 目标主键
     * @param detailJson 改动详情 JSON（可为 null）
     */
    void log(Long adminId, String adminEmail, String action,
             String targetType, Long targetId, String detailJson);
}
