package com.jurong.aicenter.service.console;

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

import java.util.List;

public interface ConsoleService {
    ConsoleOverview overview();

    ConsolePage<ConsoleUserItem> users(String keyword, String role, Boolean disabled, int page, int pageSize);

    ConsoleUserDetail userDetail(Long id);

    ConsoleUserItem patchUser(Long id, ConsoleUserPatchRequest request, Long adminId, String adminEmail);

    ConsoleUserItem patchUserPlan(Long id, ConsoleUserPlanRequest request, Long adminId, String adminEmail);

    ConsoleUserItem adjustCredits(Long id, ConsoleCreditAdjustRequest request, Long adminId, String adminEmail);

    ConsoleUserItem resetUserPassword(Long id, ConsoleUserPasswordRequest request, Long adminId, String adminEmail);

    ConsolePage<ConsoleAdminItem> admins(String keyword, String role, Boolean disabled, int page, int pageSize);

    ConsoleAdminItem createAdmin(ConsoleAdminCreateRequest request, Long adminId, String adminEmail);

    ConsoleAdminItem patchAdmin(Long id, ConsoleAdminPatchRequest request, Long adminId, String adminEmail);

    ConsoleAdminItem resetAdminPassword(Long id, ConsoleAdminPasswordRequest request, Long adminId, String adminEmail);

    void deleteAdmin(Long id, Long adminId, String adminEmail);

    ConsolePage<ConsoleJobItem> jobs(String keyword, String status, String templateId, int page, int pageSize);

    ConsoleJobItem patchJob(Long id, ConsoleJobPatchRequest request, Long adminId, String adminEmail);

    ConsolePage<ConsoleAssetItem> assets(String keyword, String type, String source, Boolean deleted, int page, int pageSize);

    void deleteAsset(Long id, Long adminId, String adminEmail);

    ConsoleAssetItem restoreAsset(Long id, Long adminId, String adminEmail);

    ConsolePage<ConsoleAuditItem> audits(String action, int page, int pageSize);

    ConsolePage<ConsoleFinanceOrderItem> orders(String keyword, String status, int page, int pageSize);

    ConsolePage<ConsoleBillingItem> billings(String keyword, String type, int page, int pageSize);

    List<ConsolePricingRuleItem> pricingRules();

    List<ConsoleSettingItem> settings();
}
