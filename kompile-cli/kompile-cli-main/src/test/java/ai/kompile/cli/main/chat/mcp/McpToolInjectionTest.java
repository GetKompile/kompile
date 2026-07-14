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
package ai.kompile.cli.main.chat.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolInjectionTest {

    @TempDir
    Path tempDir;

    @Test
    void codexCommandLineOverridesDoNotMutateGlobalConfig() throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path home = tempDir.resolve("isolated-home");
        Path workDir = tempDir.resolve("isolated-work");
        Path binary = createExecutable("kompile cli");
        Files.createDirectories(home);
        Files.createDirectories(workDir);

        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("kompile.cli.binary", binary.toString());

            List<String> overrides = McpToolInjection.codexCommandLineOverrides(workDir);
            String escapedWorkDir = workDir.toAbsolutePath().toString()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"");

            assertEquals(List.of(
                    "-c",
                    "mcp_servers.kompile.command=\"" + binary + "\"",
                    "-c",
                    "mcp_servers.kompile.args=[\"mcp-stdio\", \"--work-dir\", \""
                            + escapedWorkDir + "\"]"),
                    overrides);
            assertFalse(Files.exists(home.resolve(".codex").resolve("config.toml")));
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
            if (previousBinary == null) {
                System.clearProperty("kompile.cli.binary");
            } else {
                System.setProperty("kompile.cli.binary", previousBinary);
            }
        }
    }

    @Test
    void codexInjectionUsesStdioEvenWhenSseUrlIsAvailable() throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path home = tempDir.resolve("home");
        Path workDir = tempDir.resolve("work");
        Path binary = createExecutable("kompile-cli");
        Files.createDirectories(home);
        Files.createDirectories(workDir);

        Path configFile = home.resolve(".codex").resolve("config.toml");
        try {
            System.setProperty("user.home", home.toString());
            System.setProperty("kompile.cli.binary", binary.toString());

            Path written = McpToolInjection.injectTools(
                    workDir, "codex", "http://localhost:8080/mcp/sse");

            assertTrue(Files.exists(written));
            String config = Files.readString(configFile);
            assertTrue(config.contains("[mcp_servers.kompile]"));
            assertTrue(config.contains("command = \"" + binary + "\""));
            assertTrue(config.contains("\"mcp-stdio\""));
            assertFalse(config.contains("url = "));
            assertFalse(config.contains("/mcp/sse"));
        } finally {
            McpToolInjection.removeTools(configFile);
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
            if (previousBinary == null) {
                System.clearProperty("kompile.cli.binary");
            } else {
                System.setProperty("kompile.cli.binary", previousBinary);
            }
        }
    }

    @Test
    void staleAbsoluteBinaryOverrideFallsBackToRunnableLauncher() {
        String previousBinary = System.getProperty("kompile.cli.binary");
        Path staleBinary = tempDir.resolve("missing-kompile-cli").toAbsolutePath();
        try {
            System.setProperty("kompile.cli.binary", staleBinary.toString());

            McpToolInjectionSupport.CliLauncher launcher = McpToolInjectionSupport.findCliLauncher();

            assertNotNull(launcher);
            assertNotEquals(staleBinary.toString(), launcher.command());
        } finally {
            if (previousBinary == null) {
                System.clearProperty("kompile.cli.binary");
            } else {
                System.setProperty("kompile.cli.binary", previousBinary);
            }
        }
    }

    private Path createExecutable(String name) throws Exception {
        Path binary = Files.createFile(tempDir.resolve(name)).toAbsolutePath();
        assertTrue(binary.toFile().setExecutable(true));
        return binary;
    }
}
