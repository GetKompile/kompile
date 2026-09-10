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
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.graphrag.GraphConstants;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadlessUnifiedCorpusExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void canonicalSchemaArtifactRoundTripPreservesNullAndClosedEmptyFields() {
        GraphSchema schema = new GraphSchema(
                List.of(new NodeType("PERSON", "A named person.", null)),
                List.of(),
                null);

        CanonicalGraphSchemaArtifact.Value decoded = CanonicalGraphSchemaArtifact.decode(
                MAPPER, CanonicalGraphSchemaArtifact.encode(MAPPER, schema));

        assertNotNull(decoded.schema());
        assertEquals(1, decoded.schema().getNodeTypes().size());
        assertEquals(List.of(), decoded.schema().getRelationshipTypes());
        assertNull(decoded.schema().getPatterns());
        assertEquals(CrawlOntology.contentFingerprint(decoded.schema()), decoded.fingerprint());
    }

    @Test
    void canonicalSchemaArtifactRejectsFingerprintCorruption() throws Exception {
        GraphSchema schema = new GraphSchema(
                List.of(new NodeType("PERSON", "A named person.", null)),
                List.of(),
                List.of());
        var root = MAPPER.readTree(CanonicalGraphSchemaArtifact.encode(MAPPER, schema));
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("fingerprint", "corrupt");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CanonicalGraphSchemaArtifact.decode(MAPPER, MAPPER.writeValueAsBytes(root)));
        assertTrue(failure.getMessage().contains("fingerprint mismatch"), failure.getMessage());
    }

    @Test
    void suppliesManagedTopicEvidenceToHeadlessSchemaDiscovery() {
        AtomicReference<String> schemaPrompt = new AtomicReference<>();
        AtomicReference<Map<String, Object>> persistedTopicEvidence = new AtomicReference<>();
        LocalServingBackend serving = new LocalServingBackend() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public boolean matchesModel(String modelId) {
                return "schema-model".equals(modelId);
            }

            @Override
            public boolean supportsStructuredChat() {
                return true;
            }

            @Override
            public StructuredChatLanguageModel.Response generateChat(
                    StructuredChatLanguageModel.Request request, int maxNewTokens) {
                String prompt = request.messages().stream()
                        .map(StructuredChatLanguageModel.Message::content)
                        .reduce("", (left, right) -> left + "\n" + right);
                String tool = request.tools().get(0).name();
                if (CorpusSchemaUnifier.TOPIC_BINDING_TOOL_NAME.equals(tool)) {
                    schemaPrompt.set(prompt);
                    boolean astronomy = prompt.contains("topic-0001");
                    return new StructuredChatLanguageModel.Response(
                            "<tool_call>", "", "", List.of(),
                            List.of(new StructuredChatLanguageModel.ToolCall(
                                    "topic-binding", tool, Map.of(
                                    "b", astronomy
                                            ? bindingOptionId(request, "nodeIds", "TELESCOPE") + "|"
                                                    + bindingOptionId(request, "parentIds", "PRODUCT")
                                                    + "|" + bindingOptionId(request, "evidenceIds", "telescope")
                                                    + "|0|0|0|0|0"
                                            : bindingOptionId(request, "nodeIds", "HABITAT") + "|"
                                                    + bindingOptionId(request, "parentIds", "LOCATION")
                                                    + "|" + bindingOptionId(request, "evidenceIds", "habitat")
                                                    + "|0|0|0|0|0"))),
                            List.of());
                }
                List<Map<String, Object>> definitions = List.of();
                String argument = "submit_node_types".equals(tool)
                        ? "nodeTypes" : "relationshipTypes";
                return new StructuredChatLanguageModel.Response(
                        "<tool_call>", "", "", List.of(),
                        List.of(new StructuredChatLanguageModel.ToolCall(
                                "schema", tool, Map.of(argument, definitions))), List.of());
            }

            @Override
            public String generate(String prompt) {
                return "{\"entities\":[],\"relations\":[]}";
            }
        };
        EmbeddingModel embeddings = fixedTopicEmbeddings();
        ProcessingRouteConfig route = ProcessingRouteConfig.builder()
                .fallbackEnabled(false)
                .servingLaneEnabled(true)
                .backends(List.of(ProcessingRouteConfig.ProcessingBackend.builder()
                        .id("local-serving")
                        .type(ProcessingRouteConfig.ProcessingBackendType.LOCAL_MODEL)
                        .agentName("serving")
                        .capabilities(List.of("llm"))
                        .build()))
                .build();
        GraphExtractionConfig extraction = GraphExtractionConfig.builder()
                .llmProvider("serving")
                .modelName("schema-model")
                .entityResolution(false)
                .build();
        List<Document> corpus = List.of(
                topicDocument("astronomy-en.txt", "astronomy telescope galaxy orbit"),
                topicDocument("astronomy-es.txt", "astronomía telescopio galaxia órbita"),
                topicDocument("astronomy-fr.txt", "astronomie télescope galaxie orbite"),
                topicDocument("ecology-en.txt", "ecology forest habitat species"),
                topicDocument("ecology-de.txt", "ökologie wald lebensraum arten"),
                topicDocument("ecology-es.txt", "ecología bosque hábitat especies"));

        try (HeadlessUnifiedCorpusExtractor extractor =
                     new HeadlessUnifiedCorpusExtractor(null, serving, embeddings, null,
                             1, null, trace -> {
                                 if ("CORPUS_TOPIC_EVIDENCE".equals(trace.get("eventType"))) {
                                     persistedTopicEvidence.set(trace);
                                 }
                             })) {
            extractor.extract(corpus, extraction, route, "topic-headless", 42L);
        }

        assertNotNull(schemaPrompt.get());
        assertTrue(schemaPrompt.get().contains("CORPUS TOPIC EVIDENCE"), schemaPrompt.get());
        assertTrue(schemaPrompt.get().contains("multilingual-e5-small"), schemaPrompt.get());
        assertTrue(schemaPrompt.get().contains("\"en\""), schemaPrompt.get());
        assertNotNull(persistedTopicEvidence.get());
        String persisted = persistedTopicEvidence.get().toString();
        for (String language : List.of("en", "es", "de", "fr")) {
            assertTrue(persisted.contains(language), persisted);
        }
        assertTrue(persisted.contains("TELESCOPE"), persisted);
        assertTrue(persisted.contains("HABITAT"), persisted);
        assertNull(extraction.getStandardizedSchema(),
                "corpus-derived schema must not leak back into a reusable caller config");
    }

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
                .standardizedSchema(new GraphSchema(
                        List.of(new NodeType("ORGANIZATION", "An organization.", null)),
                        List.of(new RelationshipType(
                                "ACQUIRED", "One organization acquired another.", null)),
                        List.of("(ORGANIZATION)-[:ACQUIRED]->(ORGANIZATION)")))
                .schemaMode(SchemaEnforcementMode.STRICT)
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
    void preservesCallerValidationRetryLimitInHeadlessRequest() {
        AtomicInteger modelCalls = new AtomicInteger();
        LocalServingBackend serving = new LocalServingBackend() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public boolean matchesModel(String modelId) {
                return "retry-model".equals(modelId);
            }

            @Override
            public String generate(String prompt) {
                modelCalls.incrementAndGet();
                return "not-json";
            }
        };
        ProcessingRouteConfig route = ProcessingRouteConfig.builder()
                .fallbackEnabled(false)
                .servingLaneEnabled(true)
                .backends(List.of(ProcessingRouteConfig.ProcessingBackend.builder()
                        .id("local-serving")
                        .type(ProcessingRouteConfig.ProcessingBackendType.LOCAL_MODEL)
                        .agentName("serving")
                        .capabilities(List.of("llm"))
                        .build()))
                .build();
        GraphExtractionConfig extraction = GraphExtractionConfig.builder()
                .llmProvider("serving")
                .modelName("retry-model")
                .standardizedSchema(new GraphSchema(
                        List.of(new NodeType("CONCEPT", "A concept.", null)),
                        List.of(), List.of()))
                .schemaMode(SchemaEnforcementMode.STRICT)
                .extractionMode(ExtractionMode.SINGLE_PASS)
                .entityResolution(false)
                .build();

        try (HeadlessUnifiedCorpusExtractor extractor =
                     new HeadlessUnifiedCorpusExtractor(null, serving, 1)) {
            HeadlessUnifiedCorpusExtractor.Result result = extractor.extract(
                    List.of(new Document("A telescope recorded a galaxy.",
                            Map.of(GraphConstants.META_SOURCE_PATH, "astronomy.txt"))),
                    extraction, route, null, 0,
                    "headless-retry-limit", 42L);

            assertTrue(result.failed());
            assertEquals(2, modelCalls.get(),
                    "maxValidationRetries=0 must skip document validation repairs; "
                            + "the separate one-shot per-chunk recovery pass remains enabled");
        }
    }

    @Test
    void successfulInPhaseRetryDoesNotLeaveTransientModelErrorFatal() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger entityCalls = new AtomicInteger();
        AtomicInteger relationCalls = new AtomicInteger();
        LocalServingBackend serving = new LocalServingBackend() {
            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public boolean matchesModel(String modelId) {
                return "retry-model".equals(modelId);
            }

            @Override
            public boolean supportsStructuredChat() {
                return true;
            }

            @Override
            public StructuredChatLanguageModel.Response generateChat(
                    StructuredChatLanguageModel.Request request, int maxNewTokens) {
                modelCalls.incrementAndGet();
                String tool = request.tools().get(0).name();
                if ("submit_typed_entities".equals(tool)) {
                    boolean firstEntityAttempt = entityCalls.incrementAndGet() == 1;
                    return toolResponse(tool, Map.of("entities", List.of(
                            Map.of("name", "Alex Rivera", "type", "PERSON"),
                            Map.of("name", "Morgan Chen", "type", "PERSON"),
                            Map.of("name", "Acme Robotics", "type",
                                    firstEntityAttempt ? "PERSON" : "COMPANY"),
                            Map.of("name", "Nova Labs", "type", "COMPANY"))));
                }
                if ("submit_relations".equals(tool) && relationCalls.incrementAndGet() == 1) {
                    throw new IllegalStateException("transient relation generation failure");
                }
                if ("submit_relations".equals(tool)) {
                    return toolResponse(tool, Map.of("relations", List.of(
                            Map.of("source", 0, "target", 2, "type", "WORKS_AT"),
                            Map.of("source", 1, "target", 3, "type", "WORKS_AT"))));
                }
                throw new AssertionError("Unexpected extraction tool: " + tool);
            }

            @Override
            public String generate(String prompt) {
                throw new AssertionError("decomposed extraction must preserve structured chat");
            }
        };
        GraphSchema schema = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A named person.", null),
                        new NodeType("COMPANY", "A named company.", null)),
                List.of(new RelationshipType(
                        "WORKS_AT", "A person works at a company.", null)),
                List.of("(PERSON)-[:WORKS_AT]->(COMPANY)"));
        GraphExtractionConfig extraction = GraphExtractionConfig.builder()
                .llmProvider("serving")
                .modelName("retry-model")
                .standardizedSchema(schema)
                .entityTypes(List.of("PERSON", "COMPANY"))
                .relationshipTypes(List.of("WORKS_AT"))
                .schemaMode(SchemaEnforcementMode.STRICT)
                .extractionMode(ExtractionMode.DECOMPOSED)
                .decomposedPromptTier(GraphExtractionConfig.DecomposedPromptTier.COMPACT)
                .decomposedPassStrategy(
                        GraphExtractionConfig.DecomposedPassStrategy.ENTITIES_THEN_RELATIONS)
                .decomposedBoundNativeProposalArrays(true)
                .entityResolution(false)
                .build();
        ProcessingRouteConfig route = ProcessingRouteConfig.builder()
                .fallbackEnabled(false)
                .servingLaneEnabled(true)
                .backends(List.of(ProcessingRouteConfig.ProcessingBackend.builder()
                        .id("local-serving")
                        .type(ProcessingRouteConfig.ProcessingBackendType.LOCAL_MODEL)
                        .agentName("serving")
                        .modelName("retry-model")
                        .capabilities(List.of("llm"))
                        .build()))
                .build();

        try (HeadlessUnifiedCorpusExtractor extractor =
                     new HeadlessUnifiedCorpusExtractor(null, serving, 1)) {
            HeadlessUnifiedCorpusExtractor.Result result = extractor.extract(
                    List.of(new Document(
                            "Alex Rivera is a person. Morgan Chen is a person. "
                                    + "Acme Robotics is a company. Nova Labs is a company. "
                                    + "Alex Rivera works at Acme Robotics. "
                                    + "Morgan Chen works at Nova Labs.",
                            Map.of(GraphConstants.META_SOURCE_PATH, "employment.txt"))),
                    extraction, route, "recovered-retry-test", 42L);

            assertFalse(result.failed(), () -> "Recovered extraction stayed failed: " + result.errors());
            assertTrue(result.errors().isEmpty(), () -> "Recovered errors were retained: " + result.errors());
            assertEquals(5, modelCalls.get(),
                    "the entity assertion repair and failed relation call must both be exercised");
            assertEquals(3, entityCalls.get(),
                    "one entity repair plus one complete outer retry must run");
            assertEquals(2, relationCalls.get());
            assertEquals(4, result.graph().getEntities().size());
            assertEquals(2, result.graph().getRelationships().size());
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

    private static String bindingOptionId(
            StructuredChatLanguageModel.Request request,
            String group,
            String value) {
        String prompt = request.messages().get(request.messages().size() - 1).content();
        String marker = "BINDING_OPTION_IDS_JSON=";
        int start = prompt.lastIndexOf(marker);
        assertTrue(start >= 0, prompt);
        try {
            Map<String, Object> root = MAPPER.readValue(
                    prompt.substring(start + marker.length()).trim(), Map.class);
            List<?> options = (List<?>) root.get(group);
            int index = options.indexOf(value);
            if (index < 0) {
                throw new AssertionError(value + " not found in " + group + ": " + options);
            }
            return String.valueOf(index + 1);
        } catch (java.io.IOException e) {
            throw new AssertionError("Unable to parse binding options", e);
        }
    }

    private static StructuredChatLanguageModel.Response toolResponse(
            String tool, Map<String, Object> arguments) {
        return new StructuredChatLanguageModel.Response(
                "<tool_call>", "", "", List.of(),
                List.of(new StructuredChatLanguageModel.ToolCall(
                        "call-" + tool, tool, arguments)),
                List.of());
    }

    private static Document topicDocument(String sourcePath, String text) {
        return new Document(sourcePath, text,
                Map.of(GraphConstants.META_SOURCE_PATH, sourcePath));
    }

    private static EmbeddingModel fixedTopicEmbeddings() {
        return new EmbeddingModel() {
            @Override
            public INDArray embed(String text) {
                return Nd4j.create(vector(text));
            }

            @Override
            public INDArray embed(List<String> texts) {
                return Nd4j.create(texts.stream().map(this::vector).toArray(float[][]::new));
            }

            @Override
            public INDArray embedDocuments(List<Document> documents) {
                return embed(documents.stream().map(Document::getText).toList());
            }

            @Override
            public List<float[]> embedBatch(List<String> texts) {
                return texts.stream().map(this::vector).toList();
            }

            @Override
            public int dimensions() {
                return 2;
            }

            @Override
            public String getModelIdentifier() {
                return "multilingual-e5-small";
            }

            private float[] vector(String text) {
                String lower = text.toLowerCase();
                boolean astronomy = lower.contains("astronom") || lower.contains("telescope")
                        || lower.contains("telescopio") || lower.contains("télescope");
                return astronomy ? new float[]{1.0f, 0.02f} : new float[]{0.02f, 1.0f};
            }
        };
    }
}
