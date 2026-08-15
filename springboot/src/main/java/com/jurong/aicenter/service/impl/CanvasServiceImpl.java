package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.dto.canvas.*;
import com.jurong.aicenter.entity.Canvas;
import com.jurong.aicenter.entity.CanvasNode;
import com.jurong.aicenter.entity.CanvasTask;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.CanvasNodeRepository;
import com.jurong.aicenter.repository.CanvasRepository;
import com.jurong.aicenter.repository.CanvasTaskRepository;
import com.jurong.aicenter.dto.media.MediaUploadResponse;
import com.jurong.aicenter.service.CanvasService;
import com.jurong.aicenter.service.MediaService;
import com.jurong.aicenter.service.canvas.CanvasAiService;
import com.jurong.aicenter.service.canvas.CanvasAsyncExecutor;
import com.jurong.aicenter.service.canvas.VideoFrameCaptionService;
import com.jurong.aicenter.service.canvas.ClothingTransferService;
import com.jurong.aicenter.service.canvas.CanvasVideoGenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 画布服务实现（节点 + 画布两级）。
 *
 * 关键设计：
 *   - generate() 立刻返回 pending 状态（**不等** AI 完成）
 *   - AI 生成在 @Async 线程里跑，写回 task/node 状态
 *   - 前端用 getTaskStatus(taskId) 轮询
 *   - 上游/下游连线用 List&lt;NodeConnection&gt;（多端口）取代旧的 List&lt;String&gt;
 *
 * 安全约束：
 *   - getNodeEntity / getCanvas 是**内部**用法（返回完整 entity），Controller 层**禁止**直接用
 *   - Controller 必须用 CanvasNodeResponse.from() / CanvasListItem.from() 转换后再返回
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanvasServiceImpl implements CanvasService {

    private final CanvasRepository canvasRepository;
    private final CanvasNodeRepository nodeRepository;
    private final CanvasTaskRepository taskRepository;
    private final CanvasAiService aiService;
    private final CanvasAsyncExecutor asyncExecutor;
    private final MediaService mediaService;
    private final ObjectMapper objectMapper;
    private final VideoFrameCaptionService videoFrameCaptionService;
    private final ClothingTransferService clothingTransferService;
    private final CanvasVideoGenService canvasVideoGenService;

    private static final String DEFAULT_CANVAS_NAME = "默认画布";

    // ============= 画布 CRUD =============

    @Override
    public List<CanvasListItem> listCanvases(Long userId, int page, int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.max(1, Math.min(pageSize, 50));

        // 1) 拉画布（按 updated_at DESC）
        List<Canvas> canvases = canvasRepository.selectList(
            new LambdaQueryWrapper<Canvas>()
                .eq(Canvas::getUserId, userId)
                .orderByDesc(Canvas::getUpdatedAt)
                .last("LIMIT " + pageSize + " OFFSET " + ((page - 1) * pageSize))
        );
        if (canvases.isEmpty()) return Collections.emptyList();

        // 2) 批量查每张画布的节点数（避免 N+1）
        List<String> canvasIds = canvases.stream().map(Canvas::getId).toList();
        Map<String, Long> countByCanvas = nodeRepository.selectList(
            new LambdaQueryWrapper<CanvasNode>().in(CanvasNode::getCanvasId, canvasIds)
        ).stream().collect(Collectors.groupingBy(CanvasNode::getCanvasId, Collectors.counting()));

        // 2.5) 2026-08-09 fix: 缩略图 fallback — canvas.thumbnail 为空时,
        //     查每个画布第一个有 resultUrl 的 image/video 节点(按 createdAt 升序)作为封面
        Map<String, String> thumbByCanvas = new java.util.HashMap<>();
        if (!canvasIds.isEmpty()) {
            List<CanvasNode> firstMedias = nodeRepository.selectList(
                new LambdaQueryWrapper<CanvasNode>()
                    .in(CanvasNode::getCanvasId, canvasIds)
                    .in(CanvasNode::getType, "image", "video")
                    .isNotNull(CanvasNode::getResultUrl)
                    .ne(CanvasNode::getResultUrl, "")
                    .orderByAsc(CanvasNode::getCanvasId, CanvasNode::getCreatedAt)
            );
            for (CanvasNode n : firstMedias) {
                // 每 canvasId 只保留第一个（Map.putIfAbsent）
                thumbByCanvas.putIfAbsent(n.getCanvasId(), n.getResultUrl());
            }
        }

        // 3) 转 DTO
        return canvases.stream()
            .map(c -> CanvasListItem.from(
                c,
                countByCanvas.getOrDefault(c.getId(), 0L).intValue(),
                thumbByCanvas.get(c.getId())
            ))
            .toList();
    }

    @Override
    public Canvas createCanvas(Long userId, CreateCanvasRequest req) {
        Canvas c = new Canvas();
        c.setUserId(userId);
        c.setName(req.getName());
        LocalDateTime now = LocalDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        canvasRepository.insert(c);
        log.debug("Canvas created: id={}, userId={}, name={}", c.getId(), userId, c.getName());
        return c;
    }

    @Override
    public Canvas getCanvas(Long userId, String canvasId) {
        return mustGetOwnedCanvas(userId, canvasId);
    }

    @Override
    public CanvasDetail getCanvasDetail(Long userId, String canvasId) {
        Canvas canvas = mustGetOwnedCanvas(userId, canvasId);

        // 拉该画布所有节点
        List<CanvasNode> nodes = nodeRepository.selectList(
            new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getCanvasId, canvasId)
                .orderByAsc(CanvasNode::getCreatedAt)
        );

        // 转 NodeResponse（脱敏）
        List<CanvasNodeResponse> nodeResponses = nodes.stream()
            .map(CanvasNodeResponse::from)
            .toList();

        // 2026-08-12 修复:视频节点兜底 —— 如果 node.resultUrl 为空,但有最近的 success canvas_task
        //   且 task.resultUrl 有值,把 task.resultUrl 写回 node(并持久化),避免"刷新后视频消失"
        //   触发场景:executeVideoGenAsync 主流程异常中断 / @Scheduled 兜底轮询只更新了 job 表没更新 node 表
        for (CanvasNode n : nodes) {
            if (!"video".equalsIgnoreCase(n.getType())) continue;
            if (n.getResultUrl() != null && !n.getResultUrl().isBlank()) continue;
            try {
                com.jurong.aicenter.entity.CanvasTask lastSuccessTask = taskRepository.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.jurong.aicenter.entity.CanvasTask>()
                        .eq(com.jurong.aicenter.entity.CanvasTask::getNodeId, n.getId())
                        .eq(com.jurong.aicenter.entity.CanvasTask::getStatus, "success")
                        .orderByDesc(com.jurong.aicenter.entity.CanvasTask::getCompletedAt)
                        .last("LIMIT 1")
                );
                if (lastSuccessTask != null
                    && lastSuccessTask.getResultUrl() != null
                    && !lastSuccessTask.getResultUrl().isBlank()) {
                    String url = lastSuccessTask.getResultUrl();
                    n.setResultUrl(url);
                    n.setStatus("success");
                    n.setUpdatedAt(java.time.LocalDateTime.now());
                    nodeRepository.updateById(n);
                    log.info("[getCanvasDetail] 视频节点 result_url 兜底回填: nodeId={}, url={}", n.getId(), url);
                }
            } catch (Exception recoverErr) {
                log.warn("[getCanvasDetail] 视频节点 result_url 兜底失败(非致命): nodeId={}, err={}",
                    n.getId(), recoverErr.getMessage());
            }
        }

        // 解析所有连线（多端口格式）→ EdgeDto 列表
        // 2026-08-10 修复:前端 addNode 只传了上游节点 (upstreamIds),
        // 没同步更新源节点的 downstreamIds,所以原来只从 downstreamIds 拍平的话刷新会丢边。
        // 现在双向拍平:downstreamIds (n -> c.nodeId) + upstreamIds (c.nodeId -> n),合并去重。
        List<CanvasDetail.EdgeDto> edges = new ArrayList<>();
        Set<String> edgeKeys = new HashSet<>();   // 去重 key: from|to
        Set<String> nodeIds = nodes.stream().map(CanvasNode::getId).collect(Collectors.toSet());
        for (CanvasNode n : nodes) {
            // 1) 下游连接:把 downstreamIds 拍平成 edges
            List<NodeConnection> downs = parseConnections(n.getDownstreamIds());
            for (NodeConnection c : downs) {
                if (!nodeIds.contains(c.getNodeId())) {
                    log.warn("Canvas edge dropped: from node {} to missing node {} (port={})",
                        n.getId(), c.getNodeId(), c.getPort());
                    continue;
                }
                String key = n.getId() + "|" + c.getNodeId();
                if (edgeKeys.add(key)) {
                    edges.add(new CanvasDetail.EdgeDto(n.getId(), c.getNodeId(), c.getPort()));
                }
            }
            // 2) 上游连接:把 upstreamIds 反向拍平成 edges (c.nodeId -> n)
            List<NodeConnection> ups = parseConnections(n.getUpstreamIds());
            for (NodeConnection c : ups) {
                if (!nodeIds.contains(c.getNodeId())) {
                    log.warn("Canvas edge dropped: from missing upstream {} to {} (port={})",
                        c.getNodeId(), n.getId(), c.getPort());
                    continue;
                }
                String key = c.getNodeId() + "|" + n.getId();
                if (edgeKeys.add(key)) {
                    edges.add(new CanvasDetail.EdgeDto(c.getNodeId(), n.getId(), c.getPort()));
                }
            }
        }

        return CanvasDetail.from(canvas, nodeResponses, edges);
    }

    @Override
    public Canvas updateCanvas(Long userId, String canvasId, UpdateCanvasRequest req) {
        Canvas c = mustGetOwnedCanvas(userId, canvasId);
        c.setName(req.getName());
        c.setUpdatedAt(LocalDateTime.now());
        canvasRepository.updateById(c);
        return c;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCanvas(Long userId, String canvasId) {
        Canvas canvas = mustGetOwnedCanvas(userId, canvasId);

        // 1. 查该画布所有节点 id
        List<String> nodeIds = nodeRepository.selectList(
            new LambdaQueryWrapper<CanvasNode>().eq(CanvasNode::getCanvasId, canvasId)
        ).stream().map(CanvasNode::getId).toList();

        // 2. 级联删任务
        if (!nodeIds.isEmpty()) {
            taskRepository.delete(
                new LambdaQueryWrapper<CanvasTask>().in(CanvasTask::getNodeId, nodeIds)
            );
            // 3. 级联删节点
            nodeRepository.delete(
                new LambdaQueryWrapper<CanvasNode>().eq(CanvasNode::getCanvasId, canvasId)
            );
        }

        // 4. 删画布本身
        canvasRepository.deleteById(canvasId);

        log.debug("Canvas deleted: id={}, userId={}, cascaded nodes={}",
            canvasId, userId, nodeIds.size());
    }

    // ============= 节点 CRUD =============

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CanvasNode createNode(Long userId, String canvasId, CreateCanvasNodeRequest req) {
        // 1. 解析目标画布（NULL → 默认画布）
        String resolvedCanvasId = resolveCanvasId(userId, canvasId);

        CanvasNode node = new CanvasNode();
        node.setUserId(userId);
        node.setCanvasId(resolvedCanvasId);
        node.setType(req.getType());
        node.setTitle(req.getTitle());
        node.setContent(req.getContent());
        node.setAssetId(req.getAssetId());
        node.setPositionX(req.getPositionX() == null ? 0 : req.getPositionX());
        node.setPositionY(req.getPositionY() == null ? 0 : req.getPositionY());
        node.setUpstreamIds(serializeConnections(req.getUpstreamIds()));
        node.setDownstreamIds(serializeConnections(req.getDownstreamIds()));
        node.setStatus("idle");
        LocalDateTime now = LocalDateTime.now();
        node.setCreatedAt(now);
        node.setUpdatedAt(now);
        nodeRepository.insert(node);

        // 触绘画布 updated_at（让"我的创作"列表把刚加节点的画布顶上去）
        touchCanvas(resolvedCanvasId);

        log.debug("Canvas node created: id={}, userId={}, type={}, canvasId={}",
            node.getId(), userId, node.getType(), resolvedCanvasId);
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
        if (req.getUpstreamIds() != null) node.setUpstreamIds(serializeConnections(req.getUpstreamIds()));
        if (req.getDownstreamIds() != null) node.setDownstreamIds(serializeConnections(req.getDownstreamIds()));
        if (req.getSettings() != null) node.setSettings(req.getSettings());
        node.setUpdatedAt(LocalDateTime.now());
        nodeRepository.updateById(node);
        return node;
    }

    @Override
    public CanvasNode getNodeEntity(Long userId, String nodeId) {
        return mustGetOwnedNode(userId, nodeId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteNode(Long userId, String nodeId) {
        CanvasNode node = mustGetOwnedNode(userId, nodeId);
        String canvasId = node.getCanvasId();
        nodeRepository.deleteById(node.getId());
        // 级联删任务
        taskRepository.delete(new LambdaQueryWrapper<CanvasTask>().eq(CanvasTask::getNodeId, nodeId));
        touchCanvas(canvasId);
        log.debug("Canvas node deleted: id={}, userId={}", nodeId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CanvasNode uploadAndCreateNode(Long userId, String canvasId, MultipartFile file,
                                            String title, Integer positionX, Integer positionY) {
        // 1. 基础校验
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "文件不能为空");
        }

        // 2. 判断节点类型（image / video / audio）
        String nodeType = inferCanvasNodeType(file.getContentType(), file.getOriginalFilename());
        if (nodeType == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "不支持的文件类型，仅支持图片/视频/音频");
        }

        // 3. 解析目标画布（NULL → 默认画布）
        String resolvedCanvasId = resolveCanvasId(userId, canvasId);

        // 4. 上传到 MinIO + 写 media_assets（libraryId=null → "我的资产"系统库）
        MediaUploadResponse uploadRes = mediaService.uploadAsset(userId, null, file);

        // 5. 建节点
        CanvasNode node = new CanvasNode();
        node.setUserId(userId);
        node.setCanvasId(resolvedCanvasId);
        node.setType(nodeType);
        String fileName = file.getOriginalFilename() == null ? "未命名" : file.getOriginalFilename();
        node.setTitle(title != null && !title.isBlank() ? title : fileName);
        node.setAssetId(String.valueOf(uploadRes.getId()));
        node.setResultUrl(uploadRes.getUrl());
        node.setPositionX(positionX == null ? 0 : positionX);
        node.setPositionY(positionY == null ? 0 : positionY);
        node.setStatus("success");  // 直接成功（不需要 AI 生成）
        LocalDateTime now = LocalDateTime.now();
        node.setCreatedAt(now);
        node.setUpdatedAt(now);
        nodeRepository.insert(node);

        // 6. 触绘画布（让"我的创作"列表把刚加节点的画布顶上去）
        touchCanvas(resolvedCanvasId);

        log.debug("Canvas node created via upload: id={}, type={}, userId={}, filename={}, assetId={}",
            node.getId(), nodeType, userId, fileName, uploadRes.getId());
        return node;
    }

    /**
     * 推断画布节点类型（image / video / audio），按 mime → 扩展名 fallback。
     */
    private String inferCanvasNodeType(String mime, String filename) {
        String mimeLower = mime == null ? "" : mime.toLowerCase().trim();
        if (mimeLower.startsWith("image/")) return "image";
        if (mimeLower.startsWith("video/")) return "video";
        if (mimeLower.startsWith("audio/")) return "audio";

        if (filename == null) return null;
        String lower = filename.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
            || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp")) return "image";
        if (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov")
            || lower.endsWith(".avi") || lower.endsWith(".mkv")) return "video";
        if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg")
            || lower.endsWith(".m4a") || lower.endsWith(".aac")) return "audio";

        return null;
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

        // 2026-08-09 新增:换装场景快速路由
        //   条件: image 节点 + 有上游节点(可以是 1 个 source + N 个衣服,或多个 image 节点被当作 source+materials)
        //   效果: 不走 generate 常规流程,直接调用 ClothingTransferService
        if ("image".equalsIgnoreCase(node.getType())
                && req.getAssetIds() != null && !req.getAssetIds().isEmpty()) {
            // 收集上游 image 节点 id(自动过滤其他类型)
            java.util.List<String> imageUpstreamIds = new java.util.ArrayList<>();
            for (String aid : req.getAssetIds()) {
                try {
                    CanvasNode upNode = mustGetOwnedNode(userId, aid);
                    if ("image".equalsIgnoreCase(upNode.getType())) {
                        imageUpstreamIds.add(aid);
                    }
                } catch (Exception ignore) {}
            }
            // 有 materialNodeIds 或 imageUpstreamIds.size() >= 2 → 走换装
            boolean hasMaterials = req.getMaterialNodeIds() != null && !req.getMaterialNodeIds().isEmpty();
            if (hasMaterials || imageUpstreamIds.size() >= 2) {
                // 第 1 个 image 节点作为主体帧,其余 + materialNodeIds 作为衣服参考
                String sourceNodeId = imageUpstreamIds.get(0);
                java.util.List<String> materialIds = new java.util.ArrayList<>();
                if (hasMaterials) materialIds.addAll(req.getMaterialNodeIds());
                if (imageUpstreamIds.size() > 1) {
                    materialIds.addAll(imageUpstreamIds.subList(1, imageUpstreamIds.size()));
                }
                if (!materialIds.isEmpty()) {
                    log.debug("[generate] 检测到换装场景: nodeId={}, source={}, materials={}, userInstruction={}",
                        nodeId, sourceNodeId, materialIds, req.getUserInstruction());
                    // 2026-08-10 v5:传 nodeId(用户点击的目标节点)作为结果落点,
                    // sourceNodeId(抽帧图节点)只读,不写回,避免刷新后覆盖原图
                    // 2026-08-11:透传 userInstruction(用户自然语言描述)
                    return transferClothing(userId, nodeId, sourceNodeId, materialIds, req.getUserInstruction());
                }
            }
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
            if (req.getInputs() != null) {
                // 注意：DB 列名仍是 asset_ids（向后兼容），存的是多端口 NodeConnection 列表的 JSON
                task.setAssetIds(objectMapper.writeValueAsString(req.getInputs()));
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
        touchCanvas(node.getCanvasId());

        // 4. 跨 Bean 调用 @Async（注入的是 CanvasAsyncExecutor 代理，触发异步）
        asyncExecutor.executeGenerationAsync(task, node, req, userId);

        // 5. 立刻返回 pending 响应
        return new GenerateCanvasNodeResponse(
            task.getId(), nodeId, "pending",
            null, null, task.getCreditsEstimated(),
            java.util.Collections.emptyList(),
            null  // failMessage: pending 状态无失败原因
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public GenerateCanvasNodeResponse extractAndCaption(Long userId, String nodeId, double fps, String mode) {
        // 1. 鉴权 + 拿节点
        CanvasNode node = mustGetOwnedNode(userId, nodeId);

        // 2. 校验：必须是视频节点
        if (!"video".equalsIgnoreCase(node.getType())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "只有视频节点才能抽帧，当前 type=" + node.getType());
        }

        // 3. 校验：必须已有视频
        if (node.getResultUrl() == null || node.getResultUrl().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "视频节点还没有 resultUrl，请先上传或生成视频");
        }

        // 4. 校验：fps 范围
        if (fps <= 0 || fps > 10) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "fps 必须在 (0, 10] 范围内，实际=" + fps);
        }

        // 4.5 校验：mode 取值
        String safeMode = mode;
        if (safeMode == null || safeMode.isBlank()) safeMode = "both";
        if (!java.util.Set.of("script", "frames", "both").contains(safeMode)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "mode 必须是 script/frames/both，实际=" + safeMode);
        }

        // 5. 写任务记录（pending 状态）
        CanvasTask task = new CanvasTask();
        task.setNodeId(nodeId);
        task.setUserId(userId);
        task.setType("video-frame-caption");
        task.setStatus("pending");
        task.setPrompt("fps=" + fps + ",mode=" + safeMode);
        taskRepository.insert(task);

        // 6. 节点标 running
        node.setStatus("running");
        node.setUpdatedAt(LocalDateTime.now());
        nodeRepository.updateById(node);

        // 7. 异步执行（captionExecutor 线程池抽帧 + 并行 VL caption）
        //    直接传 entity —— 避免 async 线程查不到父事务未提交的 task（事务可见性问题）
        videoFrameCaptionService.executeCaptionAsync(
            task, node, node.getResultUrl(), fps, userId, safeMode);

        log.debug("Canvas video-caption task created: taskId={}, nodeId={}, userId={}, fps={}, mode={}",
            task.getId(), nodeId, userId, fps, safeMode);

        // 8. 立刻返回 pending，前端轮询 /tasks/{taskId}
        return new GenerateCanvasNodeResponse(
            task.getId(), nodeId, "pending", null, null, null,
            java.util.Collections.emptyList(),
            null  // failMessage: pending 状态无失败原因
        );
    }

    /**
     * 2026-08-09 新增:换装(Clothing Transfer) — 3 参数重载
     * 直接在源节点上点换装:target = source,结果覆盖源节点 resultUrl
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GenerateCanvasNodeResponse transferClothing(Long userId, String targetNodeId,
                                                        java.util.List<String> clothingNodeIds) {
        // Controller 直接调用时 targetNodeId 既是 source 也是 target
        return transferClothing(userId, targetNodeId, targetNodeId, clothingNodeIds, null);
    }

    /**
     * 2026-08-10 v5:换装(Clothing Transfer) — 4 参数重载
     * 从 generateNode 自动路由过来:target(右侧激活节点,用户点击的)是结果落点,
     * source(上游抽帧图节点)只读不写,避免刷新后覆盖原图。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GenerateCanvasNodeResponse transferClothing(Long userId, String targetNodeId,
                                                        String sourceNodeId,
                                                        java.util.List<String> clothingNodeIds) {
        return transferClothing(userId, targetNodeId, sourceNodeId, clothingNodeIds, null);
    }

    /**
     * 2026-08-11 新增:5 参数重载,支持 userInstruction(用户自然语言描述,如"换人脸+换沐浴露")
     * 替换上面的 4 参数重载,把 userInstruction 也传到 service。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GenerateCanvasNodeResponse transferClothing(Long userId, String targetNodeId,
                                                        String sourceNodeId,
                                                        java.util.List<String> clothingNodeIds,
                                                        String userInstruction) {
        // 1. 鉴权 + 拿节点
        CanvasNode sourceNode = mustGetOwnedNode(userId, sourceNodeId);
        CanvasNode targetNode = mustGetOwnedNode(userId, targetNodeId);
        // 2026-08-09:源节点可以是 video(抽帧)或 image(本身是拼好的帧网格)
        if (!"video".equalsIgnoreCase(sourceNode.getType())
                && !"image".equalsIgnoreCase(sourceNode.getType())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "源节点必须是 video 或 image,当前 type=" + sourceNode.getType());
        }
        if (sourceNode.getResultUrl() == null || sourceNode.getResultUrl().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "源节点还没有 resultUrl,请先上传或生成");
        }
        // 2. 校验衣服图(>=1 张)
        if (clothingNodeIds == null || clothingNodeIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "至少需要 1 张衣服参考图,实际=" + (clothingNodeIds == null ? 0 : clothingNodeIds.size()));
        }
        for (String cid : clothingNodeIds) {
            CanvasNode cn = mustGetOwnedNode(userId, cid);
            if (!"image".equalsIgnoreCase(cn.getType())) {
                throw new BusinessException(ErrorCode.INVALID_PARAM,
                    "衣服节点 " + cid + " 必须是 image 类型,实际=" + cn.getType());
            }
        }
        // 3. 写任务 — task.nodeId 绑定 target(激活节点),不是 source!
        // 把 userInstruction 也存进 prompt 字段,供后续可能的查询/恢复
        CanvasTask task = new CanvasTask();
        task.setNodeId(targetNodeId);
        task.setUserId(userId);
        task.setType("clothing-transfer");
        task.setStatus("pending");
        task.setPrompt("clothingNodeIds=" + String.join(",", clothingNodeIds)
            + ";userInstruction=" + (userInstruction == null ? "" : userInstruction));
        taskRepository.insert(task);

        // 4. target(激活节点)标 running;如果 target != source,source 不动保持原图
        targetNode.setStatus("running");
        targetNode.setUpdatedAt(LocalDateTime.now());
        nodeRepository.updateById(targetNode);

        // 5. 异步执行 — 传 sourceNode(只读) + userInstruction
        clothingTransferService.executeTransferAsync(task, sourceNode, clothingNodeIds, userId, userInstruction);

        log.debug("Canvas clothing-transfer task created: taskId={}, targetNodeId={}, sourceNodeId={}, userId={}, clothingNodeIds={}, userInstruction={}",
            task.getId(), targetNodeId, sourceNodeId, userId, clothingNodeIds, userInstruction);

        // 6. 立刻返回 pending
        return new GenerateCanvasNodeResponse(
            task.getId(), targetNodeId, "pending", null, null, null,
            java.util.Collections.emptyList(),
            null  // failMessage: pending 状态无失败原因
        );
    }

    /**
     * 2026-08-10 新增:画布视频生成(从多源输入生成新视频)
     * 模式同 transferClothing: 1.鉴权+拿节点 2.写任务 3.标节点 running 4.异步执行 5.返回 pending
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GenerateCanvasNodeResponse generateVideoFromCanvas(Long userId, String videoGenNodeId,
                                                                int duration, String resolution) {
        // 2026-08-13:在方法开头加 INFO 日志,确保所有触发都能看到(避免后续 idempotent 命中静默 return)
        log.info("┌─ [video-gen] generateVideoFromCanvas ENTRY: userId={}, videoGenNodeId={}, duration={}s, resolution={}",
            userId, videoGenNodeId, duration, resolution);

        // 1. 鉴权 + 拿节点(可接受 video 节点 —— 合并后 video 节点同时作为上传节点和生成节点)
        CanvasNode videoGenNode = mustGetOwnedNode(userId, videoGenNodeId);
        log.info("│  [video-gen] 节点已获取: id={}, upstreamIds={}, status={}",
            videoGenNode.getId(), videoGenNode.getUpstreamIds(), videoGenNode.getStatus());

        // 2026-08-14 删除幂等性检查 — 同一 videoGenNodeId 重新触发应直接调 NewAPI 重新提交

        // 上游连接必须存在(脚本拆解文本 + 换装总图)
        if (videoGenNode.getUpstreamIds() == null || videoGenNode.getUpstreamIds().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "视频节点没有上游输入,请先连接脚本拆解文本节点和换装总图节点");
        }

        // 默认参数(优先用节点 settings 中的配置,其次用请求参数,最后用默认值)
        if (videoGenNode.getSettings() != null && !videoGenNode.getSettings().isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode settings = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(videoGenNode.getSettings());
                if (duration <= 0 && settings.has("duration")) duration = settings.get("duration").asInt();
                if ((resolution == null || resolution.isBlank()) && settings.has("resolution")) {
                    resolution = settings.get("resolution").asText();
                }
                log.debug("[video-gen] 从节点 settings 读取参数: duration={}, resolution={}", duration, resolution);
            } catch (Exception e) {
                log.warn("[video-gen] 解析节点 settings 失败(非致命): {}", e.getMessage());
            }
        }
        if (duration <= 0) duration = 9;
        if (resolution == null || resolution.isBlank()) resolution = "720P";

        // 2. 写任务
        CanvasTask task = new CanvasTask();
        task.setNodeId(videoGenNodeId);
        task.setUserId(userId);
        task.setType("video-generation");
        task.setStatus("pending");
        task.setPrompt("videoGenNodeId=" + videoGenNodeId);
        taskRepository.insert(task);

        // 3. 视频生成节点标 running
        videoGenNode.setStatus("running");
        videoGenNode.setUpdatedAt(LocalDateTime.now());
        nodeRepository.updateById(videoGenNode);

        // 4. 异步执行
        canvasVideoGenService.executeVideoGenAsync(task, videoGenNode, duration, resolution, userId);

        log.debug("Canvas video-generation task created: taskId={}, videoGenNodeId={}, userId={}, duration={}s, resolution={}",
            task.getId(), videoGenNodeId, userId, duration, resolution);

        // 5. 立刻返回 pending
        return new GenerateCanvasNodeResponse(
            task.getId(), videoGenNodeId, "pending", null, null, null,
            java.util.Collections.emptyList(),
            null  // failMessage: pending 状态无失败原因
        );
    }

    @Override
    public GenerateCanvasNodeResponse getTaskStatus(Long userId, String taskId) {
        CanvasTask task = taskRepository.selectById(taskId);
        if (task == null || !task.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        // 解析本次任务新建的节点 ID 列表（抽帧 / 脚本拆解 sidecar 写入）
        List<String> createdIds = List.of();
        if (task.getCreatedNodeIds() != null && !task.getCreatedNodeIds().isBlank()) {
            try {
                createdIds = objectMapper.readValue(
                    task.getCreatedNodeIds(), new TypeReference<List<String>>() {});
            } catch (Exception parseErr) {
                log.warn("createdNodeIds JSON parse failed for taskId={}: {}",
                    taskId, parseErr.getMessage());
            }
        }
        GenerateCanvasNodeResponse resp = new GenerateCanvasNodeResponse(
            task.getId(),
            task.getNodeId(),
            task.getStatus(),
            task.getTextResult(),
            task.getResultUrl(),
            task.getCreditsEstimated(),
            java.util.Collections.emptyList(),
            null  // failMessage 占位,下面再根据 status 设置
        );
        resp.setCreatedNodeIds(createdIds);
        // 2026-08-10 修复:把失败原因透传给前端,前端轮询拿到 status="failed" 时能显示具体原因
        if ("failed".equalsIgnoreCase(task.getStatus())) {
            String err = task.getErrorMessage();
            // 截断到合理长度,防止超长 Java 堆栈撑爆前端 toast / 数据库 fail_reason VARCHAR(500)
            if (err != null && err.length() > 450) {
                err = err.substring(0, 450) + "...";
            }
            resp.setFailMessage(err);
        }
        return resp;
    }

    // ============= 内部 =============

    /**
     * 解析节点请求里的 canvasId。
     *   - NULL  → 自动用/建用户默认画布
     *   - 非空  → 校验归属（必须是同一 userId 的画布）
     */
    private String resolveCanvasId(Long userId, String canvasId) {
        if (canvasId == null || canvasId.isBlank()) {
            return getOrCreateDefaultCanvas(userId).getId();
        }
        Canvas c = canvasRepository.selectById(canvasId);
        if (c == null || !c.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "画布不存在");
        }
        return canvasId;
    }

    /**
     * 拿用户默认画布（不存在就建一张）。
     * 默认画布命名 "默认画布"，按用户唯一。
     */
    private Canvas getOrCreateDefaultCanvas(Long userId) {
        Canvas existing = canvasRepository.selectOne(
            new LambdaQueryWrapper<Canvas>()
                .eq(Canvas::getUserId, userId)
                .eq(Canvas::getName, DEFAULT_CANVAS_NAME)
                .last("LIMIT 1")
        );
        if (existing != null) return existing;

        Canvas c = new Canvas();
        c.setUserId(userId);
        c.setName(DEFAULT_CANVAS_NAME);
        LocalDateTime now = LocalDateTime.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        canvasRepository.insert(c);
        log.debug("Default canvas auto-created: userId={}, id={}", userId, c.getId());
        return c;
    }

    /** 序列化 List&lt;NodeConnection&gt; → JSON 字符串（null/空都安全） */
    private String serializeConnections(List<NodeConnection> conns) {
        if (conns == null || conns.isEmpty()) return null;
        // 过滤掉 nodeId 为空的脏数据
        List<NodeConnection> clean = conns.stream()
            .filter(c -> c != null && c.getNodeId() != null && !c.getNodeId().isBlank())
            .toList();
        if (clean.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(clean);
        } catch (Exception e) {
            log.warn("serializeConnections failed: {}", e.getMessage());
            return null;
        }
    }

    /** 反序列化 JSON 字符串 → List&lt;NodeConnection&gt;（兼容旧 List&lt;String&gt; 格式） */
    private List<NodeConnection> parseConnections(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            List<NodeConnection> parsed = objectMapper.readValue(json,
                new TypeReference<List<NodeConnection>>() {});
            return parsed == null ? Collections.emptyList() : parsed;
        } catch (Exception e) {
            // 兼容旧格式 [ "uuid1", "uuid2" ]
            try {
                List<String> old = objectMapper.readValue(json, new TypeReference<List<String>>() {});
                return old.stream()
                    .map(id -> new NodeConnection("default", id))
                    .toList();
            } catch (Exception e2) {
                log.warn("parseConnections failed: {}", e2.getMessage());
                return Collections.emptyList();
            }
        }
    }

    /** 触绘画布 updated_at（用于"我的创作"列表排序） */
    private void touchCanvas(String canvasId) {
        if (canvasId == null) return;
        try {
            Canvas c = canvasRepository.selectById(canvasId);
            if (c != null) {
                c.setUpdatedAt(LocalDateTime.now());
                canvasRepository.updateById(c);
            }
        } catch (Exception e) {
            log.warn("touchCanvas failed for {}: {}", canvasId, e.getMessage());
        }
    }

    private Canvas mustGetOwnedCanvas(Long userId, String canvasId) {
        Canvas c = canvasRepository.selectById(canvasId);
        if (c == null || !c.getUserId().equals(userId)) {
            log.warn("Unauthorized canvas access: userId={} tried canvasId={} owned by={}",
                userId, canvasId, c == null ? "null" : c.getUserId());
            throw new BusinessException(ErrorCode.NOT_FOUND, "画布不存在");
        }
        return c;
    }

    private CanvasNode mustGetOwnedNode(Long userId, String nodeId) {
        CanvasNode node = nodeRepository.selectById(nodeId);
        if (node == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "节点不存在");
        }
        if (!node.getUserId().equals(userId)) {
            log.warn("Unauthorized canvas node access: userId={} tried nodeId={} owned by={}",
                userId, nodeId, node.getUserId());
            throw new BusinessException(ErrorCode.NOT_FOUND, "节点不存在");
        }
        return node;
    }

    }