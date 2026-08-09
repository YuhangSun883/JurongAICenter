package com.jurong.aicenter.dto.media;

import com.jurong.aicenter.entity.MediaRole;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MediaRoleDto {
    private Long id;
    private String name;
    private String category;
    private String imageUrl;
    private String description;
    private List<String> tags;
    private Long createdAt;

    public static MediaRoleDto from(MediaRole r) {
        if (r == null) return null;
        List<String> tagList = null;
        if (r.getTags() != null && !r.getTags().isBlank()) {
            tagList = Arrays.stream(r.getTags().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        }
        return new MediaRoleDto(
            r.getId(),
            r.getName(),
            r.getCategory(),
            r.getImageUrl(),
            r.getDescription(),
            tagList,
            r.getCreatedAt() != null ? r.getCreatedAt().toInstant(java.time.ZoneOffset.ofHours(8)).toEpochMilli() : null
        );
    }
}