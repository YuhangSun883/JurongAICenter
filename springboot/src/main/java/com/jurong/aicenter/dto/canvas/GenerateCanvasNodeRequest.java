package com.jurong.aicenter.dto.canvas;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 画布节点 generate 请求。
 *
 * <p>{@code inputs} 是多端口格式（取代旧的 {@code assetIds} List&lt;String&gt;）。
 * 每条带 port 名（"video" / "text" / "frames" / "clothing" 等）和 nodeId。
 *
 * <p>执行时按 port 路由：例如换装节点的 inputs 可能同时含 frames 和 clothing 两个端口。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GenerateCanvasNodeRequest {

    @NotBlank
    /** text / image / video / audio — 必须和节点 type 一致 */
    private String type;

    @NotNull
    /** 用户原始输入（必填） */
    private String prompt;

    /** 上游输入（文本节点传入上游 content，图片/视频节点可携带上游 image URL） */
    private String content;

    /**
     * 上游输入连接（多端口格式）。
     * 每条带 port 名（如 "video" / "text" / "frames" / "clothing"）和 nodeId。
     * 取代旧版的 {@code List<String> assetIds}。
     */
    private List<NodeConnection> inputs;

    /** 模型参数（temperature / size / duration 等），可空 */
    private Map<String, Object> settings;
}