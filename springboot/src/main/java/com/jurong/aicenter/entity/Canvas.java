package com.jurong.aicenter.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 画布表（容器）。
 *
 * <p>一张画布 = 一个"项目"，一组画布节点的归属。
 * 对应前端"我的创作"列表里的一个缩略图项。</>
 *
 * <p> 与 CanvasNode 是一对多。canvas_nodes.canvas_id 即本表 id。
 */
@Data
@TableName("canvas")
public class Canvas {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private Long userId;

    /** 画布名（用户在"我的创作"里看到的标题） */
    private String name;

    /**
     * 缩略图 URL。
     * 可空：刚建的画布没有产物时为空；
     * 有产物时由 CanvasBackfillRunner 或 CanvasAsyncExecutor
     * 取第一张成功节点产物的图来填充。
     */
    private String thumbnail;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}