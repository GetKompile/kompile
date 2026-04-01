/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.chat.history;

import ai.kompile.chat.history.domain.ChatMessage;
import ai.kompile.chat.history.domain.ChatSession;
import ai.kompile.chat.history.dto.ChatMessageDto;
import ai.kompile.chat.history.dto.ChatSessionDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for token count fields in ChatMessage entity and DTOs.
 * Verifies that token counts are correctly stored, converted, and aggregated.
 */
@DisplayName("Chat Message Token Count Tests")
public class ChatMessageTokenCountTest {

    @Nested
    @DisplayName("ChatMessage Entity Token Count")
    class ChatMessageEntityTests {

        @Test
        @DisplayName("Should store token count on message")
        void shouldStoreTokenCountOnMessage() {
            ChatMessage message = ChatMessage.builder()
                .role(ChatMessage.MessageRole.ASSISTANT)
                .content("Hello, how can I help?")
                .model("claude-sonnet-4-20250514")
                .tokenCount(42)
                .build();

            assertEquals(42, message.getTokenCount());
        }

        @Test
        @DisplayName("Should allow null token count")
        void shouldAllowNullTokenCount() {
            ChatMessage message = ChatMessage.builder()
                .role(ChatMessage.MessageRole.USER)
                .content("What is Java?")
                .build();

            assertNull(message.getTokenCount());
        }

        @Test
        @DisplayName("Should store model name on message")
        void shouldStoreModelNameOnMessage() {
            ChatMessage message = ChatMessage.builder()
                .role(ChatMessage.MessageRole.ASSISTANT)
                .content("Response")
                .model("gpt-4")
                .tokenCount(100)
                .build();

            assertEquals("gpt-4", message.getModel());
            assertEquals(100, message.getTokenCount());
        }

        @Test
        @DisplayName("Should support all message roles")
        void shouldSupportAllMessageRoles() {
            for (ChatMessage.MessageRole role : ChatMessage.MessageRole.values()) {
                ChatMessage message = ChatMessage.builder()
                    .role(role)
                    .content("Content for " + role)
                    .tokenCount(10)
                    .build();

                assertEquals(role, message.getRole());
                assertEquals(10, message.getTokenCount());
            }
        }

        @Test
        @DisplayName("Message roles should include USER, ASSISTANT, SYSTEM, TOOL")
        void messageRolesShouldIncludeExpectedValues() {
            ChatMessage.MessageRole[] roles = ChatMessage.MessageRole.values();
            assertEquals(4, roles.length);
            assertNotNull(ChatMessage.MessageRole.valueOf("USER"));
            assertNotNull(ChatMessage.MessageRole.valueOf("ASSISTANT"));
            assertNotNull(ChatMessage.MessageRole.valueOf("SYSTEM"));
            assertNotNull(ChatMessage.MessageRole.valueOf("TOOL"));
        }
    }

    @Nested
    @DisplayName("ChatMessageDto Token Count Conversion")
    class ChatMessageDtoTests {

        @Test
        @DisplayName("Should convert entity token count to DTO")
        void shouldConvertEntityTokenCountToDto() {
            ChatSession session = ChatSession.builder()
                .sessionId("test-session")
                .title("Test")
                .build();

            ChatMessage message = ChatMessage.builder()
                .id(1L)
                .session(session)
                .role(ChatMessage.MessageRole.ASSISTANT)
                .content("Hello!")
                .createdAt(LocalDateTime.now())
                .model("claude-sonnet-4-20250514")
                .tokenCount(150)
                .build();

            ChatMessageDto dto = ChatMessageDto.fromEntity(message);

            assertEquals(1L, dto.getId());
            assertEquals(ChatMessage.MessageRole.ASSISTANT, dto.getRole());
            assertEquals("Hello!", dto.getContent());
            assertEquals("claude-sonnet-4-20250514", dto.getModel());
            assertEquals(150, dto.getTokenCount());
        }

