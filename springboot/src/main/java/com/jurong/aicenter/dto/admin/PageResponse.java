package com.jurong.aicenter.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通用分页响应（仅供 admin 模块使用 — Phase 9 之前不使用）。
 *
 * <p>字段命名刻意不与未来统一 PageResponse 冲突 — 当前命名贴合 admin 模块沿用的字段。
 * 如果 Phase 9 设计全局 PageResponse 后会替换为统一类型；当前先局部用。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {
    private List<T> items;
    private long total;
    private int page;
    private int pageSize;
}
