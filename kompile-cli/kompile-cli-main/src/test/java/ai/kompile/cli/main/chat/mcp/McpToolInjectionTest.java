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

import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
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
    void staleAbsoluteBinaryOverrideFallsBackToExecutableJar() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        String previousJar = System.getProperty("kompile.cli.jar");
        Path staleBinary = tempDir.resolve("missing-kompile-cli").toAbsolutePath();
        Path executableJar = Files.writeString(
                tempDir.resolve("kompile-cli-main-exec.jar"), "executable jar");
        try {
            System.setProperty("kompile.cli.binary", staleBinary.toString());
            System.setProperty("kompile.cli.jar", executableJar.toString());

            McpToolInjectionSupport.CliLauncher launcher = McpToolInjectionSupport.findCliLauncher();

            assertNotNull(launcher);
            assertNotEquals(staleBinary.toString(), launcher.command());
            assertEquals(List.of("-jar", executableJar.toAbsolutePath().toString()),
                    launcher.prefixArgs());
        } finally {
            restoreProperty("kompile.cli.binary", previousBinary);
            restoreProperty("kompile.cli.jar", previousJar);
        }
    }

    @Test
    void deletedProcessExecutableSuffixUsesTheRunnableReplacement() throws Exception {
        Path replacement = createExecutable("replacement-kompile-cli");

        assertEquals(replacement.toString(),
                McpToolInjectionSupport.normalizeCurrentCommand(replacement + " (deleted)"));
    }

    @Test
    void deletedProcessExecutableWithoutAReplacementIsRejected() {
        Path missing = tempDir.resolve("missing-replacement").toAbsolutePath();

        assertEquals(null,
                McpToolInjectionSupport.normalizeCurrentCommand(missing + " (deleted)"));
    }


    @Test
    void piInjectionWritesDirectToolsConfigAndRestoresExistingFile() throws Exception {
        String previousBinary = System.getProperty("kompile.cli.binary");
        String previousAdapter = System.getProperty("kompile.pi.adapter.path");
        Path workDir = tempDir.resolve("pi-work");
        Path adapter = tempDir.resolve("adapter");
        Files.createDirectories(workDir);
        Files.createDirectories(adapter);
        Files.writeString(adapter.resolve("package.json"), "{}");
        Path config = workDir.resolve(".pi").resolve("mcp.json");
        Files.createDirectories(config.getParent());
        String original = "{\n  \"mcpServers\": { \"other\": { \"command\": \"other\" } }\n}\n";
        Files.writeString(config, original);
        Path binary = createExecutable("pi-kompile-cli");

        try {
            System.setProperty("kompile.cli.binary", binary.toString());
            System.setProperty("kompile.pi.adapter.path", adapter.toString());

            Path written = McpToolInjection.injectTools(workDir, "pi-cli", "http://localhost:8080/mcp/sse");
            assertEquals(config, written);

            String configured = Files.readString(config);
            assertTrue(configured.contains("\"other\""));
            assertTrue(configured.contains("\"kompile\""));
            assertTrue(configured.contains("\"directTools\" : true") || configured.contains("\"directTools\" : true"));
            assertTrue(configured.contains("\"toolPrefix\" : \"mcp\""));
            assertTrue(configured.contains("\"lifecycle\" : \"eager\""));
            assertTrue(configured.contains("http://localhost:8080/mcp/sse"));

            assertEquals(List.of("-e", adapter.toString()),
                    McpToolInjection.commandLineOverrides(workDir, "pi"));
        } finally {
            McpToolInjection.removeTools(config);
            assertEquals(original, Files.readString(config));
            restoreProperty("kompile.cli.binary", previousBinary);
            restoreProperty("kompile.pi.adapter.path", previousAdapter);
        }
    }

    @Test
    void piManagedCommandReceivesExtensionBeforeProviderFlags() throws Exception {
        String previousAdapter = System.getProperty("kompile.pi.adapter.path");
        Path adapter = tempDir.resolve("adapter-command");
        Files.createDirectories(adapter);
        try {
            System.setProperty("kompile.pi.adapter.path", adapter.toString());
            List<String> base = SubprocessAgentRunner.buildManagedCommand(
                    "pi", "pi", "hello", false, null, true, tempDir, null);
            List<String> command = SubprocessAgentRunner.prependGlobalOptions(
                    base, McpToolInjection.commandLineOverrides(tempDir, "pi"));

            assertEquals("pi", command.get(0));
            assertEquals("-e", command.get(1));
            assertEquals(adapter.toString(), command.get(2));
        } finally {
            restoreProperty("kompile.pi.adapter.path", previousAdapter);
        }
    }

    @Test
    void piAdapterIsProvisionedFromClasspath() throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousAdapter = System.getProperty("kompile.pi.adapter.path");
        Path home = tempDir.resolve("pi-home");
        Files.createDirectories(home);
        try {
            System.setProperty("user.home", home.toString());
            System.clearProperty("kompile.pi.adapter.path");

            Path adapter = PiMcpAdapterProvisioner.ensureProvisioned();
            assertTrue(Files.isRegularFile(adapter.resolve("package.json")));
            assertTrue(Files.isRegularFile(adapter.resolve("index.ts")));
            assertEquals(adapter, PiMcpAdapterProvisioner.ensureProvisioned());
        } finally {
            restoreProperty("user.home", previousHome);
            restoreProperty("kompile.pi.adapter.path", previousAdapter);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private Path createExecutable(String name) throws Exception {
        Path binary = Files.createFile(tempDir.resolve(name)).toAbsolutePath();
        assertTrue(binary.toFile().setExecutable(true));
        return binary;
    }
}