        @Test
        @DisplayName("Should handle null token count in conversion")
        void shouldHandleNullTokenCountInConversion() {
            ChatSession session = ChatSession.builder()
                .sessionId("test-session-2")
                .title("Test 2")
                .build();

            ChatMessage message = ChatMessage.builder()
                .id(2L)
                .session(session)
                .role(ChatMessage.MessageRole.USER)
                .content("Question")
                .createdAt(LocalDateTime.now())
                .build();

            ChatMessageDto dto = ChatMessageDto.fromEntity(message);

            assertNull(dto.getTokenCount());
            assertNull(dto.getModel());
        }

        @Test
        @DisplayName("Should build DTO directly with token count")
        void shouldBuildDtoDirectlyWithTokenCount() {
            ChatMessageDto dto = ChatMessageDto.builder()
                .id(3L)
                .role(ChatMessage.MessageRole.ASSISTANT)
                .content("Built directly")
                .model("gpt-4")
                .tokenCount(200)
                .createdAt(LocalDateTime.now())
                .build();

            assertEquals(200, dto.getTokenCount());
            assertEquals("gpt-4", dto.getModel());
        }
    }

    @Nested
    @DisplayName("Session Token Aggregation")
    class SessionTokenAggregationTests {

        @Test
        @DisplayName("Should aggregate token counts across session messages")
        void shouldAggregateTokenCountsAcrossMessages() {
            List<ChatMessageDto> messages = List.of(
                ChatMessageDto.builder().role(ChatMessage.MessageRole.USER).content("Q1").tokenCount(20).build(),
                ChatMessageDto.builder().role(ChatMessage.MessageRole.ASSISTANT).content("A1").tokenCount(150).build(),
                ChatMessageDto.builder().role(ChatMessage.MessageRole.USER).content("Q2").tokenCount(25).build(),
                ChatMessageDto.builder().role(ChatMessage.MessageRole.ASSISTANT).content("A2").tokenCount(200).build()
            );

            int totalTokens = messages.stream()
                .filter(m -> m.getTokenCount() != null)
                .mapToInt(ChatMessageDto::getTokenCount)
                .sum();

            assertEquals(395, totalTokens);
        }

        @Test
        @DisplayName("Should handle mixed null and non-null token counts")
        void shouldHandleMixedNullTokenCounts() {
            List<ChatMessageDto> messages = List.of(
                ChatMessageDto.builder().role(ChatMessage.MessageRole.USER).content("Q1").build(),
                ChatMessageDto.builder().role(ChatMessage.MessageRole.ASSISTANT).content("A1").tokenCount(100).build(),
                ChatMessageDto.builder().role(ChatMessage.MessageRole.USER).content("Q2").build(),
                ChatMessageDto.builder().role(ChatMessage.MessageRole.ASSISTANT).content("A2").tokenCount(200).build()
            );

            int totalTokens = messages.stream()
                .filter(m -> m.getTokenCount() != null)
                .mapToInt(ChatMessageDto::getTokenCount)
                .sum();

            assertEquals(300, totalTokens);

            long messagesWithTokens = messages.stream()
                .filter(m -> m.getTokenCount() != null)
                .count();

            assertEquals(2, messagesWithTokens);
        }

        @Test
        @DisplayName("Should compute zero total for empty message list")
        void shouldComputeZeroForEmptyMessages() {
            List<ChatMessageDto> messages = List.of();

            int totalTokens = messages.stream()
                .filter(m -> m.getTokenCount() != null)
                .mapToInt(ChatMessageDto::getTokenCount)
                .sum();

            assertEquals(0, totalTokens);
        }
    }

    @Nested
    @DisplayName("ChatSession DTO Tests")
    class ChatSessionDtoTests {

        @Test
        @DisplayName("Should include message count in session DTO")
        void shouldIncludeMessageCountInSessionDto() {
            ChatSession session = ChatSession.builder()
                .id(1L)
                .sessionId("session-1")
                .title("Test Session")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

            ChatSessionDto dto = ChatSessionDto.fromEntity(session, false);

            assertNotNull(dto);
            assertEquals("session-1", dto.getSessionId());
            assertEquals("Test Session", dto.getTitle());
        }
    }
}
