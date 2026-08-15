package com.jurong.aicenter.dto.productimage;

import lombok.Data;

import java.util.List;

/**
 * 商详套图 —— 提交生成请求 DTO
 * <p>
 * 前端约定见 frontend/src/types/product-image.ts 的 CreateProductImageRequest。
 */
@Data
public class CreateProductImageTaskRequest {

    /** 商品图素材 id 列表（来自素材库，仅记录用；实际生成以 images 为准） */
    private List<String> assetIds;

    /**
     * 商品图内容列表（1~5 张），base64 data URI（data:image/...;base64,...）
     * 或后端可访问的 http(s) URL。图生图的引用图来源。
     */
    private List<String> images;

    /** 输出语言：中文 / English 等 */
    private String lang;

    /** 套图张数："4 张" / "8 张" / "12 张" */
    private String count;

    /** 补充说明：卖点方向、目标人群、禁用元素、风格（选填） */
    private String brief;

    /** 模型档位 key：standard / premium */
    private String modelKey;

    /** 图片设置 key（前端透传，后端暂不解析） */
    private String settingKey;

    /** 分辨率：1K / 2K / 4K */
    private String resolution;

    /** 输出格式：PNG / JPEG（当前上游统一返回 png，仅记录） */
    private String format;

    /** 自定义提示词（选填）：分析卡片「立即生成」传入的完整设计分析文案；非空时单张生成模式，忽略 count 与默认角色循环 */
    private String prompt;

    /** 指定套图类型（选填）：与 prompt 搭配使用，如 主视图 / 卖点图 */
    private String role;
}
