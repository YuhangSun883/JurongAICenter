package com.jurong.aicenter.service.impl;

import com.jurong.aicenter.dto.user.QuotaResponse;
import com.jurong.aicenter.entity.User;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.UserRepository;
import com.jurong.aicenter.service.QuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class QuotaServiceImpl implements QuotaService {

    private final UserRepository userRepository;

    @Override
    public QuotaResponse getCurrentUsage(Long userId) {
        User user = userRepository.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "用户不存在");
        }
        // 只读数据，直接返回
        return new QuotaResponse(
            user.getCredits(),
            user.getMonthlyQuota(),
            user.getQuotaUsed(),
            user.getPlan()
        );
    }
}