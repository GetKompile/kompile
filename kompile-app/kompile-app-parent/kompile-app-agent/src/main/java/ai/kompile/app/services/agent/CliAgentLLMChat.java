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

import ai.kompile.cli.common.KompileHome;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.crawl.graph.AgentCallContext;
import ai.kompile.core.crawl.graph.CrawlProgressEvent;
import ai.kompile.core.llm.chat.LLMChat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.util.stream.Collectors;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * LLMChat implementation that uses locally installed CLI agents (Claude Code, Codex, Gemini CLI).
 * <p>
 * This provides LLM capabilities using CLI-based agents when no API-based LLM is configured.
 * Extraction calls go through a warm pool of PERSISTENT INTERACTIVE agent sessions managed by
 * {@link HeadlessInteractiveSessionPool}. No one-shot spawns; no {@code -p} flag.
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
    /** Default rebatch budget: models to try per opencode extraction turn before giving up. */
    private static final int DEFAULT_MAX_MODEL_ATTEMPTS = 8;
    private static final int MAX_POOL_SIZE = 32;
    private static final double DEFAULT_SLOW_SUCCESS_TIMEOUT_FRACTION = 0.75;
    private static final int DEFAULT_SLOW_SUCCESS_MIN_SECONDS = 120;

    private final AgentRegistryService agentRegistryService;
    private final AgentSubprocessExecutor subprocessExecutor;
    private final ClaudeStreamParser streamParser;
    private final CliAgentModelService cliAgentModelService;
    private final HeadlessInteractiveSessionPool sessionPool;
    /** opencode extraction transport: a managed {@code opencode serve} subprocess (clean structured
     *  turns over HTTP). The PTY {@link #sessionPool} is the claude/stream-json transport and is NOT
     *  used for opencode (opencode has no stream-json REPL; its TUI mangles JSON). */
    private final OpencodeServeManager opencodeServeManager;
    /** Scores extraction-output correctness and merges A/B outputs into a weighted consensus. */
    private final ExtractionConsensusService consensusService;
    private final ObjectMapper objectMapper = JsonUtils.standardMapper();
    /** Publishes model-routing/de-escalation decisions as {@link CrawlProgressEvent.EventType#DECISION}
     *  so they stream into the crawl UI (live) and the per-job timeline (past jobs). Optional so unit
     *  tests and non-Spring contexts still construct this service. */
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;
    @Autowired(required = false)
    private ModelFallbackConfigManager modelFallbackConfigManager;
    @Autowired(required = false)
    private LocalStagingLlmService localStagingLlmService;

    private volatile AgentProvider cachedAgent;

    // ─── opencode free-model alternation (per-spawn round-robin; concurrency-safe) ───────────────
    /** Round-robin cursor over the dynamic model list from {@link CliAgentModelService#selectExtractionModels}. */
    private final AtomicInteger opencodeRotationCursor = new AtomicInteger(0);

    private volatile int targetPoolSize = DEFAULT_POOL_SIZE;
    private final AtomicBoolean poolInitialized = new AtomicBoolean(false);

    public CliAgentLLMChat(AgentRegistryService agentRegistryService,
                           AgentSubprocessExecutor subprocessExecutor,
                           ClaudeStreamParser streamParser,
                           CliAgentModelService cliAgentModelService,
                           HeadlessInteractiveSessionPool sessionPool,
                           OpencodeServeManager opencodeServeManager,
                           ExtractionConsensusService consensusService) {
        this.consensusService = consensusService;
        this.agentRegistryService = agentRegistryService;
        this.subprocessExecutor = subprocessExecutor;
        this.streamParser = streamParser;
        this.cliAgentModelService = cliAgentModelService;
        this.sessionPool = sessionPool;
        this.opencodeServeManager = opencodeServeManager;

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
     * Respects the configured command in project-scoped cli-llm-config.json,
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
            Path configPath = cliLlmConfigPath();
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

    private AgentProvider resolveNamedAgent(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return null;
        }
        AgentProvider agent = agentRegistryService.getAgent(agentName.trim())
                .filter(AgentProvider::isAvailable)
                .orElse(null);
        if (agent != null) {
            return agent;
        }
        AgentProvider byCommand = agentRegistryService.getAgentByCommand(agentName.trim());
        return byCommand != null && byCommand.isAvailable() ? byCommand : null;
    }

    /**
     * Crawl extraction has its own provider/model policy. If that policy resolves opencode models,
     * route the transport through opencode too; otherwise the model selector can use DeepSeek while
     * the PTY layer still spawns the globally configured/default CLI agent.
     */
    private AgentProvider resolveExtractionAgent() {
        List<String> opencodeModels = cliAgentModelService.selectExtractionModels("opencode-cli");
        if (!opencodeModels.isEmpty()) {
            AgentProvider opencode = resolveNamedAgent("opencode-cli");
            if (opencode == null) {
                opencode = resolveNamedAgent("opencode");
            }
            if (opencode != null) {
                log.debug("Using extraction CLI agent from model policy: {} ({} candidate models)",
                        opencode.getName(), opencodeModels.size());
                return opencode;
            }
            log.warn("Extraction model policy selected opencode models, but no opencode CLI agent is available; falling back to active CLI agent");
        }
        return getActiveAgent();
    }

    /**
     * Per-spawn dynamic model selection for opencode: queries {@link CliAgentModelService#selectExtractionModels}
     * (which applies the active policy — providerAllow, excludeMarkers, and health de-escalation) and returns
     * the next model in the resulting ordered list via round-robin, or {@code null} when the agent is not
     * opencode or discovery returns nothing. Because each pre-spawned pool process can be pinned to a
     * different model, a warm pool ends up holding a mix of healthy free models so concurrent extraction
     * calls spread across them. Never returns a paid/excluded model.
     */
    private String nextRotationModel(AgentProvider agent) {
        if (agent == null) return null;
        String name = agent.getName() == null ? "" : agent.getName().toLowerCase(Locale.ROOT);
        if (!name.contains("opencode")) return null;
        List<String> all = cliAgentModelService.selectExtractionModels(agent.getName());
        if (all.isEmpty()) return null;
        // Rotate over HEALTHY models only — a benched model (known to return empty or hang) is NOT
        // retried until its bench expires; this stops the rebatch loop burning 60s on a benched
        // silent-429 hang every cycle. Fall back to the full list only when everything is benched.
        List<String> healthy = all.stream()
                .filter(cliAgentModelService::isModelHealthy)
                .collect(Collectors.toList());
        if (healthy.isEmpty()) {
            return all.get(Math.floorMod(opencodeRotationCursor.getAndIncrement(), all.size()));
        }
        int tick = opencodeRotationCursor.getAndIncrement();
        // Epsilon-greedy: mostly EXPLOIT proven models (known to return clean output on the real
        // prompt) and only periodically EXPLORE a fresh one, so the crawl converges to what works
        // instead of re-trying the many models that return empty/hang. When a proven model fails it
        // is benched (removed from `healthy`), so the next attempt naturally falls through to explore.
        List<String> proven = healthy.stream()
                .filter(cliAgentModelService::isModelProven)
                .collect(Collectors.toList());
        int exploreEvery = readExploreEveryFromConfig();
        boolean exploit = !proven.isEmpty() && (exploreEvery <= 0 || tick % exploreEvery != 0);
        List<String> pool = exploit ? proven : healthy;
        return pool.get(Math.floorMod(tick, pool.size()));
    }

    /**
     * True when the agent is opencode — detected the same way as {@link #nextRotationModel} so the
     * transport split and the model-rotation policy can never disagree. opencode extraction goes
     * through {@link OpencodeServeManager} (managed {@code opencode serve} HTTP); all other agents
     * use the PTY {@link HeadlessInteractiveSessionPool}.
     */
    private boolean isOpencodeAgent(AgentProvider agent) {
        if (agent == null) return false;
        String name = agent.getName() == null ? "" : agent.getName().toLowerCase(Locale.ROOT);
        if (name.contains("opencode")) return true;
        String command = agent.getCommand() == null ? "" : agent.getCommand().toLowerCase(Locale.ROOT);
        return command.contains("opencode");
    }

    /**
     * Publish a per-call routing/de-escalation decision so the crawl UI and per-job timeline show
     * exactly which model handled each extraction turn, how long it took, what timeout it was given,
     * and — on failure — the symptom and how long the model was benched. Reuses the existing
     * {@link CrawlProgressEvent.EventType#DECISION} → SSE → UI pipeline. No-op outside a crawl context;
     * the slf4j decision line in {@code executeAgent} still records every call regardless.
     */
    private void publishRoutingDecision(String jobId, AgentProvider agent, String model,
                                        CliAgentModelService.ModelOutcome outcome,
                                        int timeoutSeconds, long latencyMs, int responseChars) {
        String modelLabel = (model != null && !model.isBlank()) ? model : "default";
        long benchSec = (outcome == CliAgentModelService.ModelOutcome.OK)
                ? 0L
                : cliAgentModelService.getOutcomeBackoffMs().getOrDefault(outcome, 0L) / 1000;

        // Stamp the decision on this thread so the crawl dispatcher (which holds the job) can attach a
        // model-routing TuningDecision that surfaces in the crawl UI. This works even though the LLM
        // call runs on the dispatcher's timeout executor: the dispatcher captures it via a context
        // holder before clearing the pooled thread — see CrawlLlmDispatcher.callLlmWithTimeout.
        AgentCallContext.setModelDecision(new AgentCallContext.ModelDecision(
                modelLabel, outcome.name(), latencyMs, timeoutSeconds, benchSec, responseChars));

        // Best-effort live SSE nudge when a job id is bound to this thread (non-crawl CLI calls);
        // the crawl path surfaces via the dispatcher's TuningDecision above, not this event.
        if (eventPublisher == null || jobId == null || jobId.isBlank()) {
            return;
        }
        String message = (outcome == CliAgentModelService.ModelOutcome.OK)
                ? String.format("extraction → %s · OK · %d chars · %dms (timeout %ds)",
                        modelLabel, responseChars, latencyMs, timeoutSeconds)
                : String.format("extraction → %s · %s · %dms (timeout %ds) → benched %ds, de-escalating",
                        modelLabel, outcome, latencyMs, timeoutSeconds, benchSec);
        try {
            eventPublisher.publishEvent(new CrawlProgressEvent(
                    this, jobId, null, CrawlProgressEvent.EventType.DECISION, message));
        } catch (Exception e) {
            log.debug("Failed to publish routing decision for job {}: {}", jobId, e.getMessage());
        }
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
     * Lazily initialize the headless interactive session pool on first use.
     * Pre-warms {@code targetPoolSize} persistent interactive agent sessions.
     */
    private void ensurePoolInitialized() {
        if (poolInitialized.compareAndSet(false, true)) {
            AgentProvider agent = getActiveAgent();
            if (agent == null) {
                poolInitialized.set(false);
                return;
            }
            targetPoolSize = readPoolSizeFromConfig();
            log.info("Initializing headless interactive session pool: size={}, agent={}",
                    targetPoolSize, agent.getName());
            // Pre-warm the pool with one session per rotation model (or one if no rotation).
            // Each session is pinned to a specific model so the pool holds a healthy mix.
            String initialModel = nextRotationModel(agent);
            sessionPool.scheduleReplenish(agent, initialModel, subprocessExecutor, targetPoolSize);
        }
    }

    private Path cliLlmConfigPath() {
        return KompileHome.configDirectory().toPath().resolve("cli-llm-config.json");
    }

    private int readPoolSizeFromConfig() {
        try {
            Path configPath = cliLlmConfigPath();
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
        return readCliTimeoutFromConfig(DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * Crawl extraction uses the same per-project/per-job timeout as the fallback executor.
     * The graph planner already sizes prompts from model context metadata; shrinking a later
     * large-context DeepSeek batch to a short EWMA probe timeout causes false failures.
     */
    private int readExtractionTimeoutFromConfig() {
        if (modelFallbackConfigManager != null) {
            try {
                ModelFallbackConfigManager.ModelFallbackConfig config = modelFallbackConfigManager.getConfig();
                if (config != null && config.perCallTimeoutSeconds > 0) {
                    return config.perCallTimeoutSeconds;
                }
            } catch (Exception e) {
                log.debug("Could not read extraction timeout from model fallback config: {}", e.getMessage());
            }
        }
        return readTimeoutFromConfig();
    }

    private int readCliTimeoutFromConfig(int defaultSeconds) {
        try {
            Path configPath = cliLlmConfigPath();
            if (Files.exists(configPath)) {
                JsonNode root = objectMapper.readTree(configPath.toFile());
                if (root.has("timeoutSeconds")) {
                    return Math.max(1, root.get("timeoutSeconds").asInt(defaultSeconds));
                }
            }
        } catch (Exception e) {
            log.debug("Could not read timeout from config: {}", e.getMessage());
        }
        return defaultSeconds;
    }

    /**
     * Max number of models to try for a single opencode extraction turn before giving up (rebatch
     * budget). Read from {@code cli-llm-config.json} key {@code maxModelAttempts} so it is tunable on
     * the fly; defaults to {@link #DEFAULT_MAX_MODEL_ATTEMPTS}. Bounded to [1, 32] to cap worst-case
     * latency when many models hang/return empty.
     */
    private int readMaxModelAttemptsFromConfig() {
        try {
            Path configPath = cliLlmConfigPath();
            if (Files.exists(configPath)) {
                JsonNode root = objectMapper.readTree(configPath.toFile());
                if (root.has("maxModelAttempts")) {
                    return Math.max(1, Math.min(32, root.get("maxModelAttempts").asInt(DEFAULT_MAX_MODEL_ATTEMPTS)));
                }
            }
        } catch (Exception e) {
            log.debug("Could not read maxModelAttempts from config: {}", e.getMessage());
        }
        return DEFAULT_MAX_MODEL_ATTEMPTS;
    }

    /**
     * Number of DISTINCT models to run the same extraction prompt through for A/B weighted consensus.
     * 1 (default) = single model, no A/B. >1 enables A/B: collect that many OK outputs from different
     * models and merge them weighted by per-output correctness × cross-model agreement. Read from
     * {@code cli-llm-config.json} key {@code abTestModelCount}; bounded to [1, 5].
     */
    private int readAbTestModelCountFromConfig() {
        try {
            Path configPath = cliLlmConfigPath();
            if (Files.exists(configPath)) {
                JsonNode root = objectMapper.readTree(configPath.toFile());
                if (root.has("abTestModelCount")) {
                    return Math.max(1, Math.min(5, root.get("abTestModelCount").asInt(1)));
                }
            }
        } catch (Exception e) {
            log.debug("Could not read abTestModelCount from config: {}", e.getMessage());
        }
        return 1;
    }

    /**
     * Epsilon-greedy exploration cadence for opencode model rotation: every Nth pick EXPLORES a fresh
     * (unproven) model instead of exploiting a proven one. Larger = exploit more (converge faster, less
     * alternation); {@code <=0} = never explore once a proven model exists. Read from
     * {@code cli-llm-config.json} key {@code modelExploreEvery}; default 7.
     */
    private int readExploreEveryFromConfig() {
        try {
            Path configPath = cliLlmConfigPath();
            if (Files.exists(configPath)) {
                JsonNode root = objectMapper.readTree(configPath.toFile());
                if (root.has("modelExploreEvery")) {
                    return root.get("modelExploreEvery").asInt(7);
                }
            }
        } catch (Exception e) {
            log.debug("Could not read modelExploreEvery from config: {}", e.getMessage());
        }
        return 7;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RUNTIME STATUS — cheap, read-only
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Snapshot of the CLI LLM agent session pool state, safe to call at any time.
     *
     * @param activeAgent  display name of the currently configured agent, or null if none
     * @param agentCommand raw CLI command (e.g. "opencode"), or null if none
     * @param poolSize     configured target pool size ({@code targetPoolSize})
     * @param pooled       number of warm/idle sessions currently in the headless pool
     * @param inFlight     always 0 (sessions are borrowed exclusively; not tracked separately)
     * @param liveTotal    pooled (+ any actively-borrowed sessions, which are not tracked here)
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
     */
    public CliAgentRuntimeStatus getRuntimeStatus() {
        AgentProvider agent = cachedAgent; // volatile read — no side-effects
        String agentDisplayName = agent != null ? agent.getDisplayName() : null;
        String agentCommand = agent != null ? agent.getCommand() : null;
        int pooled = sessionPool.idleSize();
        return new CliAgentRuntimeStatus(
                agentDisplayName,
                agentCommand,
                targetPoolSize,
                pooled,
                0,
                pooled
        );
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down CliAgentLLMChat (headless session pool shutdown is handled by HeadlessInteractiveSessionPool)");
        // HeadlessInteractiveSessionPool has its own @PreDestroy that terminates all sessions.
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AGENT EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Execute the CLI agent with the given prompt via a PERSISTENT INTERACTIVE session.
     *
     * <p>A warm session is borrowed from {@link HeadlessInteractiveSessionPool}, the prompt is
     * written to its stdin, and the call blocks until the session's reader thread signals
     * turn-complete (via {@link ClaudeStreamParser} for stream-json agents or a prompt-pattern
     * for plain-text agents). The session is returned to the pool after the turn so it can
     * serve the next extraction call without re-spawning.
     *
     * <p>On timeout or session failure the session is killed and a new one is spawned
     * asynchronously to refill the pool. Pool and non-crawl timeout settings are read
     * from project-scoped {@code cli-llm-config.json}; crawl calls use fallback timeout.
     *
     * <p>Model rotation (opencode free-model alternation) and health de-escalation via
     * {@link CliAgentModelService} are fully preserved: the model used for each turn is
     * the one the session was pinned to at spawn time; that model is reported back after
     * the call for outcome recording.
     */
    String executeAgent(String userMessage, String systemMessage) {
        return executeAgent(userMessage, systemMessage, null, null);
    }

    String executeAgentForAgent(String agentName, String userMessage, String systemMessage) {
        return executeAgent(userMessage, systemMessage, agentName, null);
    }

    String executeAgentForAgent(String agentName, String modelName, String userMessage, String systemMessage) {
        return executeAgent(userMessage, systemMessage, agentName, modelName);
    }

    private boolean isLocalStagingAgentName(String agentName) {
        return LocalStagingLlmService.AGENT_NAME.equals(agentName);
    }

    private AgentProvider localStagingAgent() {
        return AgentProvider.builder()
                .name(LocalStagingLlmService.AGENT_NAME)
                .displayName("Local Staging LLM")
                .available(localStagingLlmService != null)
                .build();
    }

    private String executeLocalStaging(String fullPrompt, String forcedModelName,
                                       String crawlJobId, int timeoutSeconds) {
        AgentProvider agent = localStagingAgent();
        String model = forcedModelName;
        if (model == null || model.isBlank()) {
            List<String> candidates = cliAgentModelService.selectExtractionModels(LocalStagingLlmService.AGENT_NAME);
            model = candidates.isEmpty() ? null : candidates.get(0);
        }
        long startMs = System.currentTimeMillis();
        String content = "";
        CliAgentModelService.ModelOutcome outcome = CliAgentModelService.ModelOutcome.OK;
        try {
            if (localStagingLlmService == null) {
                outcome = CliAgentModelService.ModelOutcome.NO_PROVIDER;
                throw new IllegalStateException("Local staging LLM service is not available");
            }
            if (model == null || model.isBlank()) {
                outcome = CliAgentModelService.ModelOutcome.NO_PROVIDER;
                throw new IllegalStateException("No local staged LLM model is available");
            }
            content = localStagingLlmService.generate(model, fullPrompt, timeoutSeconds);
            boolean blank = content == null || content.trim().isEmpty() || content.startsWith("Error:");
            outcome = cliAgentModelService.classifyOutcome(content, blank);
            if (outcome == CliAgentModelService.ModelOutcome.OK) {
                long okLatencyMs = System.currentTimeMillis() - startMs;
                cliAgentModelService.recordModelLatency(model, okLatencyMs);
                cliAgentModelService.recordModelThroughput(model, content.length(), okLatencyMs);
                ExtractionConsensusService.ScoredExtraction scored = consensusService.score(model, content);
                cliAgentModelService.recordModelCorrectness(model, scored.correctness());
                if (scored.correctness() <= 0.0) {
                    outcome = CliAgentModelService.ModelOutcome.ERROR;
                    log.warn("Local staging LLM model '{}' returned unusable extraction output", model);
                }
            }
            if (outcome != CliAgentModelService.ModelOutcome.OK || blank) {
                throw new IllegalStateException("Local staging LLM model '" + model + "' returned unusable output");
            }
            return content.trim();
        } catch (RuntimeException e) {
            if (outcome == CliAgentModelService.ModelOutcome.OK) {
                outcome = CliAgentModelService.ModelOutcome.ERROR;
            }
            throw e;
        } catch (Exception e) {
            outcome = CliAgentModelService.ModelOutcome.ERROR;
            throw new IllegalStateException("Local staging LLM failed", e);
        } finally {
            long latencyMs = System.currentTimeMillis() - startMs;
            if (model != null && !model.isBlank()) {
                cliAgentModelService.recordModelOutcome(model, outcome);
            }
            publishRoutingDecision(crawlJobId, agent, model, outcome, timeoutSeconds, latencyMs,
                    content == null ? 0 : content.length());
            log.info("Local staging LLM attempt model={} outcome={} chars={} timeout={}s latency={}ms",
                    model != null ? model : "default", outcome, content == null ? 0 : content.length(),
                    timeoutSeconds, latencyMs);
        }
    }

    private String executeAgent(String userMessage, String systemMessage, String forcedAgentName, String forcedModelName) {
        // Capture the crawl job id the orchestrator stamped on this thread BEFORE clearing context,
        // so routing/de-escalation decisions can be routed to the right job in the UI/timeline.
        String crawlJobId = AgentCallContext.getJobId();
        boolean crawlExtractionCall = crawlJobId != null && !crawlJobId.isBlank();
        boolean forcedAgentCall = forcedAgentName != null && !forcedAgentName.isBlank();
        boolean forcedModelCall = forcedModelName != null && !forcedModelName.isBlank();
        boolean extractionExecution = crawlExtractionCall || forcedAgentCall;
        // Reset any session id left on this (possibly pooled) thread by a previous call.
        AgentCallContext.clear();

        String fullPrompt;
        if (systemMessage != null && !systemMessage.isEmpty()) {
            fullPrompt = "[System: " + systemMessage + "]\n\n" + userMessage;
        } else {
            fullPrompt = userMessage;
        }

        if (forcedAgentCall && isLocalStagingAgentName(forcedAgentName.trim())) {
            int timeoutCeiling = extractionExecution ? readExtractionTimeoutFromConfig() : readTimeoutFromConfig();
            return executeLocalStaging(fullPrompt, forcedModelName, crawlJobId, timeoutCeiling);
        }

        AgentProvider agent = forcedAgentCall
                ? resolveNamedAgent(forcedAgentName)
                : (crawlExtractionCall ? resolveExtractionAgent() : getActiveAgent());
        if (agent == null) {
            String requested = forcedAgentCall ? " Requested agent: " + forcedAgentName + "." : "";
            throw new IllegalStateException("No CLI agent available for execution." + requested);
        }

        boolean opencode = isOpencodeAgent(agent);
        // Only PTY agents use the headless session pool; opencode extraction goes through opencode serve.
        if (!opencode) {
            ensurePoolInitialized();
        }

        int timeoutCeiling = extractionExecution ? readExtractionTimeoutFromConfig() : readTimeoutFromConfig();
        int poolSize = targetPoolSize;

        // Rebatch-on-failure with model de-escalation: try successive HEALTHY models — each failure
        // benches its model per-symptom (so the next attempt picks a different one) — until one returns
        // a usable response or the attempt budget is exhausted. This converts the old "one model fails →
        // batch dropped (0 entities)" into "converge to a working model within the batch". The first
        // batch explores + benches the dead/slow models; once a model proves OK it is preferred by
        // selectExtractionModels, so subsequent batches use it immediately. Non-opencode agents keep a
        // single attempt (the PTY pool pins its own model).
        int maxAttempts = opencode && !forcedAgentCall ? readMaxModelAttemptsFromConfig() : 1;
        int abTargetCount = opencode && !forcedAgentCall ? readAbTestModelCountFromConfig() : 1; // 1 = single model (no A/B)

        // Collected OK outputs (scored for correctness) — 1 for normal mode, up to abTargetCount for A/B.
        List<ExtractionConsensusService.ScoredExtraction> okOutputs = new ArrayList<>();
        String lastContent = "";
        String rotationModel;
        CliAgentModelService.ModelOutcome outcome = CliAgentModelService.ModelOutcome.OK;
        int callTimeoutSeconds = timeoutCeiling;
        long latencyMs = 0L;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            rotationModel = forcedModelCall ? forcedModelName.trim() : nextRotationModel(agent);

            // Adaptive per-model timeout: an unproven model gets a short probe leash so a silent-429
            // hang is benched in ~seconds rather than burning the full ceiling. For real crawl extraction
            // batches, use the full project/job ceiling: prompt size already comes from model context metadata.
            callTimeoutSeconds = (rotationModel != null && !extractionExecution)
                    ? cliAgentModelService.adaptiveTimeoutSeconds(rotationModel, timeoutCeiling)
                    : timeoutCeiling;

            // Transport split: opencode → managed `opencode serve` HTTP subprocess (clean structured
            // turns); everything else → the PTY headless interactive session pool.
            long startMs = System.currentTimeMillis();
            String rawContent = opencode
                    ? opencodeServeManager.prompt(agent, rotationModel, fullPrompt, callTimeoutSeconds)
                    : sessionPool.prompt(agent, rotationModel, subprocessExecutor,
                            fullPrompt, callTimeoutSeconds, poolSize);
            latencyMs = System.currentTimeMillis() - startMs;

            String content = rawContent == null ? "" : rawContent.trim();
            boolean blank = content.isEmpty() || content.startsWith("Error:");
            lastContent = content;

            // Classify the symptom and react: record latency on success (feeds the adaptive timeout),
            // bench the model per-symptom on failure (quota=long, rate-limit/timeout=short, empty=medium).
            outcome = cliAgentModelService.classifyOutcome(rawContent, blank);
            CliAgentModelService.ModelOutcome healthOutcome = outcome;
            double correctness = 0.0;
            if (rotationModel != null) {
                if (outcome == CliAgentModelService.ModelOutcome.OK) {
                    cliAgentModelService.recordModelLatency(rotationModel, latencyMs);
                    cliAgentModelService.recordModelThroughput(rotationModel,
                            rawContent == null ? 0 : rawContent.length(), latencyMs);
                    // Score the output's correctness (well-formed entities/relationships, not merely
                    // non-empty) and record it so selection prefers models that produce GOOD output;
                    // collect only correctness-positive outputs for the A/B weighted-consensus merge.
                    ExtractionConsensusService.ScoredExtraction scored =
                            consensusService.score(rotationModel, rawContent);
                    correctness = scored.correctness();
                    cliAgentModelService.recordModelCorrectness(rotationModel, correctness);
                    if (correctness > 0.0) {
                        okOutputs.add(scored);
                        if (isSlowSuccessfulExtraction(latencyMs, callTimeoutSeconds)) {
                            healthOutcome = CliAgentModelService.ModelOutcome.SLOW;
                            log.warn("CLI extraction model '{}' returned usable output but exceeded throughput pressure threshold: latency={}ms timeout={}s",
                                    rotationModel, latencyMs, callTimeoutSeconds);
                        }
                    } else {
                        outcome = CliAgentModelService.ModelOutcome.ERROR;
                        healthOutcome = CliAgentModelService.ModelOutcome.ERROR;
                        log.warn("CLI extraction model '{}' returned unusable extraction output; treating the attempt as failed",
                                rotationModel);
                    }
                }
                cliAgentModelService.recordModelOutcome(rotationModel, healthOutcome);
            }
            log.info("CLI agent '{}' attempt {}/{} model={} outcome={} healthOutcome={} correctness={} chars={} timeout={}s latency={}ms",
                    agent.getName(), attempt, maxAttempts, rotationModel != null ? rotationModel : "default",
                    outcome, healthOutcome, String.format(Locale.ROOT, "%.2f", correctness), content.length(),
                    callTimeoutSeconds, latencyMs);

            // Surface the routing/de-escalation decision to the crawl UI + per-job timeline.
            publishRoutingDecision(crawlJobId, agent, rotationModel, healthOutcome, callTimeoutSeconds, latencyMs, content.length());

            if (rotationModel == null) {
                break; // no model rotation to fall back on — single attempt
            }
            if (outcome == CliAgentModelService.ModelOutcome.OK && okOutputs.size() >= abTargetCount) {
                break; // collected enough usable outputs (1 = first-OK-wins; >1 = A/B set complete)
            }
            // else: failed (model benched) OR still collecting A/B outputs — try the next healthy model
        }

        // Produce the result: single OK → its raw JSON unchanged; multiple OK → weighted-consensus merge
        // (entities and relationships weighted by each output's correctness x cross-model agreement).
        if (okOutputs.isEmpty()) {
            throw new IllegalStateException("No CLI agent attempt returned usable extraction output"
                    + (lastContent == null || lastContent.isBlank() ? "" : "; last output length=" + lastContent.length()));
        }
        if (okOutputs.size() == 1) {
            return okOutputs.get(0).rawJson();
        }
        return consensusService.merge(okOutputs);
    }

    private boolean isSlowSuccessfulExtraction(long latencyMs, int timeoutSeconds) {
        if (latencyMs <= 0 || timeoutSeconds <= 0) {
            return false;
        }
        ModelFallbackConfigManager.ModelFallbackConfig config = modelFallbackConfigManager != null
                ? modelFallbackConfigManager.getConfig()
                : null;
        boolean enabled = config == null || config.slowSuccessPressureEnabled;
        if (!enabled) {
            return false;
        }
        double fraction = config != null
                ? config.slowSuccessTimeoutFraction
                : DEFAULT_SLOW_SUCCESS_TIMEOUT_FRACTION;
        if (!Double.isFinite(fraction) || fraction <= 0.0) {
            fraction = DEFAULT_SLOW_SUCCESS_TIMEOUT_FRACTION;
        }
        int minSeconds = config != null
                ? config.slowSuccessMinSeconds
                : DEFAULT_SLOW_SUCCESS_MIN_SECONDS;
        long thresholdMs = Math.max(Math.max(1, minSeconds) * 1000L,
                (long) Math.ceil(timeoutSeconds * 1000.0 * fraction));
        return latencyMs >= thresholdMs;
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
