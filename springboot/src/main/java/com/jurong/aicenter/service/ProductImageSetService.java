package com.jurong.aicenter.service;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.client.NewApiClient;
import com.jurong.aicenter.dto.media.MediaAssetResponse;
import com.jurong.aicenter.dto.productimage.CreateProductImageTaskRequest;
import com.jurong.aicenter.dto.productimage.ProductImageAnalysisResponse;
import com.jurong.aicenter.dto.productimage.ProductImageTaskResponse;
import com.jurong.aicenter.dto.productimage.RefineAnalysisItemRequest;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 商详套图服务。
 * <p>
 * 功能参考 da-ai.cc 的「一张图生成一套商品详情图」：
 * 用户上传 1~5 张商品图，选择语言与张数，后端按商详图类型（主视图/卖点图/细节图/成分图/场景图/结构图/对比图/包装图/收尾图）
 * 逐张循环调用图生图链路（NewApiClient.editImage，走聚融中转站手册 v3.0 的
 * 素材库两段式协议），生成结果入 MinIO 并写入「我的资产」（media_assets，不进「AI 生成结果」库）后返回 URL 列表。
 * <p>
 * 任务态保存在内存（ConcurrentHashMap），前端创建后轮询 GET /tasks/{id}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImageSetService {

    private final NewApiClient newApiClient;
    private final MediaService mediaService;
    private final StorageService storageService;

    /** 商品详解分析用的多模态模型（与 llm.model 一致：NewAPI 实际可用的 claude-sonnet-4-6） */
    @Value("${product-image.analysis-model:claude-sonnet-4-6}")
    private String analysisModel;

    /** NewAPI 基础地址：下载生成结果超时/不可达时，把 URL host 改写为此地址重试（与 AI 图片链路同策略） */
    @Value("${newapi.base-url:}")
    private String newapiBaseUrl;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 任务执行线程池：最多同时跑 2 个套图任务，任务内部逐张串行（避免上游限流） */
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, new java.util.concurrent.ThreadFactory() {
        private final AtomicInteger seq = new AtomicInteger();
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "product-image-set-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    /** 内存任务表：taskId → 任务态 */
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();

    /** 内存分析任务表：taskId → 分析态（商品详解，LLM 多模态生成） */
    private final Map<String, AnalysisState> analysisTasks = new ConcurrentHashMap<>();

    /** 单张图生成失败自动重试的最大尝试次数（含首次，与 AI 图片前端重试策略对齐） */
    private static final int MAX_ATTEMPTS_PER_IMAGE = 3;
    /** 商品详解分析（多模态 LLM）超时/上游异常时自动重试的最大尝试次数（含首次） */
    private static final int MAX_ATTEMPTS_PER_ANALYSIS = 2;
    /** 自动重试前的等待时间（毫秒） */
    private static final long RETRY_DELAY_MS = 1500;

    /** 商详套图逐张的图片类型：名称 + 生成要点（超过张数时循环取用） */
    private record Role(String name, String desc) {}

    private static final List<Role> ROLES = List.of(
        new Role("主视图", "产品整体展示 + 核心卖点标题，建立第一印象"),
        new Role("卖点图", "核心卖点分点呈现，图文结合突出价值主张"),
        new Role("细节图", "材质与工艺特写，突出质感"),
        new Role("成分图", "成分或材质可视化，溯源建立信任"),
        new Role("场景图", "产品融入真实生活场景，激发购买联想"),
        new Role("结构图", "拆解或剖面视角，展示产品内部结构与工艺"),
        new Role("对比图", "使用前/后或同类对比，突出差异化优势"),
        new Role("包装图", "包装与配件细节，塑造超值感与仪式感"),
        new Role("收尾图", "资质背书与购买引导收尾，促成转化")
    );

    /**
     * 创建套图任务并异步开始生成。
     */
    public ProductImageTaskResponse createTask(Long userId, CreateProductImageTaskRequest req) {
        List<String> images = req.getImages() == null ? List.of()
            : req.getImages().stream().filter(s -> s != null && !s.isBlank()).toList();
        if (images.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "请至少提供一张商品图");
        }
        if (images.size() > 5) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "商品图最多 5 张");
        }

        int count = parseCount(req.getCount());
        // 分析卡片「立即生成」：传了完整设计分析文案 → 单张生成模式（忽略 count 与默认角色循环）
        String customPrompt = req.getPrompt() == null ? "" : req.getPrompt().trim();
        boolean singleMode = !customPrompt.isBlank();
        if (singleMode) {
            count = 1;
        }
        final int finalCount = count;
        boolean premium = !"standard".equals(req.getModelKey());
        String model = premium ? "gpt-image-2-2k" : "gpt-image-2-1k";
        long creditsCost = premium ? 200 : 100;
        String size = mapSize(req.getResolution());
        String langText = req.getLang() != null && req.getLang().contains("中") ? "中文" : "英文";
        String brief = req.getBrief() == null ? "" : req.getBrief().trim();

        TaskState st = new TaskState();
        st.taskId = "pit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        st.userId = userId;
        st.status = "running";
        st.creditsCost = creditsCost;
        st.createdAt = System.currentTimeMillis();
        // 预览只放 http(s) URL（data URI 过大不进响应）
        st.previewUrls = images.stream()
            .filter(s -> s.startsWith("http://") || s.startsWith("https://"))
            .toList();
        tasks.put(st.taskId, st);

        log.info("[PI-SET] 任务已创建: taskId={}, userId={}, 商品图={}张, count={}, lang={}, model={}, size={}, briefLen={}, singleMode={}",
            st.taskId, userId, images.size(), count, langText, model, size, brief.length(), singleMode);

        EXECUTOR.submit(() -> runTask(st, userId, images, finalCount, langText, brief, model, size,
            singleMode ? customPrompt : null, singleMode ? req.getRole() : null));
        return toResponse(st);
    }

    /**
     * 批量删除任务：从内存任务表移除任务态，并把该任务生成的结果图从 MinIO 直接删除，
     * 同步清理对应的个人资产记录（media_assets），物理删除不可恢复。
     *
     * @return 实际删除的任务数
     */
    public int batchDeleteTasks(Long userId, List<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        List<String> objectKeys = new ArrayList<>();
        for (String taskId : taskIds) {
            if (taskId == null || taskId.isBlank()) {
                continue;
            }
            TaskState st = tasks.remove(taskId);
            if (st != null) {
                deleted++;
                objectKeys.addAll(st.objectKeys);
            }
        }
        for (String key : objectKeys) {
            try {
                storageService.deleteFile(key);
            } catch (Exception e) {
                log.warn("[PI-SET] 删除生成结果文件失败: objectKey={}, err={}", key, e.getMessage());
            }
        }
        // 同步清理个人资产记录，避免「我的资产」里残留已删图的记录
        if (!objectKeys.isEmpty()) {
            try {
                mediaService.deleteAssetsByObjectKeys(userId, objectKeys);
            } catch (Exception e) {
                log.warn("[PI-SET] 清理生成图资产记录失败: err={}", e.getMessage());
            }
        }
        log.info("[PI-SET] 批量删除任务: userId={}, requested={}, deleted={}, 清理生成图={}张",
            userId, taskIds.size(), deleted, objectKeys.size());
        return deleted;
    }

    /** 查询任务状态（不存在抛 NOT_FOUND）。 */
    public ProductImageTaskResponse getTask(String taskId) {
        TaskState st = tasks.get(taskId);
        if (st == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "套图任务不存在: " + taskId);
        }
        return toResponse(st);
    }

    /** 查询当前用户的任务列表（内存任务态，新任务在前）：页面重新进入时恢复任务队列与生成结果 */
    public List<ProductImageTaskResponse> listTasks(Long userId) {
        return tasks.values().stream()
            .filter(st -> java.util.Objects.equals(st.userId, userId))
            .sorted(Comparator.comparingLong((TaskState st) -> st.createdAt).reversed())
            .map(this::toResponse)
            .toList();
    }

    /** 套图类型名称列表（前端分析卡片的类型下拉选项） */
    public List<String> listRoleNames() {
        return ROLES.stream().map(Role::name).toList();
    }

    /**
     * 创建商品详解分析任务并异步执行。
     * <p>
     * 多模态 LLM（chatCompletionWithImages）结合用户上传的商品图，逐张输出商详图设计分析
     * （画布尺寸/引用图/锁定主体/画面环境/画面文字/负面约束），文案语言 = 用户选择的语种。
     */
    public ProductImageAnalysisResponse createAnalysis(Long userId, CreateProductImageTaskRequest req) {
        List<String> images = req.getImages() == null ? List.of()
            : req.getImages().stream().filter(s -> s != null && !s.isBlank()).toList();
        if (images.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "请至少提供一张商品图");
        }
        if (images.size() > 5) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "商品图最多 5 张");
        }
        int count = parseCount(req.getCount());
        String langText = req.getLang() != null && req.getLang().contains("中") ? "中文" : "英文";
        String brief = req.getBrief() == null ? "" : req.getBrief().trim();

        AnalysisState st = new AnalysisState();
        st.taskId = "pia_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        st.status = "running";
        st.createdAt = System.currentTimeMillis();
        analysisTasks.put(st.taskId, st);

        log.info("[PI-ANALYSIS] 分析任务已创建: taskId={}, userId={}, 商品图={}张, count={}, lang={}, model={}",
            st.taskId, userId, images.size(), count, langText, analysisModel);

        EXECUTOR.submit(() -> runAnalysis(st, images, count, langText, brief, req.getResolution()));
        return toAnalysisResponse(st);
    }

    /** 查询分析任务状态（不存在抛 NOT_FOUND） */
    public ProductImageAnalysisResponse getAnalysis(String taskId) {
        AnalysisState st = analysisTasks.get(taskId);
        if (st == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "分析任务不存在: " + taskId);
        }
        return toAnalysisResponse(st);
    }

    /**
     * 单条分析文案重写（同步）：分析卡片修改「定位/比例」后点「重新生成」，
     * 先按新定位与画布比例让多模态 LLM 重写该条设计分析，前端拿到新文案后再提交图片生成。
     */
    public ProductImageAnalysisResponse.AnalysisItem refineAnalysisItem(Long userId, RefineAnalysisItemRequest req) {
        if (req.getImage() == null || req.getImage().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "缺少引用图");
        }
        String roleName = req.getRole() == null || req.getRole().isBlank() ? "主视图" : req.getRole().trim();
        String roleDesc = ROLES.stream()
            .filter(r -> r.name().equals(roleName))
            .map(Role::desc)
            .findFirst().orElse("");
        String ratio = req.getRatio() == null || req.getRatio().isBlank() ? "1:1" : req.getRatio().trim();
        String langText = req.getLang() != null && req.getLang().contains("中") ? "中文" : "英文";
        boolean en = "英文".equals(langText);
        String brief = req.getBrief() == null ? "" : req.getBrief().trim();
        String refLabel = req.getRefLabel() == null || req.getRefLabel().isBlank() ? "@图片1" : req.getRefLabel().trim();
        String canvas = canvasFor(ratio, req.getResolution());

        StringBuilder sb = new StringBuilder();
        sb.append("你是顶级电商视觉策划师。任务分两步：\n");
        sb.append("第一步【商品识别】：仔细观察商品图（记为 ").append(refLabel)
          .append("），识别出其中的商品：品类与用途、品牌名/logo 文字、材质、颜色、")
          .append("结构细节（部件/工艺/纹理）、包装或标签上的可见文字。")
          .append("识别结果不要单独输出，直接融入第二步的内容中。\n");
        sb.append("第二步【设计分析】：基于第一步识别出的商品信息，为一张商详图输出设计分析。")
          .append("主题：").append(roleName);
        if (!roleDesc.isBlank()) {
            sb.append("（").append(roleDesc).append("）");
        }
        sb.append("，引用图：").append(refLabel).append("\n");
        if (!brief.isBlank()) {
            sb.append("用户补充要求：").append(brief).append("\n");
        }
        sb.append("画布为 ").append(canvas).append("（").append(ratio).append("）。\n");
        sb.append("输出一个 JSON 对象，结构：{\"sections\": [{\"key\": \"...\", \"value\": \"...\"}]}。")
          .append("sections 固定 6 项，key 依次为：");
        if (en) {
            sb.append("Canvas Size, Reference Image, Lock Subject, Scene Environment, On-image Text, Negative Constraints。");
        } else {
            sb.append("画布尺寸、引用图、锁定主体、画面环境、画面文字、负面约束。");
        }
        sb.append("所有 value 使用").append(langText)
          .append("，锚定第一步识别出的具体信息：画布尺寸说明资产类型与尺寸并关联商品品类；")
          .append("引用图列出对应 ").append(refLabel).append(" 中需参照的具体视觉元素；")
          .append("锁定主体逐项列出必须 100% 还原的产品细节；")
          .append("画面环境给出与商品品类及主题定位匹配的实拍级场景、灯光与背景；")
          .append("画面文字给出图中应出现的品牌/型号文字与排版位置；")
          .append("负面约束列出禁止改动的细节与需避免的画面问题。")
          .append("每条 value 一句连贯整洁的话（70 字以内），不用 markdown 符号。")
          .append("直接输出 JSON 对象本身：不要任何分析过程、解释、标题或 ``` 代码块标记。");

        log.info("[PI-REFINE] 单条分析重写开始: userId={}, role={}, ratio={}, lang={}",
            userId, roleName, ratio, langText);
        try {
            String content = newApiClient.chatCompletionWithImages(
                analysisModel, null, sb.toString(), List.of(req.getImage()), 2000);
            List<ProductImageAnalysisResponse.Section> sections = null;
            List<List<ProductImageAnalysisResponse.Section>> parsed = parseAnalysisSections(content);
            if (parsed != null) {
                sections = parsed.stream().filter(l -> !l.isEmpty()).findFirst().orElse(null);
            }
            if (sections == null || sections.isEmpty()) {
                // 无锚点时直接宽松扫描对象；仍失败则回退原文单条，避免前端空卡片
                sections = extractSectionObjects(sanitizeJsonText(content == null ? "" : content));
            }
            if (sections.isEmpty()) {
                sections = List.of(new ProductImageAnalysisResponse.Section(
                    en ? "Analysis" : "分析", content == null ? "" : sanitizeJsonText(content)));
            }
            log.info("[PI-REFINE] 单条分析重写完成: userId={}, role={}, sections={}", userId, roleName, sections.size());
            return new ProductImageAnalysisResponse.AnalysisItem(refLabel, roleName, ratio, sections);
        } catch (Exception e) {
            log.error("[PI-REFINE] 单条分析重写失败: userId={}, role={}, err={}", userId, roleName, e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "分析文案重写失败: " + e.getMessage());
        }
    }

    /** 画布比例 + 分辨率 → 画布像素尺寸描述（分析文案的画布尺寸项用） */
    private String canvasFor(String ratio, String resolution) {
        String res = resolution == null ? "1K" : resolution.toUpperCase();
        if ("4:5".equals(ratio)) {
            return switch (res) {
                case "2K" -> "2048x2560";
                case "4K" -> "2880x3600";
                default -> "1024x1280";
            };
        }
        return switch (res) {
            case "2K" -> "2048x2048";
            case "4K" -> "2880x2880";
            default -> "1200x1200";
        };
    }

    // ==================== 内部实现 ====================

    /** 逐张循环图生图：与 AI 图片生成(带引用图)同一链路，只是按套图类型循环多次；
     *  customPrompt 非空时为单张模式：用分析卡片传入的完整设计分析作为提示词 */
    private void runTask(TaskState st, Long userId, List<String> images, int count,
                         String langText, String brief, String model, String size,
                         String customPrompt, String customRole) {
        int failed = 0;
        String lastError = null;
        for (int i = 0; i < count; i++) {
            Role role;
            String prompt;
            String ref;
            if (customPrompt != null) {
                // 分析卡片「立即生成」：文案来自 LLM 分析结果，引用图固定第一张（前端已按条目传对应图）
                // 定位以用户在下拉框选择的类型为准（可能与原分析文案的定位不同）
                String roleName = customRole == null || customRole.isBlank() ? "主视图" : customRole;
                String roleDesc = ROLES.stream()
                    .filter(r -> r.name().equals(roleName))
                    .map(Role::desc)
                    .findFirst().orElse("");
                role = new Role(roleName, roleDesc);
                prompt = buildSinglePrompt(customPrompt, langText, roleName, roleDesc);
                ref = images.get(0);
            } else {
                role = ROLES.get(i % ROLES.size());
                prompt = buildPrompt(role.name() + "：" + role.desc(), i + 1, count, langText, brief);
                // 引用图轮转使用（多图时每张生成图换一张参考）
                ref = images.get(i % images.size());
            }
            try {
                boolean ok = false;
                Exception last = null;
                for (int attempt = 1; attempt <= MAX_ATTEMPTS_PER_IMAGE && !ok; attempt++) {
                    try {
                        log.info("[PI-SET] 生成第 {}/{} 张（尝试 {}/{}）: taskId={}, role={}",
                            i + 1, count, attempt, MAX_ATTEMPTS_PER_IMAGE, st.taskId, role.name());
                        String result = newApiClient.editImage(prompt, List.of(ref), size, null, null, model);
                        byte[] bytes = toImageBytes(result);
                        String mimeType = result.startsWith("data:image/jpeg") ? "image/jpeg" : "image/png";
                        // 生成结果入个人资产：MinIO + media_assets（「我的资产」库，不进「AI 生成结果」库）
                        MediaAssetResponse saved = mediaService.recordProductImageAsset(userId, bytes, mimeType);
                        st.objectKeys.add(saved.getObjectKey());
                        st.imageUrls.add(saved.getUrl());
                        st.imageRoles.add(role.name());
                        log.info("[PI-SET] 第 {}/{} 张完成: taskId={}, url={}", i + 1, count, st.taskId, saved.getUrl());
                        ok = true;
                    } catch (Exception e) {
                        last = e;
                        log.warn("[PI-SET] 第 {}/{} 张第 {} 次尝试失败: taskId={}, err={}",
                            i + 1, count, attempt, st.taskId, e.getMessage());
                        // 还有剩余尝试次数：等待后自动重新生成（不中断任务）
                        if (attempt < MAX_ATTEMPTS_PER_IMAGE) {
                            try {
                                Thread.sleep(RETRY_DELAY_MS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw e;
                            }
                        }
                    }
                }
                if (!ok) {
                    throw last != null ? last : new BusinessException(ErrorCode.INTERNAL_ERROR, "生成失败");
                }
            } catch (Exception e) {
                failed++;
                lastError = e.getMessage();
                log.error("[PI-SET] 第 {}/{} 张失败（已重试 {} 次）: taskId={}, err={}",
                    i + 1, count, MAX_ATTEMPTS_PER_IMAGE - 1, st.taskId, e.getMessage());
            }
        }

        if (st.imageUrls.isEmpty()) {
            st.status = "failed";
            st.failReason = lastError != null ? lastError : "所有图片生成失败";
        } else {
            st.status = "success";
            if (failed > 0) {
                st.failReason = "成功生成 " + st.imageUrls.size() + " 张，" + failed + " 张失败";
            }
        }
        log.info("[PI-SET] 任务结束: taskId={}, status={}, 成功={}张, 失败={}张",
            st.taskId, st.status, st.imageUrls.size(), failed);
    }

    /** 商品详解分析：单次调用拿到全部分析文本，再按 "sections" 锚点逐张截取拼接成卡片 */
    private void runAnalysis(AnalysisState st, List<String> images, int count,
                             String langText, String brief, String resolution) {
        try {
            String res = resolution == null ? "1K" : resolution.toUpperCase();
            String canvas = switch (res) {
                case "2K" -> "2048x2048";
                case "4K" -> "2880x2880";
                default -> "1200x1200";
            };
            String ratio = "1:1";
            boolean en = "英文".equals(langText);

            StringBuilder sb = new StringBuilder();
            sb.append("你是顶级电商视觉策划师。任务分两步：\n");
            sb.append("第一步【商品识别】：仔细观察每张商品图（按顺序记为 @图片1");
            if (images.size() > 1) {
                sb.append(" ~ @图片").append(images.size());
            }
            sb.append("），识别出其中的商品：品类与用途、品牌名/logo 文字、材质、颜色、")
              .append("结构细节（部件/工艺/纹理）、包装或标签上的可见文字。")
              .append("识别结果不要单独输出，直接融入第二步的内容中。\n");
            sb.append("第二步【设计分析】：基于第一步识别出的商品信息，为以下 ").append(count)
              .append(" 张商详图逐张输出设计分析。\n");
            for (int i = 0; i < count; i++) {
                Role role = ROLES.get(i % ROLES.size());
                int refIdx = i % images.size() + 1;
                sb.append(i + 1).append(". 主题：").append(role.name())
                  .append("（").append(role.desc()).append("），引用图：@图片").append(refIdx).append("\n");
            }
            if (!brief.isBlank()) {
                sb.append("用户补充要求：").append(brief).append("\n");
            }
            sb.append("画布为 ").append(canvas).append("（").append(ratio).append("）。\n");
            sb.append("输出一个 JSON 数组，按上面顺序每张一个元素，元素结构：")
              .append("{\"sections\": [{\"key\": \"...\", \"value\": \"...\"}]}。")
              .append("sections 固定 6 项，key 依次为：");
            if (en) {
                sb.append("Canvas Size, Reference Image, Lock Subject, Scene Environment, On-image Text, Negative Constraints。");
            } else {
                sb.append("画布尺寸、引用图、锁定主体、画面环境、画面文字、负面约束。");
            }
            sb.append("所有 value 使用").append(langText)
              .append("，锚定第一步识别出的具体信息：画布尺寸说明资产类型与尺寸并关联商品品类；")
              .append("引用图列出对应 @图片N 中需参照的具体视觉元素；")
              .append("锁定主体逐项列出必须 100% 还原的产品细节；")
              .append("画面环境给出与商品品类匹配的实拍级场景、灯光与背景；")
              .append("画面文字给出图中应出现的品牌/型号文字与排版位置；")
              .append("负面约束列出禁止改动的细节与需避免的画面问题。")
              .append("每条 value 一句连贯整洁的话（70 字以内），不用 markdown 符号。")
              .append("直接输出 JSON 数组本身：不要任何分析过程、解释、标题或 ``` 代码块标记。")
              .append("务必输出全部 ").append(count).append(" 个元素，宁可每条更精炼也不要中途截断。");

            int maxTokens = Math.min(20000, 1600 * count);
            // 多模态分析耗时长且上游偶发超时：失败自动重试（与图片生成链路同策略），全部失败才标记任务失败
            String content = null;
            Exception lastErr = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS_PER_ANALYSIS && content == null; attempt++) {
                try {
                    log.info("[PI-ANALYSIS] 多模态分析调用（尝试 {}/{}）: taskId={}, images={}, maxTokens={}",
                        attempt, MAX_ATTEMPTS_PER_ANALYSIS, st.taskId, images.size(), maxTokens);
                    content = newApiClient.chatCompletionWithImages(
                        analysisModel, null, sb.toString(), images, maxTokens);
                } catch (Exception e) {
                    lastErr = e;
                    log.warn("[PI-ANALYSIS] 第 {} 次尝试失败: taskId={}, err={}", attempt, st.taskId, e.getMessage());
                    if (attempt < MAX_ATTEMPTS_PER_ANALYSIS) {
                        try {
                            Thread.sleep(RETRY_DELAY_MS * 2);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            if (content == null) {
                throw lastErr != null ? lastErr : new BusinessException(ErrorCode.INTERNAL_ERROR, "分析失败");
            }

            // 按 "sections" 锚点逐张截取解析；全失败才回退原文到第一张卡
            List<List<ProductImageAnalysisResponse.Section>> parsed = parseAnalysisSections(content);
            List<ProductImageAnalysisResponse.AnalysisItem> items = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Role role = ROLES.get(i % ROLES.size());
                String refLabel = "@图片" + (i % images.size() + 1);
                List<ProductImageAnalysisResponse.Section> sections;
                if (parsed != null && i < parsed.size() && !parsed.get(i).isEmpty()) {
                    sections = parsed.get(i);
                } else if (parsed == null && i == 0) {
                    // LLM 输出完全无法解析：原文（去掉代码块标记）放第一张卡片，避免全空
                    sections = List.of(new ProductImageAnalysisResponse.Section(
                        en ? "Analysis" : "分析", content == null ? "" : sanitizeJsonText(content)));
                } else {
                    sections = List.of();
                }
                items.add(new ProductImageAnalysisResponse.AnalysisItem(refLabel, role.name(), ratio, sections));
            }
            st.items = items;
            st.status = "success";
            log.info("[PI-ANALYSIS] 分析完成: taskId={}, items={}, parsed={}",
                st.taskId, items.size(), parsed == null ? "null(回退原文)" : String.valueOf(parsed.size()));
        } catch (Exception e) {
            st.status = "failed";
            st.failReason = e.getMessage();
            log.error("[PI-ANALYSIS] 分析失败: taskId={}, err={}", st.taskId, e.getMessage());
        }
    }

    /**
     * 把 LLM 返回的整段文本按 "sections" 锚点逐张截取：第 k 张的区间 = 第 k 个锚点到第 k+1 个锚点；
     * 段内不解析整段数组，而是逐个扫描 {...} 对象单独解析（任一对象损坏只丢它自己）。
     * 全部失败返回 null。
     */
    private List<List<ProductImageAnalysisResponse.Section>> parseAnalysisSections(String content) {
        if (content == null || content.isBlank()) return null;
        String clean = sanitizeJsonText(content);
        List<Integer> anchors = new ArrayList<>();
        for (int idx = clean.indexOf("\"sections\""); idx >= 0; idx = clean.indexOf("\"sections\"", idx + 1)) {
            anchors.add(idx);
        }
        if (anchors.isEmpty()) {
            log.warn("[PI-ANALYSIS] 输出中未找到 sections 锚点，回退为原文展示");
            return null;
        }
        List<List<ProductImageAnalysisResponse.Section>> result = new ArrayList<>();
        for (int i = 0; i < anchors.size(); i++) {
            int from = anchors.get(i);
            int to = i + 1 < anchors.size() ? anchors.get(i + 1) : clean.length();
            String segment = clean.substring(from, to);
            List<ProductImageAnalysisResponse.Section> sections = extractSectionObjects(segment);
            if (sections.isEmpty()) {
                log.warn("[PI-ANALYSIS] 第 {}/{} 段未解析出任何项，段内容前300字：{}",
                    i + 1, anchors.size(), segment.substring(0, Math.min(300, segment.length())));
            }
            result.add(sections);
        }
        boolean any = result.stream().anyMatch(l -> !l.isEmpty());
        if (!any) {
            log.warn("[PI-ANALYSIS] 截取到 {} 段但均解析失败，回退为原文展示；全文前500字：{}",
                anchors.size(), clean.substring(0, Math.min(500, clean.length())));
            return null;
        }
        return result;
    }

    /** 在片段内逐个扫描 {"key":...,"value":...} 对象（按大括号配对截取），每个对象独立宽松解析 */
    private List<ProductImageAnalysisResponse.Section> extractSectionObjects(String segment) {
        List<ProductImageAnalysisResponse.Section> sections = new ArrayList<>();
        int pos = segment.indexOf('{');
        while (pos >= 0) {
            int depth = 0;
            boolean inStr = false;
            boolean esc = false;
            int end = -1;
            for (int i = pos; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (inStr) {
                    if (esc) {
                        esc = false;
                    } else if (c == '\\') {
                        esc = true;
                    } else if (c == '"') {
                        inStr = false;
                    }
                } else {
                    if (c == '"') {
                        inStr = true;
                    } else if (c == '{') {
                        depth++;
                    } else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            end = i;
                            break;
                        }
                    }
                }
            }
            if (end < 0) {
                // 对象未闭合（输出被截断）：后面的都不要了
                break;
            }
            String obj = segment.substring(pos, end + 1);
            try {
                JsonNode node = LENIENT_MAPPER.readTree(obj);
                String key = node.path("key").asText("");
                String value = node.path("value").asText("");
                if (!key.isBlank() || !value.isBlank()) {
                    sections.add(new ProductImageAnalysisResponse.Section(key, value));
                }
            } catch (Exception ignored) {
                // 单个对象损坏只丢它自己，继续扫下一个
            }
            pos = segment.indexOf('{', end + 1);
        }
        return sections;
    }

    /** 宽松 JSON 解析器：容忍尾逗号/单引号/未加引号字段名/注释等 LLM 常见小毛病 */
    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
        .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true)
        .configure(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature(), true)
        .configure(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(), true)
        .configure(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature(), true);

    /** 清洗 LLM 输出：去掉 markdown 代码块标记（尾逗号交给宽松解析器，不在此全局替换以免误伤正文） */
    private String sanitizeJsonText(String text) {
        String t = text.replaceAll("```(?:json)?", "").replace("```", "");
        return t.trim();
    }

    private ProductImageAnalysisResponse toAnalysisResponse(AnalysisState st) {
        return new ProductImageAnalysisResponse(
            st.taskId,
            st.status,
            st.items,
            st.createdAt,
            st.failReason
        );
    }

    /** 单张模式提示词包装：把分析卡片的完整设计分析文案包进生成指令（与 AI 图片生成同一链路）；
     *  用户修改过定位时，把新定位及其设计要点写进提示词，并要求定位优先于分析文案中的冲突描述 */
    private String buildSinglePrompt(String analysisText, String langText, String roleName, String roleDesc) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是顶级电商视觉设计师。请严格按照以下设计分析生成一张电商商品详情图，页面文案使用").append(langText).append("。");
        if (roleName != null && !roleName.isBlank()) {
            sb.append("本张图片的主题定位是【").append(roleName).append("】");
            if (roleDesc != null && !roleDesc.isBlank()) {
                sb.append("（").append(roleDesc).append("）");
            }
            sb.append("，请按该主题定位设计画面构图、版式与文案；若下方设计分析中有与该主题定位不一致的内容，以该主题定位为准。");
        }
        sb.append("要求：严格保持参考图中商品外观一致；商业摄影级画质；构图简洁专业、适合电商详情页直接上架。\n")
          .append("【设计分析】\n").append(analysisText);
        return sb.toString();
    }

    /** 组装单张商详图的提示词 */
    private String buildPrompt(String role, int index, int total, String langText, String brief) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是顶级电商视觉设计师。请基于参考图中的商品，为电商商品详情页生成一套商详图中的第 ")
          .append(index).append(" 张（共 ").append(total).append(" 张）。");
        sb.append("本张主题：").append(role).append("。");
        sb.append("页面文案使用").append(langText).append("。");
        if (!brief.isBlank()) {
            sb.append("用户补充要求：").append(brief).append("。");
        }
        sb.append("要求：严格保持参考图中商品外观一致；商业摄影级画质；构图简洁专业、适合电商详情页直接上架。");
        return sb.toString();
    }

    /** 解析张数："8 张" → 8；默认 8，范围 1~12 */
    private int parseCount(String countStr) {
        int count = 8;
        if (countStr != null) {
            String digits = countStr.replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                try {
                    count = Integer.parseInt(digits);
                } catch (NumberFormatException ignored) {}
            }
        }
        return Math.max(1, Math.min(12, count));
    }

    /** 分辨率 → NewAPI size 参数 */
    private String mapSize(String resolution) {
        if ("2K".equalsIgnoreCase(resolution) || "4K".equalsIgnoreCase(resolution)) {
            return "2048x2048";
        }
        return "1024x1024";
    }

    /** 生成结果统一转字节：data URI 直接解码，URL 带超时下载；
     *  下载失败时把 host 改写为配置的 newapi.base-url 重试（与 AI 图片链路同一兜底策略）；
     *  无论哪条路径，字节都必须通过图片魔数校验——上游偶发以 200 返回 HTML 兜底页，
     *  不校验会把网页存成假 PNG，任务"成功"但前端永远显示不出图 */
    private byte[] toImageBytes(String data) {
        if (data.startsWith("data:")) {
            int commaIdx = data.indexOf(',');
            if (commaIdx == -1) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "无效的 data URI");
            }
            return requireImageBytes(Base64.getDecoder().decode(data.substring(commaIdx + 1)), "data URI");
        }
        try {
            return downloadUrl(data);
        } catch (Exception e) {
            log.warn("[PI-SET] 首次下载生成结果失败: url={}, err={}", data, e.getMessage());
            String rewritten = rewriteToConfiguredNewApi(data);
            if (rewritten != null) {
                try {
                    log.info("[PI-SET] 改写 NewAPI host 后重试下载: {}", rewritten);
                    return downloadUrl(rewritten);
                } catch (Exception e2) {
                    log.error("[PI-SET] 改写 host 后仍下载失败: url={}, err={}", rewritten, e2.getMessage());
                }
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载生成结果图片失败: " + e.getMessage());
        }
    }

    /** 下载指定 URL（connect 10s / read 60s 超时）：校验 Content-Type 与图片魔数，非图片内容按失败抛出 */
    private byte[] downloadUrl(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);
        try (InputStream is = conn.getInputStream()) {
            byte[] bytes = is.readAllBytes();
            String ct = conn.getContentType();
            if (ct != null && !ct.toLowerCase().startsWith("image/") && !ct.contains("octet-stream")) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "生成结果 URL 返回非图片内容（Content-Type=" + ct + "），疑似上游兜底页: " + url);
            }
            return requireImageBytes(bytes, url);
        } finally {
            conn.disconnect();
        }
    }

    /** 图片魔数校验（PNG/JPEG/GIF/WEBP）：不是真图片直接抛异常，交给生成重试机制重新出图，
     *  避免把 HTML 兜底页等脏字节存进 MinIO 造成"任务成功但图永远裂掉" */
    private byte[] requireImageBytes(byte[] bytes, String src) {
        boolean png = bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        boolean jpeg = bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
        boolean gif = bytes.length > 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F';
        boolean webp = bytes.length > 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
            && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
        if (!(png || jpeg || gif || webp)) {
            String preview = new String(bytes, 0, Math.min(64, bytes.length), java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "生成结果不是图片（" + bytes.length + " 字节，开头: " + preview + "）: " + src);
        }
        return bytes;
    }

    /**
     * 把上游返回 URL 的 host 改写为配置的 newapi.base-url（保留原 path/query）；
     * 无法改写（配置为空/同 host/解析失败）时返回 null。与 ImageController 同一策略。
     */
    private String rewriteToConfiguredNewApi(String url) {
        if (newapiBaseUrl == null || newapiBaseUrl.isBlank()) return null;
        try {
            URI orig = new URI(url);
            URI base = new URI(newapiBaseUrl.trim());
            if (base.getHost() == null) return null;
            if (base.getHost().equalsIgnoreCase(orig.getHost()) && base.getPort() == orig.getPort()) {
                return null;
            }
            String pathAndQuery = orig.getRawPath() == null ? "" : orig.getRawPath();
            if (orig.getRawQuery() != null) pathAndQuery += "?" + orig.getRawQuery();
            return base.getScheme() + "://" + base.getHost()
                + (base.getPort() > 0 ? ":" + base.getPort() : "") + pathAndQuery;
        } catch (Exception e) {
            log.warn("[PI-SET] 改写 NewAPI host 失败: url={}, err={}", url, e.getMessage());
            return null;
        }
    }

    private ProductImageTaskResponse toResponse(TaskState st) {
        // URL 按 objectKey 每次重新签名（7 天有效）：生成时存的签名 URL 24h 过期，
        // 会导致切去其它功能隔一段时间再回来时图片加载失败；文件已持久化在 MinIO，重签即可恢复
        List<String> urls = new ArrayList<>(st.objectKeys.size());
        for (int i = 0; i < st.objectKeys.size(); i++) {
            try {
                urls.add(storageService.getPresignedUrl(st.objectKeys.get(i), 168));
            } catch (Exception e) {
                log.warn("[PI-SET] 重签生成图 URL 失败，回退生成时的 URL: objectKey={}, err={}",
                    st.objectKeys.get(i), e.getMessage());
                urls.add(i < st.imageUrls.size() ? st.imageUrls.get(i) : "");
            }
        }
        return new ProductImageTaskResponse(
            st.taskId,
            st.status,
            urls,
            new ArrayList<>(st.imageRoles),
            st.previewUrls,
            st.creditsCost,
            st.createdAt,
            st.failReason
        );
    }

    /** 内存任务态 */
    private static class TaskState {
        String taskId;
        /** 提交人（任务列表按用户过滤） */
        Long userId;
        volatile String status = "running";
        /** 该任务生成结果的 MinIO objectKey（已入个人资产；删除任务时同步删文件与资产记录） */
        final List<String> objectKeys = new CopyOnWriteArrayList<>();
        /** 生成时的签名 URL（仅作为 toResponse 重签失败时的回退） */
        final List<String> imageUrls = new CopyOnWriteArrayList<>();
        final List<String> imageRoles = new CopyOnWriteArrayList<>();
        List<String> previewUrls = List.of();
        long creditsCost;
        long createdAt;
        volatile String failReason;
    }

    /** 内存分析任务态 */
    private static class AnalysisState {
        String taskId;
        volatile String status = "running";
        volatile List<ProductImageAnalysisResponse.AnalysisItem> items = List.of();
        long createdAt;
        volatile String failReason;
    }
}
