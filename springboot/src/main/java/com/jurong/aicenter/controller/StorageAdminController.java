package com.jurong.aicenter.controller;

import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 2026-08-14 新增:MinIO 存储管理端点(紧急清理用)。
 *
 * <p>路径前缀 {@code /api/storage-admin/**},由 SecurityConfig 拦截:必须 ROLE_ADMIN 才能访问。</p>
 *
 * <p>用途:当 MinIO bucket 触发"minimum free drive threshold"时(磁盘已满),
 * 管理员可以列出所有对象 / 批量删除以释放空间。</p>
 *
 * <p><b>注意</b>:这些操作不可逆,会永久删除 MinIO 上的对象。生产环境慎用。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/storage-admin")
@RequiredArgsConstructor
public class StorageAdminController {

    private final StorageService storageService;

    /**
     * 列出 bucket 里所有对象 key(分页,默认 1000)。
     * GET /api/storage-admin/list?maxKeys=1000
     */
    @GetMapping("/list")
    public Map<String, Object> listObjects(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "1000") int maxKeys) {
        requireAdmin(user);
        if (maxKeys > 10000) maxKeys = 10000; // 上限保护
        List<String> keys = storageService.listAllObjectKeys(maxKeys);
        return Map.of(
            "count", keys.size(),
            "maxKeys", maxKeys,
            "keys", keys
        );
    }

    /**
     * 批量删除对象。
     * POST /api/storage-admin/delete
     * body: {"keys": ["media/1/2026-08/foo.png", ...]}
     */
    @PostMapping("/delete")
    public Map<String, Object> batchDelete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody BatchDeleteRequest req) {
        requireAdmin(user);
        if (req.keys == null || req.keys.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "keys 不能为空");
        }
        if (req.keys.size() > 1000) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "单次最多删除 1000 个对象");
        }
        int ok = storageService.deleteFiles(req.keys);
        log.warn("[storage-admin] batch delete: requested={}, ok={}, by admin={}",
            req.keys.size(), ok, user.id());
        return Map.of(
            "requested", req.keys.size(),
            "deleted", ok
        );
    }

    private void requireAdmin(AuthenticatedUser user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        // 简单 role 检查(实际由 SecurityConfig 拦截,这里是双重保险)
        if (!"ADMIN".equalsIgnoreCase(user.role())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "需要管理员权限");
        }
    }

    public static class BatchDeleteRequest {
        public List<String> keys;
    }
}