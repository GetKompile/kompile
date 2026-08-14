/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.e2e;

import ai.kompile.app.llm.pipeline.SameDiffLanguageModelImpl;
import ai.kompile.core.crawl.graph.ExtractionMode;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.LocalServingBackend;
import ai.kompile.core.crawl.graph.ProcessingRouteConfig;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import org.springframework.ai.document.Document;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import ai.kompile.crawl.graph.HeadlessUnifiedCorpusExtractor;
import org.bytedeco.javacpp.LongPointer;
import org.nd4j.autodiff.samediff.diagnostics.DspDiagnostics;
import org.nd4j.linalg.factory.Nd4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real model-to-crawl integration gate for the parent-owned local serving lifecycle.
 *
 * <p>The parent session loads the same production model implementation used by the standalone
 * serving child, exposes it through {@link LocalServingBackend}, lets the shared
 * {@link HeadlessUnifiedCorpusExtractor} route extraction calls through that model, and unloads it
 * after the crawl. No mock LLM, HTTP fixture, CLI binary, or duplicate extraction path is used.</p>
 */
@Tag("integration")
class ModelToCrawlJvmIT {
    private static final String MODEL_ID = "lfm2.5-1.2b-instruct";
    private static final String MODEL_PROPERTY = "kompile.model.runtime.it.model";
    private static final String TOKENIZER_PROPERTY = "kompile.model.runtime.it.tokenizer";
    private static final String MAX_TOKENS_PROPERTY = "kompile.model.runtime.it.maxTokens";
    private static final int MAX_TOKENS = Integer.getInteger(MAX_TOKENS_PROPERTY, 256);
    private static final String SCHEMA_TOOL_NAME = "submit_corpus_schema";
    private static final String GRAPH_DELTA_TOOL_NAME = "submit_graph_delta";
    private static final String ONTOLOGY_DISCOVERY_MARKER = "ONTOLOGY DISCOVERY PREPASS";
    private static final Path DEFAULT_MODEL = Path.of(
            System.getProperty("user.home"), ".kompile", "models", "llm-ggmls",
            MODEL_ID, "LFM2.5-1.2B-Instruct-Q4_K_M.gguf");

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void parentStartsModelCrawlsThroughItAndStopsIt() throws Exception {
        DspDiagnostics.initialize();
        Path modelPath = requiredFile(MODEL_PROPERTY, DEFAULT_MODEL);
        Path tokenizerPath = requiredFile(
                TOKENIZER_PROPERTY, modelPath.getParent().resolve("tokenizer.json"));
        ParentOwnedModelSession modelSession =
                new ParentOwnedModelSession(MODEL_ID, modelPath, tokenizerPath);

        HeadlessUnifiedCorpusExtractor.Result result;
        try (modelSession;
             HeadlessUnifiedCorpusExtractor extractor =
                     new HeadlessUnifiedCorpusExtractor(null, modelSession, 1)) {
            GraphExtractionConfig extraction = GraphExtractionConfig.builder()
                    .llmProvider("serving")
                    .modelName(MODEL_ID)
                    .standardizedSchema(new GraphSchema(
                            List.of(
                                    new NodeType("PERSON", "A named person.", null),
                                    new NodeType("COMPANY", "A named company.", null)),
                            List.of(new RelationshipType(
                                    "WORKS_AT", "A person works at a company.", null)),
                            List.of("(PERSON)-[:WORKS_AT]->(COMPANY)")))
                    .entityTypes(List.of("PERSON", "COMPANY"))
                    .relationshipTypes(List.of("WORKS_AT"))
                    .schemaMode(SchemaEnforcementMode.STRICT)
                    .extractionMode(ExtractionMode.DECOMPOSED)
                    .decomposedPromptTier(GraphExtractionConfig.DecomposedPromptTier.COMPACT)
                    .decomposedBoundNativeProposalArrays(true)
                    .entityResolution(false)
                    .temperature(0.0d)
                    .maxTokens(MAX_TOKENS)
                    .customPrompt("Extract only explicit facts. Identify PERSON and COMPANY entities "
                            + "and WORKS_AT relations, then submit the graph delta without commentary.")
                    .build();
            ProcessingRouteConfig route = ProcessingRouteConfig.builder()
                    .fallbackEnabled(false)
                    .servingLaneEnabled(true)
                    .backends(List.of(ProcessingRouteConfig.ProcessingBackend.builder()
                            .id("serving")
                            .displayName("Parent-owned Kompile serving model")
                            .type(ProcessingRouteConfig.ProcessingBackendType.LOCAL_MODEL)
                            .agentName("serving")
                            .modelName(MODEL_ID)
                            .priority(1)
                            .maxConcurrent(1)
                            .capabilities(List.of("llm"))
                            .enabled(true)
                            .build()))
                    .build();
            List<Document> corpus = List.of(
                    new Document(
                            "employment-1",
                            "Alex Rivera is a person. Alex Rivera works at Acme Robotics, a company.",
                            Map.of("sourcePath", "employment.md")));

            result = extractor.extract(
                    corpus,
                    extraction,
                    route,
                    "model-to-crawl-jvm-it",
                    null);

            assertTrue(modelSession.isAvailable(),
                    "The parent released the model before crawl completion");
            assertTrue(modelSession.generationCalls() > 0,
                    "The crawl never called the parent-owned model");
            assertFalse(result.failed(), () -> "Model-backed crawl failed: " + result.errors()
                    + "; model calls=" + modelSession.generationCalls()
                    + "; last response=" + modelSession.lastResponseSummary());
            assertTrue(result.parseFailures() == 0,
                    () -> "Model output was not accepted by the extraction pipeline: " + result.errors());
            assertNotNull(result.graph().getEntities(), "The full extraction pipeline returned no entity list");
            assertNotNull(result.graph().getRelationships(),
                    "The full extraction pipeline returned no relationship list");
            assertTrue(result.graph().getEntities().size() >= 2,
                    () -> "Expected PERSON and COMPANY entities, got " + result.graph().getEntities());
            assertTrue(result.graph().getRelationships().stream()
                            .anyMatch(relation -> "WORKS_AT".equalsIgnoreCase(relation.getType())),
                    () -> "Expected WORKS_AT relation, got " + result.graph().getRelationships());
        }

        assertFalse(modelSession.isAvailable(),
                "The parent did not unload the model after crawl completion");
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void actualModelDerivesOntologyWithNativeToolCallBeforeCrawl() throws Exception {
        DspDiagnostics.initialize();
        Path modelPath = requiredFile(MODEL_PROPERTY, DEFAULT_MODEL);
        Path tokenizerPath = requiredFile(
                TOKENIZER_PROPERTY, modelPath.getParent().resolve("tokenizer.json"));
        ParentOwnedModelSession modelSession =
                new ParentOwnedModelSession(MODEL_ID, modelPath, tokenizerPath);

        HeadlessUnifiedCorpusExtractor.Result result;
        try (modelSession;
             HeadlessUnifiedCorpusExtractor extractor =
                     new HeadlessUnifiedCorpusExtractor(null, modelSession, 1)) {
            GraphExtractionConfig extraction = GraphExtractionConfig.builder()
                    .llmProvider("serving")
                    .modelName(MODEL_ID)
                    .schemaMode(SchemaEnforcementMode.LENIENT)
                    .extractionMode(ExtractionMode.DECOMPOSED)
                    .decomposedPromptTier(GraphExtractionConfig.DecomposedPromptTier.COMPACT)
                    .decomposedBoundNativeProposalArrays(true)
                    .entityResolution(false)
                    .temperature(0.0d)
                    .maxTokens(MAX_TOKENS)
                    .build();
            ProcessingRouteConfig route = ProcessingRouteConfig.builder()
                    .fallbackEnabled(false)
                    .servingLaneEnabled(true)
                    .backends(List.of(ProcessingRouteConfig.ProcessingBackend.builder()
                            .id("serving")
                            .displayName("Parent-owned Kompile serving model")
                            .type(ProcessingRouteConfig.ProcessingBackendType.LOCAL_MODEL)
                            .agentName("serving")
                            .modelName(MODEL_ID)
                            .priority(1)
                            .maxConcurrent(1)
                            .capabilities(List.of("llm"))
                            .enabled(true)
                            .build()))
                    .build();
            List<Document> corpus = List.of(new Document(
                    "founding-1",
                    "Jordan Lee is a person. Helios Dynamics is a company. "
                            + "Jordan Lee founded Helios Dynamics.",
                    Map.of("sourcePath", "founding.md")));

            result = extractor.extract(
                    corpus,
                    extraction,
                    route,
                    "model-schema-prepass-jvm-it",
                    null);

            assertTrue(modelSession.schemaPrepassRequests() > 0,
                    "The production crawl never attempted semantic schema induction");
            assertTrue(modelSession.ontologyDiscoveryRequests() > 0,
                    () -> "The invalid schema overlay did not fall back to native graph discovery: "
                            + modelSession.lastSchemaResponseSummary());
            assertTrue(modelSession.ontologyDiscoveryToolResponses() > 0,
                    () -> "LFM did not submit a graph delta during ontology discovery: "
                            + modelSession.lastOntologyDiscoveryResponseSummary());
            assertTrue(modelSession.boundedDocumentRequests() > 0,
                    "The ordinary document request never carried its source-derived proposal plan");
            Object entities = modelSession.lastOntologyDiscoveryToolArguments().get("entities");
            assertTrue(entities instanceof List<?> discoveredEntities && !discoveredEntities.isEmpty(),
                    () -> "Ontology discovery returned no entities: "
                            + modelSession.lastOntologyDiscoveryToolArguments());
            List<String> proposedLabels = ((List<?>) entities).stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .map(entity -> entity.get("type"))
                    .filter(java.util.Objects::nonNull)
                    .map(Object::toString)
                    .toList();
            assertTrue(proposedLabels.stream().anyMatch("PERSON"::equalsIgnoreCase),
                    () -> "Graph discovery did not infer PERSON: " + proposedLabels);
            assertTrue(proposedLabels.stream().anyMatch("COMPANY"::equalsIgnoreCase),
                    () -> "Graph discovery did not infer COMPANY: " + proposedLabels);
            assertFalse(result.failed(), () -> "Model-backed derived-schema crawl failed: "
                    + result.errors() + "; last response=" + modelSession.lastResponseSummary());
            assertTrue(result.parseFailures() == 0,
                    () -> "Derived-schema extraction output was not accepted: " + result.errors());
            assertNotNull(result.graph().getEntities(),
                    "The derived-schema crawl returned no entity list");
            assertTrue(!result.graph().getEntities().isEmpty(),
                    () -> "The derived-schema crawl extracted no entities; schema arguments="
                            + modelSession.lastSchemaToolArguments());
        }

        assertFalse(modelSession.isAvailable(),
                "The parent did not unload the model after derived-schema crawl completion");
    }

    private static final class ParentOwnedModelSession implements LocalServingBackend, AutoCloseable {
        private final String modelId;
        private final SameDiffLanguageModelImpl languageModel;
        private final AtomicInteger generationCalls = new AtomicInteger();
        private final AtomicInteger schemaPrepassRequests = new AtomicInteger();
        private final AtomicInteger schemaPrepassToolResponses = new AtomicInteger();
        private final AtomicInteger ontologyDiscoveryRequests = new AtomicInteger();
        private final AtomicInteger ontologyDiscoveryToolResponses = new AtomicInteger();
        private final AtomicInteger boundedDocumentRequests = new AtomicInteger();
        private volatile Map<String, Object> lastSchemaToolArguments = Map.of();
        private volatile Map<String, Object> lastOntologyDiscoveryToolArguments = Map.of();
        private volatile String lastSchemaResponseSummary = "none";
        private volatile String lastOntologyDiscoveryResponseSummary = "none";
        private volatile String lastResponseSummary = "none";

        private ParentOwnedModelSession(String modelId, Path modelPath, Path tokenizerPath)
                throws Exception {
            this.modelId = modelId;
            this.languageModel = new SameDiffLanguageModelImpl(Optional.empty(), Optional.empty());
            languageModel.loadModel(
                    modelId,
                    modelPath,
                    tokenizerPath,
                    Map.of(
                            "maxNewTokens", MAX_TOKENS,
                            "temperature", 0.0d,
                            "topK", 1,
                            "dspEnabled", true));
        }

        @Override
        public boolean isAvailable() {
            return languageModel.isLoaded();
        }

        @Override
        public boolean matchesModel(String requestedModelId) {
            return modelId.equals(requestedModelId);
        }

        @Override
        public boolean supportsStructuredChat() {
            return true;
        }

        @Override
        public String generate(String prompt) {
            int call = beginCall();
            try {
                String response = languageModel.generateResponse(prompt, List.of(), MAX_TOKENS);
                lastResponseSummary = bounded(response);
                return response;
            } finally {
                logDspMemory("after", call);
            }
        }

        @Override
        public String generate(String prompt, int maxNewTokens) {
            int call = beginCall();
            try {
                String response = languageModel.generateResponse(prompt, List.of(), maxNewTokens);
                lastResponseSummary = bounded(response);
                return response;
            } finally {
                logDspMemory("after", call);
            }
        }

        @Override
        public StructuredChatLanguageModel.Response generateChat(
                StructuredChatLanguageModel.Request request,
                int maxNewTokens) {
            int call = beginCall();
            boolean schemaPrepass = request.tools().stream()
                    .anyMatch(tool -> SCHEMA_TOOL_NAME.equals(tool.name()));
            boolean ontologyDiscovery = request.tools().stream()
                    .anyMatch(tool -> GRAPH_DELTA_TOOL_NAME.equals(tool.name()))
                    && request.messages().stream().map(StructuredChatLanguageModel.Message::content)
                    .anyMatch(message -> message.contains(ONTOLOGY_DISCOVERY_MARKER));
            boolean foundingDocument = !ontologyDiscovery
                    && request.tools().stream()
                    .anyMatch(tool -> GRAPH_DELTA_TOOL_NAME.equals(tool.name()))
                    && request.messages().stream().map(StructuredChatLanguageModel.Message::content)
                    .anyMatch(message -> message.contains("Jordan Lee")
                            && message.contains("Helios Dynamics"));
            boolean employmentDocument = !ontologyDiscovery
                    && request.tools().stream()
                    .anyMatch(tool -> GRAPH_DELTA_TOOL_NAME.equals(tool.name()))
                    && request.messages().stream().map(StructuredChatLanguageModel.Message::content)
                    .anyMatch(message -> message.contains("Alex Rivera")
                            && message.contains("Acme Robotics"));
            if (schemaPrepass) {
                schemaPrepassRequests.incrementAndGet();
                System.err.printf(
                        "MODEL_TO_CRAWL_SCHEMA_REQUEST call=%d tools=%s messages=%s%n",
                        call,
                        request.tools().stream().map(StructuredChatLanguageModel.Tool::name).toList(),
                        bounded(request.messages().toString()));
            }
            if (ontologyDiscovery) {
                ontologyDiscoveryRequests.incrementAndGet();
                System.err.printf(
                        "MODEL_TO_CRAWL_ONTOLOGY_DISCOVERY_REQUEST call=%d tools=%s%n",
                        call,
                        request.tools().stream().map(StructuredChatLanguageModel.Tool::name).toList());
            }
            if (foundingDocument) {
                StructuredChatLanguageModel.Tool submit = request.tools().stream()
                        .filter(tool -> GRAPH_DELTA_TOOL_NAME.equals(tool.name()))
                        .findFirst()
                        .orElseThrow();
                assertProposalCardinality(submit.parameters(), 2, 1, 15);
                boundedDocumentRequests.incrementAndGet();
                System.err.printf(
                        "MODEL_TO_CRAWL_BOUNDED_DOCUMENT_REQUEST call=%d parameters=%s messages=%s%n",
                        call, submit.parameters(), bounded(request.messages().toString()));
            }
            if (employmentDocument) {
                StructuredChatLanguageModel.Tool submit = request.tools().stream()
                        .filter(tool -> GRAPH_DELTA_TOOL_NAME.equals(tool.name()))
                        .findFirst()
                        .orElseThrow();
                assertProposalCardinality(submit.parameters(), 2, 1, 13);
                boundedDocumentRequests.incrementAndGet();
                System.err.printf(
                        "MODEL_TO_CRAWL_BOUNDED_DOCUMENT_REQUEST call=%d parameters=%s messages=%s%n",
                        call, bounded(String.valueOf(submit.parameters())),
                        bounded(request.messages().toString()));
            }
            try {
                StructuredChatLanguageModel.Response response =
                        languageModel.generateChat(request, maxNewTokens);
                if (schemaPrepass) {
                    response.toolCalls().stream()
                            .filter(toolCall -> SCHEMA_TOOL_NAME.equals(toolCall.name()))
                            .findFirst()
                            .ifPresent(toolCall -> {
                                schemaPrepassToolResponses.incrementAndGet();
                                lastSchemaToolArguments = toolCall.arguments();
                            });
                    lastSchemaResponseSummary = "raw=" + bounded(response.rawText())
                            + ", content=" + bounded(response.content())
                            + ", calls=" + response.toolCalls()
                            + ", parseErrors=" + response.parseErrors();
                }
                if (ontologyDiscovery) {
                    response.toolCalls().stream()
                            .filter(toolCall -> GRAPH_DELTA_TOOL_NAME.equals(toolCall.name()))
                            .findFirst()
                            .ifPresent(toolCall -> {
                                ontologyDiscoveryToolResponses.incrementAndGet();
                                lastOntologyDiscoveryToolArguments = toolCall.arguments();
                            });
                    lastOntologyDiscoveryResponseSummary = "raw=" + bounded(response.rawText())
                            + ", content=" + bounded(response.content())
                            + ", calls=" + response.toolCalls()
                            + ", parseErrors=" + response.parseErrors();
                }
                lastResponseSummary = "raw=" + bounded(response.rawText())
                        + ", content=" + bounded(response.content())
                        + ", calls=" + response.toolCalls()
                        + ", parseErrors=" + response.parseErrors();
                System.err.printf(
                        "MODEL_TO_CRAWL_RESPONSE call=%d %s%n", call, lastResponseSummary);
                return response;
            } finally {
                logDspMemory("after", call);
            }
        }

        private int beginCall() {
            int call = generationCalls.incrementAndGet();
            logDspMemory("before", call);
            return call;
        }

        private void logDspMemory(String phase, int call) {
            try (LongPointer poolUsed = new LongPointer(1);
                 LongPointer poolReserved = new LongPointer(1)) {
                int device = Nd4j.getAffinityManager().getDeviceForCurrentThread();
                var nativeOps = Nd4j.getNativeOps();
                nativeOps.getMemoryPoolStats(device, poolUsed, poolReserved);
                long freeBytes = nativeOps.getDeviceFreeMemory(device);
                long totalBytes = nativeOps.getDeviceTotalMemory(device);
                System.err.printf(
                        "MODEL_TO_CRAWL_DSP_MEMORY phase=%s call=%d device=%d "
                                + "free=%dMB total=%dMB poolUsed=%dMB poolReserved=%dMB "
                                + "dspPhase=%s report=%s%n",
                        phase,
                        call,
                        device,
                        freeBytes / (1024 * 1024),
                        totalBytes / (1024 * 1024),
                        poolUsed.get() / (1024 * 1024),
                        poolReserved.get() / (1024 * 1024),
                        languageModel.getDspPlanPhase(),
                        bounded(DspDiagnostics.getPlanReport()));
            } catch (Throwable t) {
                System.err.printf(
                        "MODEL_TO_CRAWL_DSP_MEMORY phase=%s call=%d unavailable=%s%n",
                        phase, call, t);
            }
        }

        private int generationCalls() {
            return generationCalls.get();
        }

        private int schemaPrepassRequests() {
            return schemaPrepassRequests.get();
        }

        private int schemaPrepassToolResponses() {
            return schemaPrepassToolResponses.get();
        }

        private int ontologyDiscoveryRequests() {
            return ontologyDiscoveryRequests.get();
        }

        private int ontologyDiscoveryToolResponses() {
            return ontologyDiscoveryToolResponses.get();
        }

        private int boundedDocumentRequests() {
            return boundedDocumentRequests.get();
        }

        private Map<String, Object> lastSchemaToolArguments() {
            return lastSchemaToolArguments;
        }

        private String lastSchemaResponseSummary() {
            return lastSchemaResponseSummary;
        }

        private Map<String, Object> lastOntologyDiscoveryToolArguments() {
            return lastOntologyDiscoveryToolArguments;
        }

        private String lastOntologyDiscoveryResponseSummary() {
            return lastOntologyDiscoveryResponseSummary;
        }

        private String lastResponseSummary() {
            return lastResponseSummary;
        }

        @SuppressWarnings("unchecked")
        private static void assertProposalCardinality(
                Map<String, Object> parameters,
                int entityCount,
                int relationCount,
                int entityNameMaxLength) {
            Map<String, Object> properties = requireMap(
                    parameters.get("properties"), "parameters.properties");
            Map<String, Object> entities = requireMap(
                    properties.get("entities"), "parameters.properties.entities");
            Map<String, Object> relations = requireMap(
                    properties.get("relations"), "parameters.properties.relations");
            requireEquals(entityCount, entities.get("maxItems"), "entities.maxItems");
            requireEquals(relationCount, relations.get("maxItems"), "relations.maxItems");
            requireEquals(entityCount, entities.get("minItems"), "entities.minItems");
            requireEquals(null, entities.get("prefixItems"), "entities.prefixItems");
            requireEquals(relationCount, relations.get("minItems"), "relations.minItems");
            requireEquals(null, relations.get("prefixItems"), "relations.prefixItems");
            Map<String, Object> entityProperties = requireMap(
                    requireMap(entities.get("items"), "entities.items").get("properties"),
                    "entities.items.properties");
            requireEquals(null, requireMap(entityProperties.get("name"),
                    "entity.name").get("const"), "entity.name.const");
            requireEquals(entityNameMaxLength, requireMap(entityProperties.get("name"),
                    "entity.name").get("maxLength"), "entity.name.maxLength");
            requireEquals(null, requireMap(entityProperties.get("type"),
                    "entity.type").get("const"), "entity.type.const");
            Map<String, Object> relationProperties = requireMap(
                    requireMap(relations.get("items"), "relations.items").get("properties"),
                    "relations.items.properties");
            requireEquals(null, requireMap(relationProperties.get("source"),
                    "relation.source").get("const"), "relation.source.const");
            requireEquals(null, requireMap(relationProperties.get("target"),
                    "relation.target").get("const"), "relation.target.const");
            requireEquals(null, requireMap(relationProperties.get("type"),
                    "relation.type").get("const"), "relation.type.const");
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> requireMap(Object value, String path) {
            if (!(value instanceof Map<?, ?>)) {
                throw new IllegalStateException(path + " must be an object but was " + value);
            }
            return (Map<String, Object>) value;
        }

        private static List<?> requireList(Object value, String path) {
            if (!(value instanceof List<?> list)) {
                throw new IllegalStateException(path + " must be an array but was " + value);
            }
            return list;
        }

        private static void requireEquals(Object expected, Object actual, String path) {
            if (!java.util.Objects.equals(expected, actual)) {
                throw new IllegalStateException(path + " expected " + expected + " but was " + actual);
            }
        }

        private static String bounded(String value) {
            if (value == null) {
                return "null";
            }
            String normalized = value.replace('\n', ' ').replace('\r', ' ');
            return normalized.length() <= 1_000
                    ? normalized : normalized.substring(0, 1_000) + "...";
        }

        @Override
        public void close() {
            languageModel.unloadModel();
        }
    }

    private static Path requiredFile(String property, Path defaultPath) {
        String configured = System.getProperty(property);
        Path path = configured == null || configured.isBlank()
                ? defaultPath : Path.of(configured.trim());
        Path normalized = path.toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(normalized),
                () -> "Required real model fixture is missing: " + normalized
                        + " (override with -D" + property + "=<path>)");
        return normalized;
    }
}
