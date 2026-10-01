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
import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.agent.ProcessStatus;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** The name this app's MCP server has in every agent configuration it writes. */
    private static final String MCP_SERVER_NAME = "kompile-app";

    /**
     * Gemini CLI's system settings file. System settings override the user's and the
     * workspace's, which every Gemini CLI session shares, so a launch gets its own.
     */
    static final String GEMINI_SYSTEM_SETTINGS_ENV = "GEMINI_CLI_SYSTEM_SETTINGS_PATH";

    /** Gemini CLI's system defaults file; unset, Gemini CLI looks beside the system settings. */
    static final String GEMINI_SYSTEM_DEFAULTS_ENV = "GEMINI_CLI_SYSTEM_DEFAULTS_PATH";

    /** JSON that OpenCode 1.x merges over every config file it reads. */
    static final String OPENCODE_CONFIG_CONTENT_ENV = "OPENCODE_CONFIG_CONTENT";

    /** Where the temporary configurations of scoped and isolated turns live. */
    static final String SCOPES_DIRECTORY = "agent-mcp-scopes";

    /** Where the system settings of Gemini CLI launches live. */
    static final String LAUNCH_DIRECTORY = "agent-mcp-launch";

    static final String SCOPE_FILE_PREFIX = "scope-";
    static final String GEMINI_LAUNCH_FILE_PREFIX = "gemini-";

    private static final Set<PosixFilePermission> OWNER_READ_WRITE = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    /**
     * Reads agent settings the way people write them (comments, trailing commas, single
     * quotes): an agent whose settings Kompile cannot read still needs Kompile's server.
     */
    private static final ObjectMapper SETTINGS_MAPPER = JsonUtils.newStandardMapper()
            .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature(),
                    JsonReadFeature.ALLOW_YAML_COMMENTS.mappedFeature(),
                    JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(),
                    JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(),
                    JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature(),
                    JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature());
    private static final ObjectReader SETTINGS_READER = SETTINGS_MAPPER.reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final AgentRegistryService agentRegistry;
    private final AgentProcessDiagnosticService diagnosticService;
    private final ClaudeStreamParser streamParser;
    private final Path configRoot;

    @Autowired(required = false)
    BuiltInToolDiscoveryService toolDiscoveryService;

    @Autowired(required = false)
    ServerPortService serverPortService;

    private final ExecutorService cleanupExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-subprocess-cleanup");
        t.setDaemon(true);
        return t;
    });

    // Track running processes by processId for interrupt support
    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();

    /**
     * A command and what its launch needs besides the command line: environment variables to
     * set over the agent's own, and a temporary MCP configuration to delete once the process
     * has exited. Close it then.
     */
    public static final class PreparedCommand implements AutoCloseable {
        private final List<String> command;
        private final Map<String, String> environment;
        private final Path ephemeralMcpConfig;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private PreparedCommand(List<String> command, Path ephemeralMcpConfig) {
            this(command, Map.of(), ephemeralMcpConfig);
        }

        private PreparedCommand(List<String> command, Map<String, String> environment,
                                Path ephemeralMcpConfig) {
            this.command = Collections.unmodifiableList(new ArrayList<>(command));
            this.environment = Map.copyOf(environment);
            this.ephemeralMcpConfig = ephemeralMcpConfig;
        }

        public List<String> command() {
            return command;
        }

        /** The variables the launch sets over {@link AgentProvider#safeEnvironment()}. */
        public Map<String, String> environment() {
            return environment;
        }

        /**
         * Set {@link #environment()} in a launch's environment. Call it after the agent's own
         * variables are in: a value here already carries the agent's forward, and the agent's
         * would otherwise replace it.
         */
        public void applyEnvironment(Map<String, String> target) {
            target.putAll(environment);
        }

        Path ephemeralMcpConfig() {
            return ephemeralMcpConfig;
        }

        public void deleteEphemeralMcpConfig() {
            close();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true) || ephemeralMcpConfig == null) {
                return;
            }
            try {
                Files.deleteIfExists(ephemeralMcpConfig);
            } catch (IOException failure) {
                log.warn("Could not delete temporary MCP configuration");
            }
        }
    }

    /** What a launch collects besides its command line while the command is built. */
    private static final class LaunchResources {
        private final Map<String, String> agentEnvironment;
        private final Map<String, String> environment = new LinkedHashMap<>();
        private Path file;

        private LaunchResources(AgentProvider agent) {
            this.agentEnvironment = agent.safeEnvironment();
        }

        /** {@code name}'s value in the launch before this app sets it: the agent's, else this process's. */
        private String inherited(String name) {
            return agentEnvironment.containsKey(name) ? agentEnvironment.get(name) : System.getenv(name);
        }

        private PreparedCommand toPreparedCommand(List<String> command) {
            return new PreparedCommand(command, environment, file);
        }

        /** Delete what the launch wrote, for a command that could not be built. */
        private void discard() {
            new PreparedCommand(List.of(), file).close();
        }
    }

    @Autowired
    public AgentSubprocessExecutor(AgentRegistryService agentRegistry,
                                    AgentProcessDiagnosticService diagnosticService,
                                    ClaudeStreamParser streamParser) {
        this(agentRegistry, diagnosticService, streamParser,
                Path.of(System.getProperty("user.home"), ".kompile", "config"));
    }

    AgentSubprocessExecutor(
            AgentRegistryService agentRegistry,
            AgentProcessDiagnosticService diagnosticService,
            ClaudeStreamParser streamParser,
            Path configRoot) {
        this.agentRegistry = agentRegistry;
        this.diagnosticService = diagnosticService;
        this.streamParser = streamParser;
        this.configRoot = Objects.requireNonNull(configRoot, "configRoot");
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
        return buildInteractiveCommand(agent, skipPermissions, injectMcpTools, agentArgs, null);
    }

    /**
     * Variant that allows an explicit {@code modelOverride} to be used as the {@code --model} value
     * (when non-blank), bypassing {@link AgentProvider#getModelName()}. This lets a warm process pool
     * pin individual pre-spawned processes to <em>different</em> models (per-spawn round-robin), which
     * is how free-model alternation is realised without a racy global model flip or pool invalidation.
     *
     * @param modelOverride explicit model id to pass via the agent's model flag; when null/blank the
     *                      agent's own configured model (or the CLI default) is used instead
     */
    public List<String> buildInteractiveCommand(AgentProvider agent, boolean skipPermissions, boolean injectMcpTools, List<String> agentArgs, String modelOverride) {
        return buildInteractiveCommand(agent, skipPermissions, injectMcpTools, agentArgs, modelOverride, null);
    }

    /**
     * Build the base interactive command. An agent that takes its MCP server only from its
     * environment or settings gets it through {@code launch} (see {@link #addMcpServerArgs});
     * a null {@code launch} is a bare command line, which gives such an agent none.
     */
    private List<String> buildInteractiveCommand(AgentProvider agent, boolean skipPermissions, boolean injectMcpTools,
                                                 List<String> agentArgs, String modelOverride, LaunchResources launch) {
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
        // An explicit per-call override wins (used for free-model alternation); otherwise the agent's
        // configured model; left unset, the CLI uses its own default model.
        String modelFlag = agent.getModelFlag();
        String modelName = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride.trim() : agent.getModelName();
        if (modelFlag != null && !modelFlag.isBlank() && modelName != null && !modelName.isBlank()) {
            command.add(modelFlag);
            command.add(modelName);
        }

        // Add MCP server configuration if agent supports it and tools are available
        if (injectMcpTools && agent.isMcpSupported() && toolDiscoveryService != null) {
            addMcpServerArgs(command, agent, launch);
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
     * <p>
     * A bare command line cannot give Gemini CLI or OpenCode an MCP server, since they take one
     * only from their environment; launch those through {@link #prepareCommand}.
     */
    public List<String> buildCommand(AgentProvider agent, boolean skipPermissions, boolean injectMcpTools,
                                      List<String> agentArgs, String prompt, String workingDirectory) {
        List<String> command = buildInteractiveCommand(agent, skipPermissions, injectMcpTools, agentArgs);

        return appendPrompt(command, agent, prompt, workingDirectory);
    }

    /**
     * {@link #buildInteractiveCommand(AgentProvider, boolean, boolean, List)} with what its launch
     * needs besides the command line. Set {@link PreparedCommand#applyEnvironment its environment}
     * over the agent's, and close it once the process has exited.
     */
    public PreparedCommand prepareInteractiveCommand(AgentProvider agent, boolean skipPermissions,
                                                     boolean injectMcpTools, List<String> agentArgs) {
        LaunchResources launch = new LaunchResources(agent);
        try {
            return launch.toPreparedCommand(buildInteractiveCommand(
                    agent, skipPermissions, injectMcpTools, agentArgs, null, launch));
        } catch (RuntimeException failure) {
            launch.discard();
            throw failure;
        }
    }

    /**
     * {@link #buildCommand} with what its launch needs besides the command line. Set
     * {@link PreparedCommand#applyEnvironment its environment} over the agent's, and close it
     * once the process has exited.
     */
    public PreparedCommand prepareCommand(AgentProvider agent, boolean skipPermissions, boolean injectMcpTools,
                                          List<String> agentArgs, String prompt, String workingDirectory) {
        LaunchResources launch = new LaunchResources(agent);
        try {
            List<String> command = buildInteractiveCommand(
                    agent, skipPermissions, injectMcpTools, agentArgs, null, launch);
            return launch.toPreparedCommand(appendPrompt(command, agent, prompt, workingDirectory));
        } catch (RuntimeException failure) {
            launch.discard();
            throw failure;
        }
    }

    /**
     * Build a one-shot command whose only injected MCP endpoint is the supplied bearer URL.
     * Caller-provided MCP flags are rejected so they cannot replace or augment the server binding.
     */
    public PreparedCommand buildScopedCommand(
            AgentProvider agent,
            boolean skipPermissions,
            List<String> agentArgs,
            String prompt,
            String workingDirectory,
            String scopedMcpSseUrl) {
        if (!supportsScopedMcpIsolation(agent)) {
            throw new IllegalArgumentException(
                    "Agent does not expose verified strict MCP configuration isolation");
        }
        validateScopedAgentArgs(agent, agentArgs);

        if (isCodexAgent(agent)) {
            validateMcpUrl(scopedMcpSseUrl, true);
            List<String> command = buildInteractiveCommand(agent, skipPermissions, false, null);
            command.add("-c");
            command.add("mcp_servers.kompile_private_graph.url=\"" + scopedMcpSseUrl + "\"");
            if (agentArgs != null && !agentArgs.isEmpty()) {
                command.addAll(agentArgs);
            }
            return new PreparedCommand(appendCodexPrompt(command, prompt, true), null);
        }

        List<String> command = buildInteractiveCommand(
                agent, skipPermissions, false, null);
        Path config = null;
        try {
            config = addMcpServerArgs(command, agent, scopedMcpSseUrl, true);
            if (config == null) {
                throw new IllegalStateException("Could not create temporary scoped MCP configuration");
            }
            command.add("--strict-mcp-config");
            if (agentArgs != null && !agentArgs.isEmpty()) {
                command.addAll(agentArgs);
            }
            return new PreparedCommand(
                    appendPrompt(command, agent, prompt, workingDirectory), config);
        } catch (RuntimeException failure) {
            if (config != null) {
                new PreparedCommand(List.of(), config).close();
            }
            throw failure;
        }
    }

    /** Build a strict empty-MCP command for provisioned turns that disabled tool injection. */
    public PreparedCommand buildIsolatedCommand(
            AgentProvider agent,
            boolean skipPermissions,
            List<String> agentArgs,
            String prompt,
            String workingDirectory) {
        if (!supportsScopedMcpIsolation(agent)) {
            throw new IllegalArgumentException(
                    "Agent does not expose verified strict MCP configuration isolation");
        }
        validateScopedAgentArgs(agent, agentArgs);
        if (isCodexAgent(agent)) {
            List<String> command = buildInteractiveCommand(agent, skipPermissions, false, null);
            if (agentArgs != null && !agentArgs.isEmpty()) {
                command.addAll(agentArgs);
            }
            return new PreparedCommand(appendCodexPrompt(command, prompt, true), null);
        }
        List<String> command = buildInteractiveCommand(agent, skipPermissions, false, null);
        Path config = writeTemporaryMcpConfigFile(null);
        try {
            if (config == null) {
                throw new IllegalStateException("Could not create temporary isolated MCP configuration");
            }
            command.add(agent.getMcpConfigFlag());
            command.add(config.toString());
            command.add("--strict-mcp-config");
            if (agentArgs != null && !agentArgs.isEmpty()) {
                command.addAll(agentArgs);
            }
            return new PreparedCommand(
                    appendPrompt(command, agent, prompt, workingDirectory), config);
        } catch (RuntimeException failure) {
            if (config != null) {
                new PreparedCommand(List.of(), config).close();
            }
            throw failure;
        }
    }

    /**
     * Whether {@link #prepareCommand} can give the agent this app's MCP server: through a flag
     * its help advertises, Codex's {@code -c} overrides, Gemini CLI's system settings, or
     * OpenCode's {@value #OPENCODE_CONFIG_CONTENT_ENV}.
     */
    public boolean supportsMcpInjection(AgentProvider agent) {
        return agent != null && agent.isMcpSupported()
                && (agent.getMcpConfigFlag() != null || agent.getMcpServerFlag() != null
                || isCodexAgent(agent) || isGeminiAgent(agent) || isOpenCodeAgent(agent));
    }

    public boolean supportsScopedMcpIsolation(AgentProvider agent) {
        if (agent == null || !agent.isMcpSupported() || agent.getHelpOutput() == null) {
            return false;
        }
        if (isCodexAgent(agent)) {
            return agent.getHelpOutput().contains("--ignore-user-config")
                    && agent.getHelpOutput().contains("--config");
        }
        return agent.getMcpConfigFlag() != null
                && agent.getHelpOutput().contains("--strict-mcp-config");
    }

    private List<String> appendPrompt(
            List<String> command,
            AgentProvider agent,
            String prompt,
            String workingDirectory) {

        if (isCodexAgent(agent)) {
            return appendCodexPrompt(command, prompt, false);
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

    private List<String> appendCodexPrompt(
            List<String> command,
            String prompt,
            boolean isolated) {
        command.add("exec");
        if (isolated) {
            command.add("--ignore-user-config");
            command.add("--ephemeral");
        }
        command.add("--json");
        command.add(prompt);
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
        PreparedCommand prepared = null;

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

            prepared = prepareCommand(agent, skipPermissions, injectMcpTools, null, prompt, workingDirectory);
            List<String> command = prepared.command();

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
            prepared.applyEnvironment(pb.environment());

            Process process = pb.start();
            runningProcesses.put(processId, process);
            diagnosticService.processStarted(processId, process.pid());
            closeProcessStdin(process, agent.getName(), processId);

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
        } finally {
            if (prepared != null) {
                prepared.close();
            }
        }
    }

    private void closeProcessStdin(Process process, String agentName, String processId) {
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            log.debug("Could not close stdin for agent process '{}' ({}): {}",
                    agentName, processId, e.getMessage());
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

    /**
     * Give the agent this app's MCP server the way it takes one: a config-file or server flag
     * its help advertises, Codex's {@code -c} overrides, Gemini CLI's system settings, or
     * OpenCode's {@value #OPENCODE_CONFIG_CONTENT_ENV}. The last two live in the launch's
     * environment, which a bare command line ({@code launch == null}) cannot carry.
     */
    private void addMcpServerArgs(List<String> command, AgentProvider agent, LaunchResources launch) {
        if (toolDiscoveryService == null || toolDiscoveryService.getDiscoveredTools().isEmpty()) {
            return;
        }

        String mcpSseUrl = serverPortService != null ? serverPortService.getMcpSseUrl() : null;
        if (mcpSseUrl == null) {
            return;
        }

        if (agent.getMcpConfigFlag() != null || agent.getMcpServerFlag() != null) {
            addMcpServerArgs(command, agent, mcpSseUrl, false);
            return;
        }
        validateMcpUrl(mcpSseUrl, false);
        if (isCodexAgent(agent)) {
            // Codex has no MCP flag; a config override adds the server to this run alone.
            command.add("-c");
            command.add("mcp_servers." + MCP_SERVER_NAME + ".url=\"" + mcpSseUrl + "\"");
            log.info("Injecting MCP server for agent '{}' through a config override (server: {})",
                    agent.getName(), mcpSseUrl);
        } else if (isGeminiAgent(agent) || isOpenCodeAgent(agent)) {
            if (launch == null) {
                log.warn("Agent '{}' takes an MCP server only from its launch environment, which a bare "
                        + "command line cannot carry, so it runs without Kompile's tools", agent.getName());
            } else if (isGeminiAgent(agent)) {
                addGeminiSystemSettings(agent, launch, mcpSseUrl);
            } else {
                addOpenCodeConfigContent(agent, launch, mcpSseUrl);
            }
        } else {
            log.warn("Agent '{}' advertises no MCP flag and takes no MCP server any other way Kompile "
                    + "knows, so it runs without Kompile's tools", agent.getName());
        }
    }

    /**
     * Give a Gemini CLI launch this app's server through its system settings: Gemini CLI takes
     * no MCP server on its command line, and its user and workspace settings are shared by every
     * session. The launch's file stands in for the system settings it would have read (the
     * agent's {@value #GEMINI_SYSTEM_SETTINGS_ENV}, this process's, or the platform's), so it
     * keeps those settings with this app's server added, and the system defaults stay where
     * Gemini CLI would have looked for them. System settings that do not parse would stop
     * Gemini CLI from starting; the launch's hold this app's server alone instead.
     */
    private void addGeminiSystemSettings(AgentProvider agent, LaunchResources launch, String mcpSseUrl) {
        String configured = launch.inherited(GEMINI_SYSTEM_SETTINGS_ENV);
        Path systemSettings = configured != null && !configured.isBlank()
                ? pathOrNull(configured) : geminiDefaultSystemSettings();
        ObjectNode settings = null;
        if (systemSettings == null) {
            log.warn("{} for agent '{}' is not a path, so its system settings hold only Kompile's MCP server",
                    GEMINI_SYSTEM_SETTINGS_ENV, agent.getName());
        } else if (Files.exists(systemSettings)) {
            settings = readSettingsOrNull(systemSettings);
            if (settings == null) {
                log.warn("Gemini CLI's system settings {} are not a JSON object Kompile can read, so agent "
                        + "'{}' gets system settings holding only Kompile's MCP server",
                        systemSettings, agent.getName());
            }
        }
        if (settings == null) {
            settings = SETTINGS_MAPPER.createObjectNode();
        }
        JsonNode servers = settings.get("mcpServers");
        ObjectNode mcpServers = servers != null && servers.isObject()
                ? (ObjectNode) servers : settings.putObject("mcpServers");
        mcpServers.putObject(MCP_SERVER_NAME).put("url", mcpSseUrl).put("type", "sse");

        Path file;
        try {
            file = writeLaunchFile(configRoot.resolve(LAUNCH_DIRECTORY), GEMINI_LAUNCH_FILE_PREFIX,
                    SETTINGS_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(settings));
        } catch (IOException failure) {
            file = null;
        }
        if (file == null) {
            log.warn("Could not write Gemini CLI system settings for agent '{}', so it runs without "
                    + "Kompile's tools", agent.getName());
            return;
        }
        launch.file = file;
        launch.environment.put(GEMINI_SYSTEM_SETTINGS_ENV, file.toString());
        String defaults = launch.inherited(GEMINI_SYSTEM_DEFAULTS_ENV);
        if ((defaults == null || defaults.isBlank()) && systemSettings != null) {
            Path directory = systemSettings.getParent();
            launch.environment.put(GEMINI_SYSTEM_DEFAULTS_ENV, (directory != null
                    ? directory.resolve("system-defaults.json") : Path.of("system-defaults.json")).toString());
        }
        log.info("Injecting MCP server for agent '{}' through Gemini CLI system settings {} (server: {})",
                agent.getName(), file, mcpSseUrl);
    }

    /**
     * Give an OpenCode launch this app's server through {@value #OPENCODE_CONFIG_CONTENT_ENV},
     * which OpenCode 1.x merges over every config file it reads, keeping whatever the variable
     * already holds (the agent's, else this process's). A value that is not a JSON object would
     * stop OpenCode from starting, so it is replaced.
     */
    private void addOpenCodeConfigContent(AgentProvider agent, LaunchResources launch, String mcpSseUrl) {
        String current = launch.inherited(OPENCODE_CONFIG_CONTENT_ENV);
        ObjectNode content = null;
        if (current != null && !current.isBlank()) {
            content = settingsObjectOrNull(current);
            if (content == null) {
                log.warn("Replacing {} for agent '{}', which is not a JSON object",
                        OPENCODE_CONFIG_CONTENT_ENV, agent.getName());
            }
        }
        if (content == null) {
            content = SETTINGS_MAPPER.createObjectNode();
        }
        JsonNode mcp = content.get("mcp");
        ObjectNode servers = mcp != null && mcp.isObject() ? (ObjectNode) mcp : content.putObject("mcp");
        servers.putObject(MCP_SERVER_NAME)
                .put("enabled", true)
                .put("type", "remote")
                .put("url", mcpSseUrl);
        try {
            launch.environment.put(OPENCODE_CONFIG_CONTENT_ENV, SETTINGS_MAPPER.writeValueAsString(content));
        } catch (IOException failure) {
            log.warn("Could not write {} for agent '{}', so it runs without Kompile's tools: {}",
                    OPENCODE_CONFIG_CONTENT_ENV, agent.getName(), failure.getMessage());
            return;
        }
        log.info("Injecting MCP server for agent '{}' through {} (server: {})",
                agent.getName(), OPENCODE_CONFIG_CONTENT_ENV, mcpSseUrl);
    }

    /** Where Gemini CLI reads its system settings when {@value #GEMINI_SYSTEM_SETTINGS_ENV} is unset. */
    static Path geminiDefaultSystemSettings() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("mac")) {
            return Path.of("/Library/Application Support/GeminiCli/settings.json");
        }
        if (os.startsWith("windows")) {
            return Path.of("C:\\ProgramData\\gemini-cli\\settings.json");
        }
        return Path.of("/etc/gemini-cli/settings.json");
    }

    private static Path pathOrNull(String value) {
        try {
            return Path.of(value);
        } catch (InvalidPathException invalid) {
            return null;
        }
    }

    /**
     * The JSON object a settings file holds, read leniently; an empty file holds an empty one.
     * Null when the file cannot be read, does not parse, or holds another kind of value.
     */
    static ObjectNode readSettingsOrNull(Path file) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException | RuntimeException unreadable) {
            log.warn("Could not read {}: {}", file, unreadable.getMessage());
            return null;
        }
        try {
            JsonNode parsed = SETTINGS_READER.readTree(bytes);
            if (parsed == null || parsed.isMissingNode()) {
                return SETTINGS_MAPPER.createObjectNode();
            }
            return parsed.isObject() ? (ObjectNode) parsed : null;
        } catch (IOException | RuntimeException notJson) {
            return null;
        }
    }

    /** The JSON object {@code text} holds, read leniently; null when it holds anything else. */
    static ObjectNode settingsObjectOrNull(String text) {
        try {
            JsonNode parsed = SETTINGS_READER.readTree(text);
            return parsed != null && parsed.isObject() ? (ObjectNode) parsed : null;
        } catch (IOException | RuntimeException notJson) {
            return null;
        }
    }

    private Path addMcpServerArgs(
            List<String> command,
            AgentProvider agent,
            String mcpSseUrl,
            boolean ephemeral) {
        validateMcpUrl(mcpSseUrl, ephemeral);

        // Config-file injection is the mechanism real CLIs actually support (claude's
        // --mcp-config takes a JSON file/string; it has no --mcp-server option), so it
        // wins over the name:url pair form, which is kept as a fallback for CLIs that
        // genuinely advertise a server flag.
        if (agent.getMcpConfigFlag() != null) {
            Path configFile = ephemeral
                    ? writeScopedMcpConfigFile(mcpSseUrl)
                    : writeMcpConfigFile(mcpSseUrl);
            if (configFile != null) {
                String flag = agent.getMcpConfigFlag();
                // One token: Claude's --mcp-config takes any number of values, so a path given
                // apart would also take a positional argument that follows it. The scoped form
                // keeps two tokens because --strict-mcp-config always follows it.
                List<String> tokens = !ephemeral && flag.startsWith("--")
                        ? List.of(flag + "=" + configFile)
                        : List.of(flag, configFile.toString());
                command.addAll(tokens);
                if (ephemeral) {
                    log.info("Injecting temporary scoped MCP config for agent '{}'", agent.getName());
                } else {
                    log.info("Injecting MCP config for agent '{}': {} (server: {})",
                            agent.getName(), String.join(" ", tokens), mcpSseUrl);
                }
            }
            return configFile;
        } else if (agent.getMcpServerFlag() != null) {
            command.add(agent.getMcpServerFlag());
            command.add("kompile-app:" + mcpSseUrl);
            if (ephemeral) {
                log.info("Injecting scoped MCP server for agent '{}' (URL redacted)", agent.getName());
            } else {
                log.info("Injecting MCP server for agent '{}': {} kompile-app:{}",
                        agent.getName(), agent.getMcpServerFlag(), mcpSseUrl);
            }
        }
        return null;
    }

    /**
     * Writes the MCP config file handed to spawned CLI agents (claude-compatible
     * {@code {"mcpServers":{...}}} shape) pointing at this app's live MCP SSE server.
     * Each port has its own file, since the admin, chat and crawl processes all spawn agents
     * and each points them at its own server. A file is rewritten only when its URL changes,
     * and replaced whole, so an agent starting meanwhile never reads half of one.
     */
    private Path writeMcpConfigFile(String sseUrl) {
        try {
            Files.createDirectories(configRoot);
            Path file = configRoot.resolve(mcpConfigFileName(sseUrl));
            String json = "{\n"
                    + "  \"mcpServers\": {\n"
                    + "    \"" + MCP_SERVER_NAME + "\": {\n"
                    + "      \"type\": \"sse\",\n"
                    + "      \"url\": \"" + sseUrl + "\"\n"
                    + "    }\n"
                    + "  }\n"
                    + "}\n";
            if (!json.equals(readIfRegularFile(file))) {
                writeAtomically(file, json);
            }
            return file;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not write agent MCP config file, skipping MCP injection: {}", e.getMessage());
            return null;
        }
    }

    /** The name of the file {@link #writeMcpConfigFile} writes for the server at {@code sseUrl}. */
    static String mcpConfigFileName(String sseUrl) {
        int port = -1;
        try {
            port = URI.create(sseUrl).getPort();
        } catch (IllegalArgumentException invalid) {
            // validateMcpUrl has rejected it already; the unnumbered name is as good as any.
        }
        return port > 0 ? "agent-mcp-config-" + port + ".json" : "agent-mcp-config.json";
    }

    /** A file's text, or null when it is missing, not a regular file, or cannot be read. */
    private static String readIfRegularFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            return null;
        }
    }

    /** Replace {@code file} with {@code content} in one step where the file system can. */
    private static void writeAtomically(Path file, String content) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), "." + file.getFileName() + ".", ".tmp");
        try {
            Files.writeString(temp, content);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private Path writeScopedMcpConfigFile(String sseUrl) {
        return writeTemporaryMcpConfigFile(sseUrl);
    }

    private Path writeTemporaryMcpConfigFile(String sseUrl) {
        String json;
        if (sseUrl == null) {
            json = "{\n  \"mcpServers\": {}\n}\n";
        } else {
            json = "{\n"
                    + "  \"mcpServers\": {\n"
                    + "    \"kompile-private-graph\": {\n"
                    + "      \"type\": \"sse\",\n"
                    + "      \"url\": \"" + sseUrl + "\"\n"
                    + "    }\n"
                    + "  }\n"
                    + "}\n";
        }
        return writeLaunchFile(configRoot.resolve(SCOPES_DIRECTORY), SCOPE_FILE_PREFIX, json);
    }

    /**
     * Write {@code content} to a new owner-only file in {@code directory} whose name records this
     * process's pid after {@code prefix}, first deleting the files of processes that have exited:
     * a process that is killed never closes its launches.
     *
     * @return the file, or null when it could not be written
     */
    private static Path writeLaunchFile(Path directory, String prefix, String content) {
        Path file = null;
        try {
            Files.createDirectories(directory);
            restrictPermissions(directory, OWNER_DIRECTORY);
            sweepLaunchFiles(directory, prefix);
            String filePrefix = prefix + ProcessHandle.current().pid() + "-";
            file = Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null
                    ? Files.createTempFile(directory, filePrefix, ".json",
                    PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE))
                    : Files.createTempFile(directory, filePrefix, ".json");
            Files.writeString(file, content);
            restrictPermissions(file, OWNER_READ_WRITE);
            return file;
        } catch (IOException | RuntimeException failure) {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // Best effort after a failed create/write.
                }
            }
            log.warn("Could not write temporary MCP configuration: {}", failure.getMessage());
            return null;
        }
    }

    /** Delete the {@code prefix} files in {@code directory} whose writer has exited. */
    static void sweepLaunchFiles(Path directory, String prefix) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, prefix + "*.json")) {
            for (Path file : files) {
                long owner = launchFileOwner(file, prefix);
                if (owner <= 0 || ProcessHandle.of(owner).map(ProcessHandle::isAlive).orElse(false)) {
                    continue;
                }
                try {
                    Files.deleteIfExists(file);
                } catch (IOException failure) {
                    log.debug("Could not delete stale MCP configuration {}: {}",
                            file.getFileName(), failure.getMessage());
                }
            }
        } catch (IOException | RuntimeException failure) {
            log.debug("Could not sweep MCP configurations in {}: {}", directory, failure.getMessage());
        }
    }

    /** The pid a launch file's name records after {@code prefix}, or -1 when it records none. */
    static long launchFileOwner(Path file, String prefix) {
        String name = file.getFileName().toString();
        if (!name.startsWith(prefix)) {
            return -1;
        }
        String rest = name.substring(prefix.length());
        int end = rest.indexOf('-');
        if (end <= 0) {
            return -1;
        }
        try {
            return Long.parseLong(rest.substring(0, end));
        } catch (NumberFormatException notPid) {
            return -1;
        }
    }

    private static void restrictPermissions(Path path, Set<PosixFilePermission> permissions)
            throws IOException {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX platforms rely on the user's private Kompile home permissions.
        }
    }

    private static void validateMcpUrl(String value, boolean scoped) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid MCP endpoint URL", invalid);
        }
        String host = uri.getHost();
        boolean loopback = host != null && ("localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host) || "::1".equals(host));
        if (!"http".equalsIgnoreCase(uri.getScheme()) || !loopback
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("MCP endpoint must be an HTTP loopback URL");
        }
        if (scoped && (uri.getPath() == null || !uri.getPath().startsWith("/mcp/scoped/")
                || !uri.getPath().endsWith("/sse"))) {
            throw new IllegalArgumentException("Scoped MCP endpoint path is invalid");
        }
    }

    private void validateScopedAgentArgs(AgentProvider agent, List<String> agentArgs) {
        if (agentArgs == null) {
            return;
        }
        for (String argument : agentArgs) {
            String normalized = argument == null ? "" : argument.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("--mcp") || normalized.startsWith("-mcp")
                    || normalized.startsWith("--strict-mcp-config")
                    || (isCodexAgent(agent) && (normalized.equals("-c")
                    || normalized.startsWith("--config")
                    || normalized.equals("-p")
                    || normalized.startsWith("--profile")
                    || normalized.startsWith("--ignore-user-config")))) {
                throw new IllegalArgumentException(
                        "Provisioned-agent CLI arguments may not override scoped MCP configuration");
            }
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

    /** Gemini CLI itself, not Antigravity ({@code agy}), which keeps its MCP servers elsewhere. */
    private boolean isGeminiAgent(AgentProvider agent) {
        if (agent == null || agent.getName() == null) {
            return false;
        }
        String name = agent.getName().toLowerCase(Locale.ROOT);
        String command = agent.getCommand() != null ? agent.getCommand().toLowerCase(Locale.ROOT) : "";
        boolean antigravity = name.contains("agy") || name.contains("antigravity")
                || command.contains("agy") || command.contains("antigravity");
        return !antigravity && (name.contains("gemini") || command.contains("gemini"));
    }

    private boolean isOpenCodeAgent(AgentProvider agent) {
        if (agent == null || agent.getName() == null) {
            return false;
        }
        String name = agent.getName().toLowerCase(Locale.ROOT);
        String command = agent.getCommand() != null ? agent.getCommand().toLowerCase(Locale.ROOT) : "";
        return name.contains("opencode") || command.contains("opencode");
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

    @PreDestroy
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
