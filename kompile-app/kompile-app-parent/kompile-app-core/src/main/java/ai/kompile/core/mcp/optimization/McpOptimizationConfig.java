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

package ai.kompile.core.mcp.optimization;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for MCP token-saving optimization. Persisted to JSON for
 * retention across restarts. Controlled via the Settings UI
 * ({@code /api/config/mcp-optimization}) — no Spring properties.
 *
 * <p>Controls three complementary layers:
 * <ol>
 *   <li>Surgical caps on the largest raw-response offenders (RAG, filesystem,
 *       knowledge graph).</li>
 *   <li>A generic response-compression / result-reference cache that turns
 *       oversized payloads into handles the caller can later fetch.</li>
 *   <li>A dynamic-toolsets meta-tool mode that collapses the advertised tool
 *       surface to {@code search_tools} / {@code describe_tools} /
 *       {@code execute_tool}.</li>
 * </ol>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpOptimizationConfig {

    /**
     * Controls how the advertised tool surface is shaped per MCP session.
     */
    public enum MetaToolMode {
        /** Expose every discovered {@code @Tool} bean directly (pre-optimization behavior). */
        DIRECT,
        /** Expose only meta-tools and the {@code alwaysExposedTools} allow-list. */
        DYNAMIC,
        /** Meta-tools + allow-list + a small built-in whitelist (rag_query, read_file, list_files). */
        HYBRID
    }

    /**
     * Per-tool override so specific tools can be force-enabled / force-disabled
     * for compression or dynamic-mode exposure regardless of defaults.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolOverride {
        /** If non-null, forces compression on/off for this tool. */
        private Boolean compressionEnabled;
        /** If non-null, overrides {@link #compressionThresholdChars} for this tool. */
        private Integer maxResponseChars;
        /** If non-null, forces this tool to appear / disappear in DYNAMIC/HYBRID mode. */
        private Boolean exposeInDynamicMode;
    }

    /**
     * Master switch. When {@code false} every compression and meta-tool layer
     * short-circuits to passthrough behavior.
     */
    private Boolean enabled;

    // ═══════════════════════════════════════════════════════════════════════════
    // RAG TOOL CAPS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Max characters of document content per retrieved doc in RAG responses. */
    private Integer ragMaxContentChars;

    /** Max number of documents returned per RAG query. */
    private Integer ragMaxDocs;

    // ═══════════════════════════════════════════════════════════════════════════
    // FILESYSTEM TOOL CAPS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * When true, write/delete operations stash the previous content in the
     * result-reference cache and return a handle instead of inlining the bytes.
     */
    private Boolean filesystemStorePreviousContentInCache;

    /** TTL for filesystem undo handles, in seconds. */
    private Long filesystemUndoTtlSeconds;

    // ═══════════════════════════════════════════════════════════════════════════
    // KNOWLEDGE GRAPH TOOL CAPS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Max characters of description / source-context text returned per KG node. */
    private Integer knowledgeGraphTruncateChars;

    // ═══════════════════════════════════════════════════════════════════════════
    // COMPRESSION + RESULT CACHE
    // ═══════════════════════════════════════════════════════════════════════════

    /** Responses whose serialized length is below this threshold skip compression. */
    private Integer compressionThresholdChars;

    /** Max entries in the result-reference cache before LRU-style eviction. */
    private Integer resultCacheMaxEntries;

    /** TTL for stored result references, in seconds. */
    private Long resultCacheTtlSeconds;

    // ═══════════════════════════════════════════════════════════════════════════
    // DYNAMIC TOOLSETS
    // ═══════════════════════════════════════════════════════════════════════════

    /** How the advertised tool surface is shaped per MCP session. */
    private MetaToolMode metaToolMode;

    /**
     * Tool names that should always remain directly visible in {@code DYNAMIC}
     * / {@code HYBRID} mode alongside the meta-tools.
     */
    private List<String> alwaysExposedTools;

    /** Per-tool overrides keyed by tool name. */
    private Map<String, ToolOverride> toolOverrides;

    /**
     * Creates a default configuration. All layers are enabled, meta-tool mode
     * defaults to {@link MetaToolMode#HYBRID}, and the result cache is sized
     * for modest agent session lengths.
     */
    public static McpOptimizationConfig defaults() {
        return McpOptimizationConfig.builder()
                .enabled(true)
                .ragMaxContentChars(2000)
                .ragMaxDocs(3)
                .filesystemStorePreviousContentInCache(true)
                .filesystemUndoTtlSeconds(3600L)
                .knowledgeGraphTruncateChars(200)
                .compressionThresholdChars(4000)
                .resultCacheMaxEntries(1000)
                .resultCacheTtlSeconds(900L)
                .metaToolMode(MetaToolMode.HYBRID)
                .alwaysExposedTools(new ArrayList<>())
                .toolOverrides(new LinkedHashMap<>())
                .build();
    }

    /**
     * Merges non-null values from another config into this one.
     */
    public McpOptimizationConfig merge(McpOptimizationConfig other) {
        if (other == null) {
            return this;
        }
        return McpOptimizationConfig.builder()
                .enabled(other.enabled != null ? other.enabled : this.enabled)
                .ragMaxContentChars(
                        other.ragMaxContentChars != null ? other.ragMaxContentChars : this.ragMaxContentChars)
                .ragMaxDocs(other.ragMaxDocs != null ? other.ragMaxDocs : this.ragMaxDocs)
                .filesystemStorePreviousContentInCache(
                        other.filesystemStorePreviousContentInCache != null
                                ? other.filesystemStorePreviousContentInCache
                                : this.filesystemStorePreviousContentInCache)
                .filesystemUndoTtlSeconds(
                        other.filesystemUndoTtlSeconds != null
                                ? other.filesystemUndoTtlSeconds
                                : this.filesystemUndoTtlSeconds)
                .knowledgeGraphTruncateChars(
                        other.knowledgeGraphTruncateChars != null
                                ? other.knowledgeGraphTruncateChars
                                : this.knowledgeGraphTruncateChars)
                .compressionThresholdChars(
                        other.compressionThresholdChars != null
                                ? other.compressionThresholdChars
                                : this.compressionThresholdChars)
                .resultCacheMaxEntries(
                        other.resultCacheMaxEntries != null
                                ? other.resultCacheMaxEntries
                                : this.resultCacheMaxEntries)
                .resultCacheTtlSeconds(
                        other.resultCacheTtlSeconds != null
                                ? other.resultCacheTtlSeconds
                                : this.resultCacheTtlSeconds)
                .metaToolMode(other.metaToolMode != null ? other.metaToolMode : this.metaToolMode)
                .alwaysExposedTools(
                        other.alwaysExposedTools != null ? other.alwaysExposedTools : this.alwaysExposedTools)
                .toolOverrides(other.toolOverrides != null ? other.toolOverrides : this.toolOverrides)
                .build();
    }
}
