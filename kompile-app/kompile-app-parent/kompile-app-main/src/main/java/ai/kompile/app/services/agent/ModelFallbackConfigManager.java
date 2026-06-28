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

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Hot-reloadable configuration for the model-fallback layer.
 *
 * <p>Config is persisted at {@code ~/.kompile/config/model-fallback-config.json}
 * and re-read at most once every 5 seconds (mtime-gated). All fields have safe
 * defaults so the feature works out-of-the-box without a config file.</p>
 *
 * <p>Follows the same pattern as {@code CrawlRuntimeConfigManager}: inner DTO
 * with {@code defaults()}, {@code from(JsonNode)}, {@code toMap()}, and an
 * {@code update(Map)} that merges only own keys.</p>
 */
@Component
@Slf4j
public class ModelFallbackConfigManager {

    private static final String CONFIG_FILENAME = "model-fallback-config.json";
    private static final long CONFIG_REFRESH_INTERVAL_NANOS = 5_000_000_000L; // 5 s

    private final Path configPath;
    private final ObjectMapper mapper = JsonUtils.standardMapper();

    private volatile ModelFallbackConfig cachedConfig = ModelFallbackConfig.defaults();
    private volatile long lastMtime = Long.MIN_VALUE;
    private volatile long lastRefreshNanos = 0L;

    // Per-crawl/per-project overrides pushed at job start from GraphExtractionConfig (null = inherit
    // the global file value). Makes the paid-tier guardrails + timeout configurable per job/project,
    // mirroring CliAgentModelService.setActiveExtractionPolicy for the selection policy.
    private volatile Boolean overridePaidFallbackEnabled;
    private volatile Integer overrideMaxPaidCallsPerCrawl;
    private volatile Integer overridePerCallTimeoutSeconds;

    public ModelFallbackConfigManager() {
        this.configPath = KompileHome.configDirectory().toPath().resolve(CONFIG_FILENAME);
    }

    /** Test seam. */
    ModelFallbackConfigManager(Path configPath) {
        this.configPath = configPath;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the current config, re-reading the file at most once per 5 s.
     */
    public synchronized ModelFallbackConfig getConfig() {
        long now = System.nanoTime();
        if ((now - lastRefreshNanos) < CONFIG_REFRESH_INTERVAL_NANOS) {
            return applyOverride(cachedConfig);
        }
        lastRefreshNanos = now;
        try {
            if (!Files.exists(configPath)) {
                lastMtime = Long.MIN_VALUE;
                cachedConfig = ModelFallbackConfig.defaults();
                return applyOverride(cachedConfig);
            }
            long mtime = Files.getLastModifiedTime(configPath).toMillis();
            if (mtime != lastMtime) {
                JsonNode root = mapper.readTree(configPath.toFile());
                cachedConfig = ModelFallbackConfig.from(root);
                lastMtime = mtime;
                log.info("Loaded model-fallback config from {}", configPath);
            }
        } catch (Exception e) {
            log.warn("Failed to read model-fallback-config.json: {}", e.getMessage());
        }
        return applyOverride(cachedConfig);
    }

    /** Apply any active per-crawl/per-project override on top of the global file config. */
    private ModelFallbackConfig applyOverride(ModelFallbackConfig base) {
        if (base == null) return null;
        if (overridePaidFallbackEnabled == null && overrideMaxPaidCallsPerCrawl == null
                && overridePerCallTimeoutSeconds == null) {
            return base;
        }
        return base.withOverrides(overridePaidFallbackEnabled, overrideMaxPaidCallsPerCrawl,
                overridePerCallTimeoutSeconds);
    }

    /**
     * Push per-crawl / per-project overrides of the paid-tier guardrails + per-call timeout, from the
     * crawl's {@code GraphExtractionConfig} at job start. Each {@code null} argument leaves the global
     * file default in effect. Mirrors {@code CliAgentModelService.setActiveExtractionPolicy} so the
     * fallback executor config is configurable per job/project, not only globally.
     */
    public void setActiveExtractionFallbackOverride(Boolean paidFallbackEnabled,
                                                    Integer maxPaidCallsPerCrawl,
                                                    Integer perCallTimeoutSeconds) {
        this.overridePaidFallbackEnabled = paidFallbackEnabled;
        this.overrideMaxPaidCallsPerCrawl = maxPaidCallsPerCrawl;
        this.overridePerCallTimeoutSeconds = perCallTimeoutSeconds;
        log.info("Active extraction fallback override set: paidFallbackEnabled={}, maxPaidCallsPerCrawl={}, perCallTimeoutSeconds={}",
                paidFallbackEnabled, maxPaidCallsPerCrawl, perCallTimeoutSeconds);
    }

    /** Return the effective config as a plain map (for REST responses). */
    public synchronized Map<String, Object> currentConfig() {
        return getConfig().toMap();
    }

    /**
     * Merge-update: overlays {@code updates} onto the existing file, touching
     * only keys that belong to {@link ModelFallbackConfig}. Unknown keys are
     * silently ignored so callers cannot corrupt unrelated config sections.
     *
     * @return the new effective config as a map
     */
    public synchronized Map<String, Object> updateConfig(Map<String, Object> updates) {
        try {
            ObjectNode root;
            if (Files.exists(configPath)) {
                JsonNode existing = mapper.readTree(configPath.toFile());
                root = existing.isObject() ? (ObjectNode) existing : mapper.createObjectNode();
            } else {
                Files.createDirectories(configPath.getParent());
                root = mapper.createObjectNode();
            }

            Set<String> ownKeys = ModelFallbackConfig.defaults().toMap().keySet();
            if (updates != null) {
                for (Map.Entry<String, Object> e : updates.entrySet()) {
                    if (ownKeys.contains(e.getKey())) {
                        root.set(e.getKey(), mapper.valueToTree(e.getValue()));
                    }
                }
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), root);
            // Force immediate re-read on next access.
            lastMtime = Long.MIN_VALUE;
            lastRefreshNanos = 0L;
            return getConfig().toMap();
        } catch (IOException e) {
            log.error("Failed to update model-fallback-config.json: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to update model-fallback config: " + e.getMessage(), e);
        }
    }

