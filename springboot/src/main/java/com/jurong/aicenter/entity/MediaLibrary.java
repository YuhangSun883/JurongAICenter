package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("media_libraries")
public class MediaLibrary {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    /** system-uploaded / system-ai / custom */
    private String type;

    /** folder / star / heart / sparkles */
    private String iconKey;

    private String description;

    private Integer sortOrder;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
