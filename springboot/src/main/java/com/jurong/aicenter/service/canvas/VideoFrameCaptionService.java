package com.jurong.aicenter.service.canvas;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.client.NewApiClient;
import com.jurong.aicenter.dto.canvas.NodeConnection;
import com.jurong.aicenter.client.NewApiClient;
import com.jurong.aicenter.entity.CanvasNode;
import com.jurong.aicenter.entity.CanvasTask;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.repository.CanvasNodeRepository;
import com.jurong.aicenter.repository.CanvasTaskRepository;
import com.jurong.aicenter.service.StorageService;
import com.jurong.aicenter.service.VideoFrameExtractor;
import com.jurong.aicenter.service.VideoFrameExtractor.FrameMeta;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.stream.IntStream;

/**
 * 视频抽帧 + VL 模型 caption 编排服务。
 *
 * 链路(每个阶段用 captionExecutor 线程池并行):
 *   1. ffmpeg 抽帧到本地临时目录(1 fps)
 *   2. 每帧上传到 MinIO,拿到公网 URL
 *   3. 对每帧 URL 调 gpt-5.5 vision 模型(json_schema 强约束输出 {camera, action})
 *   4. 把结果按口播文案模板拼成文本,写回 CanvasNode.content
 *   5. CanvasTask 标记 success / failed
 *   6. 清理本地临时目录
 *
 * 注意:此 Bean 必须被另一个 Bean 调用,@Async 才生效(Spring AOP 自调用失效)。
 */
@Slf4j
@Component
public class VideoFrameCaptionService {

    /** 发给 VL 模型的 prompt —— 单帧版,聚焦动作而不是静态画面,严格 30 字内 */
    private static final String CAPTION_PROMPT = """
        用一句简洁中文描述这一秒视频帧中发生的主要动作或变化
        (聚焦动作和前后差异,不要静态描述画面里有什么),不超过30字,只输出描述本身,不要任何前缀。
        运镜(camera)方面,基于画面构图判断,如:固定/推近/拉远/仰拍/俯拍/微推近/特写等。""";

    /**
     * 批量版 prompt —— 一次给模型 N 张连续帧的图(按时间顺序)。
     * 模型能"看到"前后帧,识别运镜变化和动作连续性,比单帧更准。
     */
    private static final String CAPTION_BATCH_PROMPT = """
        下面是视频的连续多帧(按时间顺序,1 帧 1 秒)。
        请逐帧描述,返回 JSON 数组(顺序与图片一致):
        [{"camera":"运镜方式","action":"动作描述"}, ...]

        要求:
        - camera:运镜方式,如固定/推近/拉远/仰拍/俯拍/微推近/特写等
        - action:这一秒发生的主要动作或变化,30字内,聚焦动作和前后差异
        - 看连续帧时,重点识别运镜的变化(比如前 2 帧固定,第 3 帧开始推近)
          和主体的连续动作(比如转身、手势、移动的轨迹)""";

    /** 批量大小:每 N 帧打一次 NewAPI(N 张图 + 1 个 prompt),9 帧 → 3 次请求 */
    private static final int CAPTION_BATCH_SIZE = 3;

    private final VideoFrameExtractor extractor;
    private final NewApiClient newApiClient;
    private final StorageService storageService;
    private final CanvasTaskRepository taskRepository;
    private final CanvasNodeRepository nodeRepository;
    @Qualifier("captionExecutor")
    private final Executor captionExecutor;

    // 限流:同时最多 3 个 caption 请求打 NewAPI(避免打爆中转模型)
    private final Semaphore captionSemaphore = new Semaphore(3);

    // 错峰:每帧请求前加递增延迟,让 NewAPI 喘口气
    private static final long CAPTION_STAGGER_MS = 50;
    private final ObjectMapper objectMapper;

