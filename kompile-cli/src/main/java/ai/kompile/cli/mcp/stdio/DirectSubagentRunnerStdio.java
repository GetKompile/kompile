/*
 *   Copyright 2025 Kompile Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package ai.kompile.cli.mcp.stdio;

import ai.kompile.cli.main.chat.agent.AgentConfig;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Spawns external agent processes (qwen, claude, codex, etc.) as subagents.
 * <p>
 * CRITICAL: This class runs inside {@code kompile mcp-stdio}, where System.out is
 * the MCP JSON-RPC response pipe. All human-readable output MUST go to System.err,
 * and subprocess I/O must NOT inherit the parent's stdin/stdout to avoid corrupting
 * the MCP protocol stream or deadlocking on shared pipes.
 */
public class DirectSubagentRunnerStdio {

    private static final String RESET = "\033[0m";
    private static final String GREEN = "\033[32m";
    private static final String DIM = "\033[2m";
    private static final String RED = "\033[31m";

    /** Max bytes to capture from subprocess stdout+stderr combined. */
    private static final int MAX_OUTPUT_BYTES = 512_000; // 512KB

    private final Path workDir;

    public DirectSubagentRunnerStdio(Path workDir) {
        this.workDir = workDir;
    }

    public String runSubagent(AgentConfig agent, String prompt) throws Exception {
        String agentName = agent.getName();
        String binary = resolveAgentBinary(agentName);

        if (binary == null) {
            return String.format(
                "Agent '%s' not found in PATH.\n\n" +
                "Please install the agent first:\n" +
                "  - Claude Code: npm install -g @anthropic-ai/claude-code\n" +
                "  - Codex: Install from OpenAI\n" +
                "  - Qwen Code: npm install -g @anthropic-ai/qwen-code\n" +
                "  - Gemini CLI: npm install -g @anthropic-ai/gemini-code\n" +
                "  - OpenCode: go install github.com/opencode-ai/opencode@latest",
                agentName);
        }

        // All status output goes to stderr (stdout is the MCP JSON-RPC pipe)
        System.err.println(GREEN + "⟳ Spawning subagent: " + agentName + RESET);
        System.err.println(DIM + "  Binary: " + binary + RESET);
        System.err.println(DIM + "  Prompt: " + prompt.substring(0, Math.min(80, prompt.length())) + "..." + RESET);
        System.err.flush();

        List<String> cmd = buildAgentCommand(binary, agentName, prompt);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir.toFile());

        // CRITICAL: Do NOT use inheritIO(). When running inside kompile mcp-stdio:
        //   - stdout is the MCP JSON-RPC pipe — subprocess output would corrupt it
        //   - stdin is the MCP JSON-RPC input — subprocess reads would consume messages
        // Instead: close stdin, merge stderr into stdout, capture all output.
        pb.redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        pb.redirectErrorStream(true); // merge stderr into stdout for unified capture

        Process process = pb.start();
        long startTime = System.currentTimeMillis();

