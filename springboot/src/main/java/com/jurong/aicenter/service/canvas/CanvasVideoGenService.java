package com.jurong.aicenter.service.canvas;


import com.fasterxml.jackson.databind.ObjectMapper;


import com.jurong.aicenter.client.AicomingAssetsClient;


import com.jurong.aicenter.dto.canvas.NodeConnection;


import com.jurong.aicenter.dto.generation.GenerateResponse;


import com.jurong.aicenter.entity.CanvasNode;


import com.jurong.aicenter.entity.CanvasTask;


import com.jurong.aicenter.entity.Job;


import com.jurong.aicenter.exception.BusinessException;


import com.jurong.aicenter.exception.ErrorCode;


import com.jurong.aicenter.repository.CanvasNodeRepository;


import com.jurong.aicenter.repository.CanvasTaskRepository;


import com.jurong.aicenter.repository.JobRepository;


import com.jurong.aicenter.service.StorageService;


import com.jurong.aicenter.service.VideoGenerationService;


import com.jurong.aicenter.event.VideoJobCompletedEvent;


import lombok.RequiredArgsConstructor;


import lombok.extern.slf4j.Slf4j;


import org.springframework.beans.factory.annotation.Qualifier;


import org.springframework.context.event.EventListener;


import org.springframework.scheduling.annotation.Async;


import org.springframework.stereotype.Component;


import java.io.ByteArrayInputStream;


import java.io.InputStream;


import java.net.URI;


import java.time.LocalDateTime;


import java.util.ArrayList;


import java.util.List;


import java.util.concurrent.Executor;


/**
 * 2026-08-10 新增:画布视频生成服务(图生视频)
 * <p>
 * <p>
 * <p>
 * <p>
 * <p>
 * 链路(模式同 ClothingTransferService):
 * <p>
 * <p>
 * 1. 从 videoGenNode 的 upstreamIds 找上游节点:
 * <p>
 * <p>
 * - 文本节点(脚本拆解文案) → 拿分镜 prompt
 * <p>
 * <p>
 * - 图片节点(换装总图) → 拿 image URL → 下载字节
 * <p>
 * <p>
 * 2. 合并 prompt:用户写在 videoGenNode.content 里的内容 + 上游脚本拆解文案
 * <p>
 * <p>
 * (用户优先:用户内容会整体前置,作为额外约束)
 * <p>
 * <p>
 * 3. 调 VideoGenerationService.submitImageToVideo(走 NewAPI 主路径,ComfyUI 兜底)
 * <p>
 * <p>
 * → 创建 Job,提交 NewAPI 视频任务,返回 jobId
 * <p>
 * <p>
 * 4. 异步轮询 Job 状态(每 5 秒查一次,最多 10 分钟)
 * <p>
 * <p>
 * 5. 完成后:
 * <p>
 * <p>
 * - 下载视频到 MinIO
 * <p>
 * <p>
 * - 建一个新的 CanvasNode(type=video)放在 videoGenNode 右边
 * <p>
 * <p>
 * - CanvasTask status=success
 * <p>
 * <p>
 * 6. 失败/超时:CanvasTask status=failed + errorMessage
 * <p>
 * <p>
 * <p>
 * <p>
 * <p>
 * 调用入口:CanvasServiceImpl.generateVideoFromCanvas()
 */


@Slf4j


@Component


@RequiredArgsConstructor


public class CanvasVideoGenService {


    private final VideoGenerationService videoGenerationService;


    private final StorageService storageService;


    private final CanvasTaskRepository taskRepository;


    private final CanvasNodeRepository nodeRepository;


    private final JobRepository jobRepository;


    // 2026-08-10 修复:CanvasVideoGenService 直接注入 NewApiClient 做兜底轮询。


    // pollRunningVideoJobs @Scheduled 在某些环境下不运行,导致 job 状态永远卡在 RUNNING,


    // pollAndWait 一直等不到 SUCCESS。兜底逻辑:job.status 还是 RUNNING 时,直接调


    // newApiClient.pollVideo(taskId) 查 NewAPI,看到 completed 就走完下载+MinIO 上传。


    private final com.jurong.aicenter.client.NewApiClient newApiClient;


    // 2026-08-12:CanvasVideoGenService 直接注入 AicomingAssetsClient 做素材库上传。


    // 之前用 newApiClient.uploadAsset() 搞错了 baseUrl (发到 NewAPI 中转站没有 /v1/assets 端点,404)。


    // 正确做法:资产 CRUD 必须直连 aicoming proxy 8080 (手册 §1),用 AicomingAssetsClient.uploadAssetByMultipart。


    private final AicomingAssetsClient assetsClient;


    @Qualifier("captionExecutor")


    private final Executor captionExecutor;


    /**
     * 轮询间隔(秒)
     */


    private static final int POLL_INTERVAL_SEC = 5;


    /**
     * 最大等待时间(秒);10 分钟
     */


    private static final int MAX_WAIT_SEC = 600;


    /**
     * 任务类型(写到 CanvasTask.type)
     */


    private static final String TASK_TYPE = "video-generation";


    /**
     * 异步入口。被 CanvasServiceImpl.generateVideoFromCanvas() 调用。
     *
     * @param task         任务实体(已 save,id 已生成)
     * @param videoGenNode 视频生成节点(type=video-generation)
     * @param duration     视频时长(秒),来自节点 settings 或请求参数
     * @param resolution   视频分辨率,如 "720P"
     * @param userId       用户 id
     */


    @Async("captionExecutor")


