package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.user.QuotaResponse;

public interface QuotaService {
    // B7：获取当前用户配额（只查不扣）
    QuotaResponse getCurrentUsage(Long userId);
}