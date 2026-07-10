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

package ai.kompile.cli.main.chat.terminal;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic tests for the L0 {@link ScriptAgentProcess} spawn/lifecycle. Uses a NONE-PTY provider so
 * the tests run without {@code script(1)} and assert the shared spawn concerns: env assembly, stdin
 * round-trip, escalating interrupt, tree signalling and exit-future completion.
 */
@DisabledOnOs(OS.WINDOWS)
class ScriptAgentProcessTest {

    /** A PtyProvider that never wraps — lets us spawn plain bash without a real PTY. */
    private static final PtyProvider NO_PTY = new PtyProvider() {
        @Override public List<String> wrap(List<String> command, PtyDims dims) { return command; }
        @Override public boolean available() { return false; }
    };

    private static Path writeScript(Path dir, String name, String body) throws Exception {
        Path script = dir.resolve(name);
        Files.writeString(script, body, StandardCharsets.UTF_8);
        script.toFile().setExecutable(true);
        return script;
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void spawnsAppliesExtraEnvAndRoundTripsStdin(@TempDir Path tmp) throws Exception {
        // Echoes an env var (proving extraEnv reached the child) then echoes stdin lines.
        Path script = writeScript(tmp, "envcho.sh", """
                #!/bin/bash
                echo "ENV=$KOMPILE_TEST_ENV"
                while IFS= read -r line; do echo "GOT:$line"; done
                """);

        ScriptAgentProcess proc = new ScriptAgentProcess(NO_PTY);
        proc.start(AgentLaunchSpec.builder(List.of("bash", script.toString()), tmp.toString())
                .env(Map.of("KOMPILE_TEST_ENV", "hello"))
                .build());

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.inputStream(), StandardCharsets.UTF_8));
        assertEquals("ENV=hello", reader.readLine(), "extraEnv must reach the subprocess");

        OutputStream stdin = proc.stdin();
        stdin.write("ping\n".getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        assertEquals("GOT:ping", reader.readLine(), "stdin must round-trip to the subprocess");

        assertTrue(proc.isAlive());
        assertTrue(proc.pid() > 0);
        proc.close();
        assertTrue(proc.exitFuture().get(3, TimeUnit.SECONDS) != null);
        assertFalse(proc.isAlive());
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void interruptEscalatesToKillForStubbornAgent(@TempDir Path tmp) throws Exception {
        Path script = writeScript(tmp, "stubborn.sh", """
                #!/bin/bash
                trap '' INT TERM
                echo READY
                while true; do sleep 1; done
                """);

        ScriptAgentProcess proc = new ScriptAgentProcess(NO_PTY);
        proc.start(AgentLaunchSpec.builder(List.of("bash", script.toString()), tmp.toString()).build());

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.inputStream(), StandardCharsets.UTF_8));
        assertEquals("READY", reader.readLine());

        String stoppedBy = proc.interrupt(InterruptEscalation.soft());
        assertEquals("KILL", stoppedBy, "an agent ignoring INT and TERM must be SIGKILLed");
        assertFalse(proc.isAlive());
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void resizeSignalsWinchToRunningAgent(@TempDir Path tmp) throws Exception {
        // Agent that counts SIGWINCH deliveries and prints on each — proves resize() reaches it.
        Path script = writeScript(tmp, "winch.sh", """
                #!/bin/bash
                trap 'echo WINCH' WINCH
                echo READY
                while true; do sleep 0.1; done
                """);

        ScriptAgentProcess proc = new ScriptAgentProcess(NO_PTY);
        proc.start(AgentLaunchSpec.builder(List.of("bash", script.toString()), tmp.toString()).build());

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.inputStream(), StandardCharsets.UTF_8));
        assertEquals("READY", reader.readLine());

        proc.resize(40, 100);
        assertEquals("WINCH", reader.readLine(), "resize() must deliver SIGWINCH to the agent");

        proc.interrupt(InterruptEscalation.hardTree());
        assertFalse(proc.isAlive());
    }
}
