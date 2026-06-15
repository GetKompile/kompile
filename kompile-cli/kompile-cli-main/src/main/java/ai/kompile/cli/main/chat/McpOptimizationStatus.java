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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Fetches and caches MCP optimization status for display in the CLI chat REPL.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li><b>Server mode</b> (baseUrl non-null): fetches live status from the
 *       running kompile-app REST API.</li>
 *   <li><b>Local mode</b> (baseUrl null): reads configuration from the local
 *       JSON files written by kompile-app or the config CLI.</li>
 * </ul>
 *
 * <p>All fetch operations are fail-safe: any exception is caught, a warning is
 * logged, and sensible defaults are used so the caller never sees an exception.
 */
public class McpOptimizationStatus {

    private static final Logger logger = LoggerFactory.getLogger(McpOptimizationStatus.class);

    // Default values used when config is unavailable or fetch fails.
    // In local/direct mode the server never auto-creates the file, so we
    // bootstrap it ourselves with enabled=true (matching server-side behaviour).
    private static final boolean DEFAULT_ENABLED = false;
    private static final boolean LOCAL_MODE_DEFAULT_ENABLED = true;
    private static final String DEFAULT_META_TOOL_MODE = "DIRECT";
    private static final int DEFAULT_COMPRESSION_THRESHOLD_CHARS = 4000;
    private static final int DEFAULT_RESULT_CACHE_MAX_ENTRIES = 1000;
    private static final boolean DEFAULT_GATEWAY_ENABLED = false;

    // Local config file paths (relative to ~/.kompile/config/)
    private static final String MCP_OPT_CONFIG_FILENAME = "mcp-optimization-config.json";
    private static final String GATEWAY_RULES_FILENAME = "tool-gateway-rules.json";

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    // Cached state populated by fetch()
    private boolean enabled = DEFAULT_ENABLED;
    private String metaToolMode = DEFAULT_META_TOOL_MODE;
    private int compressionThresholdChars = DEFAULT_COMPRESSION_THRESHOLD_CHARS;
    private int resultCacheMaxEntries = DEFAULT_RESULT_CACHE_MAX_ENTRIES;
    private boolean gatewayEnabled = DEFAULT_GATEWAY_ENABLED;
    private int gatewayRuleCount = 0;

    /**
     * True when running in local/direct mode (no server).  In that case
     * {@link ai.kompile.cli.main.chat.agent.AgenticChatLoop} always applies
     * client-side compression via {@code ToolResponseOptimizer}, regardless of
     * what the config file says.
     */
    private final boolean localCompressionActive;

