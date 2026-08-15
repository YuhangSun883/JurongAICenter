package com.jurong.aicenter.dto.media;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PatchAssetRequest {

    @Size(max = 255, message = "文件名不能超过 255 字符")
    private String name;

    /**
     * 2026-08-15：可选，改素材所属资产库（move 操作）。
     * 传 null/缺省 表示不改库；传具体 id 表示把素材搬到目标库。
     * 后端会校验：目标库属于当前用户 且 目标库不能是 system-ai（AI 库只装 AI 产出）。
     */
    @Positive(message = "libraryId 必须为正数")
    private Long libraryId;
}
