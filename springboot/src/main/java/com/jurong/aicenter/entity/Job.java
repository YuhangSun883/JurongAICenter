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

    /**
     * 2026-08-13 字段名误导(历史包袱):
     *   字段名 comfyuiPromptId 来自早期项目 ComfyUI 时代,实际语义已偏移 ——
     *   现在存的是 <b>NewAPI 中转站的 task_id</b>(aicoming-proxy 也用同一个字段存它的 task_id),
     *   与 ComfyUI prompt_id 无关。
     *   未来重构应迁移到 {@code newapiTaskId} 字段,保持向后兼容。
     */
    // 2026-08-14:移除 @Deprecated 注解,避免 Lombok 把注解复制到自动生成的 getter/setter 上,
    //   导致调用方 (VideoGenerationServiceImpl 等) 编译报"已过时"警告。
    //   字段语义仍按 NewAPI task_id 使用,后续重构再迁移到新字段。
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