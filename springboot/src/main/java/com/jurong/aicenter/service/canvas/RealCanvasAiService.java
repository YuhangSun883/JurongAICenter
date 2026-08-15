package com.jurong.aicenter.service.canvas;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.client.AicomingAssetsClient;
import com.jurong.aicenter.client.ComfyUIClient;
import com.jurong.aicenter.client.NewApiClient;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.service.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 画布 AI 生成服务真实实现。
 *
 * 三个能力的调用路径：
 *   - polishText    : NewApiClient.chatCompletion()（直接 HTTP 调 NewAPI）
 *   - generateImage : ComfyUIClient.submitWorkflow() + polling history + StorageService.uploadFile()
 *   - generateVideo : ComfyUIClient.submitWorkflow() + polling history + 提取 ui.newapi_task_id
 *                     → NewApiClient.waitForVideo() + download → StorageService.uploadFile()
 *
 * 严禁 mock / 占位 / placeholder URL。所有产物必须是真实 API 调用结果。
 */
@Service
@RequiredArgsConstructor
public class RealCanvasAiService implements CanvasAiService {

    // 2026-08-09 显式 log 字段(替代 @Slf4j,兼容 lombok 不跑的环境)
    private static final Logger log = LoggerFactory.getLogger(RealCanvasAiService.class);

    private final NewApiClient newApiClient;
    private final ComfyUIClient comfyUIClient;
    private final AicomingAssetsClient aicomingAssetsClient;  // 2026-08-11 新增:画布视频走素材库白名单
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    @Value("${llm.model:deepseek-v4-flash}")
    private String llmModel;

    @Value("${llm.max-tokens:2048}")
    private int llmMaxTokens;

    @Value("${llm.system-prompt:你是一位资深的文字扩写编辑。你的唯一任务是：将用户提供的一段简短文字作为核心素材，扩写成一段细节丰富、生动饱满的长文本。\n\n【工作方式】\n1.保留原意：严格基于用户的核心信息，不要改变意图。\n2.增加细节：补充场景、动作、神态、环境、光影、氛围等具体描写，使文本画面感强、适合作为视觉生成的Prompt。\n3.不做任何其他操作：不要解释、不要总结、不要提问、不要反问、不要询问用户意图、不要提供选项、不要判断用户想做视频还是图片。\n\n【绝对禁止】\n-禁止出现任何形式的提问（包括问号、疑问句、反问句、选择问句）\n-禁止出现您想/是否/可以/请问/需要/想要等询问类词汇\n-禁止出现好的/没问题/我来帮你/根据你的需求等服务类开场白\n-禁止输出JSON、tool_call、代码块、函数调用\n-禁止出现对话语气，只能是纯粹的叙事/描写文体\n\n【输出要求】\n-只输出扩写后的正文\n-不加任何前缀、后缀、寒暄、解释\n-用中文，纯文本，不用markdown\n-字数50-150字}")
    private String llmSystemPrompt;

    @Value("${llm.image-poll-timeout-sec:600}")
    private int imagePollTimeoutSec;

    @Value("${llm.video-poll-timeout-sec:1200}")
    private int videoPollTimeoutSec;

    /** workflows 目录路径（jar 同级或 classpath:/workflows/） */
    @Value("${canvas.workflows-dir:classpath:/workflows/}")
    private String workflowsDir;

    /** 视频上传到 MinIO 后的 object key 前缀 */
    private static final String STORAGE_PREFIX = "canvas";

    // ============= 文本润色（真 LLM 调用） =============

    @Override
    public String polishText(String userPrompt, String upstreamContent) {
        // 拼接最终输入：上游上下文（若有）+ 扩写指令 + 用户原始输入
        String expansionInstruction = "\n\n---\n\n【扩写任务】请将上面的文字作为核心素材，扩写成一段50-150字的详细描述。\n" +
            "扩写要求：\n" +
            "1. 增加场景环境：天气、时间、地点、背景\n" +
            "2. 增加动作细节：姿态、表情、运动方式\n" +
            "3. 增加氛围描写：光线、声音、情绪\n" +
            "4. 使用比喻或拟人让画面感更强\n\n" +
            "示例：\n" +
            "输入：一只小猫\n" +
            "输出：一只橘黄色的小猫在午后温暖的阳光下散步，毛茸茸的尾巴轻轻摇晃，金色的瞳孔微微眯起，脚步悠闲，空气里飘着青草的香气。\n\n" +
            "现在请扩写以上内容，直接输出扩写后的正文，不要任何解释或前缀：";

        String finalInput;
        if (upstreamContent != null && !upstreamContent.isBlank()) {
            finalInput = "【上游节点输出】\n" + upstreamContent + expansionInstruction + "\n【用户原始输入】\n" + userPrompt;
        } else {
            finalInput = expansionInstruction + "\n【用户原始输入】\n" + userPrompt;
        }

        String raw = newApiClient.chatCompletion(
            llmModel,
            llmSystemPrompt,
            finalInput,
            llmMaxTokens
        );
        String polished = cleanLlmTextOutput(raw);
        log.info("Canvas text polished: inputLen={}, rawOutputLen={}, cleanOutputLen={}",
            finalInput.length(), raw == null ? 0 : raw.length(), polished == null ? 0 : polished.length());
        return polished;
    }

