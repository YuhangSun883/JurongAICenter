package com.jurong.aicenter.dto.enhancer;

/**
 * 2026-08-15 新增:画质增强提交请求 DTO
 *
 * <p>前端工作台选择完视频后提交这个请求,后端构造 v2v 协议 body 调 NewAPI。</p>
 */
import lombok.Data;

@Data
public class EnhancerSubmitRequest {
    /** 源视频公网 URL(http/https/asset://) */
    private String videoUrl;

    /** 版本: "标准版" / "专业版" */
    private String version;

    /** 画质设置:
     * <ul>
     *   <li>"1080P · AIGC · 无"  — 默认,1080P 重生成,无原音</li>
     *   <li>"720P · AIGC · 无"   — 720P 重生成,无原音</li>
     *   <li>"1080P · 原画 · 有声" — 1080P 保留原音</li>
     * </ul>
     */
    private String setting;
}
