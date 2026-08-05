package com.jurong.aicenter.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.jurong.aicenter.dto.workflow.WorkflowRequest;
import com.jurong.aicenter.entity.Workflow;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.repository.WorkflowRepository;
import com.jurong.aicenter.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowServiceImpl implements WorkflowService {

    private final WorkflowRepository workflowRepository;

    @Override
    public Workflow save(Long userId, WorkflowRequest request) {
        // TODO(C): 校验 graphJson 是合法 JSON（用 Jackson parse）
        Workflow w = new Workflow();
        w.setUserId(userId);
        w.setName(request.getName());
        w.setDescription(request.getDescription());
        w.setGraphJson(request.getGraphJson());
        w.setThumbnailUrl(request.getThumbnailUrl());
        w.setIsPublic(Boolean.TRUE.equals(request.getIsPublic()));
        w.setIsTemplate(false);
        w.setCreatedAt(LocalDateTime.now());
        w.setUpdatedAt(LocalDateTime.now());
        workflowRepository.insert(w);
        return w;
    }

    @Override
    public Workflow get(Long id, Long userId) {
        Workflow w = workflowRepository.selectById(id);
        if (w == null) throw new BusinessException(ErrorCode.WORKFLOW_NOT_FOUND);
        // 模板公开的话允许跨用户读
        if (Boolean.TRUE.equals(w.getIsTemplate()) || Boolean.TRUE.equals(w.getIsPublic())) {
            return w;
        }
        if (!w.getUserId().equals(userId)) throw new BusinessException(ErrorCode.WORKFLOW_ACCESS_DENIED);
        return w;
    }

    @Override
    public Workflow update(Long id, Long userId, WorkflowRequest request) {
        Workflow w = get(id, userId);  // 复用 get 做权限校验
        w.setName(request.getName());
        w.setDescription(request.getDescription());
        w.setGraphJson(request.getGraphJson());
        w.setThumbnailUrl(request.getThumbnailUrl());
        if (request.getIsPublic() != null) w.setIsPublic(request.getIsPublic());
        w.setUpdatedAt(LocalDateTime.now());
        workflowRepository.updateById(w);
        return w;
    }

    @Override
    public void delete(Long id, Long userId) {
        get(id, userId);  // 校验权限
        workflowRepository.deleteById(id);
    }

    @Override
    public List<Workflow> listByUser(Long userId, int page, int pageSize) {
        int offset = Math.max(0, (page - 1) * pageSize);
        LambdaQueryWrapper<Workflow> wrapper = new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getUserId, userId)
                .orderByDesc(Workflow::getUpdatedAt)
                .last("LIMIT " + offset + ", " + pageSize);
        return workflowRepository.selectList(wrapper);
    }

    @Override
    public List<Workflow> listTemplates() {
        LambdaQueryWrapper<Workflow> wrapper = new LambdaQueryWrapper<Workflow>()
                .eq(Workflow::getIsTemplate, true)
                .or()
                .eq(Workflow::getIsPublic, true)
                .orderByDesc(Workflow::getCreatedAt);
        return workflowRepository.selectList(wrapper);
    }
}