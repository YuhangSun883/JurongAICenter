package com.jurong.aicenter.controller;

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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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

        log.info("AI 图片生成请求: userId={}, promptLen={}, size={}, quality={}, style={}, refImageCount={}",
            userId, prompt.length(), request.getSize(), request.getQuality(), request.getStyle(),
            hasReferences ? referenceImages.size() : 0);

        // 1. 根据是否有引用图片，选择调用不同的 NewAPI 接口
        // - 有引用图片：调用 /v1/images/edits（图片编辑接口），将引用图片作为素材
        // - 无引用图片：调用 /v1/images/generations（纯文本生成接口）
        String originalData;
        try {
            if (hasReferences) {
                // 有引用图片：调用图片编辑接口，将引用图片作为素材结合提示词生成新图片
                log.info("检测到 {} 张引用图片，调用 /v1/images/edits 接口", referenceImages.size());
                originalData = newApiClient.editImage(
                    prompt,
                    referenceImages,
                    request.getSize(),
                    request.getQuality(),
                    request.getStyle()
                );
            } else {
                // 无引用图片：调用纯文本生成接口
                originalData = newApiClient.generateImage(
                    prompt,
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

        // 3. 构造响应：imageUrl 为 base64 data URI，可直接在网页中显示
        ImageGenerateResponse response = new ImageGenerateResponse(
            base64DataUri,
            "gpt-image-2-2k",
            base64DataUri
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
        try (InputStream is = new URI(originalData).toURL().openStream()) {
            byte[] imageBytes = is.readAllBytes();
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String dataUri = "data:image/png;base64," + base64;
            log.info("图片(URL)已转换为 base64 data URI: originalLen={}, base64Len={}",
                imageBytes.length, dataUri.length());
            return dataUri;
        } catch (Exception e) {
            log.error("图片(URL)转换为 base64 失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                "图片生成成功但转换格式失败，请稍后重试");
        }
    }

    // ==================== 图片收藏功能 ====================
    // 收藏 = 把图片作为一条素材资产入库到"我的资产"库。
    // MinIO 存储路径：media/{userId}/{yyyy-MM}/{uuid}.{ext}
    // （与用户上传素材共用 media/ 路径，不再单独用 favorites/ 目录）

    /**
     * 收藏图片接口
     * 将 base64 图片数据上传到 MinIO（media/ 路径），并写入 media_assets 表（sourceTool=favorite，库="我的资产"）。
     */
    @PostMapping("/favorite")
    public FavoriteImageResponse favoriteImage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody FavoriteImageRequest request) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }

        Long userId = principal.id();
        String imageData = request.getImageData();
        log.info("用户 {} 收藏图片: dataLen={}", userId, imageData.length());

        // 解析 base64 data URI，提取图片字节和 MIME 类型
        String mimeType = "image/png";
        byte[] imageBytes;

        if (imageData.startsWith("data:image/")) {
            int semicolonIdx = imageData.indexOf(";");
            int commaIdx = imageData.indexOf(",");
            if (semicolonIdx != -1 && commaIdx != -1) {
                mimeType = imageData.substring(5, semicolonIdx); // image/png
            }
            String b64Data = imageData.substring(commaIdx + 1);
            imageBytes = Base64.getDecoder().decode(b64Data);
        } else {
            imageBytes = Base64.getDecoder().decode(imageData);
        }

        // 入库（"我的资产"库 + MinIO media/ 路径）
        MediaAssetResponse asset = mediaService.saveFavoriteAsAsset(userId, imageBytes, mimeType, null);
        log.info("收藏图片已入库为资产: userId={}, assetId={}, library={}, url={}",
            userId, asset.getId(), asset.getLibraryName(), asset.getUrl());

        long createdAtMs = asset.getCreatedAt() != null
            ? java.sql.Timestamp.valueOf(asset.getCreatedAt()).getTime()
            : System.currentTimeMillis();
        // id 用 assetId:asset.getId()（字符串化），这样 unfavorite 删除时可直接按 assetId 走 mediaService.deleteAsset
        return new FavoriteImageResponse(
            String.valueOf(asset.getId()),
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
        // 收藏入库时 source=ai-generated + sourceTool=favorite，但为了兼容"所有 AI 生成结果也算收藏"，
        // 这里只要是图片 AI 生成资产就都返回（用户在 AI 工作台收藏 Tab 能看到自己的 AI 产物）。
        List<FavoriteImageResponse> result = page.getItems().stream()
            .filter(a -> "ai-generated".equals(a.getSource()) && "image".equals(a.getType()))
            .map(a -> new FavoriteImageResponse(
                String.valueOf(a.getId()),
                a.getUrl(),
                a.getCreatedAt() != null ? java.sql.Timestamp.valueOf(a.getCreatedAt()).getTime() : 0L
            ))
            .toList();
        log.info("用户 {} 的收藏图片: {} 张", userId, result.size());
        return result;
    }

    /**
     * 取消收藏：删除对应的媒体资产（连 MinIO 对象一起删）。
     * id = String(assetId)，即 favoriteImage 返回的 FavoriteImageResponse.id。
     */
    @DeleteMapping("/favorite")
    public java.util.Map<String, Object> unfavoriteImage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam String objectKey) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }

        Long userId = principal.id();
        Long assetId;
        try {
            assetId = Long.valueOf(objectKey);
        } catch (NumberFormatException e) {
            // 兼容老版本：传 MinIO objectKey 的情况，尝试反查 assetId 然后删
            log.warn("unfavorite: objectKey is not numeric asset id: {}, fallback to asset deletion", objectKey);
            throw new BusinessException(ErrorCode.INVALID_PARAM, "收藏 ID 格式错误");
        }

        log.info("用户 {} 取消收藏: assetId={}", userId, assetId);
        mediaService.deleteAsset(userId, assetId);
        log.info("取消收藏成功: assetId={}", assetId);
        return java.util.Map.of("success", true);
    }
}
