package com.jurong.aicenter.dto.canvas;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 画布节点返回 DTO。
 *
 * **安全要点**：绝不直接返回 CanvasNode entity（会泄露 userId/settings/upstreamIds 等）。
 * 本 DTO 只暴露前端画布 UI 真正需要的字段：
 *   - id / type / title / content / assetId / resultUrl / status
 *   - createdAt / updatedAt（前端做时间显示）
 *
 * 不暴露：
 *   - userId（防越权枚举）
 *   - settings JSON（内部模型参数）
 *   - upstreamIds / downstreamIds（图中其他用户的节点 ID 防泄露）
 *   - failReason 详细堆栈（只在前端用户点击"查看原因"时单独拉详情接口）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CanvasNodeResponse {

    private String id;

    /** text / image / video / audio */
    private String type;

    private String title;
    private String content;
    private String assetId;

    /** 图片/视频节点的产物 URL（公开） */
    private String resultUrl;

    /** idle / running / success / failed */
    private String status;

    /** Unix 毫秒时间戳（前端 JS 直接用） */
    private Long createdAt;
    private Long updatedAt;

    /** 失败时的友好提示（已脱敏），无失败则为 null */
    private String failMessage;

    /**
     * 从 entity 转 DTO。
     * 注意：failReason 在后端日志里是详细堆栈，转 DTO 时只暴露安全文案。
     */
    public static CanvasNodeResponse from(com.jurong.aicenter.entity.CanvasNode n) {
        if (n == null) return null;
        CanvasNodeResponse r = new CanvasNodeResponse();
        r.id = n.getId();
        r.type = n.getType();
        r.title = n.getTitle();
        r.content = n.getContent();
        r.assetId = n.getAssetId();
        r.resultUrl = n.getResultUrl();
        r.status = n.getStatus();
        r.createdAt = n.getCreatedAt() == null ? null
            : n.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        r.updatedAt = n.getUpdatedAt() == null ? null
            : n.getUpdatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        // failReason 脱敏：只取前 100 字符，不暴露堆栈
        r.failMessage = sanitizeFailReason(n.getFailReason());
        return r;
    }

    private static String sanitizeFailReason(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // 取第一行 + 前 100 字符，避免暴露 Java 堆栈
        int newlineIdx = raw.indexOf('\n');
        String firstLine = newlineIdx >= 0 ? raw.substring(0, newlineIdx) : raw;
        if (firstLine.length() > 100) {
            firstLine = firstLine.substring(0, 100) + "...";
        }
        return firstLine;
    }
}