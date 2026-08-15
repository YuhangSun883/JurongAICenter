package com.jurong.aicenter.dto.media;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class CreateLibraryRequest {

    @NotBlank(message = "资产库名称不能为空")
    @Size(max = 100, message = "资产库名称不能超过 100 字符")
    private String name;

    /** folder / star / heart / sparkles，默认 folder */
    private String iconKey;

    @Size(max = 500, message = "描述不能超过 500 字符")
    private String description;

    /**
     * 业务类型（V18 引入）：normal / virtual_human / real_person
     * 默认 normal
     */
    @Pattern(regexp = "^(normal|virtual_human|real_person)$",
        message = "bizType 必须为 normal / virtual_human / real_person")
    private String bizType;

    /** 授权用途说明（仅 real_person 类型必填） */
    @Size(max = 1000, message = "授权用途不能超过 1000 字符")
    private String authPurpose;

    /** 授权有效期（仅 real_person 类型必填） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate authExpireAt;

    /**
     * 2026-08-15 V19：父库 id，可选
     * - null/不传：建根库
     * - 传具体 id：建为该父库的子库
     * 后端校验：父库存在、归属、非系统库、类型匹配（虚拟人/真人下子库类型必须一致）
     */
    @Positive(message = "parentId 必须为正数")
    private Long parentId;
}
