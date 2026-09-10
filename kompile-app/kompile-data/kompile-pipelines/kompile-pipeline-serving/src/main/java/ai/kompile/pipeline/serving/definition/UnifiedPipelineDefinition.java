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

    /** Current portable definition schema. Increment only for incompatible changes. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public enum LifecycleState {
        DRAFT, ACTIVE, ARCHIVED
    }

    /**
     * The kind of pipeline - determines which builder bridge is used to construct
     * the framework Pipeline from domain-specific config.
     */
    public enum PipelineKind {
        LLM, VLM, RAG, GENERIC, TRANSLATION
    }

    /**
     * Whether the pipeline is a linear sequence or a directed acyclic graph.
     */
    public enum ExecutionTopology {
        SEQUENCE, GRAPH
    }

    // ── Identity ─────────────────────────────────────────────────────────────

    /** Portable definition schema version. */
    @Builder.Default
    private int schemaVersion = CURRENT_SCHEMA_VERSION;

    /** Immutable project-local version assigned by the pipeline store. */
    @Builder.Default
    private long definitionVersion = 1L;

    /** SHA-256 of compatibility-affecting definition content. */
    private String contentDigest;

    /** Unique ID, used as filename under ~/.kompile/data/pipelines/ */
    private String pipelineId;

    /** Human-readable name for UI display */
    private String displayName;

    /** Description of what this pipeline does */
    private String description;

    /** Lifecycle is controlled by version promotion rather than in-place mutation. */
    @Builder.Default
    private LifecycleState lifecycleState = LifecycleState.DRAFT;

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

    /**
     * Optional standalone host processor: CHAT_MODEL with kind LLM or TRANSLATION,
     * topology SEQUENCE, and no pipelineSpec. Translation uses operation=translation
     * and nested translation options; its underlying provider capability is text.
     * Only non-secret selectors/options belong here; credentials remain in host chat configuration.
     * Composed host stages instead live in pipelineSpec with an LLM envelope; mixing
     * host processors and local tensor runners is unsupported.
     */
    private Map<String, Object> processor;

    /** Named input and output contracts exposed to MCP pipeline authors. */
    private Map<String, DataContract> inputs;
    private Map<String, DataContract> outputs;

    // ── Domain-Specific Overlays ─────────────────────────────────────────────

    /** LLM/VLM: the model set ID to use */
    private String modelSetId;

    /**
     * Named model roles used by this pipeline. Keys are pipeline-local roles such as
     * {@code generator}, {@code vision}, {@code embedding}, or {@code reranker}; values
     * reference entries in {@link #modelDefinitions} or a project model id.
     */
    private Map<String, String> modelBindings;

    /**
     * Optional pipeline-local model definitions keyed by registry id. Request-scoped
     * callers may merge a shared model registry into this map before launch.
     */
    private Map<String, Map<String, Object>> modelDefinitions;

    /**
     * Parent-resolved local model artifacts keyed by pipeline role. This is populated
     * immediately before subprocess launch so native and JVM children receive the same
     * model contract without performing their own staging.
     */
    private Map<String, Map<String, Object>> resolvedModels;

    /** VLM only: extraction types (document-understanding, table-extraction, etc.) */
    private List<String> extractionTypes;

    /** LLM generation defaults (maxNewTokens, temperature, topK, etc.) */
    private Map<String, Object> llmConfig;

    /** RAG stage overrides (embeddingModel, vectorStore, rerankingEnabled, etc.) */
    private Map<String, Object> ragConfig;

    // ── Serving Configuration ────────────────────────────────────────────────

    /** Resource configuration for the MCP-owned reusable runtime. */
    private ServingConfig serving;

    /** Runtime capabilities required to execute the definition. */
    private RuntimeRequirements runtimeRequirements;

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

    /** Agent/user provenance for immutable versions. */
    private String createdBy;
    private String updatedBy;
    private String source;

    /** Arbitrary tags for categorization/filtering */
    private Map<String, Object> tags;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DataContract {
        private String type;
        @Builder.Default
        private boolean required = false;
        private String description;
        private Map<String, Object> constraints;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RuntimeRequirements {
        private List<String> capabilities;
        private List<String> runnerTypes;
        private String backend;
        private String device;
        private String memoryRegime;
        private Map<String, Object> options;
    }

    /** Resource configuration for the reusable isolated runtime. */
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

        /** GPU device ID(s) to use ("0", "0,1", "auto") */
        private String gpuDeviceId;
    }
}