    /**
     * Constructs a new status fetcher.
     *
     * @param baseUrl      base URL of the kompile-app REST API (e.g.
     *                     {@code "http://localhost:8080"}), or {@code null} for local mode
     * @param objectMapper Jackson mapper used for JSON parsing
     */
    public McpOptimizationStatus(String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        // Local/direct mode always has client-side compression active
        this.localCompressionActive = (this.baseUrl == null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetches (or re-fetches) current MCP optimization and gateway status,
     * populating all internal state. Call this before any accessor.
     *
     * <p>Fail-safe: any I/O or parse error results in defaults being used.
     */
    public void fetch() {
        if (baseUrl != null) {
            fetchFromServer();
        } else {
            fetchFromLocalConfig();
        }
    }

    /**
     * Whether MCP optimization is active.  Returns {@code true} when the server
     * configuration has optimization enabled, or when running in local/direct
     * mode where client-side compression is always active.
     */
    public boolean isEnabled() {
        return enabled || localCompressionActive;
    }

    /**
     * The active meta-tool mode: {@code "DIRECT"}, {@code "DYNAMIC"}, or
     * {@code "HYBRID"}.
     */
    public String getMetaToolMode() {
        return metaToolMode;
    }

    /** Responses shorter than this many characters bypass compression. */
    public int getCompressionThresholdChars() {
        return compressionThresholdChars;
    }

    /** Maximum number of entries in the result-reference cache. */
    public int getResultCacheMaxEntries() {
        return resultCacheMaxEntries;
    }

    /** Whether the tool gateway is enabled. */
    public boolean isGatewayEnabled() {
        return gatewayEnabled;
    }

    /**
     * Returns a one-line human-readable summary of the MCP optimization status.
     * Examples:
     * <ul>
     *   <li>{@code "MCP optimization: HYBRID (compress >4000 chars, cache 1000)"}</li>
     *   <li>{@code "MCP optimization: disabled"}</li>
     * </ul>
     */
    public String getSummaryLine() {
        if (enabled) {
            return String.format("MCP optimization: %s (compress >%d chars, cache %d)",
                    metaToolMode, compressionThresholdChars, resultCacheMaxEntries);
        }
        if (localCompressionActive) {
            // Client-side compression runs unconditionally in direct mode even when
            // the config file has not been written yet or has enabled=false.
            return String.format("MCP optimization: local (compress >%d chars)",
                    compressionThresholdChars);
        }
        return "MCP optimization: disabled";
    }

    /**
     * Returns a one-line human-readable summary of the tool gateway status.
     * Examples:
     * <ul>
     *   <li>{@code "Tool gateway: enabled (3 rules)"}</li>
     *   <li>{@code "Tool gateway: disabled"}</li>
     * </ul>
     */
    public String getGatewaySummaryLine() {
        if (!gatewayEnabled) {
            return "Tool gateway: disabled";
        }
        return String.format("Tool gateway: enabled (%d rule%s)",
                gatewayRuleCount, gatewayRuleCount == 1 ? "" : "s");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Server-mode fetch
    // ─────────────────────────────────────────────────────────────────────────

    private void fetchFromServer() {
        fetchMcpOptimizationFromServer();
        fetchGatewayFromServer();
    }

    private void fetchMcpOptimizationFromServer() {
        String url = baseUrl + "/api/config/mcp-optimization";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.warn("MCP optimization status endpoint returned HTTP {}: {}",
                        response.statusCode(), url);
                return;
            }

            JsonNode root = objectMapper.readTree(response.body());
            applyMcpOptimizationNode(root);

        } catch (Exception e) {
            logger.warn("Failed to fetch MCP optimization status from {}: {}", url, e.getMessage());
        }
    }

    private void fetchGatewayFromServer() {
        String url = baseUrl + "/api/tool-gateway/config";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                logger.warn("Tool gateway config endpoint returned HTTP {}: {}",
                        response.statusCode(), url);
                return;
            }

            JsonNode root = objectMapper.readTree(response.body());
            applyGatewayNode(root);

        } catch (Exception e) {
            logger.warn("Failed to fetch tool gateway status from {}: {}", url, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Local-mode fetch
    // ─────────────────────────────────────────────────────────────────────────

    private void fetchFromLocalConfig() {
        fetchMcpOptimizationFromLocal();
        fetchGatewayFromLocal();
    }

    private void fetchMcpOptimizationFromLocal() {
        Path configPath = kompileConfigDir().resolve(MCP_OPT_CONFIG_FILENAME);
        if (!Files.exists(configPath)) {
            createDefaultLocalConfig(configPath);
            return;
        }
        try {
            String json = Files.readString(configPath);
            JsonNode root = objectMapper.readTree(json);
            applyMcpOptimizationNode(root);
        } catch (Exception e) {
            logger.warn("Failed to read local MCP optimization config from {}: {}",
                    configPath, e.getMessage());
        }
    }

    /**
     * Writes a default {@code mcp-optimization-config.json} to the local config
     * directory, mirroring what the Spring Boot server does in its
     * {@code McpOptimizationConfigService#@PostConstruct}.  The defaults include
     * {@code enabled=true} so subsequent sessions read the correct value.
     */
    private void createDefaultLocalConfig(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());

            ObjectNode defaults = objectMapper.createObjectNode();
            defaults.put("enabled", LOCAL_MODE_DEFAULT_ENABLED);
            defaults.put("compressionThresholdChars", DEFAULT_COMPRESSION_THRESHOLD_CHARS);
            defaults.put("metaToolMode", DEFAULT_META_TOOL_MODE);
            defaults.put("resultCacheMaxEntries", DEFAULT_RESULT_CACHE_MAX_ENTRIES);

            Files.writeString(configPath,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(defaults));
            logger.info("Created default MCP optimization config at {}", configPath);

            // Apply the defaults we just wrote
            this.enabled = LOCAL_MODE_DEFAULT_ENABLED;
            this.compressionThresholdChars = DEFAULT_COMPRESSION_THRESHOLD_CHARS;
            this.metaToolMode = DEFAULT_META_TOOL_MODE;
            this.resultCacheMaxEntries = DEFAULT_RESULT_CACHE_MAX_ENTRIES;

        } catch (Exception e) {
            logger.warn("Could not create default MCP optimization config at {}: {}",
                    configPath, e.getMessage());
            // Fall back to in-memory defaults; localCompressionActive ensures the
            // summary line still reflects reality.
        }
    }

