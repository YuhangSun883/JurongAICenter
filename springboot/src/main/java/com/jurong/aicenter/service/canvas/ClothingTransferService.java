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

    /** 单帧换装 prompt:保持主体不变,只换衣服 */
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

    /** 总图换装 prompt:一次性替换所有帧的衣服和人脸(性能优化) */
    private static final String GRID_CLOTHING_PROMPT_TEMPLATE =
        "图1是一张视频抽帧拼图,包含%FRAME_COUNT%帧画面(按行排列,每行3帧):\n" +
        "请对图1中所有帧画面的人物进行服装和人脸替换:\n" +
        "1. 服装: 将所有帧中人物穿的衣服,替换为%CLOTHING_DESC%展示的那件衣服\n" +
        "2. 人脸: 如果参考图中包含可见的人脸(特别是模特的脸),将所有帧中人物的脸也替换为该模特的脸\n" +
        "严格要求:\n" +
        "- 每一帧人物的动作、姿势、表情、发型(除衣服和脸外)完全不变\n" +
        "- 每一帧的背景、光照、相机角度完全不变\n" +
        "- 人物的身材、肤色保持不变\n" +
        "- 只能改变衣服的款式/颜色/面料/图案 以及 脸部的五官\n" +
        "- 保持原图的拼图布局、帧排列顺序和每帧的位置不变\n" +
        "- 输出图片尺寸与图1完全一致\n" +
        "- 所有帧的换装风格必须统一,衣服款式和人脸保持一致";

    /** 视频节点换装 prompt:直接对视频帧整体换装 */
    private static final String VIDEO_CLOTHING_PROMPT_TEMPLATE =
        "将图1(视频)中人物的服装和人脸同时进行替换:\n" +
        "1. 服装: 将图1中人物穿的衣服,替换为%CLOTHING_DESC%展示的那件衣服\n" +
        "2. 人脸: 如果参考图中包含可见的人脸(特别是模特的脸),将图1中人物的脸也替换为该模特的脸\n" +
        "严格要求:\n" +
        "- 人物的动作、姿势、表情、发型(除被替换的部分外)完全不变\n" +
        "- 背景、光照、相机角度完全不变\n" +
        "- 人物的身材、肤色保持不变\n" +
        "- 只能改变衣服的款式/颜色/面料/图案 以及 脸部的五官\n" +
        "- 输出图片尺寸与图1一致";

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
        if (clothingNodeIds == null || clothingNodeIds.size() != 3) {
            log.error("[clothing-transfer] 需要 3 张衣服图,实际 {} 张", clothingNodeIds == null ? 0 : clothingNodeIds.size());
            failTask(task, targetNode, "需要恰好 3 张衣服参考图(正面/背面/模特上身)");
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

            // ② 加载 3 张衣服图
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
            log.debug("[clothing-transfer] 衣服图加载完成: 3 张");

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
                    .replace("%CLOTHING_DESC%", buildClothingDesc(clothingDataUris.size()));
                log.debug("[clothing-transfer] 整体换装(总图): frameCount={}, 1次API调用", frameCount);
            } else {
                prompt = VIDEO_CLOTHING_PROMPT_TEMPLATE
                    .replace("%CLOTHING_DESC%", buildClothingDesc(clothingDataUris.size()));
                log.debug("[clothing-transfer] 整体换装(单图/视频): 1次API调用");
            }

            // 2026-08-11:如果用户有自定义描述,拼到 prompt 末尾作为强约束
            if (!userInstructionBlock.isEmpty()) {
                prompt = prompt + userInstructionBlock;
                log.debug("[clothing-transfer] 用户自定义描述已拼接到 prompt: {} 字符",
                    userInstruction.trim().length());
            }

            // 调 NewAPI
            List<String> refs = new ArrayList<>(4);
            refs.add(sourceDataUri);
            refs.addAll(clothingDataUris);
            String outputSize = detectOutputSize(sourceBytes);
            log.debug("[clothing-transfer] 开始 NewAPI editImage: refs={}, outputSize={}",
                refs.size(), outputSize);
            String resultDataUri = newApiClient.editImage(
                prompt, refs, outputSize, "low", null);

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