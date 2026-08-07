package com.jurong.aicenter.dto.agent;

import com.jurong.aicenter.entity.AgentSession;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentSessionDto {
    private String id;
    private String title;
    private Boolean pinned;
    private Integer creditsUsed;
    private Long createdAt;
    private Long updatedAt;

    public static AgentSessionDto from(AgentSession s) {
        if (s == null) return null;
        return new AgentSessionDto(
            s.getId(),
            s.getTitle(),
            s.getPinned(),
            s.getCreditsUsed(),
            s.getCreatedAt() != null ? s.getCreatedAt().toInstant(java.time.ZoneOffset.ofHours(8)).toEpochMilli() : System.currentTimeMillis(),
            s.getUpdatedAt() != null ? s.getUpdatedAt().toInstant(java.time.ZoneOffset.ofHours(8)).toEpochMilli() : System.currentTimeMillis()
        );
    }
}
