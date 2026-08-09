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
import java.io.InputStream;
import java.net.URL;
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

    /** 换装专用 prompt:严格保持主体不变,只换衣服 */
    private static final String CLOTHING_PROMPT_TEMPLATE =
        "将图1(原视频帧)中人物的服装和人脸同时进行替换:\n" +
        "1. 服装: 将图1中人物穿的衣服,替换为%CLOTHING_DESC%展示的那件衣服\n" +
        "2. 人脸: 如果参考图中包含可见的人脸(特别是模特的脸),将图1中人物的脸也替换为该模特的脸\n" +
        "严格要求:\n" +
        "- 人物的动作、姿势、表情、发型(除被替换的部分外)完全不变\n" +
        "- 背景、光照、相机角度完全不变\n" +
        "- 人物的身材、肤色保持不变\n" +
        "- 只能改变衣服的款式/颜色/面料/图案 以及 脸部的五官\n" +
        "- 如果原图人物本来没穿这件衣服(比如手里拿着、放在旁边),保持原状,只替换穿在身上的\n" +
        "- 输出图片尺寸与图1一致\n" +
        "如果有多个参考图，它们是同一件衣服/同一个人的不同角度，最终输出里这件衣服/脸的不同角度都应与参考图一致";

    /** 2026-08-09 根据衣服图数量动态生成 prompt 描述部分 */
    private static String buildClothingDesc(int count) {
        if (count <= 0) return "";
        if (count == 1) return "图2(衣服参考)";
        if (count == 2) return "图2、图3 展示的那件衣服";
        if (count == 3) return "图2(衣服正面)、图3(衣服背面)、图4(模特上身)展示的那件衣服";
        StringBuilder sb = new StringBuilder("以下参考图: ");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append("、");
            sb.append("图").append(i + 2);
        }
        sb.append(" 展示的那件衣服");
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
                                      List<String> clothingNodeIds, Long userId) {
        if (task == null || videoNode == null) {
            log.error("[clothing-transfer] task/videoNode null");
            return;
        }
        if (clothingNodeIds == null || clothingNodeIds.size() != 3) {
            log.error("[clothing-transfer] 需要 3 张衣服图,实际 {} 张", clothingNodeIds == null ? 0 : clothingNodeIds.size());
            failTask(task, videoNode, "需要恰好 3 张衣服参考图(正面/背面/模特上身)");
            taskRepository.updateById(task);
            nodeRepository.updateById(videoNode);
            return;
        }

        String taskId = task.getId();
        String nodeId = videoNode.getId();

        // running
        task.setStatus("running");
        task.setStartedAt(LocalDateTime.now());
        taskRepository.updateById(task);
        log.info("=== [clothing-transfer] START taskId={} source={} clothing={} ===",
            taskId, nodeId, clothingNodeIds);

        long start = System.currentTimeMillis();
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "clothing-transfer-" + taskId);

        List<String> createdIds = new ArrayList<>();

        try {
            List<FrameMeta> frames;
            if ("image".equalsIgnoreCase(videoNode.getType())) {
                // 2026-08-09:image 节点本身是拼图(image_1 就是拼图),直接当作 1 帧
                frames = new ArrayList<>();
                // 必须先创建 tmpDir,否则 Files.copy 报 NoSuchFileException
                java.nio.file.Files.createDirectories(tmpDir);
                java.io.InputStream in = new java.net.URL(videoNode.getResultUrl()).openStream();
                Path tmpFrame = tmpDir.resolve("frame-0001.jpg");
                java.nio.file.Files.copy(in, tmpFrame, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                in.close();
                frames.add(new FrameMeta(0, 0, tmpFrame));
                log.info("[clothing-transfer] image 节点不抽帧,只使用原图: 1 帧, videoNodeId={}", nodeId);
            } else {
                // video 节点:正常抽帧
                frames = extractor.extractFrames(videoNode.getResultUrl(), tmpDir, 1.0);
                log.info("[clothing-transfer] 抽帧完成: {} 帧, videoNodeId={}", frames.size(), nodeId);
            }

            // ② 加载 3 张衣服图,转 base64 data URI
            List<String> clothingDataUris = new ArrayList<>(3);
            for (String cid : clothingNodeIds) {
                byte[] bytes = downloadNodeImageBytes(cid, userId);
                if (bytes == null) {
                    throw new BusinessException(com.jurong.aicenter.exception.ErrorCode.INTERNAL_ERROR,
                        "衣服节点 " + cid + " 加载失败");
                }
                String mime = detectMime(bytes);
                clothingDataUris.add("data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
            }
            log.info("[clothing-transfer] 衣服图加载完成: 3 张, totalBytes={}",
                clothingDataUris.stream().mapToInt(s -> s.length()).sum());

            // ③ 逐帧调 NewAPI /v1/images/edits(image_1=原帧、image_2/3/4=衣服参考)
            List<String> transferResultUrls = new ArrayList<>();
            int frameIdx = 0;
            for (FrameMeta frame : frames) {
                try {
                    byte[] frameBytes = Files.readAllBytes(frame.path());
                    String frameDataUri = "data:image/jpeg;base64," +
                        Base64.getEncoder().encodeToString(frameBytes);

                    List<String> refs = new ArrayList<>(4);
                    refs.add(frameDataUri);
                    refs.addAll(clothingDataUris);

                    log.info("[clothing-transfer] 帧 {}/{} 调 NewAPI editImage, promptLen={}, refs={}",
                        frameIdx + 1, frames.size(), CLOTHING_PROMPT_TEMPLATE.length(), refs.size());

                    String resultDataUri = newApiClient.editImage(
                        CLOTHING_PROMPT_TEMPLATE.replace("%CLOTHING_DESC%", buildClothingDesc(clothingDataUris.size())),
                        refs, detectOutputSize(frameBytes), "low", null);

                    // resultDataUri 是 "data:image/png;base64,..." 或 URL,统一解析上传 MinIO
                    byte[] resultBytes = decodeResultDataUri(resultDataUri);
                    String key = "clothing-transfer/" + nodeId + "/frame-" + String.format("%02d", frameIdx) +
                        "-" + System.currentTimeMillis() + ".png";
                    String url;
                    try (InputStream in = new ByteArrayInputStream(resultBytes)) {
                        url = storageService.uploadObject(key, in, "image/png");
                    }
                    transferResultUrls.add(url);
                    log.info("[clothing-transfer] 帧 {}/{} 完成 → {}", frameIdx + 1, frames.size(), url);
                } catch (Exception frameErr) {
                    log.warn("[clothing-transfer] 帧 {}/{} 失败: {}",
                        frameIdx + 1, frames.size(), frameErr.getMessage());
                    transferResultUrls.add(null);  // 占位,后续拼图时跳过
                }
                frameIdx++;
            }

            int successCount = (int) transferResultUrls.stream().filter(u -> u != null).count();
            log.info("[clothing-transfer] 全部帧完成: {}/{} 成功", successCount, frames.size());

            if (successCount == 0) {
                failTask(task, videoNode, "所有帧换装失败,NewAPI 可能不可达");
                taskRepository.updateById(task);
                nodeRepository.updateById(videoNode);
                return;
            }

            // ④ 自动建 image 节点(每张成功换装图 1 个)
            int imageNodeCount = 0;
            for (int i = 0; i < transferResultUrls.size(); i++) {
                String url = transferResultUrls.get(i);
                if (url == null) continue;
                CanvasNode imgNode = new CanvasNode();
                imgNode.setUserId(videoNode.getUserId());
                imgNode.setCanvasId(videoNode.getCanvasId());
                imgNode.setType("image");
                imgNode.setTitle("换装 #" + String.format("%02d", i + 1));
                imgNode.setResultUrl(url);
                // 位置:视频节点右边,4 列布局,行高 240
                int col = imageNodeCount % 4;
                int row = imageNodeCount / 4;
                imgNode.setPositionX((videoNode.getPositionX() == null ? 0 : videoNode.getPositionX()) + 360 + col * 340);
                imgNode.setPositionY((videoNode.getPositionY() == null ? 0 : videoNode.getPositionY()) + row * 240);
                imgNode.setStatus("success");
                LocalDateTime now = LocalDateTime.now();
                imgNode.setCreatedAt(now);
                imgNode.setUpdatedAt(now);
                nodeRepository.insert(imgNode);
                createdIds.add(imgNode.getId());
                imageNodeCount++;
            }
            log.info("[clothing-transfer] 建 image 节点: {} 个", imageNodeCount);

            // ⑤ 不再生成拼图节点(用户只要 frame-00-...png 单图)
//    保留 combineTransferResults 方法代码备以后需要,但不调用

            // ⑥ task SUCCESS
            task.setStatus("success");
            task.setDurationMs((int) (System.currentTimeMillis() - start));
            task.setCompletedAt(LocalDateTime.now());
            try {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                task.setCreatedNodeIds(mapper.writeValueAsString(createdIds));
            } catch (Exception jsonErr) {
                log.warn("[clothing-transfer] createdNodeIds JSON 失败(非致命): {}", jsonErr.getMessage());
            }
            log.info("[clothing-transfer] SUCCESS: taskId={}, frames={}, successFrames={}, imageNodes={}, durationMs={}",
                taskId, frames.size(), successCount, createdIds.size(), task.getDurationMs());
            log.info("=== [clothing-transfer] DONE taskId={} status=SUCCESS durationMs={} ===",
                taskId, task.getDurationMs());
        } catch (BusinessException e) {
            log.error("[clothing-transfer] BIZ_FAIL: taskId={}, err={}", taskId, e.getMessage());
            log.error("=== [clothing-transfer] DONE taskId={} status=FAILED reason=BIZ durationMs={} ===",
                taskId, System.currentTimeMillis() - start);
            failTask(task, videoNode, e.getMessage());
        } catch (Exception e) {
            log.error("[clothing-transfer] FAIL: taskId={}, err={}", taskId, e.getMessage(), e);
            log.error("=== [clothing-transfer] DONE taskId={} status=FAILED reason=EXCEPTION durationMs={} ===",
                taskId, System.currentTimeMillis() - start);
            failTask(task, videoNode, e.getMessage() == null ? "未知错误" : e.getMessage());
        } finally {
            try {
                if (Files.exists(tmpDir)) {
                    Files.walk(tmpDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignore) {} });
                }
            } catch (Exception ignore) {}
            taskRepository.updateById(task);
            nodeRepository.updateById(videoNode);
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
        try (InputStream in = new URL(url).openStream()) {
            return in.readAllBytes();
        }
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
        final int FRAME_H = (int) Math.round((double) FRAME_W * origH / origW);
        if (FRAME_H < 100) FRAME_H_TYPE_HACK: {} // ignore, will set below
        final int frameH;
        if (FRAME_H < 100) frameH = 100;
        else if (FRAME_H > 540) frameH = 540;
        else frameH = FRAME_H;

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
        log.info("[clothing-grid] created: {} ({}x{} px, {} frames)", url, canvasW, canvasH, total);
        return url;
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