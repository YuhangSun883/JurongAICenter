package com.jurong.aicenter.dto.productimage;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 商详套图 —— 任务状态响应 DTO
 * <p>
 * 对应前端 frontend/src/types/product-image.ts 的 ProductImageTask。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductImageTaskResponse {

    /** 任务 id */
    private String taskId;

    /** editing / running / success / failed */
    private String status;

    /** 已生成的图片 URL 列表（MinIO 地址，生成过程中增量追加） */
    private List<String> imageUrls;

    /** 每张生成图对应的套图类型（与 imageUrls 顺序一致，前端结果卡片标签用） */
    private List<String> imageRoles;

    /** 任务用到的商品图预览（http(s) URL，data URI 不放入，避免响应过大） */
    private List<String> previewUrls;

    /** 预计/实际消耗积分 */
    private long creditsCost;

    /** 创建时间戳（毫秒） */
    private long createdAt;

    /** 失败原因（失败或部分失败时有值） */
    private String failReason;
}
