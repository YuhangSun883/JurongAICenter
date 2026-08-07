package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.dto.canvas.*;
import com.jurong.aicenter.entity.CanvasNode;
import com.jurong.aicenter.entity.CanvasTask;

import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.CanvasNodeRepository;
import com.jurong.aicenter.repository.CanvasTaskRepository;
import com.jurong.aicenter.service.CanvasService;
import com.jurong.aicenter.service.canvas.CanvasAiService;
import com.jurong.aicenter.service.canvas.CanvasAsyncExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 画布服务实现。
 *
 * 关键设计：
 *   - generate() 立刻返回 pending 状态（**不等** AI 完成）
 *   - AI 生成在 @Async 线程里跑，写回 task/node 状态
 *   - 前端用 getTaskStatus(taskId) 轮询
 *
 * 安全约束：
 *   - getNodeEntity 是**内部**用法（返回完整 entity），Controller 层**禁止**直接用
 *   - Controller 必须用 CanvasNodeResponse.from() 转换后再返回
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanvasServiceImpl implements CanvasService {

    private final CanvasNodeRepository nodeRepository;
    private final CanvasTaskRepository taskRepository;
    private final CanvasAiService aiService;
    private final CanvasAsyncExecutor asyncExecutor;
    private final ObjectMapper objectMapper;

    // ============= 节点 CRUD =============

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CanvasNode createNode(Long userId, CreateCanvasNodeRequest req) {
        CanvasNode node = new CanvasNode();
        node.setUserId(userId);
        node.setType(req.getType());
        node.setTitle(req.getTitle());
        node.setContent(req.getContent());
        node.setAssetId(req.getAssetId());
        node.setPositionX(req.getPositionX() == null ? 0 : req.getPositionX());
        node.setPositionY(req.getPositionY() == null ? 0 : req.getPositionY());
        // upstreamIds/downstreamIds：DTO 是 List<String>，entity 存 JSON 字符串
        node.setUpstreamIds(serializeIds(req.getUpstreamIds()));
        node.setDownstreamIds(serializeIds(req.getDownstreamIds()));
        node.setStatus("idle");
        node.setCreatedAt(LocalDateTime.now());
        node.setUpdatedAt(LocalDateTime.now());
        nodeRepository.insert(node);
        log.info("Canvas node created: id={}, userId={}, type={}", node.getId(), userId, node.getType());
        return node;
    }

    @Override
    public CanvasNode updateNode(Long userId, String nodeId, UpdateCanvasNodeRequest req) {
        CanvasNode node = mustGetOwnedNode(userId, nodeId);
        if (req.getTitle() != null) node.setTitle(req.getTitle());
        if (req.getContent() != null) node.setContent(req.getContent());
        if (req.getAssetId() != null) node.setAssetId(req.getAssetId());
        if (req.getResultUrl() != null) node.setResultUrl(req.getResultUrl());
        if (req.getPositionX() != null) node.setPositionX(req.getPositionX());
        if (req.getPositionY() != null) node.setPositionY(req.getPositionY());
        if (req.getUpstreamIds() != null) node.setUpstreamIds(serializeIds(req.getUpstreamIds()));
        if (req.getDownstreamIds() != null) node.setDownstreamIds(serializeIds(req.getDownstreamIds()));
        node.setUpdatedAt(LocalDateTime.now());
        nodeRepository.updateById(node);
        return node;
    }

    /** List<String> → JSON 字符串（null/空都安全） */
    private String serializeIds(java.util.List<String> ids) {
        if (ids == null || ids.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (Exception e) {
            log.warn("serializeIds 失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public CanvasNode getNodeEntity(Long userId, String nodeId) {
        return mustGetOwnedNode(userId, nodeId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteNode(Long userId, String nodeId) {
        CanvasNode node = mustGetOwnedNode(userId, nodeId);
        nodeRepository.deleteById(node.getId());
        // 级联删任务
        taskRepository.delete(new LambdaQueryWrapper<CanvasTask>().eq(CanvasTask::getNodeId, nodeId));
        log.info("Canvas node deleted: id={}, userId={}", nodeId, userId);
    }

    // ============= 异步生成 =============

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GenerateCanvasNodeResponse generate(Long userId, String nodeId, GenerateCanvasNodeRequest req) {
        CanvasNode node = mustGetOwnedNode(userId, nodeId);

        // 1. 校验 type 与节点匹配
        if (!node.getType().equalsIgnoreCase(req.getType())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "节点 type=" + node.getType() + " 与请求 type=" + req.getType() + " 不一致");
        }

        // 2. 写任务记录（pending 状态）
        CanvasTask task = new CanvasTask();
        task.setNodeId(nodeId);
        task.setUserId(userId);
        task.setType(req.getType());
        task.setStatus("pending");
        task.setPrompt(req.getPrompt());
        task.setUpstreamContent(req.getContent());
        try {
            if (req.getSettings() != null) {
                task.setSettings(objectMapper.writeValueAsString(req.getSettings()));
            }
            if (req.getAssetIds() != null) {
                task.setAssetIds(objectMapper.writeValueAsString(req.getAssetIds()));
            }
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "参数序列化失败: " + e.getMessage());
        }
        task.setCreditsEstimated(aiService.estimateCredits(req.getType(), req.getSettings()));
        task.setCreatedAt(LocalDateTime.now());
        taskRepository.insert(task);

        // 3. 节点状态切到 running
        node.setStatus("running");
        node.setUpdatedAt(LocalDateTime.now());
        nodeRepository.updateById(node);

        // 4. 跨 Bean 调用 @Async（注入的是 CanvasAsyncExecutor 代理，触发异步）
        asyncExecutor.executeGenerationAsync(task.getId(), nodeId, req, userId);

        // 5. 立刻返回 pending 响应
        return new GenerateCanvasNodeResponse(
            task.getId(), nodeId, "pending",
            null, null, task.getCreditsEstimated()
        );
    }

    @Override
    public GenerateCanvasNodeResponse getTaskStatus(Long userId, String taskId) {
        CanvasTask task = taskRepository.selectById(taskId);
        if (task == null || !task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        return new GenerateCanvasNodeResponse(
            task.getId(),
            task.getNodeId(),
            task.getStatus(),
            task.getTextResult(),
            task.getResultUrl(),
            task.getCreditsEstimated()
        );
    }

    // ============= 内部 =============

    private CanvasNode mustGetOwnedNode(Long userId, String nodeId) {
        CanvasNode node = nodeRepository.selectById(nodeId);
        if (node == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "节点不存在");
        }
        if (!node.getUserId().equals(userId)) {
            // **防越权**：不暴露"是否存在"，统一返回 404
            log.warn("Unauthorized canvas node access: userId={} tried nodeId={} owned by={}",
                userId, nodeId, node.getUserId());
            throw new BusinessException(ErrorCode.NOT_FOUND, "节点不存在");
        }
        return node;
    }
}