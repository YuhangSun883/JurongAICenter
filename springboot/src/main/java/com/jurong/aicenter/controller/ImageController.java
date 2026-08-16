package com.jurong.aicenter.controller;

import com.jurong.aicenter.client.AicomingAssetsClient;
import com.jurong.aicenter.client.NewApiClient;
import com.jurong.aicenter.dto.PageResult;
import com.jurong.aicenter.dto.image.FavoriteImageRequest;
import com.jurong.aicenter.dto.image.FavoriteImageResponse;
import com.jurong.aicenter.dto.image.ImageGenerateRequest;
import com.jurong.aicenter.dto.image.ImageGenerateResponse;
import com.jurong.aicenter.dto.media.MediaAssetResponse;
import com.jurong.aicenter.dto.media.MediaListQuery;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.MediaService;
import com.jurong.aicenter.service.StorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * AI 图片生成控制器
 * 参考 da-ai.cc 网站的 AI 图片生成功能
 * 使用 gpt-image-2-2k 模型，通过 NewAPI 调用
 */
@Slf4j
@RestController
@RequestMapping("/api/images")
@RequiredArgsConstructor
public class ImageController {

    private final NewApiClient newApiClient;
    private final StorageService storageService;
    private final MediaService mediaService;
    private final AicomingAssetsClient aicomingAssetsClient;

    /**
     * 后端实际可达的 NewAPI 地址（dev: http://192.140.163.161:3000）。
     * 上游返回的图片 URL 可能带其它对外主机名（如 124.156.215.114:3000），
     * 本机不可达时改写 host 后按原 path 重试。
     */
    @Value("${newapi.base-url:}")
    private String newapiBaseUrl;

