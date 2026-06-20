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

package ai.kompile.cli.main.chat.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic tests of the subprocess interrupt mechanics the enforcer uses to stop a managed agent,
 * and the latency of those interactions. Uses controllable {@code bash} children rather than a real
 * agent CLI, so it runs without claude/codex/etc. installed.
 *
 * <p>These mirror {@code SubprocessAgentRunner.sendSigint()} (the "escape"/SIGINT interrupt sent by
 * {@code interruptForMonitor}) and {@code killProcess()} (the SIGINT→SIGTERM→SIGKILL escalation used
 * by {@code cancel()}). Each test asserts the interrupt is delivered AND prints/bounds the latency.</p>
 */
@DisabledOnOs(OS.WINDOWS)
class SubprocessInterruptLatencyTest {

    private static Path writeScript(Path dir, String name, String body) throws Exception {
        Path script = dir.resolve(name);
        Files.writeString(script, body, StandardCharsets.UTF_8);
        script.toFile().setExecutable(true);
        return script;
    }

    /** Read lines until the child prints READY, so the trap/handler is installed before we interrupt. */
    private static void awaitReady(Process p) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        String line;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && (line = reader.readLine()) != null) {
            if (line.contains("READY")) {
                return;
            }
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void sigintInterruptsAgentAndLatencyIsLow(@TempDir Path tmp) throws Exception {
        Path marker = tmp.resolve("sigint.marker");
        // A cooperative "agent": traps SIGINT, records it, and exits gracefully — exactly what the
        // enforcer expects when it sends `kill -INT` to wind the agent down. The idle wait runs in
        // the background with `wait` so a SIGINT delivered to the bash PID is handled immediately
        // (a foreground `sleep` would defer the trap until it returned).
        Path script = writeScript(tmp, "agent-sigint.sh", """
                #!/bin/bash
                trap 'echo SIGINT_RECEIVED >> "$1"; kill $BG 2>/dev/null; exit 0' INT
                echo READY
                sleep 30 & BG=$!
                wait $BG
                """);

        Process p = new ProcessBuilder("bash", script.toString(), marker.toString())
                .redirectErrorStream(true).start();
        awaitReady(p);

        // Deliver the interrupt the same way SubprocessAgentRunner.sendSigint() does.
        long t0 = System.nanoTime();
        new ProcessBuilder("kill", "-INT", String.valueOf(p.pid())).start().waitFor();
        boolean exited = p.waitFor(5, TimeUnit.SECONDS);
        long latencyMs = (System.nanoTime() - t0) / 1_000_000L;

        System.out.println("[latency] SIGINT interrupt → agent exit: " + latencyMs + " ms");
        assertTrue(exited, "agent must exit after SIGINT");
        assertEquals(0, p.exitValue(), "cooperative agent exits 0 on SIGINT");
        assertTrue(Files.exists(marker) && Files.readString(marker).contains("SIGINT_RECEIVED"),
                "agent must have received the SIGINT");
        assertTrue(latencyMs < 3000, "SIGINT interrupt latency should be well under 3s, was " + latencyMs + "ms");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void forceKillEscalationStopsStubbornAgent(@TempDir Path tmp) throws Exception {
        // A stubborn "agent" that ignores SIGINT and SIGTERM — only SIGKILL stops it.
        Path script = writeScript(tmp, "agent-stubborn.sh", """
                #!/bin/bash
                trap '' INT TERM
                echo READY
                while true; do sleep 1; done
                """);

        Process p = new ProcessBuilder("bash", script.toString()).redirectErrorStream(true).start();
        awaitReady(p);

        // Replicate SubprocessAgentRunner.killProcess(): INT, 500ms grace, TERM, 300ms grace, KILL.
        long t0 = System.nanoTime();
        long pid = p.pid();
        new ProcessBuilder("kill", "-INT", String.valueOf(pid)).start().waitFor();
        if (p.isAlive()) {
            Thread.sleep(500);
            new ProcessBuilder("kill", "-TERM", String.valueOf(pid)).start().waitFor();
        }
        if (p.isAlive()) {
            Thread.sleep(300);
            new ProcessBuilder("kill", "-9", String.valueOf(pid)).start().waitFor();
        }
        boolean exited = p.waitFor(5, TimeUnit.SECONDS);
        long latencyMs = (System.nanoTime() - t0) / 1_000_000L;

        System.out.println("[latency] force-kill escalation (INT→TERM→KILL) → exit: " + latencyMs + " ms");
        assertTrue(exited, "stubborn agent must be force-killed");
        assertFalse(p.isAlive(), "process must be dead after escalation");
        // INT(500ms) + TERM(300ms) grace must elapse before KILL, so >= ~800ms; bound the upper end too.
        assertTrue(latencyMs >= 700, "escalation should honor the grace windows, was " + latencyMs + "ms");
        assertTrue(latencyMs < 4000, "escalation should complete within 4s, was " + latencyMs + "ms");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void stdinByteDeliveryRoundTripLatency(@TempDir Path tmp) throws Exception {
        // Validates the stdin write path (the enforcer writes ETX/0x03 to stdin on Windows and
        // forwards raw keystrokes in mirror mode). Here we measure a write→echo round trip.
        Path script = writeScript(tmp, "agent-echo.sh", """
                #!/bin/bash
                echo READY
                while IFS= read -r line; do
                  echo "GOT:$line"
                done
                """);

        Process p = new ProcessBuilder("bash", script.toString()).redirectErrorStream(true).start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        // consume READY
        String first = reader.readLine();
        assertTrue(first != null && first.contains("READY"), "echo agent must signal READY");

        OutputStream stdin = p.getOutputStream();
        long t0 = System.nanoTime();
        stdin.write("ping\n".getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        String echoed = reader.readLine();
        long latencyMs = (System.nanoTime() - t0) / 1_000_000L;

        System.out.println("[latency] stdin write → echo round trip: " + latencyMs + " ms");
        assertEquals("GOT:ping", echoed, "stdin bytes must reach the subprocess and echo back");
        assertTrue(latencyMs < 2000, "stdin round-trip should be fast, was " + latencyMs + "ms");

        p.destroyForcibly();
        p.waitFor(3, TimeUnit.SECONDS);
    }

    // ── Escalating soft interrupt (the fix: SIGINT → SIGTERM → SIGKILL) ──────

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void softInterruptStopsCooperativeAgentWithSigint(@TempDir Path tmp) throws Exception {
        // Cooperative agent (claude-like): honors SIGINT and exits immediately. Uses `wait` (a
        // bash builtin that is interruptible by signals) so a SIGINT delivered to the bash PID is
        // handled at once — a foreground `sleep` would defer it.
        Path script = writeScript(tmp, "coop.sh", """
                #!/bin/bash
                trap 'exit 0' INT
                echo READY
                sleep 30 & wait $!
                """);
        Process p = new ProcessBuilder("bash", script.toString()).redirectErrorStream(true).start();
        awaitReady(p);

        long t0 = System.nanoTime();
        String stoppedBy = SubprocessAgentRunner.escalatingUnixInterrupt(p, 300, 200);
        long latencyMs = (System.nanoTime() - t0) / 1_000_000L;

        System.out.println("[soft-interrupt] cooperative agent stopped by SIG" + stoppedBy + " in " + latencyMs + " ms");
        assertEquals("INT", stoppedBy, "an agent that honors SIGINT stops at the SIGINT stage");
        assertFalse(p.isAlive());
        assertTrue(latencyMs < 300, "a SIGINT-honoring agent should stop before the grace window elapses");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void softInterruptEscalatesToSigtermForSigintIgnoringAgent(@TempDir Path tmp) throws Exception {
        // Simulates opencode: IGNORES SIGINT, stops on SIGTERM. Before the fix, the monitor's
        // SIGINT-only interrupt would never stop this agent.
        Path script = writeScript(tmp, "ignore-int.sh", """
                #!/bin/bash
                trap '' INT
                echo READY
                sleep 30
                """);
        Process p = new ProcessBuilder("bash", script.toString()).redirectErrorStream(true).start();
        awaitReady(p);

        long t0 = System.nanoTime();
        String stoppedBy = SubprocessAgentRunner.escalatingUnixInterrupt(p, 300, 200);
        long latencyMs = (System.nanoTime() - t0) / 1_000_000L;

        System.out.println("[soft-interrupt] SIGINT-ignoring agent (opencode-like) stopped by SIG"
                + stoppedBy + " in " + latencyMs + " ms");
        assertEquals("TERM", stoppedBy, "a SIGINT-ignoring agent must be escalated to SIGTERM");
        assertFalse(p.isAlive(), "the escalating interrupt must actually stop the agent");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void softInterruptEscalatesToKillForStubbornAgent(@TempDir Path tmp) throws Exception {
        // Agent that ignores BOTH SIGINT and SIGTERM — only SIGKILL stops it.
        Path script = writeScript(tmp, "stubborn.sh", """
                #!/bin/bash
                trap '' INT TERM
                echo READY
                sleep 30
                """);
        Process p = new ProcessBuilder("bash", script.toString()).redirectErrorStream(true).start();
        awaitReady(p);

        long t0 = System.nanoTime();
        String stoppedBy = SubprocessAgentRunner.escalatingUnixInterrupt(p, 300, 200);
        long latencyMs = (System.nanoTime() - t0) / 1_000_000L;

        System.out.println("[soft-interrupt] stubborn agent stopped by SIG" + stoppedBy + " in " + latencyMs + " ms");
        assertEquals("KILL", stoppedBy, "an agent that ignores INT and TERM must be SIGKILLed");
        assertFalse(p.isAlive());
    }
}
