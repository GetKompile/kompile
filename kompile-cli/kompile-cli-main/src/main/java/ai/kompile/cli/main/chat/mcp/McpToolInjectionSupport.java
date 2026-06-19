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
import ai.kompile.cli.main.MainCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
        String binaryOverride = firstNonBlank(System.getProperty("kompile.cli.binary"), System.getenv("KOMPILE_CLI_BINARY"));
        if (binaryOverride != null && !binaryOverride.isBlank()) {
            return new CliLauncher(binaryOverride, List.of());
        }

        String jarOverride = firstNonBlank(System.getProperty("kompile.cli.jar"), System.getenv("KOMPILE_CLI_JAR"));
        if (jarOverride != null && Files.exists(Path.of(jarOverride))) {
            return new CliLauncher(resolveJavaCommand(), List.of("-jar", Path.of(jarOverride).toAbsolutePath().toString()));
        }

        String currentCommand = ProcessHandle.current().info().command().orElse(null);
        if (currentCommand != null && !currentCommand.toLowerCase(Locale.ROOT).contains("java")) {
            String scriptLauncher = resolveShellWrappedLauncher();
            if (scriptLauncher != null) {
                return new CliLauncher(scriptLauncher, List.of());
            }
            return new CliLauncher(currentCommand, List.of());
        }

        Path codeSource = resolveCodeSource();
        if (codeSource != null && Files.isRegularFile(codeSource) && codeSource.toString().endsWith(".jar")) {
            return new CliLauncher(resolveJavaCommand(), List.of("-jar", codeSource.toString()));
        }

        String classPath = System.getProperty("java.class.path");
        if (classPath != null && !classPath.isBlank()) {
            return new CliLauncher(resolveJavaCommand(), List.of("-cp", classPath, MainCommand.class.getName()));
        }

        return null;
    }

    private static Path resolveCodeSource() {
        try {
            return Path.of(MainCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath()
                .normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private static String resolveJavaCommand() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String javaBinary = osName.contains("win") ? "java.exe" : "java";
        Path javaPath = Path.of(System.getProperty("java.home"), "bin", javaBinary);
        if (Files.isExecutable(javaPath)) {
            return javaPath.toString();
        }
        return ProcessHandle.current().info().command().orElse("java");
    }

    private static Path normalizeWorkingDir(Path workingDir) {
        Path resolved = workingDir != null ? workingDir : Path.of(System.getProperty("user.dir"));
        return resolved.toAbsolutePath().normalize();
    }

    private static String resolveShellWrappedLauncher() {
        String currentCommand = ProcessHandle.current().info().command().orElse(null);
        if (currentCommand == null) {
            return null;
        }

        String executableName = Path.of(currentCommand).getFileName().toString().toLowerCase(Locale.ROOT);
        if (!List.of("sh", "bash", "zsh", "dash", "fish").contains(executableName)) {
            return null;
        }

        String[] arguments = ProcessHandle.current().info().arguments().orElse(null);
        if (arguments == null || arguments.length == 0) {
            return null;
        }

        try {
            Path scriptPath = Path.of(arguments[0]).toAbsolutePath().normalize();
            if (Files.isExecutable(scriptPath)) {
                return scriptPath.toString();
            }
        } catch (Exception e) {
            return null;
        }

        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
}
