package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jurong.aicenter.dto.media.MediaAssetDto;
import com.jurong.aicenter.dto.media.MediaLibraryDto;
import com.jurong.aicenter.dto.media.MediaRoleDto;
import com.jurong.aicenter.dto.media.UploadMediaResponse;
import com.jurong.aicenter.entity.MediaAsset;
import com.jurong.aicenter.entity.MediaLibrary;
import com.jurong.aicenter.entity.MediaRole;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.MediaAssetRepository;
import com.jurong.aicenter.repository.MediaLibraryRepository;
import com.jurong.aicenter.repository.MediaRoleRepository;
import com.jurong.aicenter.service.MediaService;
import com.jurong.aicenter.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 媒体资产服务实现
 *
 * <p>对应 controller: MediaController
 *
 * <p>关键设计：
 *   - 用户首次访问 /api/media/libraries 时，自动建 2 个系统默认库
 *     (system-uploaded + system-ai) + "默认分组"
 *   - 上传文件直接走 StorageService.uploadObject()，key 形如 "media/{userId}/{assetId}/{filename}"
 *   - 删除时同时软删 DB 行 + 删 MinIO 对象
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaServiceImpl implements MediaService {

    private final MediaLibraryRepository libraryRepo;
    private final MediaAssetRepository assetRepo;
    private final MediaRoleRepository roleRepo;
    private final StorageService storageService;

    private static final String LIB_TYPE_SYSTEM_UPLOADED = "system-uploaded";
    private static final String LIB_TYPE_SYSTEM_AI = "system-ai";
    private static final String LIB_TYPE_CUSTOM = "custom";

    /** 11 个标准分类（顺序固定） */
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

    // ============= 资产库 =============

    @Override
    public List<MediaLibraryDto> listLibraries(Long userId) {
        // 1) 查用户已有库
        List<MediaLibrary> existing = libraryRepo.selectList(
            new QueryWrapper<MediaLibrary>()
                .eq("user_id", userId)
                .orderByAsc("sort_order", "id")
        );

        // 2) 自动建 2 个系统默认库（首次）
        if (existing.stream().noneMatch(l -> LIB_TYPE_SYSTEM_UPLOADED.equals(l.getType()))) {
            MediaLibrary lib = createLibraryInternal(userId, "我的资产", LIB_TYPE_SYSTEM_UPLOADED, "folder", 0);
            existing.add(0, lib);
        }
        if (existing.stream().noneMatch(l -> LIB_TYPE_SYSTEM_AI.equals(l.getType()))) {
            MediaLibrary lib = createLibraryInternal(userId, "AI 生成结果", LIB_TYPE_SYSTEM_AI, "sparkles", 1);
            existing.add(1, lib);
        }

        // 3) 聚合每个库的 assetCount
        List<Long> libIds = existing.stream().map(MediaLibrary::getId).toList();
        Map<Long, Long> countByLib = libIds.isEmpty()
            ? Map.of()
            : assetRepo.selectMaps(
                new QueryWrapper<MediaAsset>()
                    .select("library_id AS libraryId, COUNT(*) AS cnt")
                    .in("library_id", libIds)
                    .eq("deleted", 0)
                    .groupBy("library_id")
            ).stream().collect(Collectors.toMap(
                m -> ((Number) m.get("libraryId")).longValue(),
                m -> ((Number) m.get("cnt")).longValue()
            ));

        // 4) 转 DTO
        return existing.stream()
            .map(l -> MediaLibraryDto.from(l, countByLib.getOrDefault(l.getId(), 0L)))
            .toList();
    }

    private MediaLibrary createLibraryInternal(Long userId, String name, String type, String iconKey, int sortOrder) {
        MediaLibrary lib = new MediaLibrary();
        lib.setUserId(userId);
        lib.setName(name);
        lib.setType(type);
        lib.setIconKey(iconKey);
        lib.setSortOrder(sortOrder);
        lib.setDeleted(0);
        LocalDateTime now = LocalDateTime.now();
        lib.setCreatedAt(now);
        lib.setUpdatedAt(now);
        libraryRepo.insert(lib);
        log.info("Auto-created media library: userId={}, name={}, type={}", userId, name, type);
        return lib;
    }

    // ============= 素材 =============

    @Override
    public Map<String, Object> listAssets(Long userId, Long libraryId, String type,
                                          String source, String keyword,
                                          int page, int pageSize) {
        page = Math.max(page, 1);
        pageSize = Math.max(1, Math.min(pageSize, 100));

        QueryWrapper<MediaAsset> qw = new QueryWrapper<>();
        qw.eq("user_id", userId);
        if (libraryId != null) qw.eq("library_id", libraryId);
        if (type != null && !type.isBlank()) qw.eq("type", type);
        if (source != null && !source.isBlank()) qw.eq("source", source);
        if (keyword != null && !keyword.isBlank()) qw.like("name", keyword.trim());
        qw.orderByDesc("created_at");

        long total = assetRepo.selectCount(qw);
        qw.last("LIMIT " + pageSize + " OFFSET " + ((page - 1) * pageSize));
        List<MediaAsset> assets = assetRepo.selectList(qw);

        List<MediaAssetDto> items = assets.stream()
            .map(a -> MediaAssetDto.from(a, presign(a.getObjectKey())))
            .toList();

        Map<String, Object> result = new HashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
    }

    @Override
    public MediaAssetDto getAsset(Long userId, Long assetId) {
        MediaAsset a = mustOwnedAsset(userId, assetId);
        return MediaAssetDto.from(a, presign(a.getObjectKey()));
    }

    @Override
    @Transactional
    public UploadMediaResponse upload(Long userId, Long libraryId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "文件不能为空");
        }
        if (file.getSize() > 200 * 1024 * 1024L) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "文件大小不能超过 200MB");
        }

        // 1) 解析 type
        String mime = file.getContentType();
        String type;
        if (mime == null) {
            // fallback by filename
            String n = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
            if (n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".webm")) type = "video";
            else if (n.endsWith(".mp3") || n.endsWith(".wav") || n.endsWith(".m4a")) type = "audio";
            else type = "image";
        } else if (mime.startsWith("image/")) type = "image";
        else if (mime.startsWith("video/")) type = "video";
        else if (mime.startsWith("audio/")) type = "audio";
        else throw new BusinessException(ErrorCode.INVALID_PARAM, "仅支持图片/视频/音频");

        // 2) 决定 libraryId（默认 "我的资产"）
        Long targetLibId = libraryId;
        if (targetLibId == null) {
            List<MediaLibrary> libs = libraryRepo.selectList(
                new QueryWrapper<MediaLibrary>()
                    .eq("user_id", userId)
                    .eq("type", LIB_TYPE_SYSTEM_UPLOADED)
                    .last("LIMIT 1")
            );
            targetLibId = libs.isEmpty() ? null : libs.get(0).getId();
        }
        if (targetLibId != null) {
            // 校验库归属
            MediaLibrary lib = libraryRepo.selectById(targetLibId);
            if (lib == null || !lib.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "无权上传到此库");
            }
        }

        // 3) 先插一行拿 id（用 id 拼 objectKey）
        MediaAsset a = new MediaAsset();
        a.setUserId(userId);
        a.setLibraryId(targetLibId);
        a.setType(type);
        a.setSource("uploaded");
        a.setName(file.getOriginalFilename() != null ? file.getOriginalFilename() : "未命名");
        a.setMimeType(mime);
        a.setSizeBytes(file.getSize());
        a.setDeleted(0);
        LocalDateTime now = LocalDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        // objectKey 暂时写 placeholder,insert 后拿到 id 再 update
        a.setObjectKey("media/" + userId + "/pending/" + a.getName());
        assetRepo.insert(a);

        // 4) 上传到 MinIO
        String objectKey = "media/" + userId + "/" + a.getId() + "/" + sanitize(a.getName());
        String url;
        try {
            url = storageService.uploadObject(objectKey, file.getInputStream(), mime);
        } catch (IOException e) {
            // 上传失败 → 删回 DB 行
            assetRepo.deleteById(a.getId());
            log.error("MinIO upload failed for userId={}", userId, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "上传失败：" + e.getMessage());
        }

        // 5) 更新 objectKey + 返回 URL
        a.setObjectKey(objectKey);
        assetRepo.updateById(a);
        log.info("Media uploaded: id={}, userId={}, name={}, size={}", a.getId(), userId, a.getName(), file.getSize());

        return new UploadMediaResponse(
            a.getId(),
            a.getLibraryId(),
            a.getType(),
            a.getSource(),
            a.getName(),
            a.getMimeType(),
            a.getSizeBytes(),
            null,  // width 暂无（要 ImageImage/ ffprobe）
            null,
            null,  // durationSec 暂无
            url
        );
    }

    @Override
    @Transactional
    public void deleteAsset(Long userId, Long assetId) {
        MediaAsset a = mustOwnedAsset(userId, assetId);
        // 软删
        assetRepo.deleteById(a.getId());
        // 删 MinIO（最佳努力，失败仅 log）
        try {
            if (a.getObjectKey() != null && !a.getObjectKey().isBlank()) {
                storageService.deleteFile(a.getObjectKey());
            }
        } catch (Exception e) {
            log.warn("MinIO delete failed (DB 已软删): objectKey={}", a.getObjectKey(), e);
        }
        log.info("Media asset deleted: id={}, userId={}", assetId, userId);
    }

    // ============= 角色库 =============

    @Override
    public List<Map<String, String>> listCategories() {
        return CATEGORIES;
    }

    @Override
    public List<MediaRoleDto> listRolesByCategory(String category) {
        QueryWrapper<MediaRole> qw = new QueryWrapper<>();
        if (category != null && !category.isBlank()) {
            qw.eq("category", category);
        }
        qw.orderByAsc("sort_order", "id");
        List<MediaRole> roles = roleRepo.selectList(qw);
        return roles.stream().map(MediaRoleDto::from).toList();
    }

    @Override
    public List<MediaRoleDto> listAllRoles() {
        return listRolesByCategory(null);
    }

    // ============= helpers =============

    private MediaAsset mustOwnedAsset(Long userId, Long assetId) {
        MediaAsset a = assetRepo.selectById(assetId);
        if (a == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "素材不存在");
        }
        if (!a.getUserId().equals(userId)) {
            // 脱敏：跟前端保持一致不说"无权限"
            throw new BusinessException(ErrorCode.NOT_FOUND, "素材不存在");
        }
        return a;
    }

    /** 拼预签名 URL,失败兜底返回 null */
    private String presign(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) return null;
        try {
            return storageService.getPresignedUrl(objectKey, 24);
        } catch (Exception e) {
            log.warn("PresignGet failed: {}", objectKey, e);
            return null;
        }
    }

    /** 文件名清洗:去除路径分隔符 + 非法字符 */
    private String sanitize(String name) {
        if (name == null || name.isBlank()) return "file";
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (s.length() > 200) s = s.substring(s.length() - 200);
        return s;
    }
}