    public VideoFrameCaptionService(VideoFrameExtractor extractor,
                                    NewApiClient newApiClient,
                                    StorageService storageService,
                                    CanvasTaskRepository taskRepository,
                                    CanvasNodeRepository nodeRepository,
                                    @Qualifier("captionExecutor") Executor captionExecutor,
                                    ObjectMapper objectMapper) {
        this.extractor = extractor;
        this.newApiClient = newApiClient;
        this.storageService = storageService;
        this.taskRepository = taskRepository;
        this.nodeRepository = nodeRepository;
        this.captionExecutor = captionExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * 异步入口。被 CanvasServiceImpl.extractAndCaption() 调用。
     */
    @Async("captionExecutor")
    public void executeCaptionAsync(CanvasTask task, CanvasNode node,
                                    String videoUrl, double fps, Long userId, String mode) {
        if (task == null || node == null) {
            log.error("[video-caption] task/node missing (passed null entity)");
            return;
        }
        String taskId = task.getId();
        String nodeId = node.getId();

        // running
        task.setStatus("running");
        task.setStartedAt(LocalDateTime.now());
        taskRepository.updateById(task);

        long start = System.currentTimeMillis();
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "video-frame-" + taskId);

        try {
            // ① 抽帧
            List<FrameMeta> frames = extractor.extractFrames(videoUrl, tmpDir, fps);
            log.info("[video-caption] 抽帧完成: {} 帧, fps={}, mode={}", frames.size(), fps, mode);

            // ② 并行上传到 MinIO(无论 mode 都要上传,frames mode 要在 canvas 上展示)
            List<CompletableFuture<String>> uploadJobs = frames.stream()
                .map(f -> CompletableFuture.supplyAsync(() -> {
                    try (InputStream in = Files.newInputStream(f.path())) {
                        String key = "video-frame/" + nodeId + "/" + f.path().getFileName();
                        return storageService.uploadObject(key, in, "image/jpeg");
                    } catch (Exception e) {
                        log.warn("[video-caption] 帧上传失败: {}", f.path(), e);
                        return null;
                    }
                }, captionExecutor))
                .toList();
            List<String> frameUrls = uploadJobs.stream().map(CompletableFuture::join).toList();
            long uploadOk = frameUrls.stream().filter(u -> u != null).count();
            log.info("[video-caption] 帧上传完成: {}/{} 成功", uploadOk, frameUrls.size());

            boolean needCaption = "script".equals(mode) || "both".equals(mode);
            String content = "";

            // ④ caption(只 script/both 才需要)
                        // ④a 先做 ASR 提取口播原文(增强功能,失败不影响主流程)
            Map<Integer, String> dubMap = new java.util.HashMap<>();
            try {
                Path tempVideoDir = frames.get(0).path().getParent();
                Path audioFile = tempVideoDir.resolve("audio.wav");
                Path extracted = extractor.extractAudio(node.getResultUrl(), audioFile);
                if (extracted != null) {
                    byte[] audioBytes = java.nio.file.Files.readAllBytes(extracted);
                    List<Map<String, Object>> segments = newApiClient.audioTranscribe(audioBytes, "audio/wav");
                    // 把 segments 按帧时间映射:每帧 [t-0.5, t+0.5] 区间内的口播拼接
                    for (int i = 0; i < frames.size(); i++) {
                        double t = frames.get(i).timestampSeconds();
                        StringBuilder dubBuilder = new StringBuilder();
                        for (Map<String, Object> seg : segments) {
                            // 注意:命名加 seg 前缀,避免和外层方法的 start/end 变量冲突
                            double segStart = (double) seg.get("start");
                            double segEnd = (double) seg.get("end");
                            double segMid = (segStart + segEnd) / 2.0;
                            if (segMid >= t - 0.5 && segMid <= t + 0.5) {
                                if (dubBuilder.length() > 0) dubBuilder.append(" ");
                                dubBuilder.append((String) seg.get("text"));
                            }
                        }
                        if (dubBuilder.length() > 0) {
                            dubMap.put(i, dubBuilder.toString());
                        }
                    }
                    log.info("[video-caption] ASR 完成: {} 个 segments,映射到 {}/{} 帧",
                        segments.size(), dubMap.size(), frames.size());
                }
            } catch (Exception asrErr) {
                log.warn("[video-caption] ASR 失败(非致命): {}", asrErr.getMessage());
            }

            if (needCaption) {
                // 批量 caption:每 CAPTION_BATCH_SIZE 帧打一次 NewAPI(一次传多张图)
                // 9 帧 → 3 个 batch,每个 batch 返回 N 个 caption
                // 模型能看到连续帧 → 运镜变化 + 连续动作 比单帧更准
                final int batchSize = CAPTION_BATCH_SIZE;
                final int totalBatches = (frames.size() + batchSize - 1) / batchSize;

                List<CompletableFuture<List<Map<String, String>>>> batchJobs = IntStream.range(0, totalBatches)
                    .<CompletableFuture<List<Map<String, String>>>>mapToObj(batchIdx -> CompletableFuture.supplyAsync(() -> {
                        try {
                            // 错峰:每个 batch 启动前等 batchIdx * 50ms(3 个 batch 总计 100ms)
                            if (CAPTION_STAGGER_MS > 0 && batchIdx > 0) {
                                Thread.sleep(batchIdx * CAPTION_STAGGER_MS);
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return List.of();
                        }
                        // 限流:Semaphore 同时最多 3 个并发 batch
                        try {
                            captionSemaphore.acquire();
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return List.of();
                        }
                        try {
                            int startIdx = batchIdx * batchSize;
                            int endIdx = Math.min(startIdx + batchSize, frames.size());
                            List<String> batchUrls = new java.util.ArrayList<>();
                            for (int i = startIdx; i < endIdx; i++) {
                                batchUrls.add(frameUrls.get(i));
                            }
                            try {
                                List<Map<String, String>> batchCaptions = newApiClient.visionCaptionBatch(batchUrls, CAPTION_BATCH_PROMPT);
                                if (batchCaptions.size() != batchUrls.size()) {
                                    log.warn("[video-caption] batch {} 返回数量不匹配: 期望 {},实际 {}", batchIdx, batchUrls.size(), batchCaptions.size());
                                    while (batchCaptions.size() < batchUrls.size()) {
                                        batchCaptions.add(Map.of("camera", "__FAILED__", "action", "模型返回缺失"));
                                    }
                                    if (batchCaptions.size() > batchUrls.size()) {
                                        batchCaptions = batchCaptions.subList(0, batchUrls.size());
                                    }
                                }
                                return batchCaptions;
                            } catch (Exception e) {
                                log.warn("[video-caption] batch {} 失败: {}", batchIdx, e.getMessage());
                                String reason = e.getMessage() == null ? "未知错误" : e.getMessage();
                                if (reason.length() > 100) reason = reason.substring(0, 100) + "...";
                                List<Map<String, String>> failList = new java.util.ArrayList<>();
                                for (int i = 0; i < batchUrls.size(); i++) {
                                    failList.add(Map.of("camera", "__FAILED__", "action", reason));
                                }
                                return failList;
                            }
                        } finally {
                            captionSemaphore.release();
                        }
                    }, captionExecutor))
                    .toList();

                // Flatten batch 结果成 captions(每 batch 返回 N 个)
                List<List<Map<String, String>>> batchResults = batchJobs.stream().map(CompletableFuture::join).toList();
                List<Map<String, String>> captions = new java.util.ArrayList<>();
                for (List<Map<String, String>> br : batchResults) {
                    captions.addAll(br);
                }

                // 2026-08-09 fix: VL 全部 batch 失败时,标 task FAILED 而不是 SUCCESS
                // (之前 3 个 batch 全 503 但 task 还标 SUCCESS,导致空 text 节点,误导用户)
                long failedCount = captions.stream()
                    .filter(c -> "__FAILED__".equals(c.get("camera")))
                    .count();
                if (!captions.isEmpty() && failedCount == captions.size()) {
                    failTask(task, node,
                        "VL 视觉模型全部失败(" + failedCount + "/" + captions.size() + "),NewAPI 视觉模型不可达");
                    log.error("[video-caption] taskId={} 全 {}/{} 帧 VL 失败,task 已标 FAILED",
                        taskId, failedCount, captions.size());
                    return;  // 跳出 handleExtractCaption,不再走 SUCCESS 路径
                }
                if (failedCount > 0) {
                    log.warn("[video-caption] taskId={} 部分帧 VL 失败 {}/{},降级为 SUCCESS 包含 __FAILED__ 标记",
                        taskId, failedCount, captions.size());
                }

                // 拼口播文案模板
                content = assembleScript(frames, captions, dubMap);
                node.setContent(content);
                task.setTextResult(content);
            } else {
                // frames 模式：不调 VL，video 节点放状态标记
                node.setContent(String.format("已抽帧 %d 张，按 F5 查看", frames.size()));
            }

            node.setStatus("success");
            node.setUpdatedAt(LocalDateTime.now());

            // ⑤ 视觉 sidecar：根据 mode 只建需要的
            //    失败不影响主流程，降级为 WARN
            String combinedUrl = null;
            if ("frames".equals(mode) || "both".equals(mode)) {
                // 抽帧时（frames / both）：用 BufferedImage 拼成 1 张大图上传，只建 1 个 image 节点
                try {
                    combinedUrl = combineAndUploadFrames(frames, nodeId);
                } catch (Exception combineErr) {
                    log.warn("[video-caption] combine frames failed (non-fatal): {}",
                        combineErr.getMessage(), combineErr);
                }
            }
            // 收集本次任务新建的节点 ID，写进 task.createdNodeIds，
            // 前端轮询成功后只拉这些节点追加，不 reload 整张画布
            java.util.List<String> createdIds = new java.util.ArrayList<>();
            try {
                createSidecarByMode(node, frameUrls, content, mode, combinedUrl, createdIds);
            } catch (Exception sidecarErr) {
                log.warn("[video-caption] sidecar creation failed (non-fatal): {}",
                    sidecarErr.getMessage(), sidecarErr);
            }

            task.setStatus("success");
            task.setDurationMs((int) (System.currentTimeMillis() - start));
            task.setCompletedAt(LocalDateTime.now());
            try {
                task.setCreatedNodeIds(objectMapper.writeValueAsString(createdIds));
            } catch (Exception jsonErr) {
                log.warn("[video-caption] createdNodeIds JSON failed (non-fatal): {}",
                    jsonErr.getMessage());
            }

            log.info("[video-caption] SUCCESS: taskId={}, frames={}, mode={}, durationMs={}",
                taskId, frames.size(), mode, task.getDurationMs());
        } catch (BusinessException e) {
            log.error("[video-caption] BIZ_FAIL: taskId={}, err={}", taskId, e.getMessage());
            failTask(task, node, e.getMessage());
        } catch (Exception e) {
            log.error("[video-caption] FAIL: taskId={}, err={}", taskId, e.getMessage(), e);
            String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
            failTask(task, node, msg);
        } finally {
            // 清理临时帧
            try {
                if (Files.exists(tmpDir)) {
                    Files.walk(tmpDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (Exception ignore) {}
                        });
                }
            } catch (Exception ignore) {}
            taskRepository.updateById(task);
            nodeRepository.updateById(node);
        }
    }

