package com.jurong.aicenter.service;

import com.jurong.aicenter.dto.workflow.WorkflowRequest;
import com.jurong.aicenter.entity.Workflow;

import java.util.List;

/**
 * 工作流服务 - Phase 5 C 负责完整实现
 *
 * 用户的私有 workflow + 官方模板管理
 */
public interface WorkflowService {

    Workflow save(Long userId, WorkflowRequest request);

    Workflow get(Long id, Long userId);

    Workflow update(Long id, Long userId, WorkflowRequest request);

    void delete(Long id, Long userId);

    List<Workflow> listByUser(Long userId, int page, int pageSize);

    List<Workflow> listTemplates();
}