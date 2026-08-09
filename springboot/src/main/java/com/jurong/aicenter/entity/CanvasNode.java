package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 画布节点表（对应 canvas_nodes）。
 *
 * 注意：此 entity 仅用于 DB 映射。**Controller 层绝不直接返回该 entity**，
 * 需通过 CanvasNodeResponse DTO 暴露给前端（脱敏）。
 */
@Data
@TableName("canvas_nodes")
public class CanvasNode {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private Long userId;

    /** 所属画布（NULL 表示孤儿节点，由 CanvasBackfillRunner 自动归入默认画布） */
    private String canvasId;

    /** text / image / video / audio */
    private String type;

    private String title;

    /** 文本节点：原文；图片/视频节点：可存放上游输入 */
    private String content;

    private String assetId;

    /** 图片/视频节点的产物 URL（公开） */
    private String resultUrl;

    /** JSON 字符串：模型参数（temperature/size/duration 等） */
    private String settings;

    private Integer positionX;
    private Integer positionY;

    /**
     * JSON 字符串：上游节点连接列表（多端口格式 List&lt;NodeConnection&gt;）。
     * 每条连接有 port（端口名）和 nodeId（上游节点 UUID）。
     */
    private String upstreamIds;

    /**
     * JSON 字符串：下游节点连接列表（多端口格式 List&lt;NodeConnection&gt;）。
     */
    private String downstreamIds;

    /** idle / running / success / failed */
    private String status;

    /** 失败原因（前端可见，但脱敏后的文案） */
    private String failReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}