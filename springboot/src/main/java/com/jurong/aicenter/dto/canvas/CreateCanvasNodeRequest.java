package com.jurong.aicenter.dto.canvas;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class CreateCanvasNodeRequest {

    @NotBlank
    /** text / image / video / audio */
    private String type;

    private String title;
    private String content;
    private String assetId;

    private Integer positionX;
    private Integer positionY;

    /** 上游节点 ID 列表（前端传数组，后端存 JSON 字符串到 canvas_nodes.upstream_ids） */
    private List<String> upstreamIds;
    private List<String> downstreamIds;
}