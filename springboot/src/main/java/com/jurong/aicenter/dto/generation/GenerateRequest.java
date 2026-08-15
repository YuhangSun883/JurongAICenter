package com.jurong.aicenter.dto.generation;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class GenerateRequest {

    @NotNull
    private Long workflowId;

    /** 填入工作流的输入参数 */
    private Map<String, Object> inputs;

    /** 可选：指定模板 ID（不基于已有 workflow） */
    private String templateId;

    /** 可选：直接传 workflow JSON（覆盖 workflowId） */
    private JsonNode workflowOverride;

    /**
     * 2026-08-14 新增:引用图(base64 data URI 数组)。
     * 流程:前端 → 控制器 → GenerationService 注入到 workflow 节点的 image_ref / image 输入。
     */
    private List<String> referenceImages;
}