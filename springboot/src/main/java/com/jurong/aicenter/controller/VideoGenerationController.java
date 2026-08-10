package com.jurong.aicenter.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jurong.aicenter.client.ComfyUIClient;
import com.jurong.aicenter.dto.generation.GenerateResponse;
import com.jurong.aicenter.dto.job.JobResponse;
import com.jurong.aicenter.entity.Job;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.JobRepository;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.VideoGenerationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频生成端点 — 文字生成视频 (ComfyUI) + 图片生成视频 (NewAPI 中转站)。
 *
 * <p>端点：
 * <pre>
 *   POST /api/videos                   (JSON body, 文字生成视频 → ComfyUI)
 *   POST /api/videos/image-to-video    (multipart/form-data, 图片生成视频 → NewAPI)
 *   GET  /api/videos                   (列出本用户的视频生成任务)
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/videos")
@RequiredArgsConstructor
public class VideoGenerationController {

    private final VideoGenerationService videoGenerationService;
    private final JobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final ComfyUIClient comfyUIClient;

    @Value("${canvas.workflows-dir:classpath:/workflows/}")
    private String workflowsDir;

    private static final String TEMPLATE_ID = "image-to-video";
    /** text-to-video job 标记，与 image-to-video 区分 */
    private static final String TEXT_TO_VIDEO_TEMPLATE = "text-to-video";
    /** ComfyUI 图生视频 job 标记（走 ComfyUI 工作流，不走 aicoming proxy） */
    private static final String I2V_COMFY_TEMPLATE = "i2v-comfy";

