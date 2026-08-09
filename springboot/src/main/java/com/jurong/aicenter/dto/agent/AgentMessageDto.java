package com.jurong.aicenter.dto.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurong.aicenter.entity.AgentMessage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentMessageDto {
    private String id;
    private String sessionId;
    private String role;
    private String content;
    private List<?> attachments;
    private List<?> toolCalls;
    private Long createdAt;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static AgentMessageDto from(AgentMessage m) {
        if (m == null) return null;
        return new AgentMessageDto(
            m.getId(),
            m.getSessionId(),
            m.getRole(),
            m.getContent(),
            parseJson(m.getAttachments(), new TypeReference<List<?>>() {}),
            parseJson(m.getToolCalls(), new TypeReference<List<?>>() {}),
            m.getCreatedAt() != null ? m.getCreatedAt().toInstant(java.time.ZoneOffset.ofHours(8)).toEpochMilli() : System.currentTimeMillis()
        );
    }

    private static <T> T parseJson(String json, TypeReference<T> ref) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, ref);
        } catch (Exception e) {
            return null;
        }
    }
}
