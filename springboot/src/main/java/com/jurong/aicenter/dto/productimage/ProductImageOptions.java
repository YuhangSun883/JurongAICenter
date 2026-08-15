package com.jurong.aicenter.dto.productimage;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 商详套图 —— 静态选项定义（模型档位 / 分辨率 / 输出格式 / 参考示例）。
 * <p>
 * 对应前端 frontend/src/types/product-image.ts；
 * 选项内容与前端 mock（api/product-image.mock.ts）保持一致。
 */
public final class ProductImageOptions {

    private ProductImageOptions() {}

    /** 模型档位 */
    public record ModelOption(String key, String label, String badge, int creditsCost) {}

    /** 分辨率选项（short 为 JSON 字段名，对应前端 ResolutionOption.short） */
    public record ResolutionOption(String key, String label, @JsonProperty("short") String shortLabel) {}

    /** 输出格式选项 */
    public record FormatOption(String key, String label) {}

    /** 参考示例（中间轮播用） */
    public record ExampleItem(String id, String title, String subtitle,
                              String description, String imageUrl, int order) {}

    public static final List<ModelOption> MODELS = List.of(
        new ModelOption("standard", "标准版", null, 100),
        new ModelOption("premium", "高级版", "更快更稳", 200)
    );

    public static final List<ResolutionOption> RESOLUTIONS = List.of(
        new ResolutionOption("1K", "标准 1K", "1K"),
        new ResolutionOption("2K", "高清 2K", "2K"),
        new ResolutionOption("4K", "超清 4K", "4K")
    );

    public static final List<FormatOption> FORMATS = List.of(
        new FormatOption("PNG", "PNG"),
        new FormatOption("JPEG", "JPEG")
    );

    /**
     * 参考示例：按商详套图的图片类型组织（与 da-ai.cc 电商套图结构一致）。
     * 图片使用 picsum 占位（按 seed 稳定），后续可替换为真实示例图。
     */
    public static final List<ExampleItem> EXAMPLES = List.of(
        new ExampleItem("ex-1", "主视图", "首页 / 主视觉",
            "用清晰主体、品牌氛围和核心利益点，快速建立商品第一印象。",
            "https://picsum.photos/seed/product-set-1/720/720", 1),
        new ExampleItem("ex-2", "卖点图", "核心卖点 / 价值主张",
            "提炼核心卖点分点呈现，图文结合强化用户对价值的认知。",
            "https://picsum.photos/seed/product-set-2/720/720", 2),
        new ExampleItem("ex-3", "细节图", "材质 / 工艺",
            "放大产品纹理与工艺亮点，提升详情页的专业感。",
            "https://picsum.photos/seed/product-set-3/720/720", 3),
        new ExampleItem("ex-4", "成分图", "成分 / 安全",
            "用可视化展示成分与材质溯源，建立信任感。",
            "https://picsum.photos/seed/product-set-4/720/720", 4),
        new ExampleItem("ex-5", "场景图", "场景化 / 情感代入",
            "把商品融入生活场景，激发用户的使用联想与购买欲。",
            "https://picsum.photos/seed/product-set-5/720/720", 5),
        new ExampleItem("ex-6", "结构图", "结构 / 拆解",
            "以拆解或剖面视角呈现产品内部结构与工艺。",
            "https://picsum.photos/seed/product-set-6/720/720", 6),
        new ExampleItem("ex-7", "对比图", "差异 / 优势",
            "通过对比突出核心优势，强化用户对价值的认知。",
            "https://picsum.photos/seed/product-set-7/720/720", 7),
        new ExampleItem("ex-8", "包装图", "包装 / 配件",
            "呈现包装与配件细节，塑造超值感与仪式感。",
            "https://picsum.photos/seed/product-set-8/720/720", 8),
        new ExampleItem("ex-9", "收尾图", "信任 / 转化",
            "以资质背书与购买引导收尾，促成最终转化。",
            "https://picsum.photos/seed/product-set-9/720/720", 9)
    );
}
