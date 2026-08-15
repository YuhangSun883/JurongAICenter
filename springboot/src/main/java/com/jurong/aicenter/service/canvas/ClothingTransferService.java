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
        "图1是一张视频抽帧拼图,包含%FRAME_COUNT%帧画面(按行排列,每行3帧)。\n" +
        "【最关键】输出图片必须保持图1的原始比例与宽高比,绝对不能重新排列帧的位置。\n" +
        "请将图1作为一张完整的拼图进行整体换装处理,输出也必须是相同布局的%FRAME_COUNT%帧拼图:\n" +
        "1. 服装: 尽量将拼图中所有帧人物穿的衣服,替换为%CLOTHING_DESC%展示的衣服\n" +
        "2. 人脸: 如果参考图中包含可见的人脸(特别是模特的脸),将所有帧中人物的脸也替换为该模特的脸\n" +
        "严格要求(必须严格遵守):\n" +
        "- 输出图片的宽高比必须与图1完全一致(画布宽高比保持不变)\n" +
        "- 输出必须是与图1相同布局的拼图(包含所有帧,按相同排列顺序、相同位置)\n" +
        "- 每一帧人物的动作、姿势、表情必须完全不变\n" +
        "- 每一帧的背景、光照、相机角度、构图必须完全不变\n" +
        "- 人物的身材、肤色、年龄感必须完全不变\n" +
        "- 人物的长相(五官/脸型)默认保持图1原样;只有在参考图明确是模特脸部特写时才替换脸\n" +
        "- 可以自由改变:发色、发型、头部整体\n" +
        "- 服装款式应参考图2、图3、图4,但若参考图与原图差距过大可以保留原款\n" +
        "- 保持原图的拼图布局、帧排列顺序和每帧的位置完全不变\n" +
        "- 所有帧的换装风格必须统一,衣服款式保持一致\n" +
        "- 不要只输出一帧,必须输出完整的%FRAME_COUNT%帧拼图\n" +
        "- 绝对不要重新排列帧的位置、行列数、行高列宽\n" +
        "- 严禁编造新的人物特征(五官/脸型/年龄/性别等)";

    /** 视频节点换装 prompt:直接对视频帧整体换装 */
    private static final String VIDEO_CLOTHING_PROMPT_TEMPLATE =
        "将图1(视频)中人物的服装和人脸同时进行替换:\n" +
        "1. 服装: 尽量将图1中人物穿的衣服,替换为%CLOTHING_DESC%展示的衣服\n" +
        "2. 人脸: 如果参考图中包含可见的人脸(特别是模特的脸),将图1中人物的脸也替换为该模特的脸\n" +
        "严格要求(必须严格遵守):\n" +
        "- 人物的动作、姿势、表情必须完全不变\n" +
        "- 背景、光照、相机角度、构图必须完全不变\n" +
        "- 人物的身材、肤色、年龄感必须完全不变\n" +
        "- 人物的长相(五官/脸型)默认保持图1原样;只有在参考图明确是模特脸部特写时才替换脸\n" +
        "- 可以自由改变:发色、发型、头部整体\n" +
        "- 服装款式应参考图2、图3,但若参考图与原图差距过大可以保留原款\n" +
        "- 输出图片尺寸与图1一致\n" +
        "- 严禁编造新的人物特征(五官/脸型/年龄/性别等)";

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
        // 2026-08-14:衣服图数量从必须 3 张放宽到 1-3 张(用户实际测试常只传 1 张或 2 张)
