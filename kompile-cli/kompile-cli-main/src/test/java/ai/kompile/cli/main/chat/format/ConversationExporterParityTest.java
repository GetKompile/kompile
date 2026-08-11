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
package ai.kompile.cli.main.chat.format;

import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.adapters.PiAdapter;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ConversationExporterParityTest {

    @TempDir
    Path tempDir;

    @Test
    void canonicalResumeTranscriptIsIdenticalForEveryNativeExporter() {
        var rawBlocks = JsonNodeFactory.instance.arrayNode();
        rawBlocks.addObject().put("type", "tool_result").put("content", "result");
        List<ChatHistory.Turn> source = List.of(
                new ChatHistory.Turn("assistant", "assistant-first", null),
                new ChatHistory.Turn("user", "request", null),
                new ChatHistory.Turn("system", "system context", null),
                new ChatHistory.Turn("tool", "tool output", rawBlocks),
                new ChatHistory.Turn("assistant", "answer", null),
                new ChatHistory.Turn("user", "   ", null));

        List<ChatHistory.Turn> canonical = ConversationExporter.canonicalResumeTurns(source);

        assertEquals(List.of("assistant", "user", "user", "user", "assistant"),
                canonical.stream().map(ChatHistory.Turn::role).toList());
        assertEquals(List.of("assistant-first", "request", "system context", "tool output", "answer"),
                canonical.stream().map(ChatHistory.Turn::content).toList());
        assertSame(rawBlocks, canonical.get(3).rawContentBlocks());
    }

    @Test
    void piExportUsesVersionThreeJsonlAndRoundTripsThroughPiAdapter() throws Exception {
        String previousHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            ConversationExporter.ExportResult result = ConversationExporter.exportToAgent(
                    List.of(
                            new ChatHistory.Turn("user", "hello pi", null),
                            new ChatHistory.Turn("assistant", "hello from pi", null)),
                    "pi", "pi-roundtrip", "claude-code", tempDir);

            assertEquals("pi --session pi-roundtrip", result.getResumeCommand());
            List<ChatTurn> turns = PiAdapter.parseJsonl(result.getSessionPath());
            assertEquals(List.of("user", "assistant"), turns.stream().map(ChatTurn::role).toList());
            assertEquals(List.of("hello pi", "hello from pi"), turns.stream().map(ChatTurn::content).toList());
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
        }
    }
}
