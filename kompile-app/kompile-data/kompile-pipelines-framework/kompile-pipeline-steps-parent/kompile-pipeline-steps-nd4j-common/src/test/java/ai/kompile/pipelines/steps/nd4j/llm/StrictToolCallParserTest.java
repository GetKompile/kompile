/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.pipelines.steps.nd4j.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrictToolCallParserTest {

    @Test
    void acceptsOnlyACompleteDeclaredJsonEnvelope() {
        StrictToolCallParser.ParseResult valid = StrictToolCallParser.parseJson(
                "{\"toolName\":\"lookup\",\"arguments\":{\"query\":\"Mira\"}}",
                List.of("lookup"));
        StrictToolCallParser.ParseResult marked = StrictToolCallParser.parseJson(
                "<tool_call_json>{\"toolName\":\"lookup\","
                        + "\"arguments\":{\"query\":\"Mira\"}}</tool_call_json>",
                List.of("lookup"));
        StrictToolCallParser.ParseResult prose = StrictToolCallParser.parseJson(
                "I will call lookup({\"query\":\"Mira\"})", List.of("lookup"));
        StrictToolCallParser.ParseResult undeclared = StrictToolCallParser.parseJson(
                "{\"toolName\":\"delete\",\"arguments\":{}}", List.of("lookup"));

        assertEquals(1, valid.getToolCalls().size());
        assertEquals("lookup", valid.getToolCalls().get(0).getName());
        assertTrue(marked.getToolCalls().isEmpty());
        assertFalse(marked.getErrors().isEmpty());
        assertTrue(prose.getToolCalls().isEmpty());
        assertTrue(undeclared.getToolCalls().isEmpty());
        assertEquals(List.of("undeclared tool delete"), undeclared.getErrors());
    }
}
