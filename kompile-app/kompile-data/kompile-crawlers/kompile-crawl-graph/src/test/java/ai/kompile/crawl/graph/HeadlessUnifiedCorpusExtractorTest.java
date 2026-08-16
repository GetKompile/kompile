/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.crawl.graph;

import ai.kompile.core.agent.CliAgentRunner;
import ai.kompile.core.crawl.graph.ExtractionMode;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.LocalServingBackend;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void deterministicToolRejectionIsNotReplayedByOuterCrawlRetries() {
        AtomicInteger modelCalls = new AtomicInteger();
        Map<String, Object> firstArguments = Map.of(
                "format", "indexed",
                "entities", List.of(
                        Map.of("name", "Alex Rivera", "type", "PERSON"),
                        Map.of("name", "submit_graphd", "type", "PERSON")),
                "relations", List.of(Map.of(
                        "source", 0,
                        "target", 0.0,
                        "type", "WORKS_AT")));
        Map<String, Object> repeatedArguments = Map.of(
                "format", "indexed",
                "entities", List.of(
                        Map.of("name", "Alex Rivera", "type", "PERSON"),
                        Map.of("name", "submit_graphd", "type", "PERSON")),
                "relations", List.of(Map.of(
                        "source", 0,
                        "target", 0,
                        "type", "WORKS_AT")));
        LocalServingBackend serving = new LocalServingBackend() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public boolean matchesModel(String modelId) {
                return "test-model".equals(modelId);
            }

            @Override
            public boolean supportsStructuredChat() {
                return true;
            }

            @Override
            public StructuredChatLanguageModel.Response generateChat(
                    StructuredChatLanguageModel.Request request, int maxNewTokens) {
                int call = modelCalls.getAndIncrement();
                return new StructuredChatLanguageModel.Response(
                        "<repeated-submit>", "", List.of(
                                new StructuredChatLanguageModel.ToolCall(
                                        "same-call", "submit_graph_delta",
                                        call == 0 ? firstArguments : repeatedArguments)),
                        List.of());
            }

            @Override
            public String generate(String prompt) {
                throw new AssertionError("decomposed extraction must preserve structured chat");
            }
        };
        GraphSchema schema = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", null),
                        new NodeType("COMPANY", "A company", null)),
                List.of(new RelationshipType(
                        "WORKS_AT", "A person works at a company", null)),
                List.of("(PERSON)-[:WORKS_AT]->(COMPANY)"));
        ProcessingRouteConfig route = ProcessingRouteConfig.builder()
                .fallbackEnabled(false)
                .servingLaneEnabled(false)
                .backends(List.of(ProcessingRouteConfig.ProcessingBackend.builder()
                        .id("local-serving")
                        .displayName("Local serving")
                        .type(ProcessingRouteConfig.ProcessingBackendType.LOCAL_MODEL)
                        .agentName("serving")
                        .priority(1)
                        .capabilities(List.of("llm"))
                        .build()))
                .build();
        GraphExtractionConfig extraction = GraphExtractionConfig.builder()
                .llmProvider("serving")
                .modelName("test-model")
                .maxTokens(256)
                .standardizedSchema(schema)
                .schemaMode(SchemaEnforcementMode.STRICT)
                .extractionMode(ExtractionMode.DECOMPOSED)
                .decomposedPromptTier(GraphExtractionConfig.DecomposedPromptTier.COMPACT)
                .entityResolution(false)
                .build();

        try (HeadlessUnifiedCorpusExtractor extractor =
                     new HeadlessUnifiedCorpusExtractor(null, serving, 1)) {
            HeadlessUnifiedCorpusExtractor.Result result = extractor.extract(
                    List.of(new Document("Alex Rivera works at Acme Robotics.",
                            Map.of(GraphConstants.META_SOURCE_PATH, "employment.txt"))),
                    extraction,
                    route,
                    "deterministic-rejection-test",
                    42L);

            assertTrue(result.failed(),
                    "the deterministic semantic rejection must remain visible as a crawl failure");
            assertEquals(2, modelCalls.get(),
                    "the inner two-round repair loop must not be restarted by document or chunk retries");
            assertNotNull(result.graph());
            assertTrue(result.graph().getEntities() == null
                    || result.graph().getEntities().isEmpty());
            assertTrue(result.graph().getRelationships() == null
                    || result.graph().getRelationships().isEmpty());
        }
    }
}
