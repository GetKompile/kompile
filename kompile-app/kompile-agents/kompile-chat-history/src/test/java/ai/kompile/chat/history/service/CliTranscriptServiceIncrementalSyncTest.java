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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.chat.history.service;

import ai.kompile.chat.history.config.ChatHistoryProperties;
import ai.kompile.chat.history.domain.ChatMessage;
import ai.kompile.chat.history.domain.ChatSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CliTranscriptServiceIncrementalSyncTest {

    @Mock
    private ChatHistoryService chatHistoryService;

    @TempDir
    Path tempDir;

    private CliTranscriptService service;

    @BeforeEach
    void setUp() {
        ChatHistoryProperties properties = new ChatHistoryProperties(null);
        properties.setCliConversationsPath(tempDir.toString());
        service = new CliTranscriptService(properties, chatHistoryService);
        service.init();
    }

    @Test
    void discoversScopedKompileTranscriptAndAppendsOnlyNewTurns() throws Exception {
        Path projectDir = tempDir.resolve("project").toAbsolutePath().normalize();
        Files.createDirectories(projectDir);
        Files.writeString(tempDir.resolve("continued.txt"), """
                ──── Conversation: continued ────
                Started: 2026-08-11 10:00:00
                Server:  localhost
                Agent:   kompile
                RAG:     disabled
                CWD:     %s

                ──────────────────────────────────

                > First question

                First answer

                > Follow-up

                Follow-up answer

                """.formatted(projectDir));

        String importId = "imported-kompile-continued";
        ChatSession existing = ChatSession.builder().sessionId(importId).build();
        when(chatHistoryService.getSession(importId)).thenReturn(Optional.of(existing));
        when(chatHistoryService.getSessionMessages(importId)).thenReturn(List.of(
                message(ChatMessage.MessageRole.USER, "First question"),
                message(ChatMessage.MessageRole.ASSISTANT, "First answer")));

        var pending = service.listNewSessions("kompile", projectDir);
        assertEquals(1, pending.size());
        assertEquals("continued", pending.get(0).sessionId());

        service.importTranscript("continued", "kompile");

        verify(chatHistoryService).addMessage(
                importId, ChatMessage.MessageRole.USER, "Follow-up", null);
        verify(chatHistoryService).addMessage(
                importId, ChatMessage.MessageRole.ASSISTANT, "Follow-up answer", null);
        assertEquals(0, service.listSessions("kompile", tempDir.resolve("other-project")).size());
    }

    private static ChatMessage message(ChatMessage.MessageRole role, String content) {
        return ChatMessage.builder().role(role).content(content).build();
    }
}