    // ── DTO ───────────────────────────────────────────────────────────────────

    /**
     * Represents one entry in the ordered fallback chain.
     */
    public record FallbackEntry(String agentName, String modelId) {

        /** Deserialize from a JSON object node that has "agentName" and "modelId" keys. */
        static FallbackEntry from(JsonNode node) {
            String agent = node.path("agentName").asText("opencode-cli");
            String model = node.path("modelId").asText("");
            return new FallbackEntry(agent, model);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agentName", agentName);
            m.put("modelId", modelId != null ? modelId : "");
            return m;
        }
    }

    /**
     * Immutable config snapshot.
     */
    public static final class ModelFallbackConfig {

        public final boolean enabled;
        public final int perCallTimeoutSeconds;
        public final int maxAttempts;
        public final int throttleBackoffSeconds;
        public final int consecutiveTimeoutsBeforeSwitch;
        public final List<FallbackEntry> fallbackChain;
        public final Set<String> throttleSignals;
        // Paid-tier guardrails (user-mandated): paid models are a gated last resort so a crawl can
        // never silently drain a paid subscription. OFF by default — free-first, degrade+resume when
        // free is exhausted; opt-in per-project/per-job, and capped per crawl when on. The cap is
        // keyed per crawl (jobId) in the executor.
        public final boolean paidFallbackEnabled;
        public final int maxPaidCallsPerCrawl;
        public final Set<String> paidAgents;

        private ModelFallbackConfig(boolean enabled,
                                    int perCallTimeoutSeconds,
                                    int maxAttempts,
                                    int throttleBackoffSeconds,
                                    int consecutiveTimeoutsBeforeSwitch,
                                    List<FallbackEntry> fallbackChain,
                                    Set<String> throttleSignals,
                                    boolean paidFallbackEnabled,
                                    int maxPaidCallsPerCrawl,
                                    Set<String> paidAgents) {
            this.enabled = enabled;
            this.perCallTimeoutSeconds = perCallTimeoutSeconds;
            this.maxAttempts = maxAttempts;
            this.throttleBackoffSeconds = throttleBackoffSeconds;
            this.consecutiveTimeoutsBeforeSwitch = consecutiveTimeoutsBeforeSwitch;
            this.fallbackChain = Collections.unmodifiableList(new ArrayList<>(fallbackChain));
            this.throttleSignals = Collections.unmodifiableSet(new LinkedHashSet<>(throttleSignals));
            this.paidFallbackEnabled = paidFallbackEnabled;
            this.maxPaidCallsPerCrawl = maxPaidCallsPerCrawl;
            this.paidAgents = Collections.unmodifiableSet(new LinkedHashSet<>(paidAgents));
        }

        /**
         * Return a copy with the given per-job/per-project overrides applied. Each {@code null}
         * argument leaves the corresponding global value unchanged. Used to make the paid-tier
         * guardrails + timeout configurable per crawl/project (see
         * {@link ModelFallbackConfigManager#setActiveExtractionFallbackOverride}).
         */
        ModelFallbackConfig withOverrides(Boolean paidEnabled, Integer maxPaid, Integer timeoutSeconds) {
            return new ModelFallbackConfig(
                    enabled,
                    timeoutSeconds != null ? timeoutSeconds : perCallTimeoutSeconds,
                    maxAttempts,
                    throttleBackoffSeconds,
                    consecutiveTimeoutsBeforeSwitch,
                    fallbackChain,
                    throttleSignals,
                    paidEnabled != null ? paidEnabled : paidFallbackEnabled,
                    maxPaid != null ? maxPaid : maxPaidCallsPerCrawl,
                    paidAgents);
        }

