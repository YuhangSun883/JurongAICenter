package com.jurong.aicenter.dto.media;

import lombok.Data;

@Data
public class MediaListQuery {
    /** 资产库 ID（可选，不传则跨库） */
    private Long libraryId;

    /** image / video / audio */
    private String type;

    /** uploaded / ai-generated */
    private String source;

    /** 名称模糊搜索 */
    private String keyword;

    private Integer page = 1;
    private Integer pageSize = 20;
}