    /**
     * 视频抽帧 sidecar：在视频节点右边自动建
     *   - N 个 image 节点（每帧一张，4 列网格，超过 20 帧等距采样）
     *   - 1 个 text 节点（口播文案全文）
     * 不自动连边，让用户手动拖。
     *
     * 直接用 nodeRepository 操作,避免循环依赖 CanvasService。
     * 不 bump 父画布的 updated_at(需要 CanvasRepository,暂时不做,UX 上只是 "我的创作" 列表排序不变)。
     */
    /**
     * 视频抽帧 sidecar：根据 mode 只建需要的节点
     *   - mode="script" / "both"：在视频节点右边建 1 个口播文案文本节点
     *   - mode="frames" / "both"：在视频节点右边建 1 个帧拼图大节点（combinedUrl）
     */
    private void createSidecarByMode(CanvasNode videoNode, List<String> frameUrls,
                                       String scriptText, String mode, String combinedUrl,
                                       java.util.List<String> createdIds) {
        boolean needText = "script".equals(mode) || "both".equals(mode);
        boolean needFrames = "frames".equals(mode) || "both".equals(mode);

        if (!needText && !needFrames) {
            log.warn("[video-sidecar] 未知 mode={}，什么都不建", mode);
            return;
        }

        CanvasNode frameGridNode = null;
        if (needFrames) {
            frameGridNode = createFrameGridSidecar(videoNode, combinedUrl, createdIds);
            if (frameGridNode != null) {
                connectNodes(videoNode, "frames", frameGridNode, "video");
            }
        }
        if (needText) {
            CanvasNode parent = needFrames && frameGridNode != null ? frameGridNode : videoNode;
            CanvasNode textNode = createScriptTextSidecar(videoNode, scriptText, needFrames, createdIds);
            if (textNode != null) {
                connectNodes(parent, "text", textNode, needFrames ? "frames" : "video");
            }
        }
    }