        public static ModelFallbackConfig defaults() {
            // NO hardcoded model ids. The live chain is built DYNAMICALLY by the executor from the
            // CLI model catalog (free models via the discovery∩policy∩health selector, paid agents
            // last). This default is ONLY the cold-start fallback for when discovery is unavailable:
            // try the free agent's own default model, then the paid agent's default model LAST. Both
            // modelIds are empty = "use the agent's configured default" (no hardcoded version string).
            List<FallbackEntry> chain = List.of(
                    new FallbackEntry("opencode-cli", ""),
                    new FallbackEntry("claude-cli", "")
            );
            Set<String> signals = new LinkedHashSet<>(Arrays.asList(
                    "rate limit", "429", "quota", "capacity",
                    "too many requests", "throttl", "overload",
                    "retry after", "slowdown"
            ));
            Set<String> paid = new LinkedHashSet<>(Arrays.asList("claude-cli"));
            // maxAttempts 64: the dynamic chain enumerates every available free model, so the cap must
            //   be high enough to traverse the whole rotation before the paid tail (chain exhaustion
            //   terminates earlier for shorter chains).
            // consecutiveTimeoutsBeforeSwitch 1: each chain entry is a DISTINCT model, so advance to
            //   the next model after a single failure rather than retrying the same dead model.
            // paidFallbackEnabled=false (sane global default): free-first, never auto-reach for paid.
            return new ModelFallbackConfig(true, 300, 64, 30, 1, chain, signals,
                    false, 25, paid);
        }

        public static ModelFallbackConfig from(JsonNode root) {
            ModelFallbackConfig d = defaults();

            boolean enabled = root.path("enabled").asBoolean(d.enabled);
            int timeout     = root.path("perCallTimeoutSeconds").asInt(d.perCallTimeoutSeconds);
            int maxAttempts = root.path("maxAttempts").asInt(d.maxAttempts);
            int backoff     = root.path("throttleBackoffSeconds").asInt(d.throttleBackoffSeconds);
            int consec      = root.path("consecutiveTimeoutsBeforeSwitch").asInt(d.consecutiveTimeoutsBeforeSwitch);

            List<FallbackEntry> chain = new ArrayList<>();
            JsonNode chainNode = root.path("fallbackChain");
            if (chainNode.isArray() && chainNode.size() > 0) {
                for (JsonNode n : chainNode) {
                    chain.add(FallbackEntry.from(n));
                }
            } else {
                chain = new ArrayList<>(d.fallbackChain);
            }

            Set<String> signals = new LinkedHashSet<>();
            JsonNode signalsNode = root.path("throttleSignals");
            if (signalsNode.isArray() && signalsNode.size() > 0) {
                for (JsonNode n : signalsNode) {
                    String s = n.asText("").trim();
                    if (!s.isEmpty()) signals.add(s);
                }
            } else {
                signals = new LinkedHashSet<>(d.throttleSignals);
            }

            boolean paidEnabled = root.path("paidFallbackEnabled").asBoolean(d.paidFallbackEnabled);
            int maxPaid = root.path("maxPaidCallsPerCrawl").asInt(d.maxPaidCallsPerCrawl);
            Set<String> paid = new LinkedHashSet<>();
            JsonNode paidNode = root.path("paidAgents");
            if (paidNode.isArray() && paidNode.size() > 0) {
                for (JsonNode n : paidNode) {
                    String s = n.asText("").trim();
                    if (!s.isEmpty()) paid.add(s);
                }
            } else {
                paid = new LinkedHashSet<>(d.paidAgents);
            }

            return new ModelFallbackConfig(enabled, timeout, maxAttempts, backoff, consec, chain, signals,
                    paidEnabled, maxPaid, paid);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("enabled", enabled);
            m.put("perCallTimeoutSeconds", perCallTimeoutSeconds);
            m.put("maxAttempts", maxAttempts);
            m.put("throttleBackoffSeconds", throttleBackoffSeconds);
            m.put("consecutiveTimeoutsBeforeSwitch", consecutiveTimeoutsBeforeSwitch);
            List<Map<String, Object>> chainList = new ArrayList<>();
            for (FallbackEntry e : fallbackChain) {
                chainList.add(e.toMap());
            }
            m.put("fallbackChain", chainList);
            m.put("throttleSignals", new ArrayList<>(throttleSignals));
            m.put("paidFallbackEnabled", paidFallbackEnabled);
            m.put("maxPaidCallsPerCrawl", maxPaidCallsPerCrawl);
            m.put("paidAgents", new ArrayList<>(paidAgents));
            return m;
        }
    }
}
