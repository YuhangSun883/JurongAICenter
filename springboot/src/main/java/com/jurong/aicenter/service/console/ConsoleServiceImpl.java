package com.jurong.aicenter.service.console;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.jurong.aicenter.dto.console.ConsoleAssetItem;
import com.jurong.aicenter.dto.console.ConsoleAdminCreateRequest;
import com.jurong.aicenter.dto.console.ConsoleAdminItem;
import com.jurong.aicenter.dto.console.ConsoleAdminPasswordRequest;
import com.jurong.aicenter.dto.console.ConsoleAdminPatchRequest;
import com.jurong.aicenter.dto.console.ConsoleAuditItem;
import com.jurong.aicenter.dto.console.ConsoleBillingItem;
import com.jurong.aicenter.dto.console.ConsoleCreditAdjustRequest;
import com.jurong.aicenter.dto.console.ConsoleFinanceOrderItem;
import com.jurong.aicenter.dto.console.ConsoleJobItem;
import com.jurong.aicenter.dto.console.ConsoleJobPatchRequest;
import com.jurong.aicenter.dto.console.ConsoleOverview;
import com.jurong.aicenter.dto.console.ConsolePage;
import com.jurong.aicenter.dto.console.ConsolePricingRuleItem;
import com.jurong.aicenter.dto.console.ConsoleSettingItem;
import com.jurong.aicenter.dto.console.ConsoleUserDetail;
import com.jurong.aicenter.dto.console.ConsoleUserItem;
import com.jurong.aicenter.dto.console.ConsoleUserPasswordRequest;
import com.jurong.aicenter.dto.console.ConsoleUserPatchRequest;
import com.jurong.aicenter.dto.console.ConsoleUserPlanRequest;
import com.jurong.aicenter.entity.AdminAuditLog;
import com.jurong.aicenter.entity.BillingLog;
import com.jurong.aicenter.entity.ConsoleAdmin;
import com.jurong.aicenter.entity.Job;
import com.jurong.aicenter.entity.MediaAsset;
import com.jurong.aicenter.entity.User;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.AdminAuditLogRepository;
import com.jurong.aicenter.repository.BillingLogRepository;
import com.jurong.aicenter.repository.ConsoleAdminRepository;
import com.jurong.aicenter.repository.JobRepository;
import com.jurong.aicenter.repository.MediaAssetRepository;
import com.jurong.aicenter.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ConsoleServiceImpl implements ConsoleService {

    private final UserRepository userRepository;
    private final JobRepository jobRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final BillingLogRepository billingLogRepository;
    private final AdminAuditLogRepository auditLogRepository;
    private final ConsoleAdminRepository consoleAdminRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${newapi.base-url:}")
    private String newApiBaseUrl;

    @Value("${newapi.video-base-url:}")
    private String newApiVideoBaseUrl;

    @Value("${aicoming.proxy.base-url:}")
    private String aicomingBaseUrl;

    @Value("${minio.endpoint:}")
    private String minioEndpoint;

    @Value("${comfyui.base-url:}")
    private String comfyuiBaseUrl;

    @Override
    public ConsoleOverview overview() {
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        long totalUsers = userRepository.selectCount(new LambdaQueryWrapper<>());
        long disabledUsers = userRepository.selectCount(new LambdaQueryWrapper<User>().eq(User::getDisabled, 1));
        long adminUsers = userRepository.selectCount(new LambdaQueryWrapper<User>().eq(User::getRole, "ADMIN"));
        long totalJobs = jobRepository.selectCount(new LambdaQueryWrapper<>());
        long todayJobs = jobRepository.selectCount(new LambdaQueryWrapper<Job>().ge(Job::getCreatedAt, todayStart));
        long runningJobs = jobRepository.selectCount(new LambdaQueryWrapper<Job>().eq(Job::getStatus, "RUNNING"));
        long failedJobs = jobRepository.selectCount(new LambdaQueryWrapper<Job>().eq(Job::getStatus, "FAILED"));
        long totalAssets = mediaAssetRepository.selectCount(new LambdaQueryWrapper<>());
        long todayAssets = mediaAssetRepository.selectCount(new LambdaQueryWrapper<MediaAsset>().ge(MediaAsset::getCreatedAt, todayStart));
        long totalCredits = sumUserCredits();
        List<ConsoleJobItem> recentJobs = jobs(null, null, null, 1, 6).items();

        return new ConsoleOverview(
            totalUsers,
            Math.max(0, totalUsers - disabledUsers),
            disabledUsers,
            adminUsers,
            totalJobs,
            todayJobs,
            runningJobs,
            failedJobs,
            totalAssets,
            todayAssets,
            totalCredits,
            recentJobs
        );
    }

    @Override
    public ConsolePage<ConsoleUserItem> users(String keyword, String role, Boolean disabled, int page, int pageSize) {
        int currentPage = page(page);
        int currentPageSize = pageSize(pageSize);
        long total = userRepository.selectCount(userQuery(keyword, role, disabled));
        List<User> records = userRepository.selectList(userQuery(keyword, role, disabled).last(limitClause(currentPage, currentPageSize)));
        List<ConsoleUserItem> items = records.stream().map(this::toUserItem).toList();
        return new ConsolePage<>(items, total, currentPage, currentPageSize);
    }

    @Override
    public ConsoleUserDetail userDetail(Long id) {
        User user = mustUser(id);

        List<Job> jobRecords = jobRepository.selectList(
            new LambdaQueryWrapper<Job>().eq(Job::getUserId, id).orderByDesc(Job::getCreatedAt).last("LIMIT 5")
        );
        Map<Long, String> emails = Map.of(id, user.getEmail());
        List<ConsoleJobItem> recentJobs = jobRecords.stream().map(job -> toJobItem(job, emails)).toList();

        List<BillingLog> billingRecords = billingLogRepository.selectList(
            new LambdaQueryWrapper<BillingLog>().eq(BillingLog::getUserId, id).orderByDesc(BillingLog::getCreatedAt).last("LIMIT 8")
        );
        List<ConsoleBillingItem> recentBillings = billingRecords.stream()
            .map(log -> toBillingItem(log, emails))
            .toList();

        List<MediaAsset> assetRecords = mediaAssetRepository.selectList(
            new LambdaQueryWrapper<MediaAsset>().eq(MediaAsset::getUserId, id).orderByDesc(MediaAsset::getCreatedAt).last("LIMIT 5")
        );
        List<ConsoleAssetItem> recentAssets = assetRecords.stream().map(asset -> toAssetItem(asset, emails)).toList();

        return new ConsoleUserDetail(toUserItem(user), recentJobs, recentBillings, recentAssets);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsoleUserItem patchUser(Long id, ConsoleUserPatchRequest request, Long adminId, String adminEmail) {
        User user = mustUser(id);
        String oldRole = user.getRole();
        Integer oldDisabled = user.getDisabled();
        if (request.getRole() != null && !request.getRole().isBlank()) {
            String nextRole = request.getRole().trim().toUpperCase();
            if (!"USER".equals(nextRole) && !"ADMIN".equals(nextRole)) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "role must be USER or ADMIN");
            }
            user.setRole(nextRole);
        }
        if (request.getDisabled() != null) {
            user.setDisabled(request.getDisabled() ? 1 : 0);
        }
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.updateById(user);
        audit(adminId, adminEmail, "CONSOLE_USER_UPDATE", "USER", id,
            "role:" + oldRole + "->" + user.getRole() + ", disabled:" + oldDisabled + "->" + user.getDisabled());
        return toUserItem(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsoleUserItem patchUserPlan(Long id, ConsoleUserPlanRequest request, Long adminId, String adminEmail) {
        User user = mustUser(id);
        String oldName = user.getDisplayName();
        String oldPlan = user.getPlan();
        Integer oldQuota = user.getMonthlyQuota();

        if (request.getDisplayName() != null) {
            user.setDisplayName(request.getDisplayName().trim());
        }
        if (request.getPlan() != null && !request.getPlan().isBlank()) {
            user.setPlan(normalizePlan(request.getPlan()));
        }
        if (request.getMonthlyQuota() != null) {
            if (request.getMonthlyQuota() < 0 || request.getMonthlyQuota() > 9999999) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "monthlyQuota invalid");
            }
            user.setMonthlyQuota(request.getMonthlyQuota());
        }
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.updateById(user);
        audit(adminId, adminEmail, "CONSOLE_USER_PLAN_UPDATE", "USER", id,
            "name:" + oldName + "->" + user.getDisplayName()
                + ", plan:" + oldPlan + "->" + user.getPlan()
                + ", monthlyQuota:" + oldQuota + "->" + user.getMonthlyQuota());
        return toUserItem(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsoleUserItem adjustCredits(Long id, ConsoleCreditAdjustRequest request, Long adminId, String adminEmail) {
        User user = mustUser(id);
        int oldCredits = user.getCredits() == null ? 0 : user.getCredits();
        int delta = request.getDelta();
        int nextCredits = oldCredits + delta;
        if (nextCredits < 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "credits cannot be negative");
        }
        user.setCredits(nextCredits);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.updateById(user);

        BillingLog log = new BillingLog();
        log.setUserId(id);
        log.setType(delta >= 0 ? "GRANT" : "CONSUME");
        log.setCreditsDelta(delta);
        log.setBalanceAfter(nextCredits);
        log.setDescription(blankToDefault(request.getReason(), "console manual adjustment"));
        log.setCreatedAt(LocalDateTime.now());
        billingLogRepository.insert(log);

        audit(adminId, adminEmail, "CONSOLE_CREDITS_ADJUST", "USER", id,
            "delta:" + delta + ", balance:" + oldCredits + "->" + nextCredits);
        return toUserItem(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsoleUserItem resetUserPassword(Long id, ConsoleUserPasswordRequest request, Long adminId, String adminEmail) {
        User user = mustUser(id);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.updateById(user);
        audit(adminId, adminEmail, "CONSOLE_USER_PASSWORD_RESET", "USER", id, user.getEmail());
        return toUserItem(user);
    }

    @Override
    public ConsolePage<ConsoleAdminItem> admins(String keyword, String role, Boolean disabled, int page, int pageSize) {
        int currentPage = page(page);
        int currentPageSize = pageSize(pageSize);
        long total = consoleAdminRepository.selectCount(adminQuery(keyword, role, disabled));
        List<ConsoleAdmin> records = consoleAdminRepository.selectList(adminQuery(keyword, role, disabled).last(limitClause(currentPage, currentPageSize)));
        List<ConsoleAdminItem> items = records.stream().map(this::toAdminItem).toList();
        return new ConsolePage<>(items, total, currentPage, currentPageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsoleAdminItem createAdmin(ConsoleAdminCreateRequest request, Long adminId, String adminEmail) {
        String email = request.getEmail() == null ? "" : request.getEmail().trim().toLowerCase();
        if (email.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "email is required");
        }
        ConsoleAdmin exists = consoleAdminRepository.selectOne(
            new LambdaQueryWrapper<ConsoleAdmin>().eq(ConsoleAdmin::getEmail, email)
        );
        if (exists != null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "console admin email already exists");
        }

        ConsoleAdmin admin = new ConsoleAdmin();
        admin.setEmail(email);
        admin.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        admin.setDisplayName(blankToDefault(request.getDisplayName(), email.substring(0, email.indexOf('@'))));
        admin.setRole(normalizeConsoleRole(request.getRole()));
        admin.setDisabled(0);
        admin.setCreatedAt(LocalDateTime.now());
        admin.setUpdatedAt(LocalDateTime.now());
        consoleAdminRepository.insert(admin);
        audit(adminId, adminEmail, "CONSOLE_ADMIN_CREATE", "CONSOLE_ADMIN", admin.getId(), admin.getEmail());
        return toAdminItem(admin);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsoleAdminItem patchAdmin(Long id, ConsoleAdminPatchRequest request, Long adminId, String adminEmail) {
        ConsoleAdmin admin = mustAdmin(id);
        if (adminId != null && adminId.equals(id) && Boolean.TRUE.equals(request.getDisabled())) {
            throw new BusinessException(ErrorCode.ADMIN_OPERATION_DENIED, "cannot disable current console account");
        }
        String oldRole = admin.getRole();
        Integer oldDisabled = admin.getDisabled();
        if (request.getDisplayName() != null) {
            admin.setDisplayName(request.getDisplayName().trim());
        }
        if (request.getRole() != null && !request.getRole().isBlank()) {
            admin.setRole(normalizeConsoleRole(request.getRole()));
        }
        if (request.getDisabled() != null) {
            admin.setDisabled(request.getDisabled() ? 1 : 0);
        }
        admin.setUpdatedAt(LocalDateTime.now());
        consoleAdminRepository.updateById(admin);
        audit(adminId, adminEmail, "CONSOLE_ADMIN_UPDATE", "CONSOLE_ADMIN", id,
            "role:" + oldRole + "->" + admin.getRole() + ", disabled:" + oldDisabled + "->" + admin.getDisabled());
        return toAdminItem(admin);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsoleAdminItem resetAdminPassword(Long id, ConsoleAdminPasswordRequest request, Long adminId, String adminEmail) {
        ConsoleAdmin admin = mustAdmin(id);
        admin.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        admin.setUpdatedAt(LocalDateTime.now());
        consoleAdminRepository.updateById(admin);
        audit(adminId, adminEmail, "CONSOLE_ADMIN_PASSWORD_RESET", "CONSOLE_ADMIN", id, admin.getEmail());
        return toAdminItem(admin);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAdmin(Long id, Long adminId, String adminEmail) {
        ConsoleAdmin admin = mustAdmin(id);
        if (adminId != null && adminId.equals(id)) {
            throw new BusinessException(ErrorCode.ADMIN_OPERATION_DENIED, "cannot delete current console account");
        }
        consoleAdminRepository.deleteById(id);
        audit(adminId, adminEmail, "CONSOLE_ADMIN_DELETE", "CONSOLE_ADMIN", id, admin.getEmail());
    }

    @Override
    public ConsolePage<ConsoleJobItem> jobs(String keyword, String status, String templateId, int page, int pageSize) {
        int currentPage = page(page);
        int currentPageSize = pageSize(pageSize);
        long total = jobRepository.selectCount(jobQuery(keyword, status, templateId));
        List<Job> records = jobRepository.selectList(jobQuery(keyword, status, templateId).last(limitClause(currentPage, currentPageSize)));
        Map<Long, String> emails = userEmails(records.stream().map(Job::getUserId).toList());
        List<ConsoleJobItem> items = records.stream().map(job -> toJobItem(job, emails)).toList();
        return new ConsolePage<>(items, total, currentPage, currentPageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsoleJobItem patchJob(Long id, ConsoleJobPatchRequest request, Long adminId, String adminEmail) {
        Job job = jobRepository.selectById(id);
        if (job == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "job not found");
        }
        String nextStatus = request.getStatus() == null ? "" : request.getStatus().trim().toUpperCase();
        if (!List.of("PENDING", "RUNNING", "COMPLETED", "FAILED", "CANCELLED").contains(nextStatus)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "invalid job status");
        }
        String oldStatus = job.getStatus();
        job.setStatus(nextStatus);
        if ("FAILED".equals(nextStatus) || "CANCELLED".equals(nextStatus)) {
            job.setErrorMessage(blankToDefault(request.getReason(), "console marked " + nextStatus));
            job.setCompletedAt(LocalDateTime.now());
        }
        jobRepository.updateById(job);
        audit(adminId, adminEmail, "CONSOLE_JOB_STATUS", "JOB", id, oldStatus + "->" + nextStatus);
        return toJobItem(job, userEmails(List.of(job.getUserId())));
    }

    @Override
    public ConsolePage<ConsoleAssetItem> assets(String keyword, String type, String source, Boolean deleted, int page, int pageSize) {
        int currentPage = page(page);
        int currentPageSize = pageSize(pageSize);
        int deletedFlag = Boolean.TRUE.equals(deleted) ? 1 : 0;
        int offset = (currentPage - 1) * currentPageSize;
        List<MediaAsset> records = mediaAssetRepository.selectConsoleAssets(
            trimToNull(keyword),
            trimToNull(type),
            trimToNull(source),
            deletedFlag,
            offset,
            currentPageSize
        );
        long total = mediaAssetRepository.countConsoleAssets(trimToNull(keyword), trimToNull(type), trimToNull(source), deletedFlag);
        Map<Long, String> emails = userEmails(records.stream().map(MediaAsset::getUserId).toList());
        List<ConsoleAssetItem> items = records.stream().map(asset -> toAssetItem(asset, emails)).toList();
        return new ConsolePage<>(items, total, currentPage, currentPageSize);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAsset(Long id, Long adminId, String adminEmail) {
        MediaAsset asset = mediaAssetRepository.selectById(id);
        if (asset == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "asset not found");
        }
        mediaAssetRepository.deleteById(id);
        audit(adminId, adminEmail, "CONSOLE_ASSET_DELETE", "ASSET", id, asset.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsoleAssetItem restoreAsset(Long id, Long adminId, String adminEmail) {
        int updated = mediaAssetRepository.restoreConsoleAsset(id);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "deleted asset not found");
        }
        MediaAsset asset = mediaAssetRepository.selectById(id);
        if (asset == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "asset not found");
        }
        audit(adminId, adminEmail, "CONSOLE_ASSET_RESTORE", "ASSET", id, asset == null ? "" : asset.getName());
        Map<Long, String> emails = userEmails(List.of(asset.getUserId()));
        return toAssetItem(asset, emails);
    }

    @Override
    public ConsolePage<ConsoleAuditItem> audits(String action, int page, int pageSize) {
        int currentPage = page(page);
        int currentPageSize = pageSize(pageSize);
        long total = auditLogRepository.selectCount(auditQuery(action));
        List<AdminAuditLog> records = auditLogRepository.selectList(auditQuery(action).last(limitClause(currentPage, currentPageSize)));
        List<ConsoleAuditItem> items = records.stream().map(this::toAuditItem).toList();
        return new ConsolePage<>(items, total, currentPage, currentPageSize);
    }

    @Override
    public ConsolePage<ConsoleFinanceOrderItem> orders(String keyword, String status, int page, int pageSize) {
        int currentPage = page(page);
        int currentPageSize = pageSize(pageSize);
        long total = billingLogRepository.selectCount(orderLogQuery(keyword));
        List<BillingLog> records = billingLogRepository.selectList(orderLogQuery(keyword).last(limitClause(currentPage, currentPageSize)));
        Map<Long, String> emails = userEmails(records.stream().map(BillingLog::getUserId).toList());
        List<ConsoleFinanceOrderItem> items = records.stream()
            .filter(log -> status == null || status.isBlank() || "PAID".equalsIgnoreCase(status))
            .map(log -> new ConsoleFinanceOrderItem(
                log.getPaymentId() == null || log.getPaymentId().isBlank() ? "manual-" + log.getId() : log.getPaymentId(),
                log.getUserId(),
                emails.get(log.getUserId()),
                orderSource(log),
                "PAID",
                null,
                log.getCreditsDelta(),
                log.getPaymentId(),
                log.getCreatedAt()
            ))
            .toList();
        return new ConsolePage<>(items, total, currentPage, currentPageSize);
    }

    @Override
    public ConsolePage<ConsoleBillingItem> billings(String keyword, String type, int page, int pageSize) {
        int currentPage = page(page);
        int currentPageSize = pageSize(pageSize);
        long total = billingLogRepository.selectCount(billingQuery(keyword, type));
        List<BillingLog> records = billingLogRepository.selectList(billingQuery(keyword, type).last(limitClause(currentPage, currentPageSize)));
        Map<Long, String> emails = userEmails(records.stream().map(BillingLog::getUserId).toList());
        List<ConsoleBillingItem> items = records.stream()
            .map(log -> toBillingItem(log, emails))
            .toList();
        return new ConsolePage<>(items, total, currentPage, currentPageSize);
    }

    @Override
    public List<ConsolePricingRuleItem> pricingRules() {
        return List.of(
            new ConsolePricingRuleItem("agent-chat", "AI 助手对话", 1, "每发送 1 条消息扣 1 积分", "部分接入", "当前仅记录会话消耗，未从用户余额扣减"),
            new ConsolePricingRuleItem("image-create", "图片生成", 10, "基础 10 积分，可按模型和清晰度加倍率", "待接入", "前端已有预估，后端生成任务当前 creditsCost 多数为 0"),
            new ConsolePricingRuleItem("video-create", "视频生成", 30, "基础 30 积分，可按时长、分辨率、模型加倍率", "待接入", "图生视频/文生视频任务已记录，但未正式扣用户余额"),
            new ConsolePricingRuleItem("asset-upload", "素材上传", 0, "上传不扣积分，只占用存储额度", "已接入", "后续可增加企业存储额度策略"),
            new ConsolePricingRuleItem("admin-adjust", "后台调整", 0, "管理员手动加减积分，必须写入审计和流水", "已接入", "已写 billing_logs 与 admin_audit_logs")
        );
    }

    @Override
    public List<ConsoleSettingItem> settings() {
        return List.of(
            new ConsoleSettingItem("AI", "newapi.base-url", newApiBaseUrl, "NewAPI chat and image endpoint"),
            new ConsoleSettingItem("AI", "newapi.video-base-url", newApiVideoBaseUrl, "Video generation endpoint"),
            new ConsoleSettingItem("AI", "aicoming.proxy.base-url", aicomingBaseUrl, "Asset proxy endpoint"),
            new ConsoleSettingItem("Storage", "minio.endpoint", minioEndpoint, "Object storage endpoint"),
            new ConsoleSettingItem("Workflow", "comfyui.base-url", comfyuiBaseUrl, "ComfyUI workflow endpoint"),
            new ConsoleSettingItem("Security", "tokens", "******", "Secret values are intentionally hidden")
        );
    }

    private LambdaQueryWrapper<ConsoleAdmin> adminQuery(String keyword, String role, Boolean disabled) {
        LambdaQueryWrapper<ConsoleAdmin> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            wrapper.and(w -> w.like(ConsoleAdmin::getEmail, k).or().like(ConsoleAdmin::getDisplayName, k));
        }
        if (role != null && !role.isBlank()) {
            wrapper.eq(ConsoleAdmin::getRole, normalizeConsoleRole(role));
        }
        if (disabled != null) {
            wrapper.eq(ConsoleAdmin::getDisabled, disabled ? 1 : 0);
        }
        wrapper.orderByDesc(ConsoleAdmin::getCreatedAt);
        return wrapper;
    }

    private LambdaQueryWrapper<User> userQuery(String keyword, String role, Boolean disabled) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = cleanKeyword(keyword);
            Long id = parseLong(k);
            wrapper.and(w -> {
                w.like(User::getEmail, k).or().like(User::getDisplayName, k);
                if (id != null) {
                    w.or().eq(User::getId, id);
                }
            });
        }
        if (role != null && !role.isBlank()) {
            wrapper.eq(User::getRole, role.trim().toUpperCase());
        }
        if (disabled != null) {
            wrapper.eq(User::getDisabled, disabled ? 1 : 0);
        }
        wrapper.orderByDesc(User::getCreatedAt);
        return wrapper;
    }

    private LambdaQueryWrapper<Job> jobQuery(String keyword, String status, String templateId) {
        LambdaQueryWrapper<Job> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = cleanKeyword(keyword);
            Long id = parseLong(k);
            wrapper.and(w -> {
                w.like(Job::getTemplateId, k).or().like(Job::getComfyuiPromptId, k);
                if (id != null) {
                    w.or().eq(Job::getId, id);
                }
            });
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(Job::getStatus, status.trim().toUpperCase());
        }
        if (templateId != null && !templateId.isBlank()) {
            wrapper.eq(Job::getTemplateId, templateId.trim());
        }
        wrapper.orderByDesc(Job::getCreatedAt);
        return wrapper;
    }

    private LambdaQueryWrapper<MediaAsset> assetQuery(String keyword, String type, String source) {
        LambdaQueryWrapper<MediaAsset> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            wrapper.and(w -> w.like(MediaAsset::getName, k).or().like(MediaAsset::getSourceTool, k));
        }
        if (type != null && !type.isBlank()) {
            wrapper.eq(MediaAsset::getType, type.trim());
        }
        if (source != null && !source.isBlank()) {
            wrapper.eq(MediaAsset::getSource, source.trim());
        }
        wrapper.orderByDesc(MediaAsset::getCreatedAt);
        return wrapper;
    }

    private LambdaQueryWrapper<AdminAuditLog> auditQuery(String action) {
        LambdaQueryWrapper<AdminAuditLog> wrapper = new LambdaQueryWrapper<>();
        if (action != null && !action.isBlank()) {
            wrapper.eq(AdminAuditLog::getAction, action.trim());
        }
        wrapper.orderByDesc(AdminAuditLog::getCreatedAt);
        return wrapper;
    }

    private QueryWrapper<BillingLog> orderLogQuery(String keyword) {
        QueryWrapper<BillingLog> wrapper = new QueryWrapper<>();
        wrapper.in("type", List.of("RECHARGE", "GRANT"));
        wrapper.gt("credits_delta", 0);
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            wrapper.and(w -> w.like("payment_id", k).or().like("description", k));
        }
        wrapper.orderByDesc("created_at");
        return wrapper;
    }

    private QueryWrapper<BillingLog> billingQuery(String keyword, String type) {
        QueryWrapper<BillingLog> wrapper = new QueryWrapper<>();
        if (type != null && !type.isBlank()) {
            wrapper.eq("type", type.trim().toUpperCase());
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            wrapper.and(w -> w.like("payment_id", k).or().like("description", k));
        }
        wrapper.orderByDesc("created_at");
        return wrapper;
    }

    private ConsoleAdmin mustAdmin(Long id) {
        ConsoleAdmin admin = consoleAdminRepository.selectById(id);
        if (admin == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "console admin not found");
        }
        return admin;
    }

    private User mustUser(Long id) {
        User user = userRepository.selectById(id);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "user not found");
        }
        return user;
    }

    private ConsoleAdminItem toAdminItem(ConsoleAdmin admin) {
        return new ConsoleAdminItem(
            admin.getId(),
            admin.getEmail(),
            admin.getDisplayName(),
            admin.getRole(),
            admin.getDisabled(),
            admin.getLastLoginAt(),
            admin.getCreatedAt(),
            admin.getUpdatedAt()
        );
    }

    private ConsoleUserItem toUserItem(User user) {
        return new ConsoleUserItem(
            user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
            user.getDisabled(), user.getCredits(), user.getMonthlyQuota(), user.getQuotaUsed(),
            user.getPlan(), user.getCreatedAt(), user.getUpdatedAt()
        );
    }

    private ConsoleJobItem toJobItem(Job job, Map<Long, String> emails) {
        return new ConsoleJobItem(
            job.getId(), job.getUserId(), emails.get(job.getUserId()), job.getTemplateId(),
            job.getComfyuiPromptId(), job.getStatus(), job.getCreditsCost(), job.getDurationMs(),
            job.getErrorMessage(), job.getCreatedAt(), job.getCompletedAt()
        );
    }

    private ConsoleAssetItem toAssetItem(MediaAsset asset, Map<Long, String> emails) {
        return new ConsoleAssetItem(
            asset.getId(), asset.getUserId(), emails.get(asset.getUserId()), asset.getType(),
            asset.getSource(), asset.getName(), asset.getMimeType(), asset.getSizeBytes(),
            asset.getSourceTool(), asset.getSourceTaskId(), asset.getCreatedAt(), asset.getDeleted()
        );
    }

    private ConsoleBillingItem toBillingItem(BillingLog log, Map<Long, String> emails) {
        return new ConsoleBillingItem(
            log.getId(),
            log.getUserId(),
            emails.get(log.getUserId()),
            log.getJobId(),
            log.getType(),
            log.getCreditsDelta(),
            log.getBalanceAfter(),
            log.getDescription(),
            log.getPaymentId(),
            log.getCreatedAt()
        );
    }

    private ConsoleAuditItem toAuditItem(AdminAuditLog log) {
        return new ConsoleAuditItem(
            log.getId(), log.getAdminId(), log.getAdminEmail(), log.getAction(),
            log.getTargetType(), log.getTargetId(), log.getDetail(), log.getCreatedAt()
        );
    }

    private String orderSource(BillingLog log) {
        String type = log.getType() == null ? "" : log.getType();
        String paymentId = log.getPaymentId() == null ? "" : log.getPaymentId();
        if (paymentId.startsWith("order_")) return "会员套餐";
        if (paymentId.startsWith("cord_")) return "积分充值";
        if ("GRANT".equals(type)) return "后台赠送";
        return "充值入账";
    }

    private void audit(Long adminId, String adminEmail, String action, String targetType, Long targetId, String detail) {
        AdminAuditLog entry = new AdminAuditLog();
        entry.setAdminId(adminId);
        entry.setAdminEmail(adminEmail == null ? "" : adminEmail);
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setDetail(toJsonDetail(detail));
        entry.setCreatedAt(LocalDateTime.now());
        auditLogRepository.insert(entry);
    }

    private String toJsonDetail(String detail) {
        String message = detail == null ? "" : detail;
        return "{\"message\":\"" + message
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            + "\"}";
    }

    private Map<Long, String> userEmails(List<Long> userIds) {
        Map<Long, String> result = new HashMap<>();
        List<Long> ids = userIds.stream().filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) return result;
        userRepository.selectBatchIds(ids).forEach(user -> result.put(user.getId(), user.getEmail()));
        return result;
    }

    private long sumUserCredits() {
        QueryWrapper<User> query = new QueryWrapper<>();
        query.select("COALESCE(SUM(credits), 0) AS total");
        List<Map<String, Object>> rows = userRepository.selectMaps(query);
        if (rows.isEmpty() || rows.get(0).get("total") == null) return 0;
        return Long.parseLong(rows.get(0).get("total").toString());
    }

    private int page(int value) {
        return Math.max(1, value);
    }

    private int pageSize(int value) {
        return Math.max(1, Math.min(100, value));
    }

    private String limitClause(int page, int pageSize) {
        int currentPage = page(page);
        int currentPageSize = pageSize(pageSize);
        int offset = (currentPage - 1) * currentPageSize;
        return "LIMIT " + currentPageSize + " OFFSET " + offset;
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : cleanKeyword(value);
    }

    private String cleanKeyword(String value) {
        String keyword = value == null ? "" : value.trim();
        while (keyword.startsWith("#")) {
            keyword = keyword.substring(1).trim();
        }
        return keyword;
    }

    private Long parseLong(String value) {
        try {
            return value == null || value.isBlank() ? null : Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalizeConsoleRole(String value) {
        String role = value == null || value.isBlank() ? "VIEWER" : value.trim().toUpperCase();
        if (!List.of("ADMIN", "FINANCE", "OPERATOR", "VIEWER").contains(role)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "role must be ADMIN, FINANCE, OPERATOR or VIEWER");
        }
        return role;
    }

    private String normalizePlan(String value) {
        String plan = value == null || value.isBlank() ? "FREE" : value.trim().toUpperCase();
        if (!List.of("FREE", "BASIC", "STANDARD", "PREMIUM", "ENTERPRISE").contains(plan)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "plan invalid");
        }
        return plan;
    }
}
