/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.project;

import ai.kompile.core.llm.StructuredChatLanguageModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocalCrawlServingSessionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void structuredResponsePreservesReasoningAcrossLocalServingBridge() throws Exception {
        StructuredChatLanguageModel.Response response =
                LocalCrawlServingSession.structuredResponse(MAPPER.readTree("""
                        {
                          "finishReason": "completed",
                          "rawText": "<think>inspect source</think><native>",
                          "content": "",
                          "reasoningContent": "inspect source",
                          "outputBlocks": [
                            {"type": "think", "content": "inspect source"},
                            {"type": "analysis", "content": "cite source"}
                          ],
                          "toolCalls": [{
                            "id": "call-1",
                            "name": "submit_graph_delta",
                            "arguments": {"entities": [], "relations": []}
                          }],
                          "parseErrors": []
                        }
                        """));

        assertEquals("<think>inspect source</think><native>", response.rawText());
        assertEquals("", response.content());
        assertEquals("inspect source", response.reasoningContent());
        assertEquals(2, response.outputBlocks().size());
        assertEquals("think", response.outputBlocks().get(0).type());
        assertEquals("analysis", response.outputBlocks().get(1).type());
        assertEquals("cite source", response.outputBlocks().get(1).content());
        assertEquals("submit_graph_delta", response.toolCalls().get(0).name());
        assertEquals(0, ((java.util.List<?>) response.toolCalls().get(0)
                .arguments().get("entities")).size());
        assertEquals(0, response.parseErrors().size());
    }
}
