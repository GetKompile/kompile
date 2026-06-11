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

package ai.kompile.toolgateway.config;

import lombok.Data;

/**
 * Configuration properties for the tool gateway.
 */
@Data
public class ToolGatewayProperties {

    /**
     * Master switch. When false, the gateway is completely bypassed and
     * no LLM calls are made for tool interception.
     */
    private boolean enabled = false;

    /**
     * Path to the rules JSON file.
     * Default: {@code ~/.kompile/config/tool-gateway-rules.json}
     */
    private String rulesFilePath;

    /**
     * Whether to fail open (ALLOW) when the LLM evaluation throws an error
     * or returns an unparseable response. When false, failures result in BLOCK.
     */
    private boolean failOpen = true;

    /**
     * Timeout in milliseconds for the LLM evaluation call.
     * If the LLM doesn't respond within this time, the fail-open/fail-closed
     * policy applies.
     */
    private long evaluationTimeoutMs = 10000;

    /**
     * Whether to log every gateway evaluation (tool name, matched rule, decision)
     * at INFO level. Useful for auditing. When false, only BLOCK/REWRITE decisions
     * are logged at INFO; ALLOW is logged at DEBUG.
     */
    private boolean verboseLogging = false;

    /**
     * Whether to reload the rules file on every tool call. Useful during
     * development. In production, rules are loaded once at startup and
     * can be reloaded via the REST API or by calling
     * {@link ai.kompile.toolgateway.service.ToolGatewayRulesProvider#reload()}.
     */
    private boolean hotReload = false;

    /**
     * When true, the gateway runs in dry-run mode: it evaluates rules and
     * logs decisions but never actually blocks or rewrites. Useful for
     * testing new rules without impacting tool execution.
     */
    private boolean dryRun = false;

    /**
     * Dedicated LLM model configuration for the gateway evaluator.
     * <p>
     * When {@code model.baseUrl} is set, the gateway creates its own ChatModel
     * instance pointing to that endpoint instead of using the application's
     * global ChatModel bean. This allows the gateway to use a different model
     * (e.g., a local kompile-serve instance) than the main RAG application.
     * </p>
     * <p>
     * When not configured, the gateway falls back to whatever ChatModel bean
     * is available in the Spring context (OpenAI, Anthropic, Gemini, etc.).
     * </p>
     */
    private ModelConfig model = new ModelConfig();

    /**
     * Returns the effective rules file path, defaulting to ~/.kompile/config/tool-gateway-rules.json.
     */
    public String getEffectiveRulesFilePath() {
        if (rulesFilePath != null && !rulesFilePath.isBlank()) {
            return rulesFilePath;
        }
        return System.getProperty("user.home") + "/.kompile/config/tool-gateway-rules.json";
    }

    /**
     * Dedicated model configuration for the gateway's LLM evaluator.
     */
    @Data
    public static class ModelConfig {

        /**
         * Base URL of the OpenAI-compatible API endpoint.
         * <p>
         * Examples:
         * <ul>
         *   <li>{@code http://localhost:8090} — local kompile-serve instance</li>
         *   <li>{@code http://localhost:11434} — Ollama</li>
         *   <li>{@code https://api.openai.com} — OpenAI API</li>
         * </ul>
         * When null or blank, the gateway uses the application's global ChatModel bean.
         */
        private String baseUrl;

        /**
         * API key for the dedicated endpoint. Required for cloud APIs (OpenAI,
         * Anthropic). For local servers (kompile-serve, Ollama), set to any
         * non-empty value (e.g., "kompile").
         */
        private String apiKey;

        /**
         * Model name to request from the endpoint.
         * For kompile-serve, this is typically the loaded model's ID.
         * For OpenAI, e.g., "gpt-4o-mini". For Ollama, e.g., "llama3".
         */
        private String modelName;

        /**
         * Temperature for the gateway LLM evaluation (0.0 = deterministic).
         * Lower is better for rule evaluation since we want consistent decisions.
         */
        private double temperature = 0.0;

        /**
         * Whether a dedicated model endpoint is configured.
         */
        public boolean isConfigured() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }
}
