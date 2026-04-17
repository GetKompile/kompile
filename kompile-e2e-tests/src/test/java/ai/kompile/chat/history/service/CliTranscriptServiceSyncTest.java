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
package ai.kompile.chat.history.service;

import ai.kompile.chat.history.config.ChatHistoryProperties;
import ai.kompile.chat.history.domain.ChatSession;
import ai.kompile.chat.history.service.CliTranscriptService.CliSessionSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CliTranscriptService Sync Helper Tests")
class CliTranscriptServiceSyncTest {

    @Mock
    private ChatHistoryService chatHistoryService;

    private ChatHistoryProperties properties;
    private CliTranscriptService cliTranscriptService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        properties = new ChatHistoryProperties();
        properties.setCliConversationsPath(tempDir.toString());
        cliTranscriptService = new CliTranscriptService(properties, chatHistoryService);
        cliTranscriptService.init();
    }

    @Nested
    @DisplayName("listNewSessions")
    class ListNewSessions {

        @Test
        @DisplayName("should return empty list when no kompile transcripts exist")
        void shouldReturnEmptyWhenNoTranscripts() {
            var result = cliTranscriptService.listNewSessions("kompile");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return sessions not yet imported")
        void shouldReturnOnlyNewSessions() throws IOException {
            // Create a transcript file
            Path transcript = tempDir.resolve("test-session.txt");
            Files.writeString(transcript, """
                    ──── Conversation: test-session ────
                    Started: 2025-01-15 10:00:00
                    Server:  localhost
                    Agent:   default
                    RAG:     anserini

                    ──────────────────────────────────

                    > Hello world

                    Hi there!

                    """);

            // Not imported yet
            when(chatHistoryService.getSession("imported-kompile-test-session")).thenReturn(Optional.empty());

            var result = cliTranscriptService.listNewSessions("kompile");
            assertEquals(1, result.size());
            assertEquals("test-session", result.get(0).sessionId());
            assertEquals("kompile", result.get(0).source());
        }

        @Test
        @DisplayName("should exclude already imported sessions")
        void shouldExcludeAlreadyImported() throws IOException {
            Path transcript = tempDir.resolve("already-imported.txt");
            Files.writeString(transcript, """
                    ──── Conversation: already-imported ────
                    Started: 2025-01-15 10:00:00
                    Server:  localhost
                    Agent:   default
                    RAG:     anserini

                    ──────────────────────────────────

                    > Test question

                    Test answer

                    """);

            // Already imported
            ChatSession existing = ChatSession.builder()
                    .sessionId("imported-kompile-already-imported")
                    .title("Test question")
                    .source("kompile")
                    .build();
            when(chatHistoryService.getSession("imported-kompile-already-imported"))
                    .thenReturn(Optional.of(existing));

            var result = cliTranscriptService.listNewSessions("kompile");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return mix of new and exclude imported")
        void shouldReturnMixOfNewAndExcludeImported() throws IOException {
            // Create two transcripts
            Files.writeString(tempDir.resolve("new-one.txt"), """
                    ──── Conversation: new-one ────
                    Started: 2025-01-15 10:00:00
                    Server:  localhost
                    Agent:   default
                    RAG:     anserini

                    ──────────────────────────────────

                    > New question

                    New answer

                    """);

            Files.writeString(tempDir.resolve("old-one.txt"), """
                    ──── Conversation: old-one ────
                    Started: 2025-01-15 09:00:00
                    Server:  localhost
                    Agent:   default
                    RAG:     anserini

                    ──────────────────────────────────

                    > Old question

                    Old answer

                    """);

            // old-one already imported, new-one not
            when(chatHistoryService.getSession("imported-kompile-new-one")).thenReturn(Optional.empty());
            when(chatHistoryService.getSession("imported-kompile-old-one"))
                    .thenReturn(Optional.of(ChatSession.builder().sessionId("imported-kompile-old-one").build()));

            var result = cliTranscriptService.listNewSessions("kompile");
            assertEquals(1, result.size());
            assertEquals("new-one", result.get(0).sessionId());
        }
    }

    @Nested
    @DisplayName("syncSource")
    class SyncSource {

        @Test
        @DisplayName("should return 0 when no new sessions")
        void shouldReturnZeroWhenNoNewSessions() {
            int count = cliTranscriptService.syncSource("kompile", 50);
            assertEquals(0, count);
        }

        @Test
        @DisplayName("should import new sessions and return count")
        void shouldImportNewSessions() throws IOException {
            Files.writeString(tempDir.resolve("sync-test.txt"), """
                    ──── Conversation: sync-test ────
                    Started: 2025-01-15 10:00:00
                    Server:  localhost
                    Agent:   default
                    RAG:     anserini

                    ──────────────────────────────────

                    > Sync test question

                    Sync test answer

                    """);

            // Mock the import chain
            ChatSession importedSession = ChatSession.builder()
                    .sessionId("imported-kompile-sync-test")
                    .title("Sync test question")
                    .source("kompile")
                    .build();
            when(chatHistoryService.createSessionWithId(eq("imported-kompile-sync-test"), anyString(), eq("kompile")))
                    .thenReturn(importedSession);
            when(chatHistoryService.addMessage(anyString(), any(), anyString(), any()))
                    .thenReturn(null);
            // Called in: listNewSessions filter, importTranscript duplicate check, importTranscript return
            when(chatHistoryService.getSession(eq("imported-kompile-sync-test")))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(importedSession));

            int count = cliTranscriptService.syncSource("kompile", 50);
            assertEquals(1, count);
        }

        @Test
        @DisplayName("should respect batch size limit")
        void shouldRespectBatchSize() throws IOException {
            // Create 5 transcripts
            for (int i = 0; i < 5; i++) {
                Files.writeString(tempDir.resolve("batch-" + i + ".txt"), String.format("""
                        ──── Conversation: batch-%d ────
                        Started: 2025-01-15 10:0%d:00
                        Server:  localhost
                        Agent:   default
                        RAG:     anserini

                        ──────────────────────────────────

                        > Question %d

                        Answer %d

                        """, i, i, i, i));
            }

            // None imported yet
            when(chatHistoryService.getSession(argThat(s -> s != null && s.startsWith("imported-kompile-batch-"))))
                    .thenReturn(Optional.empty());

            // Mock import for first 2
            when(chatHistoryService.createSessionWithId(anyString(), anyString(), eq("kompile")))
                    .thenAnswer(inv -> ChatSession.builder()
                            .sessionId(inv.getArgument(0))
                            .title(inv.getArgument(1))
                            .source("kompile")
                            .build());
            when(chatHistoryService.addMessage(anyString(), any(), anyString(), any()))
                    .thenReturn(null);

            // Batch size 2 — should only import 2 out of 5
            int count = cliTranscriptService.syncSource("kompile", 2);
            assertEquals(2, count);
        }

        @Test
        @DisplayName("should skip sessions that fail to import")
        void shouldSkipFailedImports() throws IOException {
            Files.writeString(tempDir.resolve("will-fail.txt"), """
                    ──── Conversation: will-fail ────
                    Started: 2025-01-15 10:00:00
                    Server:  localhost
                    Agent:   default
                    RAG:     anserini

                    ──────────────────────────────────

                    """);  // Empty transcript — no user messages

            when(chatHistoryService.getSession("imported-kompile-will-fail")).thenReturn(Optional.empty());

            // Should not throw, just return 0
            int count = cliTranscriptService.syncSource("kompile", 50);
            assertEquals(0, count);
        }
    }
}
