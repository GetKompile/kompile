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
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.activity;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentActivityRendererTest {

    @Test
    void rendersCorrelatedAgentProcessAndRedactedToolSummaryWithinTerminalWidth() {
        Instant now = Instant.parse("2026-09-02T12:00:00Z");
        AgentActivitySnapshot.ToolActivity call = new AgentActivitySnapshot.ToolActivity(
                "call-1", "webfetch",
                "api_key=super-secret Authorization: Bearer abc.def.ghi",
                now.minusSeconds(4), false, 23L);
        AgentActivitySnapshot.ProcessActivity process =
                new AgentActivitySnapshot.ProcessActivity(
                        "coord-session", "proc-001", "codex", "architect", "command",
                        "mvn test", "Run focused dashboard tests", 123L, "RUNNING",
                        now.minusSeconds(40), null, null, "/tmp/output.log",
                        Duration.ofSeconds(40), true, true);
        AgentActivitySnapshot.AgentActivity agent =
                new AgentActivitySnapshot.AgentActivity(
                        "coord-session", "tool-session", "codex", "architect", "parent", "",
                        0, "Implement the live project activity dashboard", 123L,
                        now.minusSeconds(90), now.minusSeconds(2), true, true,
                        List.of(process), List.of(call), 0);
        AgentActivitySnapshot snapshot = new AgentActivitySnapshot(
                now, Duration.ofMinutes(5), List.of(agent), List.of());
        AgentActivityRenderer renderer = new AgentActivityRenderer();

        String rendered = renderer.render(snapshot, "", "tool-session", 72);

        assertTrue(rendered.contains("YOU"));
        assertTrue(rendered.contains("codex/architect"));
        assertTrue(rendered.contains("coord-se/proc-001"));
        assertTrue(rendered.contains("<redacted>"));
        assertFalse(rendered.contains("super-secret"));
        assertFalse(rendered.contains("abc.def.ghi"));
        assertTrue(rendered.lines().allMatch(line -> line.length() <= 72), rendered);
        assertTrue(renderer.compactStatus(snapshot).contains("1 agent"));
        assertTrue(renderer.compactStatus(snapshot).contains("1 running"));
        assertTrue(renderer.compactStatus(snapshot).contains("1 recent call"));
    }

    @Test
    void filterReportsNoMatchWithoutDroppingAvailableIdentities() {
        Instant now = Instant.parse("2026-09-02T12:00:00Z");
        AgentActivitySnapshot.AgentActivity agent =
                new AgentActivitySnapshot.AgentActivity(
                        "coord-session", "tool-session", "claude", "reviewer", "parent", "",
                        0, "Review dashboard", 123L, now, now, true, true,
                        List.of(), List.of(), 0);
        AgentActivitySnapshot snapshot = new AgentActivitySnapshot(
                now, Duration.ofMinutes(5), List.of(agent), List.of());

        String rendered = new AgentActivityRenderer().render(snapshot, "missing", "", 80);

        assertTrue(rendered.contains("No project agent matches \"missing\""));
        assertTrue(rendered.contains("coord-se=claude/reviewer"));
    }

    @Test
    void declaredRunningProcessWithDeadPidIsExplicitlyUnverified() {
        Instant now = Instant.parse("2026-09-02T12:00:00Z");
        AgentActivitySnapshot.ProcessActivity process =
                new AgentActivitySnapshot.ProcessActivity(
                        "orphan-session", "proc-009", "codex", "coder", "command",
                        "mvn test", "Old build record", 999_999L, "RUNNING",
                        now.minusSeconds(30), null, null, "/tmp/output.log",
                        Duration.ofSeconds(30), true, false);
        AgentActivitySnapshot.AgentActivity orphan =
                new AgentActivitySnapshot.AgentActivity(
                        "orphan-session", "", "codex", "coder", "orphan", "",
                        0, "Process owner is not registered", 0L,
                        now.minusSeconds(30), null, false, false,
                        List.of(process), List.of(), 0);
        AgentActivitySnapshot snapshot = new AgentActivitySnapshot(
                now, Duration.ofMinutes(5), List.of(orphan), List.of());
        AgentActivityRenderer renderer = new AgentActivityRenderer();

        String rendered = renderer.render(snapshot, "", "", 100);

        assertTrue(rendered.contains("UNVERIFIED (declared RUNNING)"));
        assertTrue(renderer.compactStatus(snapshot).contains("0 running"));
        assertTrue(renderer.compactStatus(snapshot).contains("issue"));
    }
}