    /**
     * 清理 LLM 文本输出：剥掉 tool_call JSON 块、开场白、废话前后缀。
     *
     * <p>deepseek-v4-flash 等模型被 Agent 训练过，即使 system prompt 说"输出纯文本"，
     * 仍可能输出 {"tool_call":...} 或 "好的，我帮你...以下是..."等格式。
     * 本方法作为防御性清洗，确保画布文本节点只显示正文章案。</p>
     */
    private String cleanLlmTextOutput(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";

        // 1) 剥掉 {"tool_call": {...}} 整段（包括嵌套花括号，最多 2 层）
        java.util.regex.Pattern toolCallPat = java.util.regex.Pattern.compile(
            "\\{\\s*\"tool_call\"\\s*:\\s*\\{(?:[^{}]|\\{[^{}]*\\})*\\}\\s*\\}",
            java.util.regex.Pattern.DOTALL
        );
        s = toolCallPat.matcher(s).replaceAll(" ").trim();

        // 2) 剥掉代码块 ```json ... ``` / ``` ... ```
        s = s.replaceAll("(?s)```[a-zA-Z0-9_-]*\\s*.*?```", " ").trim();

        // 3) 彻底清除带问号的行（LLM 反问/提问时整行删掉）
        String original = s;
        s = s.replaceAll("(?m)^.*[?？].*$\\s*", "").trim();
        s = s.replaceAll("\\n{3,}", "\n\n").trim();
        boolean hadQuestion = !original.equals(s);
        if (hadQuestion) {
            log.warn("Canvas LLM output contained question marks (stripped those lines). rawLen={}, cleanLen={}",
                raw.length(), s.length());
        }

        // 4) 去掉开头的寒暄语 / 提问前缀（循环 5 次直到稳定）
        String[] openers = {
            "^好的[，,。.！!]?\\s*",
            "^没问题[，,。.！!]?\\s*",
            "^我可以帮你[^\n\r]{0,40}([\n\r]+|：)",
            "^以下是[^\n\r]{0,30}：\\s*",
            "^下面是[^\n\r]{0,30}：\\s*",
            "^为你[^\n\r]{0,20}如下[：:]\\s*",
            "^这是[^\n\r]{0,20}：\\s*",
            "^根据[^\n\r]{0,30}，?(我)?为你?(准备|生成|润色|整理)?了?(以下|如下)?内容?[：:]?\\s*",
            "^润色结果[如下为：:]*\\s*",
            "^(您好|你好)[，,。.!！]?\\s*",
            "^是的?[，,。.！!]?\\s*",
            "^您想[^\n\r]{0,30}[?？]\\s*",
            "^是否[^\n\r]{0,30}[?？]\\s*",
            "^请问[^\n\r]{0,30}[?？]\\s*",
            "^需要[^\n\r]{0,30}[?？]\\s*",
            "^想要[^\n\r]{0,30}[?？]\\s*",
        };
        boolean changed = true;
        int guard = 0;
        while (changed && guard < 5) {
            changed = false;
            guard++;
            for (String re : openers) {
                String before = s;
                s = s.replaceFirst("(?s)" + re, "");
                if (!s.equals(before)) changed = true;
            }
            s = s.trim();
        }

        // 5) 去掉结尾的客套话（行级，单行尾部）
        s = s.replaceAll("(?m)\\s*(希望[对为]你有[帮助用处益]|如有.*请.*|以上.*|请.*参考|感谢.*|期待.*)$", "");

        // 6) 合并多余空行（超过 2 个换行 → 1 个空行）
        s = s.replaceAll("\\r\\n", "\n").replaceAll("\n{3,}", "\n\n").trim();

        // 7) 如果清洗后太短（< 20 字），说明 LLM 没认真扩写，用原始内容兜底
        if (s.length() < 20) {
            log.warn("Canvas LLM output too short after clean: {} chars (raw={}), falling back to cleaned raw",
                s.length(), raw.length());
            return original;
        }

        return s.trim();
    }

    /**
     * 智能合并：用户输入为主，上游文案作为风格参考；冲突时以用户为准。
     *
     * <p>当 upstreamContent 为空时，直接返回 userPrompt（不需要 LLM 调用），
     * 避免不必要的延迟和费用。</p>
     */
    @Override
    public String mergePrompts(String userPrompt, String upstreamContent) {
        // 安全校验
        if (userPrompt == null) userPrompt = "";
        if (upstreamContent == null) upstreamContent = "";

        String trimmedUser = userPrompt.trim();
        String trimmedUp = upstreamContent.trim();

        // 没上游或上游为空 → 直接用用户输入
        if (trimmedUp.isEmpty()) {
            log.info("Canvas mergePrompts: no upstream, use userPrompt directly (len={})", trimmedUser.length());
            return userPrompt;
        }

        // 用户没输入 → 用上游文案
        if (trimmedUser.isEmpty()) {
            log.info("Canvas mergePrompts: no userPrompt, use upstreamContent directly (len={})", trimmedUp.length());
            return upstreamContent;
        }

        // 用户输入太短（<20字）→ 调 LLM 不划算，直接拼接
        // 原因：用户只填关键词（如"高端手表"）时，上游文案已经足够描述产品，
        //       LLM 合并反而多 1 次调用、30-60秒延迟，偶尔还会 180s 超时
        // 阈值 20 字符经验值：低于这个长度一般是"补充关键词"，不需要智能合并
        final int LLM_MERGE_MIN_USER_LEN = 20;
        if (trimmedUser.length() < LLM_MERGE_MIN_USER_LEN) {
            String concat = trimmedUser + ", " + trimmedUp;
            log.info("Canvas mergePrompts: user prompt too short ({}<{}), skip LLM, concat: {}",
                     trimmedUser.length(), LLM_MERGE_MIN_USER_LEN, concat);
            return concat;
        }

        // 两者都有且用户输入足够长 → LLM 智能合并
        String mergeSystemPrompt = "你是 AI 创作提示词工程师。请基于【用户输入】（主）和【上游润色文案】（参考），生成适合视频/图片生成的最终详细描述。\n\n" +
            "规则：\n" +
            "1. **以用户输入为主**：用户指定的具体细节（颜色、动作、天气、场景、角色特征等）优先级最高\n" +
            "2. **借鉴上游文案的氛围与风格**：光线、镜头、质感、节奏、整体调性\n" +
            "3. **解决冲突**：如果两者矛盾（如天气、颜色、场景、动作、风格），以用户输入为准，上游文案仅取其风格描写\n" +
            "4. **输出要求**：包含主体动作、镜头运动、光影变化、氛围细节；用中文；纯文本不用 markdown；100-200 字\n" +
            "5. **严格格式**：直接输出最终描述正文，不要任何解释、前缀、寒暄、JSON、tool_call、函数调用、代码块。不要说'好的'、'以下是'、'根据...我帮你'等客套话。只给正文。";

        String mergeUserPrompt = "【用户输入】\n" + userPrompt + "\n\n【上游润色文案】\n" + upstreamContent;

        String raw = newApiClient.chatCompletion(
            llmModel,
            mergeSystemPrompt,
            mergeUserPrompt,
            llmMaxTokens
        );
        String merged = cleanLlmTextOutput(raw);
        log.info("Canvas mergePrompts: userLen={}, upstreamLen={}, rawLen={}, mergedLen={}",
            userPrompt.length(), upstreamContent.length(), raw == null ? 0 : raw.length(), merged.length());
        return merged;
    }

