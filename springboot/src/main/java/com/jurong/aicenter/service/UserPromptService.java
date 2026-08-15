package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.prompt.UserPromptResponse;

import java.util.List;

/**
 * 用户提示词服务接口
 */
public interface UserPromptService {

    /**
     * 保存提示词（如果已存在则使用次数+1）
     */
    UserPromptResponse savePrompt(String email, String title, String prompt);

    /**
     * 编辑提示词
     */
    UserPromptResponse updatePrompt(Long id, String email, String title, String prompt);

    /**
     * 查询用户的所有提示词，按使用次数降序排列
     */
    List<UserPromptResponse> listByEmail(String email);

    /**
     * 使用提示词时调用，使用次数+1
     */
    void incrementUseCount(Long id);

    /**
     * 删除提示词
     */
    void deletePrompt(Long id, String email);
}
