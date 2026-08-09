package com.jurong.aicenter.controller;

import com.jurong.aicenter.dto.canvas.*;
import com.jurong.aicenter.entity.Canvas;
import com.jurong.aicenter.entity.CanvasNode;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.CanvasService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 画布 REST API。
 *
 * **安全约束（用户要求）**：
 *   - **绝不**直接返回 CanvasNode / Canvas entity（会泄露 userId/settings/upstreamIds 等敏感字段）
 *   - 所有 GET/POST/PATCH/DELETE 的响应都用 CanvasNodeResponse / CanvasListItem / CanvasDetail DTO 包装
 *   - 失败消息统一脱敏（不超过 100 字符，不暴露 Java 堆栈）
 *
 * 端点列表：
 *   ====== 画布（容器） ======
 *   GET    /api/canvas/canvases              "我的创作"列表（分页）
 *   POST   /api/canvas/canvases              新建画布
 *   GET    /api/canvas/canvases/{id}         画布完整快照（画布 + 节点 + 连线）
 *   PATCH  /api/canvas/canvases/{id}         改名字
 *   DELETE /api/canvas/canvases/{id}         删除（级联节点 + 任务）
 *
 *   ====== 节点 ======
 *   POST   /api/canvas/nodes                 创建节点（canvasId 可空 → 默认画布）
 *   POST   /api/canvas/upload                本地上传（图片/视频/音频自动建对应类型节点）
 *   PATCH  /api/canvas/nodes/{id}            修改节点
 *   GET    /api/canvas/nodes/{id}            查询单个节点
 *   DELETE /api/canvas/nodes/{id}            删除节点
 *   POST   /api/canvas/nodes/{id}/generate   异步生成（立刻返回 pending）
 *   GET    /api/canvas/tasks/{taskId}        任务状态（前端轮询用）
 */
@Slf4j
@RestController
@RequestMapping("/api/canvas")
@RequiredArgsConstructor
public class CanvasController {

    private final CanvasService canvasService;

    // ============= 画布端点 =============

    @GetMapping("/canvases")
    public List<CanvasListItem> listCanvases(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false, defaultValue = "1") int page,
            @RequestParam(required = false, defaultValue = "20") int pageSize) {
        requireUser(user);
        return canvasService.listCanvases(user.id(), page, pageSize);
    }

    @PostMapping("/canvases")
    public CanvasListItem createCanvas(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateCanvasRequest req) {
        requireUser(user);
        Canvas c = canvasService.createCanvas(user.id(), req);
        // 新建画布没有节点，count=0
        return CanvasListItem.from(c, 0);
    }

    @GetMapping("/canvases/{id}")
    public CanvasDetail getCanvas(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String id) {
        requireUser(user);
        return canvasService.getCanvasDetail(user.id(), id);
    }

    @PatchMapping("/canvases/{id}")
    public CanvasListItem updateCanvas(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String id,
            @Valid @RequestBody UpdateCanvasRequest req) {
        requireUser(user);
        Canvas c = canvasService.updateCanvas(user.id(), id, req);
        // 更新后保持 count 字段（前端不用拉详情）
        return CanvasListItem.from(c, -1);
    }

    @DeleteMapping("/canvases/{id}")
    public Map<String, Object> deleteCanvas(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String id) {
        requireUser(user);
        canvasService.deleteCanvas(user.id(), id);
        return Map.of("canvasId", id, "status", "deleted");
    }

    // ============= 节点端点 =============

    @PostMapping("/nodes")
    public CanvasNodeResponse createNode(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateCanvasNodeRequest req) {
        requireUser(user);
        CanvasNode node = canvasService.createNode(user.id(), req.getCanvasId(), req);
        return CanvasNodeResponse.from(node);
    }

    /**
     * 本地上传 + 自动建节点。
     * 传图片 → 建 image 节点；传视频 → 建 video 节点；传音频 → 建 audio 节点。
     * 文件大小限制由 MediaService 强制（图片 20M / 视频 200M / 音频 50M）。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CanvasNodeResponse upload(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "canvasId", required = false) String canvasId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "positionX", required = false) Integer positionX,
            @RequestParam(value = "positionY", required = false) Integer positionY) {
        requireUser(user);
        CanvasNode node = canvasService.uploadAndCreateNode(
            user.id(), canvasId, file, title, positionX, positionY);
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

    /**
     * 视频抽帧描述：ffmpeg 抽帧 + VL 模型 caption + 拼装口播文案。
     * 必须 type=video 且节点有 resultUrl。立刻返回 pending，前端轮询 /tasks/{taskId}。
     *
     * @param mode  脚本拆解脚本拆解脚本拆解脚本拆解
     *             - "script" 只生成口播文案文本节点（不抽帧）
     *             - "frames" 只生成帧缩略图网格（不建文本节点）
     *             - "both" 文本节点 + 帧节点都生成（默认）
     */
    @PostMapping("/nodes/{id}/extract-caption")
    public GenerateCanvasNodeResponse extractCaption(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String id,
            @RequestParam(value = "fps", defaultValue = "1") double fps,
            @RequestParam(value = "mode", defaultValue = "both") String mode) {
        requireUser(user);
        return canvasService.extractAndCaption(user.id(), id, fps, mode);
    }

    /** 前端轮询任务状态 */
    @GetMapping("/tasks/{taskId}")
    public GenerateCanvasNodeResponse getTask(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String taskId) {
        requireUser(user);
        return canvasService.getTaskStatus(user.id(), taskId);
    }

    /**
     * 2026-08-09 新增:换装(clothing transfer)
     * 路径参数:{id} = 视频节点 ID
     * 请求 body:{"clothingNodeIds":["衣服正面节点ID","衣服背面节点ID","衣服模特上身节点ID"]}
     * 返回:pending 状态的任务,前端轮询 /tasks/{taskId} 看进度
     */
    @PostMapping("/nodes/{id}/transfer-clothing")
    public GenerateCanvasNodeResponse transferClothing(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        requireUser(user);
        Object raw = body == null ? null : body.get("clothingNodeIds");
        if (!(raw instanceof List)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "clothingNodeIds 必须是数组");
        }
        List<String> clothingNodeIds = new java.util.ArrayList<>();
        for (Object o : (List<?>) raw) {
            if (o != null) clothingNodeIds.add(o.toString());
        }
        return canvasService.transferClothing(user.id(), id, clothingNodeIds);
    }

    private void requireUser(AuthenticatedUser user) {
        if (user == null || user.id() == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}