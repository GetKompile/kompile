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
package ai.kompile.app.services.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.agent.AgentProvider;
import ai.kompile.core.graphrag.agent.ExtractionLlmServiceRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Discovers and caches available models for each CLI agent.
 * <p>
 * Model discovery is done by running the agent's model list command
 * (e.g., {@code opencode models}, {@code pi --list-models}) and parsing stdout.
 * For agents without a list command, well-known models are provided.
 * <p>
 * Model selections are persisted to {@code ~/.kompile/config/cli-llm-config.json}.
 */
@Service
public class CliAgentModelService {

    private static final Logger log = LoggerFactory.getLogger(CliAgentModelService.class);
    private static final int DISCOVERY_TIMEOUT_SECONDS = 15;

    private final AgentRegistryService agentRegistry;
    private final AgentSubprocessExecutor subprocessExecutor;
    private final ObjectMapper objectMapper = JsonUtils.standardMapper();

    @Autowired(required = false)
    private ExtractionLlmServiceRegistry extractionRegistry;

    // Cache: agentName -> list of discovered model IDs
    private final Map<String, List<String>> modelCache = new ConcurrentHashMap<>();

    // ── Model health tracking ──────────────────────────────────────────────
    /** Map of model id → epoch millis until which it is considered unhealthy (de-escalated). */
    private final Map<String, Long> modelUnhealthyUntilMs = new ConcurrentHashMap<>();
    /** Fallback bench duration for outcomes with no specific entry in {@link #backoffByOutcomeMs}. */
    private volatile long modelUnhealthyTtlMs = 15 * 60 * 1000L;

    /**
     * Per-symptom bench durations — we react to <em>what</em> went wrong, not just "it failed".
     * A quota/credit problem won't fix itself within a crawl (bench long); a rate-limit/timeout is
     * transient (bench short, retry soon); an empty response means the model is effectively
     * unavailable here (bench medium). All values configurable on the fly via {@link #setOutcomeBackoffMs}.
     */
    private final Map<ModelOutcome, Long> backoffByOutcomeMs = new ConcurrentHashMap<>(Map.of(
            ModelOutcome.INSUFFICIENT_BALANCE, 60 * 60 * 1000L,  // quota — won't recover this crawl
            ModelOutcome.UNAUTHORIZED,         60 * 60 * 1000L,  // not entitled to this model
            ModelOutcome.NO_PROVIDER,          60 * 60 * 1000L,  // provider not wired
            ModelOutcome.EMPTY,                30 * 60 * 1000L,  // returns nothing — effectively dead here
            ModelOutcome.THROTTLED,            10 * 60 * 1000L,  // rate-limited — retry later
            ModelOutcome.TIMEOUT,              20 * 60 * 1000L,  // hung/too-slow-on-big-prompts — bench longer
            ModelOutcome.ERROR,                 5 * 60 * 1000L)); // transient/unknown — retry soon

    // ── Adaptive per-model timeout (so a hanging model is detected fast, not after the full ceiling) ──
    /** Model id → EWMA of observed successful-call latency (ms). */
    private final Map<String, Double> modelLatencyEwmaMs = new ConcurrentHashMap<>();
    /** Model id → EWMA of extraction-output correctness in [0,1] (quality, not just non-empty). */
    private final Map<String, Double> modelCorrectnessEwma = new ConcurrentHashMap<>();
    /** Leash for a model with no success history yet — long enough for a legitimate first extraction
     *  call to land, short enough to catch a silent-429 hang well before the dispatcher ceiling. Configurable. */
    private volatile int probeTimeoutSeconds = 60;
    /** Multiplier applied to a proven model's EWMA latency to derive its call timeout. Configurable. */
    private volatile double latencyTimeoutFactor = 2.5;
    /** Floor on any adaptive timeout so a single fast call can't starve a legitimately variable model. */
    private volatile int minAdaptiveTimeoutSeconds = 20;

    // Detection patterns, kept as data so they read clearly and are easy to extend.
    private static final List<String> PATTERNS_INSUFFICIENT_BALANCE =
            List.of("insufficient balance", "creditserror");
    private static final List<String> PATTERNS_NO_PROVIDER =
            List.of("no provider available");
    private static final List<String> PATTERNS_UNAUTHORIZED =
            List.of("unauthorized");
    private static final List<String> PATTERNS_THROTTLED =
            List.of("rate limit", "rate-limit", "429", "too many requests", "overloaded", "throttl");
    private static final List<String> PATTERNS_TIMEOUT =
            List.of("timed out", "timeout");