    /**
     * AI 图片生成接口
     * <p>
     * 根据用户输入的提示词，调用 NewAPI 的 gpt-image-2-2k 模型生成图片。
     * 生成的图片统一转换为 base64 data URI 格式返回，可直接在网页中显示。
     * 超时时间：5 分钟（由 NewApiClient 内部控制）。
     *
     * @param principal 当前登录用户
     * @param request   生成请求（包含 prompt、size、quality、style）
     * @return 生成的图片信息（imageUrl 为 data:image/png;base64,... 格式）
     */
    @PostMapping("/generate")
    public ImageGenerateResponse generateImage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody ImageGenerateRequest request) {
        // 鉴权检查
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }

        Long userId = principal.id();
        String prompt = request.getPrompt();
        List<String> referenceImages = request.getReferenceImages();
        boolean hasReferences = referenceImages != null && !referenceImages.isEmpty();
        int refCount = hasReferences ? referenceImages.size() : 0;

        log.info("AI 图片生成请求: userId={}, promptLen={}, size={}, quality={}, style={}, refImageCount={}",
            userId, prompt.length(), request.getSize(), request.getQuality(), request.getStyle(),
            refCount);

        // 1. 根据引用图片"张数"选择不同接口（手册 v3.0 §文生图/§图生图/§多图生图）：
        //   - 0 张 → /v1/images/generations                      （文生图，不带 image）
        //   - 1 张 → /v1/images/generations + "image" 字段      （单图生图：先上传为 asset:// 后引用）
        //   - ≥2 张 → /v1/images/edits                          （多图生图：multipart 多个 image）
        //   旧逻辑只判断 "有没有" 引用图而忽略张数，已修复 2026-08-14。
        String originalData;
        try {
            if (refCount == 0) {
                // 文生图：调用纯文本生成接口，不带 image 字段
                log.info("分支=文生图 → /v1/images/generations（无 image）");
                originalData = newApiClient.generateImage(
                    prompt,
                    null,  // imageUrl=null:文生图走无 image 字段
                    request.getSize(),
                    request.getQuality(),
                    request.getStyle()
                );
            } else if (refCount == 1) {
                // 单图生图：先把用户的引用图上传为 aicoming asset，拿到 asset_url 后
                //           传给 /v1/images/generations 的 image 字段
                log.info("分支=单图生图 → /v1/images/generations + image(asset_url)");
                String assetUrl = aicomingAssetsClient.uploadDataUriAsAssetUrl(
                    referenceImages.get(0),
                    "skill-img-gen-" + userId);
                originalData = newApiClient.generateImage(
                    prompt,
                    assetUrl,
                    request.getSize(),
                    request.getQuality(),
                    request.getStyle()
                );
            } else {
                // 多图生图：调用 /v1/images/edits（multipart 多文件）
                log.info("分支=多图生图 → /v1/images/edits（{} 个 image）", refCount);
                originalData = newApiClient.editImage(
                    prompt,
                    referenceImages,
                    request.getSize(),
                    request.getQuality(),
                    request.getStyle()
                );
            }
        } catch (Exception e) {
            log.error("NewAPI 图片生成抛出异常: {}", e.getMessage(), e);
            throw e;
        }
        if (originalData == null || originalData.isBlank()) {
            log.error("NewAPI 返回的图片信息为空");
            throw new BusinessException(ErrorCode.NEWAPI_UNREACHABLE, "图片生成返回为空");
        }
        log.info("NewAPI 返回图片信息: type={}, len={}",
            isUrlFormat(originalData) ? "URL" : "base64",
            originalData.length());

        // 2. 统一转换为 base64 data URI 格式返回
        // base64 data URI（data:image/png;base64,...）可直接在网页 <img src> 中显示
        String base64DataUri = convertToBase64DataUri(originalData);

        // 3. 入库到 media_assets（type=image, source=ai-generated, sourceTool=image）
        //    这样预览窗口的图片会进入任务队列（用户切走再回来也能看到历史）
        String savedObjectKey = null;
        try {
            int commaIdx = base64DataUri.indexOf(',');
            String mimeType = base64DataUri.startsWith("data:image/png") ? "image/png" : "image/jpeg";
            if (commaIdx != -1) {
                byte[] imageBytes = Base64.getDecoder().decode(base64DataUri.substring(commaIdx + 1));
                MediaAssetResponse saved = mediaService.recordGeneratedImage(userId, imageBytes, mimeType);
                savedObjectKey = saved.getObjectKey();
                log.info("AI 生成图片已入库: assetId={}, objectKey={}", saved.getId(), savedObjectKey);
            }
        } catch (Exception e) {
            // 入库失败不影响主流程（生成已成功），仅记录告警
            log.warn("AI 生成图片入库失败（不影响生成结果）: {}", e.getMessage());
        }

        // 4. 构造响应：imageUrl 为 base64 data URI，可直接在网页中显示
        ImageGenerateResponse response = new ImageGenerateResponse(
            base64DataUri,
            "gpt-image-2-2k",
            base64DataUri,
            savedObjectKey
        );

        // 计算图片详细属性
        String imageFormat = "png";
        int prefixLen = "data:image/png;base64,".length();
        if (base64DataUri.startsWith("data:image/jpeg")) {
            imageFormat = "jpeg";
            prefixLen = "data:image/jpeg;base64,".length();
        }
        int b64DataLen = base64DataUri.length() - prefixLen;
        // base64 编码后字节数 ≈ b64DataLen * 3 / 4
        long imageBytes = (long) (b64DataLen * 3.0 / 4);
        String imageSizeStr = imageBytes >= 1048576
            ? String.format("%.2f MB", imageBytes / 1048576.0)
            : String.format("%.2f KB", imageBytes / 1024.0);

        // 输出返回图片的详细属性值
        log.info("AI 图片生成完成: userId={}, model={}, format={}, size={}, quality={}, style={}, "
                + "imageType=base64, imageBytes={}, imageSize={}, b64DataLen={}, dataUriLen={}, originalType={}",
            userId,
            "gpt-image-2-2k",
            imageFormat,
            request.getSize(),
            request.getQuality(),
            request.getStyle(),
            imageBytes,
            imageSizeStr,
            b64DataLen,
            base64DataUri.length(),
            isUrlFormat(originalData) ? "URL" : "base64");
        return response;
    }

    /**
     * 判断字符串是否为 URL 格式（http/https 开头）
     */
    private boolean isUrlFormat(String s) {
        return s != null && (s.startsWith("http://") || s.startsWith("https://"));
    }

    /**
     * 将 NewAPI 返回的图片信息统一转换为 base64 data URI 格式。
     * <p>
     * 规则：
     * - base64 格式（data:image/...）：已经是 data URI，直接返回。
     * - URL 格式（http/https）：下载图片字节，编码为 base64 data URI 返回。
     *
     * @param originalData NewAPI 原始返回（URL 或 base64 data URI）
     * @return base64 data URI 字符串（data:image/png;base64,...），可直接在网页中显示
     */
    private String convertToBase64DataUri(String originalData) {
        // 已经是 base64 data URI 格式，直接返回
        if (originalData.startsWith("data:image/")) {
            log.info("图片已是 base64 data URI 格式，直接返回");
            return originalData;
        }

        // URL 格式：下载图片字节，编码为 base64 data URI
        try {
            log.info("开始下载中转站返回的图片 URL: {}", originalData);
            return downloadUrlToDataUri(originalData);
        } catch (Exception e) {
            // 下载失败（多为中转站返回了本机不可达的主机/端口）：
            // 先尝试把 host 改写为后端配置的 newapi.base-url 后按原 path 重试
            log.warn("图片(URL)首次下载失败: url={}, err={}", originalData, e.getMessage());
            String rewritten = rewriteToConfiguredNewApi(originalData);
            if (rewritten != null) {
                try {
                    log.info("改写 NewAPI host 后重试下载: {}", rewritten);
                    return downloadUrlToDataUri(rewritten);
                } catch (Exception e2) {
                    log.error("图片(URL)改写 host 后仍下载失败: url={}, err={}", rewritten, e2.getMessage());
                }
            }
            // 两次都失败：不阻断整个请求，回退返回原始 URL 由前端直接加载
            log.error("图片(URL)下载失败，回退返回原始 URL: url={}", originalData);
            return originalData;
        }
    }

    /**
     * 下载指定 URL 并编码为 base64 data URI（connect 10s / read 60s 超时）。
     * 下载后校验图片魔数：上游偶发以 200 返回 HTML 兜底页，不校验会把网页当图片入库。
     */
    private String downloadUrlToDataUri(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(60_000);
        try (InputStream is = conn.getInputStream()) {
            byte[] imageBytes = is.readAllBytes();
            if (!looksLikeImage(imageBytes)) {
                String preview = new String(imageBytes, 0, Math.min(64, imageBytes.length),
                    java.nio.charset.StandardCharsets.UTF_8).replaceAll("\\s+", " ");
                throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "图片 URL 返回非图片内容（" + imageBytes.length + " 字节，开头: " + preview + "）: " + url);
            }
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String dataUri = "data:image/png;base64," + base64;
            log.info("图片(URL)已转换为 base64 data URI: originalLen={}, base64Len={}",
                imageBytes.length, dataUri.length());
            return dataUri;
        } finally {
            conn.disconnect();
        }
    }

    /** 图片魔数校验（PNG/JPEG/GIF/WEBP）：拦住上游以 200 返回的 HTML 兜底页 */
    private boolean looksLikeImage(byte[] b) {
        boolean png = b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        boolean jpeg = b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
        boolean gif = b.length > 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F';
        boolean webp = b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
            && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
        return png || jpeg || gif || webp;
    }

    /**
     * 把上游返回 URL 的 host 改写为配置的 newapi.base-url（保留原 path/query）。
     * 上游 image-proxy 路径由 NewAPI 自身提供，换用后端可达的地址即可下载。
     * 无法改写（配置为空/同 host/解析失败）时返回 null。
     */
    private String rewriteToConfiguredNewApi(String url) {
        if (newapiBaseUrl == null || newapiBaseUrl.isBlank()) return null;
        try {
            URI orig = new URI(url);
            URI base = new URI(newapiBaseUrl.trim());
            if (base.getHost() == null) return null;
            // 已经是配置地址，无需改写
            if (base.getHost().equalsIgnoreCase(orig.getHost()) && base.getPort() == orig.getPort()) {
                return null;
            }
            String pathAndQuery = orig.getRawPath() == null ? "" : orig.getRawPath();
            if (orig.getRawQuery() != null) pathAndQuery += "?" + orig.getRawQuery();
            return base.getScheme() + "://" + base.getHost()
                + (base.getPort() > 0 ? ":" + base.getPort() : "") + pathAndQuery;
        } catch (Exception e) {
            log.warn("改写 NewAPI host 失败: url={}, err={}", url, e.getMessage());
            return null;
        }
    }

    // ==================== 图片收藏功能 ====================
    // 收藏 = 把"AI 生成"记录（source_tool='image'）标记为"已收藏"（source_tool='favorite'），
    // 不再额外上传/复制图片（避免 MinIO 重复存储）。

    /**
     * 收藏图片接口
     * 把已存在的 AI 生成图片的 source_tool 改为 'favorite'（不复制图片）。
     */
    @PostMapping("/favorite")
    public FavoriteImageResponse favoriteImage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody FavoriteImageRequest request) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }

        Long userId = principal.id();
        String objectKey = request.getObjectKey();
        log.info("用户 {} 收藏图片: objectKey={}", userId, objectKey);

        // 修改已有记录的 source_tool（不复制图片）
        MediaAssetResponse asset = mediaService.markAsFavorite(userId, objectKey);
        log.info("收藏图片已标记: userId={}, assetId={}, sourceTool={}, url={}",
            userId, asset.getId(), asset.getSourceTool(), asset.getUrl());

        long createdAtMs = asset.getCreatedAt() != null
            ? java.sql.Timestamp.valueOf(asset.getCreatedAt()).getTime()
            : System.currentTimeMillis();
        return new FavoriteImageResponse(
            asset.getObjectKey(),
            asset.getUrl(),
            createdAtMs
        );
    }

    /**
     * 获取用户收藏（AI 生成来源且 sourceTool=favorite 的图片资产）列表。
     * 注意：收藏 Tab 的数据直接取"我的资产"里的 ai-generated + sourceTool=favorite 图片。
     */
    @GetMapping("/favorites")
    public List<FavoriteImageResponse> getFavorites(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }

        Long userId = principal.id();
        log.info("获取用户 {} 的收藏图片列表", userId);

        // 直接从资产库过滤：type=image + source=ai-generated（收藏走的是 ai-generated 来源），
        // 前端在收藏 Tab 用 listAssets（"我的资产"下拉也能看到）。
        // 这里额外提供 /api/images/favorites 为了让前端图片工作台"收藏 Tab"独立渲染。
        MediaListQuery q = new MediaListQuery();
        q.setType("image");
        q.setPage(1);
        q.setPageSize(1000);
        PageResult<MediaAssetResponse> page = mediaService.listAssets(userId, q);
        // 收藏 Tab 仅展示被显式收藏过的图片：type=image + source=ai-generated + sourceTool=favorite
        // 预览 Tab 里的图（sourceTool=image）不会出现在收藏 Tab 中
        List<FavoriteImageResponse> result = page.getItems().stream()
            .filter(a -> "ai-generated".equals(a.getSource())
                && "image".equals(a.getType())
                && "favorite".equals(a.getSourceTool()))
            .map(a -> new FavoriteImageResponse(
                a.getObjectKey(),
                a.getUrl(),
                a.getCreatedAt() != null ? java.sql.Timestamp.valueOf(a.getCreatedAt()).getTime() : 0L
            ))
            .toList();
        log.info("用户 {} 的收藏图片: {} 张", userId, result.size());
        return result;
    }

    /**
     * 取消收藏：把 source_tool 改回 'image'（不删除图片，不删除 asset 记录）。
     * objectKey 即 favoriteImage 请求中的 objectKey。
     */
    @DeleteMapping("/favorite")
    public java.util.Map<String, Object> unfavoriteImage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam String objectKey) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }

        Long userId = principal.id();
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "objectKey 不能为空");
        }
        log.info("用户 {} 取消收藏: objectKey={}", userId, objectKey);
        MediaAssetResponse asset = mediaService.unmarkAsFavorite(userId, objectKey);
        log.info("取消收藏成功: assetId={}, objectKey={}, sourceTool={}",
            asset.getId(), objectKey, asset.getSourceTool());
        return java.util.Map.of("success", true);
    }
}
