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
    NEWAPI_REQUEST_INVALID(3011, "newapi request invalid (4xx, business rejected)"),
    // 2026-08-12 added: 区分 task not found (400) 与其他 4xx 错误
    // 400 task not found 不一定是任务失败 —— 可能是 NewAPI 元数据被清理但视频文件还在 CDN 上
    // 调用方应根据 syncUrl / assetUrl 等兜底,而不是立即 FAILED
    NEWAPI_TASK_NOT_FOUND(3012, "newapi task not found (TTL expired or cleaned, but video may still exist on CDN)"),

    WORKFLOW_NOT_FOUND(4001, "workflow not found"),
    WORKFLOW_ACCESS_DENIED(4002, "workflow access denied"),

    BILLING_NOT_ENABLED(5001, "billing not enabled"),

    ADMIN_OPERATION_DENIED(6001, "admin operation denied"),
    ADMIN_CANNOT_CHANGE_OWN_ROLE(6002, "cannot change own role"),
    ADMIN_CANNOT_DISABLE_SELF(6003, "cannot disable self"),
    // 2026-08-14 added: 管理员账号禁止从普通 /login 页登录
    // 普通登录页不允许 ADMIN 角色登录，管理员必须走 /admin/login 专属入口（未来）
    // 注：原 6003 已占用，用新 code 6010
    ADMIN_LOGIN_FORBIDDEN_ON_USER_ENTRY(6010, "admin login forbidden on user login page"),
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
    MEDIA_LIBRARY_INVALID_AUTH(7004, "真人库必须填写授权用途说明和授权有效期"),
    // 2026-08-15 V19: 父子库相关
    MEDIA_LIBRARY_PARENT_NOT_FOUND(7005, "父库不存在"),
    MEDIA_LIBRARY_PARENT_IS_SYSTEM(7006, "系统库不能作为父库"),
    MEDIA_LIBRARY_PARENT_TYPE_MISMATCH(7007, "虚拟人/真人库的子库类型必须与父库一致"),
    MEDIA_LIBRARY_HAS_CHILDREN(7008, "请先删除子库"),
    MEDIA_LIBRARY_BIZTYPE_IMMUTABLE(7009, "资产库业务类型创建后不可修改"),
    MEDIA_ASSET_NOT_FOUND(7010, "素材不存在"),
    MEDIA_ASSET_TYPE_INVALID(7011, "素材类型不支持"),
    MEDIA_ASSET_NAME_DUPLICATE(7012, "当前库内已存在同名素材"),
    MEDIA_ASSET_CANNOT_MOVE_TO_AI(7013, "AI 生成结果库只接收 AI 产出，不能手工移入"),
    MEDIA_ASSET_CANNOT_MOVE_SAME_LIB(7014, "素材已在目标库中"),
    MEDIA_UPLOAD_FAILED(7020, "文件上传失败"),
    ASSET_UPLOAD_FAILED(7021, "素材上传失败（aicoming proxy）"),
    ASSET_NOT_ACTIVE(7022, "素材未激活(aicoming proxy 返回 status=processing,请轮询等到 active)"),
    ASSET_DELETE_FAILED(7023, "素材删除失败(aicoming proxy)"),
    MEDIA_FILE_TOO_LARGE(7021, "文件超过大小限制"),
    MEDIA_FILE_EMPTY(7022, "文件为空"),
    // Aicoming 外部资产服务
    EXTERNAL_ASSET_UPLOAD_FAILED(7030, "外部资产上传失败"),
    EXTERNAL_ASSET_NOT_ACTIVE(7031, "外部资产未就绪"),
    EXTERNAL_ASSET_DELETE_FAILED(7032, "外部资产删除失败");

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
