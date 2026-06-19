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

package ai.kompile.app.services.agent;

import ai.kompile.app.services.ServerPortService;
import ai.kompile.app.services.mcp.BuiltInToolDiscoveryService;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.ProcessStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * Low-level service for building CLI agent commands and executing agent subprocesses.
 * <p>
 * This is the single source of truth for CLI agent subprocess invocation.
 * Both {@link AgentChatService} (RAG-augmented chat) and {@link CliAgentLLMChat}
 * (Spring AI LLMChat adapter) delegate here for the actual subprocess execution.
 * <p>
 * Has no dependency on LLMChat, GraphRag, or VectorStore — only on agent registry,
 * stream parser, diagnostics, and MCP tool discovery. This avoids circular dependency
 * cycles in the Spring context.
 */
@Service
public class AgentSubprocessExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentSubprocessExecutor.class);

    private final AgentRegistryService agentRegistry;
    private final AgentProcessDiagnosticService diagnosticService;
    private final ClaudeStreamParser streamParser;

    @Autowired(required = false)
    private BuiltInToolDiscoveryService toolDiscoveryService;

    @Autowired(required = false)
    private ServerPortService serverPortService;

    private final ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-subprocess-cleanup");
        t.setDaemon(true);
        return t;
    });

    // Track running processes by processId for interrupt support
    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();

    public AgentSubprocessExecutor(AgentRegistryService agentRegistry,
                                    AgentProcessDiagnosticService diagnosticService,
                                    ClaudeStreamParser streamParser) {
        this.agentRegistry = agentRegistry;
        this.diagnosticService = diagnosticService;
        this.streamParser = streamParser;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COMMAND BUILDING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Build the base interactive command for the agent (without -p prompt).
     * Used by both one-shot and passthrough interactive modes.
     */
    public List<String> buildInteractiveCommand(AgentProvider agent, boolean skipPermissions, boolean injectMcpTools) {
        return buildInteractiveCommand(agent, skipPermissions, injectMcpTools, null);
    }

    /**
     * Build the base interactive command for the agent (without -p prompt).
     * Used by both one-shot and passthrough interactive modes.
     *
     * @param agent           the agent provider
     * @param skipPermissions whether to add the skip-permissions flag
     * @param injectMcpTools  whether to inject MCP server args
     * @param agentArgs       optional extra CLI arguments to pass through to the agent
     */
    public List<String> buildInteractiveCommand(AgentProvider agent, boolean skipPermissions, boolean injectMcpTools, List<String> agentArgs) {
        List<String> command = new ArrayList<>();
        command.add(agent.getCommand());

        // Add agent-specific args FIRST — these may include subcommands (e.g. "run" for opencode)
        // that must appear immediately after the main command
        command.addAll(agent.safeArgs());

        // Add skip permissions flag after subcommand/args
        if (skipPermissions && agent.getSkipPermissionsFlag() != null) {
            command.add(agent.getSkipPermissionsFlag());
        }

        // Add model selection if a model is configured for this agent (e.g. "--model <model>").
        // Left unset, the CLI uses its own default model.
        String modelFlag = agent.getModelFlag();
        String modelName = agent.getModelName();
        if (modelFlag != null && !modelFlag.isBlank() && modelName != null && !modelName.isBlank()) {
            command.add(modelFlag);
            command.add(modelName);
        }

        // Add MCP server configuration if agent supports it and tools are available
        if (injectMcpTools && agent.isMcpSupported() && toolDiscoveryService != null) {
            addMcpServerArgs(command, agent);
        }

        // Add caller-supplied pass-through args
        if (agentArgs != null && !agentArgs.isEmpty()) {
            command.addAll(agentArgs);
        }

        return command;
    }

    /**
     * Build the full CLI command including the prompt.
     * Handles Codex exec, Gemini prompt files, and standard -p prompt.
     */
    public List<String> buildCommand(AgentProvider agent, boolean skipPermissions, boolean injectMcpTools,
                                      List<String> agentArgs, String prompt, String workingDirectory) {
        List<String> command = buildInteractiveCommand(agent, skipPermissions, injectMcpTools, agentArgs);

        if (isCodexAgent(agent)) {
            command.add("exec");
            command.add("--json");
            command.add(prompt);
            return command;
        }

        // Add the prompt - handle Gemini's workspace restrictions
        command.add("-p");

        if (isAgyAgent(agent)) {
            try {
                String promptFilePath = createAgyPromptFile(workingDirectory, prompt);
                command.add("@" + promptFilePath);
                log.debug("Created Agy prompt file at: {}", promptFilePath);
            } catch (IOException e) {
                log.warn("Failed to create Agy prompt file, using inline prompt: {}", e.getMessage());
                command.add(prompt);
            }
        } else {
            command.add(prompt);
        }

        return command;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SUBPROCESS EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Result of a synchronous subprocess execution.
     */
    public record SubprocessResult(
            String content,
            String processId,
            int exitCode,
            long durationMs,
            List<String> modifiedFiles,
            Map<String, Object> stats,
            String error
    ) {
        public boolean isSuccess() {
            return error == null && exitCode == 0;
        }
    }

    /**
     * Execute a CLI agent subprocess synchronously with the given prompt.
     * Handles command building, process lifecycle, stream-json parsing, and timeouts.
     *
     * @param agentName      agent name from the registry
     * @param prompt         the prompt (already assembled — no RAG augmentation here)
     * @param skipPermissions whether to add skip-permissions flag
     * @param injectMcpTools whether to inject MCP server args
     * @param workingDirectory optional working directory for the subprocess
     * @param timeoutSeconds timeout (0 = no timeout)
     * @return subprocess result with content and metadata
     */
    public SubprocessResult executeSync(String agentName, String prompt, boolean skipPermissions,
                                         boolean injectMcpTools, String workingDirectory,
                                         int timeoutSeconds) {
        long startTime = System.currentTimeMillis();

        try {
            Optional<AgentProvider> agentOpt = agentRegistry.getAgent(agentName);
            if (agentOpt.isEmpty()) {
                return new SubprocessResult("", null, -1, 0, List.of(), null,
                        "Agent not found: " + agentName);
            }

            AgentProvider agent = agentOpt.get();
            if (!agent.isAvailable()) {
                return new SubprocessResult("", null, -1, 0, List.of(), null,
                        "Agent not available: " + agent.getDisplayName());
            }

            if (agent.isApiAgent()) {
                return new SubprocessResult("", null, -1, 0, List.of(), null,
                        "API agents are not supported for subprocess execution. Use CLI agents.");
            }

            List<String> command = buildCommand(agent, skipPermissions, injectMcpTools, null, prompt, workingDirectory);

            ProcessStatus processStatus = diagnosticService.startProcess(agent.getName(), command);
            String processId = processStatus.getId();

            log.info("Executing synchronous agent command: {} (processId: {})", agent.getCommand(), processId);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            if (workingDirectory != null && !workingDirectory.isEmpty()) {
                File workDir = new File(workingDirectory);
                if (workDir.isDirectory()) {
                    pb.directory(workDir);
                }
            }

            pb.environment().putAll(agent.safeEnvironment());

            Process process = pb.start();
            runningProcesses.put(processId, process);
            diagnosticService.processStarted(processId, process.pid());

            StringBuilder fullResponse = new StringBuilder();
            Map<String, Object> chatStats = new LinkedHashMap<>();
            boolean useStreamParser = streamParser.supportsStreamJson(agent.getName());

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                diagnosticService.processStreaming(processId);
                String line;

                while ((line = reader.readLine()) != null) {
                    if (!runningProcesses.containsKey(processId)) {
                        break; // Cancelled
                    }

                    diagnosticService.outputReceived(processId, line);

                    if (useStreamParser) {
                        ClaudeStreamParser.ParseResult result = streamParser.parseLine(processId, line);
                        if (result != null) {
                            if (result.textContent() != null && !result.textContent().isEmpty()) {
                                fullResponse.append(result.textContent());
                            }
                            if (result.isResult()) {
                                chatStats.put("durationMs", result.durationMs() != null ? result.durationMs() : 0);
                                chatStats.put("costUsd", result.costUsd() != null ? result.costUsd() : 0.0);
                                chatStats.put("numTurns", result.numTurns() != null ? result.numTurns() : 0);
                                chatStats.put("isError", result.isError());
                                Map<String, Object> tokenMetrics = streamParser.getTokenMetrics(processId);
                                if (tokenMetrics != null) {
                                    chatStats.put("tokenMetrics", tokenMetrics);
                                }
                            }
                        }
                    } else {
                        fullResponse.append(line).append("\n");
                    }
                }

                boolean completed;
                if (timeoutSeconds <= 0) {
                    // Default to 1 hour max to prevent indefinite blocking
                    completed = process.waitFor(3600, TimeUnit.SECONDS);
                } else {
                    completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                }

                if (!completed) {
                    process.destroyForcibly();
                    diagnosticService.processTimedOut(processId);
                    return new SubprocessResult(fullResponse.toString(), processId, -1,
                            System.currentTimeMillis() - startTime, List.of(),
                            chatStats, "Process timed out after " + timeoutSeconds + "s");
                }

                int exitCode = process.exitValue();
                diagnosticService.processCompleted(processId, exitCode);

                List<String> modifiedFiles = new ArrayList<>();
                Object mf = streamParser.getModifiedFiles(processId);
                if (mf instanceof List<?> list) {
                    for (Object item : list) {
                        modifiedFiles.add(item.toString());
                    }
                }

                streamParser.clearSession(processId);
                streamParser.clearModifiedFiles(processId);

                long duration = System.currentTimeMillis() - startTime;

                if (exitCode != 0) {
                    return new SubprocessResult(fullResponse.toString(), processId, exitCode,
                            duration, modifiedFiles, chatStats,
                            "Process exited with code: " + exitCode);
                }

                return new SubprocessResult(fullResponse.toString(), processId, exitCode,
                        duration, modifiedFiles, chatStats, null);

            } finally {
                runningProcesses.remove(processId);
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }

        } catch (Exception e) {
            log.error("Error in synchronous agent subprocess execution", e);
            return new SubprocessResult("", null, -1,
                    System.currentTimeMillis() - startTime, List.of(), null,
                    "Execution error: " + e.getMessage());
        }
    }

    /**
     * Cancel a running subprocess by processId.
     */
    public boolean cancelProcess(String processId) {
        Process process = runningProcesses.remove(processId);
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private void addMcpServerArgs(List<String> command, AgentProvider agent) {
        if (toolDiscoveryService == null || toolDiscoveryService.getDiscoveredTools().isEmpty()) {
            return;
        }

        String mcpServerUrl = serverPortService != null ? serverPortService.getMcpApiUrl() : null;
        if (mcpServerUrl == null) {
            return;
        }

        if (agent.getMcpServerFlag() != null) {
            command.add(agent.getMcpServerFlag());
            command.add("kompile-rag:" + mcpServerUrl);
            log.info("Injecting MCP server for agent '{}': {} kompile-rag:{}",
                    agent.getName(), agent.getMcpServerFlag(), mcpServerUrl);
        } else if (agent.getMcpConfigFlag() != null) {
            log.debug("Agent '{}' requires config file for MCP - skipping auto-injection",
                    agent.getName());
        }
    }

    private boolean isCodexAgent(AgentProvider agent) {
        if (agent == null || agent.getName() == null) {
            return false;
        }
        String name = agent.getName().toLowerCase();
        String command = agent.getCommand() != null ? agent.getCommand().toLowerCase() : "";
        return name.contains("codex") || command.contains("codex");
    }

    private boolean isAgyAgent(AgentProvider agent) {
        if (agent == null || agent.getName() == null) {
            return false;
        }
        String name = agent.getName().toLowerCase();
        String command = agent.getCommand() != null ? agent.getCommand().toLowerCase() : "";
        return name.contains("gemini") || name.contains("agy") || name.contains("antigravity") || command.contains("gemini") || command.contains("agy") || command.contains("antigravity");
    }

    private String createAgyPromptFile(String workingDirectory, String prompt) throws IOException {
        Path promptDir;
        if (workingDirectory != null && !workingDirectory.isEmpty()) {
            promptDir = Path.of(workingDirectory, ".agy", "tmp");
        } else {
            throw new IllegalStateException("workingDirectory is required for Agy agents but was not provided");
        }

        Files.createDirectories(promptDir);

        String filename = "agent-prompt-" + System.currentTimeMillis() + "-" +
                UUID.randomUUID().toString().substring(0, 8) + ".txt";
        Path promptFile = promptDir.resolve(filename);
        Files.writeString(promptFile, prompt);

        schedulePromptFileCleanup(promptFile);
        return promptFile.toAbsolutePath().toString();
    }

    private void schedulePromptFileCleanup(Path promptFile) {
        cleanupExecutor.submit(() -> {
            try {
                Thread.sleep(5 * 60 * 1000);
                if (Files.exists(promptFile)) {
                    Files.delete(promptFile);
                    log.debug("Cleaned up prompt file: {}", promptFile);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                log.debug("Failed to cleanup prompt file: {}", e.getMessage());
            }
        });
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
