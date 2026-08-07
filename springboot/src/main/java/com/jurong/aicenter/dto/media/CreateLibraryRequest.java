package com.jurong.aicenter.dto.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateLibraryRequest {

    @NotBlank(message = "资产库名称不能为空")
    @Size(max = 100, message = "资产库名称不能超过 100 字符")
    private String name;

    /** folder / star / heart / sparkles，默认 folder */
    private String iconKey;

    @Size(max = 500, message = "描述不能超过 500 字符")
    private String description;
}