    /**
     * 文字生成视频（ComfyUI 通道）。
     * 接收前端 Workbench 的 CreateVideoRequest 并提交到 ComfyUI。
     */
    @PostMapping
    public GenerateResponse createVideo(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestBody Map<String, Object> request) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);

        @SuppressWarnings("unchecked")
        String script = (String) request.getOrDefault("script", "");
        if (script == null || script.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "script 不能为空");
        }

        String model = (String) request.getOrDefault("model", "Seedance-2.0-VIP");
        String aspectRatio = (String) request.getOrDefault("aspectRatio", "16:9");
        String resolution = ((String) request.getOrDefault("resolution", "720p")).toUpperCase();
        int duration = request.get("duration") instanceof Number n ? n.intValue() : 4;
        String audioMode = (String) request.getOrDefault("audioMode", "mute");

        // ComfyUI JurongTextToVideoV2 节点目前只支持 doubao-seedance-2.0
        String comfyModel = "doubao-seedance-2.0";

        // 1. 读文字生成视频 workflow 模板
        String template = readWorkflowTemplate("02-text-to-video.json");

        // 2. 替换占位符
        String workflowJson = template
            .replace("{{prompt}}", escapeJson(script));
        // 用 JsonNode 精确替换其他字段
        try {
            JsonNode root = objectMapper.readTree(workflowJson);
            // 遍历找到 JurongTextToVideoV2 节点的 inputs
            var fields = root.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                JsonNode node = entry.getValue();
                if (node.has("class_type") && node.get("class_type").asText().contains("TextToVideo")) {
                    var inputs = node.get("inputs");
                    if (inputs != null) {
                        if (inputs.has("model")) {
                            ((com.fasterxml.jackson.databind.node.ObjectNode) inputs)
                                .put("model", comfyModel);
                        }
                        if (inputs.has("duration")) {
                            ((com.fasterxml.jackson.databind.node.ObjectNode) inputs)
                                .put("duration", String.valueOf(mapToValidDuration(duration)));
                        }
                        if (inputs.has("resolution")) {
                            ((com.fasterxml.jackson.databind.node.ObjectNode) inputs)
                                .put("resolution", resolution);
                        }
                        if (inputs.has("aspect_ratio")) {
                            ((com.fasterxml.jackson.databind.node.ObjectNode) inputs)
                                .put("aspect_ratio", aspectRatio);
                        }
                        if (inputs.has("audio") && "mute".equals(audioMode)) {
                            ((com.fasterxml.jackson.databind.node.ObjectNode) inputs)
                                .putNull("audio");
                        }
                    }
                    break;
                }
            }
            workflowJson = objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("[VIDEO-CREATE] 替换 workflow 参数失败，使用纯占位符版本: {}", e.getMessage());
        }

        log.info("[VIDEO-CREATE] userId={}, frontModel={}, comfyModel={}, duration={}, resolution={}, aspectRatio={}, audioMode={}",
            principal.id(), model, comfyModel, duration, resolution, aspectRatio, audioMode);

        // 3. 先建 job（PENDING），记录输入
        Job job = new Job();
        job.setUserId(principal.id());
        job.setTemplateId(TEXT_TO_VIDEO_TEMPLATE);
        job.setStatus("RUNNING");
        job.setInputsSnapshot(toJsonString(request));
        job.setGraphSnapshot(workflowJson);
        job.setCreditsCost(0);
        job.setCreatedAt(LocalDateTime.now());
        job.setStartedAt(LocalDateTime.now());
        jobRepository.insert(job);
        log.info("[VIDEO-CREATE] job {} 已创建: userId={}, template=text-to-video", job.getId(), principal.id());

        // 4. 提交 ComfyUI
        String promptId;
        try {
            JsonNode workflow = objectMapper.readTree(workflowJson);
            promptId = comfyUIClient.submit(workflow);
            log.info("[VIDEO-CREATE] job {} → ComfyUI submitted: promptId={}", job.getId(), promptId);
        } catch (Exception e) {
            log.error("[VIDEO-CREATE] job {} → ComfyUI 提交失败: {}", job.getId(), e.getMessage(), e);
            markFailed(job, "ComfyUI submit failed: " + e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "ComfyUI submit failed: " + e.getMessage());
        }

        // 5. 更新 job：存 promptId
        job.setComfyuiPromptId(promptId);
        jobRepository.updateById(job);
        log.info("[VIDEO-CREATE] job {} → RUNNING, promptId={}", job.getId(), promptId);

        return new GenerateResponse(job.getId(), job.getStatus(), promptId);
    }

    /**
     * 图生视频：上传图片 + 提示词 → NewAPI 中转站 → 异步生成视频。
     */
    @PostMapping("/image-to-video")
    public GenerateResponse imageToVideo(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam("prompt") String prompt,
            @RequestParam(value = "duration", defaultValue = "4") int duration,
            @RequestParam(value = "resolution", defaultValue = "480p") String resolution) {
        log.info("[I2V-REQ] 收到图生视频请求: userId={}, filename={}, contentType={}, size={}B, promptLen={}, duration={}, resolution={}",
            principal == null ? null : principal.id(),
            file == null ? null : file.getOriginalFilename(),
            file == null ? null : file.getContentType(),
            file == null ? 0 : file.getSize(),
            prompt == null ? 0 : prompt.length(),
            duration, resolution);

        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (file == null || file.isEmpty()) {
            log.warn("[I2V-REQ] 文件为空: userId={}", principal.id());
            throw new BusinessException(ErrorCode.INVALID_PARAM, "file 不能为空");
        }
        if (prompt == null || prompt.isBlank()) {
            log.warn("[I2V-REQ] prompt 为空: userId={}", principal.id());
            throw new BusinessException(ErrorCode.INVALID_PARAM, "prompt 不能为空");
        }

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            log.error("[I2V-REQ] 读取文件失败: userId={}, filename={}, err={}",
                principal.id(), file.getOriginalFilename(), e.getMessage(), e);
            throw new BusinessException(ErrorCode.INVALID_PARAM, "读取文件失败: " + e.getMessage());
        }
        GenerateResponse resp = videoGenerationService.submitImageToVideo(
            principal.id(), fileBytes, file.getOriginalFilename(), file.getContentType(),
            prompt, duration, resolution);
        log.info("[I2V-REQ] 图生视频任务已提交: userId={}, jobId={}, status={}, taskId={}",
            principal.id(), resp.getJobId(), resp.getStatus(), resp.getComfyuiPromptId());
        return resp;
    }

    @GetMapping
    public Map<String, Object> listVideos(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);

        LambdaQueryWrapper<Job> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Job::getUserId, principal.id())
               .in(Job::getTemplateId, List.of(TEMPLATE_ID, TEXT_TO_VIDEO_TEMPLATE))
               .orderByDesc(Job::getCreatedAt);

        Page<Job> mpPage = jobRepository.selectPage(
            new Page<>(page, Math.min(pageSize, 100)), wrapper);

        List<JobResponse> items = mpPage.getRecords().stream().map(job -> {
            List<String> resultUrls = null;
            if (job.getResultUrls() != null && !job.getResultUrls().isBlank()) {
                try {
                    resultUrls = objectMapper.readValue(job.getResultUrls(), new TypeReference<>() {});
                } catch (Exception ignored) {}
            }
            return new JobResponse(
                job.getId(), job.getWorkflowId(), job.getTemplateId(),
                job.getStatus(), job.getCreditsCost(), job.getDurationMs(),
                resultUrls, job.getErrorMessage(),
                job.getCreatedAt(), job.getCompletedAt()
            );
        }).toList();

        return Map.of("items", items, "total", mpPage.getTotal());
    }

    // ========== 辅助方法 ==========

    private String readWorkflowTemplate(String filename) {
        try {
            if (workflowsDir.startsWith("classpath:")) {
                String path = workflowsDir.substring("classpath:".length()) + filename;
                var res = new org.springframework.core.io.ClassPathResource(path);
                if (!res.exists()) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "workflow 模板不存在: classpath:" + path);
                }
                try (InputStream is = res.getInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            } else {
                Path p = Paths.get(workflowsDir, filename);
                if (!Files.exists(p)) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR, "workflow 模板不存在: " + p.toAbsolutePath());
                }
                return Files.readString(p, StandardCharsets.UTF_8);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读 workflow 失败: " + e.getMessage());
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 20);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
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

    /** 将前端 duration (5/10/15/30 等) 映射到 ComfyUI 支持的值 [4, 8, 12] */
    private static int mapToValidDuration(int duration) {
        int[] valid = {4, 8, 12};
        int nearest = valid[0];
        int minDist = Math.abs(duration - valid[0]);
        for (int i = 1; i < valid.length; i++) {
            int dist = Math.abs(duration - valid[i]);
            if (dist < minDist) {
                minDist = dist;
                nearest = valid[i];
            }
        }
        return nearest;
    }

    private void markFailed(Job job, String errorMessage) {
        job.setStatus("FAILED");
        job.setErrorMessage(errorMessage);
        job.setCompletedAt(LocalDateTime.now());
        if (job.getStartedAt() != null) {
            job.setDurationMs((int) java.time.Duration.between(job.getStartedAt(), job.getCompletedAt()).toMillis());
        }
        jobRepository.updateById(job);
        log.warn("[VIDEO] job {} → FAILED, err={}", job.getId(), errorMessage);
    }
}
