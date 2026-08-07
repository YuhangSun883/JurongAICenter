package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 画布异步生成任务表（对应 canvas_tasks）。
 *
 * 注意：error_message 字段**仅后端日志使用**，API 层不直接暴露。
 */
@Data
@TableName("canvas_tasks")
public class CanvasTask {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String nodeId;
    private Long userId;

    /** text / image / video / audio */
    private String type;

    /** pending / running / success / failed */
    private String status;

    private String prompt;
    private String upstreamContent;

    /** JSON 字符串 */
    private String settings;
    private String assetIds;

    /** AI 生成的产物 */
    private String textResult;
    private String resultUrl;

    private Integer creditsEstimated;
    private Integer durationMs;

    /** 后端日志用，**不在 API 返回** */
    private String errorMessage;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
}