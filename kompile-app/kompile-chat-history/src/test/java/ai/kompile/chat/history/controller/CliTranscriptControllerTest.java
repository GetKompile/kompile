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

import ai.kompile.chat.history.domain.ChatSession;
import ai.kompile.chat.history.service.CliTranscriptService;
import ai.kompile.chat.history.service.CliTranscriptService.*;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CliTranscriptController Tests")
class CliTranscriptControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @Mock
    private CliTranscriptService cliTranscriptService;

    @InjectMocks
    private CliTranscriptController controller;

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

    // ═══════════════════════════════════════════════════════════════════════════════
    // GET /api/chat-history/cli/sources
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/chat-history/cli/sources")
    class DiscoverSources {

        @Test
        @DisplayName("should return 200 with all sources")
        void shouldReturnSources() throws Exception {
            Map<String, SourceInfo> sources = new LinkedHashMap<>();
            sources.put("kompile", new SourceInfo("/home/user/.kompile/conversations", 5, true));
            sources.put("claude-code", new SourceInfo("/home/user/.claude/projects", 12, true));
            sources.put("opencode", new SourceInfo("/home/user/.opencode/opencode.db", 0, false));
            sources.put("codex", new SourceInfo("/home/user/.codex/history.jsonl", 3, true));
            sources.put("qwen", new SourceInfo("/home/user/.qwen/projects", 0, false));

            when(cliTranscriptService.discoverSources()).thenReturn(sources);

            mockMvc.perform(get("/api/chat-history/cli/sources"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.kompile.count", is(5)))
                    .andExpect(jsonPath("$.kompile.available", is(true)))
                    .andExpect(jsonPath("$.['claude-code'].count", is(12)))
                    .andExpect(jsonPath("$.opencode.available", is(false)))
                    .andExpect(jsonPath("$.codex.count", is(3)))
                    .andExpect(jsonPath("$.qwen.available", is(false)));
        }

        @Test
        @DisplayName("should return 200 with empty sources when none available")
        void shouldReturnEmptySources() throws Exception {
            when(cliTranscriptService.discoverSources()).thenReturn(Map.of());

            mockMvc.perform(get("/api/chat-history/cli/sources"))
                    .andExpect(status().isOk())
                    .andExpect(content().json("{}"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // GET /api/chat-history/cli/sessions
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/chat-history/cli/sessions")
    class ListSessions {

        @Test
        @DisplayName("should list all sessions without filter")
        void shouldListAllSessions() throws Exception {
            List<CliSessionSummary> sessions = List.of(
                    new CliSessionSummary("session-1", "kompile", "Hello world", "default", 4, 1700000000000L),
                    new CliSessionSummary("abc/def.jsonl", "claude-code", "Fix bug", "claude", 20, 1700001000000L)
            );

            when(cliTranscriptService.listSessions(null)).thenReturn(sessions);

            mockMvc.perform(get("/api/chat-history/cli/sessions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].sessionId", is("session-1")))
                    .andExpect(jsonPath("$[0].source", is("kompile")))
                    .andExpect(jsonPath("$[0].title", is("Hello world")))
                    .andExpect(jsonPath("$[0].messageCount", is(4)))
                    .andExpect(jsonPath("$[1].source", is("claude-code")));
        }

        @Test
        @DisplayName("should filter by source when param provided")
        void shouldFilterBySource() throws Exception {
            List<CliSessionSummary> sessions = List.of(
                    new CliSessionSummary("s1", "claude-code", "Debug issue", "claude", 10, 1700000000000L)
            );

            when(cliTranscriptService.listSessions("claude-code")).thenReturn(sessions);

            mockMvc.perform(get("/api/chat-history/cli/sessions")
                            .param("source", "claude-code"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].source", is("claude-code")));
        }

        @Test
        @DisplayName("should return empty list when no sessions found")
        void shouldReturnEmptyList() throws Exception {
            when(cliTranscriptService.listSessions("codex")).thenReturn(List.of());

            mockMvc.perform(get("/api/chat-history/cli/sessions")
                            .param("source", "codex"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // GET /api/chat-history/cli/sessions/{sessionId}
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/chat-history/cli/sessions/{sessionId}")
    class ReadTranscript {

        @Test
        @DisplayName("should return transcript with parsed turns")
        void shouldReturnTranscript() throws Exception {
            List<ParsedTurn> turns = List.of(
                    new ParsedTurn("user", "What is Java?"),
                    new ParsedTurn("assistant", "Java is a programming language...")
            );
            CliTranscriptDetail detail = new CliTranscriptDetail(
                    "session-1", "kompile", "What is Java?", "default", turns);

            when(cliTranscriptService.readTranscript("session-1", "kompile")).thenReturn(detail);

            mockMvc.perform(get("/api/chat-history/cli/sessions/session-1")
                            .param("source", "kompile"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessionId", is("session-1")))
                    .andExpect(jsonPath("$.source", is("kompile")))
                    .andExpect(jsonPath("$.title", is("What is Java?")))
                    .andExpect(jsonPath("$.turns", hasSize(2)))
                    .andExpect(jsonPath("$.turns[0].role", is("user")))
                    .andExpect(jsonPath("$.turns[0].content", is("What is Java?")))
                    .andExpect(jsonPath("$.turns[1].role", is("assistant")));
        }

        @Test
        @DisplayName("should default source to kompile")
        void shouldDefaultSourceToKompile() throws Exception {
            CliTranscriptDetail detail = new CliTranscriptDetail(
                    "s1", "kompile", "Title", "default", List.of());

            when(cliTranscriptService.readTranscript("s1", "kompile")).thenReturn(detail);

            mockMvc.perform(get("/api/chat-history/cli/sessions/s1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.source", is("kompile")));
        }

        @Test
        @DisplayName("should return 404 when transcript not found")
        void shouldReturn404WhenNotFound() throws Exception {
            when(cliTranscriptService.readTranscript("nonexistent", "kompile"))
                    .thenThrow(new IllegalArgumentException("Transcript not found: nonexistent"));

            mockMvc.perform(get("/api/chat-history/cli/sessions/nonexistent")
                            .param("source", "kompile"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("should read claude-code transcript with encoded sessionId")
        void shouldReadClaudeCodeTranscript() throws Exception {
            // Session IDs with slashes must be URL-encoded by the client;
            // here we test a simple ID without slashes
            CliTranscriptDetail detail = new CliTranscriptDetail(
                    "abc123.jsonl", "claude-code", "Fix auth",
                    "claude", List.of(new ParsedTurn("user", "Fix auth")));

            when(cliTranscriptService.readTranscript("abc123.jsonl", "claude-code"))
                    .thenReturn(detail);

            mockMvc.perform(get("/api/chat-history/cli/sessions/abc123.jsonl")
                            .param("source", "claude-code"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.source", is("claude-code")));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // POST /api/chat-history/cli/sessions/{sessionId}/import
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/chat-history/cli/sessions/{sessionId}/import")
    class ImportTranscript {

        @Test
        @DisplayName("should import transcript and return 201")
        void shouldImportTranscript() throws Exception {
            ChatSession imported = ChatSession.builder()
                    .sessionId("imported-kompile-session-1")
                    .title("What is Java?")
                    .source("kompile")
                    .build();
            // Need messages list for fromEntity
            imported.setMessages(new ArrayList<>());

            when(cliTranscriptService.importTranscript("session-1", "kompile")).thenReturn(imported);

            mockMvc.perform(post("/api/chat-history/cli/sessions/session-1/import")
                            .param("source", "kompile"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sessionId", is("imported-kompile-session-1")))
                    .andExpect(jsonPath("$.title", is("What is Java?")))
                    .andExpect(jsonPath("$.source", is("kompile")));
        }

        @Test
        @DisplayName("should return 409 when transcript already imported")
        void shouldReturn409WhenDuplicate() throws Exception {
            when(cliTranscriptService.importTranscript("session-1", "kompile"))
                    .thenThrow(new IllegalStateException("Transcript already imported: session-1"));

            mockMvc.perform(post("/api/chat-history/cli/sessions/session-1/import")
                            .param("source", "kompile"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error", containsString("already imported")));
        }

        @Test
        @DisplayName("should return 400 when transcript has no messages")
        void shouldReturn400WhenEmpty() throws Exception {
            when(cliTranscriptService.importTranscript("empty-session", "kompile"))
                    .thenThrow(new IllegalArgumentException("No messages found in transcript: empty-session"));

            mockMvc.perform(post("/api/chat-history/cli/sessions/empty-session/import")
                            .param("source", "kompile"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", containsString("No messages")));
        }

        @Test
        @DisplayName("should import from different sources")
        void shouldImportFromDifferentSources() throws Exception {
            ChatSession imported = ChatSession.builder()
                    .sessionId("imported-claude-code-abc")
                    .title("Fix authentication")
                    .source("claude-code")
                    .build();
            imported.setMessages(new ArrayList<>());

            when(cliTranscriptService.importTranscript("abc", "claude-code")).thenReturn(imported);

            mockMvc.perform(post("/api/chat-history/cli/sessions/abc/import")
                            .param("source", "claude-code"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.source", is("claude-code")));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // POST /api/chat-history/cli/export/{sessionId}
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/chat-history/cli/export/{sessionId}")
    class ExportTranscript {

        @Test
        @DisplayName("should export session and return path")
        void shouldExportSession() throws Exception {
            Path exportPath = Path.of("/home/user/.kompile/conversations/exported-app-session-1.txt");
            when(cliTranscriptService.exportToTranscript("session-1")).thenReturn(exportPath);

            mockMvc.perform(post("/api/chat-history/cli/export/session-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transcriptPath", containsString("exported-app-session-1.txt")))
                    .andExpect(jsonPath("$.sessionId", is("session-1")));
        }

        @Test
        @DisplayName("should return 400 when session not found")
        void shouldReturn400WhenSessionNotFound() throws Exception {
            when(cliTranscriptService.exportToTranscript("nonexistent"))
                    .thenThrow(new IllegalArgumentException("Session not found: nonexistent"));

            mockMvc.perform(post("/api/chat-history/cli/export/nonexistent"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error", containsString("Session not found")));
        }

        @Test
        @DisplayName("should return 500 on IO error")
        void shouldReturn500OnIoError() throws Exception {
            when(cliTranscriptService.exportToTranscript("session-1"))
                    .thenThrow(new java.io.IOException("Disk full"));

            mockMvc.perform(post("/api/chat-history/cli/export/session-1"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error", containsString("Disk full")));
        }
    }
}
