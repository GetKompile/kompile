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

package ai.kompile.toolgateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * JSON-serializable configuration for the tool gateway.
 * Persisted at {@code ~/.kompile/config/tool-gateway-config.json}.
 * <p>
 * The enabled/disabled state lives in {@code feature-flags-config.json}
 * under the key {@code toolGatewayEnabled}, not here.
 * </p>
 * <p>
 * Model resolution uses the existing kompile infrastructure:
 * <ul>
 *   <li>{@code STAGING} — uses the kompile-model-staging server
 *       (managed by {@code StagingServerLifecycleService}, discovered
 *       by {@code KompileLocalModelService})</li>
 *   <li>{@code GLOBAL} — uses whatever global Spring AI {@code ChatModel}
 *       bean is configured (OpenAI, Anthropic, Gemini, etc.)</li>
 * </ul>
 * </p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ToolGatewayConfig {

    /**
     * Where to send LLM evaluation requests.
     * <ul>
     *   <li>{@code STAGING} — kompile-model-staging (default, uses existing subprocess infra)</li>
     *   <li>{@code GLOBAL} — application's global ChatModel bean</li>
     * </ul>
     */
    @JsonProperty("modelSource")
    private ModelSource modelSource = ModelSource.STAGING;

    /**
     * Whether to fail open (ALLOW) when the LLM evaluation throws an error
     * or returns an unparseable response. When false, failures result in BLOCK.
     */
    @JsonProperty("failOpen")
    private boolean failOpen = true;

    /**
     * Timeout in milliseconds for the LLM evaluation call.
     */
    @JsonProperty("evaluationTimeoutMs")
    private long evaluationTimeoutMs = 10000;

    /**
     * Whether to log every gateway evaluation at INFO level.
     */
    @JsonProperty("verboseLogging")
    private boolean verboseLogging = false;

    /**
     * Whether to reload the rules file on every tool call.
     */
    @JsonProperty("hotReload")
    private boolean hotReload = false;

    /**
     * When true, the gateway evaluates rules and logs decisions but
     * never actually blocks or rewrites.
     */
    @JsonProperty("dryRun")
    private boolean dryRun = false;

    /**
     * Whether to run judge quality scoring on gateway decisions.
     * When enabled, each gateway evaluation is itself scored for quality
     * using the judge LLM (correctness, completeness, reasoning).
     */
    @JsonProperty("judgeScoringEnabled")
    private boolean judgeScoringEnabled = false;

    /**
     * Where to route LLM evaluation requests.
     */
    public enum ModelSource {
        /** Use kompile-model-staging (existing subprocess/serving infra) */
        STAGING,
        /** Use the application's global ChatModel bean */
        GLOBAL
    }

    /**
     * Create a config with sensible defaults.
     */
    public static ToolGatewayConfig defaults() {
        return new ToolGatewayConfig();
    }

    /**
     * Merge a loaded config with defaults: loaded values take precedence,
     * but missing fields are filled from defaults.
     */
    public ToolGatewayConfig merge(ToolGatewayConfig loaded) {
        if (loaded == null) return this;

        ToolGatewayConfig merged = new ToolGatewayConfig();
        merged.setModelSource(loaded.modelSource != null ? loaded.modelSource : this.modelSource);
        merged.setFailOpen(loaded.failOpen);
        merged.setEvaluationTimeoutMs(loaded.evaluationTimeoutMs > 0
                ? loaded.evaluationTimeoutMs : this.evaluationTimeoutMs);
        merged.setVerboseLogging(loaded.verboseLogging);
        merged.setHotReload(loaded.hotReload);
        merged.setDryRun(loaded.dryRun);
        merged.setJudgeScoringEnabled(loaded.judgeScoringEnabled);
        return merged;
    }
}
