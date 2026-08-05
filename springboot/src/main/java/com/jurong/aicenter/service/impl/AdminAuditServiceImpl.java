package com.jurong.aicenter.service.impl;

import com.jurong.aicenter.entity.AdminAuditLog;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.AdminAuditLogRepository;
import com.jurong.aicenter.service.AdminAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * AdminAuditLog 写入实现。失败不抛 — 仅 {@code log.warn}，
 * 避免审计写失败把业务操作"连坐"回滚（fail-open 策略）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminAuditServiceImpl implements AdminAuditService {

    private final AdminAuditLogRepository adminAuditLogRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(Long adminId, String adminEmail, String action,
                    String targetType, Long targetId, String detailJson) {
        if (adminId == null || action == null || targetType == null) {
            log.warn("admin audit log skipped: invalid args adminId={} action={} targetType={}",
                    adminId, action, targetType);
            return;
        }
        try {
            AdminAuditLog entry = new AdminAuditLog();
            entry.setAdminId(adminId);
            entry.setAdminEmail(adminEmail == null ? "" : adminEmail);
            entry.setAction(action);
            entry.setTargetType(targetType);
            entry.setTargetId(targetId);
            entry.setDetail(detailJson);
            entry.setCreatedAt(LocalDateTime.now());
            adminAuditLogRepository.insert(entry);
        } catch (Exception ex) {
            // fail-open：审计写失败仅记日志，不阻断业务
            log.warn("admin audit log write failed: action={} target={}:{} detail={}",
                    action, targetType, targetId, detailJson, ex);
        }
    }
}
