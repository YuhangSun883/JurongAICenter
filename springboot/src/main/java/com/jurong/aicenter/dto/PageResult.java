package com.jurong.aicenter.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通用分页响应（资产模块统一使用）。
 * 与 admin/PageResponse 区分：本类放 dto/ 根下，给业务模块共享。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {
    private List<T> items;
    private long total;
    private int page;
    private int pageSize;
}
