package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("jobs")
public class Job {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long workflowId;

    private String templateId;

    private String comfyuiPromptId;

    /** PENDING / RUNNING / COMPLETED / FAILED / CANCELLED */
    private String status;

    /** 提交时的输入参数 JSON */
    private String inputsSnapshot;

    /** 提交时的 workflow graph JSON */
    private String graphSnapshot;

    /** MinIO 产物 URL 数组 JSON */
    private String resultUrls;

    private String errorMessage;

    private Integer creditsCost;

    private Integer durationMs;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime createdAt;

    @TableLogic
    private Integer deleted;
}