package com.jurong.aicenter.dto.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RenameLibraryRequest {

    @NotBlank(message = "资产库名称不能为空")
    @Size(max = 100, message = "资产库名称不能超过 100 字符")
    private String name;

    private String iconKey;
}