    /**
     * 只建 1 个帧拼图大节点（在视频节点右边）。
     * combinedUrl 是 combineAndUploadFrames 拼图后上传到 MinIO 的公网 URL。
     * 帧内容、文字标注、网格布局都在那张大图里里。画布上只看到 1 个 image 节点。
     */
    private CanvasNode createFrameGridSidecar(CanvasNode videoNode, String combinedUrl,
                                              java.util.List<String> createdIds) {
        if (combinedUrl == null || combinedUrl.isBlank()) {
            log.warn("[video-sidecar-frames] combinedUrl 为空，跳过帧拼图节点创建");
            return null;
        }
        final int SIDE_OFFSET = 360;
        int baseX = (videoNode.getPositionX() == null ? 0 : videoNode.getPositionX()) + SIDE_OFFSET;
        int baseY = videoNode.getPositionY() == null ? 0 : videoNode.getPositionY();

        CanvasNode node = new CanvasNode();
        node.setUserId(videoNode.getUserId());
        node.setCanvasId(videoNode.getCanvasId());
        node.setType("image");
        node.setTitle("抽帧拼图");
        node.setResultUrl(combinedUrl);
        node.setPositionX(baseX);
        node.setPositionY(baseY);
        node.setStatus("success");
        LocalDateTime now = LocalDateTime.now();
        node.setCreatedAt(now);
        node.setUpdatedAt(now);
        nodeRepository.insert(node);
        createdIds.add(node.getId());

        log.info("[video-sidecar-frames] OK: videoNodeId={}, combinedNodeId={}",
            videoNode.getId(), node.getId());
        return node;
    }

