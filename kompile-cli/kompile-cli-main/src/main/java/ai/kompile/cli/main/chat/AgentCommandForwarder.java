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

import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Forwards slash commands typed in kompile's REPL to the underlying agent CLI
 * (claude, codex, opencode, gemini, qwen) and streams the output in real time.
 * <p>
 * Each agent has its own subcommand structure:
 * <ul>
 *   <li><b>claude</b>: {@code claude model list}, {@code claude config list}, {@code claude --help}</li>
 *   <li><b>codex</b>: {@code codex --help}, {@code codex --model} (flag-based)</li>
 *   <li><b>opencode</b>: {@code opencode --help}, {@code opencode config} (subcommand-based)</li>
 *   <li><b>gemini</b>: {@code gemini --help}</li>
 *   <li><b>qwen</b>: {@code qwen --help}</li>
 * </ul>
 * <p>
 * The forwarder wraps subprocesses with a PTY ({@code script -q /dev/null -c ...})
 * to force line-buffered output and preserve ANSI colors/formatting from the agent.
 */
public class AgentCommandForwarder {

    private static final int DEFAULT_TIMEOUT_SECONDS = 15;

    private final String workingDir;

    public AgentCommandForwarder() {
        this(System.getProperty("user.dir"));
    }

    public AgentCommandForwarder(String workingDir) {
        this.workingDir = workingDir;
    }

    /**
     * Describes a mapped agent command: the CLI arguments to execute and
     * a human-readable label for status messages.
     */
    public record AgentCommand(List<String> command, String label) {}

    /**
     * Result of executing a forwarded command.
     */
    public record ForwardResult(int exitCode, String output, boolean timedOut) {
        public boolean success() {
            return exitCode == 0 && !timedOut;
        }
    }

    // ── Slash command mapping ──────────────────────────────────────────────

    /**
     * Map a kompile slash command to the equivalent agent CLI command.
     *
     * @param slashCommand the raw slash command (e.g. "/model", "/model sonnet", "/help")
     * @param agentBinary  resolved path to the agent binary
     * @param agentName    canonical agent name (claude, codex, opencode, etc.)
     * @return the mapped command, or null if no mapping exists
     */
    public AgentCommand mapSlashCommand(String slashCommand, String agentBinary, String agentName) {
        String[] parts = slashCommand.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1] : "";
        String agentKey = normalizeAgentKey(agentName);