    /** Possible outcomes of a single model call, used to drive health-based de-escalation. */
    public enum ModelOutcome {
        OK, NO_PROVIDER, INSUFFICIENT_BALANCE, UNAUTHORIZED, THROTTLED, TIMEOUT, EMPTY, ERROR
    }

    // ── Active extraction policy (set by the crawl at job start) ──────────
    /** If non-empty, only models whose provider prefix (before the first '/') is in this list are selected. */
    private volatile List<String> activeProviderAllow = List.of();
    /** Model ids whose lowercased id contains any of these markers are excluded from extraction selection. */
    private volatile List<String> activeExcludeMarkers = List.of("claude", "codex");
    /**
     * Explicit model-id allow-list; when non-empty, selection uses exactly these ids (∩ discovery ∩ health),
     * ignoring {@link #activeProviderAllow}.  Empty means no explicit pin — providerAllow governs candidates.
     */
    private volatile List<String> activeModelAllow = List.of();

    /**
     * Set the active extraction model policy. This is called at crawl-job start from the
     * per-crawl {@code GraphExtractionConfig} so model selection is policy-governed
     * for the duration of the crawl.
     *
     * @param providerAllow   whitelist of provider prefixes (e.g. ["opencode"]) — null means all providers
     * @param excludeMarkers  substrings to exclude from model ids — null keeps the default ["claude","codex"]
     * @param modelAllow      explicit model-id allow-list; null or empty = no explicit pin (providerAllow governs)
     */
    public void setActiveExtractionPolicy(List<String> providerAllow, List<String> excludeMarkers,
                                           List<String> modelAllow) {
        this.activeProviderAllow  = (providerAllow  != null) ? List.copyOf(providerAllow) : List.of();
        this.activeExcludeMarkers = (excludeMarkers != null) ? List.copyOf(excludeMarkers)
                                                             : List.of("claude", "codex");
        this.activeModelAllow     = (modelAllow     != null) ? List.copyOf(modelAllow) : List.of();
    }

    public void setModelUnhealthyTtlMs(long ttlMs) {
        this.modelUnhealthyTtlMs = ttlMs;
    }

    // Well-known models for agents that lack a list command
    private static final Map<String, List<String>> WELL_KNOWN_MODELS = Map.of(
            "claude-cli", List.of(
                    "claude-fable-5", "claude-opus-4-8", "opus",
                    "claude-opus-4-7", "claude-sonnet-4-6", "sonnet",
                    "claude-opus-4-6", "claude-haiku-4-5-20251001", "haiku"
            ),
            "codex-cli", List.of(
                    "gpt-5.5", "gpt-5.4", "gpt-5.4-mini",
                    "gpt-5.3-codex", "gpt-5.3-codex-spark", "gpt-5.2-codex"
            ),
            "agy-cli", List.of(
                    "agy-3.1-pro", "agy-3-flash",
                    "agy-3.1-pro-preview", "agy-2.5-pro", "agy-2.5-flash"
            ),
            "qwen-cli", List.of(
                    "qwen3-coder", "qwen3-coder-next", "qwen3.7-max",
                    "qwen3.7-plus", "qwen3.6-plus"
            )
    );

    public CliAgentModelService(AgentRegistryService agentRegistry, AgentSubprocessExecutor subprocessExecutor) {
        this.agentRegistry = agentRegistry;
        this.subprocessExecutor = subprocessExecutor;
    }

