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

package ai.kompile.cli.common.chat.sources;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KompileTranscriptFormatTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsMultilineAndMultiParagraphTurns() throws Exception {
        Path transcript = tempDir.resolve("session.txt");
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(
                transcript, StandardCharsets.UTF_8))) {
            writer.println("──── Conversation: session ────");
            writer.println("Started: 2026-08-11 10:00:00");
            writer.println("Agent:   kompile");
            writer.println("CWD:     " + tempDir);
            writer.println();
            writer.println("──────────────────────────────────");
            writer.println();
            KompileTranscriptFormat.writeTurn(writer, "user", "first line\n\nthird line");
            KompileTranscriptFormat.writeTurn(writer, "assistant", "first paragraph\n\nsecond paragraph");
        }

        List<ChatTurn> turns = KompileTranscriptFormat.readTurns(transcript);

        assertEquals(2, turns.size());
        assertEquals("first line\n\nthird line", turns.get(0).content());
        assertEquals("first paragraph\n\nsecond paragraph", turns.get(1).content());
        assertEquals(2, KompileTranscriptFormat.countTurns(transcript));
        assertEquals(tempDir.toAbsolutePath().normalize(),
                KompileTranscriptFormat.resolveWorkingDirectory(transcript).orElseThrow());
    }

    @Test
    void parsesLegacyMultilineTurnsAndAssistantParagraphs() throws Exception {
        Path transcript = tempDir.resolve("legacy.txt");
        Files.writeString(transcript, """
                ──── Conversation: legacy ────
                Started: 2026-08-11 10:00:00
                Agent:   kompile

                ──────────────────────────────────

                > first user line
                second user line

                first assistant paragraph

                second assistant paragraph

                > next question

                final answer

                """, StandardCharsets.UTF_8);

        List<ChatTurn> turns = KompileTranscriptFormat.readTurns(transcript);

        assertEquals(4, turns.size());
        assertEquals("first user line\nsecond user line", turns.get(0).content());
        assertEquals("first assistant paragraph\n\nsecond assistant paragraph", turns.get(1).content());
        assertEquals("next question", turns.get(2).content());
        assertEquals("final answer", turns.get(3).content());
    }

    @Test
    void ignoresOperationalMetadataBetweenTurns() throws Exception {
        Path transcript = tempDir.resolve("metadata.txt");
        Files.writeString(transcript, """
                ──── Conversation: metadata ────
                Started: 2026-08-11 10:00:00
                Agent:   claude
                CWD:     /tmp/project

                ──────────────────────────────────

                > hello

                [agent:claude]
                < hi there

                  [completed in 15ms]
                [tool:read] ok (2ms)
                [harvested:native-session]

                """, StandardCharsets.UTF_8);

        List<ChatTurn> turns = KompileTranscriptFormat.readTurns(transcript);

        assertEquals(List.of(
                new ChatTurn("user", "hello"),
                new ChatTurn("assistant", "hi there")), turns);
    }

    @Test
    void usesWorkingDirectoryRecordedWhenLegacyTranscriptIsResumed() throws Exception {
        Path resumedProject = tempDir.resolve("resumed-project");
        Path transcript = tempDir.resolve("resumed.txt");
        Files.writeString(transcript, """
                ──── Conversation: resumed ────
                Started: 2026-08-11 10:00:00
                Agent:   kompile

                ──────────────────────────────────

                > original question

                CWD: this is assistant content, not metadata

                [resumed 2026-08-11 11:00:00]
                CWD:     %s

                > follow-up

                answer

                """.formatted(resumedProject), StandardCharsets.UTF_8);

        assertEquals(resumedProject.toAbsolutePath().normalize(),
                KompileTranscriptFormat.resolveWorkingDirectory(transcript).orElseThrow());
    }
}