        return switch (cmd) {
            case "/model" -> mapModelCommand(agentBinary, agentKey, rest);
            case "/config" -> mapConfigCommand(agentBinary, agentKey, rest);
            case "/help", "/?" -> mapHelpCommand(agentBinary, agentKey, rest);
            case "/version" -> mapVersionCommand(agentBinary, agentKey);
            case "/doctor" -> mapDoctorCommand(agentBinary, agentKey);
            default -> mapGenericCommand(agentBinary, agentKey, cmd.substring(1), rest);
        };
    }

    /**
     * Returns the list of known forwardable slash commands for a given agent.
     * Used for help text and tab completion.
     */
    public List<String> listForwardableCommands(String agentName) {
        String agentKey = normalizeAgentKey(agentName);
        List<String> commands = new ArrayList<>();
        commands.add("/model");
        commands.add("/help");
        commands.add("/version");

        switch (agentKey) {
            case "claude" -> {
                commands.add("/config");
                commands.add("/doctor");
            }
            case "codex" -> {
                // Codex uses flag-based config, fewer subcommands
            }
            case "opencode" -> {
                commands.add("/config");
            }
            case "pi" -> {
                commands.add("/config");
            }
        }
        return commands;
    }

    // ── Per-command mapping ────────────────────────────────────────────────

    private AgentCommand mapModelCommand(String binary, String agentKey, String rest) {
        return switch (agentKey) {
            case "claude" -> {
                if (rest.isBlank()) {
                    yield new AgentCommand(List.of(binary, "model", "list"), "claude model list");
                } else if (rest.startsWith("get")) {
                    yield new AgentCommand(List.of(binary, "model", "get"), "claude model get");
                } else if (rest.startsWith("set ")) {
                    String modelName = rest.substring(4).trim();
                    yield new AgentCommand(List.of(binary, "model", "set", modelName), "claude model set " + modelName);
                } else {
                    // Treat argument as a model to set
                    yield new AgentCommand(List.of(binary, "model", "set", rest.trim()), "claude model set " + rest.trim());
                }
            }
            case "codex" -> {
                if (rest.isBlank()) {
                    // Codex has no model list subcommand; doctor shows config and runtime info
                    yield new AgentCommand(List.of(binary, "doctor"), "codex doctor (model & config info)");
                } else {
                    // Codex model is set via --model flag at runtime
                    yield new AgentCommand(List.of(binary, "doctor"), "codex doctor");
                }
            }
            case "opencode" -> {
                if (rest.isBlank()) {
                    yield new AgentCommand(List.of(binary, "models"), "opencode models");
                } else {
                    // opencode models <provider> lists models for a specific provider
                    yield new AgentCommand(List.of(binary, "models", rest.trim()), "opencode models " + rest.trim());
                }
            }
            case "gemini" -> {
                if (rest.isBlank()) {
                    yield new AgentCommand(List.of(binary, "--help"), "gemini --help (model flags)");
                } else {
                    yield new AgentCommand(List.of(binary, "--help"), "gemini --help");
                }
            }
            case "qwen" -> {
                if (rest.isBlank()) {
                    yield new AgentCommand(List.of(binary, "--help"), "qwen --help (model flags)");
                } else {
                    yield new AgentCommand(List.of(binary, "--help"), "qwen --help");
                }
            }
            case "pi" -> {
                if (rest.isBlank()) {
                    yield new AgentCommand(List.of(binary, "--list-models"), "pi --list-models");
                } else {
                    // pi --list-models <search> does fuzzy search
                    yield new AgentCommand(List.of(binary, "--list-models", rest.trim()), "pi --list-models " + rest.trim());
                }
            }
            default -> new AgentCommand(List.of(binary, "--help"), agentKey + " --help");
        };
    }

    private AgentCommand mapConfigCommand(String binary, String agentKey, String rest) {
        return switch (agentKey) {
            case "claude" -> {
                if (rest.isBlank()) {
                    yield new AgentCommand(List.of(binary, "config", "list"), "claude config list");
                } else if (rest.startsWith("get ")) {
                    String key = rest.substring(4).trim();
                    yield new AgentCommand(List.of(binary, "config", "get", key), "claude config get " + key);
                } else if (rest.startsWith("set ")) {
                    String[] kv = rest.substring(4).trim().split("\\s+", 2);
                    if (kv.length == 2) {
                        yield new AgentCommand(List.of(binary, "config", "set", kv[0], kv[1]), "claude config set " + kv[0]);
                    }
                    yield new AgentCommand(List.of(binary, "config", "list"), "claude config list");
                } else {
                    yield new AgentCommand(List.of(binary, "config", "list"), "claude config list");
                }
            }
            case "opencode" -> new AgentCommand(List.of(binary, "providers"), "opencode providers");
            case "pi" -> new AgentCommand(List.of(binary, "config"), "pi config");
            default -> null; // No config subcommand for this agent
        };
    }

    private AgentCommand mapHelpCommand(String binary, String agentKey, String rest) {
        if (rest.isBlank()) {
            return new AgentCommand(List.of(binary, "--help"), agentKey + " --help");
        }
        // Help for a specific subcommand: claude model --help
        return switch (agentKey) {
            case "claude" -> new AgentCommand(List.of(binary, rest.trim(), "--help"), "claude " + rest.trim() + " --help");
            default -> new AgentCommand(List.of(binary, "--help"), agentKey + " --help");
        };
    }

    private AgentCommand mapVersionCommand(String binary, String agentKey) {
        return new AgentCommand(List.of(binary, "--version"), agentKey + " --version");
    }

    private AgentCommand mapDoctorCommand(String binary, String agentKey) {
        return switch (agentKey) {
            case "claude" -> new AgentCommand(List.of(binary, "doctor"), "claude doctor");
            default -> null;
        };
    }

    /**
     * Generic fallback: try to forward as a subcommand of the agent binary.
     * For example, {@code /mcp} becomes {@code claude mcp} for Claude.
     */
    private AgentCommand mapGenericCommand(String binary, String agentKey, String subcommand, String rest) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);
        cmd.add(subcommand);
        if (!rest.isBlank()) {
            // Split rest by whitespace (respecting simple quotes)
            Collections.addAll(cmd, rest.split("\\s+"));
        }
        return new AgentCommand(cmd, agentKey + " " + subcommand + (rest.isBlank() ? "" : " " + rest));
    }

    // ── Execution ─────────────────────────────────────────────────────────

    /**
     * Execute an agent command with real-time output streaming to stdout.
     * Uses PTY wrapping to preserve ANSI colors and force line-buffered output.
     *
     * @param agentCmd the command to execute
     * @return the result including exit code and captured output
     */
    public ForwardResult executeWithRealtimeOutput(AgentCommand agentCmd) {
        return executeWithRealtimeOutput(agentCmd, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Execute an agent command with real-time output streaming.
     *
     * @param agentCmd       the command to execute
     * @param timeoutSeconds maximum time to wait
     * @return the result including exit code and captured output
     */
    public ForwardResult executeWithRealtimeOutput(AgentCommand agentCmd, int timeoutSeconds) {
        List<String> cmd = wrapWithPty(agentCmd.command());
        StringBuilder captured = new StringBuilder();

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(workingDir).getAbsoluteFile());
            pb.redirectErrorStream(true);

            // Inherit essential env vars
            Map<String, String> env = pb.environment();
            inheritEnv(env, "PATH", "HOME", "USER", "SHELL", "LANG", "LC_ALL",
                    "TERM", "COLORTERM", "COLUMNS", "LINES",
                    "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GOOGLE_API_KEY",
                    "GEMINI_API_KEY", "CODEX_API_KEY");
            // Force color output
            env.putIfAbsent("FORCE_COLOR", "1");
            env.putIfAbsent("CLICOLOR_FORCE", "1");

            Process process = pb.start();

            // Stream stdout line by line in real time
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = reader.read(buf)) != -1) {
                        String chunk = new String(buf, 0, n);
                        // Strip carriage returns from PTY output
                        chunk = chunk.replace("\r\n", "\n");
                        System.out.print(chunk);
                        System.out.flush();
                        captured.append(chunk);
                    }
                } catch (IOException e) {
                    // Process ended
                }
            }, "agent-cmd-reader");
            readerThread.setDaemon(true);
            readerThread.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                readerThread.interrupt();
                return new ForwardResult(-1, captured.toString(), true);
            }

            // Wait for reader to finish consuming remaining output
            readerThread.join(2000);

            return new ForwardResult(process.exitValue(), captured.toString(), false);

        } catch (IOException e) {
            return new ForwardResult(-1, "Failed to execute: " + e.getMessage(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ForwardResult(-1, "Interrupted", false);
        }
    }

    // ── Agent resolution ──────────────────────────────────────────────────

    /**
     * Resolve an agent name to its binary path on the system PATH.
     *
     * @param name agent name (e.g. "claude", "codex")
     * @return absolute path to the binary, or null if not found
     */
    public static String resolveAgentBinary(String name) {
        return SubprocessAgentRunner.resolveAgentBinary(name);
    }

    /**
     * Check if a given agent is installed and available on PATH.
     */
    public static boolean isAgentAvailable(String agentName) {
        return resolveAgentBinary(agentName) != null;
    }

    // ── PTY wrapping ──────────────────────────────────────────────────────

    /**
     * Wrap a command with {@code script -q /dev/null -c ...} to allocate a PTY.
     * This forces line-buffered output and preserves ANSI formatting.
     */
    static List<String> wrapWithPty(List<String> cmd) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().startsWith("win");
        if (isWindows) return new ArrayList<>(cmd);

        // Check if 'script' is available
        try {
            Process check = new ProcessBuilder("which", "script")
                    .redirectErrorStream(true).start();
            int rc = check.waitFor();
            if (rc != 0) return new ArrayList<>(cmd);
        } catch (Exception e) {
            return new ArrayList<>(cmd);
        }

        boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        List<String> wrapped = new ArrayList<>();
        wrapped.add("script");
        wrapped.add("-q");
        if (isMac) {
            wrapped.add("/dev/null");
            wrapped.addAll(cmd);
        } else {
            // Linux: script -q /dev/null -c "cmd arg1 arg2"
            wrapped.add("/dev/null");
            wrapped.add("-c");
            StringBuilder cmdStr = new StringBuilder();
            for (int i = 0; i < cmd.size(); i++) {
                if (i > 0) cmdStr.append(' ');
                String arg = cmd.get(i);
                if (arg.contains(" ") || arg.contains("'") || arg.contains("\"")
                        || arg.contains("(") || arg.contains(")")) {
                    cmdStr.append("'").append(arg.replace("'", "'\\''")).append("'");
                } else {
                    cmdStr.append(arg);
                }
            }
            wrapped.add(cmdStr.toString());
        }
        return wrapped;
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    static String normalizeAgentKey(String agentName) {
        if (agentName == null) return "unknown";
        String name = agentName.toLowerCase().trim();
        if (name.contains("claude")) return "claude";
        if (name.contains("codex")) return "codex";
        if (name.contains("opencode") || name.contains("open-code")) return "opencode";
        if (name.contains("gemini")) return "gemini";
        if (name.contains("qwen")) return "qwen";
        if (name.equals("pi") || name.contains("pi-coding")) return "pi";
        return name;
    }

    private static void inheritEnv(Map<String, String> target, String... keys) {
        for (String key : keys) {
            String value = System.getenv(key);
            if (value != null) {
                target.put(key, value);
            }
        }
    }
}
