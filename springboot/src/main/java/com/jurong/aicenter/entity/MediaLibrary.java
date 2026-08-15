package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 媒体资产库（用户维度）
 *
 * <p>对应 V8 migration: media_libraries
 *
 * <p>一个用户注册时自动建 2 个系统默认库（type='system-uploaded' / 'system-ai'），
 * 用户可建自定义库（type='custom'）。
 */
@Data
@TableName("media_libraries")
public class MediaLibrary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    /** system-uploaded / system-ai / custom */
    private String type;

    /** folder/star/heart/sparkles */
    private String iconKey;

    private String description;

    private Integer sortOrder;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
