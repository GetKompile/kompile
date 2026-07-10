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

package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.main.chat.harness.JudgeBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic test that the (formerly dead — 0 instantiations) {@link EnforcerJsonlTailer} actually
 * parses native-session JSONL and fires its violation handler through the judge (WP9). Drives the
 * package-private {@code processLine} directly, with a stubbed judge, so no LLM backend or real
 * {@code ~/.claude} session file is needed.
 */
class EnforcerJsonlTailerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A JudgeBackend that never touches an LLM. */
    private static final JudgeBackend STUB_BACKEND = new JudgeBackend() {
        @Override public String generate(String userPrompt, String systemPrompt) { return "{}"; }
        @Override public boolean isAvailable() { return true; }
    };

    /** A judge that blocks any tool call whose name contains "rm" and stops on "SECRET" text. */
    private static final class StubJudge extends EnforcerJudge {
        StubJudge() {
            super(STUB_BACKEND, MAPPER);
        }

        @Override
        public EnforcerToolCallDecision evaluateToolCall(String toolName, String toolInput,
                                                         EnforcerPolicy policy,
                                                         EnforcerConversationContext context) {
            if (toolName != null && toolName.contains("rm")) {
                return EnforcerToolCallDecision.block("destructive tool blocked: " + toolName);
            }
            return EnforcerToolCallDecision.allow("ok");
        }

        @Override
        public EnforcerDecision evaluatePartialOutput(String userPrompt, String partialOutput,
                                                      EnforcerPolicy policy,
                                                      EnforcerConversationContext context) {
            if (partialOutput != null && partialOutput.contains("SECRET")) {
                return EnforcerDecision.stop(List.of("leaked secret"), "Do not reveal secrets.");
            }
            return EnforcerDecision.pass("ok");
        }
    }

    private record Violation(String reason, String correction, boolean toolCall) {}

    private static EnforcerJsonlTailer tailer(Path tmp, List<Violation> sink) {
        EnforcerConversationWindow window = new EnforcerConversationWindow(tmp.resolve("ctx.json"), MAPPER);
        EnforcerPolicy policy = new EnforcerPolicy("BAN: rm -rf", 3, false);
        return new EnforcerJsonlTailer("claude", tmp, Instant.now(), MAPPER, window,
                new StubJudge(), policy,
                (reason, correction, toolCall) -> sink.add(new Violation(reason, correction, toolCall)));
    }

    @Test
    void firesViolationOnBlockedClaudeToolCall(@TempDir Path tmp) {
        List<Violation> violations = new ArrayList<>();
        EnforcerJsonlTailer t = tailer(tmp, violations);

        t.processLine("{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"name\":\"rm_tool\",\"input\":{\"path\":\"/\"}}]}}");

        assertEquals(1, violations.size(), "a blocked tool call must fire exactly one violation");
        assertTrue(violations.get(0).toolCall(), "flagged as a tool-call violation");
        assertTrue(violations.get(0).reason().contains("destructive tool blocked"),
                "carries the judge's block reason: " + violations.get(0).reason());
    }

    @Test
    void allowsBenignClaudeToolCall(@TempDir Path tmp) {
        List<Violation> violations = new ArrayList<>();
        EnforcerJsonlTailer t = tailer(tmp, violations);

        t.processLine("{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"name\":\"read_file\",\"input\":{\"path\":\"pom.xml\"}}]}}");

        assertTrue(violations.isEmpty(), "a benign tool call must not fire a violation");
    }

    @Test
    void evaluatesAssistantTextInRealClaudeArrayFormat(@TempDir Path tmp) {
        // Matches the on-disk claude session record shape verified against a real ~/.claude session:
        //   {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":...}]}}
        // The partial-output judge only fires past MIN_TEXT_EVALUATION_CHARS (1200), so pad the text.
        List<Violation> violations = new ArrayList<>();
        EnforcerJsonlTailer t = tailer(tmp, violations);

        String longText = "here is my plan. ".repeat(80) + "the SECRET is 1234"; // > 1200 chars, contains SECRET
        String line = "{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"" + longText + "\"}]}}";
        t.processLine(line);

        assertEquals(1, violations.size(), "long assistant text (real array format) must reach the partial judge");
        assertFalse(violations.get(0).toolCall(), "flagged as an output (not tool-call) violation");
        assertFalse(violations.get(0).reason().isBlank(), "carries the judge's violation summary");
    }

    @Test
    void extractsUserStringContentWithoutViolation(@TempDir Path tmp) {
        // Real claude user records carry message.content as a plain STRING (verified on disk).
        List<Violation> violations = new ArrayList<>();
        EnforcerJsonlTailer t = tailer(tmp, violations);
        t.processLine("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":\"Reply with exactly: DONE\"}}");
        assertTrue(violations.isEmpty(), "a user turn is context only — no violation");
    }

    @Test
    void ignoresUserLinesAndPlainText(@TempDir Path tmp) {
        List<Violation> violations = new ArrayList<>();
        EnforcerJsonlTailer t = tailer(tmp, violations);

        t.processLine("{\"type\":\"user\",\"message\":{\"content\":\"delete everything with rm\"}}");
        t.processLine("{\"type\":\"assistant\",\"message\":{\"content\":\"Sure, here is a plan.\"}}");

        assertFalse(violations.stream().anyMatch(Violation::toolCall),
                "user text and short assistant prose must not trip the tool gate");
    }
}
