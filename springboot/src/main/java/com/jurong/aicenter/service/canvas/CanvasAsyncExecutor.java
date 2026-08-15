package com.jurong.aicenter.service.canvas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.dto.canvas.GenerateCanvasNodeRequest;
import com.jurong.aicenter.dto.canvas.NodeConnection;
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
 *
 * **多端口输入**：
 *   GenerateCanvasNodeRequest.inputs 是 List&lt;NodeConnection&gt;（每条带 port 名 + nodeId）。
 *   executor 按"第一个匹配的文本/图片节点"取值（pickUpstreamText / pickUpstreamImageUrl），
 *   port 名只是路由标识，不影响取值逻辑。
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
    public void executeGenerationAsync(CanvasTask task, CanvasNode node,
                                       GenerateCanvasNodeRequest req, Long userId) {
        // 2026-08-08: 接收 entities 而不是 ID —— 避免 Spring @Async 在新线程跑
        // 看不到父事务的 uncommitted task (task or node not found 错)
        // 之前 extractCaptionAsync 也有同样问题,已修;这次同步修 generate
        if (task == null || node == null) {
            log.error("Async execution: task or node is null (caller bug)");
            return;
        }

        task.setStatus("running");
        task.setStartedAt(LocalDateTime.now());
        taskRepository.updateById(task);

        long start = System.currentTimeMillis();
        try {
            String url = null;
            String text = null;

            // 查询上游节点：优先用请求里的 inputs（多端口），fallback 到节点自己的 upstreamIds
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
                    // 2026-08-11 新增:上游有 image 节点 → 走图生图(editImage)
                    // 上游无 image 节点 → 走文字生图(generateImage)
                    String userPromptForImage = req.getPrompt();
                    String upstreamTextForImage = pickUpstreamText(upstreamNodes);
                    String upstreamImageForImage = pickUpstreamImageUrl(upstreamNodes);

                    // 关键：润色后文案可能几百字,ComfyUI 处理超长 prompt 会超时
                    // 截断到 500 字符（优先在句号/逗号/空格处断开）
                    if (upstreamTextForImage != null && upstreamTextForImage.length() > 500) {
                        log.info("Truncating long upstream text for image prompt: {} -> 500 chars",
                                 upstreamTextForImage.length());
                        upstreamTextForImage = truncateAtBoundary(upstreamTextForImage, 500);
                    }
                    // 2026-08-08: 后端兜底验证 — 不提交空 prompt 给 ComfyUI(它会 500)
                    // 前端有检查但可能漏掉(state 同步/点击过快),后端更可靠
                    if ((userPromptForImage == null || userPromptForImage.isBlank())
                        && (upstreamTextForImage == null || upstreamTextForImage.isBlank())) {
                        throw new BusinessException(ErrorCode.INVALID_PARAM,
                            "请先输入提示词,或从节点左侧/右侧 + 拖一个上游文本节点来引用");
                    }

                    if (upstreamImageForImage != null && !upstreamImageForImage.isBlank()) {
                        // 上游是 image 节点 → 走图生图
                        log.info("Canvas image-edit (上游图): imageUrl={}, promptLen={}",
                            "(set)", userPromptForImage == null ? 0 : userPromptForImage.length());
                        url = aiService.editImage(upstreamImageForImage, userPromptForImage, upstreamTextForImage);
                    } else {
                        // 上游无 image 节点 → 走文字生图(原逻辑)
                        log.info("Canvas image-generate (纯文生图): promptLen={}",
                            userPromptForImage == null ? 0 : userPromptForImage.length());
                        url = aiService.generateImage(userPromptForImage, upstreamTextForImage);
                    }
                    task.setResultUrl(url);
                    node.setResultUrl(url);
                    break;
                case "video":
                    // 上游连接：把 image/grid/text/script/breakdown 节点的输出拼到最终 prompt
                    // video 节点输入框(userPrompt) = 分镜/动作描述,会被拼到 finalPrompt
                    String userPromptForVideo = req.getPrompt();
                    // 2026-08-11:收集所有上游 image 节点 URL(支持多图参考:三视图+换装帧图+其他)
                    java.util.List<String> upstreamImageUrls = pickAllUpstreamImageUrls(upstreamNodes);
                    String upstreamImageUrl = (upstreamImageUrls != null && !upstreamImageUrls.isEmpty())
                        ? upstreamImageUrls.get(0) : null;
                    if (upstreamImageUrl == null || upstreamImageUrl.isBlank()) {
                        // 没上游图片：fallback 到视频节点自己的 resultUrl / content（防御性）
                        upstreamImageUrl = node.getResultUrl();
                        if (upstreamImageUrl == null || upstreamImageUrl.isBlank()) {
                            upstreamImageUrl = req.getContent();
                        }
                        upstreamImageUrls = (upstreamImageUrl != null && !upstreamImageUrl.isBlank())
                            ? java.util.List.of(upstreamImageUrl) : java.util.List.of();
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

                    // 2026-08-08 改:不再强制要求 prompt — image_to_video.py 开了 enable_prompt_optimize=true
                    // 会自动用 VL 模型描述图片 + 加默认动作(主体自然动态)
                    // 空 prompt 也能跑,但建议用户输入具体动作(图片是一头猪,输入"跳舞"→生成猪跳舞)
                    if (userPromptForVideo != null && userPromptForVideo.isBlank()) {
                        userPromptForVideo = null; // 规范成 null,让 ComfyUI 节点用默认
                    }
                    // 2026-08-11:支持多图参考(三视图+换装帧图+其他)
                    if (upstreamImageUrls != null && upstreamImageUrls.size() > 1) {
                        // 多图:用 generateVideoMulti
                        log.info("Canvas video (multi-image): imageUrls={}, promptLen={}",
                            upstreamImageUrls.size(),
                            userPromptForVideo == null ? 0 : userPromptForVideo.length());
                        url = aiService.generateVideoMulti(userPromptForVideo, upstreamImageUrls, upstreamTextForVideo);
                    } else {
                        // 单图或纯文:用 generateVideo(向后兼容)
                        url = aiService.generateVideo(userPromptForVideo, upstreamImageUrl, upstreamTextForVideo);
                    }
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
            log.info("Canvas task SUCCESS: task.getId()={}, type={}, durationMs={}",
                task.getId(), req.getType(), task.getDurationMs());
        } catch (Exception e) {
            log.error("Canvas task FAILED: task.getId()={}, type={}, err={}",
                task.getId(), req.getType(), e.getMessage(), e);
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
     * 优先用请求里的 inputs（多端口 List&lt;NodeConnection&gt;），
     * fallback 到节点自己的 upstreamIds（多端口格式，兼容旧的 List&lt;String&gt;）。
     * 所有上游节点必须属于同一 userId（防越权）。
     */
    private List<CanvasNode> lookupUpstreamNodes(GenerateCanvasNodeRequest req, CanvasNode currentNode) {
        List<String> upstreamIds = new ArrayList<>();

        // 1. 请求里 inputs（多端口格式）
        if (req.getInputs() != null && !req.getInputs().isEmpty()) {
            for (NodeConnection c : req.getInputs()) {
                if (c != null && c.getNodeId() != null && !c.getNodeId().isBlank()) {
                    upstreamIds.add(c.getNodeId());
                }
            }
        }

        // 2. fallback: 节点自己的 upstreamIds（多端口 JSON / 旧 List<String> JSON）
        if (upstreamIds.isEmpty() && currentNode.getUpstreamIds() != null
            && !currentNode.getUpstreamIds().isBlank()) {
            List<String> parsed = parseUpstreamIdsField(currentNode.getUpstreamIds());
            if (parsed != null) upstreamIds.addAll(parsed);
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

    /**
     * 解析节点 upstreamIds JSON 字段，兼容两种格式：
     *   - 新：List&lt;NodeConnection&gt;  → 取每个的 nodeId
     *   - 旧：List&lt;String&gt;         → 直接拿（已经是 nodeId）
     */
    private List<String> parseUpstreamIdsField(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        // 先按新格式试
        try {
            List<NodeConnection> conns = objectMapper.readValue(
                json, new TypeReference<List<NodeConnection>>() {});
            if (conns != null) {
                return conns.stream()
                    .map(NodeConnection::getNodeId)
                    .filter(id -> id != null && !id.isBlank())
                    .toList();
            }
        } catch (Exception ignore) {
            // fall through to old format
        }
        // 旧格式：List<String>
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("parseUpstreamIdsField failed: {}", e.getMessage());
            return Collections.emptyList();
        }
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
     * 2026-08-11 新增:收集所有上游 image 节点的 resultUrl,返回 List(支持多图生视频)。
     */
    private java.util.List<String> pickAllUpstreamImageUrls(List<CanvasNode> upstreamNodes) {
        java.util.List<String> urls = new java.util.ArrayList<>();
        for (CanvasNode n : upstreamNodes) {
            if ("image".equalsIgnoreCase(n.getType()) && n.getResultUrl() != null
                && !n.getResultUrl().isBlank()) {
                urls.add(n.getResultUrl());
            }
        }
        return urls;
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