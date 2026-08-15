package com.jurong.aicenter.controller;

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
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.console.ConsoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * New isolated back-office API.
 */
@RestController
@RequestMapping("/api/console")
@RequiredArgsConstructor
public class ConsoleController {

    private final ConsoleService consoleService;

    @GetMapping("/overview")
    public ConsoleOverview overview() {
        return consoleService.overview();
    }

    @GetMapping("/users")
    public ConsolePage<ConsoleUserItem> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean disabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return consoleService.users(keyword, role, disabled, page, pageSize);
    }

    @GetMapping("/users/{id}")
    public ConsoleUserDetail userDetail(@PathVariable Long id) {
        return consoleService.userDetail(id);
    }

    @PatchMapping("/users/{id}")
    public ConsoleUserItem patchUser(
            @PathVariable Long id,
            @RequestBody ConsoleUserPatchRequest request,
            @AuthenticationPrincipal AuthenticatedUser admin) {
        requireRole(admin, "ADMIN", "OPERATOR");
        return consoleService.patchUser(id, request, admin.id(), admin.email());
    }

    @PatchMapping("/users/{id}/plan")
    public ConsoleUserItem patchUserPlan(
            @PathVariable Long id,
            @RequestBody ConsoleUserPlanRequest request,
            @AuthenticationPrincipal AuthenticatedUser admin) {
        requireRole(admin, "ADMIN", "OPERATOR");
        return consoleService.patchUserPlan(id, request, admin.id(), admin.email());
    }

    @PatchMapping("/users/{id}/credits")
    public ConsoleUserItem adjustCredits(
            @PathVariable Long id,
            @Valid @RequestBody ConsoleCreditAdjustRequest request,
            @AuthenticationPrincipal AuthenticatedUser admin) {
        requireRole(admin, "ADMIN", "FINANCE");
        return consoleService.adjustCredits(id, request, admin.id(), admin.email());
    }

    @PatchMapping("/users/{id}/password")
    public ConsoleUserItem resetUserPassword(
            @PathVariable Long id,
            @Valid @RequestBody ConsoleUserPasswordRequest request,
            @AuthenticationPrincipal AuthenticatedUser admin) {
        requireRole(admin, "ADMIN", "OPERATOR");
        return consoleService.resetUserPassword(id, request, admin.id(), admin.email());
    }

    @GetMapping("/admins")
    public ConsolePage<ConsoleAdminItem> admins(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean disabled,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return consoleService.admins(keyword, role, disabled, page, pageSize);
    }

    @PostMapping("/admins")
    public ConsoleAdminItem createAdmin(
            @Valid @RequestBody ConsoleAdminCreateRequest request,
            @AuthenticationPrincipal AuthenticatedUser admin) {
        requireRole(admin, "ADMIN");
        return consoleService.createAdmin(request, admin.id(), admin.email());
    }

    @PatchMapping("/admins/{id}")
    public ConsoleAdminItem patchAdmin(
            @PathVariable Long id,
            @RequestBody ConsoleAdminPatchRequest request,
            @AuthenticationPrincipal AuthenticatedUser admin) {
        requireRole(admin, "ADMIN");
        return consoleService.patchAdmin(id, request, admin.id(), admin.email());
    }

    @PatchMapping("/admins/{id}/password")
    public ConsoleAdminItem resetAdminPassword(
            @PathVariable Long id,
            @Valid @RequestBody ConsoleAdminPasswordRequest request,
            @AuthenticationPrincipal AuthenticatedUser admin) {
        requireRole(admin, "ADMIN");
        return consoleService.resetAdminPassword(id, request, admin.id(), admin.email());
    }

    @DeleteMapping("/admins/{id}")
    public void deleteAdmin(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser admin) {
        requireRole(admin, "ADMIN");
        consoleService.deleteAdmin(id, admin.id(), admin.email());
    }

    @GetMapping("/jobs")
    public ConsolePage<ConsoleJobItem> jobs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String templateId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return consoleService.jobs(keyword, status, templateId, page, pageSize);
    }

    @PatchMapping("/jobs/{id}")
    public ConsoleJobItem patchJob(
            @PathVariable Long id,
            @RequestBody ConsoleJobPatchRequest request,
            @AuthenticationPrincipal AuthenticatedUser admin) {
        requireRole(admin, "ADMIN", "OPERATOR");
        return consoleService.patchJob(id, request, admin.id(), admin.email());
    }

    @GetMapping("/assets")
    public ConsolePage<ConsoleAssetItem> assets(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Boolean deleted,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return consoleService.assets(keyword, type, source, deleted, page, pageSize);
    }

    @DeleteMapping("/assets/{id}")
    public void deleteAsset(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser admin) {
        requireRole(admin, "ADMIN", "OPERATOR");
        consoleService.deleteAsset(id, admin.id(), admin.email());
    }

    @PatchMapping("/assets/{id}/restore")
    public ConsoleAssetItem restoreAsset(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthenticatedUser admin) {
        requireRole(admin, "ADMIN", "OPERATOR");
        return consoleService.restoreAsset(id, admin.id(), admin.email());
    }

    @GetMapping("/audits")
    public ConsolePage<ConsoleAuditItem> audits(
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return consoleService.audits(action, page, pageSize);
    }

    @GetMapping("/orders")
    public ConsolePage<ConsoleFinanceOrderItem> orders(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return consoleService.orders(keyword, status, page, pageSize);
    }

    @GetMapping("/billings")
    public ConsolePage<ConsoleBillingItem> billings(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return consoleService.billings(keyword, type, page, pageSize);
    }

    @GetMapping("/pricing")
    public List<ConsolePricingRuleItem> pricingRules() {
        return consoleService.pricingRules();
    }

    @GetMapping("/settings")
    public List<ConsoleSettingItem> settings() {
        return consoleService.settings();
    }

    private void requireRole(AuthenticatedUser admin, String... allowedRoles) {
        String role = admin == null || admin.role() == null ? "" : admin.role().trim().toUpperCase();
        for (String allowed : allowedRoles) {
            if (allowed.equals(role)) {
                return;
            }
        }
        throw new com.jurong.aicenter.exception.BusinessException(
            com.jurong.aicenter.exception.ErrorCode.ADMIN_OPERATION_DENIED,
            "当前后台角色没有该操作权限"
        );
    }
}
