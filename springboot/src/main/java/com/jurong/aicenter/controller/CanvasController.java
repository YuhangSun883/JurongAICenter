package com.jurong.aicenter.controller;

import com.jurong.aicenter.dto.canvas.*;
import com.jurong.aicenter.entity.CanvasNode;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.CanvasService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 画布 REST API。
 *
 * **安全约束（用户要求）**：
 *   - **绝不**直接返回 CanvasNode entity（会泄露 userId/settings/upstreamIds 等敏感字段）
 *   - 所有 GET/POST/PATCH/DELETE 的响应都用 CanvasNodeResponse DTO 包装
 *   - 失败消息统一脱敏（不超过 100 字符，不暴露 Java 堆栈）
 *
 * 端点列表：
 *   POST   /api/canvas/nodes              创建节点
 *   PATCH  /api/canvas/nodes/{id}         修改节点
 *   GET    /api/canvas/nodes/{id}         查询单个节点
 *   DELETE /api/canvas/nodes/{id}         删除节点
 *   POST   /api/canvas/nodes/{id}/generate 异步生成（立刻返回 pending）
 *   GET    /api/canvas/tasks/{taskId}     任务状态（前端轮询用）
 */
@Slf4j
@RestController
@RequestMapping("/api/canvas")
@RequiredArgsConstructor
public class CanvasController {

    private final CanvasService canvasService;

    @PostMapping("/nodes")
    public CanvasNodeResponse createNode(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateCanvasNodeRequest req) {
        requireUser(user);
        CanvasNode node = canvasService.createNode(user.id(), req);
        return CanvasNodeResponse.from(node);
    }

    @PatchMapping("/nodes/{id}")
    public CanvasNodeResponse updateNode(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String id,
            @RequestBody UpdateCanvasNodeRequest req) {
        requireUser(user);
        CanvasNode node = canvasService.updateNode(user.id(), id, req);
        return CanvasNodeResponse.from(node);
    }

    @GetMapping("/nodes/{id}")
    public CanvasNodeResponse getNode(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String id) {
        requireUser(user);
        CanvasNode node = canvasService.getNodeEntity(user.id(), id);
        return CanvasNodeResponse.from(node);
    }

    @DeleteMapping("/nodes/{id}")
    public Map<String, Object> deleteNode(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String id) {
        requireUser(user);
        canvasService.deleteNode(user.id(), id);
        return Map.of("nodeId", id, "status", "deleted");
    }

    /**
     * 核心端点：异步生成节点产物。
     * 立刻返回 pending 状态，**前端需轮询 /tasks/{taskId} 获取最终结果**。
     */
    @PostMapping("/nodes/{id}/generate")
    public GenerateCanvasNodeResponse generate(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String id,
            @Valid @RequestBody GenerateCanvasNodeRequest req) {
        requireUser(user);
        return canvasService.generate(user.id(), id, req);
    }

    /** 前端轮询任务状态 */
    @GetMapping("/tasks/{taskId}")
    public GenerateCanvasNodeResponse getTask(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String taskId) {
        requireUser(user);
        return canvasService.getTaskStatus(user.id(), taskId);
    }

    private void requireUser(AuthenticatedUser user) {
        if (user == null || user.id() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}