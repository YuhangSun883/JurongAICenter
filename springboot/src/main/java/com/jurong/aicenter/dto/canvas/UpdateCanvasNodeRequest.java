package com.jurong.aicenter.dto.canvas;

import lombok.Data;

import java.util.List;

@Data
public class UpdateCanvasNodeRequest {
    private String title;
    private String content;
    private String assetId;
    private String resultUrl;
    private Integer positionX;
    private Integer positionY;

    /** 上游节点 ID 列表（前端传数组，后端存 JSON 字符串到 canvas_nodes.upstream_ids） */
    private List<String> upstreamIds;
    private List<String> downstreamIds;
}