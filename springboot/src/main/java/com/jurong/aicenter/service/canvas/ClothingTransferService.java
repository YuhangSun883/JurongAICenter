package com.jurong.aicenter.service.canvas;

import com.jurong.aicenter.client.NewApiClient;
import com.jurong.aicenter.dto.media.MediaAssetResponse;
import com.jurong.aicenter.entity.CanvasNode;
import com.jurong.aicenter.entity.CanvasTask;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.repository.CanvasNodeRepository;
import com.jurong.aicenter.repository.CanvasTaskRepository;
import com.jurong.aicenter.service.MediaService;
import com.jurong.aicenter.service.StorageService;
import com.jurong.aicenter.service.VideoFrameExtractor;
import com.jurong.aicenter.service.VideoFrameExtractor.FrameMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * 2026-08-09 新增:换装(Clothing Transfer)服务
 *
 * 链路:
 *   1. 用户选视频节点 + 3 张衣服图(image节点 type=image)的 nodeId
 *   2. 从视频 re-extract 抽帧(fps=1,与脚本拆解一致)
 *   3. 对每帧调 NewAPI /v1/images/edits,image_1=原帧、image_2/3/4=衣服参考图
 *   4. 把每帧结果上传 MinIO
 *   5. 用 BufferedImage 拼 1 张大图(类似帧采样表)
 *   6. 自动在画布建 N 个 image 节点(每张换装图 1 个)
 *
 * 调用入口:CanvasServiceImpl.transferClothing()
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClothingTransferService {

    /**
     * 2026-08-13 FIX:通用多图图生图 prompt 模板(不再写死"衣服")。
     *
     * <p>支持以下场景:
     * <ul>
     *   <li>衣服图 → 替换原图人物身上的衣服
     *   <li>模特图(带人脸) → 同时替换原图人物的脸/身材
     *   <li>化妆品/箱包/鞋子/配饰等其他物品 → 替换原图中相应物品或人物身上的对应元素
     * </ul>
     *
     * <p>%REF_DESC% 占位:动态描述参考图角色,例如 "图2(衣服正面)、图3(衣服背面)、图4(模特上身)..."
     */
    private static final String GRID_CLOTHING_PROMPT_TEMPLATE =
        "图1是原视频抽帧总图,共%FRAME_COUNT%帧。请保持每一帧的人物动作、表情、背景不变,"
        + "把图1中每一帧人物身上的衣服换成图2的衣服。"
        + "%REF_DESC%"
        + "输出与图1布局一致的图。";

    /**
     * 2026-08-14 v4:按 v3.0 接口手册"图1穿图2的衣服"实测风格 prompt。
     *   自然、简洁、明确引用每个图的角色,不写元描述("禁止"、"任务类型")。
     */
    private static final String VIDEO_CLOTHING_PROMPT_TEMPLATE =
        "图1是上游节点的画面(视频帧/单张图)。请保持图1的人物动作、表情、背景不变,"
        + "把图1中人物身上的衣服换成图2的衣服。"
        + "%REF_DESC%"
        + "输出与图1同样尺寸的图。";

    /**
     * 2026-08-14 v4:把多张参考图在 prompt 里**显式标号**(图2、图3、图4)+ 说明角色。
     *   配合 images:[] 数组使用时,asset_url[i] 对应图 i+1。
     */
    private static String buildRefDesc(int count) {
        if (count <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int figNum = i + 2;
            if (i == 0) {
                sb.append("图").append(figNum).append("是衣服参考图。");
            } else if (i == 1) {
                sb.append("图").append(figNum).append("是模特图(含人脸),可参考换脸。");
            } else {
                sb.append("图").append(figNum).append("是其他参考图(化妆品/箱包/配饰等)。");
            }
        }
        return sb.toString();
    }

    /**
     * 2026-08-14 v4:把 asset_url 列表也拼进 prompt,便于模型视觉对照。
     *   即使 images:[] 数组本身已能识别素材,显式列出来更稳。
     */
    private static String buildRefUrls(java.util.List<String> refAssetUrls) {
        if (refAssetUrls == null || refAssetUrls.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" asset:");
        for (int i = 0; i < refAssetUrls.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("图").append(i + 2).append("=").append(refAssetUrls.get(i));
        }
        return sb.toString();
    }

    private final VideoFrameExtractor extractor;
    private final NewApiClient newApiClient;
    private final MediaService mediaService;
    private final StorageService storageService;
    private final CanvasTaskRepository taskRepository;
    private final CanvasNodeRepository nodeRepository;
    @Qualifier("captionExecutor")
    private final Executor captionExecutor;

    /**
     * 异步入口。被 CanvasServiceImpl.transferClothing() 调用。
     *
     * @param task              任务实体(已 save,id 已生成)
     * @param videoNode         视频节点(被换装的视频)
     * @param clothingNodeIds   3 个衣服参考 image 节点的 id(顺序:正面、背面、模特上身)
     * @param userId            用户 id
     */
    @Async("captionExecutor")
    public void executeTransferAsync(CanvasTask task, CanvasNode videoNode,
                                      List<String> clothingNodeIds, Long userId,
                                      String userInstruction) {
        if (task == null || videoNode == null) {
            log.error("[clothing-transfer] task/videoNode null");
            return;
        }
        // 2026-08-11 fix:target = user clicked, from task.nodeId (no longer source)
        // fix: resultUrl writes back to target (no more 9 new image nodes on right)
        String targetNodeId = task.getNodeId();
        CanvasNode targetNode = nodeRepository.selectById(targetNodeId);
        if (targetNode == null) {
            log.error("[clothing-transfer] targetNode not found: task.nodeId={}", targetNodeId);
            return;
        }
        // 2026-08-13 FIX:不再硬编码必须 3 张,改为 ≥1 && ≤3 张
        //   用户可以传 1 张(衣服/模特/化妆品任意)、2 张 或 3 张参考图
        if (clothingNodeIds == null || clothingNodeIds.isEmpty()
                || clothingNodeIds.size() > 3) {
            log.error("[clothing-transfer] 参考图张数应在 1~3 之间,实际 {} 张",
                clothingNodeIds == null ? 0 : clothingNodeIds.size());
            failTask(task, targetNode,
                "需要 1~3 张参考图(衣服/模特/化妆品任意),实际=" + (clothingNodeIds == null ? 0 : clothingNodeIds.size()));
            taskRepository.updateById(task);
            nodeRepository.updateById(targetNode);
            return;
        }

        String taskId = task.getId();
        String nodeId = videoNode.getId();

        // running
        task.setStatus("running");
        task.setStartedAt(LocalDateTime.now());
        taskRepository.updateById(task);
        log.debug("=== [clothing-transfer] START taskId={} source={} clothing={} ===",
            taskId, nodeId, clothingNodeIds);

        long start = System.currentTimeMillis();
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "clothing-transfer-" + taskId);

        List<String> createdIds = new ArrayList<>();

        try {
            // 2026-08-11 fix:整体换装(1次 NewAPI 调用,用户实测 3.5 分钟)
            // 关键改进:task.setResultUrl(combinedUrl) → 前端轮询拿到后 flushSync 更新 activeNode
            //          task.setCreatedNodeIds(null) → 前端不会创建新节点
            //          整个流程不创建任何新节点,直接覆盖到 activeNode(用户上传衣服图的节点)

            // ① 加载源图(总图或单图)
            byte[] sourceBytes;
            String sourceMime;
            boolean isGrid = false;
            int frameCount = 0;

            if ("image".equalsIgnoreCase(videoNode.getType())) {
                frameCount = parseFrameCount(videoNode.getSettings());
                isGrid = frameCount > 1;
                log.debug("[clothing-transfer] image 节点 frameCount={}, isGrid={}", frameCount, isGrid);
            }
            sourceBytes = downloadFromUrl(videoNode.getResultUrl());
            sourceMime = detectMime(sourceBytes);

            // ② 加载 1~3 张参考图
            List<String> clothingDataUris = new ArrayList<>(3);
            for (String cid : clothingNodeIds) {
                byte[] bytes = downloadNodeImageBytes(cid, userId);
                if (bytes == null) {
                    throw new BusinessException(com.jurong.aicenter.exception.ErrorCode.INTERNAL_ERROR,
                        "参考图节点 " + cid + " 加载失败");
                }
                String mime = detectMime(bytes);
                clothingDataUris.add("data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
            }
            log.debug("[clothing-transfer] 参考图加载完成: {} 张", clothingDataUris.size());

            // ③ 选择 prompt + 1 次 NewAPI 整体换装
            String prompt;
            String sourceDataUri = "data:" + sourceMime + ";base64," +
                Base64.getEncoder().encodeToString(sourceBytes);

            // 2026-08-11:根据 userInstruction 拼接约束(用户自然语言描述)
            // - 如果为空,使用默认 GRID/VIDEO 换装模板(行为不变)
            // - 如果非空,使用 GENERIC 通用模板 + 用户的具体要求
            String userInstructionBlock = "";
            if (userInstruction != null && !userInstruction.isBlank()) {
                userInstructionBlock = "\n【用户的具体转换要求】\n" + userInstruction.trim() + "\n";
            }

            if (isGrid) {
                prompt = GRID_CLOTHING_PROMPT_TEMPLATE
                    .replace("%FRAME_COUNT%", String.valueOf(frameCount))
                    .replace("%REF_DESC%", buildRefDesc(clothingDataUris.size()));
                log.debug("[clothing-transfer] 整体换装(总图): frameCount={}, 1次API调用", frameCount);
            } else {
                prompt = VIDEO_CLOTHING_PROMPT_TEMPLATE
                    .replace("%REF_DESC%", buildRefDesc(clothingDataUris.size()));
                log.debug("[clothing-transfer] 整体换装(单图/视频): 1次API调用");
            }

            // 2026-08-11:如果用户有自定义描述,拼到 prompt 末尾作为强约束
            if (!userInstructionBlock.isEmpty()) {
                prompt = prompt + userInstructionBlock;
                log.debug("[clothing-transfer] 用户自定义描述已拼接到 prompt: {} 字符",
                    userInstruction.trim().length());
            }

            // 调 NewAPI
            // 2026-08-14 v3:回退到原始的"4 张图分别上传"方案(08-13 实测能跑通的关键)
            //   - 第 1 张(主图)作为 body.image 单字段 — 触发 gpt-image 的 i2i 行为
            //   - 第 2~N 张(参考图)通过 prompt 文本里的 asset_url 引用,让模型视觉理解
            //   - prompt 简洁自然(~300 字符),不写"任务类型"、"禁止事项"等元描述
            //   (不再拼接网格图,不再用 images:[] 数组 — 那些方法让模型退化为文生图)
            String outputSize = detectOutputSize(sourceBytes);
            List<String> allRefImages = new ArrayList<>(4);
            allRefImages.add(sourceDataUri);
            allRefImages.addAll(clothingDataUris);
            log.debug("[clothing-transfer] 开始 NewAPI editImage: 主图+{}张参考图, "
                + "outputSize={}, promptLen={}",
                clothingDataUris.size(), outputSize, prompt.length());
            String resultDataUri = newApiClient.editImage(
                prompt, allRefImages, outputSize, "low", null);

            // ④ 上传到 MinIO,覆盖到 activeNode
            byte[] resultBytes = decodeResultDataUri(resultDataUri);
            String key = "clothing-transfer/" + nodeId + "/result-" + System.currentTimeMillis() + ".png";
            String combinedUrl;
            try (InputStream in = new ByteArrayInputStream(resultBytes)) {
                combinedUrl = storageService.uploadObject(key, in, "image/png");
            }
            if (combinedUrl == null) {
                failTask(task, targetNode, "换装失败,NewAPI 返回空结果");
                taskRepository.updateById(task);
                nodeRepository.updateById(targetNode);
                return;
            }
            targetNode.setResultUrl(combinedUrl);
            targetNode.setStatus("success");
            targetNode.setUpdatedAt(LocalDateTime.now());
            nodeRepository.updateById(targetNode);
            log.debug("[clothing-transfer] 结果覆盖到 activeNode: targetNodeId={}, url={}", targetNodeId, combinedUrl);

            // ⑤ task SUCCESS - 同步 resultUrl + 清空 createdNodeIds
            task.setStatus("success");
            task.setResultUrl(combinedUrl);  // 关键:前端轮询拿到后 flushSync → 无需刷新
            task.setCreatedNodeIds(null);    // 关键:前端不创建新节点
            task.setDurationMs((int) (System.currentTimeMillis() - start));
            task.setCompletedAt(LocalDateTime.now());
            log.debug("[clothing-transfer] SUCCESS: taskId={}, isGrid={}, durationMs={}",
                taskId, isGrid, task.getDurationMs());
            log.debug("=== [clothing-transfer] DONE taskId={} status=SUCCESS durationMs={} ===",
                taskId, task.getDurationMs());
        } catch (BusinessException e) {
            log.error("[clothing-transfer] BIZ_FAIL: taskId={}, err={}", taskId, e.getMessage());
            log.error("=== [clothing-transfer] DONE taskId={} status=FAILED reason=BIZ durationMs={} ===",
                taskId, System.currentTimeMillis() - start);
            failTask(task, targetNode, e.getMessage());
        } catch (Exception e) {
            log.error("[clothing-transfer] FAIL: taskId={}, err={}", taskId, e.getMessage(), e);
            log.error("=== [clothing-transfer] DONE taskId={} status=FAILED reason=EXCEPTION durationMs={} ===",
                taskId, System.currentTimeMillis() - start);
            failTask(task, targetNode, e.getMessage() == null ? "未知错误" : e.getMessage());
        } finally {
            try {
                if (Files.exists(tmpDir)) {
                    Files.walk(tmpDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignore) {} });
                }
            } catch (Exception ignore) {}
            taskRepository.updateById(task);
            nodeRepository.updateById(targetNode);
        }
    }

    // ============== 私有辅助方法 ==============

    /**
     * 从画布 image 节点下载图片字节。
     * 先查 node.resultUrl,有就直接 URL 下载;否则尝试 MediaService(按 assetId)。
     */
    private byte[] downloadNodeImageBytes(String nodeId, Long userId) {
        try {
            CanvasNode n = nodeRepository.selectById(nodeId);
            if (n == null) return null;
            // image 节点的 resultUrl 直接是图片 URL
            if (n.getResultUrl() != null && !n.getResultUrl().isBlank()) {
                return downloadFromUrl(n.getResultUrl());
            }
            // 没 resultUrl 但有 assetId → 走 MediaService 查 URL
            if (n.getAssetId() != null && !n.getAssetId().isBlank()) {
                MediaAssetResponse asset = mediaService.getAsset(userId, Long.parseLong(n.getAssetId()));
                if (asset != null && asset.getUrl() != null) {
                    return downloadFromUrl(asset.getUrl());
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[clothing-transfer] 下载节点 {} 图片失败: {}", nodeId, e.getMessage());
            return null;
        }
    }

    private byte[] downloadFromUrl(String url) throws Exception {
        // 2026-08-12 修复:MinIO 24h 临时签名 URL 可能过期 (403),反引号/空格污染 URL,
        //   上游维修期更常见。这里加 sanitize + 1 retry,容忍临时 5xx 抖动。
        String cleanUrl = sanitizeUrlForDownload(url);
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try (InputStream in = new URI(cleanUrl).toURL().openStream()) {
                return in.readAllBytes();
            } catch (Exception e) {
                last = e;
                int code = extractHttpCode(e);
                // 4xx 客户端错误(包括 403 expired、404 not found)立刻抛
                if (code >= 400 && code < 500 && code != 408 && code != 429) {
                    String friendly = code == 403
                        ? "图片访问被拒绝(403,通常是 MinIO 24h 签名 URL 过期)"
                        : "图片访问失败 (HTTP " + code + ")";
                    throw new RuntimeException(friendly + ": " + cleanUrl, e);
                }
                log.warn("[clothing-transfer] 下载图片失败(尝试 {}/2): code={}, err={}",
                    attempt, code, e.getMessage());
                try { Thread.sleep(800L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw new RuntimeException("下载图片失败(已重试 2 次): " + cleanUrl, last);
    }

    /** 2026-08-12 新增:清洗 URL 前后反引号/引号/空格 (上游 resultUrl 偶发污染) */
    private String sanitizeUrlForDownload(String url) {
        if (url == null) return null;
        String s = url.trim();
        s = s.replaceAll("^`+|`+$", "");
        s = s.replaceAll("^['\"]+|['\"]+$", "");
        s = s.trim();
        // 去除 URL 末尾可能存在的 `,` 或 `,`
        if (s.endsWith(",") || s.endsWith(",")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    /** 从异常链里找 HTTP 状态码 (sun.net.www.protocol.http.HttpURLConnection 抛的) */
    private int extractHttpCode(Exception e) {
        Throwable t = e;
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null) {
                if (msg.contains("HTTP response code: 403")) return 403;
                if (msg.contains("HTTP response code: 404")) return 404;
                if (msg.contains("HTTP response code: 410")) return 410;
                if (msg.contains("HTTP response code: 408")) return 408;
                if (msg.contains("HTTP response code: 429")) return 429;
                if (msg.contains("HTTP response code: 500")) return 500;
                if (msg.contains("HTTP response code: 502")) return 502;
                if (msg.contains("HTTP response code: 503")) return 503;
            }
            t = t.getCause();
        }
        return -1;
    }

    /** 检测图片 MIME:从 magic bytes */
    private String detectMime(byte[] bytes) {
        if (bytes.length > 3 &&
            (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8) return "image/jpeg";
        if (bytes.length > 8 &&
            (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";
        if (bytes.length > 12 &&
            bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') return "image/webp";
        return "image/jpeg";
    }

    /**
     * 解码 NewApiClient.editImage 返回的 data URI 或 URL 为字节
     * 已知格式:
     *   - "data:image/png;base64,...." → 取 base64 部分解码
     *   - "https://..." → URL 下载
     */
    private byte[] decodeResultDataUri(String dataUriOrUrl) throws Exception {
        if (dataUriOrUrl == null || dataUriOrUrl.isBlank()) return new byte[0];
        if (dataUriOrUrl.startsWith("data:")) {
            int comma = dataUriOrUrl.indexOf(",");
            String b64 = comma >= 0 ? dataUriOrUrl.substring(comma + 1) : dataUriOrUrl;
            return Base64.getDecoder().decode(b64);
        }
        // URL
        return downloadFromUrl(dataUriOrUrl);
    }

    /**
     * 把换装结果拼成 1 张大图(4 列网格),每张标 "换装 #N"。
     * 失败帧位置留空(浅灰底)。
     */
    private String combineTransferResults(List<String> transferUrls, String videoNodeId) throws Exception {
        // 过滤掉 null
        List<byte[]> loaded = new ArrayList<>();
        for (String url : transferUrls) {
            if (url == null) {
                loaded.add(null);
            } else {
                try {
                    loaded.add(downloadFromUrl(url));
                } catch (Exception e) {
                    loaded.add(null);
                }
            }
        }
        int total = loaded.size();
        if (total == 0) return null;

        // 读第一张成功图确定尺寸,失败帧用统一占位尺寸
        BufferedImage firstImg = null;
        for (byte[] b : loaded) {
            if (b != null) {
                firstImg = ImageIO.read(new ByteArrayInputStream(b));
                break;
            }
        }
        if (firstImg == null) return null;
        int origW = firstImg.getWidth();
        int origH = firstImg.getHeight();
        final int FRAME_W = 320;
        int frameH = (int) Math.round((double) FRAME_W * origH / origW);
        if (frameH < 100) frameH = 100;
        else if (frameH > 540) frameH = 540;

        final int COLS = 4;
        final int GAP = 8;
        int rows = (total + COLS - 1) / COLS;
        int canvasW = COLS * FRAME_W + (COLS - 1) * GAP;
        int canvasH = rows * frameH + (rows - 1) * GAP;

        BufferedImage combined = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = combined.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(245, 247, 250));
        g.fillRect(0, 0, canvasW, canvasH);

        Font font = new Font("SansSerif", Font.BOLD, 16);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();

        for (int i = 0; i < total; i++) {
            int col = i % COLS;
            int row = i / COLS;
            int x = col * (FRAME_W + GAP);
            int y = row * (frameH + GAP);
            byte[] data = loaded.get(i);
            if (data == null) {
                // 失败帧:灰底 + 标 "失败"
                g.setColor(new Color(220, 226, 235));
                g.fillRect(x, y, FRAME_W, frameH);
                g.setColor(new Color(120, 130, 145));
                String label = "帧 #" + (i + 1) + " 失败";
                int tw = fm.stringWidth(label);
                g.drawString(label, x + (FRAME_W - tw) / 2, y + frameH / 2);
                continue;
            }
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            Image scaled = img.getScaledInstance(FRAME_W, frameH, Image.SCALE_SMOOTH);
            g.drawImage(scaled, x, y, null);

            // 左上角标 "换装 #N"
            String label = "换装 #" + (i + 1);
            int tw = fm.stringWidth(label);
            int padX = 8;
            g.setColor(new Color(0, 0, 0, 160));
            g.fillRoundRect(x + 8, y + 4, tw + padX * 2, 22, 6, 6);
            g.setColor(Color.WHITE);
            g.drawString(label, x + 8 + padX, y + 4 + 16);
        }
        g.dispose();

        Path tmpFile = Files.createTempFile("clothing-grid-", ".jpg");
        ImageIO.write(combined, "jpg", tmpFile.toFile());
        String key = "clothing-grid/" + videoNodeId + "/combined-" + System.currentTimeMillis() + ".jpg";
        String url;
        try (InputStream in = Files.newInputStream(tmpFile)) {
            url = storageService.uploadObject(key, in, "image/jpeg");
        }
        Files.deleteIfExists(tmpFile);
        log.debug("[clothing-grid] created: {} ({}x{} px, {} frames)", url, canvasW, canvasH, total);
        return url;
    }

    /**
     * 2026-08-14 NEW:把 1 张主图 + N 张(1~3)参考图合成为 1 张 PNG 网格图。
     *
     * <p>布局:左半边是主图(占 2x2 网格的左上角和左下角,即较大位置),
     * 右半边是参考图(垂直排列)。
     *
     * <p>每个子图左上角加文字标签(LT/RT/LB/RB 位置标识,以及 "主图"/"参考1"等)
     * 方便模型识别哪张是主图、哪些是参考图。
     *
     * <p>返回 PNG bytes(而不是 URL),用于直接传给 NewAPI editImage 作为
     * 单一 image 字段,从根本上解决 aicoming 素材库配额满 403 的问题。
     */
    private byte[] combineReferenceGrid(byte[] sourceBytes, List<String> refDataUris,
                                        String videoNodeId) throws Exception {
        // 主图
        BufferedImage main = ImageIO.read(new ByteArrayInputStream(sourceBytes));
        if (main == null) {
            throw new BusinessException(com.jurong.aicenter.exception.ErrorCode.INTERNAL_ERROR,
                "combineReferenceGrid: 主图解码失败");
        }
        // 参考图(从 data URI 解析字节)
        List<BufferedImage> refs = new ArrayList<>(refDataUris.size());
        for (int i = 0; i < refDataUris.size(); i++) {
            String dataUri = refDataUris.get(i);
            String b64 = dataUri.contains(",")
                ? dataUri.substring(dataUri.indexOf(',') + 1)
                : dataUri;
            byte[] bytes = Base64.getDecoder().decode(b64);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) {
                throw new BusinessException(com.jurong.aicenter.exception.ErrorCode.INTERNAL_ERROR,
                    "combineReferenceGrid: 参考图 #" + (i + 1) + " 解码失败");
            }
            refs.add(img);
        }

        // 布局参数 — 2026-08-14 v2:主图占整个上半部分(占总面积 ~70%),
        //   参考图占下半部分水平排列。这样模型"主角"信息密度高,
        //   大幅降低它"自由发挥"的空间,迫使它按主图画面生成。
        final int CANVAS_W = 1024;       // 整个拼接图固定宽 1024
        final int GAP = 8;
        final int LABEL_H = 22;
        final int MAIN_H = 768;          // 主图区域固定高度 768(占总面积 ~70%)
        final int REF_H = 280;           // 参考图区域固定高度 280(下方水平排列)

        int mainW = CANVAS_W;
        int mainH = MAIN_H;
        int refBlockW = (CANVAS_W - GAP * (refs.size() - 1)) / refs.size();
        int refH = REF_H;

        int canvasW = CANVAS_W;
        int canvasH = mainH + GAP + 30 + refH;  // +30 给"参考图"标题栏

        BufferedImage combined = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = combined.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(245, 247, 250));
        g.fillRect(0, 0, canvasW, canvasH);

        Font font = new Font("SansSerif", Font.BOLD, 18);
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();

        // 主图(上方, 占 ~70% 面积) — letterbox 保持宽高比
        double mainAspect = (double) main.getWidth() / main.getHeight();
        double slotAspect = (double) mainW / mainH;
        int drawW, drawH, offX, offY;
        if (mainAspect > slotAspect) {
            drawW = mainW;
            drawH = (int) Math.round(mainW / mainAspect);
            offX = 0;
            offY = (mainH - drawH) / 2;
        } else {
            drawH = mainH;
            drawW = (int) Math.round(mainH * mainAspect);
            offX = (mainW - drawW) / 2;
            offY = 0;
        }
        g.drawImage(main.getScaledInstance(drawW, drawH, Image.SCALE_SMOOTH), offX, offY, null);
        // 主图边框(红色, 让模型清晰看到主图区域)
        g.setColor(new Color(220, 50, 50));
        g.setStroke(new java.awt.BasicStroke(4));
        g.drawRect(0, 0, mainW - 1, mainH - 1);
        g.setStroke(new java.awt.BasicStroke(1));
        drawLabel(g, fm, 0, 0, "主图(必须基于此图修改)", mainW);

        // 参考图标题(在主图和参考图之间)
        int refStartY = mainH + GAP;
        g.setColor(new Color(80, 80, 80));
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.drawString("▼ 用户上传的参考图(只用参考图中的物品/人物替换到主图) ▼",
            16, refStartY + 20);

        // 参考图(下方, 水平排列)
        int refDrawY = refStartY + 30;
        for (int i = 0; i < refs.size(); i++) {
            int x = i * (refBlockW + GAP);
            BufferedImage refImg = refs.get(i);
            // letterbox 保持宽高比
            double refAspect = (double) refImg.getWidth() / refImg.getHeight();
            double refSlotAspect = (double) refBlockW / refH;
            int rW, rH, rOX, rOY;
            if (refAspect > refSlotAspect) {
                rW = refBlockW;
                rH = (int) Math.round(refBlockW / refAspect);
                rOX = 0;
                rOY = (refH - rH) / 2;
            } else {
                rH = refH;
                rW = (int) Math.round(refH * refAspect);
                rOX = (refBlockW - rW) / 2;
                rOY = 0;
            }
            g.drawImage(refImg.getScaledInstance(rW, rH, Image.SCALE_SMOOTH),
                x + rOX, refDrawY + rOY, null);
            // 参考图边框(蓝色) + 标签
            g.setColor(new Color(50, 100, 220));
            g.setStroke(new java.awt.BasicStroke(3));
            g.drawRect(x, refDrawY, refBlockW - 1, refH - 1);
            g.setStroke(new java.awt.BasicStroke(1));
            drawLabel(g, fm, x, refDrawY, "参考 " + (i + 1), refBlockW);
        }

        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(combined, "png", baos);
        byte[] pngBytes = baos.toByteArray();
        log.info("[clothing-ref-grid] created: {}x{} px, {} bytes, 主图 + {} 张参考图",
            canvasW, canvasH, pngBytes.length, refs.size());
        return pngBytes;
    }

    /** 在指定位置画一个圆角黑底白字标签(用于网格图标记) */
    private static void drawLabel(Graphics2D g, FontMetrics fm,
                                  int x, int y, String text, int maxW) {
        int tw = fm.stringWidth(text);
        int padX = 8;
        int boxW = Math.min(tw + padX * 2, maxW - 16);
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRoundRect(x + 8, y + 4, boxW, 22, 6, 6);
        g.setColor(Color.WHITE);
        g.drawString(text, x + 8 + padX, y + 4 + 16);
    }

    /**
     * 2026-08-09: 根据源图尺寸检测输出 size(保持宽高比)
     * NewAPI gpt-image-2-1k 支持的尺寸:
     *   - 1024x1024 (1:1 方形)
     *   - 1024x1536 (2:3 竖图)
     *   - 1536x1024 (3:2 横图)
     *   - 2048x2048 (大方形)
     */
    private static String detectOutputSize(byte[] imageBytes) {
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
                new java.io.ByteArrayInputStream(imageBytes));
            if (img == null) return "1024x1024";
            int w = img.getWidth();
            int h = img.getHeight();
            double ratio = (double) w / h;
            // 选最接近的比例
            if (ratio > 1.4) return "1536x1024";      // 横图(16:9、4:3 等)
            if (ratio < 0.71) return "1024x1536";     // 竖图(9:16、3:4 等)
            return "1024x1024";                       // 接近正方形
        } catch (Exception e) {
            return "1024x1024";  // fallback
        }
    }

    /**
     * 从 settings JSON 中解析 frameCount
     * settings 格式: {"frameCount":9,"source":"video-extract"}
     * @return frameCount, 解析失败返回 0
     */
    private int parseFrameCount(String settings) {
        if (settings == null || settings.isBlank()) return 0;
        try {
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(settings);
            if (node.has("frameCount")) {
                return node.get("frameCount").asInt(0);
            }
        } catch (Exception e) {
            log.warn("[clothing-transfer] 解析 settings 失败: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 将抽帧总图拆分成多帧
     * 总图布局与 VideoFrameCaptionService.combineAndUploadFrames 一致:
     *   - 3 列 (MAX_COLS = 3)
     *   - 每帧宽 320px (FRAME_W = 320)
     *   - 间距 8px (GAP = 8)
     *   - 背景浅灰色 (245, 247, 250)
     *
     * @param gridUrl  总图 URL
     * @param outDir   输出目录
     * @param frameCount 帧数
     * @return 拆分后的帧元数据列表
     */
    private List<FrameMeta> splitFrameGrid(String gridUrl, Path outDir, int frameCount) throws Exception {
        // 下载总图
        byte[] gridBytes;
        try (java.io.InputStream in = new java.net.URI(gridUrl).toURL().openStream()) {
            gridBytes = in.readAllBytes();
        }
        
        // 读取总图
        BufferedImage gridImg = ImageIO.read(new ByteArrayInputStream(gridBytes));
        if (gridImg == null) {
            throw new BusinessException(com.jurong.aicenter.exception.ErrorCode.INTERNAL_ERROR, "无法读取抽帧总图");
        }
        
        int gridW = gridImg.getWidth();
        int gridH = gridImg.getHeight();
        log.debug("[clothing-transfer] 抽帧总图尺寸: {}x{} px, frameCount={}", gridW, gridH, frameCount);
        
        // 布局参数(与 combineAndUploadFrames 一致)
        final int MAX_COLS = 3;
        final int FRAME_W = 320;
        final int GAP = 8;
        
        // 计算行数
        int cols = Math.min(frameCount, MAX_COLS);
        int rows = (frameCount + cols - 1) / cols;
        
        // 计算每帧高度(基于总图尺寸和布局)
        int rowH = (gridH - (rows - 1) * GAP) / rows;
        if (rowH < 100) rowH = 100;
        
        List<FrameMeta> frames = new ArrayList<>();
        for (int i = 0; i < frameCount; i++) {
            int col = i % cols;
            int row = i / cols;
            
            // 计算帧在总图中的位置
            int x = col * (FRAME_W + GAP);
            int y = row * (rowH + GAP);
            
            // 边界检查
            int frameW = Math.min(FRAME_W, gridW - x);
            int frameHeight = Math.min(rowH, gridH - y);
            
            if (frameW <= 0 || frameHeight <= 0) {
                log.warn("[clothing-transfer] 帧 {} 超出总图边界,跳过", i);
                continue;
            }
            
            // 裁剪帧
            BufferedImage frameImg = gridImg.getSubimage(x, y, frameW, frameHeight);
            
            // 保存帧
            String filename = String.format("frame-%04d.jpg", i + 1);
            Path framePath = outDir.resolve(filename);
            ImageIO.write(frameImg, "jpg", framePath.toFile());
            
            frames.add(new FrameMeta(i, i, framePath));
            log.debug("[clothing-transfer] 帧 {} 拆分完成: x={}, y={}, {}x{}", i, x, y, frameW, frameHeight);
        }
        
        log.debug("[clothing-transfer] 抽帧总图拆分完成: {} 帧", frames.size());
        return frames;
    }

    private void failTask(CanvasTask task, CanvasNode node, String rawMsg) {
        String safe = rawMsg == null ? "未知错误"
            : (rawMsg.length() > 500 ? rawMsg.substring(0, 500) + "..." : rawMsg);
        task.setStatus("failed");
        task.setErrorMessage(safe);
        task.setCompletedAt(LocalDateTime.now());
        node.setStatus("failed");
        node.setFailReason(safe);
        node.setUpdatedAt(LocalDateTime.now());
    }
}