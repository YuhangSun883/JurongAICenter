package com.jurong.aicenter.dto.media;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PatchAssetRequest {

    @Size(max = 255, message = "文件名不能超过 255 字符")
    private String name;
}
