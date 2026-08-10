package com.jurong.aicenter.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.dto.generation.GenerateRequest;
import com.jurong.aicenter.dto.generation.GenerateResponse;
import com.jurong.aicenter.dto.job.JobResponse;
import com.jurong.aicenter.entity.Job;
import com.jurong.aicenter.exception.BusinessException;
import com.jurong.aicenter.exception.ErrorCode;
import com.jurong.aicenter.security.JwtAuthenticationFilter.AuthenticatedUser;
import com.jurong.aicenter.service.GenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GenerationController {

    private final GenerationService generationService;
    private final ObjectMapper objectMapper;

    @PostMapping("/generate")
    public GenerateResponse generate(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody GenerateRequest request) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return generationService.submit(principal.id(), request);
    }

    @GetMapping("/jobs")
    public List<JobResponse> listJobs(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        // TODO(C): 完整实现
        return Collections.emptyList();
    }

    @GetMapping("/jobs/{id}")
    public JobResponse getJob(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        Job job = generationService.getJob(id, principal.id());
        List<String> resultUrls = null;
        if (job.getResultUrls() != null && !job.getResultUrls().isBlank()) {
            try {
                resultUrls = objectMapper.readValue(job.getResultUrls(), new TypeReference<>() {});
            } catch (Exception ignored) {}
        }
        return new JobResponse(
            job.getId(),
            job.getWorkflowId(),
            job.getTemplateId(),
            job.getStatus(),
            job.getCreditsCost(),
            job.getDurationMs(),
            resultUrls,
            job.getErrorMessage(),
            job.getCreatedAt(),
            job.getCompletedAt()
        );
    }

    /**
     * C8 - 取得任务产物的可访问 URL（302 重定向到 24h 签名 URL）。
     * 浏览器 / 前端拿到响应后会自动跳转到 MinIO 下载文件。
     */
    @GetMapping("/jobs/{id}/result/{filename}")
    public ResponseEntity<Void> getJobResult(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id,
            @PathVariable String filename) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        String url = generationService.getResultUrl(id, principal.id(), filename);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LOCATION, url);
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    /**
     * C9 - 删除 / 取消任务。
     *   - RUNNING / PENDING → 调 ComfyUI /interrupt + 标 CANCELLED
     *   - COMPLETED / FAILED → 标 DELETED
     *   - 终态 → 幂等
     */
    @DeleteMapping("/jobs/{id}")
    public Map<String, Object> deleteJob(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable Long id) {
        if (principal == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        String newStatus = generationService.deleteJob(id, principal.id());
        return Map.of("jobId", id, "status", newStatus);
    }
}