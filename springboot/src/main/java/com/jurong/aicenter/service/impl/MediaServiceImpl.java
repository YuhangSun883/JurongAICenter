package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jurong.aicenter.dto.PageResult;
import com.jurong.aicenter.dto.media.MediaAssetDto;
import com.jurong.aicenter.dto.media.MediaAssetResponse;
import com.jurong.aicenter.dto.media.MediaListQuery;
import com.jurong.aicenter.dto.media.MediaRoleDto;
import com.jurong.aicenter.dto.media.MediaUploadResponse;
import com.jurong.aicenter.dto.media.PatchAssetRequest;
import com.jurong.aicenter.entity.MediaAsset;
import com.jurong.aicenter.entity.MediaLibrary;
import com.jurong.aicenter.entity.MediaRole;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.MediaAssetRepository;
import com.jurong.aicenter.repository.MediaLibraryRepository;
import com.jurong.aicenter.repository.MediaRoleRepository;
import com.jurong.aicenter.service.MediaLibraryService;
import com.jurong.aicenter.service.MediaService;
import com.jurong.aicenter.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaServiceImpl implements MediaService {

    private final MediaAssetRepository assetRepository;
    private final MediaLibraryRepository libraryRepository;
    private final MediaRoleRepository roleRepository;
    private final MediaLibraryService libraryService;
    private final StorageService storageService;

    private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_VIDEO_BYTES = 200L * 1024 * 1024;
    private static final long MAX_AUDIO_BYTES = 50L * 1024 * 1024;

    /**
     * V26：预签名 URL 缓存。
     * MinIO presign 出来的 URL 带 X-Amz-Date 时间戳，每次 listAssets 都生成新 URL，
     * 浏览器眼里是"不同资源" → 永远不命中磁盘缓存。
     * 这里用内存缓存同一 objectKey 在窗口内返回相同 URL，让浏览器能命中缓存。
     * 窗口取 6 小时（远小于 MinIO 7 天上限 168h），到期后自动刷新。
     */
    private static final long PRESIGN_CACHE_HOURS = 6;
    private static final class CachedPresign {
        final String url;
        final long expireAt; // System.currentTimeMillis()
        CachedPresign(String url, long expireAt) {
            this.url = url;
            this.expireAt = expireAt;
        }
    }
    private final ConcurrentHashMap<String, CachedPresign> presignCache = new ConcurrentHashMap<>();

    private static final Set<String> ALLOWED_IMAGE_MIME = Set.of(
        "image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp", "image/bmp"
    );
    private static final Set<String> ALLOWED_VIDEO_MIME = Set.of(
        "video/mp4", "video/webm", "video/quicktime", "video/x-msvideo", "video/x-matroska"
    );
    private static final Set<String> ALLOWED_AUDIO_MIME = Set.of(
        "audio/mpeg", "audio/mp3", "audio/wav", "audio/wave", "audio/x-wav",
        "audio/ogg", "audio/aac", "audio/x-m4a", "audio/mp4"
    );
    private static final Set<String> ALLOWED_IMAGE_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");
    private static final Set<String> ALLOWED_VIDEO_EXT = Set.of("mp4", "webm", "mov", "avi", "mkv");
    private static final Set<String> ALLOWED_AUDIO_EXT = Set.of("mp3", "wav", "ogg", "m4a", "aac");
    private static final Set<String> BLACKLIST_EXT = Set.of(
        "exe", "msi", "bat", "cmd", "sh", "ps1", "vbs", "js", "jar", "app", "dmg", "apk", "ipa",
        "php", "jsp", "asp", "aspx", "py", "rb", "pl", "lua", "go", "rs", "ts", "tsx", "jsx",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf",
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso",
        "env", "ini", "conf", "cfg", "keystore", "jks", "pem", "key", "crt",
        "sql", "db", "sqlite", "mdb", "ttf", "otf", "woff", "woff2", "eot",
        "html", "htm", "svg", "xml"
    );
    private static final Set<String> BLACKLIST_MIME = Set.of(
        "application/x-msdownload", "application/x-msdos-program", "application/x-exe",
        "application/x-sh", "application/x-shellscript", "application/x-bat",
        "application/javascript", "text/javascript", "application/x-javascript",
        "application/pdf", "application/zip", "application/x-zip-compressed",
        "application/x-rar-compressed", "application/x-7z-compressed",
        "application/x-tar", "application/gzip", "application/x-bzip2",
        "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-powerpoint", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "text/html", "application/xhtml+xml", "image/svg+xml", "application/xml", "text/xml"
    );

    /** 11 涓爣鍑嗚鑹插垎绫伙紙鍚屼簨淇濈暀锛?*/
    private static final List<Map<String, String>> CATEGORIES = List.of(
        Map.of("key", "face", "label", "閫肩湡浜鸿劯"),
        Map.of("key", "urban-blue", "label", "閮藉競钃濋"),
        Map.of("key", "urban-silver", "label", "閮藉競閾跺彂"),
        Map.of("key", "kids", "label", "鍎跨"),
        Map.of("key", "mom", "label", "绮捐嚧濡堝"),
        Map.of("key", "town-young", "label", "灏忛晣闈掑勾"),
        Map.of("key", "town-mid", "label", "灏忛晣涓€佸勾"),
        Map.of("key", "fantasy", "label", "二次元"),
        Map.of("key", "chinese", "label", "鍥介"),
        Map.of("key", "fashion", "label", "鏃跺皻妯＄壒"),
        Map.of("key", "animal", "label", "鍔ㄧ墿")
    );

    // ==================== 素材 ====================

    @Override
    public PageResult<MediaAssetResponse> listAssets(Long userId, MediaListQuery query) {
        MediaListQuery safeQuery = query == null ? new MediaListQuery() : query;
        int page = safeQuery.getPage() == null || safeQuery.getPage() < 1 ? 1 : safeQuery.getPage();
        int size = safeQuery.getPageSize() == null || safeQuery.getPageSize() < 1
            ? 20
            : Math.min(safeQuery.getPageSize(), 100);

        LambdaQueryWrapper<MediaAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaAsset::getUserId, userId);
        if (safeQuery.getLibraryId() != null) {
            wrapper.eq(MediaAsset::getLibraryId, safeQuery.getLibraryId());
        }
        if (safeQuery.getType() != null && !safeQuery.getType().isBlank()) {
            wrapper.eq(MediaAsset::getType, safeQuery.getType());
        }
        if (safeQuery.getSource() != null && !safeQuery.getSource().isBlank()) {
            wrapper.eq(MediaAsset::getSource, safeQuery.getSource());
        }
        if (safeQuery.getKeyword() != null && !safeQuery.getKeyword().isBlank()) {
            wrapper.like(MediaAsset::getName, safeQuery.getKeyword().trim());
        }
        wrapper.orderByDesc(MediaAsset::getCreatedAt);

        Page<MediaAsset> mpPage = assetRepository.selectPage(new Page<>(page, size), wrapper);
        List<MediaAsset> records = mpPage.getRecords();
        Map<Long, String> libraryNameMap = batchLoadLibraryNames(userId, records);
        List<MediaAssetResponse> items = records.stream()
            .map(asset -> toResponse(asset, libraryNameMap.get(asset.getLibraryId())))
            .toList();

        return new PageResult<>(items, mpPage.getTotal(), page, size);
    }

    @Override
    public MediaAssetResponse getAsset(Long userId, Long assetId) {
        MediaAsset asset = mustGetOwnedAsset(userId, assetId);
        String libraryName = null;
        if (asset.getLibraryId() != null) {
            MediaLibrary lib = libraryRepository.selectById(asset.getLibraryId());
            if (lib != null) {
                libraryName = lib.getName();
            }
        }
        return toResponse(asset, libraryName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaUploadResponse uploadAsset(Long userId, Long libraryId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.MEDIA_FILE_EMPTY);
        }

        String mimeType = file.getContentType();
        String type = inferType(mimeType, file.getOriginalFilename());
        if (type == null) {
            throw new BusinessException(ErrorCode.MEDIA_ASSET_TYPE_INVALID, "Unsupported media file type");
        }

        long size = file.getSize();
        checkSize(type, size);
        Long targetLibraryId = resolveLibraryId(userId, libraryId);
        String ext = extractExt(file.getOriginalFilename());
        String objectKey = buildObjectKey(userId, ext);
        String url;

        try {
            url = storageService.uploadObject(objectKey, file.getInputStream(), mimeType);
        } catch (IOException e) {
            log.error("Upload IO failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.MEDIA_UPLOAD_FAILED, e.getMessage());
        } catch (Exception e) {
            log.error("Upload failed: {}", e.getMessage());
            throw new BusinessException(ErrorCode.MEDIA_UPLOAD_FAILED, e.getMessage());
        }

        MediaAsset asset = new MediaAsset();
        asset.setUserId(userId);
        asset.setLibraryId(targetLibraryId);
        asset.setType(type);
        asset.setSource("uploaded");
        asset.setName(file.getOriginalFilename() == null ? "untitled" : file.getOriginalFilename());
        asset.setMimeType(mimeType);
        asset.setSizeBytes(size);
        asset.setObjectKey(objectKey);
        asset.setSourceTool("upload");
        asset.setCreatedAt(LocalDateTime.now());
        asset.setUpdatedAt(LocalDateTime.now());
        assetRepository.insert(asset);

        log.info("Media uploaded: id={}, userId={}, type={}, size={}", asset.getId(), userId, type, size);
        return new MediaUploadResponse(asset.getId(), url, asset.getName(), type, size);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAsset(Long userId, Long assetId) {
        MediaAsset asset = mustGetOwnedAsset(userId, assetId);
        storageService.deleteFile(asset.getObjectKey());
        assetRepository.deleteById(asset.getId());
        log.info("Media deleted: id={}, userId={}", assetId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDeleteAssets(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }

        LambdaQueryWrapper<MediaAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaAsset::getUserId, userId).in(MediaAsset::getId, ids);
        List<MediaAsset> assets = assetRepository.selectList(wrapper);
        for (MediaAsset asset : assets) {
            storageService.deleteFile(asset.getObjectKey());
        }

        int deleted = assetRepository.delete(wrapper);
        log.info("Batch delete media: userId={}, requested={}, deleted={}", userId, ids.size(), deleted);
        return deleted;
    }

    @Override
    public MediaAssetResponse renameAsset(Long userId, Long assetId, String name) {
        MediaAsset asset = mustGetOwnedAsset(userId, assetId);
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "File name cannot be empty");
        }

        String trimmed = name.trim();
        if (!asset.getName().equals(trimmed)
            && existsByNameInSameLibrary(userId, asset.getLibraryId(), trimmed, asset.getId())) {
            throw new BusinessException(ErrorCode.MEDIA_ASSET_NAME_DUPLICATE);
        }

        asset.setName(trimmed);
        asset.setUpdatedAt(LocalDateTime.now());
        assetRepository.updateById(asset);

        String libraryName = null;
        if (asset.getLibraryId() != null) {
            MediaLibrary lib = libraryRepository.selectById(asset.getLibraryId());
            if (lib != null) {
                libraryName = lib.getName();
            }
        }
        return toResponse(asset, libraryName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaAssetResponse patchAsset(Long userId, Long assetId, PatchAssetRequest request) {
        if (request == null
            || (request.getName() == null && request.getLibraryId() == null)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "至少需要修改名称或所属库中的一项");
        }

        MediaAsset asset = mustGetOwnedAsset(userId, assetId);

        // ============ 1. 改名称（可选） ============
        if (request.getName() != null) {
            String trimmed = request.getName().trim();
            if (trimmed.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "素材名称不能为空");
            }
            // 重名校验必须看"最终所在库"——如果同时改库，重名校验看目标库
            Long dupCheckLibId = request.getLibraryId() != null
                ? request.getLibraryId() : asset.getLibraryId();
            if (!asset.getName().equals(trimmed)
                && existsByNameInSameLibrary(userId, dupCheckLibId, trimmed, asset.getId())) {
                throw new BusinessException(ErrorCode.MEDIA_ASSET_NAME_DUPLICATE,
                    "目标库内已存在同名素材「" + trimmed + "」");
            }
            asset.setName(trimmed);
        }

        // ============ 2. 改所属库（可选） ============
        if (request.getLibraryId() != null) {
            Long targetLibId = request.getLibraryId();
            if (targetLibId.equals(asset.getLibraryId())) {
                // 移到原库：不报错但也不写盘
                log.debug("[patchAsset] asset {} already in library {}, no-op", assetId, targetLibId);
            } else {
                MediaLibrary target = libraryRepository.selectById(targetLibId);
                if (target == null || !target.getUserId().equals(userId)) {
                    throw new BusinessException(ErrorCode.MEDIA_LIBRARY_NOT_FOUND, "目标资产库不存在或不属于当前用户");
                }
                if ("system-ai".equals(target.getType())) {
                    throw new BusinessException(ErrorCode.MEDIA_ASSET_CANNOT_MOVE_TO_AI,
                        "AI 生成结果库只接收 AI 产出，不能手工移入");
                }
                asset.setLibraryId(targetLibId);
            }
        }

        asset.setUpdatedAt(LocalDateTime.now());
        assetRepository.updateById(asset);

        // 名称和库都更新后再做一次目标库重名校验（如果只改库不改名）
        // 已经在前面统一处理了，这里无需重复

        String libraryName = null;
        if (asset.getLibraryId() != null) {
            MediaLibrary lib = libraryRepository.selectById(asset.getLibraryId());
            if (lib != null) libraryName = lib.getName();
        }
        log.info("[patchAsset] user={} asset={} newName={} newLib={}",
            userId, assetId, asset.getName(), asset.getLibraryId());
        return toResponse(asset, libraryName);
    }

    @Override
    public List<Map<String, String>> listCategories() {
        return CATEGORIES;
    }

    @Override
    public List<MediaRoleDto> listRolesByCategory(String category) {
        LambdaQueryWrapper<MediaRole> wrapper = new LambdaQueryWrapper<>();
        if (category != null && !category.isBlank()) {
            wrapper.eq(MediaRole::getCategory, category);
        }
        wrapper.orderByAsc(MediaRole::getSortOrder).orderByAsc(MediaRole::getId);
        return roleRepository.selectList(wrapper).stream().map(MediaRoleDto::from).toList();
    }

    @Override
    public List<MediaRoleDto> listAllRoles() {
        return listRolesByCategory(null);
    }

    @Override
    public void recordAiGenerated(Long userId, String type, String filename,
                                  String mimeType, Long sizeBytes, String objectKey,
                                  String sourceTool, String sourceTaskId) {
        MediaLibrary aiLib = libraryService.getAiLibrary(userId);

        MediaAsset asset = new MediaAsset();
        asset.setUserId(userId);
        asset.setLibraryId(aiLib.getId());
        asset.setType(type);
        asset.setSource("ai-generated");
        asset.setName(filename);
        asset.setMimeType(mimeType);
        asset.setSizeBytes(sizeBytes);
        asset.setObjectKey(objectKey);
        asset.setSourceTool(sourceTool);
        asset.setSourceTaskId(sourceTaskId);
        asset.setCreatedAt(LocalDateTime.now());
        asset.setUpdatedAt(LocalDateTime.now());
        assetRepository.insert(asset);
        log.info("AI media recorded: id={}, userId={}, type={}, taskId={}",
            asset.getId(), userId, type, sourceTaskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaAssetResponse saveFavoriteAsAsset(Long userId, byte[] imageBytes, String mimeType, String displayName) {
        return saveImageAsAsset(userId, imageBytes, mimeType, "favorite", libraryService.getAiLibrary(userId).getId());
    }

    /**
     * AI 图片工作台同步生成的图片入库（sourceTool=image，用于"预览"Tab）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaAssetResponse recordGeneratedImage(Long userId, byte[] imageBytes, String mimeType) {
        return saveImageAsAsset(userId, imageBytes, mimeType, "image", libraryService.getAiLibrary(userId).getId());
    }

    /**
     * 商详套图生成结果入「个人资产」：libraryId 指向「我的资产」系统库（不进「AI 生成结果」库），
     * sourceTool=product-image（不会出现在 AI 工作台的预览/收藏 Tab）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaAssetResponse recordProductImageAsset(Long userId, byte[] imageBytes, String mimeType) {
        return saveImageAsAsset(userId, imageBytes, mimeType, "product-image", libraryService.getUploadLibrary(userId).getId());
    }

    /**
     * 按 objectKey 批量删除资产记录：商详套图删除任务时，生成图已物理删除，同步清掉对应资产记录。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAssetsByObjectKeys(Long userId, List<String> objectKeys) {
        if (objectKeys == null || objectKeys.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<MediaAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaAsset::getUserId, userId)
               .in(MediaAsset::getObjectKey, objectKeys);
        int deleted = assetRepository.delete(wrapper);
        log.info("Deleted asset records by objectKeys: userId={}, requested={}, deleted={}",
            userId, objectKeys.size(), deleted);
    }

    /**
     * 收藏 = 修改已有 AI 生成记录的 source_tool 字段（不复制图片）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaAssetResponse markAsFavorite(Long userId, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "objectKey 不能为空");
        }
        MediaAsset asset = findByUserAndObjectKey(userId, objectKey);
        if (asset == null) {
            throw new BusinessException(ErrorCode.MEDIA_ASSET_NOT_FOUND, "未找到该图片资产");
        }
        asset.setSourceTool("favorite");
        asset.setUpdatedAt(LocalDateTime.now());
        assetRepository.updateById(asset);
        log.info("Marked as favorite: assetId={}, userId={}, objectKey={}", asset.getId(), userId, objectKey);
        MediaLibrary lib = asset.getLibraryId() != null ? libraryRepository.selectById(asset.getLibraryId()) : null;
        return toResponse(asset, lib != null ? lib.getName() : null);
    }

    /**
     * 撤销收藏 = 把 source_tool 改回 'image'。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaAssetResponse unmarkAsFavorite(Long userId, String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "objectKey 不能为空");
        }
        MediaAsset asset = findByUserAndObjectKey(userId, objectKey);
        if (asset == null) {
            throw new BusinessException(ErrorCode.MEDIA_ASSET_NOT_FOUND, "未找到该图片资产");
        }
        asset.setSourceTool("image");
        asset.setUpdatedAt(LocalDateTime.now());
        assetRepository.updateById(asset);
        log.info("Unmarked favorite: assetId={}, userId={}, objectKey={}", asset.getId(), userId, objectKey);
        MediaLibrary lib = asset.getLibraryId() != null ? libraryRepository.selectById(asset.getLibraryId()) : null;
        return toResponse(asset, lib != null ? lib.getName() : null);
    }

    /**
     * 工具方法：按 user_id + object_key 唯一查找（忽略软删除）。
     */
    private MediaAsset findByUserAndObjectKey(Long userId, String objectKey) {
        LambdaQueryWrapper<MediaAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaAsset::getUserId, userId)
               .eq(MediaAsset::getObjectKey, objectKey);
        // 显式覆盖软删除过滤（如有）
        return assetRepository.selectOne(wrapper);
    }

    /**
     * 通用图片入库逻辑：上传 MinIO + 写 media_assets。
     *
     * @param userId      用户 ID
     * @param imageBytes  图片字节
     * @param mimeType    MIME 类型
     * @param sourceTool  写入 sourceTool 字段（"favorite" 收藏 / "image" 预览 / "product-image" 商详套图）
     * @param libraryId   资产所属库 ID（AI 库 / 「我的资产」库）
     */
    private MediaAssetResponse saveImageAsAsset(Long userId, byte[] imageBytes, String mimeType, String sourceTool, Long libraryId) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new BusinessException(ErrorCode.MEDIA_FILE_EMPTY);
        }
        long size = imageBytes.length;
        checkSize("image", size);

        String type = "image";
        String ext = extractExtFromMime(mimeType);
        String objectKey = buildObjectKey(userId, ext);

        String url;
        try (ByteArrayInputStream is = new ByteArrayInputStream(imageBytes)) {
            url = storageService.uploadObject(objectKey, is, mimeType);
        } catch (Exception e) {
            log.error("Image asset upload failed (sourceTool={}): {}", sourceTool, e.getMessage());
            throw new BusinessException(ErrorCode.MEDIA_UPLOAD_FAILED, e.getMessage());
        }

        Long aiLibId = libraryId;
        // name 字段存储文件名，即 objectKey 的最后一部分 (e.g., 0f8d58d9e36e4721a5497e65f5000f71.png)
        String name = objectKey.substring(objectKey.lastIndexOf('/') + 1);

        MediaAsset asset = new MediaAsset();
        asset.setUserId(userId);
        asset.setLibraryId(aiLibId);
        asset.setType(type);
        asset.setSource("ai-generated");
        asset.setName(name);
        asset.setMimeType(mimeType);
        asset.setSizeBytes(size);
        asset.setObjectKey(objectKey);
        asset.setSourceTool(sourceTool);
        asset.setCreatedAt(LocalDateTime.now());
        asset.setUpdatedAt(LocalDateTime.now());
        assetRepository.insert(asset);

        log.info("Image asset saved: id={}, userId={}, libraryId={}, size={}, sourceTool={}, objectKey={}",
            asset.getId(), userId, aiLibId, size, sourceTool, objectKey);

        MediaLibrary lib = libraryRepository.selectById(aiLibId);
        return toResponse(asset, lib != null ? lib.getName() : null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAssetsByLibrary(Long userId, Long libraryId) {
        LambdaQueryWrapper<MediaAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaAsset::getUserId, userId).eq(MediaAsset::getLibraryId, libraryId);
        List<MediaAsset> assets = assetRepository.selectList(wrapper);
        for (MediaAsset asset : assets) {
            storageService.deleteFile(asset.getObjectKey());
        }
        if (!assets.isEmpty()) {
            assetRepository.delete(wrapper);
        }
        log.info("Cascade delete assets by library: userId={}, libraryId={}, count={}",
            userId, libraryId, assets.size());
    }

    @Override
    public List<String> getImageUrlsByIds(Long userId, List<String> ids) {
        if (userId == null || ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<MediaAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaAsset::getUserId, userId)
               .eq(MediaAsset::getType, "image")
               .in(MediaAsset::getId, ids);
        List<MediaAsset> assets = assetRepository.selectList(wrapper);
        if (assets == null || assets.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> urls = assets.stream()
                .map(MediaAsset::getObjectKey)
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .map(key -> storageService.getPresignedUrl(key, 24))
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .distinct()
                .collect(Collectors.toList());
        log.debug("Agent getImageUrlsByIds: userId={}, inputIds={}, imageUrls={}",
            userId, ids.size(), urls.size());
        return urls;
    }

    @Override
    public List<MediaAsset> getAssetsByIds(Long userId, List<String> ids) {
        if (userId == null || ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<MediaAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaAsset::getUserId, userId).in(MediaAsset::getId, ids);
        List<MediaAsset> assets = assetRepository.selectList(wrapper);
        if (assets == null) {
            return Collections.emptyList();
        }
        log.debug("Agent getAssetsByIds: userId={}, inputIds={}, assets={}",
            userId, ids.size(), assets.size());
        return assets;
    }

    @Override
    public String getPresignedUrl(String objectKey, int hours) {
        if (objectKey == null || objectKey.isBlank()) return null;
        return storageService.getPresignedUrl(objectKey, hours);
    }

    @Override
    public String lookupAiMediaObjectKey(Long userId, String sourceTaskId, String filename) {
        if (userId == null || sourceTaskId == null || filename == null) return null;
        LambdaQueryWrapper<MediaAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaAsset::getUserId, userId)
            .eq(MediaAsset::getSourceTaskId, sourceTaskId)
            .eq(MediaAsset::getName, filename)
            .eq(MediaAsset::getSource, "ai-generated")
            .orderByDesc(MediaAsset::getId)
            .last("LIMIT 1");
        MediaAsset one = assetRepository.selectOne(wrapper);
        return one != null ? one.getObjectKey() : null;
    }

    private boolean existsByNameInSameLibrary(Long userId, Long libraryId, String name, Long excludeAssetId) {
        LambdaQueryWrapper<MediaAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaAsset::getUserId, userId)
            .eq(MediaAsset::getName, name)
            .ne(MediaAsset::getId, excludeAssetId);
        if (libraryId == null) {
            wrapper.isNull(MediaAsset::getLibraryId);
        } else {
            wrapper.eq(MediaAsset::getLibraryId, libraryId);
        }
        Long count = assetRepository.selectCount(wrapper);
        return count != null && count > 0;
    }

    private MediaAsset mustGetOwnedAsset(Long userId, Long assetId) {
        MediaAsset asset = assetRepository.selectById(assetId);
        if (asset == null || !asset.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.MEDIA_ASSET_NOT_FOUND);
        }
        return asset;
    }


    private Long resolveLibraryId(Long userId, Long libraryId) {
        if (libraryId != null) {
            MediaLibrary lib = libraryRepository.selectById(libraryId);
            if (lib == null || !lib.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.MEDIA_LIBRARY_NOT_FOUND);
            }
            return libraryId;
        }
        return libraryService.getOrCreateUploadLibrary(userId).getId();
    }

    private Map<Long, String> batchLoadLibraryNames(Long userId, List<MediaAsset> assets) {
        if (assets.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = assets.stream()
            .map(MediaAsset::getLibraryId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }

        LambdaQueryWrapper<MediaLibrary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaLibrary::getUserId, userId).in(MediaLibrary::getId, ids);
        return libraryRepository.selectList(wrapper).stream()
            .collect(Collectors.toMap(MediaLibrary::getId, MediaLibrary::getName, (a, b) -> a));
    }

    private MediaAssetResponse toResponse(MediaAsset asset, String libraryName) {
        MediaAssetResponse response = new MediaAssetResponse();
        response.setId(asset.getId());
        response.setLibraryId(asset.getLibraryId());
        response.setLibraryName(libraryName);
        response.setType(asset.getType());
        response.setSource(asset.getSource());
        response.setName(asset.getName());
        response.setMimeType(asset.getMimeType());
        response.setSizeBytes(asset.getSizeBytes());
        response.setWidth(asset.getWidth());
        response.setHeight(asset.getHeight());
        response.setDurationSec(asset.getDurationSec());
        response.setUrl(presign(asset.getObjectKey()));
        response.setObjectKey(asset.getObjectKey());
        response.setSourceTool(asset.getSourceTool());
        response.setSourceTaskId(asset.getSourceTaskId());
        response.setCreatedAt(asset.getCreatedAt());
        response.setUpdatedAt(asset.getUpdatedAt());
        return response;
    }

    private String presign(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return null;
        }
        long now = System.currentTimeMillis();
        // 命中缓存：URL 在 6 小时窗口内复用
        CachedPresign cached = presignCache.get(objectKey);
        if (cached != null && cached.expireAt > now) {
            return cached.url;
        }
        try {
            // V26：拉到 7 天（MinIO 默认最大支持 7 天 = 168 小时）。
            // URL 7 天内稳定，浏览器可命中磁盘缓存。7 天后 URL 变，需重新拉一次。
            // 配合前端 PRESIGN_CACHE_HOURS=6 缓存，6 小时内同 objectKey 复用同一 URL。
            // 彻底解决需走方案 A：后端 stream URL + cookie 认证。
            String url = storageService.getPresignedUrl(objectKey, 168);
            // 仅在生成成功时入缓存（避免把 null 也存进去）
            if (url != null) {
                presignCache.put(objectKey, new CachedPresign(
                    url, now + PRESIGN_CACHE_HOURS * 3600L * 1000L));
            }
            return url;
        } catch (Exception e) {
            log.warn("Generate presigned URL failed for object {}: {}", objectKey, e.getMessage());
            return null;
        }
    }

    /** 类型推断：MIME 白名单 + 扩展名白名单 + 类别一致性 + 黑名单 */
    private String inferType(String mime, String filename) {
        String mimeLower = mime == null ? "" : mime.toLowerCase().trim();
        String ext = extractExt(filename);

        if (BLACKLIST_EXT.contains(ext) || (!mimeLower.isEmpty() && BLACKLIST_MIME.contains(mimeLower))) {
            return null;
        }

        String mimeCategory = categorizeByMime(mimeLower);
        String extCategory = categorizeByExt(ext);
        if (mimeCategory != null && extCategory != null) {
            return mimeCategory.equals(extCategory) ? mimeCategory : null;
        }
        if (mimeCategory != null) {
            return mimeCategory;
        }
        return extCategory;
    }

    private String categorizeByMime(String mimeLower) {
        if (mimeLower == null || mimeLower.isEmpty()) {
            return null;
        }
        if (ALLOWED_IMAGE_MIME.contains(mimeLower)) {
            return "image";
        }
        if (ALLOWED_VIDEO_MIME.contains(mimeLower)) {
            return "video";
        }
        if (ALLOWED_AUDIO_MIME.contains(mimeLower)) {
            return "audio";
        }
        return null;
    }

    private String categorizeByExt(String ext) {
        if (ext == null || ext.isEmpty() || "bin".equals(ext)) {
            return null;
        }
        if (ALLOWED_IMAGE_EXT.contains(ext)) {
            return "image";
        }
        if (ALLOWED_VIDEO_EXT.contains(ext)) {
            return "video";
        }
        if (ALLOWED_AUDIO_EXT.contains(ext)) {
            return "audio";
        }
        return null;
    }

    private void checkSize(String type, long size) {
        long max = switch (type) {
            case "image" -> MAX_IMAGE_BYTES;
            case "video" -> MAX_VIDEO_BYTES;
            case "audio" -> MAX_AUDIO_BYTES;
            default -> MAX_IMAGE_BYTES;
        };
        if (size > max) {
            throw new BusinessException(ErrorCode.MEDIA_FILE_TOO_LARGE);
        }
    }

    private String extractExt(String filename) {
        if (filename == null) {
            return "bin";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "bin";
        }
        return filename.substring(dot + 1).toLowerCase();
    }

    private String extractExtFromMime(String mimeType) {
        if (mimeType == null) return "bin";
        String mime = mimeType.toLowerCase();
        if (mime.contains("jpeg") || mime.contains("jpg")) return "jpg";
        if (mime.contains("png")) return "png";
        if (mime.contains("gif")) return "gif";
        if (mime.contains("webp")) return "webp";
        if (mime.contains("bmp")) return "bmp";
        if (mime.contains("mp4")) return "mp4";
        if (mime.contains("webm")) return "webm";
        if (mime.contains("mp3")) return "mp3";
        if (mime.contains("wav")) return "wav";
        if (mime.contains("mpeg") || mime.contains("mpeg4")) return "mpeg";
        return "bin";
    }

    private String buildObjectKey(Long userId, String ext) {
        String ym = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        return String.format("media/%d/%s/%s.%s", userId, ym, UUID.randomUUID().toString().replace("-", ""), ext);
    }
}