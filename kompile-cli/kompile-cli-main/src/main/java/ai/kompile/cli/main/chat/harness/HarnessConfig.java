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

package ai.kompile.cli.main.chat.harness;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the Agent Performance Harness.
 * Persisted to ~/.kompile/harness-config.json.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class HarnessConfig {

    private static final Path CONFIG_FILE = Path.of(System.getProperty("user.home"),
            ".kompile", "harness-config.json");

    @JsonProperty private boolean enabled = true;
    @JsonProperty private boolean judgeEnabled = true;
    @JsonProperty private String judgeModel = null;
    @JsonProperty private String judgeProvider = null;   // null = same as chat provider
    @JsonProperty private String judgeApiKey = null;     // null = same as chat API key
    @JsonProperty private String judgeBaseUrl = null;    // null = default for judgeProvider
    @JsonProperty private float swapThresholdScore = 2.5f;
    @JsonProperty private int rollingWindowSize = 5;
    @JsonProperty private boolean autoSwapEnabled = true;
    @JsonProperty private List<String> swapCandidateModels = new ArrayList<>();
    @JsonProperty private boolean rateLimitFallbackEnabled = true;
    @JsonProperty private int qualityCooldownMs = 60_000;
    @JsonProperty private int rateLimitCooldownMs = 300_000;
    @JsonProperty private boolean verboseLogging = false;
    @JsonProperty private boolean persistCrossSession = true;
    @JsonProperty private int maxRecordAge = 90;  // days
    @JsonProperty private int maxRecords = 10_000;

    // ── Composite score weights (sum to 1.0) ─────────────────────────
    @JsonProperty private float escapeWeight = 0.25f;
    @JsonProperty private float judgeWeight = 0.50f;
    @JsonProperty private float efficiencyWeight = 0.15f;
    @JsonProperty private float thinkingWeight = 0.10f;

    // ── Judge backend mode ──────────────────────────────────────
    // "auto" (default), "remote", "local", "auto-server"
    @JsonProperty private String judgeMode = null;           // null = auto
    @JsonProperty private String judgeLocalModel = null;     // e.g. "qwen3.5-0.8b" or file path
    @JsonProperty private String judgeLocalQuant = null;     // e.g. "Q4_K_M" (default if null)
    @JsonProperty private String judgeServerType = null;     // "ollama" (default) or "kompile"
    @JsonProperty private int judgeServerPort = 0;           // 0 = default for server type

    // ── Layer toggles ─────────────────────────────────────────────
    @JsonProperty private boolean escapeDetectionEnabled = true;
    @JsonProperty private boolean thinkingAnalysisEnabled = true;
    @JsonProperty private List<String> toolRequiredTaskTypes = List.of("code-review", "exploration", "indexing");

    /**
     * Load config from disk, or return defaults if file doesn't exist.
     */
    public static HarnessConfig load() {
        return load(JsonUtils.standardMapper());
    }

    public static HarnessConfig load(ObjectMapper mapper) {
        if (Files.exists(CONFIG_FILE)) {
            try {
                return mapper.readValue(CONFIG_FILE.toFile(), HarnessConfig.class);
            } catch (IOException e) {
                System.err.println("Warning: Failed to load harness config: " + e.getMessage());
            }
        }
        return new HarnessConfig();
    }

    /**
     * Save config to disk.
     */
    public void save() {
        save(JsonUtils.standardMapper());
    }

    public void save(ObjectMapper mapper) {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(CONFIG_FILE.toFile(), this);
        } catch (IOException e) {
            System.err.println("Warning: Failed to save harness config: " + e.getMessage());
        }
    }

    public static Path getConfigFilePath() {
        return CONFIG_FILE;
    }
}
