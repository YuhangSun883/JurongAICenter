package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jurong.aicenter.dto.media.CreateLibraryRequest;
import com.jurong.aicenter.dto.media.MediaLibraryResponse;
import com.jurong.aicenter.dto.media.RenameLibraryRequest;
import com.jurong.aicenter.entity.MediaAsset;
import com.jurong.aicenter.entity.MediaLibrary;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.MediaAssetRepository;
import com.jurong.aicenter.repository.MediaLibraryRepository;
import com.jurong.aicenter.service.MediaLibraryService;
import com.jurong.aicenter.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MediaLibraryServiceImpl implements MediaLibraryService {

    private final MediaLibraryRepository libraryRepository;
    private final MediaAssetRepository assetRepository;
    private final StorageService storageService;

    private static final String TYPE_UPLOADED = "system-uploaded";
    private static final String TYPE_AI = "system-ai";
    private static final String TYPE_CUSTOM = "custom";

    @Override
    public List<MediaLibraryResponse> listLibraries(Long userId) {
        // 1. 查用户已有库
        LambdaQueryWrapper<MediaLibrary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaLibrary::getUserId, userId)
               .orderByAsc(MediaLibrary::getSortOrder)
               .orderByAsc(MediaLibrary::getId);
        List<MediaLibrary> libs = libraryRepository.selectList(wrapper);

        // 2. 自动建 2 个系统默认库（首次访问时）
        if (libs.stream().noneMatch(l -> TYPE_UPLOADED.equals(l.getType()))) {
            MediaLibrary uploaded = new MediaLibrary();
            uploaded.setUserId(userId);
            uploaded.setName("我的资产");
            uploaded.setType(TYPE_UPLOADED);
            uploaded.setIconKey("folder");
            uploaded.setSortOrder(0);
            uploaded.setCreatedAt(LocalDateTime.now());
            uploaded.setUpdatedAt(LocalDateTime.now());
            libraryRepository.insert(uploaded);
            libs.add(0, uploaded);
            log.info("Auto-created '我的资产' for userId={}, id={}", userId, uploaded.getId());
        }
        if (libs.stream().noneMatch(l -> TYPE_AI.equals(l.getType()))) {
            MediaLibrary ai = new MediaLibrary();
            ai.setUserId(userId);
            ai.setName("AI 生成结果");
            ai.setType(TYPE_AI);
            ai.setIconKey("sparkles");
            ai.setSortOrder(1);
            ai.setCreatedAt(LocalDateTime.now());
            ai.setUpdatedAt(LocalDateTime.now());
            libraryRepository.insert(ai);
            libs.add(1, ai);
            log.info("Auto-created 'AI 生成结果' for userId={}, id={}", userId, ai.getId());
        }

        // 3. 统计每个库的素材数量
        Map<Long, Long> countMap = countAssetsByLibrary(userId, libs);

        return libs.stream().map(lib -> toResponse(lib, countMap)).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaLibraryResponse createLibrary(Long userId, CreateLibraryRequest request) {
        // 1. 重名检查
        if (existsByName(userId, request.getName())) {
            throw new BusinessException(ErrorCode.MEDIA_LIBRARY_NAME_DUPLICATE,
                "资产库名称已存在: " + request.getName());
        }

        // 2. 计算 sort_order：当前最大值 + 1
        Integer nextOrder = nextSortOrder(userId);

        MediaLibrary lib = new MediaLibrary();
        lib.setUserId(userId);
        lib.setName(request.getName());
        lib.setType(TYPE_CUSTOM);
        lib.setIconKey(request.getIconKey() == null ? "folder" : request.getIconKey());
        lib.setDescription(request.getDescription());
        lib.setSortOrder(nextOrder);
        lib.setCreatedAt(LocalDateTime.now());
        lib.setUpdatedAt(LocalDateTime.now());
        libraryRepository.insert(lib);

        log.info("Library created: id={}, userId={}, name={}", lib.getId(), userId, lib.getName());
        return toResponse(lib, Map.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaLibraryResponse renameLibrary(Long userId, Long libraryId, RenameLibraryRequest request) {
        MediaLibrary lib = mustGetOwnedLibrary(userId, libraryId);
        if (isSystem(lib)) {
            throw new BusinessException(ErrorCode.MEDIA_LIBRARY_IS_SYSTEM_CANNOT_MODIFY,
                "系统默认库不可修改: " + lib.getName());
        }

        // 重名检查（排除自己）
        if (!lib.getName().equals(request.getName()) && existsByName(userId, request.getName())) {
            throw new BusinessException(ErrorCode.MEDIA_LIBRARY_NAME_DUPLICATE,
                "资产库名称已存在: " + request.getName());
        }

        lib.setName(request.getName());
        if (request.getIconKey() != null && !request.getIconKey().isBlank()) {
            lib.setIconKey(request.getIconKey());
        }
        lib.setUpdatedAt(LocalDateTime.now());
        libraryRepository.updateById(lib);
        log.info("Library renamed: id={}, newName={}", libraryId, lib.getName());
        return toResponse(lib, Map.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteLibrary(Long userId, Long libraryId) {
        MediaLibrary lib = mustGetOwnedLibrary(userId, libraryId);
        if (isSystem(lib)) {
            throw new BusinessException(ErrorCode.MEDIA_LIBRARY_IS_SYSTEM_CANNOT_MODIFY,
                "系统默认库不可删除: " + lib.getName());
        }

        // 1. 先级联删除库内素材（连 MinIO），内联实现避免循环依赖
        LambdaQueryWrapper<MediaAsset> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaAsset::getUserId, userId).eq(MediaAsset::getLibraryId, libraryId);
        java.util.List<MediaAsset> assets = assetRepository.selectList(wrapper);
        for (MediaAsset a : assets) {
            storageService.deleteFile(a.getObjectKey());
        }
        if (!assets.isEmpty()) {
            assetRepository.delete(wrapper);
        }
        log.info("Cascade delete assets by library: userId={}, libraryId={}, count={}",
            userId, libraryId, assets.size());

        // 2. 软删库
        libraryRepository.deleteById(libraryId);
        log.info("Library deleted: id={}, userId={}", libraryId, userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createDefaultLibraries(Long userId) {
        // 库 1：我的资产（装用户上传）
        MediaLibrary uploaded = new MediaLibrary();
        uploaded.setUserId(userId);
        uploaded.setName("我的资产");
        uploaded.setType(TYPE_UPLOADED);
        uploaded.setIconKey("folder");
        uploaded.setSortOrder(0);
        uploaded.setCreatedAt(LocalDateTime.now());
        uploaded.setUpdatedAt(LocalDateTime.now());
        libraryRepository.insert(uploaded);

        // 库 2：AI 生成结果（装 AI 生成）
        MediaLibrary ai = new MediaLibrary();
        ai.setUserId(userId);
        ai.setName("AI 生成结果");
        ai.setType(TYPE_AI);
        ai.setIconKey("sparkles");
        ai.setSortOrder(1);
        ai.setCreatedAt(LocalDateTime.now());
        ai.setUpdatedAt(LocalDateTime.now());
        libraryRepository.insert(ai);

        log.info("Default libraries created for userId={}: uploaded={}, ai={}",
            userId, uploaded.getId(), ai.getId());
    }

    @Override
    public MediaLibrary getAiLibrary(Long userId) {
        LambdaQueryWrapper<MediaLibrary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaLibrary::getUserId, userId)
               .eq(MediaLibrary::getType, TYPE_AI);
        MediaLibrary lib = libraryRepository.selectOne(wrapper);
        if (lib == null) {
            throw new BusinessException(ErrorCode.MEDIA_LIBRARY_NOT_FOUND,
                "AI 生成结果库未初始化，请联系管理员");
        }
        return lib;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaLibrary getOrCreateUploadLibrary(Long userId) {
        LambdaQueryWrapper<MediaLibrary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaLibrary::getUserId, userId)
               .eq(MediaLibrary::getType, TYPE_UPLOADED);
        MediaLibrary lib = libraryRepository.selectOne(wrapper);
        if (lib != null) return lib;

        // 没有"我的资产"库时自动建一个，避免上传时 7002
        lib = new MediaLibrary();
        lib.setUserId(userId);
        lib.setName("我的资产");
        lib.setType(TYPE_UPLOADED);
        lib.setIconKey("upload");
        lib.setSortOrder(10); // 系统库排在前面，留 0 给 system-ai
        lib.setCreatedAt(LocalDateTime.now());
        lib.setUpdatedAt(LocalDateTime.now());
        libraryRepository.insert(lib);
        log.info("Auto-created '我的资产' library for userId={}: id={}", userId, lib.getId());
        return lib;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaLibrary getOrCreateDefaultCustom(Long userId) {
        LambdaQueryWrapper<MediaLibrary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaLibrary::getUserId, userId)
               .eq(MediaLibrary::getType, TYPE_CUSTOM)
               .orderByAsc(MediaLibrary::getSortOrder)
               .last("LIMIT 1");
        MediaLibrary lib = libraryRepository.selectOne(wrapper);
        if (lib != null) return lib;

        // 没有 custom 库时建一个"未分类"
        lib = new MediaLibrary();
        lib.setUserId(userId);
        lib.setName("未分类");
        lib.setType(TYPE_CUSTOM);
        lib.setIconKey("folder");
        lib.setSortOrder(100);
        lib.setCreatedAt(LocalDateTime.now());
        lib.setUpdatedAt(LocalDateTime.now());
        libraryRepository.insert(lib);
        log.info("Auto-created '未分类' library for userId={}: id={}", userId, lib.getId());
        return lib;
    }

    // ============== 内部工具 ==============

    private MediaLibrary mustGetOwnedLibrary(Long userId, Long libraryId) {
        MediaLibrary lib = libraryRepository.selectById(libraryId);
        if (lib == null || !lib.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.MEDIA_LIBRARY_NOT_FOUND);
        }
        return lib;
    }

    private boolean isSystem(MediaLibrary lib) {
        return lib.getType() != null && lib.getType().startsWith("system-");
    }

    private boolean existsByName(Long userId, String name) {
        LambdaQueryWrapper<MediaLibrary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaLibrary::getUserId, userId)
               .eq(MediaLibrary::getName, name);
        return libraryRepository.selectCount(wrapper) > 0;
    }

    private Integer nextSortOrder(Long userId) {
        LambdaQueryWrapper<MediaLibrary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaLibrary::getUserId, userId)
               .orderByDesc(MediaLibrary::getSortOrder)
               .last("LIMIT 1");
        MediaLibrary last = libraryRepository.selectOne(wrapper);
        return last == null ? 0 : (last.getSortOrder() == null ? 0 : last.getSortOrder() + 1);
    }

    private Map<Long, Long> countAssetsByLibrary(Long userId, List<MediaLibrary> libs) {
        if (libs.isEmpty()) return Map.of();
        List<Long> ids = libs.stream().map(MediaLibrary::getId).toList();
        // 初始化：每个库默认 0
        Map<Long, Long> result = new java.util.HashMap<>();
        for (Long id : ids) result.put(id, 0L);

        // 1) 全部素材（按 userId）— 用于 "我的资产" 跨库汇总
        long totalForUser = 0L;
        // 2) AI 素材（按 userId + source='ai-generated'）— 用于 "AI 生成结果"
        long totalAiForUser = 0L;
        // 3) 各库计数（按 libraryId in (...)）— 用于自定义库
        Map<Long, Long> perLibrary = new java.util.HashMap<>();

        try {
            var all = assetRepository.selectList(
                new LambdaQueryWrapper<com.jurong.aicenter.entity.MediaAsset>()
                    .eq(com.jurong.aicenter.entity.MediaAsset::getUserId, userId)
                    .select(com.jurong.aicenter.entity.MediaAsset::getLibraryId,
                            com.jurong.aicenter.entity.MediaAsset::getSource)
            );
            for (var rec : all) {
                totalForUser++;
                if ("ai-generated".equals(rec.getSource())) {
                    totalAiForUser++;
                }
                if (rec.getLibraryId() != null) {
                    perLibrary.merge(rec.getLibraryId(), 1L, Long::sum);
                }
            }
        } catch (Exception e) {
            log.warn("countAssetsByLibrary aggregate failed: {}", e.getMessage());
        }

        // 按库类型写入最终值
        for (MediaLibrary lib : libs) {
            String type = lib.getType();
            if ("system-uploaded".equals(type)) {
                // 我的资产：跨库汇总（所有素材）
                result.put(lib.getId(), totalForUser);
            } else if ("system-ai".equals(type)) {
                // AI 生成结果：只算 AI 素材
                result.put(lib.getId(), totalAiForUser);
            } else {
                // 自定义库：本库内素材
                result.put(lib.getId(), perLibrary.getOrDefault(lib.getId(), 0L));
            }
        }
        return result;
    }

    private MediaLibraryResponse toResponse(MediaLibrary lib, Map<Long, Long> countMap) {
        MediaLibraryResponse r = new MediaLibraryResponse();
        r.setId(lib.getId());
        r.setName(lib.getName());
        r.setType(lib.getType());
        r.setIconKey(lib.getIconKey());
        r.setDescription(lib.getDescription());
        r.setSortOrder(lib.getSortOrder());
        r.setAssetCount(countMap.getOrDefault(lib.getId(), 0L));
        r.setCreatedAt(lib.getCreatedAt());
        r.setUpdatedAt(lib.getUpdatedAt());
        return r;
    }
}