    private void fetchGatewayFromLocal() {
        Path rulesPath = kompileConfigDir().resolve(GATEWAY_RULES_FILENAME);
        if (!Files.exists(rulesPath)) {
            logger.debug("Local tool gateway rules file not found at {}, using defaults", rulesPath);
            return;
        }
        try {
            String json = Files.readString(rulesPath);
            JsonNode root = objectMapper.readTree(json);

            // The rules file does not have a top-level "enabled" flag (that lives in
            // application.properties). We infer enabled=true when the file exists and
            // contains at least one rule, mirroring the CLI --show behaviour.
            JsonNode rulesNode = root.get("rules");
            int ruleCount = (rulesNode != null && rulesNode.isArray()) ? rulesNode.size() : 0;

            // Check for an explicit "enabled" key stored by the wizard
            JsonNode enabledNode = root.get("enabled");
            if (enabledNode != null && enabledNode.isBoolean()) {
                this.gatewayEnabled = enabledNode.booleanValue();
            } else {
                // Fall back: consider enabled if the rules file exists and has rules
                this.gatewayEnabled = ruleCount > 0;
            }
            this.gatewayRuleCount = ruleCount;

        } catch (Exception e) {
            logger.warn("Failed to read local tool gateway rules from {}: {}",
                    rulesPath, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JSON parsing helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Applies fields from an MCP optimization JSON node (from server or local file)
     * to internal state. Only non-null / present fields are updated.
     */
    private void applyMcpOptimizationNode(JsonNode root) {
        JsonNode enabledNode = root.get("enabled");
        if (enabledNode != null && !enabledNode.isNull()) {
            this.enabled = enabledNode.asBoolean(DEFAULT_ENABLED);
        }

        JsonNode modeNode = root.get("metaToolMode");
        if (modeNode != null && !modeNode.isNull() && modeNode.isTextual()) {
            String mode = modeNode.asText();
            if ("DIRECT".equals(mode) || "DYNAMIC".equals(mode) || "HYBRID".equals(mode)) {
                this.metaToolMode = mode;
            }
        }

        JsonNode thresholdNode = root.get("compressionThresholdChars");
        if (thresholdNode != null && !thresholdNode.isNull() && thresholdNode.isNumber()) {
            int val = thresholdNode.asInt(DEFAULT_COMPRESSION_THRESHOLD_CHARS);
            if (val > 0) this.compressionThresholdChars = val;
        }

        JsonNode cacheNode = root.get("resultCacheMaxEntries");
        if (cacheNode != null && !cacheNode.isNull() && cacheNode.isNumber()) {
            int val = cacheNode.asInt(DEFAULT_RESULT_CACHE_MAX_ENTRIES);
            if (val > 0) this.resultCacheMaxEntries = val;
        }
    }

    /**
     * Applies fields from a tool-gateway config JSON node (from server) to
     * internal state.
     */
    private void applyGatewayNode(JsonNode root) {
        // The server returns {"available": false} when the module is not loaded
        JsonNode availableNode = root.get("available");
        if (availableNode != null && availableNode.isBoolean() && !availableNode.booleanValue()) {
            this.gatewayEnabled = false;
            this.gatewayRuleCount = 0;
            return;
        }

        JsonNode enabledNode = root.get("enabled");
        if (enabledNode != null && !enabledNode.isNull()) {
            this.gatewayEnabled = enabledNode.asBoolean(DEFAULT_GATEWAY_ENABLED);
        }

        // Prefer total rulesCount; fall back to enabledRulesCount
        JsonNode rulesCountNode = root.get("rulesCount");
        if (rulesCountNode != null && rulesCountNode.isNumber()) {
            this.gatewayRuleCount = rulesCountNode.asInt(0);
        } else {
            JsonNode enabledCountNode = root.get("enabledRulesCount");
            if (enabledCountNode != null && enabledCountNode.isNumber()) {
                this.gatewayRuleCount = enabledCountNode.asInt(0);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilities
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns the ~/.kompile/config/ directory path. */
    private static Path kompileConfigDir() {
        return Paths.get(System.getProperty("user.home"), ".kompile", "config");
    }

    /**
     * Strips a trailing slash from the base URL, or returns {@code null} if
     * the input is null or blank.
     */
    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
