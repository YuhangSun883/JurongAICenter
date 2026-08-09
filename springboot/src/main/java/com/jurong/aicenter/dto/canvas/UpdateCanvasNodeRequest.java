package com.jurong.aicenter.dto.canvas;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateCanvasNodeRequest {
    private String title;
    private String content;
    private String assetId;
    private String resultUrl;
    private Integer positionX;
    private Integer positionY;

    /** 上游连接列表（多端口格式） */
    private List<NodeConnection> upstreamIds;

    /** 下游连接列表（多端口格式） */
    private List<NodeConnection> downstreamIds;
}