    /**
     * 把抽出来的所有帧拼成 1 张大图（网格布局），上传到 MinIO，返回公网 URL。
     * 帧按提取顺序从左到右、从上到下铺；每帧 256x256 + 8px 间距 + 秒数黑底白字。
     * 背景淺淺灰，整体看起来像「抽帧采样表」。
     */
    private String combineAndUploadFrames(List<FrameMeta> frames, String nodeId) {
        if (frames == null || frames.isEmpty()) {
            return null;
        }
        final int FRAME_W = 320;       // 宽点更好看(原来 256 太挤)
        final int GAP = 8;
        final int MAX_COLS = 3;
        try {
            int n = frames.size();
            int cols = Math.min(n, MAX_COLS);
            int rows = (n + cols - 1) / cols;

            // 从第一帧读出原始尺寸，按视频原始宽高比算每帧的高度
            // （所有帧都是同一个视频，aspect 一致）
            BufferedImage firstFrame = ImageIO.read(frames.get(0).path().toFile());
            int origW = firstFrame.getWidth();
            int origH = firstFrame.getHeight();
            int rowH = (int) Math.round((double) FRAME_W * origH / origW);
            // 限幅，防止极短/极长的视频让拼图变形
            if (rowH < 100) rowH = 100;
            if (rowH > 540) rowH = 540;

            int canvasW = cols * FRAME_W + (cols - 1) * GAP;
            int canvasH = rows * rowH + (rows - 1) * GAP;

            BufferedImage combined = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = combined.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // 背景：浅浅灰
            g.setColor(new Color(245, 247, 250));
            g.fillRect(0, 0, canvasW, canvasH);

            Font font = new Font("SansSerif", Font.BOLD, 16);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();

            for (int i = 0; i < n; i++) {
                int col = i % cols;
                int row = i / cols;
                FrameMeta f = frames.get(i);
                int x = col * (FRAME_W + GAP);
                int y = row * (rowH + GAP);

                // 复用第一帧的 BufferedImage(i=0 时),避免重复读文件
                BufferedImage frame = (i == 0) ? firstFrame : ImageIO.read(f.path().toFile());
                // 等比缩放到 FRAME_W × rowH（保持视频原始宽高比）
                Image scaled = frame.getScaledInstance(FRAME_W, rowH, Image.SCALE_SMOOTH);
                g.drawImage(scaled, x, y, null);

                // 每帧左上角叠加「第 N 秒」黑底白字
                String label = String.format("第 %ds", f.index() + 1);
                int tw = fm.stringWidth(label);
                int padX = 8;
                g.setColor(new Color(0, 0, 0, 160));
                g.fillRoundRect(x + 8, y + 4, tw + padX * 2, 22, 6, 6);
                g.setColor(Color.WHITE);
                g.drawString(label, x + 8 + padX, y + 4 + 16);
            }
            g.dispose();

            // 保存到临时文件，上传到 MinIO，清理临时文件
            Path tmpFile = Files.createTempFile("frame-grid-", ".jpg");
            ImageIO.write(combined, "jpg", tmpFile.toFile());
            String key = "video-frame-grid/" + nodeId + "/combined-" + System.currentTimeMillis() + ".jpg";
            String url;
            try (InputStream in = Files.newInputStream(tmpFile)) {
                url = storageService.uploadObject(key, in, "image/jpeg");
            }
            Files.deleteIfExists(tmpFile);
            log.info("[video-frame-grid] created: {} ({}x{} px, {} frames)",
                url, canvasW, canvasH, n);
            return url;
        } catch (Exception e) {
            log.error("[video-frame-grid] combine failed", e);
            return null;
        }
    }

