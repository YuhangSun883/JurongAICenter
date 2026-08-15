package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 媒体资产库（用户维度）
 *
 * <p>对应 V8 + V18 + V19 migration: media_libraries
 *
 * <p>一个用户注册时自动建 2 个系统默认库（type='system-uploaded' / 'system-ai'），
 * 用户可建自定义库（type='custom'）。
 *
 * <p>2026-08-15 升级①：库支持业务类型（normal/virtual_human/real_person），
 * 真人库需填写授权用途和授权有效期。
 *
 * <p>2026-08-15 升级②：库支持父子嵌套（parentId），无限深度。
 * - parentId = null：根库
 * - 系统库不能做父库/子库
 * - 普通库下子库类型不限；虚拟人/真人库下子库类型必须与父库一致
 * - 父库不能改
 * - 删父库 → 级联删子库、孙库、素材、MinIO
 * - 父库素材视图：各是各的（不递归聚合）
 */
@Data
@TableName("media_libraries")
public class MediaLibrary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 父库 id，null = 根库（V19 引入） */
    private Long parentId;

    private String name;

    /** system-uploaded / system-ai / custom（系统/自定义分类，V8 引入） */
    private String type;

    /** normal / virtual_human / real_person（业务类型，V18 引入） */
    private String bizType;

    /** 授权用途说明（仅 real_person 类型） */
    private String authPurpose;

    /** 授权有效期（仅 real_person 类型） */
    private LocalDate authExpireAt;

    /** folder/star/heart/sparkles */
    private String iconKey;

    private String description;

    private Integer sortOrder;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 授权状态：valid / expired / none（不持久化，前端按 authExpireAt 计算） */
    private transient String authStatus;

    /** 子库列表（仅 DTO 透传用，不持久化） */
    private transient List<MediaLibrary> children;
}
