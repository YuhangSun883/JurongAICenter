package com.jurong.aicenter.dto.console;

import java.util.List;

public record ConsoleUserDetail(
    ConsoleUserItem user,
    List<ConsoleJobItem> recentJobs,
    List<ConsoleBillingItem> recentBillings,
    List<ConsoleAssetItem> recentAssets
) {
}
