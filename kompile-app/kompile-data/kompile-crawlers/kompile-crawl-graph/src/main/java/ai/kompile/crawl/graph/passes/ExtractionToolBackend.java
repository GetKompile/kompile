/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.crawl.graph.passes;

import ai.kompile.core.crawl.graph.GraphExtractionConfig.DecomposedPromptTier;
import ai.kompile.core.crawl.graph.GraphExtractionConfig.ExtractionTarget;
import ai.kompile.core.graphrag.GraphConstructor.ExtractionTaskContext;
import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractionResult;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Staged tools available to one graph-extraction model loop.
 *
 * <p>Read tools may inspect the unified corpus and current graph. Validator-clean facts from a
 * write proposal may be retained independently while rejected facts remain eligible for correction.
 * The retained delta is staged only: callers merge it after the loop returns, so neither rejected nor
 * partially accepted proposals can dirty the live graph during model execution.</p>
 */
public interface ExtractionToolBackend {

    /** Fact families accepted by this staged backend. */
    default ExtractionTarget extractionTarget() {
        return ExtractionTarget.FULL_GRAPH;
    }

    /** Compact, model-facing JSON catalog. It may contain project schema but never example facts. */
    String catalogJson();

    /**
     * Context-tiered catalog for small models. Implementations may reveal optional capabilities
     * progressively while preserving the same executable tools and backend validation.
     */
    default String catalogJson(DecomposedPromptTier tier) {
        return catalogJson();
    }

    /**
     * Standard function definitions for model-native tool calling. Backends that expose this
     * contract keep tool semantics and validation in one place while allowing each tokenizer's
     * own chat template to choose the wire protocol.
     */
    default List<ToolDefinition> toolDefinitions(DecomposedPromptTier tier) {
        return List.of();
    }

    /**
     * Dynamic state supplied alongside native tool definitions. This is deliberately separate
     * from {@link #catalogJson(DecomposedPromptTier)}, whose legacy call-shape instructions would
     * conflict with a model-authored native tool protocol.
     */
    default String toolContextJson(DecomposedPromptTier tier) {
        return "{}";
    }

    /**
     * Task-aware state for a scheduled shard. Pre-pass concepts may prioritize schema context but
     * remain hints rather than evidence; implementations must preserve the same validation contract.
     */
    default String toolContextJson(
            DecomposedPromptTier tier, ExtractionTaskContext taskContext) {
        return toolContextJson(tier);
    }

    /** Execute one model-requested tool call. Implementations return JSON in {@link ToolExecution#json()}. */
    ToolExecution execute(String toolName, JsonNode arguments);

    /**
     * The accumulated validator-clean staged delta, present after any fact has been retained.
     * It may be returned after terminal success or bounded retry exhaustion.
     */
    Optional<ExtractionResult> acceptedResult();

    record ToolDefinition(String name, String description, Map<String, Object> parameters) {
        public ToolDefinition {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("tool name must not be blank");
            }
            description = description == null ? "" : description;
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }

    record ToolExecution(String json, boolean terminal) {
        public ToolExecution {
            json = json == null || json.isBlank()
                    ? "{\"ok\":false,\"error\":\"tool returned no result\"}"
                    : json;
        }

        public static ToolExecution continuing(String json) {
            return new ToolExecution(json, false);
        }

        public static ToolExecution terminal(String json) {
            return new ToolExecution(json, true);
        }
    }
}
