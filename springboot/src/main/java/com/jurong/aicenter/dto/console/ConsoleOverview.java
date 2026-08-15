package com.jurong.aicenter.dto.console;

import java.util.List;

/**
 * Dashboard numbers for the isolated /api/console module.
 */
public record ConsoleOverview(
    long totalUsers,
    long activeUsers,
    long disabledUsers,
    long adminUsers,
    long totalJobs,
    long todayJobs,
    long runningJobs,
    long failedJobs,
    long totalAssets,
    long todayAssets,
    long totalCredits,
    List<ConsoleJobItem> recentJobs
) {
}