        // Capture subprocess output in a background thread to prevent pipe buffer deadlock
        StringBuilder outputBuilder = new StringBuilder();
        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                int totalRead = 0;
                int n;
                while ((n = reader.read(buf)) != -1) {
                    if (totalRead < MAX_OUTPUT_BYTES) {
                        int toAppend = Math.min(n, MAX_OUTPUT_BYTES - totalRead);
                        outputBuilder.append(buf, 0, toAppend);
                    }
                    totalRead += n;
                }
                if (totalRead > MAX_OUTPUT_BYTES) {
                    outputBuilder.append("\n... (output truncated, ")
                        .append(totalRead).append(" chars total)");
                }
            } catch (IOException e) {
                // Process ended, stream closed
            }
        }, "subagent-output-" + agentName);
        outputReader.setDaemon(true);
        outputReader.start();

        int exitCode = process.waitFor();
        outputReader.join(5000); // wait up to 5s for output thread to finish

        long elapsed = System.currentTimeMillis() - startTime;
        String output = outputBuilder.toString().trim();

        if (exitCode == 0) {
            System.err.println(DIM + "✓ Subagent '" + agentName + "' completed in " +
                String.format("%.1fs", elapsed / 1000.0) + RESET);
        } else {
            System.err.println(RED + "✗ Subagent '" + agentName + "' exited with code " +
                exitCode + " after " + String.format("%.1fs", elapsed / 1000.0) + RESET);
        }
        System.err.flush();

        // Return the captured output as the tool result (not "check output above")
        if (output.isEmpty()) {
            return exitCode == 0
                ? String.format("Subagent '%s' completed successfully in %.1fs (no output captured).", agentName, elapsed / 1000.0)
                : String.format("Subagent '%s' exited with code %d after %.1fs (no output captured).", agentName, exitCode, elapsed / 1000.0);
        }

        String header = exitCode == 0
            ? String.format("Subagent '%s' completed in %.1fs:\n\n", agentName, elapsed / 1000.0)
            : String.format("Subagent '%s' exited with code %d after %.1fs:\n\n", agentName, exitCode, elapsed / 1000.0);

        return header + output;
    }

    private String resolveAgentBinary(String agentName) {
        String binary = switch (agentName.toLowerCase()) {
            case "qwen", "qwen-code" -> "qwen";
            case "claude", "claude-code" -> "claude";
            case "codex" -> "codex";
            case "gemini" -> "gemini";
            case "opencode", "open-code" -> "opencode";
            default -> agentName;
        };

        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                File candidate = new File(dir, binary);
                if (candidate.canExecute()) return candidate.getAbsolutePath();
                for (String ext : new String[]{".exe", ".cmd", ".bat"}) {
                    File candidateExt = new File(dir, binary + ext);
                    if (candidateExt.canExecute()) return candidateExt.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private List<String> buildAgentCommand(String binary, String agentName, String prompt) {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary);

        String name = agentName.toLowerCase();

        // Inject MCP tools into the agent's settings before building the command.
        // McpToolInjection clears existing MCP servers to prevent recursive spawning deadlocks
        // (subagent → kompile MCP → task tool → subagent → ...).
        try {
            Path settingsFile = ai.kompile.cli.main.chat.mcp.McpToolInjection.injectTools(workDir, agentName);
            if (settingsFile != null) {
                System.err.println(DIM + "  Injected kompile MCP tools into " + settingsFile + RESET);
                System.err.flush();
            }
        } catch (Exception e) {
            System.err.println(DIM + "  Warning: Could not configure MCP tools for subagent: "
                    + e.getMessage() + RESET);
            System.err.flush();
        }

        // Build the non-interactive command for each agent.
        // Each agent has its own flags for auto-approve and one-shot prompt execution.
        if (name.contains("claude")) {
            // claude --dangerously-skip-permissions -p "prompt"
            cmd.add("--dangerously-skip-permissions");
            cmd.add("-p");
            cmd.add(prompt);
        } else if (name.contains("codex")) {
            // codex exec --full-auto "prompt"
            cmd.add("exec");
            cmd.add("--full-auto");
            cmd.add(prompt);
        } else if (name.contains("qwen")) {
            // qwen --yolo -p "prompt"
            cmd.add("--yolo");
            cmd.add("-p");
            cmd.add(prompt);
        } else if (name.contains("gemini")) {
            // gemini --yolo -p "prompt"
            cmd.add("--yolo");
            cmd.add("-p");
            cmd.add(prompt);
        } else if (name.contains("opencode")) {
            // opencode run "prompt" (auto-approves in non-interactive mode)
            cmd.add("run");
            cmd.add(prompt);
        } else {
            // Generic fallback: try -p flag
            cmd.add("-p");
            cmd.add(prompt);
        }

        return cmd;
    }
}
