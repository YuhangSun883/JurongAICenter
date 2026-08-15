package com.jurong.aicenter.dto.media;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class RenameLibraryRequest {

    @NotBlank(message = "资产库名称不能为空")
    @Size(max = 100, message = "资产库名称不能超过 100 字符")
    private String name;

    private String iconKey;

    /**
     * 业务类型（V18 引入）：normal / virtual_human / real_person
     * 改库类型时也要带
     */
    @Pattern(regexp = "^(normal|virtual_human|real_person)$",
        message = "bizType 必须为 normal / virtual_human / real_person")
    private String bizType;

    /** 授权用途说明（仅 real_person 类型） */
    @Size(max = 1000, message = "授权用途不能超过 1000 字符")
    private String authPurpose;

    /** 授权有效期（仅 real_person 类型） */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate authExpireAt;
}
