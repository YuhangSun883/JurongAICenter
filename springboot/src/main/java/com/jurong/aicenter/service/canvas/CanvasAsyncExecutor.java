package com.jurong.aicenter.service.canvas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.dto.canvas.GenerateCanvasNodeRequest;
import com.jurong.aicenter.entity.CanvasNode;
import com.jurong.aicenter.entity.CanvasTask;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.CanvasNodeRepository;
import com.jurong.aicenter.repository.CanvasTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 画布异步生成执行器。
 *
 * **为什么单独成类**：
 *   Spring @Async 通过 AOP 代理实现，同一类内自调用（this.xxx）**不会**经过代理，
 *   导致 @Async 失效。所以必须把异步方法放在另一个 Bean 里。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CanvasAsyncExecutor {

    private final CanvasNodeRepository nodeRepository;
    private final CanvasTaskRepository taskRepository;
    private final CanvasAiService aiService;
    private final ObjectMapper objectMapper;

    /**
     * 异步执行画布节点生成。
     * 被调用方必须是另一个 Bean（CanvasServiceImpl），不能是本类。
     */
    @Async
    public void executeGenerationAsync(String taskId, String nodeId,
                                       GenerateCanvasNodeRequest req, Long userId) {
        CanvasTask task = taskRepository.selectById(taskId);
        CanvasNode node = nodeRepository.selectById(nodeId);
        if (task == null || node == null) {
            log.error("Async execution: task or node not found, taskId={}, nodeId={}", taskId, nodeId);
            return;
        }

        task.setStatus("running");
        task.setStartedAt(LocalDateTime.now());
        taskRepository.updateById(task);

        long start = System.currentTimeMillis();
        try {
            String url = null;
            String text = null;

            // 查询上游节点：优先用请求里的 assetIds，fallback 到节点自己的 upstreamIds
            List<CanvasNode> upstreamNodes = lookupUpstreamNodes(req, node);

            switch (req.getType() == null ? "" : req.getType()) {
                case "text":
                    // 文本润色：上游若有文本节点，把它的 content 作为 upstreamContent
                    String upstreamText = pickUpstreamText(upstreamNodes);
                    text = aiService.polishText(req.getPrompt(), upstreamText);
                    task.setTextResult(text);
                    node.setContent(text);
                    break;
                case "image":
                    // 生图：上游若有文本节点，把它的 content 作为 upstreamContent
                    String upstreamTextForImage = pickUpstreamText(upstreamNodes);
                    // 关键：润色后文案可能几百字,ComfyUI 处理超长 prompt 会超时
                    // 截断到 500 字符（优先在句号/逗号/空格处断开）
                    if (upstreamTextForImage != null && upstreamTextForImage.length() > 500) {
                        log.info("Truncating long upstream text for image prompt: {} -> 500 chars",
                                 upstreamTextForImage.length());
                        upstreamTextForImage = truncateAtBoundary(upstreamTextForImage, 500);
                    }
                    url = aiService.generateImage(req.getPrompt(), upstreamTextForImage);
                    task.setResultUrl(url);
                    node.setResultUrl(url);
                    break;
                case "video":
                    // 视频：上游若有图片节点 → image-to-video；上游若有文本节点 → 润色后文本作为 prompt 上下文
                    String upstreamImageUrl = pickUpstreamImageUrl(upstreamNodes);
                    if (upstreamImageUrl == null || upstreamImageUrl.isBlank()) {
                        // 没上游图片：fallback 到视频节点自己的 resultUrl / content（防御性）
                        upstreamImageUrl = node.getResultUrl();
                        if (upstreamImageUrl == null || upstreamImageUrl.isBlank()) {
                            upstreamImageUrl = req.getContent();
                        }
                    }
                    String upstreamTextForVideo = pickUpstreamText(upstreamNodes);
                    // 同样：上游润色文案过长会拖慢 NewAPI/aicoming，截断到 800 字符
                    if (upstreamTextForVideo != null && upstreamTextForVideo.length() > 800) {
                        log.info("Truncating long upstream text for video prompt: {} -> 800 chars",
                                 upstreamTextForVideo.length());
                        upstreamTextForVideo = truncateAtBoundary(upstreamTextForVideo, 800);
                    }
                    if (upstreamTextForVideo != null && !upstreamTextForVideo.isBlank()) {
                        String preview = upstreamTextForVideo.length() > 150
                            ? upstreamTextForVideo.substring(0, 150) + "..."
                            : upstreamTextForVideo;
                        log.info("Canvas video upstream text (preview): {}", preview);
                    }
                    log.info("Canvas video upstream: imageUrl={}, textLen={}",
                        upstreamImageUrl == null ? "null" : "(set)",
                        upstreamTextForVideo == null ? 0 : upstreamTextForVideo.length());
                    url = aiService.generateVideo(req.getPrompt(), upstreamImageUrl, upstreamTextForVideo);
                    task.setResultUrl(url);
                    node.setResultUrl(url);
                    break;
                default:
                    throw new BusinessException(ErrorCode.INVALID_PARAM,
                        "不支持的节点 type: " + req.getType());
            }

            task.setStatus("success");
            task.setDurationMs((int) (System.currentTimeMillis() - start));
            task.setCompletedAt(LocalDateTime.now());
            node.setStatus("success");
            node.setFailReason(null);
            node.setUpdatedAt(LocalDateTime.now());
            log.info("Canvas task SUCCESS: taskId={}, type={}, durationMs={}",
                taskId, req.getType(), task.getDurationMs());
        } catch (Exception e) {
            log.error("Canvas task FAILED: taskId={}, type={}, err={}",
                taskId, req.getType(), e.getMessage(), e);
            task.setStatus("failed");
            String safeMsg = e.getMessage() == null ? "未知错误"
                : (e.getMessage().length() > 500 ? e.getMessage().substring(0, 500) + "..." : e.getMessage());
            task.setErrorMessage(safeMsg);
            task.setCompletedAt(LocalDateTime.now());
            node.setStatus("failed");
            node.setFailReason(safeMsg);
        }
        taskRepository.updateById(task);
        nodeRepository.updateById(node);
    }

    /**
     * 查询上游节点列表。
     * 优先用请求里的 assetIds（前端传入），fallback 到节点自己的 upstreamIds 字段（JSON）。
     * 所有上游节点必须属于同一 userId（防越权）。
     */
    private List<CanvasNode> lookupUpstreamNodes(GenerateCanvasNodeRequest req, CanvasNode currentNode) {
        List<String> upstreamIds = new ArrayList<>();

        // 1. 请求里 assetIds
        if (req.getAssetIds() != null && !req.getAssetIds().isEmpty()) {
            upstreamIds.addAll(req.getAssetIds());
        }

        // 2. fallback: 节点自己的 upstreamIds (JSON 字符串)
        if (upstreamIds.isEmpty() && currentNode.getUpstreamIds() != null
            && !currentNode.getUpstreamIds().isBlank()) {
            try {
                List<String> parsed = objectMapper.readValue(
                    currentNode.getUpstreamIds(), new TypeReference<List<String>>() {});
                if (parsed != null) upstreamIds.addAll(parsed);
            } catch (Exception e) {
                log.warn("解析 upstreamIds JSON 失败 nodeId={}: {}", currentNode.getId(), e.getMessage());
            }
        }

        if (upstreamIds.isEmpty()) return Collections.emptyList();

        // 3. 批量查 + 校验所有权
        List<CanvasNode> result = new ArrayList<>();
        for (String id : upstreamIds) {
            if (id == null || id.isBlank()) continue;
            CanvasNode upstream = nodeRepository.selectById(id);
            if (upstream == null) {
                log.warn("上游节点不存在 id={}", id);
                continue;
            }
            if (!upstream.getUserId().equals(currentNode.getUserId())) {
                log.warn("上游节点不属于同一用户 currentUserId={}, upstreamUserId={}, upstreamId={}",
                    currentNode.getUserId(), upstream.getUserId(), id);
                continue;
            }
            result.add(upstream);
        }
        return result;
    }

    /** 从上游节点列表里挑第一个文本节点的 content（润色结果） */
    private String pickUpstreamText(List<CanvasNode> upstreamNodes) {
        for (CanvasNode n : upstreamNodes) {
            if ("text".equalsIgnoreCase(n.getType()) && n.getContent() != null
                && !n.getContent().isBlank()) {
                return n.getContent();
            }
        }
        return null;
    }

    /** 从上游节点列表里挑第一个图片节点的 resultUrl */
    private String pickUpstreamImageUrl(List<CanvasNode> upstreamNodes) {
        for (CanvasNode n : upstreamNodes) {
            if ("image".equalsIgnoreCase(n.getType()) && n.getResultUrl() != null
                && !n.getResultUrl().isBlank()) {
                return n.getResultUrl();
            }
        }
        return null;
    }

    /**
     * 把长文本截断到适合 prompt 的长度。
     * 优先在中英文句子边界处断开（。!?.；,， ），避免在单词/字中间硬切。
     * 如果找不到合适的边界点，直接在 maxLen 处硬切并加 "..."。
     *
     * 使用场景：润色后的营销文案可能几百字，直接丢给 ComfyUI / NewAPI 会超时。
     */
    private String truncateAtBoundary(String text, int maxLen) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.length() <= maxLen) return trimmed;

        String cut = trimmed.substring(0, maxLen);
        // 优先在句号/感叹号/问号/分号/逗号/空格处断开
        // 顺序很重要：先找高优先级的边界（句号类），再找低优先级的（逗号/空格）
        char[] breakers = {'。', '!', '?', ';', '；', '.', ',', '，', ' ', '、'};
        int bestBreak = -1;
        for (char c : breakers) {
            int idx = cut.lastIndexOf(c);
            if (idx > maxLen * 0.5) {  // 至少在中间以后才断（避免前面断太多）
                bestBreak = idx;
                break;
            }
        }
        if (bestBreak > 0) {
            return cut.substring(0, bestBreak + 1) + "...";
        }
        return cut + "...";
    }
}