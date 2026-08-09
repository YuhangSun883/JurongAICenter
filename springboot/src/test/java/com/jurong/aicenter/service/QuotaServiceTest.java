package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.user.QuotaResponse;
import com.jurong.aicenter.entity.User;
import com.jurong.aicenter.repository.UserRepository;
import com.jurong.aicenter.service.impl.QuotaServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private QuotaServiceImpl quotaService;

    @Test
    void getCurrentUsage_Success() {
        // 1. 模拟查出用户
        Long userId = 1L;
        User user = new User();
        user.setId(userId);
        user.setCredits(0);
        user.setMonthlyQuota(50);
        user.setQuotaUsed(0);
        user.setPlan("FREE");

        when(userRepository.selectById(userId)).thenReturn(user);

        // 2. 执行查询
        QuotaResponse response = quotaService.getCurrentUsage(userId);

        // 3. 断言数据
        assertEquals(0, response.getCredits());
        assertEquals(50, response.getMonthlyQuota());
    }
}