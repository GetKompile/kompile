/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.chat.history.controller;

import ai.kompile.chat.history.domain.ChatMessage;
import ai.kompile.chat.history.domain.ChatSession;
import ai.kompile.chat.history.service.ChatHistoryService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatHistoryController Tests")
class ChatHistoryControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private ChatHistoryService chatHistoryService;

    @InjectMocks
    private ChatHistoryController controller;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter();
        converter.setObjectMapper(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(converter)
                .build();
    }

    private ChatSession buildSession(String sessionId, String title, String source) {
        ChatSession session = ChatSession.builder()
                .sessionId(sessionId)
                .title(title)
                .source(source)
                .createdAt(LocalDateTime.of(2025, 1, 15, 10, 0))
                .updatedAt(LocalDateTime.of(2025, 1, 15, 10, 30))
                .build();
        session.setMessages(new ArrayList<>());
        return session;
    }

    private ChatSession buildSessionWithMessages(String sessionId, String title, String source, int msgCount) {
        ChatSession session = buildSession(sessionId, title, source);
        for (int i = 0; i < msgCount; i++) {
            ChatMessage msg = ChatMessage.builder()
                    .id((long) (i + 1))
                    .session(session)
                    .role(i % 2 == 0 ? ChatMessage.MessageRole.USER : ChatMessage.MessageRole.ASSISTANT)
                    .content("Message " + (i + 1))
                    .createdAt(LocalDateTime.of(2025, 1, 15, 10, i))
                    .build();
            session.getMessages().add(msg);
        }
        return session;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // POST /api/chat-history/sessions
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/chat-history/sessions")
    class CreateSession {

        @Test
        @DisplayName("should create session and return 201")
        void shouldCreateSession() throws Exception {
            ChatSession session = buildSession("uuid-123", "My Chat", null);
            when(chatHistoryService.createSession("My Chat", null)).thenReturn(session);

            mockMvc.perform(post("/api/chat-history/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\": \"My Chat\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sessionId", is("uuid-123")))
                    .andExpect(jsonPath("$.title", is("My Chat")));
        }

        @Test
        @DisplayName("should create session with userId")
        void shouldCreateSessionWithUserId() throws Exception {
            ChatSession session = buildSession("uuid-456", "User Chat", null);
            session.setUserId("user-1");
            when(chatHistoryService.createSession("User Chat", "user-1")).thenReturn(session);

            mockMvc.perform(post("/api/chat-history/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\": \"User Chat\", \"userId\": \"user-1\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.userId", is("user-1")));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // GET /api/chat-history/sessions (with source filter)
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/chat-history/sessions")
    class GetSessions {

        @Test
        @DisplayName("should return recent sessions when no filter")
        void shouldReturnRecentSessions() throws Exception {
            List<ChatSession> sessions = List.of(
                    buildSession("s1", "App Chat", null),
                    buildSession("s2", "Imported CLI", "kompile"),
                    buildSession("s3", "Claude Session", "claude-code")
            );
            when(chatHistoryService.getRecentSessions(100)).thenReturn(sessions);

            mockMvc.perform(get("/api/chat-history/sessions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(3)))
                    .andExpect(jsonPath("$[0].sessionId", is("s1")))
                    .andExpect(jsonPath("$[1].source", is("kompile")))
                    .andExpect(jsonPath("$[2].source", is("claude-code")));
        }

        @Test
        @DisplayName("should filter by source when source param provided")
        void shouldFilterBySource() throws Exception {
            List<ChatSession> sessions = List.of(
                    buildSession("s2", "Kompile Chat", "kompile"),
                    buildSession("s5", "Another Kompile", "kompile")
            );
            when(chatHistoryService.getSessionsBySource("kompile")).thenReturn(sessions);

            mockMvc.perform(get("/api/chat-history/sessions")
                            .param("source", "kompile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].source", is("kompile")))
                    .andExpect(jsonPath("$[1].source", is("kompile")));
        }

        @Test
        @DisplayName("should filter by claude-code source")
        void shouldFilterByClaudeCode() throws Exception {
            List<ChatSession> sessions = List.of(
                    buildSession("s3", "Claude Session", "claude-code")
            );
            when(chatHistoryService.getSessionsBySource("claude-code")).thenReturn(sessions);

            mockMvc.perform(get("/api/chat-history/sessions")
                            .param("source", "claude-code"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].source", is("claude-code")));
        }

        @Test
        @DisplayName("should return empty list for source with no sessions")
        void shouldReturnEmptyForUnknownSource() throws Exception {
            when(chatHistoryService.getSessionsBySource("qwen")).thenReturn(List.of());

            mockMvc.perform(get("/api/chat-history/sessions")
                            .param("source", "qwen"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("should filter by userId when provided")
        void shouldFilterByUserId() throws Exception {
            List<ChatSession> sessions = List.of(
                    buildSession("s1", "User Chat", null)
            );
            when(chatHistoryService.getUserSessions("user-1")).thenReturn(sessions);

            mockMvc.perform(get("/api/chat-history/sessions")
                            .param("userId", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }

        @Test
        @DisplayName("source param should take precedence over userId")
        void sourceShouldTakePrecedenceOverUserId() throws Exception {
            List<ChatSession> sessions = List.of(
                    buildSession("s1", "Codex Chat", "codex")
            );
            when(chatHistoryService.getSessionsBySource("codex")).thenReturn(sessions);

            mockMvc.perform(get("/api/chat-history/sessions")
                            .param("source", "codex")
                            .param("userId", "user-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].source", is("codex")));

            // Should have called getSessionsBySource, not getUserSessions
            verify(chatHistoryService).getSessionsBySource("codex");
            verify(chatHistoryService, never()).getUserSessions(any());
        }

        @Test
        @DisplayName("should include source field in session DTOs")
        void shouldIncludeSourceFieldInDtos() throws Exception {
            List<ChatSession> sessions = List.of(
                    buildSession("s1", "App Chat", null),
                    buildSession("s2", "Imported from Kompile", "kompile"),
                    buildSession("s3", "Claude convo", "claude-code"),
                    buildSession("s4", "OpenCode session", "opencode")
            );
            when(chatHistoryService.getRecentSessions(100)).thenReturn(sessions);

            mockMvc.perform(get("/api/chat-history/sessions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].source").value(nullValue()))  // null source
                    .andExpect(jsonPath("$[1].source", is("kompile")))
                    .andExpect(jsonPath("$[2].source", is("claude-code")))
                    .andExpect(jsonPath("$[3].source", is("opencode")));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // GET /api/chat-history/sessions/{sessionId}
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/chat-history/sessions/{sessionId}")
    class GetSession {

        @Test
        @DisplayName("should return session with messages")
        void shouldReturnSessionWithMessages() throws Exception {
            ChatSession session = buildSessionWithMessages("s1", "Test Chat", "kompile", 4);
            when(chatHistoryService.getSession("s1")).thenReturn(Optional.of(session));

            mockMvc.perform(get("/api/chat-history/sessions/s1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessionId", is("s1")))
                    .andExpect(jsonPath("$.title", is("Test Chat")))
                    .andExpect(jsonPath("$.source", is("kompile")))
                    .andExpect(jsonPath("$.messageCount", is(4)))
                    .andExpect(jsonPath("$.messages", hasSize(4)))
                    .andExpect(jsonPath("$.messages[0].role", is("USER")))
                    .andExpect(jsonPath("$.messages[1].role", is("ASSISTANT")));
        }

        @Test
        @DisplayName("should return 404 for nonexistent session")
        void shouldReturn404() throws Exception {
            when(chatHistoryService.getSession("nonexistent")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/chat-history/sessions/nonexistent"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should include source for synced sessions")
        void shouldIncludeSourceForSyncedSessions() throws Exception {
            ChatSession session = buildSessionWithMessages("imported-claude-code-abc", "Fix bug", "claude-code", 2);
            when(chatHistoryService.getSession("imported-claude-code-abc")).thenReturn(Optional.of(session));

            mockMvc.perform(get("/api/chat-history/sessions/imported-claude-code-abc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.source", is("claude-code")))
                    .andExpect(jsonPath("$.messages", hasSize(2)));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PATCH /api/chat-history/sessions/{sessionId}/title
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PATCH /api/chat-history/sessions/{sessionId}/title")
    class UpdateTitle {

        @Test
        @DisplayName("should update session title")
        void shouldUpdateTitle() throws Exception {
            ChatSession session = buildSession("s1", "Updated Title", null);
            when(chatHistoryService.updateSessionTitle("s1", "Updated Title"))
                    .thenReturn(Optional.of(session));

            mockMvc.perform(patch("/api/chat-history/sessions/s1/title")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\": \"Updated Title\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title", is("Updated Title")));
        }

        @Test
        @DisplayName("should return 404 when session not found")
        void shouldReturn404() throws Exception {
            when(chatHistoryService.updateSessionTitle("nonexistent", "Title"))
                    .thenReturn(Optional.empty());

            mockMvc.perform(patch("/api/chat-history/sessions/nonexistent/title")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\": \"Title\"}"))
                    .andExpect(status().isNotFound());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // DELETE /api/chat-history/sessions/{sessionId}
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DELETE /api/chat-history/sessions/{sessionId}")
    class DeleteSession {

        @Test
        @DisplayName("should delete session and return 204")
        void shouldDeleteSession() throws Exception {
            when(chatHistoryService.deleteSession("s1")).thenReturn(true);

            mockMvc.perform(delete("/api/chat-history/sessions/s1"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("should return 404 when session not found")
        void shouldReturn404() throws Exception {
            when(chatHistoryService.deleteSession("nonexistent")).thenReturn(false);

            mockMvc.perform(delete("/api/chat-history/sessions/nonexistent"))
                    .andExpect(status().isNotFound());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // POST /api/chat-history/sessions/{sessionId}/messages
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/chat-history/sessions/{sessionId}/messages")
    class AddMessage {

        @Test
        @DisplayName("should add message and return 201")
        void shouldAddMessage() throws Exception {
            ChatSession session = buildSession("s1", "Chat", null);
            ChatMessage message = ChatMessage.builder()
                    .id(1L)
                    .session(session)
                    .role(ChatMessage.MessageRole.USER)
                    .content("Hello!")
                    .createdAt(LocalDateTime.now())
                    .build();

            when(chatHistoryService.addMessage(eq("s1"), eq(ChatMessage.MessageRole.USER), eq("Hello!"), isNull()))
                    .thenReturn(message);

            mockMvc.perform(post("/api/chat-history/sessions/s1/messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\": \"USER\", \"content\": \"Hello!\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.role", is("USER")))
                    .andExpect(jsonPath("$.content", is("Hello!")));
        }

        @Test
        @DisplayName("should return 404 when session not found")
        void shouldReturn404WhenSessionNotFound() throws Exception {
            when(chatHistoryService.addMessage(eq("nonexistent"), any(), any(), any()))
                    .thenThrow(new IllegalArgumentException("Session not found: nonexistent"));

            mockMvc.perform(post("/api/chat-history/sessions/nonexistent/messages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"role\": \"USER\", \"content\": \"Hello!\"}"))
                    .andExpect(status().isNotFound());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // GET /api/chat-history/sessions/{sessionId}/messages
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/chat-history/sessions/{sessionId}/messages")
    class GetMessages {

        @Test
        @DisplayName("should return messages for session")
        void shouldReturnMessages() throws Exception {
            ChatSession session = buildSession("s1", "Chat", null);
            List<ChatMessage> messages = List.of(
                    ChatMessage.builder().id(1L).session(session)
                            .role(ChatMessage.MessageRole.USER).content("Hi").createdAt(LocalDateTime.now()).build(),
                    ChatMessage.builder().id(2L).session(session)
                            .role(ChatMessage.MessageRole.ASSISTANT).content("Hello!").createdAt(LocalDateTime.now()).build()
            );
            when(chatHistoryService.getSessionMessages("s1")).thenReturn(messages);

            mockMvc.perform(get("/api/chat-history/sessions/s1/messages"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].role", is("USER")))
                    .andExpect(jsonPath("$[0].content", is("Hi")))
                    .andExpect(jsonPath("$[1].role", is("ASSISTANT")));
        }

        @Test
        @DisplayName("should return empty list for session with no messages")
        void shouldReturnEmptyList() throws Exception {
            when(chatHistoryService.getSessionMessages("s1")).thenReturn(List.of());

            mockMvc.perform(get("/api/chat-history/sessions/s1/messages"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // GET /api/chat-history/messages/{messageId}
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/chat-history/messages/{messageId}")
    class GetMessageById {

        @Test
        @DisplayName("should return message by ID")
        void shouldReturnMessage() throws Exception {
            ChatSession session = buildSession("s1", "Chat", null);
            ChatMessage message = ChatMessage.builder()
                    .id(42L).session(session)
                    .role(ChatMessage.MessageRole.ASSISTANT)
                    .content("Full response content here")
                    .model("claude-sonnet-4-20250514")
                    .tokenCount(150)
                    .createdAt(LocalDateTime.now())
                    .build();
            when(chatHistoryService.getMessageById(42L)).thenReturn(Optional.of(message));

            mockMvc.perform(get("/api/chat-history/messages/42"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is(42)))
                    .andExpect(jsonPath("$.role", is("ASSISTANT")))
                    .andExpect(jsonPath("$.content", is("Full response content here")))
                    .andExpect(jsonPath("$.model", is("claude-sonnet-4-20250514")))
                    .andExpect(jsonPath("$.tokenCount", is(150)));
        }

        @Test
        @DisplayName("should return 404 for nonexistent message")
        void shouldReturn404() throws Exception {
            when(chatHistoryService.getMessageById(999L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/chat-history/messages/999"))
                    .andExpect(status().isNotFound());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // GET /api/chat-history/messages/{messageId}/content
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/chat-history/messages/{messageId}/content")
    class GetMessageContent {

        @Test
        @DisplayName("should return raw message content")
        void shouldReturnContent() throws Exception {
            when(chatHistoryService.getMessageContent(42L))
                    .thenReturn(Optional.of("Full untruncated content"));

            mockMvc.perform(get("/api/chat-history/messages/42/content"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Full untruncated content")));
        }

        @Test
        @DisplayName("should return 404 for nonexistent message")
        void shouldReturn404() throws Exception {
            when(chatHistoryService.getMessageContent(999L)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/chat-history/messages/999/content"))
                    .andExpect(status().isNotFound());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // GET /api/chat-history/sessions/{sessionId}/messages/until/{messageId}
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/chat-history/sessions/{sessionId}/messages/until/{messageId}")
    class GetMessagesUntil {

        @Test
        @DisplayName("should return messages up to specified ID")
        void shouldReturnMessagesUntil() throws Exception {
            ChatSession session = buildSession("s1", "Chat", null);
            List<ChatMessage> messages = List.of(
                    ChatMessage.builder().id(1L).session(session)
                            .role(ChatMessage.MessageRole.USER).content("Q1").createdAt(LocalDateTime.now()).build(),
                    ChatMessage.builder().id(2L).session(session)
                            .role(ChatMessage.MessageRole.ASSISTANT).content("A1").createdAt(LocalDateTime.now()).build(),
                    ChatMessage.builder().id(3L).session(session)
                            .role(ChatMessage.MessageRole.USER).content("Q2").createdAt(LocalDateTime.now()).build()
            );
            when(chatHistoryService.getMessagesUntil("s1", 3L)).thenReturn(messages);

            mockMvc.perform(get("/api/chat-history/sessions/s1/messages/until/3"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(3)))
                    .andExpect(jsonPath("$[2].id", is(3)));
        }
    }
}
