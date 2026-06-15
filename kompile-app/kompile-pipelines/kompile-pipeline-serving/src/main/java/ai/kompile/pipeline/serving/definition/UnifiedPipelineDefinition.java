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

package ai.kompile.pipeline.serving.definition;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Unified pipeline definition that wraps the framework Pipeline JSON with metadata
 * and serving configuration. This is the single definition format used by the UI,
 * CLI, and subprocess execution.
 *
 * <p>The {@link #pipelineSpec} field holds the raw JSON of a framework {@code Pipeline}
 * object (SequencePipeline or GraphPipeline). The {@code @JsonTypeInfo(use=CLASS)}
 * discriminator is preserved in the map. When the subprocess loads the definition,
 * it reconstructs the Pipeline via Jackson {@code convertValue()}.</p>
 *
 * <p>Domain-specific overlays ({@link #llmConfig}, {@link #ragConfig}, etc.) are used
 * by bridge classes to construct the pipelineSpec from higher-level definitions.
 * For GENERIC pipelines, only pipelineSpec matters.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UnifiedPipelineDefinition {

    /**
     * The kind of pipeline - determines which builder bridge is used to construct
     * the framework Pipeline from domain-specific config.
     */
    public enum PipelineKind {
        LLM, VLM, RAG, GENERIC
    }

    /**
     * Whether the pipeline is a linear sequence or a directed acyclic graph.
     */
    public enum ExecutionTopology {
        SEQUENCE, GRAPH
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    /** Unique ID, used as filename under ~/.kompile/data/pipelines/ */
    private String pipelineId;

    /** Human-readable name for UI display */
    private String displayName;

    /** Description of what this pipeline does */
    private String description;

    /** Pipeline kind - determines execution strategy and builder bridge */
    private PipelineKind kind;

    /** Execution topology - sequence (linear) or graph (DAG) */
    private ExecutionTopology topology;

    // ── Framework Pipeline Payload ───────────────────────────────────────────

    /**
     * Raw Jackson tree of the framework Pipeline object. Contains the full
     * {@code @class} discriminator so it can be deserialized to either
     * SequencePipeline or GraphPipeline in the subprocess.
     *
     * <p>Stored as a raw map to avoid requiring step runner classes on the
     * classpath at registration time (UI/CLI don't have them). Validation
     * happens lazily in the subprocess.</p>
     */
    private Map<String, Object> pipelineSpec;

    // ── Domain-Specific Overlays ─────────────────────────────────────────────

    /** LLM/VLM: the model set ID to use */
    private String modelSetId;

    /** VLM only: extraction types (document-understanding, table-extraction, etc.) */
    private List<String> extractionTypes;

    /** LLM generation defaults (maxNewTokens, temperature, topK, etc.) */
    private Map<String, Object> llmConfig;

    /** RAG stage overrides (embeddingModel, vectorStore, rerankingEnabled, etc.) */
    private Map<String, Object> ragConfig;

    // ── Serving Configuration ────────────────────────────────────────────────

    /** Configuration for subprocess-based serving */
    private ServingConfig serving;

    // ── Metadata ─────────────────────────────────────────────────────────────

    /** Whether this is a builtin (non-deletable) pipeline definition */
    private boolean builtin;

    /** Whether this pipeline is enabled for serving */
    @Builder.Default
    private boolean enabled = true;

    /** ISO-8601 creation timestamp */
    private String createdAt;

    /** ISO-8601 last update timestamp */
    private String updatedAt;

    /** Arbitrary tags for categorization/filtering */
    private Map<String, Object> tags;

    /**
     * Serving configuration that controls how the subprocess is launched and monitored.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ServingConfig {

        /** JVM heap size for the subprocess (e.g., "8g", "16g") */
        @Builder.Default
        private String heapSize = "8g";

        /** HTTP port for persistent serving (0 = auto-assign) */
        @Builder.Default
        private int port = 0;

        /** Number of subprocess replicas (1 = single process) */
        @Builder.Default
        private int replicas = 1;

        /** GPU device ID(s) to use ("0", "0,1", "auto") */
        private String gpuDeviceId;

        /** JVM heap stop threshold percent (trigger graceful stop) */
        @Builder.Default
        private int memoryStopPercent = 80;

        /** JVM heap critical threshold percent (trigger GC + warning) */
        @Builder.Default
        private int memoryCriticalPercent = 90;

        /** JVM heap kill threshold percent (force exit) */
        @Builder.Default
        private int memoryKillPercent = 95;

        /** GPU memory stop threshold percent */
        @Builder.Default
        private int gpuStopPercent = 80;

        /** GPU memory critical threshold percent */
        @Builder.Default
        private int gpuCriticalPercent = 90;

        /** GPU memory kill threshold percent */
        @Builder.Default
        private int gpuKillPercent = 95;

        /** Heartbeat interval in milliseconds */
        @Builder.Default
        private long heartbeatIntervalMs = 3000;

        /** Time without heartbeat before subprocess is considered stale */
        @Builder.Default
        private long staleTimeoutMs = 120000;

        /** Maximum restart attempts after failure */
        @Builder.Default
        private int maxRestartAttempts = 3;
    }
}
