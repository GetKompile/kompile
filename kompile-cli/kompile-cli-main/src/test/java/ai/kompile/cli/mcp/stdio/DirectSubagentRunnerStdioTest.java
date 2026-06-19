/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.mcp.stdio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DirectSubagentRunnerStdio} verifying that subagents are launched
 * as provider-agnostic managed subprocesses: the prompt is delivered over stdin,
 * the argument vector contains only the resolved binary, output is captured from
 * stdout/stderr, and cancellation destroys the managed process tree.
 */
class DirectSubagentRunnerStdioTest {

    @TempDir
    Path tempDir;

    private DirectSubagentRunnerStdio runner;

    @BeforeEach
    void setUp() {
        runner = new DirectSubagentRunnerStdio(tempDir);
    }

    private Path writeScript(String content) throws Exception {
        Path script = tempDir.resolve("mock-agent");
        Files.writeString(script, "#!/bin/sh\n" + content + "\n");
        if (!script.toFile().setExecutable(true)) {
            throw new IllegalStateException("Could not make " + script + " executable");
        }
        return script;
    }

    @Test
    void buildAgentCommandContainsOnlyBinary() {
        List<String> cmd = runner.buildAgentCommand("/usr/bin/bash", "qwen");
        assertEquals(List.of("/usr/bin/bash"), cmd);
    }

    @Test
    void commandArgvExcludesPromptModeFlagsAndPromptText() throws Exception {
        String prompt = "hello-provider-agnostic-prompt";
        // Only echo the argument vector; do not read stdin so the output is purely the argv line.
        Path script = writeScript("echo \"argv:$*\"");

        String result = runner.executeSubagentProcess("bash", prompt, script.toString(), prompt, 0);

        assertNotNull(result);
        assertTrue(result.contains("argv:"), "argv marker should be present");
        assertFalse(result.contains(prompt), "prompt text should not appear in argv/output");
        for (String banned : List.of("-p", "exec", "run", "--continue", "--resume", "--session", "--fork")) {
            assertFalse(result.contains(banned),
                    "argv/output should not contain provider prompt-mode flag '" + banned + "'");
        }
    }

    @Test
    void promptIsSentThroughStdin() throws Exception {
        String prompt = "send this via stdin\nsecond line";
        Path script = writeScript("cat; echo EOF");

        String result = runner.executeSubagentProcess("bash", prompt, script.toString(), prompt, 0);

        assertTrue(result.contains(prompt), "prompt should appear in captured stdout");
        assertTrue(result.contains("EOF"), "post-prompt marker should appear");
    }

    @Test
    void stdoutAndStderrAreCaptured() throws Exception {
        Path script = writeScript("echo out1; echo err1 >&2; echo out2; echo err2 >&2");

        String result = runner.executeSubagentProcess("bash", "ignored", script.toString(), "ignored", 0);

        assertTrue(result.contains("out1"), "stdout line 1 should be captured");
        assertTrue(result.contains("err1"), "stderr line 1 should be captured");
        assertTrue(result.contains("out2"), "stdout line 2 should be captured");
        assertTrue(result.contains("err2"), "stderr line 2 should be captured");
    }

    @Test
    void cancellationKillsManagedSubprocess() throws Exception {
        Path pidFile = tempDir.resolve("subagent.pid");
        Path script = writeScript(
                "echo $$ > " + pidFile + "; sleep 30");

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                return runner.executeSubagentProcess("bash", "sleep", script.toString(), "sleep", 0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        for (int i = 0; i < 50 && !Files.exists(pidFile); i++) {
            Thread.sleep(50);
        }
        assertTrue(Files.exists(pidFile), "subagent should have written its pid");
        long pid = Long.parseLong(Files.readString(pidFile).trim());
        assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "subagent process should be alive before cancellation");

        runner.cancel();
        assertThrows(Exception.class, () -> future.get(10, TimeUnit.SECONDS),
                "cancelled subagent should complete exceptionally");

        for (int i = 0; i < 50 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false); i++) {
            Thread.sleep(50);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "managed subprocess should be killed after cancellation");
    }
}
