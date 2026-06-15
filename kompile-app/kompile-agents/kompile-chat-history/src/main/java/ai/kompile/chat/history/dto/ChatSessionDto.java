/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.chat.history.dto;

import ai.kompile.chat.history.domain.ChatSession;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DTO for ChatSession.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionDto {
    private String sessionId;
    private String title;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String userId;
    private String source;
    private String codeProjectId;
    private List<ChatMessageDto> messages;
    private int messageCount;
    private Long originalTimestamp;

    public static ChatSessionDto fromEntity(ChatSession session, boolean includeMessages) {
        ChatSessionDtoBuilder builder = ChatSessionDto.builder()
            .sessionId(session.getSessionId())
            .title(session.getTitle())
            .description(session.getDescription())
            .createdAt(session.getCreatedAt())
            .updatedAt(session.getUpdatedAt())
            .userId(session.getUserId())
            .source(session.getSource())
            .messageCount(session.getMessageCount())
            .originalTimestamp(session.getOriginalTimestamp());

        if (includeMessages) {
            builder.messages(session.getMessages().stream()
                .map(ChatMessageDto::fromEntity)
                .collect(Collectors.toList()));
        }

        return builder.build();
    }
}
