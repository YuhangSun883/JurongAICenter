package com.jurong.aicenter.dto.canvas;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class GenerateCanvasNodeRequest {

    @NotBlank
    /** text / image / video / audio — 必须和节点 type 一致 */
    private String type;

    @NotNull
    /** 用户原始输入（必填） */
    private String prompt;

    /** 上游节点输出（文本节点传入上游 content，图片/视频节点可携带上游 image URL） */
    private String content;

    /** 引用的素材 id 列表（可空） */
    private List<String> assetIds;

    /** 模型参数（temperature / size / duration 等），可空 */
    private Map<String, Object> settings;
}