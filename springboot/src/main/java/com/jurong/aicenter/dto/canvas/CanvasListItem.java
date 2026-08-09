package com.jurong.aicenter.dto.canvas;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZoneOffset;

/**
 * "我的创作"列表项（缩略图用）。
 *
 * <p>对应前端"我的创作"网格里每一格的内容：缩略图 + 名字 + 时间。
 *
 * <p>不返回节点列表/连线详情（详情在 {@link CanvasDetail}）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CanvasListItem {

    private String id;
    private String name;
    private String thumbnail;

    /** 节点总数（前端"未命名画布"占位判断用） */
    private Integer nodeCount;

    /** Unix 毫秒 */
    private Long createdAt;
    private Long updatedAt;

    public static CanvasListItem from(com.jurong.aicenter.entity.Canvas c, int nodeCount) {
        CanvasListItem r = new CanvasListItem();
        r.id = c.getId();
        r.name = c.getName();
        r.thumbnail = c.getThumbnail();
        r.nodeCount = nodeCount;
        r.createdAt = c.getCreatedAt() == null ? null
            : c.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        r.updatedAt = c.getUpdatedAt() == null ? null
            : c.getUpdatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
        return r;
    }

    /**
     * 2026-08-09 新增：缩略图 fallback 版本
     * <p>当 canvas.thumbnail 为空时,调用方传入 fallbackThumb 覆盖（取自画布第一个 image/video 节点的 resultUrl）。</p>
     * <p>调用方不传 fallbackThumb（传 null）则行为跟 {@link #from(Canvas, int)} 一致。</p>
     */
    public static CanvasListItem from(com.jurong.aicenter.entity.Canvas c, int nodeCount, String fallbackThumb) {
        CanvasListItem r = from(c, nodeCount);
        if ((r.thumbnail == null || r.thumbnail.isEmpty()) && fallbackThumb != null && !fallbackThumb.isEmpty()) {
            r.thumbnail = fallbackThumb;
        }
        return r;
    }
}