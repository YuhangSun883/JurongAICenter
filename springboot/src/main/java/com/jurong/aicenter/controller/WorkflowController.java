package com.jurong.aicenter.controller;

import com.jurong.aicenter.dto.workflow.WorkflowRequest;
import com.jurong.aicenter.dto.workflow.WorkflowResponse;
import com.jurong.aicenter.entity.Workflow;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @PostMapping
    public WorkflowResponse save(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody WorkflowRequest request) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return toResponse(workflowService.save(principal.id(), request));
    }

    @GetMapping
    public List<WorkflowResponse> list(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return workflowService.listByUser(principal.id(), page, pageSize).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/templates")
    public List<WorkflowResponse> listTemplates(@AuthenticationPrincipal AuthenticatedUser principal) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return workflowService.listTemplates().stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{id}")
    public WorkflowResponse get(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return toResponse(workflowService.get(id, principal.id()));
    }

    @PatchMapping("/{id}")
    public WorkflowResponse update(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id,
            @Valid @RequestBody WorkflowRequest request) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return toResponse(workflowService.update(id, principal.id(), request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        workflowService.delete(id, principal.id());
        return ResponseEntity.noContent().build();
    }

    private WorkflowResponse toResponse(Workflow w) {
        return new WorkflowResponse(
            w.getId(),
            w.getName(),
            w.getDescription(),
            w.getGraphJson(),
            w.getThumbnailUrl(),
            w.getIsPublic(),
            w.getCreatedAt(),
            w.getUpdatedAt()
        );
    }
}