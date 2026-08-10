package com.jurong.aicenter.exception;

public enum ErrorCode {
    SUCCESS(0, "success"),
    INTERNAL_ERROR(9999, "internal error"),
    INVALID_PARAM(9001, "invalid param"),
    UNAUTHORIZED(9401, "unauthorized"),
    FORBIDDEN(9403, "forbidden"),
    NOT_FOUND(9404, "not found"),

    EMAIL_ALREADY_EXISTS(1001, "email already exists"),
    INVALID_CREDENTIALS(1002, "invalid credentials"),
    TOKEN_EXPIRED(1101, "token expired"),
    INVALID_TOKEN(1102, "invalid token"),

    USER_NOT_FOUND(2001, "user not found"),
    USER_DISABLED(2002, "user disabled"),

    WORKFLOW_INVALID(3001, "workflow invalid"),
    COMFYUI_UNREACHABLE(3002, "comfyui unreachable"),
    COMFYUI_REJECTED(3003, "comfyui rejected"),
    QUOTA_INSUFFICIENT(3004, "quota insufficient"),
    JOB_NOT_READY(3005, "job not ready"),
    JOB_ALREADY_TERMINAL(3006, "job already terminal"),
    NEWAPI_UNREACHABLE(3007, "newapi unreachable"),
    NEWAPI_TASK_FAILED(3008, "newapi task failed"),
    NEWAPI_TASK_TIMEOUT(3009, "newapi task timeout"),
    NEWAPI_VIDEO_URL_MISSING(3010, "newapi video url missing"),

    WORKFLOW_NOT_FOUND(4001, "workflow not found"),
    WORKFLOW_ACCESS_DENIED(4002, "workflow access denied"),

    BILLING_NOT_ENABLED(5001, "billing not enabled"),

    ADMIN_OPERATION_DENIED(6001, "admin operation denied"),
    ADMIN_CANNOT_CHANGE_OWN_ROLE(6002, "cannot change own role"),
    ADMIN_CANNOT_DISABLE_SELF(6003, "cannot disable self"),
    GROUP_NAME_DUPLICATE(6004, "group name duplicate"),
    GROUP_IS_DEFAULT_CANNOT_DELETE(6005, "default group cannot be deleted"),
    GROUP_IS_DEFAULT_CANNOT_UNSET(6006, "default group cannot be unset"),
    USER_ALREADY_IN_GROUP(6007, "user already in group"),
    USER_NOT_IN_GROUP(6008, "user not in group"),
    INVALID_ROLE_VALUE(6009, "invalid role value"),

    // Media (7xxx) — 资产库 / 素材
    MEDIA_LIBRARY_NAME_DUPLICATE(7001, "资产库名称已存在"),
    MEDIA_LIBRARY_NOT_FOUND(7002, "资产库不存在"),
    MEDIA_LIBRARY_IS_SYSTEM_CANNOT_MODIFY(7003, "系统默认资产库不可修改或删除"),
    MEDIA_ASSET_NOT_FOUND(7010, "素材不存在"),
    MEDIA_ASSET_TYPE_INVALID(7011, "素材类型不支持"),
    MEDIA_ASSET_NAME_DUPLICATE(7012, "当前库内已存在同名素材"),
    MEDIA_UPLOAD_FAILED(7020, "文件上传失败"),
    ASSET_UPLOAD_FAILED(7021, "素材上传失败（aicoming proxy）"),
    ASSET_NOT_ACTIVE(7022, "素材未激活(aicoming proxy 返回 status=processing,请轮询等到 active)"),
    ASSET_DELETE_FAILED(7023, "素材删除失败(aicoming proxy)"),
    MEDIA_FILE_TOO_LARGE(7024, "文件超过大小限制"),
    MEDIA_FILE_EMPTY(7025, "文件为空");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
