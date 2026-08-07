package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.canvas.*;
import com.jurong.aicenter.entity.CanvasNode;

public interface CanvasService {

    /** 创建节点（不含生成） */
    CanvasNode createNode(Long userId, CreateCanvasNodeRequest req);

    /** 修改节点元数据 */
    CanvasNode updateNode(Long userId, String nodeId, UpdateCanvasNodeRequest req);

    /** 获取单个节点（已鉴权） */
    CanvasNode getNodeEntity(Long userId, String nodeId);

    /** 删除节点（含级联删除任务） */
    void deleteNode(Long userId, String nodeId);

    /**
     * 异步生成：返回 pending 状态的任务快照，**真实 AI 在后台线程跑**
     */
    GenerateCanvasNodeResponse generate(Long userId, String nodeId, GenerateCanvasNodeRequest req);

    /** 任务状态查询（前端轮询用） */
    GenerateCanvasNodeResponse getTaskStatus(Long userId, String taskId);
}