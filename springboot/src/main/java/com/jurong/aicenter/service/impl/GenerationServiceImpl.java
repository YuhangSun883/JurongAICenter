package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jurong.aicenter.client.ComfyUIClient;
import com.jurong.aicenter.dto.generation.GenerateRequest;
import com.jurong.aicenter.dto.generation.GenerateResponse;
import com.jurong.aicenter.entity.Job;
import com.jurong.aicenter.entity.Workflow;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.JobRepository;
import com.jurong.aicenter.repository.WorkflowRepository;
import com.jurong.aicenter.service.GenerationService;
import com.jurong.aicenter.service.MediaService;
import com.jurong.aicenter.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Generation 实现 - Phase 4 C 负责完整实现
 *
 * submit() 完整流程：
 *   1. 解析最终 workflow JSON（workflowOverride 优先，否则按 workflowId 加载并替换 {{input}} 占位符）
 *   2. 鉴权（自己的 / 模板 / 公开）
 *   3. 调 ComfyUIClient.submit() 拿 prompt_id
 *   4. 写 jobs 表（status=RUNNING，snapshot 入库）
 *   5. 返回 GenerateResponse
 *
 * 后续由 @Scheduled pollRunningJobs() 轮询 + 落盘 MinIO。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationServiceImpl implements GenerationService {

    private final ComfyUIClient comfyUIClient;
    private final JobRepository jobRepository;
    private final WorkflowRepository workflowRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final MediaService mediaService; // V8 资产库：AI 完成的素材写入 "AI 生成结果"

    /** C3 - RUNNING 任务最大存活时间（视频节点可达 5+ 分钟，给 1h 余量） */
    private static final Duration MAX_RUNNING_DURATION = Duration.ofMinutes(60);

    @Override
    public GenerateResponse submit(Long userId, GenerateRequest request) {
        // 1. 解析最终要提交的 workflow JSON
        JsonNode workflowJson = resolveWorkflow(userId, request);

        // 2. 提交到 ComfyUI（失败已在 ComfyUIClient 内部转 BusinessException）
        String promptId = comfyUIClient.submit(workflowJson);
        log.info("ComfyUI submitted: userId={}, promptId={}", userId, promptId);

        // 3. 写 jobs 表（直接入 RUNNING，因为 /prompt 已成功入队）
        Job job = new Job();
        job.setUserId(userId);
        job.setWorkflowId(request.getWorkflowId());
        job.setTemplateId(request.getTemplateId());
        job.setComfyuiPromptId(promptId);
        job.setStatus("RUNNING");
        job.setInputsSnapshot(toJsonString(request.getInputs()));
        job.setGraphSnapshot(workflowJson.toString());
        job.setCreditsCost(0);  // Phase 8 启用
        job.setStartedAt(LocalDateTime.now());
        job.setCreatedAt(LocalDateTime.now());
        jobRepository.insert(job);

        log.info("Job created: id={}, userId={}, promptId={}", job.getId(), userId, promptId);
        return new GenerateResponse(job.getId(), job.getStatus(), promptId);
    }

    /**
     * 解析最终要提交的 workflow JSON。
     * 优先级：workflowOverride > workflowId（数据库加载 + 替换 inputs 占位符）
     */
    private JsonNode resolveWorkflow(Long userId, GenerateRequest request) {
        if (request.getWorkflowOverride() != null && !request.getWorkflowOverride().isNull()) {
            log.debug("submit() using workflowOverride, userId={}", userId);
            return request.getWorkflowOverride();
        }

        if (request.getWorkflowId() == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                "必须提供 workflowId 或 workflowOverride");
        }

        Workflow workflow = workflowRepository.selectById(request.getWorkflowId());
        if (workflow == null) {
            throw new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND);
        }

        // 鉴权：自己的 / 模板 / 公开
        boolean isOwn = workflow.getUserId() != null && workflow.getUserId().equals(userId);
        boolean isTemplate = Boolean.TRUE.equals(workflow.getIsTemplate());
        boolean isPublic = Boolean.TRUE.equals(workflow.getIsPublic());
        if (!isOwn && !isTemplate && !isPublic) {
            throw new BusinessException(ErrorCode.WORKFLOW_ACCESS_DENIED);
        }

        // 解析 graphJson
        JsonNode graph;
        try {
            graph = objectMapper.readTree(workflow.getGraphJson());
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.WORKFLOW_INVALID,
                "graphJson 解析失败: " + e.getMessage());
        }

        // 转换为 ComfyUI prompt 格式
        graph = convertGraphToPrompt(graph);

        // 替换 inputs 占位符
        if (request.getInputs() != null && !request.getInputs().isEmpty()) {
            graph = applyInputs(graph, request.getInputs());
        }

        return graph;
    }

    /**
     * 将前端画布格式转换为 ComfyUI prompt 格式。
     * 画布格式: { nodes: [{id, type, widgets_values, ...}], links: [[fromNode, fromSlot, toNode, toSlot]], ... }
     * Prompt格式: { "nodeId": { class_type, inputs: {...} } }
     */
    private JsonNode convertGraphToPrompt(JsonNode graph) {
        // 已经是 prompt 格式（以节点id为key的对象），直接返回
        if (graph.isObject() && !graph.has("nodes")) {
            return graph;
        }

        ObjectNode prompt = objectMapper.createObjectNode();
        JsonNode nodes = graph.get("nodes");
        JsonNode links = graph.get("links");

        // 构建链接映射: targetNodeId -> Map<targetSlot, [sourceNodeId, sourceSlot]>
        Map<Integer, Map<Integer, int[]>> linkMap = new java.util.HashMap<>();
        if (links != null && links.isArray()) {
            for (JsonNode link : links) {
                if (link.size() >= 4) {
                    int sourceNode = link.get(0).asInt();
                    int sourceSlot = link.get(1).asInt();
                    int targetNode = link.get(2).asInt();
                    int targetSlot = link.get(3).asInt();
                    linkMap.computeIfAbsent(targetNode, k -> new java.util.HashMap<>())
                            .put(targetSlot, new int[]{sourceNode, sourceSlot});
                }
            }
        }

        if (nodes != null && nodes.isArray()) {
            for (JsonNode node : nodes) {
                int nodeId = node.get("id").asInt();
                String nodeType = node.get("type").asText();
                ObjectNode promptNode = objectMapper.createObjectNode();
                promptNode.put("class_type", nodeType);

                ObjectNode inputs = objectMapper.createObjectNode();
                JsonNode widgetsValues = node.get("widgets_values");
                Map<Integer, int[]> nodeLinks = linkMap.getOrDefault(nodeId, new java.util.HashMap<>());

                // 根据节点类型映射 widget_values 到 inputs
                switch (nodeType) {
                    case "LoadImage":
                        mapInput(inputs, "image", widgetsValues, 0, nodeLinks, 0);
                        mapInput(inputs, "subfolder", widgetsValues, 1, null, -1);
                        mapInput(inputs, "type", widgetsValues, 2, null, -1);
                        break;
                    case "SaveImage":
                        mapInput(inputs, "images", widgetsValues, -1, nodeLinks, 0);
                        mapInput(inputs, "filename_prefix", widgetsValues, 0, null, -1);
                        break;
                    case "JurongTextToImage":
                        mapInput(inputs, "prompt", widgetsValues, 0, null, -1);
                        mapInput(inputs, "negative_prompt", widgetsValues, 1, null, -1);
                        mapInput(inputs, "width", widgetsValues, 2, null, -1);
                        mapInput(inputs, "height", widgetsValues, 3, null, -1);
                        mapInput(inputs, "seed", widgetsValues, 4, null, -1);
                        break;
                    case "JurongImageToImage":
                        mapInput(inputs, "image", widgetsValues, -1, nodeLinks, 0);
                        mapInput(inputs, "strength", widgetsValues, 1, null, -1);
                        mapInput(inputs, "image_ref", widgetsValues, 2, null, -1);
                        mapInput(inputs, "subfolder", widgetsValues, 3, null, -1);
                        mapInput(inputs, "type", widgetsValues, 4, null, -1);
                        break;
                    case "JurongImageToVideo":
                        mapInput(inputs, "image", widgetsValues, -1, nodeLinks, 0);
                        mapInput(inputs, "prompt", widgetsValues, 1, null, -1);
                        mapInput(inputs, "negative_prompt", widgetsValues, 2, null, -1);
                        mapInput(inputs, "width", widgetsValues, 3, null, -1);
                        mapInput(inputs, "height", widgetsValues, 4, null, -1);
                        mapInput(inputs, "duration", widgetsValues, 5, null, -1);
                        break;
                    default:
                        // 未知节点类型，尝试通用处理
                        if (widgetsValues != null && widgetsValues.isArray()) {
                            for (int i = 0; i < widgetsValues.size(); i++) {
                                inputs.set("widget_" + i, widgetsValues.get(i));
                            }
                        }
                        break;
                }

                promptNode.set("inputs", inputs);
                prompt.set(String.valueOf(nodeId), promptNode);
            }
        }

        log.info("Converted graph to prompt format: {} nodes", prompt.size());
        return prompt;
    }

    /**
     * 映射单个输入字段：优先用链接引用（如果有），否则用 widget_value
     */
    private void mapInput(ObjectNode inputs, String inputName, JsonNode widgetsValues, int widgetIndex,
                          Map<Integer, int[]> nodeLinks, int linkSlot) {
        // 优先检查链接引用
        if (nodeLinks != null && linkSlot >= 0 && nodeLinks.containsKey(linkSlot)) {
            int[] ref = nodeLinks.get(linkSlot);
            ArrayNode refNode = objectMapper.createArrayNode();
            refNode.add(ref[0]);
            refNode.add(ref[1]);
            inputs.set(inputName, refNode);
            return;
        }
        // 使用 widget_value
        if (widgetsValues != null && widgetIndex >= 0 && widgetIndex < widgetsValues.size()) {
            inputs.set(inputName, widgetsValues.get(widgetIndex));
        }
    }

    /**
     * 把 request.inputs 里的 key-value 替换进 workflow graph 中所有形如 {{key}} 的占位符。
     * 仅替换文本值；其他类型 / 已绑定的引用（如 ["4", 0]）不动。
     */
    private JsonNode applyInputs(JsonNode graph, Map<String, Object> inputs) {
        JsonNode inputValues = objectMapper.valueToTree(inputs);
        ObjectNode root = (ObjectNode) graph.deepCopy();

        Iterator<Map.Entry<String, JsonNode>> nodeIter = root.fields();
        while (nodeIter.hasNext()) {
            Map.Entry<String, JsonNode> entry = nodeIter.next();
            JsonNode node = entry.getValue();
            if (!(node instanceof ObjectNode)) continue;

            JsonNode inputsField = node.get("inputs");
            if (!(inputsField instanceof ObjectNode)) continue;

            ObjectNode inputsObj = (ObjectNode) inputsField;
            Iterator<Map.Entry<String, JsonNode>> inputIter = inputsObj.fields();
            while (inputIter.hasNext()) {
                Map.Entry<String, JsonNode> inputEntry = inputIter.next();
                JsonNode value = inputEntry.getValue();
                if (!value.isTextual()) continue;

                String text = value.asText();
                String key = extractPlaceholderKey(text);
                if (key != null && inputValues.has(key)) {
                    inputsObj.set(inputEntry.getKey(), inputValues.get(key));
                }
            }
        }
        return root;
    }

    /** 形如 {{prompt}} → "prompt"；不是占位符返回 null */
    private String extractPlaceholderKey(String text) {
        if (text == null || text.length() < 4) return null;
        if (!text.startsWith("{{") || !text.endsWith("}}")) return null;
        return text.substring(2, text.length() - 2).trim();
    }

    private String toJsonString(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("toJsonString failed: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Job getJob(Long jobId, Long userId) {
        Job job = jobRepository.selectById(jobId);
        if (job == null) throw new BusinessException(ErrorCode.NOT_FOUND);
        if (!job.getUserId().equals(userId)) throw new BusinessException(ErrorCode.FORBIDDEN);
        return job;
    }

    @Override
    public List<Job> listJobs(Long userId, int page, int pageSize) {
        // TODO(C): 分页查询（按 created_at 倒序）
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "listJobs() not implemented yet — see TODO(C)");
    }

    @Override
    @Scheduled(fixedDelay = 2000)
    public void pollRunningJobs() {
        // 2026-08-11:tick 和 "无任务" 日志降到 debug,避免每 2 秒刷屏(原来 95% 的日志都是这种噪声)
        log.debug("[GEN-POLL] tick, thread={}", Thread.currentThread().getName());
        List<Job> runningJobs;
        try {
            // 2026-08-10 修复:过滤掉 templateId="image-to-video" 的 job,避免 GenerationService
            // 误把 NewAPI 提交的图生视频 job 当成 ComfyUI job 处理,错误地标 FAILED。
            // image-to-video job 由 VideoGenerationServiceImpl.pollRunningVideoJobs 专门处理。
            runningJobs = jobRepository.selectList(
                new LambdaQueryWrapper<Job>()
                    .eq(Job::getStatus, "RUNNING")
                    .ne(Job::getTemplateId, "image-to-video")
            );
        } catch (Exception e) {
            log.error("pollRunningJobs: query failed", e);
            return;
        }
        if (runningJobs.isEmpty()) {
            log.debug("[GEN-POLL] 当前没有 RUNNING 非图生视频 job");
            return;
        }
        log.info("[GEN-POLL] 发现 {} 个 RUNNING 非图生视频 job", runningJobs.size());
        for (Job job : runningJobs) {
            try {
                processOneJob(job);
            } catch (Exception e) {
                // 单个 job 失败不影响其它
                log.error("pollRunningJobs: job {} failed: {}", job.getId(), e.getMessage(), e);
            }
        }
    }

    /** 处理单个 RUNNING job：超时 → FAILED；查 history → COMPLETED / FAILED / 跳过 */
    private void processOneJob(Job job) {
        // 2026-08-10 二次保险:虽然 pollRunningJobs 已经过滤了 templateId="image-to-video",
        // 但为了防止某些 MyBatis-Plus 边界情况(比如 null),在 processOneJob 里再判断一次。
        // image-to-video job 应该由 VideoGenerationServiceImpl.pollRunningVideoJobs 专门处理。
        if ("image-to-video".equals(job.getTemplateId())) {
            log.info("[GEN-POLL] 跳过 image-to-video job {} (由 VideoGenerationService 处理)", job.getId());
            return;
        }
        if (job.getComfyuiPromptId() == null || job.getComfyuiPromptId().isEmpty()) {
            log.warn("job {} has no comfyuiPromptId, marking FAILED", job.getId());
            markFailed(job, "missing comfyuiPromptId");
            return;
        }

        // 超时检测（避免永远轮询失败任务）
        if (job.getStartedAt() != null
            && Duration.between(job.getStartedAt(), LocalDateTime.now()).compareTo(MAX_RUNNING_DURATION) > 0) {
            markFailed(job, "timeout: RUNNING > " + MAX_RUNNING_DURATION.toMinutes() + "min");
            return;
        }

        // 查 ComfyUI history
        JsonNode history;
        try {
            history = comfyUIClient.pollHistory(job.getComfyuiPromptId());
        } catch (BusinessException e) {
            // COMFYUI_UNREACHABLE — 留到下次重试
            log.warn("job {} poll history failed: {}", job.getId(), e.getMessage());
            return;
        }
        if (history == null) {
            // 还在跑
            return;
        }

        JsonNode entry = history.get(job.getComfyuiPromptId());
        if (entry == null) {
            // history 返回了但没我们的 entry（异常情况），跳过
            return;
        }

        // 失败检测
        String errorMsg = extractErrorMessage(entry);
        if (errorMsg != null) {
            markFailed(job, errorMsg);
            return;
        }

        // 完成检测
        JsonNode status = entry.get("status");
        boolean completed = status != null && status.path("completed").asBoolean(false);
        if (!completed) {
            // 还在跑（ComfyUI 还没标 completed）
            return;
        }

        // 成功：下载产物 + 上传 MinIO + 改状态
        List<String> resultUrls = extractAndUploadOutputs(job, entry);
        markCompleted(job, resultUrls);
    }

    /** 从 history entry 的 status / messages 里找错误信息；无错误返回 null */
    private String extractErrorMessage(JsonNode entry) {
        JsonNode status = entry.get("status");
        if (status != null) {
            String statusStr = status.path("status_str").asText("");
            if ("error".equalsIgnoreCase(statusStr)) {
                return "ComfyUI status_str=error: " + entry.toString();
            }
        }
        JsonNode messages = entry.get("messages");
        if (messages != null && messages.isArray()) {
            for (JsonNode msg : messages) {
                if (msg.isArray() && msg.size() >= 2) {
                    String type = msg.get(0).asText("");
                    if (type.contains("error") || type.contains("execution_error")) {
                        return "ComfyUI " + type + ": " + msg.get(1).toString();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 提取 outputs 里所有文件 → 流式下载 → 流式上传 MinIO → 收集 URL
     * 支持标准 ComfyUI 格式：outputs.{nodeId}.images[] / videos[]
     * 也支持字符串类型输出（如 video_path 直接作为文件路径）
     */
    private List<String> extractAndUploadOutputs(Job job, JsonNode entry) {
        List<String> urls = new ArrayList<>();
        JsonNode outputs = entry.get("outputs");
        if (outputs == null || !outputs.isObject()) return urls;

        Iterator<Map.Entry<String, JsonNode>> nodeIter = outputs.fields();
        while (nodeIter.hasNext()) {
            Map.Entry<String, JsonNode> nodeEntry = nodeIter.next();
            JsonNode node = nodeEntry.getValue();
            if (!node.isObject()) continue;

            // 1. 数组格式（images/videos/audios）
            collectMediaList(job, node.get("images"), "image", urls);
            collectMediaList(job, node.get("videos"), "video", urls);
            collectMediaList(job, node.get("audios"), "audio", urls);

            // 2. 字符串格式（video_path 等直接作为文件路径的输出）
            collectStringPaths(job, node, urls);

            // 3. ui 字典（Jurong 节点返回 {"ui": {"newapi_task_id":[...], "video_path":[...]}, "result":(...)}）
        collectUiPaths(job, node.get("ui"), urls);

        // 4. 顶层 video_path 数组（JurongImageToVideo 实际格式：outputs[nodeId].video_path=[...]）
        collectTopLevelVideoPath(job, node, urls);
    }
    return urls;
}

    /** 处理字符串类型的文件路径输出（如 JurongImageToVideo 的 video_path）
     * 视频保存在 ComfyUI 容器内 /app/output/jurong_videos/，需通过 ComfyUI /view 接口下载
     */
    private void collectStringPaths(Job job, JsonNode node, List<String> urls) {
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            JsonNode value = field.getValue();

            // 只处理字符串类型的 video_path
            if (!value.isTextual()) continue;
            String path = value.asText();
            if (path == null || path.isEmpty()) continue;
            if (!"video_path".equalsIgnoreCase(key)) continue;

            try {
                // 从路径提取文件名和 subfolder
                // 路径格式: /app/output/jurong_videos/xxx.mp4
                String filename = path.contains("/") ?
                    path.substring(path.lastIndexOf('/') + 1) : path;

                // 从 /app/output/xxx/yyy.ext 提取 subfolder = xxx
                String subfolder = "";
                if (path.contains("/output/")) {
                    String afterOutput = path.substring(path.indexOf("/output/") + 8);
                    int lastSlash = afterOutput.lastIndexOf('/');
                    if (lastSlash > 0) {
                        subfolder = afterOutput.substring(0, lastSlash);
                    }
                }

                log.info("job {} downloading video from ComfyUI: filename={}, subfolder={}", job.getId(), filename, subfolder);

                // 通过 ComfyUI /view 接口下载
                try (InputStream is = comfyUIClient.downloadStream(filename, subfolder, "output")) {
                    String contentType = inferContentType(filename, "video");
                    String ext = extractExt(filename);
                    StorageService.UploadResult up = storageService.uploadAiMedia(
                        job.getUserId(), ext, is, contentType);
                    urls.add(up.url());
                    log.info("job {} uploaded video {} → {}", job.getId(), filename, up.objectKey());
                    try {
                        mediaService.recordAiGenerated(
                            job.getUserId(), "video", filename, contentType, 0L,
                            up.objectKey(), "video", String.valueOf(job.getId()));
                    } catch (Exception e) {
                        log.warn("recordAiGenerated failed for job {}: {}", job.getId(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("job {} download/upload video failed for {}: {}", job.getId(), path, e.getMessage());
            }
        }
    }

    /**
     * 处理 ui 字典：Jurong 节点返回 {"ui": {"video_path":["/app/output/.../xxx.mp4"], "newapi_task_id":[...]}, "result":(...)}
     * 视频文件路径在 ui.video_path 数组里，需要下载并上传 MinIO
     */
    private void collectUiPaths(Job job, JsonNode ui, List<String> urls) {
        if (ui == null || !ui.isObject()) return;

        JsonNode videoPathArr = ui.get("video_path");
        if (videoPathArr == null || !videoPathArr.isArray()) return;

        for (JsonNode pathNode : videoPathArr) {
            if (!pathNode.isTextual()) continue;
            String path = pathNode.asText();
            if (path == null || path.isEmpty()) continue;

            try {
                String filename = path.contains("/") ?
                    path.substring(path.lastIndexOf('/') + 1) : path;
                String subfolder = "";
                if (path.contains("/output/")) {
                    String afterOutput = path.substring(path.indexOf("/output/") + 8);
                    int lastSlash = afterOutput.lastIndexOf('/');
                    if (lastSlash > 0) {
                        subfolder = afterOutput.substring(0, lastSlash);
                    }
                }
                log.info("job {} downloading video from ComfyUI ui.video_path: filename={}, subfolder={}", job.getId(), filename, subfolder);
                try (InputStream is = comfyUIClient.downloadStream(filename, subfolder, "output")) {
                    String contentType = inferContentType(filename, "video");
                    String ext = extractExt(filename);
                    StorageService.UploadResult up = storageService.uploadAiMedia(
                        job.getUserId(), ext, is, contentType);
                    urls.add(up.url());
                    log.info("job {} uploaded video (ui) {} → {}", job.getId(), filename, up.objectKey());
                    try {
                        mediaService.recordAiGenerated(
                            job.getUserId(), "video", filename, contentType, 0L,
                            up.objectKey(), "video", String.valueOf(job.getId()));
                    } catch (Exception e) {
                        log.warn("recordAiGenerated failed for job {}: {}", job.getId(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("job {} download/upload video failed for {}: {}", job.getId(), path, e.getMessage());
            }
        }
    }

    /**
     * 处理 outputs[nodeId].video_path 顶层数组（JurongImageToVideo 实际输出格式）。
     * 历史背景：image_to_video.py 节点把 video_path 放在 outputs 顶层数组而不是 ui.video_path，
     * 旧 collectStringPaths 只看字符串值、collectUiPaths 只看 ui 包装，都会跳过此格式，
     * 导致 MinIO 一直没收到视频。
     */
    private void collectTopLevelVideoPath(Job job, JsonNode node, List<String> urls) {
        JsonNode videoPath = node.get("video_path");
        if (videoPath == null || !videoPath.isArray()) return;
        for (JsonNode p : videoPath) {
            if (!p.isTextual()) continue;
            String path = p.asText();
            if (path == null || path.isEmpty()) continue;
            try {
                String filename = path.contains("/") ?
                    path.substring(path.lastIndexOf('/') + 1) : path;
                String subfolder = "";
                if (path.contains("/output/")) {
                    String afterOutput = path.substring(path.indexOf("/output/") + 8);
                    int lastSlash = afterOutput.lastIndexOf('/');
                    if (lastSlash > 0) {
                        subfolder = afterOutput.substring(0, lastSlash);
                    }
                }
                log.info("job {} downloading video (top-level): filename={}, subfolder={}", job.getId(), filename, subfolder);
                try (InputStream is = comfyUIClient.downloadStream(filename, subfolder, "output")) {
                    String contentType = inferContentType(filename, "video");
                    String ext = extractExt(filename);
                    StorageService.UploadResult up = storageService.uploadAiMedia(
                        job.getUserId(), ext, is, contentType);
                    urls.add(up.url());
                    log.info("job {} uploaded video (top-level) {} → {}", job.getId(), filename, up.objectKey());
                    try {
                        mediaService.recordAiGenerated(
                            job.getUserId(), "video", filename, contentType, 0L,
                            up.objectKey(), "video", String.valueOf(job.getId()));
                    } catch (Exception e) {
                        log.warn("recordAiGenerated failed for job {}: {}", job.getId(), e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("job {} download/upload video (top-level) failed for {}: {}", job.getId(), path, e.getMessage());
            }
        }
    }

    /** 处理单个媒体数组（images/videos/audios） */
    private void collectMediaList(Job job, JsonNode mediaArray, String mediaKind, List<String> urls) {
        if (mediaArray == null || !mediaArray.isArray()) return;
        for (JsonNode item : mediaArray) {
            String filename = item.path("filename").asText("");
            if (filename.isEmpty()) continue;
            String subfolder = item.path("subfolder").asText("");
            String type = item.path("type").asText("output");
            String contentType = inferContentType(filename, mediaKind);

            try (InputStream is = comfyUIClient.downloadStream(filename, subfolder, type)) {
                String ext = extractExt(filename);
                StorageService.UploadResult up = storageService.uploadAiMedia(
                    job.getUserId(), ext, is, contentType);
                urls.add(up.url());
                log.info("job {} uploaded {} → {}", job.getId(), filename, up.objectKey());

                // V8 资产库：AI 完成的素材自动写入 "AI 生成结果" 库
                try {
                    mediaService.recordAiGenerated(
                        job.getUserId(), mediaKind, filename, contentType, 0L,
                        up.objectKey(), mediaKind, String.valueOf(job.getId()));
                } catch (Exception e) {
                    log.warn("recordAiGenerated failed for job {}: {}", job.getId(), e.getMessage());
                }
            } catch (Exception e) {
                log.warn("job {} download/upload failed for {}: {}", job.getId(), filename, e.getMessage());
            }
        }
    }

    /** 根据文件后缀猜 MIME（ComfyUI 不会给） */
    private String inferContentType(String filename, String mediaKind) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        return "application/octet-stream";
    }

    private String extractExt(String filename) {
        if (filename == null) return "bin";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "bin";
        return filename.substring(dot + 1).toLowerCase();
    }

    private void markCompleted(Job job, List<String> resultUrls) {
        job.setStatus("COMPLETED");
        job.setResultUrls(toJsonString(resultUrls));
        job.setCompletedAt(LocalDateTime.now());
        if (job.getStartedAt() != null) {
            job.setDurationMs((int) Duration.between(job.getStartedAt(), job.getCompletedAt()).toMillis());
        }
        jobRepository.updateById(job);
        log.info("job {} COMPLETED, resultUrls={}", job.getId(), resultUrls);
    }

    private void markFailed(Job job, String errorMessage) {
        job.setStatus("FAILED");
        job.setErrorMessage(errorMessage);
        job.setCompletedAt(LocalDateTime.now());
        if (job.getStartedAt() != null) {
            job.setDurationMs((int) Duration.between(job.getStartedAt(), job.getCompletedAt()).toMillis());
        }
        jobRepository.updateById(job);
        log.warn("job {} FAILED: {}", job.getId(), errorMessage);
    }

    @Override
    public JsonNode comfyuiHealthCheck() {
        // TODO(C)
        return null;
    }

    /**
     * C8 - 取得任务产物的可访问 URL（24h 签名）。
     * 先做鉴权（getJob 已做），再校验状态必须是 COMPLETED。
     * AI 产物已统一存到 media/{userId}/{yyyy-MM}/{uuid}.{ext}，
     * 因此不再按 ai-platform/{userId}/{jobId}/{filename} 拼路径，而是通过资产库按 (userId, sourceTaskId=jobId, name=filename) 反查 objectKey。
     */
    @Override
    public String getResultUrl(Long jobId, Long userId, String filename) {
        Job job = getJob(jobId, userId);
        if (!"COMPLETED".equals(job.getStatus())) {
            throw new BusinessException(ErrorCode.JOB_NOT_READY,
                "任务未完成，当前状态: " + job.getStatus());
        }
        if (filename == null || filename.contains("..") || filename.contains("/")) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "非法的 filename");
        }
        // 通过 MediaAsset 表精确查找本次 job 下该文件名的 objectKey
        String objectKey = mediaService.lookupAiMediaObjectKey(userId, String.valueOf(job.getId()), filename);
        if (objectKey == null) {
            // 向后兼容：万一有历史残留的老路径数据，再尝试老路径一次
            objectKey = String.format("ai-platform/%d/%d/%s", job.getUserId(), job.getId(), filename);
            log.warn("getResultUrl fallback to legacy path: {}", objectKey);
        }
        return storageService.getPresignedUrl(objectKey, 24);
    }

    /**
     * C9 - 删除 / 取消任务。
     *   - RUNNING / PENDING → 调 ComfyUI /interrupt + 标 CANCELLED
     *   - COMPLETED / FAILED → 标 DELETED（数据保留，方便回滚）
     *   - CANCELLED / DELETED → 幂等，直接返回
     */
    @Override
    public String deleteJob(Long jobId, Long userId) {
        Job job = getJob(jobId, userId);  // 鉴权
        String current = job.getStatus();

        switch (current) {
            case "RUNNING", "PENDING" -> {
                comfyUIClient.interrupt();
                job.setStatus("CANCELLED");
                job.setCompletedAt(LocalDateTime.now());
                if (job.getStartedAt() != null) {
                    job.setDurationMs((int) Duration.between(job.getStartedAt(), job.getCompletedAt()).toMillis());
                }
                jobRepository.updateById(job);
                log.info("job {} CANCELLED by userId={}", job.getId(), userId);
            }
            case "COMPLETED", "FAILED" -> {
                job.setStatus("DELETED");
                jobRepository.updateById(job);
                log.info("job {} marked DELETED by userId={}", job.getId(), userId);
            }
            case "CANCELLED", "DELETED" -> {
                // 幂等
            }
            default -> throw new BusinessException(ErrorCode.INVALID_PARAM,
                "未知状态: " + current);
        }
        return job.getStatus();
    }
}