//   - 1 张: 单图参考(模型按这张衣服换装)
//   - 2 张: 两个角度参考
//   - 3 张: 正面/背面/模特上身(原始设计)
//   - 0 张或 >3 张: 仍然报错
        if (clothingNodeIds == null || clothingNodeIds.isEmpty() || clothingNodeIds.size() > 3) {
            log.error("[clothing-transfer] 需要 1-3 张衣服图,实际 {} 张", clothingNodeIds == null ? 0 : clothingNodeIds.size());
            failTask(task, targetNode, "需要 1-3 张衣服参考图(最多 3 张)");
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
            // 2026-08-14:把输出格式统一为上游源图的格式(jpg/png/webp)
            //   抽帧输出 / 拼总图 / NewAPI 结果上传 都跟随 sourceMime
            String targetFormat = sourceMime;
            String targetExt = mimeToExt(targetFormat);

            // ② 加载衣服图(1-3 张)
            List<String> clothingDataUris = new ArrayList<>(3);
            for (String cid : clothingNodeIds) {
                byte[] bytes = downloadNodeImageBytes(cid, userId);
                if (bytes == null) {
                    throw new BusinessException(com.jurong.aicenter.exception.ErrorCode.INTERNAL_ERROR,
                        "衣服节点 " + cid + " 加载失败");
                }
                String mime = detectMime(bytes);
                String dataUri = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
                clothingDataUris.add(dataUri);
                // 2026-08-14:加详细日志,验证衣服图字节是否非空、MIME 是否正确
                log.info("[clothing-transfer] 衣服图 [{}] cid={} bytesLen={} mime={} dataUriPrefix={}",
                    clothingDataUris.size() - 1, cid, bytes.length, mime,
                    dataUri.length() > 80 ? dataUri.substring(0, 80) + "..." : dataUri);
            }
            log.info("[clothing-transfer] 衣服图加载完成: {} 张", clothingDataUris.size());

            // ③ 选择 prompt + 1 次 NewAPI 整体换装
            String prompt;
            String sourceDataUri = "data:" + sourceMime + ";base64," +
                Base64.getEncoder().encodeToString(sourceBytes);

            // 2026-08-11:根据 userInstruction 拼接约束(用户自然语言描述)
            // 2026-08-14:提升为强约束级别 - 用户输入的描述会被作为最优先指令执行
            String userInstructionBlock = "";
            if (userInstruction != null && !userInstruction.isBlank()) {
                userInstructionBlock = "\n【⭐用户最优先指令(必须严格按此执行)⭐】\n" +
                    userInstruction.trim() + "\n" +
                    "(上面默认要求让位于用户的具体描述,按用户说的做)\n";
            }

            if (isGrid) {
                prompt = GRID_CLOTHING_PROMPT_TEMPLATE
                    .replace("%FRAME_COUNT%", String.valueOf(frameCount))
                    .replace("%CLOTHING_DESC%", buildClothingDesc(clothingDataUris.size()));
                log.info("[clothing-transfer] 整体换装(总图): frameCount={}, 1次API调用, promptLen={}", frameCount, prompt.length());
            } else {
                prompt = VIDEO_CLOTHING_PROMPT_TEMPLATE
                    .replace("%CLOTHING_DESC%", buildClothingDesc(clothingDataUris.size()));
                log.info("[clothing-transfer] 整体换装(单图/视频): 1次API调用, promptLen={}", prompt.length());
            }

            // 2026-08-11:如果用户有自定义描述,拼到 prompt 末尾作为强约束
            if (!userInstructionBlock.isEmpty()) {
                prompt = prompt + userInstructionBlock;
                log.info("[clothing-transfer] 用户自定义描述已拼接到 prompt: {} 字符,最终 promptLen={}",
                    userInstruction.trim().length(), prompt.length());
            }

            // 2026-08-14:改用 NewAPI /v1/images/generations(多图图生图 JSON 接口)
//   原因:中转站对 /v1/images/edits 的 multipart 格式解析报错 "invalid multipart request"
//   接口手册 v3.0 §5.4:多图 input 格式 = "images": ["url1", "url2", ...]
//   ⚠️ 2026-08-14 用户最终方案:
//     - 图片先传 MinIO 留档(前端可以预览、清理任务时能找到文件)
//     - 但**不**传 MinIO URL 给 NewAPI(中转站访问不到 192.140.163.161:19000,Connection refused)
//     - 也不传 asset:// 给 NewAPI(用户要求不走素材库)
//     - 直接把 data URI(base64)放在 images 数组里,通过 HTTP body 传过去
//     - 这样 NewAPI 中转直接从 HTTP body 拿图,不依赖外网下载

            // 1) 把 4 张图的 data URI 上传到 MinIO(留档) + 同时构造 images 列表(直接传 NewAPI)
            String outputSize = detectOutputSize(sourceBytes);
            java.util.List<String> refImages = new java.util.ArrayList<>(4);
            log.info("[clothing-transfer] 源图准备: bytesLen={}, mime={}, targetFormat={}",
                sourceBytes.length, sourceMime, targetFormat);
            // 先上传源图到 MinIO(留档),但 images 数组里用 data URI(不走 MinIO URL)
            String sourceUrl = uploadDataUriToMinIO(sourceDataUri,
                "clothing-transfer/" + nodeId + "/source", targetFormat);
            if (sourceUrl == null) {
                failTask(task, targetNode, "源图上传 MinIO 失败,无法换装");
                taskRepository.updateById(task);
                nodeRepository.updateById(targetNode);
                return;
            }
            log.info("[clothing-transfer] sourceUrl(MinIO 留档)={}", sourceUrl);
            // 衣服图先上传 MinIO(留档) + 加到 refImages 用 data URI
            for (int i = 0; i < clothingDataUris.size(); i++) {
                String clothDataUri = clothingDataUris.get(i);
                String clothUrl = uploadDataUriToMinIO(clothDataUri,
                    "clothing-transfer/" + nodeId + "/cloth-" + i, targetFormat);
                if (clothUrl == null) {
                    failTask(task, targetNode, "服装图 " + i + " 上传 MinIO 失败,无法换装");
                    taskRepository.updateById(task);
                    nodeRepository.updateById(targetNode);
                    return;
                }
                refImages.add(clothDataUri);
                log.info("[clothing-transfer] clothUrl[{}] (MinIO 留档)={}", i, clothUrl);
            }
            // 源图作为数组第 0 个元素(即"图1")—— 用 data URI,不走 MinIO URL
            refImages.add(0, sourceDataUri);
            log.info("[clothing-transfer] refImages 数组 (全部 data URI, 中转站从 HTTP body 直接拿图): 共 {} 张", refImages.size());
            for (int i = 0; i < refImages.size(); i++) {
                String r = refImages.get(i);
                log.info("[clothing-transfer] refImages[{}] dataUriLen={} prefix={}",
                    i, r.length(), r.substring(0, Math.min(50, r.length())) + "...");
            }

            // 2) 调 imageToImage(多图图生图 JSON 接口)
            // 2026-08-14:整体换装策略
            //   关键:grid源图只调一次 API(不拆帧),把整张 grid 传给 NewAPI → 模型输出整张 grid
            //   用 nano-banana-pro 模型(手册 v3.0 §5.1 推荐质量最好的图生图模型,对多图 reference 支持好)
            String transferModel = "nano-banana-pro";
            String combinedUrlOrDataUri = newApiClient.imageToImage(
                transferModel,
                prompt,
                outputSize,
                refImages
            );

            if (combinedUrlOrDataUri == null || combinedUrlOrDataUri.isBlank()) {
                failTask(task, targetNode, "换装失败,NewAPI 返回空结果");
                taskRepository.updateById(task);
                nodeRepository.updateById(targetNode);
                return;
            }

            // 2026-08-14 修复:NewAPI b64_json 响应可能含 2-3 MB data URI,
//   直接写数据库 result_url 列(VARCHAR(500)) 会 Data truncation 报错。
//   所以先上传 MinIO 拿公网 URL,再写库。
//   2026-08-14 同时把 NewAPI 返回的 PNG bytes 重新编码为 sourceMime(jpg/webp),
//   保证输出格式与上游源图一致。
String combinedUrl = combinedUrlOrDataUri;
if (combinedUrlOrDataUri.startsWith("data:")) {
    String uploadedUrl = uploadDataUriToMinIO(
        combinedUrlOrDataUri, "clothing-transfer/" + nodeId + "/result", targetFormat);
    if (uploadedUrl == null) {
        failTask(task, targetNode, "换装结果上传 MinIO 失败,无法保存");
        taskRepository.updateById(task);
        nodeRepository.updateById(targetNode);
        return;
    }
    combinedUrl = uploadedUrl;
            log.info("[clothing-transfer] b64_json 结果已上传 MinIO: url={}", combinedUrl);
        }
        targetNode.setResultUrl(combinedUrl);
        targetNode.setStatus("success");
        targetNode.setUpdatedAt(LocalDateTime.now());
        nodeRepository.updateById(targetNode);
        log.info("[clothing-transfer] 结果覆盖到 activeNode: targetNodeId={}, url={}", targetNodeId, combinedUrl);

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
     * 2026-08-14:把 MIME 转扩展名(用于输出文件)。
     */
    private static String mimeToExt(String mime) {
        if (mime == null) return ".jpg";
        if (mime.equalsIgnoreCase("image/png")) return ".png";
        if (mime.equalsIgnoreCase("image/webp")) return ".webp";
        return ".jpg";
    }

    /**
     * 2026-08-14:把 MIME 转 ImageIO.write 格式字符串(jpg/png)。
     *   webp 在 JDK ImageIO 默认不支持,降级到 png。
     */
    private static String mimeToImageIoFormat(String mime) {
        if (mime == null) return "jpg";
        if (mime.equalsIgnoreCase("image/png")) return "png";
        if (mime.equalsIgnoreCase("image/webp")) return "png"; // JDK ImageIO 不支持 webp,降级 png
        return "jpg";
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
     * 2026-08-14 新增:把 data URI 上传到 MinIO,返回公网 URL。
     * 中转站 /v1/images/generations 多图 input 接收 URL 数组,需要先把 data URI 转 URL。
     * 失败返回 null(上层捕获并 failTask)。
     */
    private String uploadDataUriToMinIO(String dataUri, String keyPrefix, String targetFormat) {
        try {
            byte[] bytes = decodeResultDataUri(dataUri);
            if (bytes.length == 0) return null;
            String mime = (targetFormat != null && !targetFormat.isBlank()) ? targetFormat : "image/png";
            // 2026-08-14:如果要上传的格式与 data URI 实际格式不同(如 NewAPI 返回 PNG
            //   但 targetFormat=jpeg),需要重新编码。
            byte[] uploadBytes = bytes;
            if (targetFormat != null && !targetFormat.isBlank() &&
                !mime.startsWith("image/")) {
                // 防御性:targetFormat 应该是 mime,这里兜底
                mime = "image/png";
            }
            // 如果 data URI 是 PNG,但要求上传成 jpeg/webp,需要重新编码
            if (dataUri.startsWith("data:image/png") &&
                mime.equalsIgnoreCase("image/jpeg")) {
                uploadBytes = reencodeImage(bytes, "png", "jpg");
                log.debug("[clothing-transfer] PNG → JPG 重新编码: {} → {} bytes",
                    bytes.length, uploadBytes.length);
            } else if (dataUri.startsWith("data:image/png") &&
                       mime.equalsIgnoreCase("image/webp")) {
                // JDK ImageIO 不支持 webp,降级 jpg
                uploadBytes = reencodeImage(bytes, "png", "jpg");
                mime = "image/jpeg";
                log.debug("[clothing-transfer] PNG → JPG(webp降级)重新编码: {} → {} bytes",
                    bytes.length, uploadBytes.length);
            }
            String ext = mimeToExt(mime);
            String key = keyPrefix + "-" + System.currentTimeMillis() + ext;
            String url;
            try (InputStream in = new ByteArrayInputStream(uploadBytes)) {
                url = storageService.uploadObject(key, in, mime);
            }
            if (url == null || url.isBlank()) {
                log.warn("[clothing-transfer] 上传 MinIO 失败: key={}", key);
                return null;
            }
            return url;
        } catch (Exception e) {
            log.warn("[clothing-transfer] 上传 MinIO 异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 2026-08-14:用 ImageIO 重新编码图片(支持 png ↔ jpg)。
     * 用于把 NewAPI 返回的 PNG bytes 转成 sourceMime(jpg/webp)以保持输出格式一致。
     */
    private static byte[] reencodeImage(byte[] srcBytes, String srcFormat, String dstFormat) throws Exception {
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(
            new java.io.ByteArrayInputStream(srcBytes));
        if (img == null) throw new IllegalStateException("解码失败,format=" + srcFormat);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        // jpg 不支持透明通道,先转 RGB
        if (dstFormat.equalsIgnoreCase("jpg") && img.getType() != java.awt.image.BufferedImage.TYPE_INT_RGB) {
            java.awt.image.BufferedImage rgb = new java.awt.image.BufferedImage(
                img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = rgb.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
            g.dispose();
            img = rgb;
        }
        javax.imageio.ImageIO.write(img, dstFormat, baos);
        return baos.toByteArray();
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

        // 2026-08-14:恢复 .jpg 默认(此方法未被调用,保留历史兼容)
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
            // 2026-08-14 修复:grid总图(3×3 拼图)是正方形,源图尺寸 ~ 976×972,
            //   之前 detectOutputSize 走 1024x1024 → NewAPI 输出固定 1024×1024
            //   → 模型只能在该 size 内塞 grid,模型自己改了 layout (3×3 → 2×5)
            //   现在按源图的实际比例选 size,并尽量选最接近的 NewAPI 支持尺寸。
            // NewAPI 支持:1024x1024 / 1536x1024 / 1024x1536 (手册 v3.0 §5.2.3)
            if (ratio > 1.4) return "1536x1024";      // 横图
            if (ratio < 0.71) return "1024x1536";     // 竖图
            return "1024x1024";                       // 正方形(包含 grid)
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
            String filename = String.format("frame-%04d.jpg", i + 1);  // 历史兼容(分镜服务期待 .jpg 文件名)
            Path framePath = outDir.resolve(filename);
            // 2026-08-14:抽帧输出用 ImageIO 支持的格式(jpg/png)。webp 降级 png。
            // 这里默认用 jpg 因为下游分镜抽取按 .jpg 后缀过滤;若上游是 png/webp
            // 可在外面扩展为传 targetFormat 参数。
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