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

package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.CliProcessLauncher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared support for generating MCP configs and launching the CLI stdio MCP server.
 *
 * <p>When a running kompile-app exposes MCP over SSE we prefer that endpoint so
 * agents receive server-side tools. If no running server is available, we fall
 * back to the CLI's own stdio MCP server hosted by {@code kompile mcp-stdio}.</p>
 */
public final class McpToolInjectionSupport {

    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.standardMapper();
    private static final String SERVER_NAME = "kompile";

    private McpToolInjectionSupport() {}

    /**
     * Create the preferred MCP config for an external agent.
     * Prefers a live SSE endpoint when available, otherwise falls back to the
     * CLI stdio MCP server.
     */
    public static McpConfig createPreferredConfig(Path workingDir, String sseUrl) throws IOException {
        if (sseUrl != null && !sseUrl.isBlank()) {
            return createSseConfig(sseUrl);
        }
        return createStdioConfig(workingDir);
    }

    /**
     * Create an MCP config that launches the CLI stdio server.
     */
    public static McpConfig createStdioConfig(Path workingDir) throws IOException {
        CliLauncher launcher = findCliLauncher();
        if (launcher == null) {
            return null;
        }

        Path normalizedWorkingDir = normalizeWorkingDir(workingDir);
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        ObjectNode server = root.putObject("mcpServers").putObject(SERVER_NAME);
        server.put("command", launcher.command());

        ArrayNode args = server.putArray("args");
        for (String arg : launcher.buildArgs(normalizedWorkingDir)) {
            args.add(arg);
        }

        server.put("cwd", normalizedWorkingDir.toString());
        return writeConfig(root, "kompile-cli stdio", "stdio");
    }

    /**
     * Build a direct process command for starting the CLI stdio MCP server.
     */
    public static List<String> buildStdioServerProcessCommand(Path workingDir) {
        CliLauncher launcher = findCliLauncher();
        if (launcher == null) {
            return null;
        }

        Path normalizedWorkingDir = normalizeWorkingDir(workingDir);
        List<String> command = new ArrayList<>();
        command.add(launcher.command());
        command.addAll(launcher.buildArgs(normalizedWorkingDir));
        return command;
    }

    /**
     * Re-executes the installed CLI through its native binary or executable JAR ABI.
     * Development classpaths are deliberately not accepted.
     */
    public static List<String> buildCliProcessCommand(List<String> arguments) {
        CliLauncher launcher = findCliLauncher();
        if (launcher == null) {
            return null;
        }
        List<String> command = new ArrayList<>();
        command.add(launcher.command());
        command.addAll(launcher.prefixArgs());
        if (arguments != null) {
            command.addAll(arguments);
        }
        return command;
    }

    private static McpConfig createSseConfig(String sseUrl) throws IOException {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        ObjectNode server = root.putObject("mcpServers").putObject(SERVER_NAME);
        server.put("url", sseUrl);
        server.put("transport", "sse");
        return writeConfig(root, sseUrl, "sse");
    }

    private static McpConfig writeConfig(ObjectNode root, String displayTarget, String transport) throws IOException {
        Path configPath = Files.createTempFile("kompile-mcp-", ".json");
        Files.writeString(configPath, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        configPath.toFile().deleteOnExit();
        return new McpConfig(configPath.toString(), displayTarget, transport);
    }

    static CliLauncher findCliLauncher() {
        CliProcessLauncher.Launcher launcher = CliProcessLauncher.find();
        return launcher == null
                ? null
                : new CliLauncher(launcher.command(), launcher.prefixArgs());
    }

    /**
     * Normalizes the executable reported by {@link ProcessHandle}. Linux appends
     * {@code " (deleted)"} when a running native executable has been replaced on disk. Passing
     * that diagnostic suffix to a child as its command makes an otherwise healthy stdio MCP
     * server impossible to launch. Reuse the replacement path only when it is executable; all
     * other stale or malformed values fall through to the executable-JAR launcher.
     */
    static String normalizeCurrentCommand(String command) {
        return CliProcessLauncher.normalizeCurrentCommand(command);
    }

    private static Path normalizeWorkingDir(Path workingDir) {
        Path resolved = workingDir != null ? workingDir : Path.of(System.getProperty("user.dir"));
        return resolved.toAbsolutePath().normalize();
    }

    static record CliLauncher(String command, List<String> prefixArgs) {
        List<String> buildArgs(Path workingDir) {
            List<String> args = new ArrayList<>(prefixArgs);
            args.add("mcp-stdio");
            args.add("--work-dir");
            args.add(workingDir.toString());
            return args;
        }
    }

    public record McpConfig(String path, String displayTarget, String transport) {}

    // ── Public accessors for persistent config writers ─────────────────────

    /**
     * Returns the command of the resolved CLI launcher, or {@code null} when no
     * launcher can be found. Callers that need the full argument list should also
     * call {@link #findCliLauncherArgs(Path)}.
     *
     * <p>This is an additive helper that exposes the package-private {@link CliLauncher}
     * for use by classes outside this package (e.g. {@code InitAgentProvisioner}).</p>
     */
    public static String findCliLauncherCommand() {
        CliLauncher l = findCliLauncher();
        return l == null ? null : l.command();
    }

    /**
     * Returns the full argument list for the CLI stdio MCP server, resolved for the
     * given working directory.  Returns {@code null} when no launcher is available.
     */
    public static List<String> findCliLauncherArgs(Path workingDir) {
        CliLauncher l = findCliLauncher();
        if (l == null) return null;
        return l.buildArgs(normalizeWorkingDir(workingDir));
    }
}
