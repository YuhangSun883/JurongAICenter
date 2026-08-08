package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jurong.aicenter.dto.PageResult;
import com.jurong.aicenter.dto.media.MediaAssetResponse;
import com.jurong.aicenter.dto.media.MediaListQuery;
import com.jurong.aicenter.dto.media.MediaUploadResponse;
import com.jurong.aicenter.entity.MediaAsset;
import com.jurong.aicenter.entity.MediaLibrary;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.MediaAssetRepository;
import com.jurong.aicenter.repository.MediaLibraryRepository;
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

/**
 * 媒体素材服务实现（资产库相关见 {@link MediaLibraryServiceImpl}）。
 *
 * <p>对应 controller: MediaController
 *
 * <p>关键设计：
 *   - 上传文件走 StorageService.uploadObject()，key 形如 "media/{userId}/{yyyy-MM}/{uuid}.{ext}"
 *   - 删除时同时软删 DB 行 + 删 MinIO 对象（最佳努力）
 *   - 严格按 userId 隔离
 *   - 上传类型校验：MIME + 扩展名双重白名单 + 黑名单（可执行/脚本/压缩包等绝对禁掉）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaServiceImpl implements MediaService {

    private final MediaAssetRepository assetRepository;
    private final MediaLibraryRepository libraryRepository;
    private final MediaLibraryService libraryService;
    private final StorageService storageService;

    private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;   // 20M
    private static final long MAX_VIDEO_BYTES = 200L * 1024 * 1024;  // 200M
    private static final long MAX_AUDIO_BYTES = 50L * 1024 * 1024;   // 50M

    // ============ 类型白名单 ============
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
    private static final Set<String> ALLOWED_IMAGE_EXT = Set.of(
        "jpg", "jpeg", "png", "gif", "webp", "bmp"
    );
    private static final Set<String> ALLOWED_VIDEO_EXT = Set.of(
        "mp4", "webm", "mov", "avi", "mkv"
    );
    private static final Set<String> ALLOWED_AUDIO_EXT = Set.of(
        "mp3", "wav", "ogg", "m4a", "aac"
    );

    // ============ 类型黑名单（绝对禁止） ============
    private static final Set<String> BLACKLIST_EXT = Set.of(
        // 可执行 / 脚本
        "exe", "msi", "bat", "cmd", "sh", "ps1", "vbs", "js", "jar", "app", "dmg", "apk", "ipa",
        // 脚本语言源文件
        "php", "jsp", "asp", "aspx", "py", "rb", "pl", "lua", "go", "rs", "ts", "tsx", "jsx",
        // 文档 / 表格 / 演示
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf",
        // 压缩包
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso",
        // 配置 / 证书 / 密钥
        "env", "ini", "conf", "cfg", "keystore", "jks", "pem", "key", "crt",
        // 数据库
        "sql", "db", "sqlite", "mdb",
        // 字体（可能用于钓鱼）
        "ttf", "otf", "woff", "woff2", "eot",
        // HTML（XSS 风险）
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

    @Override
    public PageResult<MediaAssetResponse> listAssets(Long userId, MediaListQuery query) {
        int page = query.getPage() == null || query.getPage() < 1 ? 1 : query.getPage();
        int size = query.getPageSize() == null || query.getPageSize() < 1 ? 20 : Math.min(query.getPageSize(), 100);

        LambdaQueryWrapper<MediaAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaAsset::getUserId, userId);
        if (query.getLibraryId() != null) {
            wrapper.eq(MediaAsset::getLibraryId, query.getLibraryId());
        }
        if (query.getType() != null && !query.getType().isBlank()) {
            wrapper.eq(MediaAsset::getType, query.getType());
        }
        if (query.getSource() != null && !query.getSource().isBlank()) {
            wrapper.eq(MediaAsset::getSource, query.getSource());
        }
        if (query.getKeyword() != null && !query.getKeyword().isBlank()) {
            wrapper.like(MediaAsset::getName, query.getKeyword().trim());
        }
        wrapper.orderByDesc(MediaAsset::getCreatedAt);

        Page<MediaAsset> mpPage = assetRepository.selectPage(new Page<>(page, size), wrapper);
        List<MediaAsset> records = mpPage.getRecords();

        // 批量取库名（避免 N+1）
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
            if (lib != null) libraryName = lib.getName();
        }
        return toResponse(asset, libraryName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaUploadResponse uploadAsset(Long userId, Long libraryId, MultipartFile file) {
        // 1. 基本校验
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.MEDIA_FILE_EMPTY);
        }
        String mimeType = file.getContentType();
        String type = inferType(mimeType, file.getOriginalFilename());
        if (type == null) {
            throw new BusinessException(ErrorCode.MEDIA_ASSET_TYPE_INVALID,
                "不支持的文件类型: mime=" + mimeType + ", filename=" + file.getOriginalFilename()
                    + "。仅支持图片(jpg/png/gif/webp/bmp)、视频(mp4/webm/mov/avi/mkv)、音频(mp3/wav/ogg/m4a/aac)");
        }
        long size = file.getSize();
        checkSize(type, size);

        // 2. 解析目标 libraryId
        Long targetLibraryId = resolveLibraryId(userId, libraryId);

        // 3. 上传到 MinIO
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

        // 4. 写 DB
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

        log.info("Media uploaded: id={}, userId={}, type={}, size={}",
            asset.getId(), userId, type, size);
        return new MediaUploadResponse(asset.getId(), url, asset.getName(), type, size);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAsset(Long userId, Long assetId) {
        MediaAsset asset = mustGetOwnedAsset(userId, assetId);
        // 删 MinIO
        storageService.deleteFile(asset.getObjectKey());
        // 软删
        assetRepository.deleteById(asset.getId());
        log.info("Media deleted: id={}, userId={}", assetId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDeleteAssets(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        LambdaQueryWrapper<MediaAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaAsset::getUserId, userId).in(MediaAsset::getId, ids);
        List<MediaAsset> assets = assetRepository.selectList(wrapper);

        // 删 MinIO
        for (MediaAsset a : assets) {
            storageService.deleteFile(a.getObjectKey());
        }
        // 批量软删
        int deleted = assetRepository.delete(wrapper);
        log.info("Batch delete media: userId={}, requested={}, deleted={}", userId, ids.size(), deleted);
        return deleted;
    }

    @Override
    public MediaAssetResponse renameAsset(Long userId, Long assetId, String name) {
        MediaAsset asset = mustGetOwnedAsset(userId, assetId);
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "文件名不能为空");
        }
        String trimmed = name.trim();

        // 重名校验：同用户 + 同 libraryId（包含 null 表示"我的资产"跨库汇总）下排除自己
        if (!asset.getName().equals(trimmed) && existsByNameInSameLibrary(userId, asset.getLibraryId(), trimmed, asset.getId())) {
            throw new BusinessException(ErrorCode.MEDIA_ASSET_NAME_DUPLICATE,
                "当前库内已存在同名素材: " + trimmed);
        }

        asset.setName(trimmed);
        asset.setUpdatedAt(LocalDateTime.now());
        assetRepository.updateById(asset);
        String libraryName = null;
        if (asset.getLibraryId() != null) {
            MediaLibrary lib = libraryRepository.selectById(asset.getLibraryId());
            if (lib != null) libraryName = lib.getName();
        }
        return toResponse(asset, libraryName);
    }

    @Override
    public void recordAiGenerated(Long userId, String type, String filename,
                                   String mimeType, Long sizeBytes, String objectKey,
                                   String sourceTool, String sourceTaskId) {
        // 写入 "AI 生成结果" 库
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

        for (MediaAsset a : assets) {
            storageService.deleteFile(a.getObjectKey());
        }
        if (!assets.isEmpty()) {
            assetRepository.delete(wrapper);
        }
        log.info("Cascade delete assets by library: userId={}, libraryId={}, count={}",
            userId, libraryId, assets.size());
    }

    // ============== 内部工具 ==============

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
        // 未指定 → 进 "我的资产"
        return libraryService.getUploadLibrary(userId).getId();
    }

    /** 同用户 + 同一 libraryId 下的同名素材数量（排除自己） */
    private boolean existsByNameInSameLibrary(Long userId, Long libraryId, String name, Long excludeAssetId) {
        Long count = assetRepository.selectCount(
            new LambdaQueryWrapper<MediaAsset>()
                .eq(MediaAsset::getUserId, userId)
                .eq(MediaAsset::getLibraryId, libraryId)
                .eq(MediaAsset::getName, name)
                .ne(MediaAsset::getId, excludeAssetId)
        );
        return count != null && count > 0;
    }

    private Map<Long, String> batchLoadLibraryNames(Long userId, List<MediaAsset> assets) {
        if (assets.isEmpty()) return Map.of();
        List<Long> ids = assets.stream()
            .map(MediaAsset::getLibraryId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (ids.isEmpty()) return Map.of();
        LambdaQueryWrapper<MediaLibrary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaLibrary::getUserId, userId).in(MediaLibrary::getId, ids);
        List<MediaLibrary> libs = libraryRepository.selectList(wrapper);
        return libs.stream().collect(Collectors.toMap(
            MediaLibrary::getId, MediaLibrary::getName, (a, b) -> a));
    }

    private MediaAssetResponse toResponse(MediaAsset a, String libraryName) {
        MediaAssetResponse r = new MediaAssetResponse();
        r.setId(a.getId());
        r.setLibraryId(a.getLibraryId());
        r.setLibraryName(libraryName);
        r.setType(a.getType());
        r.setSource(a.getSource());
        r.setName(a.getName());
        r.setMimeType(a.getMimeType());
        r.setSizeBytes(a.getSizeBytes());
        r.setWidth(a.getWidth());
        r.setHeight(a.getHeight());
        r.setDurationSec(a.getDurationSec());
        // 24h 预签名 URL（前端可直接展示）
        try {
            r.setUrl(storageService.getPresignedUrl(a.getObjectKey(), 24));
        } catch (Exception e) {
            log.warn("Generate presigned URL failed for asset {}: {}", a.getId(), e.getMessage());
        }
        r.setSourceTool(a.getSourceTool());
        r.setSourceTaskId(a.getSourceTaskId());
        r.setCreatedAt(a.getCreatedAt());
        r.setUpdatedAt(a.getUpdatedAt());
        return r;
    }

    private String inferType(String mime, String filename) {
        String mimeLower = mime == null ? "" : mime.toLowerCase().trim();
        String ext = extractExt(filename);

        // 0. 黑名单：可执行 / 脚本 / 压缩包等绝对不允许
        if (BLACKLIST_EXT.contains(ext)) {
            log.warn("Upload blocked by extension blacklist: ext={}, filename={}", ext, filename);
            return null;
        }
        if (!mimeLower.isEmpty() && BLACKLIST_MIME.contains(mimeLower)) {
            log.warn("Upload blocked by mime blacklist: mime={}, filename={}", mimeLower, filename);
            return null;
        }

        // 1. MIME 与扩展名都必须在白名单内，且必须指向同一类（image/video/audio）
        String mimeCategory = categorizeByMime(mimeLower);
        String extCategory = categorizeByExt(ext);

        if (mimeCategory != null && extCategory != null) {
            if (!mimeCategory.equals(extCategory)) {
                // 类别不一致 → 拒绝（如 exe 改名 png + application/octet-stream）
                log.warn("Upload rejected: mime/extension category mismatch: mime={}({}), ext={}({}), filename={}",
                    mimeLower, mimeCategory, ext, extCategory, filename);
                return null;
            }
            return mimeCategory;
        }

        // 2. 只有 MIME 命中 → 接受
        if (mimeCategory != null) {
            return mimeCategory;
        }
        // 3. 只有扩展名命中 → 接受
        if (extCategory != null) {
            return extCategory;
        }
        // 4. 都没命中 → 拒绝
        log.warn("Upload rejected: mime={}, ext={}, filename={}", mimeLower, ext, filename);
        return null;
    }

    private String categorizeByMime(String mimeLower) {
        if (mimeLower.isEmpty()) return null;
        if (ALLOWED_IMAGE_MIME.contains(mimeLower)) return "image";
        if (ALLOWED_VIDEO_MIME.contains(mimeLower)) return "video";
        if (ALLOWED_AUDIO_MIME.contains(mimeLower)) return "audio";
        return null;
    }

    private String categorizeByExt(String ext) {
        if (ext == null || ext.isEmpty() || "bin".equals(ext)) return null;
        if (ALLOWED_IMAGE_EXT.contains(ext)) return "image";
        if (ALLOWED_VIDEO_EXT.contains(ext)) return "video";
        if (ALLOWED_AUDIO_EXT.contains(ext)) return "audio";
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
            throw new BusinessException(ErrorCode.MEDIA_FILE_TOO_LARGE,
                "文件大小 " + (size / 1024 / 1024) + "M 超过限制 " + (max / 1024 / 1024) + "M");
        }
    }

    private String extractExt(String filename) {
        if (filename == null) return "bin";
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "bin";
        return filename.substring(dot + 1).toLowerCase();
    }

    private String buildObjectKey(Long userId, String ext) {
        String ym = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        return String.format("media/%d/%s/%s.%s", userId, ym,
            UUID.randomUUID().toString().replace("-", ""), ext);
    }
}