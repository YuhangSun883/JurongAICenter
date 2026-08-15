package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflows")
public class Workflow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    private String description;

    /** ComfyUI workflow JSON */
    private String graphJson;

    private String thumbnailUrl;

    private Boolean isTemplate;

    private Boolean isPublic;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}