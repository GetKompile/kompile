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
package ai.kompile.cli.main.chat;

import org.jline.reader.Candidate;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ChatCompleter")
class ChatCompleterTest {

    /**
     * Minimal ParsedLine stub for testing completion.
     */
    private static ParsedLine parsedLine(String line, int cursor) {
        return new ParsedLine() {
            @Override public String word() { return ""; }
            @Override public int wordCursor() { return 0; }
            @Override public int wordIndex() { return 0; }
            @Override public List<String> words() { return List.of(); }
            @Override public String line() { return line; }
            @Override public int cursor() { return cursor; }
        };
    }

    @Nested
    @DisplayName("Skill completion")
    class SkillCompletion {

        @Test
        void completesSkillNames() {
            Set<String> skills = Set.of("commit", "review", "fix");
            ChatCompleter completer = new ChatCompleter(List::of, () -> skills);

            List<Candidate> candidates = new ArrayList<>();
            completer.complete(null, parsedLine("/co", 3), candidates);

            // Should include /commit (skill) and /config, /conversations (slash commands)
            List<String> values = candidates.stream()
                    .map(Candidate::value)
                    .toList();
            assertTrue(values.contains("/commit"), "Should complete /commit skill");
        }

        @Test
        void completesSkillWithSlashPrefix() {
            Set<String> skills = Set.of("review", "fix");
            ChatCompleter completer = new ChatCompleter(List::of, () -> skills);

            List<Candidate> candidates = new ArrayList<>();
            completer.complete(null, parsedLine("/rev", 4), candidates);

            List<String> values = candidates.stream()
                    .map(Candidate::value)
                    .toList();
            assertTrue(values.contains("/review"));
        }

        @Test
        void completesSkillsCommand() {
            ChatCompleter completer = new ChatCompleter(List::of, Set::of);

            List<Candidate> candidates = new ArrayList<>();
            completer.complete(null, parsedLine("/ski", 4), candidates);

            List<String> values = candidates.stream()
                    .map(Candidate::value)
                    .toList();
            assertTrue(values.contains("/skills"));
        }

        @Test
        void skillsAndSlashCommandsBothAppear() {
            Set<String> skills = Set.of("stats-custom");
            ChatCompleter completer = new ChatCompleter(List::of, () -> skills);

            List<Candidate> candidates = new ArrayList<>();
            completer.complete(null, parsedLine("/sta", 4), candidates);

            List<String> values = candidates.stream()
                    .map(Candidate::value)
                    .toList();
            assertTrue(values.contains("/stats"), "Slash command /stats should appear");
            assertTrue(values.contains("/stats-custom"), "Custom skill /stats-custom should appear");
        }

        @Test
        void noSkillsSupplierStillWorks() {
            // Backward-compatible constructor
            ChatCompleter completer = new ChatCompleter(List::of);

            List<Candidate> candidates = new ArrayList<>();
            completer.complete(null, parsedLine("/he", 3), candidates);

            List<String> values = candidates.stream()
                    .map(Candidate::value)
                    .toList();
            assertTrue(values.contains("/help"));
        }

        @Test
        void nullSkillSetDoesNotThrow() {
            ChatCompleter completer = new ChatCompleter(List::of, () -> null);

            List<Candidate> candidates = new ArrayList<>();
            assertDoesNotThrow(() ->
                    completer.complete(null, parsedLine("/co", 3), candidates));
        }

        @Test
        void emptyInputDoesNotComplete() {
            Set<String> skills = Set.of("commit");
            ChatCompleter completer = new ChatCompleter(List::of, () -> skills);

            List<Candidate> candidates = new ArrayList<>();
            completer.complete(null, parsedLine("hello", 5), candidates);

            assertTrue(candidates.isEmpty(), "Non-slash input should not complete");
        }

        @Test
        void skillCandidatesHaveSkillGroup() {
            Set<String> skills = Set.of("commit");
            ChatCompleter completer = new ChatCompleter(List::of, () -> skills);

            List<Candidate> candidates = new ArrayList<>();
            completer.complete(null, parsedLine("/com", 4), candidates);

            Candidate skillCandidate = candidates.stream()
                    .filter(c -> "/commit".equals(c.value()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(skillCandidate, "Should find /commit candidate");
            assertEquals("skill", skillCandidate.group());
        }
    }
}
