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

package ai.kompile.cli.main.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.kompile.cli.main.chat.enforcer.JudgementRecord;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure decision/rendering helpers behind live in-session judge control
 * (/judge off|on|judgements|status). These cover the logic without standing up a live
 * REPL: the dispatch gate ({@link EmulatedPassthroughCommand#shouldEnforce}), the status-bar tag
 * ({@link EmulatedPassthroughCommand#enforcerStatusTag}), and the judgement renderer
 * ({@link EmulatedPassthroughCommand#formatJudgementLines}).
 */
class EmulatedPassthroughEnforcerControlTest {

    @Test
    @DisplayName("shouldEnforce: enforces only when configured AND not paused")
    void shouldEnforceReflectsPauseAndConfig() {
        assertTrue(EmulatedPassthroughCommand.shouldEnforce(true, true, false),
                "configured + not paused → enforce");
        assertFalse(EmulatedPassthroughCommand.shouldEnforce(true, true, true),
                "paused → skip the judge even when fully configured");
        assertFalse(EmulatedPassthroughCommand.shouldEnforce(false, true, false),
                "no service → no enforcement");
        assertFalse(EmulatedPassthroughCommand.shouldEnforce(true, false, false),
                "no policy → no enforcement");
    }

    @Test
    @DisplayName("judge status tag: blank when unconfigured, off suffix when disabled")
    void enforcerStatusTagReflectsState() {
        assertEquals("", EmulatedPassthroughCommand.enforcerStatusTag(false, false));
        assertEquals("", EmulatedPassthroughCommand.enforcerStatusTag(false, true),
                "no tag when no enforcer, regardless of the paused flag");
        assertEquals(" · judge", EmulatedPassthroughCommand.enforcerStatusTag(true, false));
        assertEquals(" · judge off", EmulatedPassthroughCommand.enforcerStatusTag(true, true));
    }

    @Test
    @DisplayName("formatJudgementLines: symbols, backend, and violations render")
    void formatJudgementLinesRendersSymbolsAndViolations() {
        JudgementRecord pass = JudgementRecord.builder()
                .timestamp("2026-06-20T14:23:01Z").phase("RESULT").compliant(true)
                .status("ACCEPTED").backend("remote(anthropic/haiku)").build();
        JudgementRecord viol = JudgementRecord.builder()
                .timestamp("2026-06-20T14:23:05Z").phase("JUDGE_TOOL").compliant(false).stop(false)
                .severity("violation").backend("remote(anthropic/haiku)").toolName("bash")
                .violations(List.of("BAN_TOOL: bash")).build();
        JudgementRecord stop = JudgementRecord.builder()
                .timestamp("2026-06-20T14:23:09Z").phase("RESULT").compliant(false).stop(true)
                .status("BLOCKED").backend("remote(anthropic/haiku)").build();

        String joined = String.join("\n",
                EmulatedPassthroughCommand.formatJudgementLines(List.of(pass, viol, stop), 0));

        assertTrue(joined.contains("✓"), "compliant record shows a check");
        assertTrue(joined.contains("ACCEPTED"), "RESULT status shown");
        assertTrue(joined.contains("[remote(anthropic/haiku)]"), "judge backend shown");
        assertTrue(joined.contains("✗"), "non-compliant record shows a cross");
        assertTrue(joined.contains("tool=bash"), "tool name shown for a JUDGE_TOOL record");
        assertTrue(joined.contains("violations: BAN_TOOL: bash"), "violations rendered on their own line");
        assertTrue(joined.contains("■"), "stop record shows a stop glyph");
        assertTrue(joined.contains("BLOCKED"), "blocked status shown");
    }

    @Test
    @DisplayName("formatJudgementLines: limit keeps only the most recent N records")
    void formatJudgementLinesRespectsLimit() {
        JudgementRecord pass = JudgementRecord.builder()
                .timestamp("2026-06-20T14:23:01Z").phase("RESULT").compliant(true)
                .status("ACCEPTED").build();
        JudgementRecord viol = JudgementRecord.builder()
                .timestamp("2026-06-20T14:23:05Z").phase("JUDGE_TOOL").compliant(false)
                .toolName("bash").build();
        JudgementRecord stop = JudgementRecord.builder()
                .timestamp("2026-06-20T14:23:09Z").phase("RESULT").compliant(false).stop(true)
                .status("BLOCKED").build();

        String joined = String.join("\n",
                EmulatedPassthroughCommand.formatJudgementLines(List.of(pass, viol, stop), 2));

        assertFalse(joined.contains("ACCEPTED"), "oldest record dropped by the limit");
        assertTrue(joined.contains("tool=bash"), "second-newest kept");
        assertTrue(joined.contains("BLOCKED"), "newest kept");
    }

    @Test
    @DisplayName("formatJudgementLines: empty/null input is safe")
    void formatJudgementLinesEmptyIsSafe() {
        assertTrue(EmulatedPassthroughCommand.formatJudgementLines(null, 5).isEmpty());
        assertTrue(EmulatedPassthroughCommand.formatJudgementLines(List.of(), 5).isEmpty());
    }

    @Test
    @DisplayName("judgementShortTime: null/garbage are safe; valid ISO → HH:mm:ss")
    void judgementShortTimeHandlesNullAndGarbage() {
        assertEquals("--:--:--", EmulatedPassthroughCommand.judgementShortTime(null));
        assertEquals("--:--:--", EmulatedPassthroughCommand.judgementShortTime(""));
        assertEquals("garbage1", EmulatedPassthroughCommand.judgementShortTime("garbage123"),
                "unparseable long string falls back to its first 8 chars");
        assertTrue(EmulatedPassthroughCommand.judgementShortTime("2026-06-20T14:23:01Z")
                .matches("\\d\\d:\\d\\d:\\d\\d"), "valid ISO renders as HH:mm:ss in any timezone");
    }
}
