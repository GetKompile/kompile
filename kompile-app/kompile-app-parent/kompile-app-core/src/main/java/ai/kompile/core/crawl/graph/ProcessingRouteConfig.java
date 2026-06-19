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

package ai.kompile.core.crawl.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for dynamic processing route decisions during crawl/ingest.
 *
 * <p>Controls two orthogonal routing dimensions:</p>
 * <ol>
 *   <li><b>PDF routing</b>: How PDFs are classified and split between text extraction
 *       and VLM pipelines. When {@code pdfRoutingMode} is {@code AUTO}, the classifier
 *       inspects page resources for images and routes accordingly.</li>
 *   <li><b>Capacity-based fallback</b>: When the preferred processing backend (typically
 *       local GPU models) is at capacity, requests fall back through a chain of
 *       alternative backends (CLI agents, API agents) with rate limiting.</li>
 * </ol>
 *
 * <p>Example JSON for a crawl request:</p>
 * <pre>{@code
 * {
 *   "pdfRoutingMode": "AUTO",
 *   "fallbackEnabled": true,
 *   "backends": [
 *     { "id": "local-vlm", "type": "LOCAL_MODEL", "priority": 1, "maxConcurrent": 1, "maxMemoryBytes": 18000000000 },
 *     { "id": "claude-cli", "type": "CLI_AGENT", "priority": 2, "maxConcurrent": 3, "requestsPerMinute": 30 },
 *     { "id": "openai-api", "type": "API_AGENT", "priority": 3, "maxConcurrent": 10, "requestsPerMinute": 60 }
 *   ]
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProcessingRouteConfig {

    /**
     * PDF routing mode — how PDFs are classified for pipeline selection.
     */
    public enum PdfRoutingMode {
        /** Classify each PDF by inspecting page resources; route text-only to cheap parser, image PDFs to VLM */
        AUTO,
        /** Force all PDFs through VLM pipeline regardless of content */
        FORCE_VLM,
        /** Force all PDFs through text extraction only (no VLM, even for image PDFs) */
        FORCE_TEXT,
        /** Disable PDF-specific routing; use whatever the default pipeline does */
        DISABLED
    }

    /**
     * Processing backend types available for document processing tasks.
     */
    public enum ProcessingBackendType {
        /** Local model (SameDiff/ONNX on GPU or CPU) — cheapest but resource-constrained */
        LOCAL_MODEL,
        /** CLI agent subprocess (Claude Code, Codex, Gemini CLI) — moderate cost, uses local compute for agent but API for model */
        CLI_AGENT,
        /** API endpoint (OpenAI, Anthropic, etc.) — highest cost but virtually unlimited capacity */
        API_AGENT
    }

    /** How to route PDFs between text and VLM pipelines. Default: AUTO */
    @Builder.Default
    private PdfRoutingMode pdfRoutingMode = PdfRoutingMode.AUTO;

    /** Whether capacity-based fallback routing is enabled */
    @Builder.Default
    private boolean fallbackEnabled = false;

    /** Ordered list of processing backends (evaluated by priority, lowest first) */
    @Builder.Default
    private List<ProcessingBackend> backends = new ArrayList<>();

    /** For VLM processing: which VLM model ID to use (null = use default from staging) */
    private String vlmModelId;

    /** For text PDFs: whether to extract tables with Tabula */
    @Builder.Default
    private boolean extractTablesFromTextPdfs = true;

    /** Minimum text chars per page to consider a page text-extractable (used in AUTO mode) */
    @Builder.Default
    private int textThresholdCharsPerPage = 50;

    /**
     * A processing backend that can handle document processing tasks.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProcessingBackend {
        /** Unique identifier for this backend (e.g., "local-vlm", "claude-cli", "openai-api") */
        private String id;

        /** Display name shown in UI */
        private String displayName;

        /** Backend type */
        private ProcessingBackendType type;

        /** Priority for fallback ordering (lower = preferred). Default: 100 */
        @Builder.Default
        private int priority = 100;

        /** Maximum concurrent requests this backend can handle. 0 = unlimited */
        @Builder.Default
        private int maxConcurrent = 0;

        /** Rate limit: maximum requests per minute. 0 = unlimited */
        @Builder.Default
        private int requestsPerMinute = 0;

        /** For LOCAL_MODEL: maximum GPU memory in bytes this backend should use. 0 = auto */
        @Builder.Default
        private long maxMemoryBytes = 0;

        /** For CLI_AGENT: agent provider name (from AgentRegistryService) */
        private String agentName;

        /** For API_AGENT: endpoint URL */
        private String endpointUrl;

        /** For API_AGENT: API key (masked in responses) */
        private String apiKey;

        /** For API_AGENT: model name (e.g., "gpt-4o") */
        private String modelName;

        /** Whether this backend is currently enabled */
        @Builder.Default
        private boolean enabled = true;

        /** Task types this backend can handle (empty = all). Values: "vlm", "llm", "embedding" */
        @Builder.Default
        private List<String> capabilities = new ArrayList<>();

        /** Explicit backup backend ID to fall back to when this backend fails or is quota-exhausted.
         *  When set, the dispatcher tries this specific backend before the general fallback chain. */
        private String backupBackendId;

        /** Cooldown multiplier applied when this backend hits rate limits (default cooldown * this factor).
         *  Higher values keep rate-limited backends offline longer. Default: 3.0 */
        @Builder.Default
        private double rateLimitCooldownMultiplier = 3.0;

        /** Whether this backend should be permanently disabled for a job after quota exhaustion
         *  (as opposed to the standard circuit breaker cooldown). Default: true */
        @Builder.Default
        private boolean disableOnQuotaExhaustion = true;

        /** For CLI_AGENT: per-agent override for the quota exhaustion window in ms.
         *  0 = use the global CliAgentQuotaLedger default (from crawl runtime config). */
        @Builder.Default
        private long quotaWindowOverrideMs = 0;

        /** For CLI_AGENT: max requests allowed per quota window before proactively rerouting.
         *  0 = no request cap (only the time-window and token-cap gates apply). */
        @Builder.Default
        private long maxRequestsPerQuotaWindow = 0;

        /** For CLI_AGENT: max tokens (input+output) allowed per quota window before proactively
         *  rerouting. 0 = no token cap (only the time-window and request-cap gates apply). */
        @Builder.Default
        private long maxTokensPerQuotaWindow = 0;
    }

    /**
     * Snapshot of current processing capacity across all backends.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CapacitySnapshot {
        /** Backend ID */
        private String backendId;

        /** Backend type */
        private ProcessingBackendType type;

        /** Current active request count */
        private int activeRequests;

        /** Maximum concurrent requests */
        private int maxConcurrent;

        /** Requests made in the current minute */
        private int requestsThisMinute;

        /** Rate limit per minute */
        private int requestsPerMinute;

        /** For LOCAL_MODEL: current GPU memory usage in bytes */
        private long gpuMemoryUsed;

        /** For LOCAL_MODEL: total GPU memory in bytes */
        private long gpuMemoryTotal;

        /** Whether this backend can accept more work right now */
        private boolean available;

        /** Human-readable status message */
        private String statusMessage;
    }
}