    // ============= 图片生成（走 NewAPI 中转站 =============

    @Value("${canvas.defaults.image-model:gpt-image-2-1k}")
    private String canvasImageModel;

    @Value("${canvas.defaults.image-size:1024x1024}")
    private String canvasImageSize;

    @Value("${canvas.defaults.image-quality:low}")
    private String canvasImageQuality;

    /**
     * 如果 generateImage 返回的是 data URI（b64_json），
     * 解码后上传 MinIO，返回公网 URL；如果已经是 URL，则直接返回。
     */
    private String normalizeImageResult(String resultData, String logPrefix) throws Exception {
        if (resultData == null || resultData.isBlank()) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "图片生成返回为空");
        }
        // 已经是公网 URL(包括 MinIO 存储 URL / apac.ossforai.com / subrouter.ai proxy)
        if (resultData.startsWith("http://") || resultData.startsWith("https://")) {
            log.info("{} NewAPI 返回公网 URL, 直传: {}", logPrefix, resultData);
            return resultData;
        }
        // data:image/png;base64,xxxxx 格式 → 解码上传 MinIO
        if (resultData.startsWith("data:")) {
            int comma = resultData.indexOf(',');
            String mime = "image/png";
            String b64;
            if (comma >= 0) {
                String header = resultData.substring(0, comma);
                int semi = header.indexOf(';');
                if (semi >= 5) mime = header.substring(5, semi);
                b64 = resultData.substring(comma + 1);
            } else {
                b64 = resultData;
            }
            byte[] bytes = java.util.Base64.getDecoder().decode(b64);
            String ext = mime.contains("jpeg") ? "jpg" : "png";
            String uuid = java.util.UUID.randomUUID().toString().replace("-", "");
            String month = java.time.format.DateTimeFormatter.ofPattern("yyyyMM")
                .format(java.time.LocalDateTime.now());
            String objectKey = "canvas/" + month + "/" + uuid + "." + ext;
            String uploaded = storageService.uploadObject(
                objectKey, new java.io.ByteArrayInputStream(bytes), mime);
            log.info("{} NewAPI b64_json → MinIO: key={}, url={}", logPrefix, objectKey, uploaded);
            return uploaded;
        }
        log.warn("{} 未知返回格式, 前 50 字符: {}", logPrefix, resultData.substring(0, Math.min(50, resultData.length())));
        return resultData;
    }

    @Override
    public String generateImage(String prompt, String upstreamContent) {
        // 合并 prompt(用户为主 + 上游文案作为风格参考)
        String finalPrompt = mergePrompts(prompt, upstreamContent);

        // 调用 NewAPI 中转站文生图
        log.info("Canvas image (NewAPI 文生图): model={}, size={}, quality={}, promptLen={}",
            canvasImageModel, canvasImageSize, canvasImageQuality, finalPrompt.length());
        String rawResult = newApiClient.generateImageWithModel(
            canvasImageModel, finalPrompt, canvasImageSize, null, null);

        // 结果规范化(base64 → MinIO 或 直传 URL)
        try {
            return normalizeImageResult(rawResult, "[canvas-gen-image]");
        } catch (Exception e) {
            throw e instanceof BusinessException ? (BusinessException) e
                : new BusinessException(ErrorCode.INTERNAL_ERROR, "图片结果处理失败: " + e.getMessage());
        }
    }

    /**
     * 辅助：将远程图片 URL 下载并转成 base64 data URI
     * (用于传给 NewApiClient.editImage)
     */
    private String imageUrlToDataUri(String imageUrl) throws Exception {
        java.net.URL url = URI.create(imageUrl).toURL();
        byte[] bytes;
        try (InputStream in = url.openStream()) {
            bytes = in.readAllBytes();
        }
        // 简单猜 mime
        String lower = imageUrl.toLowerCase();
        String mime = "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) mime = "image/jpeg";
        else if (lower.endsWith(".webp")) mime = "image/webp";
        String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
        return "data:" + mime + ";base64," + b64;
    }

    @Override
    public String editImage(String imageUrl, String prompt, String upstreamContent) {
        // 1. 合并 prompt
        String finalPrompt = mergePrompts(prompt, upstreamContent);

        // 2. 2026-08-14 修复:改走 /v1/images/generations + JSON image 字段(单图/多图同接口)
        //    原 editImage() 走 /v1/images/edits multipart 被中转站报 "invalid multipart request"
        //    按聚融中转站接口手册 v3.0 5.4:图生图=POST /v1/images/generations,多图输入格式用 image 数组
        //    参考图直接传 URL(中转站会自动下载),不需要先转 base64
        java.util.List<String> refs = java.util.List.of(imageUrl);

        log.info("Canvas image (NewAPI 图生图→/v1/images/generations): model={}, size={}, refs=1, promptLen={}",
            canvasImageModel, canvasImageSize, finalPrompt != null ? finalPrompt.length() : 0);
        String rawResult = newApiClient.imageToImage(
            canvasImageModel, finalPrompt, canvasImageSize, refs);

        // 3. 结果规范化
        try {
            return normalizeImageResult(rawResult, "[canvas-image-to-image]");
        } catch (Exception e) {
            throw e instanceof BusinessException ? (BusinessException) e
                : new BusinessException(ErrorCode.INTERNAL_ERROR, "图片结果处理失败: " + e.getMessage());
        }
    }

    // ============= 视频生成（真 ComfyUI + 真 NewAPI 调用） =============

    @Override
        public String generateVideo(String prompt, String imageUrl, String upstreamContent) {
        // 2026-08-09:用户确认 ComfyUI 图生视频路径工作正常,统一走 ComfyUI
        //   - 有上游图片 → workflow 03 (SVD),上传图作为起始帧
        //   - 无上游图片 → workflow 02(纯文生视频,经验证可用)
        // 2026-08-11:ComfyUI 容器 token 失效,改为直接走 NewAPI(绕开 ComfyUI)
        if (imageUrl != null && !imageUrl.isBlank()) {
            return generateVideoViaNewApi(prompt, imageUrl, upstreamContent);
        }
        // 无上游图片:走文生视频(也是直接调 NewAPI)
        return generateVideoViaNewApi(prompt, null, upstreamContent);
    }

    /**
     * 2026-08-11 新增:多图版生成视频。
     * 把多个 image 节点的 URL 全部传给 NewAPI /v1/videos 的 image_urls 参数。
     * 适用于"三视图+换装帧图+其他参考图"场景。
     */
    @Override
    public String generateVideoMulti(String prompt, java.util.List<String> imageUrls, String upstreamContent) {
        // 智能合并 prompt
        String finalPrompt = mergePrompts(prompt, upstreamContent);

        // 兜底:图生视频必须有 prompt(NewAPI 400 会拒)
        // 关键:仿照 jurong-api-nodes/image_to_video.py 的 _enhance_prompt
        // 自动在 prompt 末尾追加 CRITICAL 后缀,强制锁定参考图主体
        if (finalPrompt == null || finalPrompt.trim().isEmpty()) {
            // 没输入:默认走一个轻描述,后缀会接 CRITICAL
            finalPrompt = "Animate these reference images with subtle, natural motion. Cinematic, smooth camera movement.";
        }
        // 无论是默认还是用户输入,都加 CRITICAL 后缀
        finalPrompt = enhanceVideoPrompt(finalPrompt);

        // 过滤空 URL
        java.util.List<String> validUrls = new java.util.ArrayList<>();
        if (imageUrls != null) {
            for (String u : imageUrls) {
                if (u != null && !u.isBlank()) validUrls.add(u);
            }
        }

        log.info("Canvas video (NewAPI multi) prompt={} imageUrls={}",
            finalPrompt.substring(0, Math.min(80, finalPrompt.length())),
            validUrls);

        // 1) 无上游图片 → 文生视频(直接传 prompt)
        if (validUrls.isEmpty()) {
            String taskId = newApiClient.submitVideo(
                finalPrompt, null, null, null, 4, "480P");
            return pollAndDownload(taskId, finalPrompt);
        }

        // 2) 单图 → 走单图版(走素材库白名单)
        if (validUrls.size() == 1) {
            return generateVideoViaNewApi(finalPrompt, validUrls.get(0), null);
        }

        // 3) 多图 → 2026-08-11 改:把所有图片上传到 aicoming-proxy 素材库,拿到 asset_url 列表
        //    然后用 submitVideoByAssetRefList 走白名单
        java.util.List<String> assetUrls = new java.util.ArrayList<>();
        for (int i = 0; i < validUrls.size(); i++) {
            String url = validUrls.get(i);
            String name = "canvas-video-multi-" + System.currentTimeMillis() + "-" + i;
            String assetUrl = uploadImageUrlToAsset(url, name);
            assetUrls.add(assetUrl);
        }
        log.info("Canvas video (NewAPI multi via asset_url): uploaded {} images, first={}",
            assetUrls.size(), assetUrls.get(0));

        // 提交 NewAPI 视频任务(多图 asset_url 列表)
        NewApiClient.SubmitResult result = newApiClient.submitVideoByAssetRefList(
            finalPrompt, assetUrls, 4, "480P");
        String taskId = result.taskId();
        if (taskId == null || taskId.isEmpty()) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "NewAPI submit (asset_url list) 返回 taskId 为空");
        }

        // 如果响应里已含 video url,直接走 MinIO
        String directUrl = result.url();
        String videoUrl = null;
        if (directUrl != null && !directUrl.isBlank()) {
            log.info("Canvas video (NewAPI multi via asset_url) 响应已含 video url: {}", directUrl);
            videoUrl = directUrl;
        } else {
            // 轮询等结果
            JsonNode pollResult = newApiClient.waitForVideo(taskId, videoPollTimeoutSec);
            log.info("Canvas video (NewAPI multi) taskId={} completed", taskId);
            videoUrl = newApiClient.extractVideoUrl(pollResult);
        }
        if (videoUrl == null || videoUrl.isEmpty()) {
            throw new BusinessException(ErrorCode.NEWAPI_VIDEO_URL_MISSING,
                "NewAPI multi 响应中未找到视频 URL: " + taskId);
        }

        // 下载视频 → 上传 MinIO
        try (java.io.InputStream is = new java.net.URI(videoUrl).toURL().openStream()) {
            String ext = "mp4";
            if (videoUrl.contains(".mov")) ext = "mov";
            String objectKey = STORAGE_PREFIX + "/" + taskId + "." + ext;
            String uploaded = storageService.uploadObject(objectKey, is, "video/" + ext);
            log.info("Canvas video (multi) uploaded: taskId={}, url={}", taskId, uploaded);
            return uploaded;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "视频下载/上传失败: " + e.getMessage());
        }
    }

    /**
     * 辅助:轮询 + 下载视频 + 上传 MinIO(从 generateVideoViaNewApi 抽出来复用)
     */
    private String pollAndDownload(String taskId, String finalPrompt) {
        JsonNode pollResult = newApiClient.waitForVideo(taskId, videoPollTimeoutSec);
        log.info("Canvas video (NewAPI) taskId={} completed", taskId);

        String videoUrl = newApiClient.extractVideoUrl(pollResult);
        if (videoUrl == null || videoUrl.isEmpty()) {
            throw new BusinessException(ErrorCode.NEWAPI_VIDEO_URL_MISSING,
                "NewAPI 响应中未找到视频 URL: " + pollResult);
        }

        try (java.io.InputStream is = new java.net.URI(videoUrl).toURL().openStream()) {
            String ext = "mp4";
            if (videoUrl.contains(".mov")) ext = "mov";
            String objectKey = STORAGE_PREFIX + "/" + taskId + "." + ext;
            String uploaded = storageService.uploadObject(objectKey, is, "video/" + ext);
            log.info("Canvas video uploaded: taskId={}, url={}", taskId, uploaded);
            return uploaded;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "视频下载/上传失败: " + e.getMessage());
        }
    }

    /**
     * 辅助:PNG 转 JPEG(给 aicoming-proxy 用)
     */
    private byte[] convertPngToJpegIfNeeded(byte[] rawBytes, String originalName) {
        try {
            java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(rawBytes);
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(bais);
            if (img != null) {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(img, "jpg", baos);
                log.info("Canvas video (multi) PNG→JPEG: {}B → {}B, filename={}",
                         rawBytes.length, baos.size(), originalName.replaceAll("\\.png$", ".jpg"));
                return baos.toByteArray();
            }
        } catch (Exception e) {
            log.warn("PNG→JPEG conversion failed, using raw: {}", e.getMessage());
        }
        return rawBytes;
    }

    /**
     * 2026-08-11 新增:把图片 URL(公网/MinIO)上传到 aicoming-proxy 素材库,返回 asset_url。
     * 用于画布视频节点的"走素材库白名单"机制(参考 Assets-API-参考手册.md §4.1)。
     * NewAPI 上游 doubao-seedance 对 asset_url 走白名单路径,跳过真人检测。
     *
     * @param imageUrl   公网可访问的图片 URL
     * @param assetName  资产名(用于日志/调试)
     * @return aicoming-proxy 返回的 asset_url(如 asset://aic_xxx)
     */
    private String uploadImageUrlToAsset(String imageUrl, String assetName) {
        if (imageUrl == null || imageUrl.isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "uploadImageUrlToAsset: imageUrl 为空");
        }
        try {
            // 1. 下载上游图片字节
            byte[] imageBytes;
            try (java.io.InputStream is = new URI(imageUrl).toURL().openStream()) {
                imageBytes = is.readAllBytes();
            }
            // 2. 强制转 JPEG(aicoming-proxy 兼容性更好,且白名单审核更稳)
            imageBytes = convertPngToJpegIfNeeded(imageBytes, "asset_" + System.currentTimeMillis() + ".jpg");
            String filename = "asset_" + System.currentTimeMillis() + ".jpg";

            // 3. 上传到 aicoming-proxy,获取 asset_id
            JsonNode resp = aicomingAssetsClient.uploadAssetByMultipart(
                imageBytes, filename, "image/jpeg", assetName);
            String assetId = resp.path("id").asText("");
            String assetUrl = resp.path("asset_url").asText("");

            if (assetId.isEmpty() || assetUrl.isEmpty()) {
                throw new BusinessException(ErrorCode.ASSET_UPLOAD_FAILED,
                    "aicoming-proxy 上传未返回 asset_id/asset_url: " + resp);
            }
            log.info("[Canvas video asset upload] url={} → assetId={}, assetUrl={}",
                imageUrl, assetId, assetUrl);

            // 4. 轮询等到 active(参考 Assets-API-参考手册 §4.2)
            JsonNode polled = aicomingAssetsClient.pollUntilActive(assetId, 90, 3);
            String status = polled.path("data").path("status").asText("");
            if (!"active".equalsIgnoreCase(status)) {
                throw new BusinessException(ErrorCode.ASSET_UPLOAD_FAILED,
                    "aicoming-proxy 资产未 active, status=" + status);
            }
            log.info("[Canvas video asset upload] assetId={} active, ready for NewAPI", assetId);
            return assetUrl;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.ASSET_UPLOAD_FAILED,
                "上传图片到 aicoming-proxy 素材库失败: " + e.getMessage());
        }
    }

    /**
     * 图生视频：直接调 NewAPI /v1/videos，绕开 ComfyUI
     * 调用链：MinIO → 字节 → NewAPI → aicoming.top 视频模型 → URL → 下载 → MinIO
     */
    private String generateVideoViaNewApi(String prompt, String imageUrl, String upstreamContent) {
        // 1. 智能合并 prompt
        String finalPrompt = mergePrompts(prompt, upstreamContent);

        // 兑底：图生视频必须有 prompt（NewAPI 400 会拒）
        if (finalPrompt == null || finalPrompt.trim().isEmpty()) {
            finalPrompt = "Animate this image with subtle, natural motion. Cinematic, smooth camera movement.";
        }
        // 加 CRITICAL 后缀锁定参考图主体
        finalPrompt = enhanceVideoPrompt(finalPrompt);

        // 2. 2026-08-14 修复:文生视频(imageUrl=null)场景跳过素材上传,直接走普通 /v1/videos
        //    原逻辑无脑调 uploadImageUrlToAsset → imageUrl 为空时抛"imageUrl 为空"异常
        boolean isTextToVideo = (imageUrl == null || imageUrl.isBlank());
        NewApiClient.SubmitResult result;
        String taskId;
        if (isTextToVideo) {
            // 2026-08-14 修复:文生视频严格按聚融中转站接口手册 v3.0 §6.2 走 JSON 格式
            //    原 submitVideo(...) 走 multipart + 占位图,aicoming-video-proxy 可能报缺图
            log.info("Canvas video (NewAPI 文生视频, JSON): prompt={}",
                finalPrompt.substring(0, Math.min(60, finalPrompt.length())));
            result = newApiClient.submitVideoText(finalPrompt, 4, "480P");
        } else {
            // 图生视频:上传上游图到 aicoming-proxy 拿 asset_url → 用 asset_url 调 NewAPI(走白名单,跳真人检测)
            String assetUrl = uploadImageUrlToAsset(imageUrl, "canvas-video-" + System.currentTimeMillis());
            log.info("Canvas video (NewAPI via asset_url) input: assetUrl={}, prompt={}",
                assetUrl, finalPrompt.substring(0, Math.min(60, finalPrompt.length())));
            result = newApiClient.submitVideoByAssetRef(finalPrompt, assetUrl, 4, "480P");
        }
        taskId = result.taskId();
        if (taskId == null || taskId.isEmpty()) {
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE,
                "NewAPI submit 返回 taskId 为空");
        }

        // 4. 如果响应里已经带 video url,直接走 MinIO(避免后续 poll 失败)
        String directUrl = result.url();
        String videoUrl = null;
        if (directUrl != null && !directUrl.isBlank()) {
            log.info("Canvas video (NewAPI via asset_url) 响应已含 video url: {}", directUrl);
            videoUrl = directUrl;
        } else {
            // 5. 轮询等结果
            JsonNode pollResult = newApiClient.waitForVideo(taskId, videoPollTimeoutSec);
            log.info("Canvas video (NewAPI) taskId={} completed: {}", taskId, pollResult);
            videoUrl = newApiClient.extractVideoUrl(pollResult);
        }
        if (videoUrl == null || videoUrl.isEmpty()) {
            throw new BusinessException(ErrorCode.NEWAPI_VIDEO_URL_MISSING,
                "NewAPI 响应中未找到视频 URL: " + taskId);
        }

        // 6. 下载视频 → 上传 MinIO
        try (InputStream is = new URI(videoUrl).toURL().openStream()) {
            String ext = "mp4";
            if (videoUrl.contains(".mov")) ext = "mov";
            else if (videoUrl.contains(".webm")) ext = "webm";
            String objectKey = STORAGE_PREFIX + "/" + taskId + "/video." + ext;
            String uploaded = storageService.uploadObject(objectKey, is, "video/" + ext);
            log.info("Canvas video (NewAPI) → MinIO: taskId={}, url={}", taskId, uploaded);
            return uploaded;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "视频下载/上传失败: " + e.getMessage());
        }
    }

    /**
     * 文生视频：走 ComfyUI workflow 02（已验证可用）
     * imageFilename 为 null 时走纯文本，否则是上游图片（上传到 ComfyUI input 后的名字）
     */
    private String generateVideoViaComfyUI(String prompt, String imageFilename, String upstreamContent) {
        boolean isTextToVideo = (imageFilename == null);
        String template;
        String nodeOutputKey;

        if (isTextToVideo) {
            template = readWorkflowTemplate("02-text-to-video.json");
            nodeOutputKey = "1";
        } else {
            template = readWorkflowTemplate("03-image-to-video.json");
            nodeOutputKey = "2";
        }

        // 智能合并：用户输入为主，上游文案作为风格参考；冲突时以用户为准
        String finalPrompt = mergePrompts(prompt, upstreamContent);

        // 替换占位符
        String workflowJson = template
            .replace("{{prompt}}", escapeJson(finalPrompt));
        if (!isTextToVideo) {
            workflowJson = workflowJson.replace("{{image_filename}}", escapeJson(imageFilename));
        }

        // 提交 ComfyUI
        JsonNode workflow;
        try {
            workflow = objectMapper.readTree(workflowJson);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "workflow JSON 解析失败: " + e.getMessage());
        }

        String promptId = comfyUIClient.submit(workflow);
        log.info("Canvas video (ComfyUI) workflow submitted: promptId={}, mode={}, imageFilename={}",
            promptId, isTextToVideo ? "text-to-video" : "image-to-video", imageFilename);

        // 轮询 history
        JsonNode history = pollUntilDone(promptId, videoPollTimeoutSec);

        // 提取视频产物
        JsonNode entry = history.get(promptId);
        JsonNode outputs = entry.get("outputs");
        if (outputs == null || !outputs.has(nodeOutputKey)) {
            throw new BusinessException(ErrorCode.COMFYUI_REJECTED,
                "ComfyUI 未返回 " + nodeOutputKey + " 节点 outputs");
        }
        JsonNode outNode = outputs.get(nodeOutputKey);
        log.info("ComfyUI video outputs[{}] keys: {}", nodeOutputKey,
            outNode == null ? "null" : outNode.fieldNames().toString());

        String url = null;

        // 1) 优先 video_url[]（公网 URL，不依赖 ComfyUI 本地路径，直接下载上传 MinIO）
        if (outNode != null && outNode.has("video_url") && outNode.get("video_url").isArray()) {
            url = downloadVideoUrl(outNode.get("video_url"), promptId);
        }

        // 2) fallback: video_path[]（ComfyUI 本地路径，通过 /view 下载再上传 MinIO）
        if (url == null && outNode != null && outNode.has("video_path") && outNode.get("video_path").isArray()) {
            url = downloadVideoPath(outNode.get("video_path"), promptId);
        }

        // 3) fallback: newapi_task_id[] → 调 NewAPI 等结果（image-to-video 节点用）
        if (url == null && outNode != null && outNode.has("newapi_task_id") && outNode.get("newapi_task_id").isArray()) {
            url = waitAndDownloadFromNewApi(outNode.get("newapi_task_id"), promptId);
        }

        if (url == null) {
            log.error("ComfyUI video outNode[{}] dump: {}", nodeOutputKey,
                outNode == null ? "null" : outNode.toString());
            throw new BusinessException(ErrorCode.COMFYUI_REJECTED,
                nodeOutputKey + " 节点 outputs 既无 video_url/video_path 也无 newapi_task_id");
        }

        log.info("Canvas video (ComfyUI) generated: promptId={}, url={}", promptId, url);
        return url;
    }

    /** 下载 video_url[] 里的远程 URL（公网视频），上传到 MinIO，返回公网 URL。 */
    private String downloadVideoUrl(JsonNode videoUrlArr, String promptId) {
        if (videoUrlArr == null || !videoUrlArr.isArray()) return null;
        for (JsonNode n : videoUrlArr) {
            if (!n.isTextual()) continue;
            String url = n.asText();
            if (url.isEmpty() || !url.startsWith("http")) continue;
            try {
                log.info("Downloading video URL (promptId={}): {}", promptId, url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(30_000);
                conn.setReadTimeout(300_000); // 视频文件可能 10-50MB，给 5 分钟
                conn.setRequestProperty("User-Agent", "JurongAICenter/1.0");
                try (InputStream is = conn.getInputStream()) {
                    String ext = "mp4";
                    if (url.contains(".mov")) ext = "mov";
                    else if (url.contains(".webm")) ext = "webm";
                    else if (!url.contains(".mp4")) ext = "bin";
                    String filename = "video_" + System.currentTimeMillis() + "." + ext;
                    String objectKey = STORAGE_PREFIX + "/" + promptId + "/" + filename;
                    String minioUrl = storageService.uploadObject(objectKey, is, "video/" + ext);
                    log.info("Uploaded video to MinIO: key={}, url={}", objectKey, minioUrl);
                    return minioUrl;
                }
            } catch (Exception e) {
                log.warn("downloadVideoUrl 失败 {}: {}", url, e.getMessage());
            }
        }
        return null;
    }

    @Override
    public Integer estimateCredits(String type, Map<String, Object> settings) {
        // 简化：按节点类型固定价格（真实项目应按模型 + settings 查价目表）
        return switch (type == null ? "" : type) {
            case "text"  -> 1;
            case "image" -> 5;
            case "video" -> 20;
            default      -> 1;
        };
    }

    // ============= 内部工具方法 =============

    /** 读 workflow JSON 模板（从 classpath:/workflows/ 或绝对路径） */
    private String readWorkflowTemplate(String filename) {
        try {
            if (workflowsDir.startsWith("classpath:")) {
                String path = workflowsDir.substring("classpath:".length()) + filename;
                Resource res = new org.springframework.core.io.ClassPathResource(path);
                if (!res.exists()) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "workflow 模板不存在: classpath:" + path);
                }
                try (InputStream is = res.getInputStream()) {
                    return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            } else {
                Path p = Paths.get(workflowsDir, filename);
                if (!Files.exists(p)) {
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "workflow 模板不存在: " + p.toAbsolutePath());
                }
                return Files.readString(p, StandardCharsets.UTF_8);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读 workflow 失败: " + e.getMessage());
        }
    }

    /** JSON 字符串转义（用于塞进 workflow 模板） */
    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ").replace("\t", " ");
    }

    /** 轮询 ComfyUI history 直到 status.completed=true 或超时 */
    private JsonNode pollUntilDone(String promptId, int timeoutSec) {
        long start = System.currentTimeMillis();
        long timeoutMs = timeoutSec * 1000L;
        while (System.currentTimeMillis() - start < timeoutMs) {
            JsonNode history;
            try {
                history = comfyUIClient.pollHistory(promptId);
            } catch (BusinessException e) {
                // COMFYUI_UNREACHABLE：留到下次重试
                log.warn("poll history failed, retry: {}", e.getMessage());
                sleep(3000);
                continue;
            }
            if (history == null) {
                sleep(2000);
                continue;
            }
            JsonNode entry = history.get(promptId);
            if (entry == null) {
                sleep(2000);
                continue;
            }
            // 检查是否完成
            JsonNode status = entry.get("status");
            boolean completed = status != null && status.path("completed").asBoolean(false);
            if (completed) {
                return history;
            }
            // 检查是否失败
            String statusStr = status != null ? status.path("status_str").asText("") : "";
            if ("error".equalsIgnoreCase(statusStr)) {
                // DEBUG: 把整个 entry dump 到日志，下次失败时能看到原始结构
                log.error("ComfyUI workflow FAILED. promptId={}, full entry={}",
                    promptId, entry.toString());
                // 提取错误信息。关键：取 status.exec_info.traceback 整个 list（不是取 message）
                JsonNode execInfo = status.get("exec_info");
                String tracebackJson = (execInfo != null && execInfo.has("traceback"))
                    ? execInfo.get("traceback").toString()
                    : "";
                if (!tracebackJson.isEmpty() && !"[]".equals(tracebackJson)) {
                    // 把整个 traceback 记录到后端日志，方便调试
                    log.error("ComfyUI workflow traceback (promptId={}): {}",
                        promptId, tracebackJson);
                    String snippet = tracebackJson.length() > 2000
                        ? tracebackJson.substring(0, 2000) + "..."
                        : tracebackJson;
                    throw new BusinessException(ErrorCode.COMFYUI_REJECTED, "ComfyUI traceback: " + snippet);
                }
                // fallback
                String detail = extractComfyUiError(entry);
                throw new BusinessException(ErrorCode.COMFYUI_REJECTED, detail);
            }
            sleep(2000);
        }
        throw new BusinessException(ErrorCode.NEWAPI_TASK_TIMEOUT,
            "ComfyUI workflow 超时: promptId=" + promptId);
    }

    /** 下载 ComfyUI /view 输出 + 上传到 MinIO + 返回公网 URL */
    private String downloadAndUpload(String promptId, String filename, String subfolder, String type,
                                     String kind, String mediaKind) {
        try (InputStream is = comfyUIClient.downloadStream(filename, subfolder, type)) {
            String contentType = inferContentType(filename, mediaKind);
            String objectKey = STORAGE_PREFIX + "/" + promptId + "/" + filename;
            String url = storageService.uploadObject(objectKey, is, contentType);
            log.info("Uploaded {} to MinIO: key={}, url={}", kind, objectKey, url);
            return url;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                kind + " 下载/上传失败: " + e.getMessage());
        }
    }

    /** 处理 ui.video_path[]（可能多个路径，取第一个能下载的） */
    private String downloadVideoPath(JsonNode videoPathArr, String promptId) {
        if (videoPathArr == null || !videoPathArr.isArray()) return null;
        for (JsonNode p : videoPathArr) {
            if (!p.isTextual()) continue;
            String path = p.asText();
            if (path == null || path.isEmpty()) continue;
            try {
                String filename = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                String subfolder = "";
                if (path.contains("/output/")) {
                    String after = path.substring(path.indexOf("/output/") + 8);
                    int idx = after.lastIndexOf('/');
                    if (idx > 0) subfolder = after.substring(0, idx);
                }
                return downloadAndUpload(promptId, filename, subfolder, "output", "video", "video");
            } catch (Exception e) {
                log.warn("downloadVideoPath 失败 {}: {}", path, e.getMessage());
            }
        }
        return null;
    }

    /** 处理 ui.newapi_task_id[]：NewAPI 同步等结果 → 下载视频 → 上传 MinIO */
    private String waitAndDownloadFromNewApi(JsonNode taskIdArr, String promptId) {
        for (JsonNode n : taskIdArr) {
            String taskId = n.asText("");
            if (taskId.isEmpty()) continue;
            try {
                JsonNode poll = newApiClient.waitForVideo(taskId, videoPollTimeoutSec);
                String url = newApiClient.extractVideoUrl(poll);
                if (url == null || url.isEmpty()) {
                    log.warn("NewAPI 视频 {} 无 URL", taskId);
                    continue;
                }
                // 下载 + 上传到 MinIO
                String filename = taskId + ".mp4";
                try (InputStream is = new URI(url).toURL().openStream()) {
                    String objectKey = STORAGE_PREFIX + "/" + promptId + "/" + filename;
                    String uploaded = storageService.uploadObject(objectKey, is, "video/mp4");
                    log.info("Video downloaded from NewAPI → MinIO: taskId={}, url={}", taskId, uploaded);
                    return uploaded;
                }
            } catch (Exception e) {
                log.warn("waitAndDownloadFromNewApi 失败 {}: {}", taskId, e.getMessage());
            }
        }
        return null;
    }

    /** 把上游图片 URL 下载下来 → 上传到 ComfyUI input 目录 → 返回 ComfyUI 给的 filename
     *
     * 注意：filename 只能取纯文件名，不能带 presigned URL 的 query string，
     * 否则 ComfyUI 写入文件时会报 OSError: [Errno 36] File name too long
     * (Linux 文件名限制 255 字节，presigned URL 通常 300+ 字节)
     */
    private String uploadImageToComfyUiInput(String imageUrl) {
        try (InputStream is = new URI(imageUrl).toURL().openStream()) {
            byte[] data = is.readAllBytes();
            // 从 URL 末尾取文件名, 切掉 ? 后面的 query string
            String fullName = imageUrl.contains("/")
                ? imageUrl.substring(imageUrl.lastIndexOf('/') + 1)
                : "canvas_input_" + System.currentTimeMillis();
            String originalName = fullName.contains("?")
                ? fullName.substring(0, fullName.indexOf('?'))
                : fullName;
            String mime = inferContentType(originalName, "image");
            String comfyFilename = comfyUIClient.uploadImage(data, originalName, mime);
            log.info("Uploaded image to ComfyUI input: url={} → filename={}", imageUrl, comfyFilename);
            return comfyFilename;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "上游图片上传到 ComfyUI 失败: " + e.getMessage());
        }
    }

    /** MIME 推断 */
    private String inferContentType(String filename, String mediaKind) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        return "application/octet-stream";
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms);} catch (InterruptedException e) {Thread.currentThread().interrupt();}
    }

    /**
     * 从 ComfyUI history entry 里抠出真正的错误原因
     * 优先级：status.exec_info.exception_message > status.messages > 整条 entry
     */
    private String extractComfyUiError(JsonNode entry) {
        JsonNode status = entry.get("status");
        if (status != null) {
            // 1. exec_info 里的 Python 异常（最重要）
            JsonNode execInfo = status.get("exec_info");
            if (execInfo != null && execInfo.isObject()) {
                String exType = execInfo.path("exception_type").asText("");
                String exMsg = execInfo.path("exception_message").asText("");
                // traceback 通常包含真正异常信息
                JsonNode tb = execInfo.get("traceback");
                String tbText = (tb != null && tb.isArray()) ? tb.toString() : "";

                if (exMsg != null && !exMsg.isBlank()) {
                    return "ComfyUI " + (exType.isEmpty() ? "error" : exType) + ": " + exMsg;
                }
                if (!tbText.isEmpty() && !"[]".equals(tbText) && !"null".equals(tbText)) {
                    // traceback 在后端日志里打印完整
                    log.error("ComfyUI workflow traceback: {}", tbText);
                    String shortTb = tbText.length() > 1500 ? tbText.substring(0, 1500) + "..." : tbText;
                    return "ComfyUI " + (exType.isEmpty() ? "error" : exType) + " (traceback 过长看后端日志): " + shortTb;
                }
                // exec_info 存在但内容空：返回 exec_info 整个对象
                String exInfoJson = execInfo.toString();
                if (exInfoJson.length() > 5 && !"{}".equals(exInfoJson)) {
                    return "ComfyUI exec: " + (exInfoJson.length() > 800 ? exInfoJson.substring(0, 800) + "..." : exInfoJson);
                }
            }
            // 2. messages 数组（节点报错日志）
            JsonNode messages = status.get("messages");
            if (messages != null && messages.isArray()) {
                StringBuilder sb = new StringBuilder("ComfyUI error:");
                for (JsonNode msg : messages) {
                    if (msg.isArray() && msg.size() >= 2) {
                        String type = msg.get(0).asText("");
                        if (type.contains("error") || type.contains("execution_error")) {
                            sb.append(" [").append(type).append("] ").append(msg.get(1).asText()).append(";");
                        }
                    }
                }
                String s = sb.toString();
                if (!s.equals("ComfyUI error:")) return s;
            }
        }
        // 3. 兜底：返回整条 entry（限制 800 字）
        String json = entry.toString();
        return "ComfyUI workflow error: " + (json.length() > 800 ? json.substring(0, 800) + "..." : json);
    }

    /**
     * 图生视频 prompt 增强器（仿 jurong-api-nodes/image_to_video.py 的 _enhance_prompt）
     *
     * 关键约束：模型必须复刻参考图里的人物/物体外观，
     * 禁止改变性别/年龄/服装/发型/体型/肤色，
     * 只动画作和镜头运动。
     *
     * 如果用户 prompt 里已经包含"保持原图/preserve/lock"等关键词，就跳过
     * 避免重复强调。
     */
    private String enhanceVideoPrompt(String prompt) {
        if (prompt == null) prompt = "";
        String lower = prompt.toLowerCase();
        String[] existingKeywords = {
            "same as reference", "保持原图", "保持", "preserve",
            "consistent", "exact same", "identical",
            "same person", "same face", "maintain",
            "锁定", "不要改变", "do not change",
        };
        for (String k : existingKeywords) {
            if (lower.contains(k)) {
                return prompt;
            }
        }

        String enhancer =
            "CRITICAL: The subject(s) shown in the reference image MUST appear "
            + "EXACTLY as in the reference \u2014 same face, same gender, same age, "
            + "same hairstyle and hair color, same clothing, same body type, "
            + "same skin tone, same accessories. Do NOT replace, swap, gender-swap, "
            + "or alter the subject's identity in any way. "
            + "Only animate the actions, expressions, and camera movement described above. "
            + "Preserve the exact composition, color palette, lighting, and visual style "
            + "of the reference image throughout the entire video. "
            + "Lock the first frame as the visual anchor.";
        String trimmed = prompt.trim();
        // 去掉末尾的句号（如果有），避免 double punctuation
        while (trimmed.endsWith(".") || trimmed.endsWith("\u3002")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed + ". " + enhancer;
    }
}