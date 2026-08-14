/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawl.graph;

import ai.kompile.core.agent.CliAgentRunner;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.graphrag.GraphConstants;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadlessUnifiedCorpusExtractorTest {

    @Test
    void routesLocalCorpusThroughProductionOrchestratorAndCliDispatcher() {
        AtomicReference<String> selectedAgent = new AtomicReference<>();
        AtomicReference<String> receivedPrompt = new AtomicReference<>();
        CliAgentRunner runner = (agentName, prompt, timeoutSeconds) -> {
            selectedAgent.set(agentName);
            receivedPrompt.set(prompt);
            if (prompt.contains("AUTHORITATIVE EXISTING SCHEMA")
                    && prompt.contains("CORPUS PASSAGES")) {
                return """
                        {"nodeTypes":["ORGANIZATION"],
                         "relationshipTypes":["ACQUIRED"],
                         "patterns":[{"sourceType":"ORGANIZATION",
                                      "relationshipType":"ACQUIRED",
                                      "targetType":"ORGANIZATION"}]}
                        """;
            }
            return """
                    {"$schema":"kompile-graph-extraction/v1","entities":[
                      {"id":"acme","name":"Acme","type":"ORGANIZATION","description":"Buyer","confidence":0.95},
                      {"id":"initech","name":"Initech","type":"ORGANIZATION","description":"Target","confidence":0.92}
                    ],"relations":[
                      {"source":"acme","target":"initech","type":"ACQUIRED","description":"Acme acquired Initech","confidence":0.9}
                    ]}
                    """;
        };
        ProcessingRouteConfig route = ProcessingRouteConfig.builder()
                .fallbackEnabled(true)
                .servingLaneEnabled(false)
                .backends(List.of(ProcessingRouteConfig.ProcessingBackend.builder()
                        .id("opencode-cli")
                        .displayName("OpenCode CLI")
                        .type(ProcessingRouteConfig.ProcessingBackendType.CLI_AGENT)
                        .agentName("opencode-cli")
                        .priority(1)
                        .capabilities(List.of("llm"))
                        .build()))
                .build();
        GraphExtractionConfig extraction = GraphExtractionConfig.builder()
                .entityTypes(List.of("ORGANIZATION"))
                .relationshipTypes(List.of("ACQUIRED"))
                .minConfidence(0.0)
                .entityResolution(false)
                .build();

        try (HeadlessUnifiedCorpusExtractor extractor =
                     new HeadlessUnifiedCorpusExtractor(runner, null, 1)) {
            HeadlessUnifiedCorpusExtractor.Result result = extractor.extract(
                    List.of(new Document("Acme acquired Initech.",
                            Map.of(GraphConstants.META_SOURCE_PATH, "deals.txt"))),
                    extraction,
                    route,
                    "local-test",
                    42L);

            assertFalse(result.failed(), () -> "Unexpected extraction errors: " + result.errors());
            assertNotNull(result.graph());
            assertEquals(2, result.graph().getEntities().size());
            assertEquals(1, result.graph().getRelationships().size());
            assertEquals("opencode-cli", selectedAgent.get());
            assertTrue(receivedPrompt.get().contains("Acme acquired Initech"));
        }
    }
}
