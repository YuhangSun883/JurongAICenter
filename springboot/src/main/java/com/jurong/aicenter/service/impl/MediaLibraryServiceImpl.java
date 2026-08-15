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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        // 1. 查用户已有库（含所有层级）
        LambdaQueryWrapper<MediaLibrary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaLibrary::getUserId, userId)
               .orderByAsc(MediaLibrary::getSortOrder)
               .orderByAsc(MediaLibrary::getId);
        List<MediaLibrary> libs = libraryRepository.selectList(wrapper);

        // 2. 自动建 2 个系统默认库（首次访问时）— 系统库永远是根库
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

        // 3. 统计每个库的素材数量 + 算 hasChildren
        Map<Long, Long> countMap = countAssetsByLibrary(userId, libs);
        Map<Long, Boolean> hasChildrenMap = computeHasChildrenMap(libs);

        return libs.stream().map(lib -> toResponse(lib, countMap, hasChildrenMap)).toList();
    }

    @Override
    public List<MediaLibraryResponse> listRootLibraries(Long userId) {
        List<MediaLibrary> roots = libraryRepository.listRoots(userId);
        Map<Long, Long> countMap = countAssetsByLibrary(userId, roots);
        Map<Long, Boolean> hasChildrenMap = computeHasChildrenMap(roots);
        return roots.stream().map(lib -> toResponse(lib, countMap, hasChildrenMap)).toList();
    }

    @Override
    public List<MediaLibraryResponse> listChildLibraries(Long userId, Long parentId) {
        // 父库归属校验
        MediaLibrary parent = mustGetOwnedLibrary(userId, parentId);
        if (isSystem(parent)) {
            // 系统库不展示子库（事实上也不允许有）
            return Collections.emptyList();
        }
        List<MediaLibrary> children = libraryRepository.listChildren(parentId);
        Map<Long, Long> countMap = countAssetsByLibrary(userId, children);
        Map<Long, Boolean> hasChildrenMap = computeHasChildrenMap(children);
        return children.stream().map(lib -> toResponse(lib, countMap, hasChildrenMap)).toList();
    }

    @Override
    public List<MediaLibraryResponse> getBreadcrumb(Long userId, Long libraryId) {
        // 归属校验
        mustGetOwnedLibrary(userId, libraryId);
        List<MediaLibrary> chain = libraryRepository.listAncestors(libraryId);
        Map<Long, Long> countMap = countAssetsByLibrary(userId, chain);
        Map<Long, Boolean> hasChildrenMap = computeHasChildrenMap(chain);
        return chain.stream().map(lib -> toResponse(lib, countMap, hasChildrenMap)).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaLibraryResponse createLibrary(Long userId, CreateLibraryRequest request) {
        // 1. 重名检查（同级兄弟节点不能重名，V19：parentId 决定比较范围）
        if (existsByName(userId, request.getName(), request.getParentId())) {
            throw new BusinessException(ErrorCode.MEDIA_LIBRARY_NAME_DUPLICATE,
                "同级已存在同名资产库: " + request.getName());
        }

        // 2. 真人库授权字段校验
        String bizType = request.getBizType() == null ? "normal" : request.getBizType();
        if ("real_person".equals(bizType)) {
            if (request.getAuthPurpose() == null || request.getAuthPurpose().isBlank()) {
                throw new BusinessException(ErrorCode.MEDIA_LIBRARY_INVALID_AUTH,
                    "真人库必须填写授权用途说明");
            }
            if (request.getAuthExpireAt() == null) {
                throw new BusinessException(ErrorCode.MEDIA_LIBRARY_INVALID_AUTH,
                    "真人库必须填写授权有效期");
            }
        }

        // 3. 父库校验（V19）
        if (request.getParentId() != null) {
            MediaLibrary parent = libraryRepository.selectById(request.getParentId());
            if (parent == null || !parent.getUserId().equals(userId)) {
                throw new BusinessException(ErrorCode.MEDIA_LIBRARY_PARENT_NOT_FOUND,
                    "父库不存在或不属于当前用户");
            }
            if (isSystem(parent)) {
                throw new BusinessException(ErrorCode.MEDIA_LIBRARY_PARENT_IS_SYSTEM,
                    "系统库不能作为父库");
            }
            // 虚拟人/真人库下子库类型必须一致
            String parentBiz = parent.getBizType() == null ? "normal" : parent.getBizType();
            if (("virtual_human".equals(parentBiz) || "real_person".equals(parentBiz))
                    && !parentBiz.equals(bizType)) {
                throw new BusinessException(ErrorCode.MEDIA_LIBRARY_PARENT_TYPE_MISMATCH,
                    "虚拟人/真人库的子库类型必须与父库一致（父库=" + parentBiz + "，当前=" + bizType + "）");
            }
        }

        // 4. 计算 sort_order：当前最大值 + 1
        Integer nextOrder = nextSortOrder(userId);

        MediaLibrary lib = new MediaLibrary();
        lib.setUserId(userId);
        lib.setName(request.getName());
        lib.setParentId(request.getParentId());
        lib.setType(TYPE_CUSTOM);
        lib.setIconKey(request.getIconKey() == null ? "folder" : request.getIconKey());
        lib.setDescription(request.getDescription());
        lib.setBizType(bizType);
        lib.setAuthPurpose(request.getAuthPurpose());
        lib.setAuthExpireAt(request.getAuthExpireAt());
        lib.setSortOrder(nextOrder);
        lib.setCreatedAt(LocalDateTime.now());
        lib.setUpdatedAt(LocalDateTime.now());
        libraryRepository.insert(lib);

        log.info("Library created: id={}, userId={}, name={}, bizType={}, parentId={}",
            lib.getId(), userId, lib.getName(), bizType, lib.getParentId());
        Map<Long, Long> countMap = countAssetsByLibrary(userId, List.of(lib));
        return toResponse(lib, countMap, Map.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaLibraryResponse renameLibrary(Long userId, Long libraryId, RenameLibraryRequest request) {
        MediaLibrary lib = mustGetOwnedLibrary(userId, libraryId);
        if (isSystem(lib)) {
            throw new BusinessException(ErrorCode.MEDIA_LIBRARY_IS_SYSTEM_CANNOT_MODIFY,
                "系统默认库不可修改: " + lib.getName());
        }

        // 重名检查（同级兄弟节点不能重名，排除自己）
        if (!lib.getName().equals(request.getName())
                && existsByName(userId, request.getName(), lib.getParentId())) {
            throw new BusinessException(ErrorCode.MEDIA_LIBRARY_NAME_DUPLICATE,
                "同级已存在同名资产库: " + request.getName());
        }

        // 2026-08-15 V19: bizType 创建后锁定，不允许通过 PATCH 修改
        // 真人库可继续维护 authPurpose / authExpireAt
        if (request.getBizType() != null) {
            String currentBiz = lib.getBizType() == null ? "normal" : lib.getBizType();
            if (!currentBiz.equals(request.getBizType())) {
                throw new BusinessException(ErrorCode.MEDIA_LIBRARY_BIZTYPE_IMMUTABLE,
                    "资产库业务类型创建后不可修改（当前=" + currentBiz + "，尝试改成=" + request.getBizType() + "）");
            }
        }

        lib.setName(request.getName());
        if (request.getIconKey() != null && !request.getIconKey().isBlank()) {
            lib.setIconKey(request.getIconKey());
        }
        // 2026-08-15 V19: bizType 不可修改（已在上方校验），这里不再 set
        if (request.getAuthPurpose() != null) {
            lib.setAuthPurpose(request.getAuthPurpose());
        }
        if (request.getAuthExpireAt() != null) {
            lib.setAuthExpireAt(request.getAuthExpireAt());
        }
        lib.setUpdatedAt(LocalDateTime.now());
        libraryRepository.updateById(lib);
        log.info("Library updated: id={}, newName={}, bizType={}", libraryId, lib.getName(), lib.getBizType());
        Map<Long, Long> countMap = countAssetsByLibrary(userId, List.of(lib));
        Map<Long, Boolean> hasChildrenMap = Map.of(lib.getId(), !libraryRepository.listChildren(lib.getId()).isEmpty());
        return toResponse(lib, countMap, hasChildrenMap);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteLibrary(Long userId, Long libraryId) {
        MediaLibrary lib = mustGetOwnedLibrary(userId, libraryId);
        if (isSystem(lib)) {
            throw new BusinessException(ErrorCode.MEDIA_LIBRARY_IS_SYSTEM_CANNOT_MODIFY,
                "系统默认库不可删除: " + lib.getName());
        }

        // 2026-08-15 V19: 级联删除整棵子树（含本库 + 所有后代）
        // 1) 拿到所有后代 id
        List<Long> allIds = libraryRepository.listDescendantIds(libraryId);
        if (allIds == null || allIds.isEmpty()) {
            // 防御：理论上应包含 libraryId 本身
            allIds = new ArrayList<>();
            allIds.add(libraryId);
        }

        // 2) 删每个库的素材（连 MinIO）
        int assetCount = 0;
        for (Long id : allIds) {
            LambdaQueryWrapper<MediaAsset> aw = new LambdaQueryWrapper<>();
            aw.eq(MediaAsset::getUserId, userId).eq(MediaAsset::getLibraryId, id);
            List<MediaAsset> assets = assetRepository.selectList(aw);
            for (MediaAsset a : assets) {
                try {
                    storageService.deleteFile(a.getObjectKey());
                } catch (Exception ex) {
                    log.warn("MinIO delete failed for objectKey={}: {}", a.getObjectKey(), ex.getMessage());
                }
            }
            if (!assets.isEmpty()) {
                assetRepository.delete(aw);
                assetCount += assets.size();
            }
        }
        log.info("Cascade delete assets by library subtree: userId={}, rootId={}, ids={}, assetCount={}",
            userId, libraryId, allIds, assetCount);

        // 3) 软删所有库（用 MyBatis Plus 的逻辑删除）
        //    BaseMapper.deleteById 会自动带上 deleted=0 条件并写入 deleted=1。
        //    但 BaseMapper.deleteBatchIds 不会触发 @TableLogic，
        //    所以我们用 updateWrapper 一次性软删，更安全。
        for (Long id : allIds) {
            libraryRepository.deleteById(id);
        }
        log.info("Library subtree deleted: userId={}, rootId={}, ids={}", userId, libraryId, allIds);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createDefaultLibraries(Long userId) {
        // 库 1：我的资产（装用户上传）— 根库
        MediaLibrary uploaded = new MediaLibrary();
        uploaded.setUserId(userId);
        uploaded.setName("我的资产");
        uploaded.setType(TYPE_UPLOADED);
        uploaded.setIconKey("folder");
        uploaded.setSortOrder(0);
        uploaded.setCreatedAt(LocalDateTime.now());
        uploaded.setUpdatedAt(LocalDateTime.now());
        libraryRepository.insert(uploaded);

        // 库 2：AI 生成结果（装 AI 生成）— 根库
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
               .isNull(MediaLibrary::getParentId)   // 2026-08-15 V19: 只看根级 custom
               .orderByAsc(MediaLibrary::getSortOrder)
               .last("LIMIT 1");
        MediaLibrary lib = libraryRepository.selectOne(wrapper);
        if (lib != null) return lib;

        // 没有根级 custom 库时建一个"未分类"
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

    /**
     * 2026-08-15 V19: 同父库下重名检查（兄弟节点不能重名，但子库与父库不冲突）
     * 注意：必须过滤掉已软删的（deleted=0），否则同名复活会失败
     * @param parentId null = 根级，否则指定父库
     */
    private boolean existsByName(Long userId, String name, Long parentId) {
        LambdaQueryWrapper<MediaLibrary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaLibrary::getUserId, userId)
               .eq(MediaLibrary::getName, name)
               .eq(MediaLibrary::getDeleted, 0);
        if (parentId == null) {
            wrapper.isNull(MediaLibrary::getParentId);
        } else {
            wrapper.eq(MediaLibrary::getParentId, parentId);
        }
        return libraryRepository.selectCount(wrapper) > 0;
    }

    // 重载：兼容旧调用
    private boolean existsByName(Long userId, String name) {
        // 旧行为：用户下全局唯一（包含所有层级），保留给特殊路径使用
        LambdaQueryWrapper<MediaLibrary> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(MediaLibrary::getUserId, userId)
               .eq(MediaLibrary::getName, name)
               .eq(MediaLibrary::getDeleted, 0);
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

    /**
     * 2026-08-15 V19: 一次性计算 hasChildren，避免 N+1
     */
    private Map<Long, Boolean> computeHasChildrenMap(List<MediaLibrary> libs) {
        if (libs == null || libs.isEmpty()) return Map.of();
        Map<Long, Boolean> result = new HashMap<>();
        // 用 IN 一次查完
        List<Long> ids = libs.stream().map(MediaLibrary::getId).toList();
        try {
            List<MediaLibrary> allChildren = libraryRepository.selectList(
                new LambdaQueryWrapper<MediaLibrary>()
                    .in(MediaLibrary::getParentId, ids)
                    .eq(MediaLibrary::getDeleted, 0)
                    .select(MediaLibrary::getParentId)
            );
            Set<Long> parentsWithChildren = new HashSet<>();
            for (MediaLibrary c : allChildren) {
                if (c.getParentId() != null) {
                    parentsWithChildren.add(c.getParentId());
                }
            }
            for (Long id : ids) {
                result.put(id, parentsWithChildren.contains(id));
            }
        } catch (Exception e) {
            log.warn("computeHasChildrenMap failed: {}", e.getMessage());
            for (Long id : ids) result.put(id, false);
        }
        return result;
    }

    private Map<Long, Long> countAssetsByLibrary(Long userId, List<MediaLibrary> libs) {
        if (libs.isEmpty()) return Map.of();
        List<Long> ids = libs.stream().map(MediaLibrary::getId).toList();
        // 初始化：每个库默认 0
        Map<Long, Long> result = new HashMap<>();
        for (Long id : ids) result.put(id, 0L);

        // 1) 全部素材（按 userId）— 用于 "我的资产" 跨库汇总
        long totalForUser = 0L;
        // 2) AI 素材（按 userId + source='ai-generated'）— 用于 "AI 生成结果"
        long totalAiForUser = 0L;
        // 3) 各库计数（按 libraryId in (...)）— 用于自定义库
        Map<Long, Long> perLibrary = new HashMap<>();

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
                // 我的资产：跨库汇总（所有素材）— V19 后所有素材库都参与
                result.put(lib.getId(), totalForUser);
            } else if ("system-ai".equals(type)) {
                // AI 生成结果：只算 AI 素材
                result.put(lib.getId(), totalAiForUser);
            } else {
                // 自定义库（含子库）：本库内素材
                result.put(lib.getId(), perLibrary.getOrDefault(lib.getId(), 0L));
            }
        }
        return result;
    }

    private MediaLibraryResponse toResponse(MediaLibrary lib, Map<Long, Long> countMap, Map<Long, Boolean> hasChildrenMap) {
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
        // V18 字段
        r.setBizType(lib.getBizType());
        r.setAuthPurpose(lib.getAuthPurpose());
        r.setAuthExpireAt(lib.getAuthExpireAt());
        r.setAuthStatus(computeAuthStatus(lib));
        // V19 字段
        r.setParentId(lib.getParentId());
        r.setHasChildren(hasChildrenMap.getOrDefault(lib.getId(), false));
        return r;
    }

    /**
     * 计算授权状态：valid / expired / none
     *   - 非 real_person：none
     *   - real_person 且 authExpireAt 缺失：none
     *   - real_person 且 authExpireAt >= 今天：valid
     *   - real_person 且 authExpireAt < 今天：expired
     */
    private String computeAuthStatus(MediaLibrary lib) {
        if (!"real_person".equals(lib.getBizType())) return "none";
        if (lib.getAuthExpireAt() == null) return "none";
        return !lib.getAuthExpireAt().isBefore(LocalDate.now()) ? "valid" : "expired";
    }
}
