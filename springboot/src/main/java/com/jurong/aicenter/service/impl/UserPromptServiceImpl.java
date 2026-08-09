package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jurong.aicenter.dto.prompt.UserPromptResponse;
import com.jurong.aicenter.entity.UserPrompt;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.UserPromptRepository;
import com.jurong.aicenter.service.UserPromptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserPromptServiceImpl implements UserPromptService {

    private final UserPromptRepository userPromptRepository;

    @Override
    @Transactional
    public UserPromptResponse savePrompt(String email, String prompt) {
        // 查找是否已存在相同的提示词
        LambdaQueryWrapper<UserPrompt> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserPrompt::getEmail, email)
               .eq(UserPrompt::getPrompt, prompt);
        UserPrompt existing = userPromptRepository.selectOne(wrapper);

        if (existing != null) {
            // 已存在，使用次数+1
            existing.setUseCount(existing.getUseCount() + 1);
            existing.setUpdatedAt(LocalDateTime.now());
            userPromptRepository.updateById(existing);
            log.info("提示词已存在，使用次数+1: email={}, promptId={}, useCount={}",
                email, existing.getId(), existing.getUseCount());
            return toResponse(existing);
        }

        // 新增
        UserPrompt newPrompt = new UserPrompt();
        newPrompt.setEmail(email);
        newPrompt.setPrompt(prompt);
        newPrompt.setUseCount(1);
        newPrompt.setCreatedAt(LocalDateTime.now());
        newPrompt.setUpdatedAt(LocalDateTime.now());
        userPromptRepository.insert(newPrompt);
        log.info("保存新提示词: email={}, promptId={}", email, newPrompt.getId());
        return toResponse(newPrompt);
    }

    @Override
    public List<UserPromptResponse> listByEmail(String email) {
        List<UserPrompt> prompts = userPromptRepository.findByEmailOrderByUseCountDesc(email);
        return prompts.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void incrementUseCount(Long id) {
        UserPrompt prompt = userPromptRepository.selectById(id);
        if (prompt == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "提示词不存在");
        }
        prompt.setUseCount(prompt.getUseCount() + 1);
        prompt.setUpdatedAt(LocalDateTime.now());
        userPromptRepository.updateById(prompt);
    }

    @Override
    @Transactional
    public void deletePrompt(Long id, String email) {
        UserPrompt prompt = userPromptRepository.selectById(id);
        if (prompt == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "提示词不存在");
        }
        // 安全检查：确保属于当前用户
        if (!prompt.getEmail().equals(email)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权限访问");
        }
        userPromptRepository.deleteById(id);
        log.info("删除提示词: id={}, email={}", id, email);
    }

    private UserPromptResponse toResponse(UserPrompt prompt) {
        return new UserPromptResponse(
            prompt.getId(),
            prompt.getPrompt(),
            prompt.getUseCount(),
            prompt.getCreatedAt()
        );
    }
}
