package com.jurong.aicenter.dto.canvas;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZoneOffset;
import java.util.List;

/**
 * 画布详情（前端画布编辑器打开画布时拉的完整快照）。
 *
 * <p>包含：
 * <ul>
 *   <li>画布元信息（id / name / thumbnail）</li>
 *   <li>所有节点（CanvasNodeResponse，已脱敏）</li>
 *   <li>所有连线（NodeConnection）— 供前端画布 UI 渲染箭头</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CanvasDetail {

    private String id;
    private String name;
    private String thumbnail;

    private Long createdAt;
    private Long updatedAt;

    /** 画布所有节点（脱敏后） */
    private List<CanvasNodeResponse> nodes;

    /** 画布所有连线（多端口格式） */
    private List<EdgeDto> edges;

    /**
     * 边：连接两条节点（前端渲染用）
     * fromNode / toNode 都是节点 id。
     * port 是端口名。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EdgeDto {
        private String fromNode;
        private String toNode;
        private String port;
    }

    public static CanvasDetail from(com.jurong.aicenter.entity.Canvas c,
                                      List<CanvasNodeResponse> nodes,
                                      List<EdgeDto> edges) {
        CanvasDetail d = new CanvasDetail();
        d.id = c.getId();
        d.name = c.getName();
        d.thumbnail = c.getThumbnail();
        d.createdAt = c.getCreatedAt() == null ? null
            : c.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        d.updatedAt = c.getUpdatedAt() == null ? null
            : c.getUpdatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        d.nodes = nodes;
        d.edges = edges;
        return d;
    }
}