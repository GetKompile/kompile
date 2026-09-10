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

import ai.kompile.cli.main.chat.ChatHistory;
import ai.kompile.cli.main.chat.ChatSessionMetrics;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.agent.AgentLaunchDefaults;
import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import ai.kompile.cli.main.chat.config.SystemPromptManager;
import ai.kompile.cli.main.chat.render.AsciiRenderer;
import ai.kompile.cli.main.chat.render.TerminalRenderer;
import ai.kompile.cli.main.chat.roles.RoleConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import ai.kompile.cli.main.chat.skill.SkillsInjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs delegated MCP subagents through the same managed terminal runner used by
 * interactive passthrough sessions.
 * <p>
 * This class lives inside {@code kompile mcp-stdio}, where {@code System.out}
 * is the MCP JSON-RPC response pipe. Human-readable status goes to
 * {@code System.err}, while child output is captured through
 * {@link SubprocessAgentRunner#setOutputConsumer(java.util.function.Consumer)}.
 */
public class DirectSubagentRunnerStdio {

    private static final String RESET = "\033[0m";
    private static final String GREEN = "\033[32m";
    private static final String DIM = "\033[2m";
    private static final String RED = "\033[31m";
    private static final int SUMMARY_MAX_CHARS = 2000;

    /** Maximum subagent recursion depth before MCP tool injection is disabled. */
    private static final int MAX_SUBAGENT_DEPTH = 3;

    private final Path workDir;
    private final RoleManager roleManager;

    private volatile McpSessionTracker sessionTracker;
    private volatile SkillsInjection skillsInjection;
    private volatile SystemPromptManager systemPromptManager;
    private volatile Map<String, String> baseEnvironment = Map.of();
    private volatile Map<String, String> extraEnvironment = Map.of();
    private volatile String lastTaskId;

    private final AtomicBoolean cancelSignal = new AtomicBoolean(false);
    private volatile SubprocessAgentRunner currentRunner;
    private volatile Thread waitingThread;

    public DirectSubagentRunnerStdio(Path workDir) {
        this(workDir, null);
    }

    public DirectSubagentRunnerStdio(Path workDir, RoleManager roleManager) {
        this.workDir = workDir;
        this.roleManager = roleManager;
    }

    public void setSessionTracker(McpSessionTracker tracker) {
        this.sessionTracker = tracker;
    }

    public void setSkillsInjection(SkillsInjection injection) {
        this.skillsInjection = injection;
    }

    public void setSystemPromptManager(SystemPromptManager spm) {
        this.systemPromptManager = spm;
    }

    /** Environment that every delegated agent inherits, independent of temporary policy overlays. */
    public void setBaseEnvironment(Map<String, String> env) {
        this.baseEnvironment = env != null ? Map.copyOf(env) : Map.of();
    }

    public void setExtraEnvironment(Map<String, String> env) {
        this.extraEnvironment = env != null ? Map.copyOf(env) : Map.of();
    }

    public String getLastTaskId() {
        return lastTaskId;
    }

    DirectSubagentRunnerStdio forkForSubagent() {
        DirectSubagentRunnerStdio fork = new DirectSubagentRunnerStdio(workDir, roleManager);
        fork.setSessionTracker(sessionTracker);
        fork.setSkillsInjection(skillsInjection);
        fork.setSystemPromptManager(systemPromptManager);
        fork.setBaseEnvironment(baseEnvironment);
        fork.setExtraEnvironment(extraEnvironment);
        return fork;
    }

    /**
     * Cancel the current delegated turn through the managed runner.
     */
    public void cancel() {
        cancelSignal.set(true);
        SubprocessAgentRunner runner = currentRunner;
        if (runner != null) {
            runner.cancel();
        }
        Thread wt = waitingThread;
        if (wt != null) {
            wt.interrupt();
        }
    }

    private int getCurrentSubagentDepth() {
        String depthStr = System.getenv("KOMPILE_SUBAGENT_DEPTH");
        if (depthStr != null) {
            try {
                return Integer.parseInt(depthStr);
            } catch (NumberFormatException ignored) {
                // Treat malformed depth as top-level.
            }
        }
        return 0;
    }

    public String runSubagent(AgentConfig agent, String prompt) throws Exception {
        String agentName = agent.getName();
        String effectivePrompt = prompt;

        String roleName = agent.getRoleName();
        if (roleName == null || roleName.isBlank()) {
            roleName = resolveAssignedRole(agentName);
        }

        RoleConfig role = null;
        if (roleName != null && !roleName.isBlank()) {
            role = resolveRole(roleName);
            if (role == null) {
                throw new IllegalArgumentException("Unknown role '" + roleName + "'; refusing to launch subagent");
            }
            effectivePrompt = buildRolePrompt(role) + "\n\n---\n\n" + prompt;
            System.err.println(DIM + "  Role: " + roleName + RESET);
        }

        AgentLaunchDefaults.Selection launchDefaults = AgentLaunchDefaults.resolve(
                agentName,
                workDir,
                agent.getModelOverride(),
                agent.getThinkingOverride(),
                role != null ? role.getAgentDefaultsFor(agentName) : null);
        System.err.println(GREEN + "Spawning managed subagent: " + agentName + RESET);
        System.err.println(DIM + "  Role: " + (roleName == null ? "(none)" : roleName)
                + ", model: " + launchDefaults.model() + ", thinking: " + launchDefaults.thinking()
                + ", policy: FULL_ACCESS" + RESET);
        System.err.println(DIM + "  Prompt: " + effectivePrompt.substring(0, Math.min(80, effectivePrompt.length())) + "..." + RESET);
        System.err.flush();

        int currentDepth = getCurrentSubagentDepth();
        boolean injectMcpTools = currentDepth < MAX_SUBAGENT_DEPTH;
        if (!injectMcpTools) {
            System.err.println(DIM + "  Warning: subagent depth limit reached (" + currentDepth
                    + "), skipping MCP tool injection" + RESET);
        }

        return executeManagedSubagent(agentName, effectivePrompt, currentDepth, injectMcpTools,
                launchDefaults.model(), launchDefaults.thinking());
    }

    String executeManagedSubagent(String agentName, String effectivePrompt,
                                  int currentDepth, boolean injectMcpTools) throws Exception {
        return executeManagedSubagent(agentName, effectivePrompt, currentDepth, injectMcpTools,
                null, null);
    }

    String executeManagedSubagent(String agentName, String effectivePrompt,
                                  int currentDepth, boolean injectMcpTools,
                                  String modelOverride, String thinkingOverride) throws Exception {
        long startTime = System.currentTimeMillis();
        cancelSignal.set(false);
        waitingThread = Thread.currentThread();

        StringBuilder captured = new StringBuilder();
        Path captureFile;
        try {
            Path resultsDir = workDir.resolve(".kompile").resolve("task-results");
            Files.createDirectories(resultsDir);
            captureFile = Files.createTempFile(resultsDir, "capture-" + agentName + "-", ".tmp");
        } catch (IOException e) {
            captureFile = Files.createTempFile("kompile-capture-", ".tmp");
        }

        String sessionId = "subagent-" + agentName.replaceAll("[^a-zA-Z0-9_-]", "_")
                + "-" + UUID.randomUUID().toString().substring(0, 8);
        lastTaskId = sessionId;

        ChatHistory history = new ChatHistory(sessionId);
        try {
            history.open("mcp-subagent", agentName, false);
        } catch (IOException ignored) {
            // Transcript persistence is best effort; task-result capture remains authoritative.
        }

        ChatSessionMetrics metrics = new ChatSessionMetrics(sessionId);
        metrics.setAgentName(agentName + " (subagent)");

        SubprocessAgentRunner runner = createManagedRunner(agentName, injectMcpTools);
        runner.setLaunchOverrides(modelOverride, thinkingOverride);
        runner.setSkipPermissions(true);
        Map<String, String> env = new LinkedHashMap<>(baseEnvironment);
        env.putAll(extraEnvironment);
        env.put("KOMPILE_SUBAGENT_DEPTH", String.valueOf(currentDepth + 1));
        env.put("KOMPILE_AGENT_NAME", agentName);
        runner.setExtraEnvironment(env);
        runner.setOutputConsumer(line -> {
            synchronized (captured) {
                captured.append(line == null ? "" : line).append('\n');
            }
        });
        runner.setInputProvider(prompt -> null);

        currentRunner = runner;
        try {
            if (injectMcpTools) {
                runner.injectMcpTools();
            }
            if (skillsInjection != null) {
                runner.injectSkills();
            }

            String response = runner.runMessage(effectivePrompt, history, metrics);
            String blockingNotice = runner.getBlockingNotice();
            if (blockingNotice != null) {
                throw new IllegalStateException("Subagent '" + agentName + "' stopped: "
                        + blockingNotice + ". Select another agent or retry after the limit resets.");
            }
            if (response != null && !response.isBlank()) {
                synchronized (captured) {
                    if (captured.indexOf(response) < 0) {
                        captured.append(response).append('\n');
                    }
                }
            }
        } finally {
            currentRunner = null;
            waitingThread = null;
            runner.cleanup();
        }

        if (cancelSignal.get()) {
            throw new InterruptedException("Subagent '" + agentName + "' was cancelled");
        }

        long elapsed = System.currentTimeMillis() - startTime;
        String output;
        synchronized (captured) {
            output = captured.toString();
        }
        Files.writeString(captureFile, output, StandardCharsets.UTF_8);

        if (isRateLimited(output)) {
            throw new RateLimitException(agentName, output);
        }

        String header = String.format(
                "Subagent '%s' completed in %.1fs%n%nDispatch: model=%s, thinking=%s, policy=%s",
                agentName, elapsed / 1000.0,
                modelOverride == null || modelOverride.isBlank() ? "(native/default)" : modelOverride,
                thinkingOverride == null || thinkingOverride.isBlank() ? "(native/default)" : thinkingOverride,
                "FULL_ACCESS");
        System.err.println(DIM + "  Full output (" + output.length() + " chars) written to: "
                + captureFile.toAbsolutePath() + RESET);
        System.err.flush();

        if (output.isBlank()) {
            Files.deleteIfExists(captureFile);
            return String.format("Subagent '%s' completed successfully in %.1fs (no output captured).",
                    agentName, elapsed / 1000.0);
        }

        Path resultFile = renameResultFile(captureFile, agentName, header);
        String summary = buildSummaryFromBuffers(output.trim(), output, output.length());
        return header + "\n\n"
                + "## Summary\n" + summary + "\n\n"
                + "**Full output (" + output.length() + " chars) written to:** `"
                + resultFile.toAbsolutePath() + "`\n"
                + "Use the `read` tool to access the full result if needed.";
    }

    SubprocessAgentRunner createManagedRunner(String agentName, boolean injectMcpTools) {
        TerminalRenderer renderer = new SilentTerminalRenderer();
        AsciiRenderer ascii = new AsciiRenderer(renderer, 100);
        return new SubprocessAgentRunner(agentName, workDir.toString(), true, injectMcpTools,
                "", 0, systemPromptManager, renderer, ascii);
    }

    private static final class SilentTerminalRenderer extends TerminalRenderer {
        SilentTerminalRenderer() {
            super(false);
        }

        @Override
        public TerminalRenderer.SpinnerHandle startSpinner(String toolName) {
            return noOpSpinner();
        }

        @Override
        public TerminalRenderer.SpinnerHandle startStaticSpinner(String chainInfo) {
            return noOpSpinner();
        }

        @Override
        public TerminalRenderer.SpinnerHandle startGeneratingSpinner(String chainInfo) {
            return noOpSpinner();
        }

        @Override
        public TerminalRenderer.SpinnerHandle startPinnedSpinner(int row, String chainInfo) {
            return noOpSpinner();
        }

        @Override
        public void setTerminalTitle(String title) {
            // MCP stdio stdout is JSON-RPC; terminal OSC writes are not allowed here.
        }

        @Override
        public void resetTerminalTitle() {
            // No-op for MCP stdio.
        }
    }

    /**
     * Concatenate every {@code type=="text"} part of an opencode message
     * response, skipping reasoning/tool/step parts.
     */
    static String extractOpencodeText(ObjectMapper json, String responseBody) throws IOException {
        JsonNode root = json.readTree(responseBody);
        StringBuilder sb = new StringBuilder();
        collectOpencodeTextParts(root, sb);
        return sb.toString().trim();
    }

    private static void collectOpencodeTextParts(JsonNode node, StringBuilder sb) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode type = node.get("type");
            JsonNode text = node.get("text");
            if (type != null && "text".equals(type.asText()) && text != null && text.isTextual()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(text.asText());
            }
            node.forEach(child -> collectOpencodeTextParts(child, sb));
        } else if (node.isArray()) {
            node.forEach(child -> collectOpencodeTextParts(child, sb));
        }
    }

    private static boolean isRateLimited(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }

        String lower = output.toLowerCase();
        return lower.contains("rate limit") || lower.contains("rate_limit")
                || lower.contains("too many requests") || lower.contains("429 too many")
                || lower.contains("token limit") || lower.contains("token_limit")
                || lower.contains("quota exceeded") || lower.contains("resource_exhausted");
    }

    private RoleConfig resolveRole(String roleName) {
        if (roleManager != null) {
            return roleManager.getRole(roleName);
        }
        try {
            RoleManager fallbackManager = new RoleManager(workDir);
            return fallbackManager.getRole(roleName);
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveAssignedRole(String agentName) {
        if (roleManager != null) {
            return roleManager.getAgentRole(agentName);
        }
        try {
            RoleManager fallbackManager = new RoleManager(workDir);
            return fallbackManager.getAgentRole(agentName);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildRolePrompt(RoleConfig role) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Role: ").append(role.getDisplayName()).append("\n\n");
        sb.append(role.getSystemPrompt());
        if (role.getDescription() != null && !role.getDescription().isEmpty()) {
            sb.append("\n\n## Context\n");
            sb.append(role.getDescription());
        }
        return sb.toString();
    }

    Path renameResultFile(Path captureFile, String agentName, String header) {
        try {
            Path resultsDir = workDir.resolve(".kompile").resolve("task-results");
            Files.createDirectories(resultsDir);

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String sanitizedName = agentName.replaceAll("[^a-zA-Z0-9_-]", "_");
            Path resultFile = resultsDir.resolve(sanitizedName + "-" + timestamp + ".md");

            String headerBlock = "# Task Result: " + agentName + "\n\n" + header + "\n\n---\n\n";
            Path tempResult = resultFile.resolveSibling(resultFile.getFileName() + ".tmp");
            try (var writer = Files.newBufferedWriter(tempResult, StandardCharsets.UTF_8)) {
                writer.write(headerBlock);
                try (var reader = Files.newBufferedReader(captureFile, StandardCharsets.UTF_8)) {
                    char[] buf = new char[8192];
                    int n;
                    while ((n = reader.read(buf)) != -1) {
                        writer.write(buf, 0, n);
                    }
                }
            }
            Files.move(tempResult, resultFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(captureFile);
            return resultFile;
        } catch (IOException e) {
            System.err.println(RED + "  Warning: could not rename result file: " + e.getMessage() + RESET);
            return captureFile;
        }
    }

    String buildSummaryFromBuffers(String head, String tail, long totalChars) {
        if (totalChars <= SUMMARY_MAX_CHARS) {
            return head;
        }

        String[] headLines = head.split("\n");
        StringBuilder summary = new StringBuilder();

        int headLineCount = Math.min(20, headLines.length);
        int charCount = 0;
        for (int i = 0; i < headLineCount && charCount < SUMMARY_MAX_CHARS * 2 / 3; i++) {
            summary.append(headLines[i]).append("\n");
            charCount += headLines[i].length() + 1;
        }

        summary.append("\n... (").append(totalChars).append(" chars total) ...\n\n");

        String[] tailLines = tail.split("\n");
        int tailStart = Math.max(0, tailLines.length - 10);
        for (int i = tailStart; i < tailLines.length; i++) {
            summary.append(tailLines[i]).append("\n");
        }

        return summary.toString().trim();
    }
}
