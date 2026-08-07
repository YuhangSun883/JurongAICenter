package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 媒体角色库（精选人脸/形象）
 *
 * <p>对应 V10 migration: media_roles
 *
 * <p>角色由运营/管理员预置，用户在画布/Agent 中"@主体"时引用。
 * is_locked=1 表示系统角色（不可删/改），is_locked=0 表示用户自建角色（预留）。
 */
@Data
@TableName("media_roles")
public class MediaRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** face / urban-blue / urban-silver / kids / mom / town-young / town-mid / fantasy / chinese / fashion / animal */
    private String category;

    private String imageUrl;

    /** 1=系统锁定不可删, 0=用户自建 */
    private Integer isLocked;

    private String description;

    private String tags;

    private Integer sortOrder;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}