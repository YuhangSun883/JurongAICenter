package com.jurong.aicenter.controller;

import com.jurong.aicenter.client.NewApiClient;
import com.jurong.aicenter.dto.image.FavoriteImageRequest;
import com.jurong.aicenter.dto.image.FavoriteImageResponse;
import com.jurong.aicenter.dto.image.ImageGenerateRequest;
import com.jurong.aicenter.dto.image.ImageGenerateResponse;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.StorageService;
import com.jurong.aicenter.service.impl.StorageServiceImpl;
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
    // MinIO 存储路径：ai-platform/favorites/{userId}/{uuid}.png
    // 专门用于存储用户收藏的 AI 生成图片，与生成图片目录分离

    /**
     * 收藏图片接口
     * 将 base64 图片数据上传到 MinIO 的收藏目录，返回可访问的 URL
     *
     * @param principal 当前登录用户
     * @param request   收藏请求（包含 base64 图片数据）
     * @return 收藏结果（含 MinIO URL 和收藏时间）
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
        String ext = ".png";
        byte[] imageBytes;

        if (imageData.startsWith("data:image/")) {
            // 格式：data:image/png;base64,xxxx
            int semicolonIdx = imageData.indexOf(";");
            int commaIdx = imageData.indexOf(",");
            if (semicolonIdx != -1 && commaIdx != -1) {
                mimeType = imageData.substring(5, semicolonIdx); // image/png
                ext = mimeType.equals("image/jpeg") ? ".jpg" : ".png";
            }
            String b64Data = imageData.substring(commaIdx + 1);
            imageBytes = Base64.getDecoder().decode(b64Data);
        } else {
            // 纯 base64 字符串（无 data URI 前缀）
            imageBytes = Base64.getDecoder().decode(imageData);
        }

        // 上传到 MinIO 收藏目录：ai-platform/favorites/{userId}/{uuid}.png
        String filename = "fav_" + UUID.randomUUID().toString().replace("-", "") + ext;
        String objectKey = String.format("favorites/%d/%s", userId, filename);

        String minioUrl;
        try (InputStream is = new ByteArrayInputStream(imageBytes)) {
            minioUrl = storageService.uploadObject(objectKey, is, mimeType);
            log.info("收藏图片已上传到 MinIO: userId={}, objectKey={}, url={}", userId, objectKey, minioUrl);
        } catch (Exception e) {
            log.error("收藏图片上传 MinIO 失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "收藏失败：图片存储异常");
        }

        return new FavoriteImageResponse(objectKey, minioUrl, System.currentTimeMillis());
    }

    /**
     * 获取用户收藏图片列表
     * 通过列出 MinIO 中 favorites/{userId}/ 目录下的所有对象
     *
     * @param principal 当前登录用户
     * @return 收藏图片 URL 列表
     */
    @GetMapping("/favorites")
    public List<FavoriteImageResponse> getFavorites(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }

        Long userId = principal.id();
        String prefix = String.format("favorites/%d/", userId);
        log.info("获取用户 {} 的收藏图片列表", userId);

        // 列出 MinIO 中该用户的收藏目录
        List<FavoriteImageResponse> result = new ArrayList<>();
        try {
            io.minio.ListObjectsArgs listArgs = io.minio.ListObjectsArgs.builder()
                .bucket(getBucketName())
                .prefix(prefix)
                .recursive(true)
                .build();
            Iterable<io.minio.Result<io.minio.messages.Item>> items =
                getMinioClient().listObjects(listArgs);

            for (io.minio.Result<io.minio.messages.Item> item : items) {
                String objectKey = item.get().objectName();
                String url = storageService.getPresignedUrl(objectKey, 24);
                long createdAt = item.get().lastModified() != null
                    ? item.get().lastModified().toInstant().toEpochMilli()
                    : System.currentTimeMillis();
                result.add(new FavoriteImageResponse(objectKey, url, createdAt));
            }
            log.info("用户 {} 共有 {} 张收藏图片", userId, result.size());
        } catch (Exception e) {
            log.error("获取收藏列表失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "获取收藏列表失败");
        }

        return result;
    }

    /**
     * 取消收藏（从 MinIO 删除图片）
     *
     * @param principal  当前登录用户
     * @param objectKey  图片在 MinIO 中的 objectKey
     * @return 操作结果
     */
    @DeleteMapping("/favorite")
    public java.util.Map<String, Object> unfavoriteImage(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam String objectKey) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
        }

        Long userId = principal.id();
        // 安全检查：确保 objectKey 属于当前用户
        String expectedPrefix = String.format("favorites/%d/", userId);
        if (!objectKey.startsWith(expectedPrefix)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限访问");
        }

        log.info("用户 {} 取消收藏: objectKey={}", userId, objectKey);
        storageService.deleteFile(objectKey);
        log.info("取消收藏成功: objectKey={}", objectKey);

        return java.util.Map.of("success", true);
    }

    /**
     * 获取 MinIO bucket 名称
     */
    private String getBucketName() {
        if (storageService instanceof StorageServiceImpl) {
            return ((StorageServiceImpl) storageService).getBucket();
        }
        return "ai-platform";
    }

    /**
     * 获取 MinioClient 实例
     * 通过 Spring 容器注入的 StorageServiceImpl 获取
     */
    private io.minio.MinioClient getMinioClient() {
        if (storageService instanceof StorageServiceImpl) {
            return ((StorageServiceImpl) storageService).getMinioClient();
        }
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "不支持的存储服务实现");
    }
}