    /**
     * 只建 1 个口播文案文本节点。
     * 如果已经有帧网格(text 节点应该放在帧网格下方而不是同一行)，
     * 位置会下移一整行的距离。
     */
    private CanvasNode createScriptTextSidecar(CanvasNode videoNode, String scriptText,
                                          boolean hasFrameGrid, java.util.List<String> createdIds) {
        final int Y_STEP = 240;
        final int COLS = 4;
        final int SIDE_OFFSET = 360;

        int baseX = (videoNode.getPositionX() == null ? 0 : videoNode.getPositionX()) + SIDE_OFFSET;
        int baseY = videoNode.getPositionY() == null ? 0 : videoNode.getPositionY();
        // 如果已经有帧网格，文本节点放在帧网格下方
        // 这里简单估算：假设帧网格填满 N 行，文本节点位置 = baseY + (N 行 * Y_STEP) + 40
        // 但我们不知道 N 是多少（取决于实际抽取帧数）。先用保守值：有帧网格时下移 5 行。
        int yOffset = hasFrameGrid ? (5 * Y_STEP + 40) : 0;

        CanvasNode textNode = new CanvasNode();
        textNode.setUserId(videoNode.getUserId());
        textNode.setCanvasId(videoNode.getCanvasId());
        textNode.setType("text");
        textNode.setTitle("脚本拆解");
        // 2026-08-09 15:08 恢复:脚本拆解生成的 text 节点,前端完整展示
        //   content = assembleScript 输出的完整脚本(含【节奏】【ShotXX】+各帧运镜/动作)
        textNode.setContent(scriptText == null || scriptText.isBlank() ? "(空)" : scriptText);
        textNode.setPositionX(baseX);
        textNode.setPositionY(baseY + yOffset);
        textNode.setStatus("success");
        textNode.setCreatedAt(LocalDateTime.now());
        textNode.setUpdatedAt(LocalDateTime.now());
        nodeRepository.insert(textNode);
        createdIds.add(textNode.getId());

        log.info("[video-sidecar-script] OK: videoNodeId={}, textNodeId={}, hasFrameGrid={}",
            videoNode.getId(), textNode.getId(), hasFrameGrid);
        return textNode;
    }

