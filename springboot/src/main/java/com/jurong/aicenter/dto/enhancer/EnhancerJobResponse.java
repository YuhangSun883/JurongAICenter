package com.jurong.aicenter.dto.enhancer;

/**
 * 2026-08-15 新增:画质增强任务状态响应 DTO
 *
 * <p>前端每次轮询拿到这个对象,根据 status 决定 UI 状态:
 * <ul>
 *   <li>"queued" / "pending" — 排队中</li>
 *   <li>"running" / "processing" — 处理中</li>
 *   <li>"completed" / "succeeded" / "success" — 完成,outputUrl 有值</li>
 *   <li>"failed" / "error" / "cancelled" — 失败,errorMessage 有值</li>
 * </ul>
 */
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EnhancerJobResponse {
    private String taskId;
    private String status;

    /** 源视频 URL(提交时携带,前端确认任务用) */
    private String videoUrl;

    /** 增强后的视频 URL(完成时有值) */
    private String outputUrl;

    /** 错误信息(失败时有值) */
    private String errorMessage;

    /** Unix 毫秒 */
    private Long createdAt;
    private Long completedAt;
}
