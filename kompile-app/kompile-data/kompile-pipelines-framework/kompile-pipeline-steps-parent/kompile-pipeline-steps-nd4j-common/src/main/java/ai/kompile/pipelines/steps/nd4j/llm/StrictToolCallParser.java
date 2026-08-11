/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.pipelines.steps.nd4j.llm;

import org.eclipse.deeplearning4j.llm.generation.ToolCallParser;
import org.eclipse.deeplearning4j.llm.tokenizer.ChatTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Shared bridge from pipeline tool declarations to SameDiff's canonical,
 * fail-closed tool-call parser.
 */
public final class StrictToolCallParser {

    private StrictToolCallParser() {
    }

    /**
     * Parse exactly one complete JSON tool-call envelope.
     *
     * <p>No marker extraction, fenced-JSON recovery, substring scan, or
     * cross-protocol fallback is performed.</p>
     */
    public static ToolCallParser.ParseResult parseJson(
            String rawText, Collection<String> declaredToolNames) {
        List<ChatTemplate.Tool> tools = declaredToolNames == null
                ? List.of()
                : declaredToolNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .map(name -> ChatTemplate.Tool.function(name, "", Map.of()))
                .toList();
        return ToolCallParser.parse(
                rawText, tools, ChatTemplate.ToolCallFormat.JSON);
    }
}
