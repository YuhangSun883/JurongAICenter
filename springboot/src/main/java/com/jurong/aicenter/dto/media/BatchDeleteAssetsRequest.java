package com.jurong.aicenter.dto.media;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class BatchDeleteAssetsRequest {

    @NotEmpty(message = "素材 ID 列表不能为空")
    private List<Long> ids;
}
