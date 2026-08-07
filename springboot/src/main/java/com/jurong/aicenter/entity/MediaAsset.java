package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 媒体素材资产
 *
 * <p>对应 V8 migration: media_assets
 *
 * <p>每个素材归属于一个 {@link MediaLibrary}。
 * 字段名跟前端 MediaPickerDialog 用的 PickedMedia 保持一致。
 */
@Data
@TableName("media_assets")
public class MediaAsset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long libraryId;

    /** image / video / audio */
    private String type;

    /** uploaded / ai-generated */
    private String source;

    private String name;

    private String mimeType;

    private Long sizeBytes;

    private Integer width;

    private Integer height;

    private BigDecimal durationSec;

    /** MinIO 对象 key（如 media/3/3/photo.png） */
    private String objectKey;

    /** video/image/canvas/agent/upload - 哪个工具产生的（可选） */
    private String sourceTool;

    /** 关联的任务 ID（可选） */
    private String sourceTaskId;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}