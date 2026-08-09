package com.jurong.aicenter.dto.canvas;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

/**
 * 画布 generate 端点响应。
 *
 * 安全约束：
 *   - 不返回 nodeId/userId/内部 status 等敏感字段
 *   - 只暴露前端画布需要的最小字段
 *   - 文本节点填 text 字段；图片/视频节点填 resultUrl 字段
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GenerateCanvasNodeResponse {

    /** 任务 ID，前端轮询用 */
    private String taskId;

    private String nodeId;

    /** pending / running / success / failed */
    private String status;

    /** 文本节点结果（润色后的文本） */
    private String text;

    /** 图片/视频节点结果（产物 URL） */
    private String resultUrl;

    /** 预估积分 */
    private Integer creditsEstimated;

    /**
     * 本次任务新建的 CanvasNode ID 列表。
     * 仅在 status=success 时有值。脚本拆解 / 视频抽帧返回这个字段，
     * 前端只拉这些节点追加到本地 state，不 reload 整张画布（避免把历史节点堆左上角）。
     */
    private List<String> createdNodeIds;

    public List<String> getCreatedNodeIds() {
        return createdNodeIds == null ? Collections.emptyList() : createdNodeIds;
    }
}