    public void executeVideoGenAsync(CanvasTask task, CanvasNode videoGenNode,


                                     int duration, String resolution, Long userId) {


        if (task == null || videoGenNode == null) {


            log.error("[canvas-video-gen] task/videoGenNode null - task={}, node={}", task, videoGenNode);


            return;


        }


        String taskId = task.getId();


        String nodeId = videoGenNode.getId();


        // 2026-08-13 加日志:打印 task 完整状态,排查"task 没保存/状态不对"等问题


        log.info("[canvas-video-gen] ENTRY: taskId={} nodeId={} userId={} taskStatus={} taskType={} duration={}s resolution={} upstreamIds={}",


                taskId, nodeId, userId, task.getStatus(), task.getType(), duration, resolution,


                videoGenNode.getUpstreamIds());


        log.info("[canvas-video-gen] ENTRY-DETAIL: task.prompt={}, task.resultUrl={}, node.content={}, node.settings={}",


                task.getPrompt(), task.getResultUrl(),


                videoGenNode.getContent() == null ? "null" : videoGenNode.getContent().substring(0, Math.min(200, videoGenNode.getContent().length())),


                videoGenNode.getSettings());


        // running


        task.setStatus("running");


        task.setStartedAt(LocalDateTime.now());


        int updateRows = taskRepository.updateById(task);


        log.info("[canvas-video-gen] 标 task.status=running, 影响行数={}", updateRows);


        log.info("=== [canvas-video-gen] START taskId={} nodeId={} duration={}s resolution={} ===",


                taskId, nodeId, duration, resolution);


        long start = System.currentTimeMillis();


        List<String> createdIds = new ArrayList<>();


        try {


            // 1. 收集上游节点(text + image)


            // 2026-08-11 修复:之前 for 循环每次覆盖 imageBytes,导致多张图只保留最后一张


            // 改为收集所有 image 节点的 URL,然后合并成一张大图(2×1 横向拼接)


            String textPrompt = "";


            // 2026-08-12 新增:从上游 video 节点转写拿口播文案(取代原 settings.userSpokenScript 输入框)


            String spokenScript = "";


            java.util.List<byte[]> upstreamImageBytesList = new java.util.ArrayList<>();
            // fallback: asset_url 被拒（虚拟人物/素材 line 不可用）时，传公网 MinIO URL 重提
            String firstUpstreamImagePublicUrl = null;


            List<NodeConnection> ups = parseConnections(videoGenNode.getUpstreamIds());


            log.info("[canvas-video-gen] 开始遍历上游节点: 共 {} 个连接, upstreamIds={}", ups.size(), videoGenNode.getUpstreamIds());


            for (NodeConnection c : ups) {


                log.info("[canvas-video-gen] 处理连接: nodeId={}, port={}", c.getNodeId(), c.getPort());


                CanvasNode upstream = nodeRepository.selectById(c.getNodeId());


                if (upstream == null) {


                    log.warn("[canvas-video-gen] upstream 节点不存在: {}", c.getNodeId());


                    continue;


                }


                log.info("[canvas-video-gen] 节点详情: id={}, type={}, title={}, resultUrl={}, content长度={}",


                        upstream.getId(), upstream.getType(), upstream.getTitle(),


                        upstream.getResultUrl() == null ? "null" : upstream.getResultUrl().substring(0, Math.min(100, upstream.getResultUrl().length())),


                        upstream.getContent() == null ? 0 : upstream.getContent().length());


                if (!upstream.getUserId().equals(userId)) {


                    log.warn("[canvas-video-gen] upstream 节点 {} 不属于用户 {}", c.getNodeId(), userId);


                    continue;


                }


                String port = c.getPort() == null ? "" : c.getPort();


                String type = upstream.getType() == null ? "" : upstream.getType().toLowerCase();


                log.info("[canvas-video-gen] 节点类型判断: type={}, port={}", type, port);


                // 按 type 优先;type 缺失/为空时用 port 兜底


                // 2026-08-13 关键修复:不能简单把 "default" 端口当作 text 节点!


                //   司马节点的 port=default + type=image 时,之前被错认为 text(因为 default 也算 text),


                //   导致图片字节根本没下载,imageBytesList.size=0。


                //   现在的规则:


                //     1. type 有明确值时,以 type 为准(image/video/text)


                //     2. type 为空/未知时,才用 port 兜底,但 port=default 时默认走 image(用户拖节点时最常见)


                boolean isImageNode;


                boolean isVideoNode;


                boolean isTextNode;


                if (!type.isEmpty()) {


                    // type 有值:按 type 判断


                    isImageNode = "image".equals(type);


                    isVideoNode = "video".equals(type);


                    isTextNode = "text".equals(type) || "script".equals(type) || "breakdown".equals(type)


                            || "prompt".equals(type) || "summary".equals(type) || "llm".equals(type);


                } else {


                    // type 为空:用 port 兜底


                    isImageNode = "image".equals(port) || "grid".equals(port) || "reference".equals(port)


                            || port.isEmpty() || "default".equals(port);


                    isVideoNode = "video".equals(port);


                    isTextNode = "text".equals(port) || "script".equals(port) || "breakdown".equals(port)


                            || "prompt".equals(port);


                }


                log.info("[canvas-video-gen] 节点路由判断: type={}, port={} → isImage={}, isVideo={}, isText={}",


                        type, port, isImageNode, isVideoNode, isTextNode);


                if (isTextNode) {


                    if (upstream.getContent() != null && !upstream.getContent().isBlank()) {


                        textPrompt = upstream.getContent();


                        log.info("[canvas-video-gen] 取脚本拆解文案 (type={}, port={}): {} 字符",


                                type, port, textPrompt.length());


                    } else {


                        log.warn("[canvas-video-gen] 文字节点 content 为空 (type={}, port={}, nodeId={})",


                                type, port, upstream.getId());


                    }


                } else if (isVideoNode) {


                    // 2026-08-12 新增:上游 video 节点 → 下载 + 转写拿口播文案


                    // 文档 §4 POST /v1/audio/transcriptions,模型 gpt-4o-transcribe,≤25MB


                    if (upstream.getResultUrl() != null && !upstream.getResultUrl().isBlank()) {


                        try {


                            log.info("[canvas-video-gen] 检测到上游 video 节点,开始转写拿口播: nodeId={}", upstream.getId());


                            byte[] videoBytes;


                            try (InputStream in = new URI(upstream.getResultUrl()).toURL().openStream()) {


                                videoBytes = in.readAllBytes();


                            }


                            if (videoBytes.length > 25 * 1024 * 1024) {


                                log.warn("[canvas-video-gen] 上游 video 超 25MB,跳过转写: size={}B", videoBytes.length);


                            } else {


                                String transcript = newApiClient.audioTranscription(


                                        videoBytes,


                                        "transcribe-" + upstream.getId() + ".mp4",


                                        "zh");


                                spokenScript = transcript;


                                log.info("[canvas-video-gen] 上游 video 转写成功: {} 字符", transcript.length());


                            }


                        } catch (Exception transcribeErr) {


                            log.warn("[canvas-video-gen] 上游 video 转写失败(非致命,继续): {}",


                                    transcribeErr.getMessage());


                        }


                    }


                } else if (isImageNode) {


                    log.info("[canvas-video-gen] ✓ 识别为图片节点: nodeId={}, type={}, port={}", upstream.getId(), type, port);


                    // 2026-08-12 修复:image 上游下载也要包 catch(以前没有,一旦 URL 连不上整个 task FAIL)


                    if (upstream.getResultUrl() != null && !upstream.getResultUrl().isBlank()) {


                        try {


                            // 2026-08-12 修复:清洗 URL 去除反引号/空格/引号


                            String cleanedImgUrl = sanitizeUrl(upstream.getResultUrl());


                            log.info("[canvas-video-gen] 开始下载图片: url={}", cleanedImgUrl.substring(0, Math.min(150, cleanedImgUrl.length())));


                            log.info("[canvas-video-gen] 清洗图片 URL: 原始={}, 清洗后={}",


                                    upstream.getResultUrl().substring(0, Math.min(100, upstream.getResultUrl().length())), cleanedImgUrl);


                            // 1 retry 应对 MinIO 临时 403


                            Exception lastErr = null;


                            for (int attempt = 1; attempt <= 2; attempt++) {


                                try (InputStream in = new URI(cleanedImgUrl).toURL().openStream()) {


                                    byte[] imgBytes = in.readAllBytes();


                                    upstreamImageBytesList.add(imgBytes);

                                    if (firstUpstreamImagePublicUrl == null) {
                                        firstUpstreamImagePublicUrl = cleanedImgUrl;
                                    }


                                    log.info("[canvas-video-gen] 取上游图片成功(尝试{}): {} bytes (累计 {} 张)",


                                            attempt, imgBytes.length, upstreamImageBytesList.size());


                                    lastErr = null;


                                    break;


                                } catch (Exception imgErr) {


                                    lastErr = imgErr;


                                    // 2026-08-13 强化日志:打印完整异常 class + message,排查是否 403/SSL/MinIO 签名失效


                                    log.warn("[canvas-video-gen] 上游图片下载失败(尝试 {}/2): nodeId={}, url={}, excClass={}, msg={}",


                                            attempt, upstream.getId(),


                                            cleanedImgUrl.substring(0, Math.min(150, cleanedImgUrl.length())),


                                            imgErr.getClass().getName(),


                                            imgErr.getMessage());


                                    if (attempt < 2) {


                                        try {
                                            Thread.sleep(1000);
                                        } catch (InterruptedException ie) {
                                            Thread.currentThread().interrupt();
                                        }


                                    }


                                }


                            }


                            if (lastErr != null) {


                                log.warn("[canvas-video-gen] 上游图片下载失败(非致命,跳过该图): nodeId={}, url={}, err={}",


                                        upstream.getId(), cleanedImgUrl, lastErr.getMessage());


                            }


                        } catch (Exception imgErr) {


                            // 2026-08-13 强化日志


                            log.warn("[canvas-video-gen] 提取图片 URL 异常: nodeId={}, excClass={}, msg={}",


                                    upstream.getId(), imgErr.getClass().getName(), imgErr.getMessage());


                        }


                    } else {


                        log.warn("[canvas-video-gen] 图片节点 resultUrl 为空,跳过: nodeId={}", upstream.getId());


                    }


                } else {


                    log.warn("[canvas-video-gen] 未识别的节点类型: nodeId={}, type={}, port={}", upstream.getId(), type, port);


                }


            }


            // 2026-08-11 修复:把所有上游图横向拼接成一张大图(2×1)


            //   submitImageToVideo 当前只支持单图,所以必须合并成 1 张


            //   NewAPI 上游 doubao-seedance 单图足够,拼接图也能用它做参考


            byte[] imageBytes;


            String imageFilename;


            String imageMime;

            // 2026-08-15 新增:多图 references[] 支持
            boolean multiImageMode = false;  // true = 多图走 references[] 路径
            List<byte[]> multiImageBytesRef = null;  // 多图的所有图片字节
            byte[] mergedImageBytes = null;  // 拼接 fallback 字节

            if (upstreamImageBytesList.isEmpty()) {


                imageBytes = null;


                imageFilename = "clothing-grid.jpg";


                imageMime = "image/jpeg";


            } else if (upstreamImageBytesList.size() == 1) {


                // 单图:直接用


                imageBytes = upstreamImageBytesList.get(0);


                imageMime = detectMime(imageBytes);


                imageFilename = "clothing-grid-" + System.currentTimeMillis()


                        + (imageMime.contains("png") ? ".png" : ".jpg");


                log.info("[canvas-video-gen] 单图上传: {} bytes, mime={}", imageBytes.length, imageMime);


            } else {

                // 多图:用 references[] 格式提交(聚融中转站接口手册 v3.0 §6.4)
                // 不再横向拼接 — 多图独立提交,每张图有 alias,模型能区分不同图的含义
                // 如果 references[] 提交失败,再回退到拼接方案

                // 先保留所有图的字节,用于 references[] 提交
                final List<byte[]> multiImageBytesList = new ArrayList<>(upstreamImageBytesList);

                // imageBytes 取第一张,用于后续素材库上传(单图上传逻辑复用)
                imageBytes = upstreamImageBytesList.get(0);
                imageMime = detectMime(imageBytes);
                imageFilename = "multi-image-" + System.currentTimeMillis()
                        + (imageMime.contains("png") ? ".png" : ".jpg");

                log.info("[canvas-video-gen] 多图(尝试 references[]): {} 张图,首张 {} bytes, mime={}",
                        upstreamImageBytesList.size(), imageBytes.length, imageMime);

                // 标记为多图模式,提交时走 references[] 路径
                multiImageMode = true;
                multiImageBytesRef = multiImageBytesList;

                // 同时保留拼接方案作为 fallback
                try {
                    java.awt.image.BufferedImage merged = mergeImagesHorizontally(upstreamImageBytesList);
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(merged, "jpg", baos);
                    mergedImageBytes = baos.toByteArray();
                    log.info("[canvas-video-gen] 拼接 fallback 已就绪: {}x{}, {} bytes",
                            merged.getWidth(), merged.getHeight(), mergedImageBytes.length);
                } catch (Exception e) {
                    log.warn("[canvas-video-gen] 拼接 fallback 失败: {}", e.getMessage());
                    mergedImageBytes = null;
                }

            }


            // 2. 合并 prompt(口播文案 > 用户提示词 > 上游分镜)


            String userPrompt = videoGenNode.getContent() == null ? "" : videoGenNode.getContent().trim();


            // 2026-08-11 新增:口播文案 + 用户提示词 从 settings JSON 读


            // settings 形如: {"duration":4, "resolution":"480P", "userSpokenScript":"...", "userPrompt":"..."}


            String userSpokenScript = "";


            String userExtraPrompt = "";


            if (videoGenNode.getSettings() != null && !videoGenNode.getSettings().isBlank()) {


                try {


                    com.fasterxml.jackson.databind.JsonNode settingsNode =


                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(videoGenNode.getSettings());


                    if (settingsNode.has("userSpokenScript")) {


                        userSpokenScript = settingsNode.get("userSpokenScript").asText("").trim();


                    }


                    if (settingsNode.has("userPrompt")) {


                        userExtraPrompt = settingsNode.get("userPrompt").asText("").trim();


                    }


                } catch (Exception e) {


                    log.warn("[canvas-video-gen] 解析 settings 失败(非致命): {}", e.getMessage());


                }


            }


            // 2026-08-13 关键修复:userPrompt(videoGenNode.content)是用户写在视频节点上的口播/分镜文案


            //   之前只用 settings.userPrompt,如果用户直接写视频节点的 content 就丢了


            //   现在把 videoGenNode.content 也作为 userExtraPrompt 合并(优先 settings.userPrompt,fallback 到 content)


            if (userExtraPrompt.isBlank() && !userPrompt.isBlank()) {


                userExtraPrompt = userPrompt;


                log.info("[canvas-video-gen] 从 videoGenNode.content 取用户提示词: {} 字符", userExtraPrompt.length());


            } else if (!userExtraPrompt.isBlank()) {


                log.info("[canvas-video-gen] userExtraPrompt 来自 settings.userPrompt: {} 字符", userExtraPrompt.length());


            }


            // 2026-08-13 关键修复:收敛"最终口播文案"到一个变量(之前直接用 finalSpokenScript 但未声明,编译失败)


            //   优先级: 上游 video 转写 > settings.userSpokenScript > videoGenNode.content 口播原文


            String finalSpokenScript = spokenScript; // 1) 上游 video 转写


            if (finalSpokenScript.isBlank() && !userSpokenScript.isBlank()) {


                finalSpokenScript = userSpokenScript; // 2) settings.userSpokenScript


            }


            if (finalSpokenScript.isBlank() && !userPrompt.isBlank()


                    && (userPrompt.startsWith("[口播原文") || userPrompt.startsWith("【口播") || userPrompt.contains("口播原文"))) {


                // 去掉 [口播原文-...] 前缀


                finalSpokenScript = userPrompt.replaceAll("^[\\[【]口播原文[^\\]】]*[\\]】]\\s*", "").trim();


                if (finalSpokenScript.isBlank()) {


                    finalSpokenScript = userPrompt;


                }


            }


            if (!spokenScript.isBlank()) {


                log.info("[canvas-video-gen] 口播文案来源: 上游 video 转写 ({} 字符)", spokenScript.length());


            } else if (!userSpokenScript.isBlank()) {


                log.info("[canvas-video-gen] 口播文案来源: settings.userSpokenScript fallback ({} 字符)", userSpokenScript.length());


            } else if (!finalSpokenScript.isBlank()) {


                log.info("[canvas-video-gen] 口播文案来源: videoGenNode.content 口播原文 ({} 字符)", finalSpokenScript.length());


            } else {


                log.info("[canvas-video-gen] 口播文案来源: 无(脚本拆解 + 用户额外要求 仍可生成视频)");


            }


            String mergedPrompt = mergePrompts(userExtraPrompt, textPrompt, finalSpokenScript);


            log.info("[canvas-video-gen] merged prompt: {} 字符 (userPrompt={}, spokenScript={}, script={})",


                    mergedPrompt.length(), userExtraPrompt.length(), finalSpokenScript.length(), textPrompt.length());


            // 3. 校验


            if (imageBytes == null || imageBytes.length == 0) {
                // 2026-08-14 修复:支持文生视频(无上游图)场景。
                //    上游只有文本节点(脚本拆解/口播原文)时,按文生视频走 /v1/videos JSON 接口,
                //    按聚融中转站接口手册 v3.0 §6.2,不需要 image_url。
                if (mergedPrompt.isBlank()) {
                    log.error("[canvas-video-gen] 文生视频场景但 prompt 为空: nodeId={}, mergedPrompt=''",
                        videoGenNode.getId());
                    failTask(task, videoGenNode, "文生视频场景但 prompt 为空,请填写用户提示词或连接上游文本节点");
                    taskRepository.updateById(task);
                    nodeRepository.updateById(videoNode(videoGenNode));
                    return;
                }
                log.info("[canvas-video-gen] 无上游图片 → 走文生视频路径(/v1/videos JSON, promptLen={})",
                    mergedPrompt.length());

                // 调 NewAPI 文生视频(JSON body,无 image_url)
                com.jurong.aicenter.client.NewApiClient.SubmitResult textResult =
                    newApiClient.submitVideoText(mergedPrompt, duration, resolution);
                String newapiTaskId = textResult.taskId();
                if (newapiTaskId == null || newapiTaskId.isBlank()) {
                    log.error("[canvas-video-gen] 文生视频 submit 返回 taskId 为空");
                    failTask(task, videoGenNode, "文生视频提交失败:taskId 为空");
                    taskRepository.updateById(task);
                    nodeRepository.updateById(videoNode(videoGenNode));
                    return;
                }

                // 手动建 Job(文生视频没有上游 image,跳过素材库上传)
                com.jurong.aicenter.entity.Job textJob = new com.jurong.aicenter.entity.Job();
                textJob.setUserId(userId);
                textJob.setTemplateId(com.jurong.aicenter.service.VideoGenerationService.TEMPLATE_TEXT_TO_VIDEO);
                textJob.setStatus("RUNNING");
                java.util.Map<String, Object> textInputs = new java.util.HashMap<>();
                textInputs.put("prompt", mergedPrompt);
                textInputs.put("duration", duration);
                textInputs.put("resolution", resolution);
                try {
                    textJob.setInputsSnapshot(new ObjectMapper().writeValueAsString(textInputs));
                } catch (Exception jsonErr) {
                    log.warn("[canvas-video-gen] 文生视频 inputs JSON 序列化失败: {}", jsonErr.getMessage());
                    textJob.setInputsSnapshot("{}");
                }
                textJob.setCreditsCost(0);
                textJob.setCreatedAt(LocalDateTime.now());
                textJob.setStartedAt(LocalDateTime.now());
                textJob.setComfyuiPromptId(newapiTaskId);
                jobRepository.insert(textJob);

                log.info("[canvas-video-gen] 文生视频 Job 已创建: jobId={}, newapiTaskId={}",
                    textJob.getId(), newapiTaskId);

                // 2026-08-14:把 jobId 暂存到 task.errorMessage(后端日志字段,不出现在前端响应),
                // 等 @Scheduled 处理完 Job 后通过 VideoJobCompletedEvent 反向定位 task 并同步 resultUrl。
                task.setErrorMessage("jobId=" + textJob.getId());

                // 更新 task 状态为 success(让前端立刻知道 task 已提交,后续等 NewAPI 轮询完成写视频节点)
                // 这里不立即标 SUCCESS,前端继续轮询 CanvasTask;真正的视频 URL 由 @Scheduled pollRunningVideoJobs 写回
                // 但 task.status 仍 running,前端会继续等
                taskRepository.updateById(task);
                nodeRepository.updateById(videoNode(videoGenNode));
                return;
            }


            if (mergedPrompt.isBlank()) {


                failTask(task, videoGenNode, "口播文案/用户提示词/上游分镜均为空,无法生成");


                taskRepository.updateById(task);


                nodeRepository.updateById(videoNode(videoGenNode));


                return;


            }


            // 4. 调 NewAPI 提交视频任务(走 VideoGenerationService,NewAPI 主路径)


            // 2026-08-12 修复:必须先上传到素材库拿到 asset_url,再用 asset_url 提交 NewAPI。


            //   之前用 newApiClient.uploadAsset() 搞错了 baseUrl (发到 NewAPI 中转站没有 /v1/assets 端点,404)。


            //   正确做法:资产 CRUD 必须直连 aicoming proxy 8080 (手册 §1),用 AicomingAssetsClient.uploadAssetByMultipart。


            //   上传后 status=processing,需要 poll 等 active 才能给 NewAPI 引用。


            //   不再走 multipart fallback —— 那条路径会卡人脸,直接强 fail。


            GenerateResponse resp;

            String assetUrl = null;
            String assetThumbnailUrl = null;
            String assetId = null;

            if (multiImageMode && multiImageBytesRef != null && multiImageBytesRef.size() >= 2) {
                // === 多图 references[] 路径(文档 §6.4) ===
                // 每张图单独上传素材库,用 references[] 格式提交
                log.info("[canvas-video-gen] 多图模式: {} 张图分别上传素材库", multiImageBytesRef.size());

                List<String> assetUrlsList = new ArrayList<>();
                List<String> aliasesList = new ArrayList<>();

                try {
                    for (int i = 0; i < multiImageBytesRef.size(); i++) {
                        byte[] imgBytes = multiImageBytesRef.get(i);
                        if (imgBytes == null || imgBytes.length == 0) continue;

                        String fname = "ref_" + (i + 1) + ".jpg";
                        String alias = "image_" + (i + 1);

                        log.info("[canvas-video-gen] 上传第 {}/{} 张图: alias={}, size={}B",
                            i + 1, multiImageBytesRef.size(), alias, imgBytes.length);

                        com.fasterxml.jackson.databind.JsonNode assetData = assetsClient.uploadAssetByMultipart(
                            imgBytes, fname, "image/jpeg", "canvas-multi-" + nodeId + "-" + i);

                        String aUrl = assetData.path("asset_url").asText("");
                        String aId = assetData.path("id").asText("");
                        if (aUrl.isBlank() || !aUrl.startsWith("asset://") || aId.isBlank()) {
                            log.warn("[canvas-video-gen] 第 {} 张图上传素材库失败,跳过", i + 1);
                            continue;
                        }

                        assetsClient.pollUntilActive(aId, 60, 2);
                        assetUrlsList.add(aUrl);
                        aliasesList.add(alias);
                        log.info("[canvas-video-gen] 第 {} 张图就绪: assetUrl={}", i + 1, aUrl);
                    }

                    if (assetUrlsList.isEmpty()) {
                        throw new BusinessException(ErrorCode.ASSET_UPLOAD_FAILED, "所有图片上传素材库均失败");
                    }

                    log.info("[canvas-video-gen] 多图素材就绪: {} 张,用 references[] 提交", assetUrlsList.size());
                    resp = videoGenerationService.submitMultiImageToVideoByAssetReferences(
                        userId, assetUrlsList, aliasesList, mergedPrompt, duration, resolution);

                } catch (BusinessException refErr) {
                    // references[] 失败,尝试 fallback 到拼接方案
                    log.warn("[canvas-video-gen] references[] 提交失败: {}, 尝试拼接 fallback", refErr.getMessage());

                    if (mergedImageBytes != null && mergedImageBytes.length > 0) {
                        try {
                            // 上传拼接图到素材库
                            com.fasterxml.jackson.databind.JsonNode mergedAsset = assetsClient.uploadAssetByMultipart(
                                mergedImageBytes, "merged-fallback-" + System.currentTimeMillis() + ".jpg",
                                "image/jpeg", "canvas-merged-" + nodeId);

                            String mergedUrl = mergedAsset.path("asset_url").asText("");
                            String mergedId = mergedAsset.path("id").asText("");
                            if (!mergedUrl.isBlank() && mergedUrl.startsWith("asset://") && !mergedId.isBlank()) {
                                assetsClient.pollUntilActive(mergedId, 60, 2);
                                log.info("[canvas-video-gen] 拼接 fallback 素材就绪: assetUrl={}", mergedUrl);

                                resp = videoGenerationService.submitImageToVideoByAssetUrl(
                                    userId, mergedUrl, mergedPrompt, duration, resolution);
                            } else {
                                throw refErr;
                            }
                        } catch (Exception fallbackErr) {
                            log.error("[canvas-video-gen] 拼接 fallback 也失败: {}", fallbackErr.getMessage());
                            throw refErr;
                        }
                    } else {
                        throw refErr;
                    }
                }

            } else {
                // === 单图路径(现有逻辑) ===
                try {
                    log.info("[canvas-video-gen] uploading image to aicoming-proxy asset library: filename={}, size={}B",
                            imageFilename, imageBytes.length);

                    com.fasterxml.jackson.databind.JsonNode assetData = assetsClient.uploadAssetByMultipart(
                            imageBytes, imageFilename, imageMime, "canvas-vid-" + nodeId);

                    assetUrl = assetData.path("asset_url").asText("");
                    assetThumbnailUrl = assetData.path("thumbnail_url").asText("");
                    assetId = assetData.path("id").asText("");

                    if (assetUrl.isBlank() || !assetUrl.startsWith("asset://") || assetId.isBlank()) {
                        throw new BusinessException(ErrorCode.ASSET_UPLOAD_FAILED,
                                "asset 响应缺 id/asset_url: " + assetData);
                    }

                    log.info("[canvas-video-gen] → poll asset 到 active: id={}", assetId);
                    assetsClient.pollUntilActive(assetId, 90, 3);
                    log.info("[canvas-video-gen] ← asset 已就绪: assetUrl={}", assetUrl);

                } catch (Exception assetErr) {
                    log.error("[canvas-video-gen] 素材库上传/就绪失败(强 fail,不走 multipart 避免卡人脸): {}",
                            assetErr.getMessage());
                    failTask(task, videoGenNode,
                            "素材库上传失败,无法生成视频: " + assetErr.getMessage());
                    taskRepository.updateById(task);
                    nodeRepository.updateById(videoGenNode);
                    return;
                }

                if (assetUrl != null && assetUrl.startsWith("asset://")) {
                    log.info("[canvas-video-gen] using asset_url path (required for face detection bypass)");
                    try {
                        resp = videoGenerationService.submitImageToVideoByAssetUrl(
                                userId, assetUrl, mergedPrompt, duration, resolution);
                    } catch (BusinessException assetErr) {
                        log.error("[canvas-video-gen] submitImageToVideoByAssetUrl 失败: {}", assetErr.getMessage());
                        throw assetErr;
                    }
                } else {
                    failTask(task, videoGenNode, "assetUrl 校验失败");
                    taskRepository.updateById(task);
                    nodeRepository.updateById(videoGenNode);
                    return;
                }
            }


            Long jobIdLong = resp.getJobId();


            if (jobIdLong == null) {


                failTask(task, videoGenNode, "NewAPI 视频提交返回 jobId 为空");


                taskRepository.updateById(task);


                nodeRepository.updateById(videoGenNode);


                return;


            }


            String jobId = String.valueOf(jobIdLong);


            log.info("[canvas-video-gen] 视频任务已提交: jobId={}", jobId);


            // 把 jobId 存进 task.prompt(供后续可能的查询/恢复)


            task.setPrompt("jobId=" + jobId);


            taskRepository.updateById(task);


            // 5. 轮询 Job 状态(每 5s 查一次,最多 10 分钟)


            // 2026-08-10 兜底:如果 job.status 还是 RUNNING(说明 @Scheduled 没把状态翻成 SUCCESS),


            // 直接调 newApiClient.pollVideo(taskId) 查 NewAPI,看到 completed 就下载并标 SUCCESS。


            // 这避免了 NewAPI 视频生成成功但我们拿不到的"孤儿"状态。


            Job job = null;


            String finalVideoUrl = null;


            // 2026-08-10 兜底:从 resp 或 job 拿 NewAPI 真实 taskId (存在 job.comfyuiPromptId 字段)。


            // 这里不能再声明 String taskId,因为方法签名里已经有同名变量 (L92)。


            // 重命名为 newApiTaskId 避免遮蔽。


            String newApiTaskId = null;


            if (resp != null && resp.getComfyuiPromptId() != null && !resp.getComfyuiPromptId().isBlank()) {


                newApiTaskId = resp.getComfyuiPromptId();


            } else {


                // 第一次 selectById 拿 taskId


                Job initialJob = jobRepository.selectById(jobIdLong);


                if (initialJob != null) {


                    newApiTaskId = initialJob.getComfyuiPromptId(); // 借用字段存 NewAPI task_id(@Deprecated 但仍可访问)


                }


            }


            int elapsed = 0;


            for (; elapsed < MAX_WAIT_SEC; elapsed += POLL_INTERVAL_SEC) {


                Thread.sleep(POLL_INTERVAL_SEC * 1000L);


                job = jobRepository.selectById(jobIdLong);


                if (job == null) {


                    log.warn("[canvas-video-gen] jobId={} 查不到,可能已被清理", jobId);


                    continue;


                }


                String status = job.getStatus();


                log.info("[canvas-video-gen] 轮询 jobId={} status={} resultUrls={} (elapsed={}s)",


                        jobId, status, job.getResultUrls(), elapsed);


                // 2026-08-12 根治:既要检查 SUCCESS(兑底轮询自己从 NewAPI 拉到完成时设的),也要检查 COMPLETED(@Scheduled markCompleted 设的)


                //   之前只检查 SUCCESS 导致 @Scheduled 完成后,兑底轮询检测不到, videoGenNode.resultUrl 永远写不进


                if ("SUCCESS".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status)) {


                    // resultUrls 是 JSON 数组字符串,如 ["http://..."]


                    finalVideoUrl = parseFirstResultUrl(job.getResultUrls());


                    log.info("[canvas-video-gen] 兜底检测到 SUCCESS/COMPLETED, parseFirstResultUrl={} (raw={})",


                            finalVideoUrl, job.getResultUrls());


                    break;


                }


                if ("FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status)) {
                    // 2026-08-14 16:00 关键修复:不直接 break!
                    //   之前这里直接 break 跳出循环 → 后续的"救场路径"根本没机会执行 → 前端永远拿不到视频
                    //   现在:job 已 FAILED 时进入循环底部的"if (finalVideoUrl == null)"分支,
                    //   在那里主动查 NewAPI:
                    //     - 已 completed+URL → 救场成功
                    //     - 仍 in_progress/queued → continue 继续轮询
                    //     - failed/cancelled/task_not_exist → 真正返回
                    log.warn("[canvas-video-gen] 兜底轮询: job 标 {} (可能是误判),不直接退出,继续走救场路径 (elapsed={}s)", status, elapsed);
                    // 不要 break,让循环底部"if (finalVideoUrl == null)"处理
                }


                // 2026-08-10 兜底轮询:job.status 还是 RUNNING,直接查 NewAPI。
                // 这条路径保证即使 @Scheduled 调度器没运行,我们也能拿到 NewAPI 完成的视频。
                // 2026-08-13 修复:前 60 秒只查 job.status(让 @Scheduled 处理,避免和 @Scheduled 重复调
                //   pollVideo 浪费 NewAPI 配额)。超过 60 秒 job 还是 RUNNING(说明 @Scheduled 可能有
                //   问题),才开始调 NewAPI 兜底。视频生成通常 2-5 分钟,60 秒阈值足够 @Scheduled 处理。
                if (newApiTaskId != null && "RUNNING".equalsIgnoreCase(status) && elapsed >= 60) {


                    try {


                        com.fasterxml.jackson.databind.JsonNode newApiResp = newApiClient.pollVideo(newApiTaskId);


                        if (newApiResp != null) {


                            String newApiStatus = newApiResp.path("status").asText("").toLowerCase();


                            log.info("[canvas-video-gen] 兜底查 NewAPI: taskId={} status={} raw={}",


                                    newApiTaskId, newApiStatus,


                                    newApiResp.toString().length() < 500 ? newApiResp.toString() : newApiResp.toString().substring(0, 500) + "...");


                            if ("completed".equals(newApiStatus) || "succeeded".equals(newApiStatus) || "success".equals(newApiStatus)) {


                                String videoUrl = newApiClient.extractVideoUrl(newApiResp);


                                log.info("[canvas-video-gen] 兜底 status=completed, extractVideoUrl={}", videoUrl);


                                if (videoUrl != null && !videoUrl.isBlank()) {


                                    log.info("[canvas-video-gen] 兜底发现视频URL(直传 NewAPI CDN): {}", videoUrl);


                                    finalVideoUrl = videoUrl;


                                    // 顺便把 job 状态翻成 SUCCESS,免得下次还是 RUNNING 重复拉取


                                    job.setStatus("SUCCESS");


                                    job.setResultUrls("[\"" + videoUrl + "\"]");


                                    job.setCompletedAt(java.time.LocalDateTime.now());


                                    jobRepository.updateById(job);


                                    log.info("[canvas-video-gen] 兜底写 job.status=SUCCESS + resultUrls, 跳出轮询");


                                    break;


                                }


                            }


                            if ("failed".equals(newApiStatus) || "error".equals(newApiStatus) || "cancelled".equals(newApiStatus)) {
                                // 2026-08-13 14:55 修复:不要立即 failTask,跳出兜底循环交给 @Scheduled MinIO 兜底处理
                                //   @Scheduled 的 status=failed 路径现在会先查 MinIO 兜底找视频(参考 VideoImpl L1208)
                                //   Canvas 这里直接 break 不重复 failTask
                                log.warn("[canvas-video-gen] 兑底查 NewAPI 标记 {} (可能是误判),跳出兑底交给 @Scheduled MinIO 兑底处理", newApiStatus);
                                break;
                            }


                        }


                    } catch (Exception ex) {


                        // 2026-08-12 根治:NewAPI 400 task not found(元数据被清理)不算"轮询失败"


                        //   @Scheduled pollRunningVideoJobs 已改成累计 5 次才 FAILED(参考 processOneVideoJob 改动)


                        //   兜底轮询遇此场景仅 continue,不主动 failTask,不打 warn 噪声


                        if (ex instanceof BusinessException


                                && ((BusinessException) ex).getCode() == ErrorCode.NEWAPI_TASK_NOT_FOUND.getCode()) {


                            log.info("[canvas-video-gen] 兜底轮询遇 NewAPI 任务元数据被清理 (taskId={}),继续等 @Scheduled 处理",


                                    newApiTaskId);


                        } else {


                            log.warn("[canvas-video-gen] 兜底 NewAPI 轮询失败(下次继续): err={}", ex.getMessage());


                        }


                    }


                }


                // PENDING / RUNNING / SUBMITTED → 继续等


                // 2026-08-14 16:00 救场路径:job 已 FAILED/ERROR 时主动查 NewAPI(用 newApiTaskId),
                //   不依赖 job.status 是否被 @Scheduled 误标。NewAPI 完成就救,仍在跑就 continue。
                if (newApiTaskId != null && finalVideoUrl == null
                    && (job != null && ("FAILED".equalsIgnoreCase(job.getStatus()) || "ERROR".equalsIgnoreCase(job.getStatus())))) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode rescueResp = newApiClient.pollVideo(newApiTaskId);
                        if (rescueResp != null) {
                            String rescueStatus = rescueResp.path("status").asText("").toLowerCase();
                            String rescueUrl = newApiClient.extractVideoUrl(rescueResp);
                            if (("completed".equals(rescueStatus) || "succeeded".equals(rescueStatus) || "success".equals(rescueStatus))
                                && rescueUrl != null && !rescueUrl.isBlank()) {
                                log.warn("[canvas-video-gen] 救场成功: job 状态{}但 NewAPI 已完成, URL={}", job.getStatus(), rescueUrl);
                                finalVideoUrl = rescueUrl;
                                // 2026-08-14 关键修复:救场成功立即跳出 for 循环,否则会持续空轮询 5 秒/次
                                break;
                            } else if ("in_progress".equals(rescueStatus) || "queued".equals(rescueStatus)) {
                                log.warn("[canvas-video-gen] job 标 FAILED 但 NewAPI 状态={},任务仍在跑,继续轮询等下次 (elapsed={}s)",
                                    rescueStatus, elapsed);
                                // continue 让 for 循环继续
                            } else {
                                log.warn("[canvas-video-gen] job 标 FAILED, NewAPI 状态={} 也算失败,退出兑底轮询", rescueStatus);
                                return;
                            }
                        }
                    } catch (Exception rescueEx) {
                        String errMsg = rescueEx.getMessage() == null ? "" : rescueEx.getMessage();
                        if (errMsg.contains("task_not_exist") || errMsg.contains("task not found")) {
                            log.warn("[canvas-video-gen] 救场确认失败: NewAPI 任务不存在 (TTL expired 或清理): {}", errMsg);
                            return;
                        }
                        log.warn("[canvas-video-gen] 救场异常(非致命,继续轮询): {}", errMsg);
                    }
                }


            }


            // 循环结束后:finalVideoUrl 仍为 null → 真正的超时/RUNNING → failTask
            if (finalVideoUrl == null) {
                log.error("[canvas-video-gen] ✗ 兜底轮询超时,未拿到 videoUrl (elapsed={}s, job={})",
                        elapsed, job != null ? job.getStatus() + "/" + job.getResultUrls() : "null");
                failTask(task, videoGenNode, "NewAPI 视频生成超时(>10 分钟)");
                taskRepository.updateById(task);
                nodeRepository.updateById(videoGenNode);
                return;
            }


            // 6. 2026-08-11 方案B:MinIO 备份 + 前端用 NewAPI CDN url。


            //    - 前端用的 url(frontUrl):直接用 NewAPI CDN url,前端 <video> 立刻可播。


            //    - MinIO 备份:异步下载+上传,不阻塞主流程(失败仅记日志,不影响前端)。


            final String frontUrl = finalVideoUrl;


            log.info("[canvas-video-gen] ✓ 拿到 videoUrl,准备写前端: frontUrl={}", frontUrl);


            // MinIO 异步备份(后台慢慢传,前端不卡)


            final String nodeIdForBackup = nodeId;


            java.util.concurrent.CompletableFuture.runAsync(() -> {


                try {


                    byte[] videoBytes;


                    try (java.io.InputStream in = new java.net.URI(frontUrl).toURL().openStream()) {


                        videoBytes = in.readAllBytes();


                    }


                    String key = "canvas-video-gen/" + nodeIdForBackup + "/result-" + System.currentTimeMillis() + ".mp4";


                    String minioUrl;


                    try (java.io.InputStream in2 = new java.io.ByteArrayInputStream(videoBytes)) {


                        minioUrl = storageService.uploadObject(key, in2, "video/mp4");


                    }


                    log.info("[canvas-video-gen] MinIO 备份完成: nodeId={}, url={}, size={}B",


                            nodeIdForBackup, minioUrl, videoBytes.length);


                } catch (Exception backupErr) {


                    log.warn("[canvas-video-gen] MinIO 备份失败(非致命,前端仍可用 NewAPI URL): {}", backupErr.getMessage());


                }


            });


            // 7. 2026-08-12 修复:视频放到用户已有的 videoGenNode 里,不新建节点。


            //   前端会从 CanvasTask.resultUrl 拿到 frontUrl,写到 activeNode.resultUrl(本来就是用户在画布上点的节点)。


            //   "不刷新也能看见":前端已经 2s 轮询 task.status,task.success 后自动 fetchCanvasHistory → 重新渲染 videoGenNode 节点。


            videoGenNode.setResultUrl(frontUrl);


            videoGenNode.setStatus("success");


            videoGenNode.setUpdatedAt(java.time.LocalDateTime.now());


            int nodeUpdateRows = nodeRepository.updateById(videoGenNode);


            log.info("[canvas-video-gen] 写入 videoGenNode.resultUrl: nodeId={}, resultUrl={}, updateRows={}",


                    videoGenNode.getId(), frontUrl, nodeUpdateRows);


            // 2026-08-13 防御:写入后再读一遍,确认真的写进去了(排查 ORM 缓存/脏写)


            CanvasNode reloadedNode = nodeRepository.selectById(videoGenNode.getId());


            log.info("[canvas-video-gen] 写后校验 videoGenNode.resultUrl: reloaded={}, status={}",


                    reloadedNode == null ? "NULL!" : reloadedNode.getResultUrl(),


                    reloadedNode == null ? "NULL!" : reloadedNode.getStatus());

            // 2026-08-15 修复:不再把 videoGenNode.getId() 加进 createdIds。
            // videoGenNode 是用户已有节点(非新创建),前端已在 success 回调里更新了它的 resultUrl。
            // 如果放进 createdNodeIds,前端会重新拉取并追加到画布 → 出现两个相同 ID 的节点(右侧孤立)。
            // createdIds 保持空列表,序列化后为 "[]",前端看到空列表不会追加节点。

            log.info("[canvas-video-gen] 视频结果写入 videoGenNode: id={}, resultUrl={}", videoGenNode.getId(), frontUrl);


            // 8. CanvasTask SUCCESS(前端用 frontUrl = NewAPI CDN url)


            task.setStatus("success");


            task.setResultUrl(frontUrl);


            task.setDurationMs((int) (System.currentTimeMillis() - start));


            task.setCompletedAt(java.time.LocalDateTime.now());


            try {


                ObjectMapper mapper = new ObjectMapper();


                task.setCreatedNodeIds(mapper.writeValueAsString(createdIds));


            } catch (Exception jsonErr) {


                log.warn("[canvas-video-gen] createdNodeIds JSON 失败(非致命): {}", jsonErr.getMessage());


            }


            log.info("[canvas-video-gen] SUCCESS: taskId={}, videoGenNodeId={}, durationMs={}",


                    taskId, videoGenNode.getId(), task.getDurationMs());


            log.info("=== [canvas-video-gen] DONE taskId={} status=SUCCESS ===", taskId);


        } catch (BusinessException e) {


            log.error("[canvas-video-gen] BIZ_FAIL: taskId={}, err={}", taskId, e.getMessage());


            failTask(task, videoGenNode, e.getMessage());


        } catch (Exception e) {


            log.error("[canvas-video-gen] FAIL: taskId={}, err={}", taskId, e.getMessage(), e);


            failTask(task, videoGenNode, e.getMessage() == null ? "未知错误" : e.getMessage());


        } finally {


            // 2026-08-12 修复:即使主流程异常中断,也要保证 videoGenNode.resultUrl 跟 task.resultUrl 同步


            //   避免刷新页面后视频消失。task 成功时,node 必须也成功。


            //   2026-08-12 二次修复:同步时必须把 videoGenNode.status 改成 success,否则前端按 status 过滤,


            //   即使有 URL 也不显示。这是 SQL 注入测试时发现的真实问题。


            if ("success".equalsIgnoreCase(task.getStatus())


                    && task.getResultUrl() != null


                    && !task.getResultUrl().isBlank()) {


                boolean nodeUrlBlank = (videoGenNode.getResultUrl() == null || videoGenNode.getResultUrl().isBlank());


                boolean urlMismatch = !nodeUrlBlank && !videoGenNode.getResultUrl().equals(task.getResultUrl());


                if (nodeUrlBlank || urlMismatch) {


                    videoGenNode.setResultUrl(task.getResultUrl());


                    videoGenNode.setStatus("success");


                    videoGenNode.setUpdatedAt(java.time.LocalDateTime.now());


                    log.warn("[canvas-video-gen] 兜底:task 成功 node 异常,强制同步 status=success: taskId={}, nodeId={}, url={}, reason={}",


                            taskId, videoGenNode.getId(), task.getResultUrl(),


                            nodeUrlBlank ? "nodeUrl blank" : "url mismatch");


                }


            }


            taskRepository.updateById(task);


            nodeRepository.updateById(videoGenNode);


        }


    }


    // ============== 私有辅助方法 ==============


    /**
     * 2026-08-12 调整合并顺序(跟用户图一致):
     * <p>
     * <p>
     * 1) 脚本拆解参考(节奏 + 分镜 + 替换清单):结构化镜头描述,作为画面生成的主要依据
     * <p>
     * <p>
     * 2) 用户额外要求(来自 settings.userPrompt):画面/风格/动作增强
     * <p>
     * <p>
     * 3) 口播原文(从上游 video 转写 或 settings.userSpokenScript fallback):旁白直接进入视频
     * <p>
     * <p>
     * <p>
     * <p>
     * <p>
     * 优先级:用户额外要求 > 脚本拆解(冲突以用户要求为准),口播原文作为上下文补充。
     */


    private static String mergePrompts(String userPrompt, String scriptBreakdown, String spokenScript) {


        StringBuilder sb = new StringBuilder();


        if (!scriptBreakdown.isBlank()) {


            sb.append("【脚本拆解参考(节奏+分镜+替换清单)】\n");


            sb.append(scriptBreakdown);


            sb.append("\n\n");


        }


        if (!userPrompt.isBlank()) {


            sb.append("【用户额外要求(画面/风格/动作)】\n");


            sb.append(userPrompt);


            // 2026-08-14 修复:NewAPI doubao-seedance 对中文颜色词理解差,
            //   自动追加英文颜色关键词帮助模型准确出图(蓝色的小猫 → 加 "(blue)" 后缀)
            String colorHint = extractColorHint(userPrompt);
            if (colorHint != null && !colorHint.isBlank()) {
                sb.append(" (").append(colorHint).append(")");
                log.info("[canvas-video-gen] 自动追加颜色提示词英文: ({})", colorHint);
            }


            sb.append("\n\n");


        }


        if (!spokenScript.isBlank()) {


            sb.append("【口播原文】\n");


            sb.append(spokenScript);


            sb.append("\n\n");


        }


        sb.append("【输出要求】\n");


        sb.append("- 保持原视频时长和分镜结构\n");


        sb.append("- 每个分镜的衣服与参考图(换装总图)一致\n");


        sb.append("- 动作、表情、镜头与分镜描述一致\n");


        sb.append("- 如有口播原文,确保画面节奏配合口播的语义断句\n");


        sb.append("- 如果用户额外要求与分镜描述冲突,以用户要求为准");


        return sb.toString();


    }


    /**
     * 2026-08-14 新增:从用户提示词中提取颜色关键词,自动翻译成英文追加。
     * NewAPI doubao-seedance 对中文颜色词理解差,翻译后颜色命中率显著提升。
     *
     * <p>支持的常用颜色:
     * <ul>
     *   <li>红/红色 → red</li>
     *   <li>蓝/蓝色 → blue</li>
     *   <li>绿/绿色 → green</li>
     *   <li>黄/黄色 → yellow</li>
     *   <li>白/白色 → white</li>
     *   <li>黑/黑色 → black</li>
     *   <li>紫/紫色 → purple</li>
     *   <li>粉/粉色 → pink</li>
     *   <li>橙/橙色 → orange</li>
     *   <li>棕/棕色 → brown</li>
     *   <li>灰/灰色 → gray</li>
     *   <li>金/金色 → gold</li>
     *   <li>银/银色 → silver</li>
     * </ul>
     *
     * <p>返回格式如 "blue",多个颜色时逗号分隔 "red, white"。
     *
     * @param userPrompt 用户提示词(如"蓝色的小猫")
     * @return 英文颜色关键词,无颜色返回 null
     */
    private static String extractColorHint(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) return null;
        // 颜色映射(中文 → 英文)
        java.util.Map<String, String> colorMap = new java.util.LinkedHashMap<>();
        colorMap.put("红色", "red");
        colorMap.put("红的", "red");
        colorMap.put("蓝", "blue");
        colorMap.put("蓝色", "blue");
        colorMap.put("绿的", "green");
        colorMap.put("绿色", "green");
        colorMap.put("黄色", "yellow");
        colorMap.put("黄的", "yellow");
        colorMap.put("白色", "white");
        colorMap.put("白的", "white");
        colorMap.put("黑色", "black");
        colorMap.put("黑的", "black");
        colorMap.put("紫色", "purple");
        colorMap.put("紫的", "purple");
        colorMap.put("粉色", "pink");
        colorMap.put("粉的", "pink");
        colorMap.put("橙色", "orange");
        colorMap.put("橙的", "orange");
        colorMap.put("棕色", "brown");
        colorMap.put("棕的", "brown");
        colorMap.put("灰色", "gray");
        colorMap.put("灰的", "gray");
        colorMap.put("金色", "gold");
        colorMap.put("金的", "gold");
        colorMap.put("银色", "silver");
        colorMap.put("银的", "silver");

        java.util.List<String> hits = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> e : colorMap.entrySet()) {
            if (userPrompt.contains(e.getKey())) {
                if (!hits.contains(e.getValue())) {
                    hits.add(e.getValue());
                }
            }
        }
        if (hits.isEmpty()) return null;
        return String.join(", ", hits);
    }


    /**
     * 检测图片 MIME
     */


    private static String detectMime(byte[] bytes) {


        if (bytes.length > 3 &&


                (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8) return "image/jpeg";


        if (bytes.length > 8 &&


                (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return "image/png";


        if (bytes.length > 12 &&


                bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') return "image/webp";


        return "image/jpeg";


    }


    /**
     * 解析 Job.resultUrls JSON 数组字符串,取第一个 URL 并清洗
     * <p>
     * <p>
     * NewAPI 返回的 URL 可能包含反引号、空格等 markdown 符号,需要清洗
     */


    private static String parseFirstResultUrl(String resultUrlsJson) {


        if (resultUrlsJson == null || resultUrlsJson.isBlank()) return null;


        try {


            ObjectMapper mapper = new ObjectMapper();


            String rawUrl;


            if (resultUrlsJson.trim().startsWith("[")) {


                List<String> urls = mapper.readValue(resultUrlsJson,


                        mapper.getTypeFactory().constructCollectionType(List.class, String.class));


                rawUrl = urls.isEmpty() ? null : urls.get(0);


            } else {


                rawUrl = resultUrlsJson.replace("\"", "").trim();


            }


            return sanitizeUrl(rawUrl);


        } catch (Exception e) {


            log.warn("[canvas-video-gen] 解析 resultUrls 失败: {}", e.getMessage());


            return null;


        }


    }


    /**
     * 清洗 URL:去除反引号、前后空格、markdown 符号
     */


    private static String sanitizeUrl(String url) {


        if (url == null || url.isBlank()) return null;


        // 去除前后空格


        String cleaned = url.trim();


        // 去除包裹性的反引号(NewAPI 有时会返回 `https://...` )


        cleaned = cleaned.replaceAll("^`+|`+$", "");


        // 去除包裹性的单引号或双引号


        cleaned = cleaned.replaceAll("^['\"]+|['\"]+$", "");


        // 再次 trim


        cleaned = cleaned.trim();


        if (cleaned.isEmpty()) return null;


        // 验证 URL 格式(简单检查)


        if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {


            log.warn("[canvas-video-gen] 清洗后的 URL 格式异常: {}", cleaned);


        }


        log.info("[canvas-video-gen] URL 清洗: 原始={}, 清洗后={}", url, cleaned);


        return cleaned;


    }


    /**
     * 解析节点的 upstreamIds JSON
     * <p>
     * <p>
     * 支持两种格式:
     * <p>
     * <p>
     * 1. 新格式: [ {"nodeId":"xxx", "port":"default"} ]
     * <p>
     * <p>
     * 2. 旧格式: ["nodeId1", "nodeId2"] (自动转为 port="default")
     */


    private static List<NodeConnection> parseConnections(String json) {


        if (json == null || json.isBlank()) {


            log.warn("[canvas-video-gen] upstreamIds 为空或 blank");


            return java.util.Collections.emptyList();


        }


        try {


            ObjectMapper mapper = new ObjectMapper();


            // 尝试解析为 List<NodeConnection> (新格式)


            List<NodeConnection> result = mapper.readValue(json,


                    mapper.getTypeFactory().constructCollectionType(List.class, NodeConnection.class));


            if (result != null && !result.isEmpty()) {


                log.info("[canvas-video-gen] upstreamIds 解析成功(新格式): {} 个连接", result.size());


                return result;


            }


        } catch (Exception e) {


            log.warn("[canvas-video-gen] upstreamIds 解析为 List<NodeConnection> 失败,尝试旧格式: {}", e.getMessage());


        }


        // 兼容旧格式: ["uuid1", "uuid2"]


        try {


            ObjectMapper mapper = new ObjectMapper();


            List<String> old = mapper.readValue(json,


                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));


            if (old != null && !old.isEmpty()) {


                List<NodeConnection> result = new ArrayList<>();


                for (String id : old) {


                    if (id != null && !id.isBlank()) {


                        result.add(new NodeConnection(id));


                    }


                }


                log.info("[canvas-video-gen] upstreamIds 解析成功(旧格式): {} 个连接", result.size());


                return result;


            }


        } catch (Exception e2) {


            log.warn("[canvas-video-gen] upstreamIds 旧格式解析也失败: {}", e2.getMessage());


        }


        log.warn("[canvas-video-gen] upstreamIds 最终解析为空列表");


        return java.util.Collections.emptyList();


    }


    /**
     * 兜底:videoGenNode 取不到时新建一个空节点(用于日志)
     */


    private CanvasNode videoNode(CanvasNode n) {


        return n;


    }


    /**
     * 2026-08-14 新增:监听 VideoGenerationServiceImpl.handleCompleted 发出的事件,
     * 通过 {@code task.errorMessage="jobId=<id>"} 反向定位 CanvasTask,并把视频 URL 写回 task + videoGenNode。
     * 图生视频场景 task.errorMessage 未存 jobId,本方法会查不到 → 无副作用。
     */
    @EventListener
    public void onVideoJobCompleted(VideoJobCompletedEvent event) {
        Long jobId = event.getJobId();
        String videoUrl = event.getVideoUrl();
        if (jobId == null || videoUrl == null || videoUrl.isBlank()) {
            log.warn("[canvas-video-gen-event] 事件参数无效: jobId={}, videoUrl={}", jobId, videoUrl);
            return;
        }
        try {
            String marker = "jobId=" + jobId;
            java.util.List<CanvasTask> candidates = taskRepository.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<CanvasTask>()
                    .eq("error_message", marker));
            if (candidates.isEmpty()) {
                // 图生视频场景或任务已清理,无副作用
                log.debug("[canvas-video-gen-event] job {} 未找到关联 CanvasTask(可能是图生视频路径)", jobId);
                return;
            }
            CanvasTask task = candidates.get(0);
            task.setResultUrl(videoUrl);
            task.setStatus("success");
            task.setCompletedAt(java.time.LocalDateTime.now());
            // 清理标记,避免下次误命中
            task.setErrorMessage(null);
            taskRepository.updateById(task);

            // 同步更新 videoGenNode.resultUrl,前端轮询 activeNode 就能拿到视频
            if (task.getNodeId() != null && !task.getNodeId().isBlank()) {
                CanvasNode node = nodeRepository.selectById(task.getNodeId());
                if (node != null) {
                    node.setResultUrl(videoUrl);
                    node.setStatus("success");
                    nodeRepository.updateById(node);
                    log.info("[canvas-video-gen-event] job {} → 视频节点 {} 已同步 resultUrl={}",
                        jobId, node.getId(), videoUrl);
                }
            }
        } catch (Exception e) {
            log.warn("[canvas-video-gen-event] job {} 同步 task/node 失败(不影响视频): {}",
                jobId, e.getMessage(), e);
        }
    }


    private void failTask(CanvasTask task, CanvasNode node, String rawMsg) {


        // 2026-08-10 fix:node.failReason 是 VARCHAR(500),必须留余量截断,


        // 否则 Data truncation 会让 failTask 后续的 updateById 全失败,


        // 整个清理流程崩溃,任务和节点永远卡在 running 状态。


        String safe = rawMsg == null ? "未知错误"


                : (rawMsg.length() > 450 ? rawMsg.substring(0, 450) + "..." : rawMsg);


        task.setStatus("failed");


        task.setErrorMessage(safe);


        task.setCompletedAt(LocalDateTime.now());


        node.setStatus("failed");


        node.setFailReason(safe);


        node.setUpdatedAt(LocalDateTime.now());


    }


    /**
     * 2026-08-11:把多张上游图横向拼接成一张大图,作为 NewAPI /v1/videos 的单图参考。
     *
     *
     *
     *
     *
     * <p>所有图统一缩放到"最高的一张图的高度",宽度按原比例缩放,然后并排拼接。
     * <p>
     * <p>
     * 如果只有 1 张图,直接返回(不需要拼接)。
     *
     * @param imageBytesList 原始图片字节流列表(每张解码为 BufferedImage)
     * @return 横向拼接后的 BufferedImage
     */


    private static java.awt.image.BufferedImage mergeImagesHorizontally(java.util.List<byte[]> imageBytesList)


            throws Exception {


        if (imageBytesList == null || imageBytesList.isEmpty()) {


            throw new IllegalArgumentException("imageBytesList is empty");


        }


        // 1. 把所有字节流转成 BufferedImage


        java.util.List<java.awt.image.BufferedImage> images = new java.util.ArrayList<>();


        int targetHeight = 0;


        for (byte[] b : imageBytesList) {


            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(b));


            if (img == null) {


                throw new IllegalArgumentException("无法解码图片,size=" + (b == null ? 0 : b.length));


            }


            images.add(img);


            targetHeight = Math.max(targetHeight, img.getHeight());


        }


        // 2. 每张图按 targetHeight 等比例缩放


        int totalWidth = 0;


        java.util.List<java.awt.image.BufferedImage> resized = new java.util.ArrayList<>();


        for (java.awt.image.BufferedImage img : images) {


            int w = (int) Math.round((double) img.getWidth() * targetHeight / img.getHeight());


            java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(


                    w, targetHeight, java.awt.image.BufferedImage.TYPE_INT_RGB);


            java.awt.Graphics2D g = scaled.createGraphics();


            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,


                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);


            g.drawImage(img, 0, 0, w, targetHeight, null);


            g.dispose();


            resized.add(scaled);


            totalWidth += w;


        }


        // 3. 拼成一张大图(并排,白底)


        java.awt.image.BufferedImage merged = new java.awt.image.BufferedImage(


                totalWidth, targetHeight, java.awt.image.BufferedImage.TYPE_INT_RGB);


        java.awt.Graphics2D g = merged.createGraphics();


        g.setColor(java.awt.Color.WHITE);


        g.fillRect(0, 0, totalWidth, targetHeight);


        int x = 0;


        for (java.awt.image.BufferedImage img : resized) {


            g.drawImage(img, x, 0, null);


            x += img.getWidth();


        }


        g.dispose();


        return merged;


    }


}