    private void connectNodes(CanvasNode from, String fromPort, CanvasNode to, String toPort) {
        if (from == null || to == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        from.setDownstreamIds(appendConnection(from.getDownstreamIds(), new NodeConnection(fromPort, to.getId())));
        from.setUpdatedAt(now);
        nodeRepository.updateById(from);

        to.setUpstreamIds(appendConnection(to.getUpstreamIds(), new NodeConnection(toPort, from.getId())));
        to.setUpdatedAt(now);
        nodeRepository.updateById(to);
    }

    private String appendConnection(String rawJson, NodeConnection conn) {
        if (conn == null || conn.getNodeId() == null || conn.getNodeId().isBlank()) {
            return rawJson;
        }
        List<NodeConnection> conns = new ArrayList<>();
        if (rawJson != null && !rawJson.isBlank()) {
            conns.addAll(parseConnections(rawJson));
        }
        boolean exists = conns.stream().anyMatch(c ->
            c != null
                && conn.getNodeId().equals(c.getNodeId())
                && ((conn.getPort() == null && c.getPort() == null)
                || (conn.getPort() != null && conn.getPort().equals(c.getPort()))));
        if (!exists) {
            conns.add(conn);
        }
        return serializeConnections(conns);
    }

    private List<NodeConnection> parseConnections(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<NodeConnection> parsed = objectMapper.readValue(json, new TypeReference<List<NodeConnection>>() {});
            return parsed == null ? Collections.emptyList() : parsed;
        } catch (Exception e) {
            try {
                List<String> old = objectMapper.readValue(json, new TypeReference<List<String>>() {});
                List<NodeConnection> converted = new ArrayList<>();
                for (String id : old) {
                    if (id != null && !id.isBlank()) {
                        converted.add(new NodeConnection("default", id));
                    }
                }
                return converted;
            } catch (Exception ignore) {
                log.warn("[video-sidecar] parseConnections failed: {}", e.getMessage());
                return Collections.emptyList();
            }
        }
    }