    /**
     * Apply per-agent model selections from cli-llm-config.json to the live
     * {@link AgentProvider} instances at startup, so the configured model is appended to the
     * CLI command (by {@link AgentSubprocessExecutor}) without needing a UI round-trip after a
     * restart. Only explicit per-agent entries are applied — never the global fallback, which
     * must not leak one agent's model onto another.
     */
    @jakarta.annotation.PostConstruct
    public void applyConfiguredModels() {
        try {
            JsonNode config = readCliLlmConfig();
            if (config == null) return;
            JsonNode agentModels = config.get("agentModels");
            if (agentModels == null || !agentModels.isObject()) return;
            int applied = 0;
            Iterator<Map.Entry<String, JsonNode>> it = agentModels.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode val = entry.getValue();
                if (val == null || val.isNull() || val.asText("").isBlank()) continue;
                Optional<AgentProvider> agentOpt = agentRegistry.getAgent(entry.getKey());
                if (agentOpt.isPresent()) {
                    agentOpt.get().setModelName(val.asText());
                    applied++;
                }
            }
            if (applied > 0) {
                log.info("Applied {} configured CLI agent model selection(s) at startup", applied);
            }
        } catch (Exception e) {
            log.debug("Could not apply configured agent models at startup: {}", e.getMessage());
        }
    }

    /**
     * Returned from model listing endpoints.
     */
    public record AgentModelInfo(
            String agentName,
            String displayName,
            boolean available,
            String currentModel,
            List<String> availableModels,
            String modelSource
    ) {}

    /**
     * Get available models for a specific agent.
     * Runs discovery if not cached yet.
     * Always re-checks availability live instead of using cached state.
     */
    public AgentModelInfo getModelsForAgent(String agentName, boolean refresh) {
        Optional<AgentProvider> agentOpt = agentRegistry.getAgent(agentName);
        if (agentOpt.isEmpty()) {
            return new AgentModelInfo(agentName, agentName, false, null, List.of(), "unknown");
        }

        AgentProvider agent = agentOpt.get();

        if (refresh) {
            modelCache.remove(agentName);
        }

        // Always re-check availability live — don't rely on cached startup state
        boolean available = agentRegistry.checkAgentAvailability(agentName);

        // Don't cache an EMPTY discovery (e.g. `opencode models` timed out during cold startup) —
        // a cached empty result would stick for the JVM's life and keep selectExtractionModels/the
        // pre-flight probe empty. Only cache non-empty, so a cold/failed discovery retries next call.
        List<String> models = modelCache.get(agentName);
        if (models == null || models.isEmpty()) {
            List<String> discovered = discoverModels(agent);
            if (discovered != null && !discovered.isEmpty()) {
                modelCache.put(agentName, discovered);
                models = discovered;
            } else {
                models = List.of();
            }
        }
        String currentModel = getCurrentModel(agentName);
        String source = agent.getModelListCommand() != null && !agent.getModelListCommand().isEmpty()
                ? "discovered" : "well-known";

        return new AgentModelInfo(
                agentName,
                agent.getDisplayName(),
                available,
                currentModel,
                models,
                source
        );
    }

    /**
     * Get model info for all registered CLI agents.
     */
    public List<AgentModelInfo> getAllAgentModels(boolean refresh) {
        List<AgentModelInfo> result = new ArrayList<>();
        for (AgentProvider agent : agentRegistry.getAllAgents()) {
            if (!agent.isApiAgent()) {
                result.add(getModelsForAgent(agent.getName(), refresh));
            }
        }
        return result;
    }

    /**
     * Set the model for a specific agent. Persists to cli-llm-config.json
     * and applies runtime override to extraction registry.
     */
    public boolean setAgentModel(String agentName, String model) {
        Optional<AgentProvider> agentOpt = agentRegistry.getAgent(agentName);
        if (agentOpt.isEmpty()) return false;

        // Persist to config file
        persistModelSelection(agentName, model);

        // Apply runtime override to the extraction registry
        if (extractionRegistry != null) {
            extractionRegistry.setProviderModel(agentName, model);
        }

        // Also update the agent's model name in the registry
        AgentProvider agent = agentOpt.get();
        agent.setModelName(model);

        log.info("Set model for agent '{}': {}", agentName, model);
        return true;
    }

    /**
     * Get the currently configured model for an agent (from config file).
     */
    public String getCurrentModel(String agentName) {
        try {
            JsonNode config = readCliLlmConfig();
            if (config == null) return null;

            // Check per-agent model first
            JsonNode agentModels = config.get("agentModels");
            if (agentModels != null && agentModels.has(agentName)) {
                JsonNode val = agentModels.get(agentName);
                if (!val.isNull() && !val.asText("").isBlank()) {
                    return val.asText();
                }
            }

            // Fall back to global model
            JsonNode globalModel = config.get("model");
            if (globalModel != null && !globalModel.isNull() && !globalModel.asText("").isBlank()) {
                return globalModel.asText();
            }
        } catch (Exception e) {
            log.debug("Could not read current model for agent {}: {}", agentName, e.getMessage());
        }
        return null;
    }

    /**
     * Discover models for an agent, either via its list command or well-known list.
     */
    private List<String> discoverModels(AgentProvider agent) {
        List<String> modelListCommand = agent.getModelListCommand();

        // If agent has a model list command, try to run it
        if (modelListCommand != null && !modelListCommand.isEmpty()) {
            try {
                List<String> discovered = runModelListCommand(modelListCommand);
                if (!discovered.isEmpty()) {
                    log.info("Discovered {} models for agent '{}'", discovered.size(), agent.getName());
                    return discovered;
                }
            } catch (Exception e) {
                log.warn("Model discovery failed for '{}': {}", agent.getName(), e.getMessage());
            }
        }

        // Fall back to well-known models
        List<String> wellKnown = WELL_KNOWN_MODELS.get(agent.getName());
        if (wellKnown != null) {
            log.debug("Using well-known model list for agent '{}' ({} models)", agent.getName(), wellKnown.size());
            return wellKnown;
        }

        return List.of();
    }

    /**
     * Run a model list command and parse one model per line from stdout.
     */
    private List<String> runModelListCommand(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        List<String> models = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("//")) {
                    models.add(trimmed);
                }
            }
        }

        boolean completed = process.waitFor(DISCOVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new TimeoutException("Model list command timed out after " + DISCOVERY_TIMEOUT_SECONDS + "s");
        }

        return models;
    }

    /**
     * Persist a model selection to ~/.kompile/config/cli-llm-config.json.
     */
    private void persistModelSelection(String agentName, String model) {
        try {
            Path configPath = getConfigPath();
            ObjectNode config;
            if (Files.exists(configPath)) {
                config = (ObjectNode) objectMapper.readTree(configPath.toFile());
            } else {
                Files.createDirectories(configPath.getParent());
                config = objectMapper.createObjectNode();
            }

            // Ensure agentModels section exists
            ObjectNode agentModels;
            if (config.has("agentModels") && config.get("agentModels").isObject()) {
                agentModels = (ObjectNode) config.get("agentModels");
            } else {
                agentModels = objectMapper.createObjectNode();
                config.set("agentModels", agentModels);
            }

            // Set the model (null to clear)
            if (model == null || model.isBlank()) {
                agentModels.putNull(agentName);
            } else {
                agentModels.put(agentName, model);
            }

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), config);
            log.info("Persisted model selection for '{}': {} -> {}", agentName, model, configPath);
        } catch (Exception e) {
            log.error("Failed to persist model selection for '{}': {}", agentName, e.getMessage(), e);
        }
    }

    private JsonNode readCliLlmConfig() {
        try {
            Path configPath = getConfigPath();
            if (Files.exists(configPath)) {
                return objectMapper.readTree(configPath.toFile());
            }
        } catch (Exception e) {
            log.debug("Could not read cli-llm-config.json: {}", e.getMessage());
        }
        return null;
    }

    /** Read the full cli-llm-config.json as a map (empty map if absent). */
    public Map<String, Object> getCliLlmConfig() {
        try {
            Path configPath = getConfigPath();
            if (Files.exists(configPath)) {
                return objectMapper.readValue(configPath.toFile(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.debug("Could not read cli-llm-config.json: {}", e.getMessage());
        }
        return new java.util.LinkedHashMap<>();
    }

    /**
     * Merge-preserving update of cli-llm-config.json: overlays the given keys onto the existing
     * file so {@code agentModels} (and any other keys) are preserved — never a full overwrite.
     * Validates known numeric fields (processPoolSize 1..32, timeoutSeconds &gt;= 1). Settable keys
     * include {@code command} (active CLI agent), {@code processPoolSize}, {@code timeoutSeconds},
     * {@code enabled}, {@code skipPermissions}.
     */
    public Map<String, Object> updateCliLlmConfig(Map<String, Object> updates) {
        try {
            Path configPath = getConfigPath();
            ObjectNode root;
            if (Files.exists(configPath)) {
                JsonNode n = objectMapper.readTree(configPath.toFile());
                root = n.isObject() ? (ObjectNode) n : objectMapper.createObjectNode();
            } else {
                Files.createDirectories(configPath.getParent());
                root = objectMapper.createObjectNode();
            }
            if (updates != null) {
                for (Map.Entry<String, Object> e : updates.entrySet()) {
                    String k = e.getKey();
                    Object v = e.getValue();
                    if ("processPoolSize".equals(k) && v instanceof Number num) {
                        root.put(k, Math.max(1, Math.min(32, num.intValue())));
                    } else if ("timeoutSeconds".equals(k) && v instanceof Number num) {
                        root.put(k, Math.max(1, num.intValue()));
                    } else {
                        root.set(k, objectMapper.valueToTree(v));
                    }
                }
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), root);
            log.info("Updated cli-llm-config.json keys: {}", updates != null ? updates.keySet() : "[]");
            return objectMapper.convertValue(root,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Failed to update cli-llm-config.json: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to update cli-llm-config: " + e.getMessage(), e);
        }
    }

    // ── Model health API ──────────────────────────────────────────────────

    /**
     * Classify the raw output of a model call into an outcome type.
     *
     * @param rawOutput text returned by the agent subprocess (may be empty / null)
     * @param blank     whether the caller considers the output blank (0 useful chars)
     * @return outcome; never null
     */
    public ModelOutcome classifyOutcome(String rawOutput, boolean blank) {
        if (rawOutput != null && !rawOutput.isEmpty()) {
            String lower = rawOutput.toLowerCase(Locale.ROOT);
            // Order matters: specific, actionable symptoms before the generic ERROR fallthrough.
            // TIMEOUT is checked first because a silent 429 surfaces here as a transport timeout,
            // and it is the expensive symptom we most want to react to (shorten its leash next time).
            if (PATTERNS_TIMEOUT.stream().anyMatch(lower::contains)) {
                return ModelOutcome.TIMEOUT;
            }
            if (PATTERNS_INSUFFICIENT_BALANCE.stream().anyMatch(lower::contains)) {
                return ModelOutcome.INSUFFICIENT_BALANCE;
            }
            if (PATTERNS_NO_PROVIDER.stream().anyMatch(lower::contains)) {
                return ModelOutcome.NO_PROVIDER;
            }
            if (PATTERNS_UNAUTHORIZED.stream().anyMatch(lower::contains)) {
                return ModelOutcome.UNAUTHORIZED;
            }
            if (PATTERNS_THROTTLED.stream().anyMatch(lower::contains)) {
                return ModelOutcome.THROTTLED;
            }
            if (!blank) {
                return ModelOutcome.OK;
            }
            // Non-empty but blank-per-caller (e.g. an "Error: …" string we did not pattern-match).
            return ModelOutcome.ERROR;
        }
        // Truly empty output — the model returned nothing (e.g. an unavailable provider model).
        return ModelOutcome.EMPTY;
    }

    /**
     * Record the outcome of a single model call, updating the health map.
     * OK → remove from unhealthy map (recovered). Any other outcome → mark unhealthy for {@code modelUnhealthyTtlMs}.
     *
     * @param modelId model identifier (null/blank → no-op)
     * @param outcome the outcome to record
     */
    public void recordModelOutcome(String modelId, ModelOutcome outcome) {
        if (modelId == null || modelId.isBlank()) return;
        if (outcome == ModelOutcome.OK) {
            modelUnhealthyUntilMs.remove(modelId);
        } else {
            long backoff = backoffByOutcomeMs.getOrDefault(outcome, modelUnhealthyTtlMs);
            long until = System.currentTimeMillis() + backoff;
            modelUnhealthyUntilMs.put(modelId, until);
            log.info("Model '{}' benched (symptom={}) for {}s; de-escalating until {}",
                    modelId, outcome, backoff / 1000, until);
        }
    }

    /**
     * Record a successful call's wall-clock latency for a model, updating its EWMA. Drives the
     * adaptive timeout: proven-fast models get a tight leash; a model with no history gets the
     * short {@link #probeTimeoutSeconds} probe so a silent-429 hang is caught quickly instead of
     * burning the full ceiling. EWMA weights: 70% history, 30% latest.
     */
    public void recordModelLatency(String modelId, long latencyMs) {
        if (modelId == null || modelId.isBlank() || latencyMs <= 0) return;
        modelLatencyEwmaMs.merge(modelId, (double) latencyMs,
                (prev, cur) -> prev * 0.7 + cur * 0.3);
    }

    /**
     * Record the correctness [0,1] of a model's extraction output (from {@link ExtractionConsensusService}),
     * updating its EWMA. Feeds selection ordering so models that produce <em>good</em> output — not just
     * non-empty — are preferred. EWMA weights: 70% history, 30% latest.
     */
    public void recordModelCorrectness(String modelId, double correctness) {
        if (modelId == null || modelId.isBlank()) return;
        double c = Math.max(0.0, Math.min(1.0, correctness));
        modelCorrectnessEwma.merge(modelId, c, (prev, cur) -> prev * 0.7 + cur * 0.3);
    }

    /** Current correctness EWMA for a model, or a neutral 0.5 prior if it has no history yet. */
    public double getModelCorrectness(String modelId) {
        Double c = (modelId == null) ? null : modelCorrectnessEwma.get(modelId);
        return c == null ? 0.5 : c;
    }

    /** Snapshot of per-model correctness EWMA for visibility/UI. */
    public Map<String, Double> getModelCorrectnessSnapshot() {
        return new LinkedHashMap<>(modelCorrectnessEwma);
    }

    /**
     * Compute the per-call timeout (seconds) for a model, bounded by {@code ceilingSeconds}.
     * <ul>
     *   <li>No success history → {@link #probeTimeoutSeconds} (catch hangs fast).</li>
     *   <li>Proven model → {@code ceil(ewmaMs * latencyTimeoutFactor)} clamped to
     *       [{@link #minAdaptiveTimeoutSeconds}, ceiling].</li>
     * </ul>
     */
    public int adaptiveTimeoutSeconds(String modelId, int ceilingSeconds) {
        if (ceilingSeconds <= 0) ceilingSeconds = (int) (modelUnhealthyTtlMs / 1000);
        Double ewma = (modelId == null) ? null : modelLatencyEwmaMs.get(modelId);
        if (ewma == null) {
            return Math.min(ceilingSeconds, probeTimeoutSeconds);
        }
        int derived = (int) Math.ceil(ewma * latencyTimeoutFactor / 1000.0);
        return Math.max(minAdaptiveTimeoutSeconds, Math.min(ceilingSeconds, derived));
    }

    /** Snapshot of per-model EWMA latency (ms) for visibility/UI. */
    public Map<String, Double> getModelLatencySnapshot() {
        return new LinkedHashMap<>(modelLatencyEwmaMs);
    }

    /** True once a model has returned at least one successful (OK) extraction — i.e. it has a latency
     *  history. Used by the rotation to EXPLOIT models that are known to work on real prompts rather
     *  than re-exploring the many that return empty/hang. */
    public boolean isModelProven(String modelId) {
        return modelId != null && modelLatencyEwmaMs.containsKey(modelId);
    }

    // ── On-the-fly tuning knobs (no hardcoding; settable via the model-config API) ──
    public void setOutcomeBackoffMs(ModelOutcome outcome, long ms) {
        if (outcome != null && ms > 0) backoffByOutcomeMs.put(outcome, ms);
    }

    public Map<ModelOutcome, Long> getOutcomeBackoffMs() {
        return new LinkedHashMap<>(backoffByOutcomeMs);
    }

    public void setProbeTimeoutSeconds(int seconds) {
        if (seconds > 0) this.probeTimeoutSeconds = seconds;
    }

    public void setLatencyTimeoutFactor(double factor) {
        if (factor > 0) this.latencyTimeoutFactor = factor;
    }

    public void setMinAdaptiveTimeoutSeconds(int seconds) {
        if (seconds > 0) this.minAdaptiveTimeoutSeconds = seconds;
    }

    /**
     * Returns true if the model is currently considered healthy (no recorded failure TTL, or TTL has passed).
     */
    public boolean isModelHealthy(String modelId) {
        Long until = modelUnhealthyUntilMs.get(modelId);
        if (until == null) return true;
        if (System.currentTimeMillis() >= until) {
            modelUnhealthyUntilMs.remove(modelId); // expired — clean up
            return true;
        }
        return false;
    }

    /**
     * Returns a snapshot of the current model health map (model id → epoch millis until unhealthy).
     * Safe for read-only inspection; changes to the copy do not affect internal state.
     */
    public Map<String, Long> getModelHealthSnapshot() {
        return new LinkedHashMap<>(modelUnhealthyUntilMs);
    }

    /**
     * Select the ordered list of models to use for extraction for the given agent, applying
     * the active policy (providerAllow + excludeMarkers) and health filtering.
     *
     * <p>Healthy models come first in discovery order; unhealthy (but not excluded) models
     * are appended as last-resort so that if everything is unhealthy we still try something
     * rather than returning empty.</p>
     *
     * @param agentName the agent identifier (e.g. "opencode-cli")
     * @return ordered list of model ids; never null; may be empty if discovery returns nothing
     */
    public List<String> selectExtractionModels(String agentName) {
        List<String> all = getModelsForAgent(agentName, false).availableModels();
        if (all == null || all.isEmpty()) {
            return List.of();
        }

        List<String> modelAllow    = activeModelAllow;
        List<String> providerAllow = activeProviderAllow;
        List<String> excludeMarkers = activeExcludeMarkers;

        List<String> filtered;
        if (!modelAllow.isEmpty()) {
            // Explicit pin: intersect discovered ids with the allow-list (preserving allow-list order).
            // ExcludeMarkers still apply so a misconfigured allow-list that names a claude/codex model
            // is safely rejected.
            Set<String> discovered = new HashSet<>(all);
            filtered = modelAllow.stream()
                    .filter(discovered::contains)
                    .filter(id -> {
                        String lower = id.toLowerCase(Locale.ROOT);
                        return excludeMarkers.stream().noneMatch(lower::contains);
                    })
                    .collect(Collectors.toList());
        } else {
            filtered = all.stream()
                    .filter(id -> {
                        // (a) provider-allow filter
                        if (!providerAllow.isEmpty()) {
                            String provider = id.contains("/") ? id.substring(0, id.indexOf('/')) : id;
                            if (!providerAllow.contains(provider)) return false;
                        }
                        // (b) exclude markers
                        String lower = id.toLowerCase(Locale.ROOT);
                        return excludeMarkers.stream().noneMatch(lower::contains);
                    })
                    .collect(Collectors.toList());
        }

        // (c) partition + order so the rotation converges to what actually works:
        //   1. proven-OK healthy (have a recorded success latency) — prefer models we KNOW return clean
        //      output on real prompts, so once one succeeds every later batch reaches it immediately;
        //   2. fresh/unproven healthy — explore the remainder;
        //   3. unhealthy (benched) — last-resort so we still try something if everything is benched.
        List<String> provenHealthy = new ArrayList<>();
        List<String> freshHealthy  = new ArrayList<>();
        List<String> unhealthy     = new ArrayList<>();
        for (String id : filtered) {
            if (!isModelHealthy(id)) {
                unhealthy.add(id);
            } else if (modelLatencyEwmaMs.containsKey(id)) {
                provenHealthy.add(id);
            } else {
                freshHealthy.add(id);
            }
        }
        // Among proven models, prefer the ones that produce the most correct extractions (quality,
        // not just non-empty) so single-model selection and A/B's first picks lead with the best model.
        provenHealthy.sort((a, b) -> Double.compare(getModelCorrectness(b), getModelCorrectness(a)));

        List<String> ordered = new ArrayList<>(provenHealthy.size() + freshHealthy.size() + unhealthy.size());
        ordered.addAll(provenHealthy);
        ordered.addAll(freshHealthy);
        ordered.addAll(unhealthy);
        return Collections.unmodifiableList(ordered);
    }

    /**
     * Pre-flight probe: spawn a one-shot interactive process for each candidate model, send a
     * tiny prompt, classify the outcome, record it in the health map, and return the result map.
     *
     * <p>This is intentionally sequential (one model at a time) to keep subprocess fan-out
     * bounded. Each probe uses a short 45-second timeout; a timeout is classified as ERROR
     * and never crashes the crawl — all exceptions are swallowed, returning an ERROR entry.
     * Only models selected by {@link #selectExtractionModels} (i.e. after policy filtering) are
     * probed, capped to {@code maxModels} (0 = no cap).</p>
     *
     * @param agentName the agent to probe (e.g. "opencode-cli")
     * @param maxModels maximum number of models to probe (0 or negative = no cap)
     * @return map of model-id → outcome; never null; empty if agent not found or no candidates
     */
    public Map<String, ModelOutcome> probeExtractionModels(String agentName, int maxModels) {
        Map<String, ModelOutcome> results = new LinkedHashMap<>();
        Optional<AgentProvider> agentOpt = agentRegistry.getAgent(agentName);
        if (agentOpt.isEmpty()) {
            log.debug("probeExtractionModels: agent '{}' not found", agentName);
            return results;
        }
        AgentProvider agent = agentOpt.get();
        // Force a fresh discovery FIRST: the probe runs at job-queue time, which can be before the
        // opencode model list is warm (cold startup). Without this, selectExtractionModels returns
        // empty and the probe + context-budget silently no-op. refresh=true re-runs `opencode models`.
        getModelsForAgent(agentName, true);
        List<String> candidates = selectExtractionModels(agentName);
        if (candidates.isEmpty()) {
            log.debug("probeExtractionModels: no candidates for agent '{}'", agentName);
            return results;
        }
        if (maxModels > 0 && candidates.size() > maxModels) {
            candidates = candidates.subList(0, maxModels);
        }

        // Probe ALL candidates in parallel so the pre-flight is bounded by the slowest single probe
        // (~PROBE_TIMEOUT_SECONDS) instead of the SUM — a sequential probe of N models would stall the
        // crawl for up to N × timeout before extraction starts. Each probeOneModel bounds + kills its
        // own subprocess on timeout, so a hung model can't block the pre-flight.
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(candidates.size(), 12), r -> {
            Thread t = new Thread(r, "model-preflight-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            Map<String, Future<ModelOutcome>> futures = new LinkedHashMap<>();
            for (String model : candidates) {
                final String m = model;
                futures.put(m, pool.submit(() -> probeOneModel(agent, m)));
            }
            for (Map.Entry<String, Future<ModelOutcome>> e : futures.entrySet()) {
                String model = e.getKey();
                ModelOutcome outcome;
                try {
                    outcome = e.getValue().get(PROBE_TIMEOUT_SECONDS + 10L, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    outcome = ModelOutcome.ERROR;
                }
                recordModelOutcome(model, outcome);
                log.info("probeExtractionModels: agent='{}' model='{}' outcome={}", agentName, model, outcome);
                results.put(model, outcome);
            }
        } finally {
            pool.shutdownNow();
        }
        return results;
    }

    /** Wall-clock timeout for a single model's tiny availability probe. */
    private static final int PROBE_TIMEOUT_SECONDS = 30;

    /**
     * Probe a single model with a tiny prompt and classify the raw output. Bounds itself with a
     * wall-clock timeout and force-kills the subprocess on timeout so a hung model can't block the
     * pre-flight. Never throws — returns {@link ModelOutcome#ERROR} on any failure.
     */
    private ModelOutcome probeOneModel(AgentProvider agent, String model) {
        Process proc = null;
        ExecutorService reader = null;
        try {
            List<String> cmd = subprocessExecutor.buildInteractiveCommand(agent, true, false, null, model);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().putAll(agent.safeEnvironment());
            proc = pb.start();

            try (OutputStream stdin = proc.getOutputStream()) {
                stdin.write("Reply with the single token OK.\n".getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }

            final Process fproc = proc;
            reader = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "probe-stdout-reader");
                t.setDaemon(true);
                return t;
            });
            Future<String> stdoutFuture = reader.submit(() -> {
                StringBuilder sb = new StringBuilder();
                try (InputStream is = fproc.getInputStream();
                     BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(ClaudeStreamParser.stripAnsi(line)).append('\n');
                    }
                }
                return sb.toString();
            });
            reader.shutdown();

            String raw;
            try {
                raw = stdoutFuture.get(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                log.info("probeOneModel: model '{}' timed out after {}s", model, PROBE_TIMEOUT_SECONDS);
                proc.destroyForcibly();
                raw = "";
            }
            boolean blank = raw == null || raw.trim().isEmpty();
            return classifyOutcome(raw, blank);
        } catch (Exception e) {
            log.warn("probeOneModel: exception probing '{}': {}", model, e.getMessage());
            return ModelOutcome.ERROR;
        } finally {
            if (reader != null) reader.shutdownNow();
            if (proc != null) proc.destroyForcibly();
        }
    }

    private Path getConfigPath() {
        return Path.of(System.getProperty("user.home"), ".kompile", "config", "cli-llm-config.json");
    }
}
