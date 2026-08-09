package com.jurong.aicenter.dto.canvas;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 创建画布节点请求。
 *
 * <p>canvasId 可空：
 * <ul>
 *   <li>为空 → 服务端自动给该用户建/用一个默认画布</li>
 *   <li>非空 → 校验归属后挂到该画布下</li>
 * </ul>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CreateCanvasNodeRequest {

    /** 可空：所属画布 ID（NULL → 默认画布） */
    private String canvasId;

    @NotBlank
    /** text / image / video / audio */
    private String type;

    private String title;
    private String content;
    private String assetId;

    private Integer positionX;
    private Integer positionY;

    /**
     * 上游连接列表（多端口格式）。
     * 每条带 port 名和 nodeId。
     */
    private List<NodeConnection> upstreamIds;

    /** 下游连接列表（多端口格式）。 */
    private List<NodeConnection> downstreamIds;
}