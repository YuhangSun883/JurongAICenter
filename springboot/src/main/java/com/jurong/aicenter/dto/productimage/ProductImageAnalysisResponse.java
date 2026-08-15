package com.jurong.aicenter.dto.productimage;

import java.util.List;

/**
 * 商详套图 —— 商品详解分析结果 DTO。
 * <p>
 * 对应前端 frontend/src/types/product-image.ts 的 ProductImageAnalysisTask。
 * 「分析结果」标签页：LLM 逐张输出商详图设计分析（画布尺寸/引用图/锁定主体/画面环境/画面文字/负面约束），
 * 文案语言 = 用户选择的语种。
 */
public record ProductImageAnalysisResponse(
    String taskId,
    /** running / success / failed */
    String status,
    List<AnalysisItem> items,
    long createdAt,
    String failReason
) {

    /** 单张商详图的分析条目 */
    public record AnalysisItem(
        /** 引用图标签：@图片1（对应上传顺序） */
        String refLabel,
        /** 套图类型：主视图 / 卖点图 … */
        String role,
        /** 画布比例：1:1 / 4:5 */
        String ratio,
        /** 分析要点（有序键值对，键随用户语种输出） */
        List<Section> sections
    ) {}

    /** 分析要点键值对，如 画布尺寸: xxx */
    public record Section(String key, String value) {}
}
