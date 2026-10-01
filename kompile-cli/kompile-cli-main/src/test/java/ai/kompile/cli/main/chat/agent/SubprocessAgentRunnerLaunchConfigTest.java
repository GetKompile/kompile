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

package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.mcp.McpToolInjection;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Claude Code launch reads its Kompile server from a config file of its own, and refuses
 * to start when that file is gone. Launch configs live under the user's home, so the class
 * runs in a temporary one.
 */
@TemporaryUserHome
class SubprocessAgentRunnerLaunchConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void aLaunchConfigDeletedDuringARunIsWrittenAgainBeforeTheNextLaunch() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path workDir = Files.createDirectories(tempDir.resolve("work"));
        Path binary = Files.createFile(tempDir.resolve("kompile-cli")).toAbsolutePath();
        assertTrue(binary.toFile().setExecutable(true));
        TerminalRenderer renderer = new TerminalRenderer(true);
        // A known MCP port: the runner resolves its URL without probing for a running app.
        SubprocessAgentRunner runner = new SubprocessAgentRunner("claude", workDir.toString(), true, true,
                "", 8123, null, renderer, new AsciiRenderer(renderer, 100));
        List<String> output = new ArrayList<>();
        runner.setOutputConsumer(output::add);
        Path rewritten = null;
        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            runner.injectMcpTools();
            Path first = runner.injectedSettingsFile;
            assertNotNull(first, String.join("\n", output));
            assertTrue(McpToolInjection.isLaunchConfig(first), first.toString());
            assertTrue(Files.exists(first));
            assertEquals(List.of("--mcp-config=" + first), runner.managedCommandPrefixArguments);

            // A file that is still there is kept.
            runner.reinjectMissingLaunchConfig();
            assertEquals(first, runner.injectedSettingsFile);

            // A temp or home cleaner deleted it while the run sat idle.
            Files.delete(first);
            runner.reinjectMissingLaunchConfig();

            rewritten = runner.injectedSettingsFile;
            assertNotNull(rewritten);
            assertNotEquals(first, rewritten);
            assertTrue(Files.exists(rewritten));
            assertEquals(List.of("--mcp-config=" + rewritten), runner.managedCommandPrefixArguments);
        } finally {
            runner.cleanup();
            if (previousBinary == null) {
                System.clearProperty("kompile.cli.binary");
            } else {
                System.setProperty("kompile.cli.binary", previousBinary);
            }
        }
        assertFalse(Files.exists(rewritten), "cleanup deletes the launch config it wrote");
    }
}
