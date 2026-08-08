package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jurong.aicenter.dto.PageResult;
import com.jurong.aicenter.dto.media.MediaAssetResponse;
import com.jurong.aicenter.dto.media.MediaListQuery;
import com.jurong.aicenter.dto.media.MediaRoleDto;
import com.jurong.aicenter.dto.media.MediaUploadResponse;
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

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
    private static final List<Map<String, String>> CATEGORIES = List.of(
        Map.of("key", "face", "label", "逼真人脸"),
        Map.of("key", "urban-blue", "label", "都市蓝领"),
        Map.of("key", "urban-silver", "label", "都市银发"),
        Map.of("key", "kids", "label", "儿童"),
        Map.of("key", "mom", "label", "精致妈妈"),
        Map.of("key", "town-young", "label", "小镇青年"),
        Map.of("key", "town-mid", "label", "小镇中老年"),
        Map.of("key", "fantasy", "label", "二次元"),
        Map.of("key", "chinese", "label", "国风"),
        Map.of("key", "fashion", "label", "时尚模特"),
        Map.of("key", "animal", "label", "动物")
    );

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
        return libraryService.getUploadLibrary(userId).getId();
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
        try {
            return storageService.getPresignedUrl(objectKey, 24);
        } catch (Exception e) {
            log.warn("Generate presigned URL failed for object {}: {}", objectKey, e.getMessage());
            return null;
        }
    }

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

    private String buildObjectKey(Long userId, String ext) {
        String ym = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        return String.format("media/%d/%s/%s.%s", userId, ym, UUID.randomUUID().toString().replace("-", ""), ext);
    }
}