    private String serializeConnections(List<NodeConnection> conns) {
        if (conns == null || conns.isEmpty()) {
            return null;
        }
        List<NodeConnection> clean = conns.stream()
            .filter(c -> c != null && c.getNodeId() != null && !c.getNodeId().isBlank())
            .toList();
        if (clean.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(clean);
        } catch (Exception e) {
            log.warn("[video-sidecar] serializeConnections failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 旧的方法保留为空壳（万一有地方还在调），但已不再被使用。
     */
    @Deprecated
    private void createVideoCaptionSidecar(CanvasNode videoNode, List<String> frameUrls, String scriptText) {
        // 布局参数
        final int MAX_FRAMES = 20;
        final int COLS = 4;
        final int X_GAP = 20;
        final int Y_STEP = 240;
        final int SIDE_OFFSET = 360;
        final int NODE_W = 320;

        // 过滤掉上传失败的 null
        List<String> validUrls = frameUrls == null ? java.util.Collections.emptyList()
            : frameUrls.stream().filter(u -> u != null && !u.isBlank()).toList();
        int total = validUrls.size();
        if (total == 0) {
            log.warn("[video-sidecar] 没有有效的帧 URL，跳过 sidecar 创建");
            return;
        }

        // 采样：超过 MAX_FRAMES 则等距取 MAX_FRAMES 个索引
        int[] indices;
        if (total <= MAX_FRAMES) {
            indices = new int[total];
            for (int i = 0; i < total; i++) indices[i] = i;
        } else {
            java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>();
            for (int i = 0; i < MAX_FRAMES; i++) {
                int idx = Math.min((int) Math.round((double) i * (total - 1) / (MAX_FRAMES - 1)), total - 1);
                set.add(idx);
            }
            indices = set.stream().mapToInt(Integer::intValue).toArray();
        }

        Long userId = videoNode.getUserId();
        String canvasId = videoNode.getCanvasId();
        int baseX = (videoNode.getPositionX() == null ? 0 : videoNode.getPositionX()) + SIDE_OFFSET;
        int baseY = videoNode.getPositionY() == null ? 0 : videoNode.getPositionY();
        LocalDateTime now = LocalDateTime.now();

        int created = 0;
        // 逐帧建 image 节点
        for (int i = 0; i < indices.length; i++) {
            int frameIdx = indices[i];
            int col = i % COLS;
            int row = i / COLS;
            int second = frameIdx; // 1 fps 时 frameIdx == 秒数

            CanvasNode frame = new CanvasNode();
            frame.setUserId(userId);
            frame.setCanvasId(canvasId);
            frame.setType("image");
            frame.setTitle(String.format("帧 %02d (第%ds)", frameIdx + 1, second));
            frame.setResultUrl(validUrls.get(frameIdx));
            frame.setPositionX(baseX + col * (NODE_W + X_GAP));
            frame.setPositionY(baseY + row * Y_STEP);
            frame.setStatus("success");
            frame.setCreatedAt(now);
            frame.setUpdatedAt(now);
            nodeRepository.insert(frame);
            created++;
        }

        // text 节点放在 grid 下方
        int rows = (indices.length + COLS - 1) / COLS;
        CanvasNode textNode = new CanvasNode();
        textNode.setUserId(userId);
        textNode.setCanvasId(canvasId);
        textNode.setType("text");
        textNode.setTitle("口播文案");
        textNode.setContent(scriptText == null || scriptText.isBlank() ? "(空)" : scriptText);
        textNode.setPositionX(baseX);
        textNode.setPositionY(baseY + rows * Y_STEP + 40);
        textNode.setStatus("success");
        textNode.setCreatedAt(now);
        textNode.setUpdatedAt(now);
        nodeRepository.insert(textNode);
        created++;

        log.info("[video-sidecar] OK: videoNodeId={}, created={} nodes ({} frames + 1 text)",
            videoNode.getId(), created, indices.length);
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

    /**
     * 口播文案模板:
     *   【节奏】10s 视频节奏,每 1 秒一个镜头。
     *   【Shot01】运镜:固定 动作:xxx
     *   【Shot02】运镜:推近 动作:xxx
     *   ...
     */
    private String assembleScript(List<FrameMeta> frames, List<Map<String, String>> captions, Map<Integer, String> dubMap) {
    
        StringBuilder sb = new StringBuilder();
        sb.append("【节奏】").append(frames.size()).append("s 视频节奏,每 1 秒一个镜头。\n");
        int successCount = 0;
        for (int i = 0; i < frames.size(); i++) {
            Map<String, String> c = captions.get(i);
            String camera = c.getOrDefault("camera", "固定");
            String action = c.getOrDefault("action", "");
            sb.append("【Shot").append(String.format("%02d", i + 1)).append("】");
            if ("__FAILED__".equals(camera)) {
                // 失败帧:用 ⚠️ 标记 + 原因,不再混进"运镜:固定 动作:..."格式里
                sb.append("⚠️ 识别失败: ").append(action);
            } else if ("__SKIPPED__".equals(camera)) {
                // 跳过帧(相邻帧采样占位):用 ⏭️ 标记 + 原因
                sb.append("⏭️ ").append(action);
            } else {
                sb.append("运镜:").append(camera).append(" 动作:").append(action);
            }
            // 添加口播(ASR 提取的原文)
            String dubText = dubMap == null ? null : dubMap.get(i);
            if (dubText != null && !dubText.isBlank()) {
                sb.append(" 口播:\"").append(dubText).append("\"");
            }
            if (!"__FAILED__".equals(camera) && !"__SKIPPED__".equals(camera)) {
                successCount++;
            }
            sb.append("\n");
        }
        // 在头部加一行统计,让用户一眼看清 9 帧里成功几帧
        String header = String.format("【节奏】%d 帧(成功 %d / 失败 %d)\n",
            frames.size(), successCount, frames.size() - successCount);
        sb.insert(0, header);
        return sb.toString().trim();
    }
}
