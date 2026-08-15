package com.jurong.aicenter.dto.enhancer;

/**
 * 2026-08-15 新增:画质增强提交响应 DTO
 *
 * <p>前端拿到 taskId 后开始轮询 {@code /api/image-enhancer/jobs/{taskId}}。</p>
 */
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class EnhancerSubmitResponse {
    /** NewAPI 任务 ID,用于轮询状态 */
    private String taskId;

    /** 初始状态(几乎都是 "queued" 或 "pending") */
    private String status;
}
