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

import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.crawl.graph.AgentCallContext;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.converter.StructuredOutputConverter;
import org.springframework.ai.template.TemplateRenderer;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import reactor.core.publisher.Flux;

import jakarta.annotation.PreDestroy;

import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * LLMChat implementation that uses locally installed CLI agents (Claude Code, Codex, Gemini CLI).
 * <p>
 * This provides LLM capabilities using CLI-based agents when no API-based LLM is configured.
 * It executes the agent as a subprocess and captures the output.
 * </p>
 * <p>
 * Priority: Loaded after API-based LLMChat implementations but before NoOpLLMChat.
 * Only activated when AgentRegistryService is available and has available agents.
 * </p>
 */
@Service("cliAgentLLMChat")
@ConditionalOnBean(AgentRegistryService.class)
@Order(100) // After API-based implementations (default), before NoOp
public class CliAgentLLMChat implements LLMChat {

    private static final Logger log = LoggerFactory.getLogger(CliAgentLLMChat.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int DEFAULT_POOL_SIZE = 8;
    private static final int MAX_POOL_SIZE = 32;

    private final AgentRegistryService agentRegistryService;
    private final AgentSubprocessExecutor subprocessExecutor;
    private final ClaudeStreamParser streamParser;
    private final ObjectMapper objectMapper = JsonUtils.standardMapper();
    private volatile AgentProvider cachedAgent;

    // ═══════════════════════════════════════════════════════════════════════════
    // PROCESS POOL — pre-spawns CLI agent processes to eliminate startup latency.
    // Each pooled process is alive and waiting for stdin input.
    // ═══════════════════════════════════════════════════════════════════════════
    private final LinkedBlockingQueue<Process> processPool = new LinkedBlockingQueue<>();
    private final ExecutorService poolReplenisher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cli-agent-pool-replenisher");
        t.setDaemon(true);
        return t;
    });
    private volatile List<String> poolCommand;
    private volatile Map<String, String> poolEnvironment;
    private volatile int targetPoolSize = DEFAULT_POOL_SIZE;
    private final AtomicBoolean poolInitialized = new AtomicBoolean(false);
    /** In-flight processes (taken from pool or freshly spawned) — tracked so {@code @PreDestroy}
     *  can force-kill them immediately instead of blocking on each call's wait at shutdown. */
    private final Set<Process> activeProcesses = ConcurrentHashMap.newKeySet();

    public CliAgentLLMChat(AgentRegistryService agentRegistryService,
                           AgentSubprocessExecutor subprocessExecutor,
                           ClaudeStreamParser streamParser) {
        this.agentRegistryService = agentRegistryService;
        this.subprocessExecutor = subprocessExecutor;
        this.streamParser = streamParser;

        // Log initialization
        if (agentRegistryService.hasAvailableAgents()) {
            AgentProvider defaultAgent = agentRegistryService.getDefaultAgent().orElse(null);
            log.info("CliAgentLLMChat initialized with {} available CLI agents. Default: {}",
                    agentRegistryService.getAvailableAgentCount(),
                    defaultAgent != null ? defaultAgent.getDisplayName() : "none");
        } else {
            log.info("CliAgentLLMChat initialized but no CLI agents are available");
        }
    }

    /**
     * Check if this LLMChat implementation is functional.
     */
    public boolean isAvailable() {
        return agentRegistryService.hasAvailableAgents();
    }

    /**
     * Get the currently active agent.
     * Respects the configured command in ~/.kompile/config/cli-llm-config.json,
     * falling back to the registry default if no config or agent not found.
     */
    private AgentProvider getActiveAgent() {
        if (cachedAgent == null || !cachedAgent.isAvailable()) {
            cachedAgent = resolveConfiguredAgent();
        }
        return cachedAgent;
    }

    private AgentProvider resolveConfiguredAgent() {
        // Try to read the configured command from cli-llm-config.json
        try {
            Path configPath = Path.of(
                    System.getProperty("user.home"), ".kompile", "config", "cli-llm-config.json");
            if (Files.exists(configPath)) {
                JsonNode root = objectMapper.readTree(configPath.toFile());
                if (root.has("command") && !root.get("command").isNull()) {
                    String configuredCommand = root.get("command").asText().trim();
                    if (!configuredCommand.isEmpty()) {
                        // Look up agent by command name (e.g. "opencode" -> "opencode-cli")
                        AgentProvider agent = agentRegistryService.getAgentByCommand(configuredCommand);
                        if (agent != null && agent.isAvailable()) {
                            log.debug("Using configured CLI agent from cli-llm-config.json: {}", agent.getName());
                            return agent;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not read cli-llm-config.json: {}", e.getMessage());
        }
        // Fall back to registry default
        return agentRegistryService.getDefaultAgent().orElse(null);
    }

    @Override
    public ChatClientRequestSpec prompt() {
        return new CliAgentRequestSpec(this, null);
    }

    @Override
    public ChatClientRequestSpec prompt(String content) {
        return new CliAgentRequestSpec(this, content);
    }

    @Override
    public ChatClientRequestSpec prompt(Prompt prompt) {
        // Extract user content from prompt
        String content = prompt.getContents();
        return new CliAgentRequestSpec(this, content);
    }

    @Override
    public Builder mutate() {
        return new CliAgentBuilder(this);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POOL LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Lazily initialize the process pool on first use.
     * Pre-spawns {@code targetPoolSize} processes that are alive and waiting for stdin.
     */
    private void ensurePoolInitialized() {
        if (poolInitialized.compareAndSet(false, true)) {
            AgentProvider agent = getActiveAgent();
            if (agent == null) {
                poolInitialized.set(false);
                return;
            }
            targetPoolSize = readPoolSizeFromConfig();
            poolCommand = subprocessExecutor.buildInteractiveCommand(agent, true, false);
            poolEnvironment = agent.safeEnvironment();
            log.info("Initializing CLI agent process pool: size={}, command={}", targetPoolSize, poolCommand);
            poolReplenisher.submit(() -> {
                int spawned = 0;
                for (int i = 0; i < targetPoolSize; i++) {
                    Process p = spawnPoolProcess();
                    if (p != null) {
                        processPool.offer(p);
                        spawned++;
                    }
                }
                log.info("Process pool pre-warmed with {}/{} processes", spawned, targetPoolSize);
            });
        }
    }

    private Process spawnPoolProcess() {
        List<String> cmd = poolCommand;
        Map<String, String> env = poolEnvironment;
        if (cmd == null) return null;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            if (env != null) {
                pb.environment().putAll(env);
            }
            Process process = pb.start();
            log.debug("Spawned pool process PID {}", process.pid());
            return process;
        } catch (Exception e) {
            log.warn("Failed to spawn pool process: {}", e.getMessage());
            return null;
        }
    }

    private void replenishPool() {
        int deficit = targetPoolSize - processPool.size();
        if (deficit <= 0) return;
        int spawned = 0;
        for (int i = 0; i < deficit; i++) {
            Process p = spawnPoolProcess();
            if (p != null) {
                processPool.offer(p);
                spawned++;
            }
        }
        if (spawned > 0) {
            log.debug("Replenished pool with {} processes (total: {})", spawned, processPool.size());
        }
    }

    /**
     * Take a live process from the pool, discarding any dead ones.
     * Returns null if pool is empty.
     */
    private Process takeFromPool() {
        Process process;
        while ((process = processPool.poll()) != null) {
            if (process.isAlive()) {
                return process;
            }
            log.debug("Discarding dead pool process PID {}", process.pid());
        }
        return null;
    }

    private int readPoolSizeFromConfig() {
        try {
            Path configPath = Path.of(
                    System.getProperty("user.home"), ".kompile", "config", "cli-llm-config.json");
            if (Files.exists(configPath)) {
                JsonNode root = objectMapper.readTree(configPath.toFile());
                if (root.has("processPoolSize")) {
                    return Math.max(1, Math.min(MAX_POOL_SIZE, root.get("processPoolSize").asInt(DEFAULT_POOL_SIZE)));
                }
            }
        } catch (Exception e) {
            log.debug("Could not read pool size from config: {}", e.getMessage());
        }
        return DEFAULT_POOL_SIZE;
    }

    /** Per-call timeout (seconds), configurable via cli-llm-config.json "timeoutSeconds". */
    private int readTimeoutFromConfig() {
        try {
            Path configPath = Path.of(
                    System.getProperty("user.home"), ".kompile", "config", "cli-llm-config.json");
            if (Files.exists(configPath)) {
                JsonNode root = objectMapper.readTree(configPath.toFile());
                if (root.has("timeoutSeconds")) {
                    return Math.max(1, root.get("timeoutSeconds").asInt(DEFAULT_TIMEOUT_SECONDS));
                }
            }
        } catch (Exception e) {
            log.debug("Could not read timeout from config: {}", e.getMessage());
        }
        return DEFAULT_TIMEOUT_SECONDS;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RUNTIME STATUS — cheap, read-only, never spawns processes
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Snapshot of the CLI LLM agent pool state, safe to call at any time.
     * All counts reflect live values from the concurrent data structures;
     * no locks are taken and no processes are spawned.
     *
     * @param activeAgent  display name of the currently configured agent, or null if none
     * @param agentCommand raw CLI command (e.g. "opencode"), or null if none
     * @param poolSize     configured target pool size ({@code targetPoolSize})
     * @param pooled       number of warm/idle processes currently in the pool
     * @param inFlight     number of processes actively serving a call right now
     * @param liveTotal    pooled + inFlight (the total subprocess count right now)
     */
    public record CliAgentRuntimeStatus(
            String activeAgent,
            String agentCommand,
            int poolSize,
            int pooled,
            int inFlight,
            int liveTotal
    ) {}

    /**
     * Returns a cheap, null-safe snapshot of the current CLI LLM agent pool state.
     * Returns zeros for all counts when the pool has never been initialized.
     */
    public CliAgentRuntimeStatus getRuntimeStatus() {
        AgentProvider agent = cachedAgent; // volatile read — no side-effects
        String agentDisplayName = agent != null ? agent.getDisplayName() : null;
        String agentCommand = agent != null ? agent.getCommand() : null;
        int pooled = processPool.size();
        int inFlight = activeProcesses.size();
        return new CliAgentRuntimeStatus(
                agentDisplayName,
                agentCommand,
                targetPoolSize,
                pooled,
                inFlight,
                pooled + inFlight
        );
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down CLI agent process pool ({} pooled, {} in-flight)",
                processPool.size(), activeProcesses.size());
        poolReplenisher.shutdownNow();
        // Force-kill in-flight calls first so caller threads blocked in waitFor() return at once.
        for (Process inflight : activeProcesses) {
            try {
                if (inflight.isAlive()) {
                    inflight.destroyForcibly();
                }
            } catch (Exception ignored) {
                // best-effort
            }
        }
        activeProcesses.clear();
        Process p;
        while ((p = processPool.poll()) != null) {
            try {
                if (p.isAlive()) {
                    p.destroyForcibly();
                }
            } catch (Exception e) {
                log.warn("Error destroying pooled agent process during shutdown: {}", e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AGENT EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Execute the CLI agent with the given prompt.
     * Takes a pre-spawned process from the pool when available, falling back to
     * a fresh spawn on pool miss. The pool is replenished asynchronously after each use.
     */
    String executeAgent(String userMessage, String systemMessage) {
        // Reset any session id left on this (possibly pooled) thread by a previous call.
        AgentCallContext.clear();
        AgentProvider agent = getActiveAgent();
        if (agent == null) {
            log.warn("No CLI agent available for execution");
            return "Error: No CLI agent available. Please install a CLI agent (claude, opencode, codex, gemini, etc).";
        }

        String fullPrompt;
        if (systemMessage != null && !systemMessage.isEmpty()) {
            fullPrompt = "[System: " + systemMessage + "]\n\n" + userMessage;
        } else {
            fullPrompt = userMessage;
        }

        boolean useStreamJson = streamParser.supportsStreamJson(agent.getName());

        // Ensure pool is initialized, then try to take a pre-spawned process
        ensurePoolInitialized();

        Process process = null;
        try {
            process = takeFromPool();
            if (process != null) {
                log.info("CLI agent '{}' pool hit — reusing PID {}", agent.getName(), process.pid());
            } else {
                // Pool miss — spawn fresh
                List<String> command = subprocessExecutor.buildInteractiveCommand(agent, true, false);
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                pb.environment().putAll(agent.safeEnvironment());
                process = pb.start();
                log.info("CLI agent '{}' pool miss — spawned fresh PID {}", agent.getName(), process.pid());
            }

            // Replenish pool asynchronously
            poolReplenisher.submit(this::replenishPool);

            // Track this in-flight process so shutdown can force-kill it immediately.
            activeProcesses.add(process);

            // Write prompt to stdin, then close stdin so the agent knows input is complete
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
                writer.write(fullPrompt);
                writer.newLine();
                writer.flush();
            }
            log.info("CLI agent '{}': wrote {} chars to stdin", agent.getName(), fullPrompt.length());

            // Stream stdout directly — read every line as it arrives
            StringBuilder textOutput = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String rawLine;
                while ((rawLine = reader.readLine()) != null) {
                    // Strip TUI escape sequences that leak from agent subprocesses
                    String line = ClaudeStreamParser.stripAnsi(rawLine);
                    if (line.isBlank()) continue;

                    if (line.trim().startsWith("{")) {
                        try {
                            JsonNode json = objectMapper.readTree(line);
                            String type = json.has("type") ? json.get("type").asText() : null;

                            // Capture the agent chat session id when the CLI first reports it
                            // (claude: system/init session_id; opencode/others: a session field).
                            captureSessionId(json);

                            // Claude stream-json: extract text from "assistant" events
                            if ("result".equals(type)) {
                                log.info("CLI agent '{}': stream result event received", agent.getName());
                                break;
                            } else if ("assistant".equals(type)) {
                                JsonNode message = json.has("message") ? json.get("message") : json;
                                if (message.has("content") && message.get("content").isArray()) {
                                    for (JsonNode block : message.get("content")) {
                                        if ("text".equals(block.path("type").asText("text"))
                                                && block.has("text")) {
                                            textOutput.append(block.get("text").asText());
                                        }
                                    }
                                }
                            }
                            // opencode json: extract text from "text" events
                            else if ("text".equals(type)) {
                                JsonNode part = json.has("part") ? json.get("part") : null;
                                if (part != null && part.has("text")) {
                                    textOutput.append(part.get("text").asText());
                                }
                            }
                        } catch (Exception e) {
                            // Not valid JSON — treat as plain text
                            textOutput.append(line).append('\n');
                        }
                    } else {
                        textOutput.append(line).append('\n');
                    }
                }
            }

            // Wait for process exit with configurable timeout (cli-llm-config.json "timeoutSeconds")
            int timeoutSeconds = readTimeoutFromConfig();
            boolean exited = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!exited) {
                log.warn("CLI agent '{}' timed out after {}s, destroying", agent.getName(), timeoutSeconds);
                process.destroyForcibly();
            }

            String content = textOutput.toString().trim();
            log.info("CLI agent '{}' response: {} chars (exit={})", agent.getName(), content.length(),
                    exited ? process.exitValue() : "timeout");
            return content;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("CLI agent '{}' execution interrupted", agent.getName());
            return "Error executing CLI agent: interrupted";
        } catch (Exception e) {
            log.error("Error executing CLI agent '{}': {}", agent.getName(), e.getMessage(), e);
            return "Error executing CLI agent: " + e.getMessage();
        } finally {
            if (process != null) {
                activeProcesses.remove(process);
            }
        }
    }

    /**
     * Capture the CLI agent's chat session id from a parsed stream-json event, if present,
     * publishing it to {@link AgentCallContext} for the dispatcher to attach to the transcript.
     * Only the first session id seen per call is kept.
     */
    private void captureSessionId(JsonNode json) {
        if (AgentCallContext.getSessionId() != null) {
            return;
        }
        for (String key : new String[]{"session_id", "sessionId", "sessionID"}) {
            JsonNode v = json.get(key);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                AgentCallContext.setSessionId(v.asText());
                return;
            }
        }
        JsonNode session = json.get("session");
        if (session != null) {
            if (session.isTextual() && !session.asText().isBlank()) {
                AgentCallContext.setSessionId(session.asText());
            } else if (session.isObject() && session.has("id") && session.get("id").isTextual()) {
                AgentCallContext.setSessionId(session.get("id").asText());
            }
        }
    }

    // ========================================
    // Inner classes for request/response specs
    // ========================================

    /**
     * Request specification for CLI agent.
     */
    private static class CliAgentRequestSpec implements ChatClientRequestSpec {
        private final CliAgentLLMChat parent;
        private String userMessage;
        private String systemMessage;

        CliAgentRequestSpec(CliAgentLLMChat parent, String userMessage) {
            this.parent = parent;
            this.userMessage = userMessage;
        }

        @Override
        public Builder mutate() {
            return new CliAgentBuilder(parent);
        }

        @Override
        public ChatClientRequestSpec advisors(Consumer<AdvisorSpec> consumer) {
            // Advisors not supported for CLI agents
            return this;
        }

        @Override
        public ChatClientRequestSpec advisors(Advisor... advisors) {
            return this;
        }

        @Override
        public ChatClientRequestSpec advisors(List<Advisor> advisors) {
            return this;
        }

        @Override
        public ChatClientRequestSpec messages(Message... messages) {
            // Extract content from messages
            for (Message msg : messages) {
                if (msg.getText() != null) {
                    if (userMessage == null) {
                        userMessage = msg.getText();
                    } else {
                        userMessage = userMessage + "\n" + msg.getText();
                    }
                }
            }
            return this;
        }

        @Override
        public ChatClientRequestSpec messages(List<Message> messages) {
            return messages(messages.toArray(new Message[0]));
        }

        @Override
        public <T extends ChatOptions> ChatClientRequestSpec options(T options) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolNames(String... toolNames) {
            return this;
        }

        @Override
        public ChatClientRequestSpec tools(Object... toolObjects) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolCallbacks(ToolCallback... toolCallbacks) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolCallbacks(List<ToolCallback> toolCallbacks) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolCallbacks(ToolCallbackProvider... toolCallbackProviders) {
            return this;
        }

        @Override
        public ChatClientRequestSpec toolContext(Map<String, Object> toolContext) {
            return this;
        }

        @Override
        public ChatClientRequestSpec system(String text) {
            this.systemMessage = text;
            return this;
        }

        @Override
        public ChatClientRequestSpec system(Resource textResource, Charset charset) {
            // Not implemented for CLI agents
            return this;
        }

        @Override
        public ChatClientRequestSpec system(Resource text) {
            return this;
        }

        @Override
        public ChatClientRequestSpec system(Consumer<PromptSystemSpec> consumer) {
            SimplePromptSystemSpec spec = new SimplePromptSystemSpec();
            consumer.accept(spec);
            this.systemMessage = spec.getText();
            return this;
        }

        @Override
        public ChatClientRequestSpec user(String text) {
            this.userMessage = text;
            return this;
        }

        @Override
        public ChatClientRequestSpec user(Resource text, Charset charset) {
            return this;
        }

        @Override
        public ChatClientRequestSpec user(Resource text) {
            return this;
        }

        @Override
        public ChatClientRequestSpec user(Consumer<PromptUserSpec> consumer) {
            SimplePromptUserSpec spec = new SimplePromptUserSpec();
            consumer.accept(spec);
            this.userMessage = spec.getText();
            return this;
        }

        @Override
        public ChatClientRequestSpec templateRenderer(TemplateRenderer templateRenderer) {
            return this;
        }

        @Override
        public CallResponseSpec call() {
            return new CliAgentCallResponseSpec(parent, userMessage, systemMessage);
        }

        @Override
        public StreamResponseSpec stream() {
            return new CliAgentStreamResponseSpec(parent, userMessage, systemMessage);
        }
    }

    /**
     * Call response specification for CLI agent.
     */
    private static class CliAgentCallResponseSpec implements CallResponseSpec {
        private final CliAgentLLMChat parent;
        private final String userMessage;
        private final String systemMessage;
        private String cachedResponse;

        CliAgentCallResponseSpec(CliAgentLLMChat parent, String userMessage, String systemMessage) {
            this.parent = parent;
            this.userMessage = userMessage;
            this.systemMessage = systemMessage;
        }

        private String getResponse() {
            if (cachedResponse == null) {
                cachedResponse = parent.executeAgent(userMessage, systemMessage);
            }
            return cachedResponse;
        }

        @Override
        public <T> T entity(ParameterizedTypeReference<T> type) {
            log.warn("entity() not supported for CLI agent LLMChat");
            return null;
        }

        @Override
        public <T> T entity(StructuredOutputConverter<T> structuredOutputConverter) {
            log.warn("entity() not supported for CLI agent LLMChat");
            return null;
        }

        @Override
        public <T> T entity(Class<T> type) {
            log.warn("entity() not supported for CLI agent LLMChat");
            return null;
        }

        @Override
        public ChatClientResponse chatClientResponse() {
            ChatResponse response = chatResponse();
            return ChatClientResponse.builder()
                    .chatResponse(response)
                    .build();
        }

        @Override
        public ChatResponse chatResponse() {
            String content = getResponse();
            Generation generation = new Generation(new AssistantMessage(content), null);
            return new ChatResponse(Collections.singletonList(generation));
        }

        @Override
        public String content() {
            return getResponse();
        }
    }

    /**
     * Stream response specification for CLI agent.
     */
    private static class CliAgentStreamResponseSpec implements StreamResponseSpec {
        private final CliAgentLLMChat parent;
        private final String userMessage;
        private final String systemMessage;

        CliAgentStreamResponseSpec(CliAgentLLMChat parent, String userMessage, String systemMessage) {
            this.parent = parent;
            this.userMessage = userMessage;
            this.systemMessage = systemMessage;
        }

        @Override
        public Flux<ChatClientResponse> chatClientResponse() {
            // CLI agents don't truly stream, return single response
            String content = parent.executeAgent(userMessage, systemMessage);
            Generation generation = new Generation(new AssistantMessage(content), null);
            ChatResponse response = new ChatResponse(Collections.singletonList(generation));
            ChatClientResponse clientResponse = ChatClientResponse.builder()
                    .chatResponse(response)
                    .build();
            return Flux.just(clientResponse);
        }

        @Override
        public Flux<ChatResponse> chatResponse() {
            String content = parent.executeAgent(userMessage, systemMessage);
            Generation generation = new Generation(new AssistantMessage(content), null);
            return Flux.just(new ChatResponse(Collections.singletonList(generation)));
        }

        @Override
        public Flux<String> content() {
            String response = parent.executeAgent(userMessage, systemMessage);
            return Flux.just(response);
        }
    }

    /**
     * Builder for CLI agent LLMChat.
     */
    private static class CliAgentBuilder implements Builder {
        private final CliAgentLLMChat parent;

        CliAgentBuilder(CliAgentLLMChat parent) {
            this.parent = parent;
        }

        @Override
        public Builder defaultAdvisors(Advisor... advisors) {
            return this;
        }

        @Override
        public Builder defaultAdvisors(Consumer<AdvisorSpec> advisorSpecConsumer) {
            return this;
        }

        @Override
        public Builder defaultAdvisors(List<Advisor> advisors) {
            return this;
        }

        @Override
        public Builder defaultOptions(ChatOptions chatOptions) {
            return this;
        }

        @Override
        public Builder defaultUser(String text) {
            return this;
        }

        @Override
        public Builder defaultUser(Resource text, Charset charset) {
            return this;
        }

        @Override
        public Builder defaultUser(Resource text) {
            return this;
        }

        @Override
        public Builder defaultUser(Consumer<PromptUserSpec> userSpecConsumer) {
            return this;
        }

        @Override
        public Builder defaultSystem(String text) {
            return this;
        }

        @Override
        public Builder defaultSystem(Resource text, Charset charset) {
            return this;
        }

        @Override
        public Builder defaultSystem(Resource text) {
            return this;
        }

        @Override
        public Builder defaultSystem(Consumer<PromptSystemSpec> systemSpecConsumer) {
            return this;
        }

        @Override
        public Builder defaultTemplateRenderer(TemplateRenderer templateRenderer) {
            return this;
        }

        @Override
        public Builder defaultToolNames(String... toolNames) {
            return this;
        }

        @Override
        public Builder defaultTools(Object... toolObjects) {
            return this;
        }

        @Override
        public Builder defaultToolCallbacks(ToolCallback... toolCallbacks) {
            return this;
        }

        @Override
        public Builder defaultToolCallbacks(List<ToolCallback> toolCallbacks) {
            return this;
        }

        @Override
        public Builder defaultToolCallbacks(ToolCallbackProvider... toolCallbackProviders) {
            return this;
        }

        @Override
        public Builder defaultToolContext(Map<String, Object> toolContext) {
            return this;
        }

        @Override
        public Builder clone() {
            return new CliAgentBuilder(parent);
        }

        @Override
        public LLMChat build() {
            return parent;
        }
    }

    /**
     * Simple implementation of PromptSystemSpec.
     */
    private static class SimplePromptSystemSpec implements PromptSystemSpec {
        private String text;

        String getText() {
            return text;
        }

        @Override
        public PromptSystemSpec text(String text) {
            this.text = text;
            return this;
        }

        @Override
        public PromptSystemSpec text(Resource text, Charset charset) {
            return this;
        }

        @Override
        public PromptSystemSpec text(Resource text) {
            return this;
        }

        @Override
        public PromptSystemSpec params(Map<String, Object> p) {
            return this;
        }

        @Override
        public PromptSystemSpec param(String k, Object v) {
            return this;
        }
    }

    /**
     * Simple implementation of PromptUserSpec.
     */
    private static class SimplePromptUserSpec implements PromptUserSpec {
        private String text;

        String getText() {
            return text;
        }

        @Override
        public PromptUserSpec text(String text) {
            this.text = text;
            return this;
        }

        @Override
        public PromptUserSpec text(Resource text, Charset charset) {
            return this;
        }

        @Override
        public PromptUserSpec text(Resource text) {
            return this;
        }

        @Override
        public PromptUserSpec params(Map<String, Object> p) {
            return this;
        }

        @Override
        public PromptUserSpec param(String k, Object v) {
            return this;
        }

        @Override
        public PromptUserSpec media(Media... media) {
            return this;
        }

        @Override
        public PromptUserSpec media(MimeType mimeType, URL url) {
            return this;
        }

        @Override
        public PromptUserSpec media(MimeType mimeType, Resource resource) {
            return this;
        }
    }
}
