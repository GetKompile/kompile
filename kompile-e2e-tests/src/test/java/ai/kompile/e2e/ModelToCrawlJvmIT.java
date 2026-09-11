/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.e2e;

import ai.kompile.app.llm.pipeline.SameDiffLanguageModelImpl;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.tools.CliTool;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolRegistryFactory;
import ai.kompile.cli.main.chat.tools.ToolSchemaOptimizer;
import ai.kompile.cli.main.coordination.ReusableResourcePool;
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
import org.eclipse.deeplearning4j.llm.generation.SameDiffMemoryUtils;
import org.nd4j.autodiff.samediff.diagnostics.DspDiagnostics;
import org.nd4j.common.config.ND4JSystemProperties;
import org.nd4j.linalg.factory.Nd4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real model-to-crawl integration gate for the parent-owned local serving lifecycle.
 *
 * <p>The parent session loads the same production model implementation used by the standalone
 * serving child, exposes it through {@link LocalServingBackend}, lets the shared
 * {@link HeadlessUnifiedCorpusExtractor} route extraction calls through that model, and releases a
 * shared lease after each crawl. Compatible leases within one test reuse one warm model; JUnit
 * teardown deterministically unloads it and clears its SameDiff/native resources after every test.
 * No mock LLM, HTTP fixture, CLI binary, or duplicate extraction path is used.</p>
 */
@Tag("integration")
class ModelToCrawlJvmIT {
    private static final String DEFAULT_MODEL_ID = "lfm2.5-1.2b-instruct";
    private static final String MODEL_ID_PROPERTY = "kompile.model.runtime.it.modelId";
    private static final String MODEL_ID = System.getProperty(MODEL_ID_PROPERTY, DEFAULT_MODEL_ID);
    private static final String MODEL_PROPERTY = "kompile.model.runtime.it.model";
    private static final String TOKENIZER_PROPERTY = "kompile.model.runtime.it.tokenizer";
    private static final String MAX_TOKENS_PROPERTY = "kompile.model.runtime.it.maxTokens";
    private static final String MAX_PREFILL_LENGTH_PROPERTY =
            "kompile.model.runtime.it.maxPrefillLength";
    private static final String MAX_KV_CACHE_LENGTH_PROPERTY =
            "kompile.model.runtime.it.maxKvCacheLength";
    private static final String OPTIMIZER_ENABLED_PROPERTY =
            "kompile.model.runtime.it.optimizerEnabled";
    private static final String OPTIMIZER_FP16_PROPERTY =
            "kompile.model.runtime.it.optimizerFp16";
    // One positive MiB value per visible logical device, in logical device order.
    // Example: -Dkompile.model.runtime.it.deviceMemoryLimitsMiB=14336,4096
    private static final String DEVICE_MEMORY_LIMITS_PROPERTY =
            "kompile.model.runtime.it.deviceMemoryLimitsMiB";
    private static final String REPETITION_PENALTY_PROPERTY =
            "kompile.model.runtime.it.repetitionPenalty";
    private static final String TEMPERATURE_PROPERTY = "kompile.model.runtime.it.temperature";
    private static final String TOP_K_PROPERTY = "kompile.model.runtime.it.topK";
    private static final String TOP_P_PROPERTY = "kompile.model.runtime.it.topP";
    private static final String DO_SAMPLE_PROPERTY = "kompile.model.runtime.it.doSample";
    private static final String PRESENCE_PENALTY_PROPERTY =
            "kompile.model.runtime.it.presencePenalty";
    private static final String ENABLE_THINKING_PROPERTY =
            "kompile.model.runtime.it.enableThinking";
    private static final String FORCE_FIRST_EMPLOYMENT_ENTITY_REPAIR_PROPERTY =
            "kompile.model.runtime.it.forceFirstEmploymentEntityRepair";
    private static final String MAX_OUTPUT_BLOCK_TOKENS_PROPERTY =
            "kompile.model.runtime.it.maxOutputBlockTokens";
    private static final String STRUCTURED_OUTPUT_TOKEN_RESERVE_PROPERTY =
            "kompile.model.runtime.it.structuredOutputTokenReserve";
    private static final int MAX_TOKENS = Integer.getInteger(MAX_TOKENS_PROPERTY, 768);
    private static final boolean ENABLE_THINKING =
            Boolean.getBoolean(ENABLE_THINKING_PROPERTY);
    private static final Set<String> SCHEMA_TOOL_NAMES = Set.of(
            "submit_node_types", "submit_relationship_types",
            "bind_relationship_signatures", "bind_topics_to_schema");
    private static final String GRAPH_DELTA_TOOL_NAME = "submit_graph_delta";
    private static final Set<String> PROJECT_LOCAL_MODEL_TOOL_IDS = Set.of(
            "knowledge_search", "knowledge_status", "rag_search", "graph_search",
            "graph_aggregate", "graph_forecast", "graph_centrality", "knowledge_graph",
            "ask_graph_query", "ask_graph_verify", "ask_graph_assert", "ask_graph_retract",
            "ask_graph_mebn", "ask_graph_explain", "ask_graph_explain_fused",
            "ask_graph_synthesize", "ask_graph_subscribe", "graph_reason", "graph_import",
            "graph_export", "crawl_source", "crawl_documents", "crawl_discover",
            "model_runtime", "crawl_control", "crawl_result", "process_mining",
            "ask_graph_claim", "graph_reasoning_query", "graph_bayes", "graph_embeddings",
            "graph_simulate");
    private static final Path DEFAULT_MODEL = Path.of(
            System.getProperty("user.home"), ".kompile", "models", "llm-ggmls",
            DEFAULT_MODEL_ID, "LFM2.5-1.2B-Instruct-Q4_K_M.gguf");
    private static final AtomicInteger MODEL_LOADS = new AtomicInteger();
    private static final AtomicInteger MODEL_UNLOADS = new AtomicInteger();
    private static final ReusableResourcePool<ModelRuntimeKey, PooledLanguageModel> MODEL_POOL =
            new ReusableResourcePool<>(
                    "model-to-crawl-jvm-pool",
                    () -> Long.getLong("kompile.model.runtime.it.pool.idleMs", 120_000L),
                    () -> 1,
                    PooledLanguageModel::isLoaded,
                    PooledLanguageModel::close);

    @AfterEach
    void unloadPooledModelAfterEachTest() {
        MODEL_POOL.clear();
        SameDiffMemoryUtils.reclaimClosedGraphResources();
        assertEquals(0, MODEL_POOL.pooledCount(),
                "JUnit teardown must not retain a pooled model between tests");
        assertEquals(MODEL_LOADS.get(), MODEL_UNLOADS.get(),
                "JUnit teardown must unload every model loaded by the test");
    }

    @AfterAll
    static void closeModelPool() {
        MODEL_POOL.close();
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void actualModelCallsEveryProjectLocalMcpToolIndividually() throws Exception {
        DspDiagnostics.initialize();
        Path modelPath = requiredFile(MODEL_PROPERTY, DEFAULT_MODEL);
        Path tokenizerPath = requiredFile(
                TOKENIZER_PROPERTY, modelPath.getParent().resolve("tokenizer.json"));
        ObjectMapper mapper = new ObjectMapper();
        ToolRegistry registry = ToolRegistryFactory.create(
                mapper,
                null,
                new AgentRegistry(),
                null,
                null,
                null,
                null,
                null);
        Set<String> missing = new TreeSet<>(PROJECT_LOCAL_MODEL_TOOL_IDS);
        missing.removeAll(registry.ids());
        assertTrue(missing.isEmpty(), () -> "Production chat registry is missing tools: " + missing);

        List<String> failures = new ArrayList<>();
        int succeeded = 0;
        ParentOwnedModelSession modelSession =
                new ParentOwnedModelSession(MODEL_ID, modelPath, tokenizerPath);
        try (modelSession) {
            for (String toolId : PROJECT_LOCAL_MODEL_TOOL_IDS.stream().sorted().toList()) {
                CliTool cliTool = registry.get(toolId);
                Map<String, Object> expectedArguments = exampleArguments(
                        mapper, cliTool.parameterSchema());
                ObjectNode directDefinition = mapper.createObjectNode();
                directDefinition.put("name", cliTool.id());
                directDefinition.put("description", cliTool.description());
                directDefinition.set("inputSchema", cliTool.parameterSchema());
                ArrayNode definitions = mapper.createArrayNode().add(directDefinition);
                JsonNode optimizedDefinition = ToolSchemaOptimizer.optimize(
                        definitions, ToolSchemaOptimizer.OptimizationLevel.MODERATE).get(0);
                StructuredChatLanguageModel.Tool modelTool =
                        new StructuredChatLanguageModel.Tool(
                                optimizedDefinition.path("name").asText(),
                                optimizedDefinition.path("description").asText(),
                                schemaMap(mapper, optimizedDefinition.path("inputSchema")));
                System.err.printf(
                        "MODEL_MCP_TOOL_REQUEST tool=%s rawSchemaChars=%d modelDefinitionChars=%d%n",
                        toolId,
                        cliTool.parameterSchema().toString().length(),
                        optimizedDefinition.toString().length());
                StructuredChatLanguageModel.Request request =
                        new StructuredChatLanguageModel.Request(
                                List.of(
                                        new StructuredChatLanguageModel.Message(
                                                "system",
                                                "This is an MCP compatibility check. Call the single available tool "
                                                        + "exactly once with arguments that satisfy its JSON schema. "
                                                        + "Use the suggested arguments when they fit the tool contract, "
                                                        + "do not invent parameter keys, and "
                                                        + "do not emit prose after the tool call."),
                                        new StructuredChatLanguageModel.Message(
                                                "user",
                                                "Call " + toolId + " exactly once. Suggested arguments: "
                                                        + mapper.writeValueAsString(expectedArguments))),
                                List.of(modelTool),
                                true,
                                StructuredChatLanguageModel.ToolDefinitionFormat.FLAT,
                                StructuredChatLanguageModel.ToolCallFormat.MODEL,
                                StructuredChatLanguageModel.ToolChoice.REQUIRED);
                try {
                    StructuredChatLanguageModel.Response response =
                            modelSession.generateChat(request, Math.min(MAX_TOKENS, 384));
                    if (!response.parseErrors().isEmpty()) {
                        throw new AssertionError("parse errors=" + response.parseErrors()
                                + ", raw=" + response.rawText());
                    }
                    if (response.toolCalls().size() != 1) {
                        throw new AssertionError("expected one call but received "
                                + response.toolCalls());
                    }
                    StructuredChatLanguageModel.ToolCall call = response.toolCalls().get(0);
                    assertEquals(toolId, call.name(), "Model selected the wrong MCP tool");
                    JsonNode actualTree = mapper.valueToTree(call.arguments());
                    List<String> schemaErrors = new ArrayList<>();
                    validateSchemaValue(
                            actualTree,
                            optimizedDefinition.path("inputSchema"),
                            "$",
                            schemaErrors);
                    if (!schemaErrors.isEmpty()) {
                        throw new AssertionError("schema violations=" + schemaErrors
                                + ", arguments=" + actualTree + ", raw=" + response.rawText());
                    }
                    succeeded++;
                    System.err.printf(
                            "MODEL_MCP_TOOL_PASS tool=%s arguments=%s reasoning=%s%n",
                            toolId, actualTree, boundedText(response.reasoningContent()));
                } catch (Throwable failure) {
                    String detail = toolId + ": " + failure.getMessage();
                    failures.add(detail);
                    System.err.printf("MODEL_MCP_TOOL_FAIL %s%n", detail);
                }
            }
        }

        int passed = succeeded;
        System.err.printf(
                "MODEL_MCP_TOOL_MATRIX passed=%d failed=%d total=%d%n",
                passed, failures.size(), PROJECT_LOCAL_MODEL_TOOL_IDS.size());
        assertTrue(failures.isEmpty(),
                () -> "Qwen project-local MCP compatibility failures (passed " + passed + "/"
                        + PROJECT_LOCAL_MODEL_TOOL_IDS.size() + "): " + failures);
        assertFalse(modelSession.isAvailable(),
                "The parent did not release the model lease after the MCP tool matrix");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaMap(ObjectMapper mapper, JsonNode schema) {
        return mapper.convertValue(schema, Map.class);
    }

    private static Map<String, Object> exampleArguments(ObjectMapper mapper, JsonNode schema) {
        Object generated = exampleValue(mapper, schema);
        if (!(generated instanceof Map<?, ?> generatedMap)) {
            throw new IllegalArgumentException("Tool schema root must produce an object: " + schema);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        generatedMap.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static Object exampleValue(ObjectMapper mapper, JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return "test";
        }
        if (schema.has("const")) {
            return mapper.convertValue(schema.get("const"), Object.class);
        }
        if (schema.path("enum").isArray() && !schema.path("enum").isEmpty()) {
            return mapper.convertValue(schema.path("enum").get(0), Object.class);
        }
        for (String union : List.of("oneOf", "anyOf")) {
            if (schema.path(union).isArray() && !schema.path(union).isEmpty()) {
                return exampleValue(mapper, schema.path(union).get(0));
            }
        }
        String type = schema.path("type").asText("object");
        return switch (type) {
            case "object" -> {
                Map<String, Object> value = new LinkedHashMap<>();
                JsonNode required = schema.path("required");
                JsonNode properties = schema.path("properties");
                if (required.isArray()) {
                    for (JsonNode nameNode : required) {
                        String name = nameNode.asText();
                        value.put(name, exampleValue(mapper, properties.path(name)));
                    }
                }
                yield value;
            }
            case "array" -> {
                int count = Math.max(0, schema.path("minItems").asInt(0));
                List<Object> values = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    values.add(exampleValue(mapper, schema.path("items")));
                }
                yield values;
            }
            case "integer" -> Math.max(1, schema.path("minimum").asInt(1));
            case "number" -> Math.max(1.0, schema.path("minimum").asDouble(1.0));
            case "boolean" -> true;
            case "null" -> null;
            default -> {
                int length = Math.max(1, schema.path("minLength").asInt(1));
                yield "x".repeat(length);
            }
        };
    }

    private static void validateSchemaValue(
            JsonNode value, JsonNode schema, String path, List<String> errors) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return;
        }
        if (schema.path("allOf").isArray()) {
            for (JsonNode branch : schema.path("allOf")) {
                validateSchemaValue(value, branch, path, errors);
            }
        }
        for (String union : List.of("oneOf", "anyOf")) {
            if (schema.path(union).isArray()) {
                boolean matched = false;
                for (JsonNode branch : schema.path(union)) {
                    List<String> branchErrors = new ArrayList<>();
                    validateSchemaValue(value, branch, path, branchErrors);
                    if (branchErrors.isEmpty()) {
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    errors.add(path + " does not satisfy any " + union + " branch");
                }
            }
        }
        if (schema.has("const") && !schema.get("const").equals(value)) {
            errors.add(path + " must equal const " + schema.get("const"));
        }
        if (schema.path("enum").isArray()) {
            boolean allowed = false;
            for (JsonNode candidate : schema.path("enum")) {
                if (candidate.equals(value)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                errors.add(path + " is not one of " + schema.path("enum"));
            }
        }
        String type = schema.path("type").asText("");
        boolean typeMatches = switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
        if (!typeMatches) {
            errors.add(path + " expected " + type + " but was " + value.getNodeType());
            return;
        }
        if (value.isObject()) {
            JsonNode properties = schema.path("properties");
            if (schema.path("required").isArray()) {
                for (JsonNode required : schema.path("required")) {
                    if (!value.has(required.asText())) {
                        errors.add(path + " is missing required property " + required.asText());
                    }
                }
            }
            value.fields().forEachRemaining(field -> {
                JsonNode propertySchema = properties.path(field.getKey());
                if (propertySchema.isMissingNode()) {
                    if (schema.path("additionalProperties").isBoolean()
                            && !schema.path("additionalProperties").asBoolean()) {
                        errors.add(path + " contains unknown property " + field.getKey());
                    }
                } else {
                    validateSchemaValue(
                            field.getValue(), propertySchema, path + "." + field.getKey(), errors);
                }
            });
        } else if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.path("minItems").asInt()) {
                errors.add(path + " has fewer than " + schema.path("minItems").asInt() + " items");
            }
            if (schema.has("maxItems") && value.size() > schema.path("maxItems").asInt()) {
                errors.add(path + " has more than " + schema.path("maxItems").asInt() + " items");
            }
            for (int i = 0; i < value.size(); i++) {
                validateSchemaValue(value.get(i), schema.path("items"), path + "[" + i + "]", errors);
            }
        }
    }

    private static String boundedText(String value) {
        if (value == null) {
            return "null";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ');
        return normalized.length() <= 1_000
                ? normalized : normalized.substring(0, 1_000) + "...";
    }

    @Test
    // 60 min: this test legitimately includes a cold DSP plan rebuild + Triton
    // JIT + CUDA-graph capture (~5-6 min) BEFORE the first token, then a full
    // multi-request crawl. The old 10-min budget fired mid-generation on the
    // first request — JUnit interrupts the test thread, the dispatcher surfaced
    // it as 'Structured model call interrupted' → null → spurious crawl failure
    // (2026-08-30). Must stay < the failsafe 3600s fork cap, which remains the
    // outer bound.
    @Timeout(value = 50, unit = TimeUnit.MINUTES)
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
                    .decomposedPassStrategy(
                            GraphExtractionConfig.DecomposedPassStrategy.ENTITIES_THEN_RELATIONS)
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
            List<Document> corpus = List.of(
                    new Document(
                            "employment-1",
                            "Alex Rivera is a person. Morgan Chen is a person. "
                                    + "Acme Robotics is a company. Nova Labs is a company. "
                                    + "Alex Rivera works at Acme Robotics. "
                                    + "Morgan Chen works at Nova Labs.",
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
            assertTrue(result.graph().getEntities().size() >= 4,
                    () -> "Expected four PERSON and COMPANY entities, got "
                            + result.graph().getEntities());
            Map<String, String> entityTitlesById = result.graph().getEntities().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            entity -> entity.getId(),
                            entity -> entity.getTitle(),
                            (left, right) -> left,
                            LinkedHashMap::new));
            List<String> entityTitles = entityTitlesById.values().stream()
                    .map(title -> title.toLowerCase(java.util.Locale.ROOT))
                    .toList();
            assertTrue(entityTitles.containsAll(List.of(
                            "alex rivera", "morgan chen", "acme robotics", "nova labs")),
                    () -> "Expected all four source entities in one graph delta, got "
                            + entityTitlesById.values());
            List<String> worksAtEndpoints = result.graph().getRelationships().stream()
                    .filter(relation -> "WORKS_AT".equalsIgnoreCase(relation.getType()))
                    .map(relation -> String.valueOf(entityTitlesById.get(relation.getSource()))
                            .toLowerCase(java.util.Locale.ROOT)
                            + " -> "
                            + String.valueOf(entityTitlesById.get(relation.getTarget()))
                            .toLowerCase(java.util.Locale.ROOT))
                    .toList();
            assertTrue(worksAtEndpoints.containsAll(List.of(
                            "alex rivera -> acme robotics",
                            "morgan chen -> nova labs")),
                    () -> "Expected both directed WORKS_AT facts, got " + worksAtEndpoints);
        }

        assertFalse(modelSession.isAvailable(),
                "The parent did not release the model lease after crawl completion");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void pooledModelKeepsOneHomeDeviceAcrossSequentialRealGenerations() throws Exception {
        DspDiagnostics.initialize();
        Path modelPath = requiredFile(MODEL_PROPERTY, DEFAULT_MODEL);
        Path tokenizerPath = requiredFile(
                TOKENIZER_PROPERTY, modelPath.getParent().resolve("tokenizer.json"));

        int loadsBeforeFirstLease = MODEL_LOADS.get();
        SameDiffLanguageModelImpl firstModel;
        Integer homeDevice;
        int loadsAfterFirstLease;
        try (ParentOwnedModelSession first =
                     new ParentOwnedModelSession(MODEL_ID, modelPath, tokenizerPath)) {
            firstModel = first.languageModel;
            homeDevice = firstModel.getModelExecutionDevice();
            assertNotNull(homeDevice, "A loaded pooled model must publish its home device");
            assertNotNull(firstModel.generateResponse("Reply with OK.", List.of(), 8));
            assertEquals(homeDevice, firstModel.getModelExecutionDevice(),
                    "A generation must not change the pooled model home device");
            loadsAfterFirstLease = MODEL_LOADS.get();
            assertTrue(loadsAfterFirstLease == loadsBeforeFirstLease
                            || loadsAfterFirstLease == loadsBeforeFirstLease + 1,
                    "The first lease may load at most one model");
        }

        try (ParentOwnedModelSession second =
                     new ParentOwnedModelSession(MODEL_ID, modelPath, tokenizerPath)) {
            assertTrue(firstModel == second.languageModel,
                    "A compatible sequential lease must reuse the same loaded model");
            assertEquals(homeDevice, second.languageModel.getModelExecutionDevice(),
                    "The reused model must retain its original home device");
            assertNotNull(second.languageModel.generateResponse("Reply with OK.", List.of(), 8));
            assertEquals(homeDevice, second.languageModel.getModelExecutionDevice(),
                    "The second generation must retain the original home device");
            assertEquals(loadsAfterFirstLease, MODEL_LOADS.get(),
                    "Reusing a compatible lease must not load a peer model");
            assertEquals(1, MODEL_POOL.pooledCount(),
                    "The pool must contain exactly one physical model instance");
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void actualModelRecognizesEntitiesWithoutTools() throws Exception {
        DspDiagnostics.initialize();
        Path modelPath = requiredFile(MODEL_PROPERTY, DEFAULT_MODEL);
        Path tokenizerPath = requiredFile(
                TOKENIZER_PROPERTY, modelPath.getParent().resolve("tokenizer.json"));
        ParentOwnedModelSession modelSession =
                new ParentOwnedModelSession(MODEL_ID, modelPath, tokenizerPath);

        StructuredChatLanguageModel.Response response;
        try (modelSession) {
            StructuredChatLanguageModel.Request request =
                    new StructuredChatLanguageModel.Request(
                            List.of(
                                    new StructuredChatLanguageModel.Message(
                                            "system",
                                            "Identify named entities only. Return one line per distinct named "
                                                    + "entity using the exact format NAME | TYPE, where TYPE is "
                                                    + "PERSON or COMPANY. Copy names exactly from Text. No prose."),
                                    new StructuredChatLanguageModel.Message(
                                            "user",
                                            "Text: Alex Rivera is a person. Alex Rivera works at "
                                                    + "Acme Robotics, a company.")),
                            List.of(),
                            true,
                            StructuredChatLanguageModel.ToolDefinitionFormat.FLAT,
                            StructuredChatLanguageModel.ToolCallFormat.MODEL,
                            StructuredChatLanguageModel.ToolChoice.NONE);

            response = modelSession.generateChat(request, MAX_TOKENS);
            String observable = response.rawText() + "\n" + response.content();
            System.err.printf(
                    "MODEL_ENTITY_RECOGNITION_RESPONSE raw=%s content=%s parseErrors=%s%n",
                    response.rawText(),
                    response.content(),
                    response.parseErrors());

            assertTrue(observable.contains("Alex Rivera"),
                    () -> "Model did not recognize Alex Rivera: " + observable);
            assertTrue(observable.contains("Acme Robotics"),
                    () -> "Model did not recognize Acme Robotics: " + observable);
            assertTrue(observable.toUpperCase().contains("PERSON"),
                    () -> "Model did not classify a PERSON: " + observable);
            assertTrue(observable.toUpperCase().contains("COMPANY"),
                    () -> "Model did not classify a COMPANY: " + observable);
        }

        assertFalse(modelSession.isAvailable(),
                "The parent did not release the model lease after plain entity recognition");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void actualModelExtractsEntitiesWithoutRelationOrCardinalityHints() throws Exception {
        assertNativeEntityBatch(
                "Alex Rivera is a person. Alex Rivera works at Acme Robotics, a company.",
                1,
                4,
                List.of(
                        Map.of("name", "Alex Rivera", "type", "PERSON"),
                        Map.of("name", "Acme Robotics", "type", "COMPANY")));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void actualModelExtractsFourEntityBatchWithBoundedNativeArray() throws Exception {
        assertNativeEntityBatch(
                "Alex Rivera is a person. Morgan Chen is a person. "
                        + "Acme Robotics is a company. Nova Labs is a company. "
                        + "Alex Rivera works at Acme Robotics. Morgan Chen works at Nova Labs.",
                4,
                4,
                List.of(
                        Map.of("name", "Alex Rivera", "type", "PERSON"),
                        Map.of("name", "Morgan Chen", "type", "PERSON"),
                        Map.of("name", "Acme Robotics", "type", "COMPANY"),
                        Map.of("name", "Nova Labs", "type", "COMPANY")));
    }

    private void assertNativeEntityBatch(
            String sourceText,
            int minItems,
            int maxItems,
            List<Map<String, String>> expectedEntities) throws Exception {
        DspDiagnostics.initialize();
        Path modelPath = requiredFile(MODEL_PROPERTY, DEFAULT_MODEL);
        Path tokenizerPath = requiredFile(
                TOKENIZER_PROPERTY, modelPath.getParent().resolve("tokenizer.json"));
        ParentOwnedModelSession modelSession =
                new ParentOwnedModelSession(MODEL_ID, modelPath, tokenizerPath);

        StructuredChatLanguageModel.Response response;
        try (modelSession) {
            Map<String, Object> entityItem = Map.of(
                    "type", "object",
                    "additionalProperties", false,
                    "required", List.of("name", "type"),
                    "properties", Map.of(
                            "name", Map.of(
                                    "type", "string",
                                    "minLength", 1,
                                    "maxLength", 80),
                            "type", Map.of(
                                    "type", "string",
                                    "enum", List.of("PERSON", "COMPANY"),
                                    "description",
                                    "Classify this exact name independently from Text evidence. PERSON means a "
                                            + "named human individual; COMPANY means a named business or organization. "
                                            + "Enum order and covering both labels are not evidence.")));
            StructuredChatLanguageModel.Tool tool = new StructuredChatLanguageModel.Tool(
                    "submit_entities",
                    "Submit every distinct PERSON or COMPANY explicitly named in Text. name must be the complete "
                            + "exact identifying Text span: keep every token in a contiguous multi-token proper name "
                            + "and never shorten it to one component. A type label, instruction, example, placeholder, alternate "
                            + "casing, or duplicate is not an entity. Classify each name independently from the "
                            + "sentence containing it and never reuse one name under another type.",
                    Map.of(
                            "type", "object",
                            "additionalProperties", false,
                            "required", List.of("entities"),
                            "properties", Map.of(
                                    "entities", Map.of(
                                            "type", "array",
                                            "minItems", minItems,
                                            "maxItems", maxItems,
                                            "uniqueItems", true,
                                            "items", entityItem))));
            StructuredChatLanguageModel.Request request =
                    new StructuredChatLanguageModel.Request(
                            List.of(
                                    new StructuredChatLanguageModel.Message(
                                            "system",
                                            "Extract named entities only. In thinking, make one left-to-right "
                                                    + "scan and write exactly one compact checklist row per distinct "
                                                    + "name as NAME | local Text type cue | TYPE. Do not restate the "
                                                    + "instructions or sentences, and do not repeat a name. A sentence "
                                                    + "that classifies a name still contains that entity; its common "
                                                    + "noun is the type cue. In a relation sentence, inspect both named "
                                                    + "endpoints. Treat a contiguous multi-token proper name as one complete "
                                                    + "entity and never shorten it to one component. Preserve that complete exact "
                                                    + "Text spelling for repeated mentions. Classify each name "
                                                    + "independently: re-read its sentence, identify what that referent "
                                                    + "is in ordinary words, and match it to exactly one definition: "
                                                    + "PERSON is a named human individual; COMPANY is a named business "
                                                    + "or organization. Enum order, row count, and covering both labels "
                                                    + "are not type evidence. Before calling, verify each type describes "
                                                    + "that same name and no name was reused under another type. A type "
                                                    + "classification is never a name. Do not invent, change case, "
                                                    + "duplicate, or pad with a label, instruction, example, or "
                                                    + "placeholder. Then call submit_entities exactly once with one "
                                                    + "{name,type} object per entity. Do not extract relations or add "
                                                    + "prose after thinking."),
                                    new StructuredChatLanguageModel.Message(
                                            "user",
                                            "Text: " + sourceText)),
                            List.of(tool),
                            true,
                            StructuredChatLanguageModel.ToolDefinitionFormat.FLAT,
                            StructuredChatLanguageModel.ToolCallFormat.MODEL,
                            StructuredChatLanguageModel.ToolChoice.REQUIRED);

            response = modelSession.generateChat(request, MAX_TOKENS);
            System.err.printf(
                    "MODEL_ENTITY_ONLY_RESPONSE minItems=%d maxItems=%d raw=%s content=%s "
                            + "calls=%s parseErrors=%s%n",
                    minItems,
                    maxItems,
                    response.rawText(),
                    response.content(),
                    response.toolCalls(),
                    response.parseErrors());

            assertTrue(response.parseErrors().isEmpty(),
                    () -> "Entity-only native response did not parse: " + response);
            StructuredChatLanguageModel.ToolCall submission = response.toolCalls().stream()
                    .filter(call -> "submit_entities".equals(call.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Model did not call submit_entities: " + response));
            Object rawEntities = submission.arguments().get("entities");
            assertTrue(rawEntities instanceof List<?>,
                    () -> "submit_entities.entities was not an array: " + submission.arguments());
            List<?> entities = (List<?>) rawEntities;
            for (Map<String, String> expected : expectedEntities) {
                assertTrue(containsEntity(entities, expected.get("name"), expected.get("type")),
                        () -> "Model missed " + expected + ": " + submission.arguments());
            }
        }

        assertFalse(modelSession.isAvailable(),
                "The parent did not release the model lease after entity-only extraction");
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void actualModelExtractsTwoNamedRelationsInOneBoundedNativeCall() throws Exception {
        DspDiagnostics.initialize();
        Path modelPath = requiredFile(MODEL_PROPERTY, DEFAULT_MODEL);
        Path tokenizerPath = requiredFile(
                TOKENIZER_PROPERTY, modelPath.getParent().resolve("tokenizer.json"));
        ParentOwnedModelSession modelSession =
                new ParentOwnedModelSession(MODEL_ID, modelPath, tokenizerPath);

        StructuredChatLanguageModel.Response response;
        try (modelSession) {
            List<String> entityNames =
                    List.of("Alex Rivera", "Morgan Chen", "Acme Robotics", "Nova Labs");
            Map<String, Object> endpoint = Map.of(
                    "type", "string",
                    "enum", entityNames);
            Map<String, Object> relationItem = Map.of(
                    "type", "object",
                    "additionalProperties", false,
                    "required", List.of("source", "target", "type"),
                    "properties", Map.of(
                            "source", endpoint,
                            "target", endpoint,
                            "type", Map.of(
                                    "type", "string",
                                    "enum", List.of("WORKS_AT"))));
            StructuredChatLanguageModel.Tool tool = new StructuredChatLanguageModel.Tool(
                    "submit_relations",
                    "Submit every distinct explicit directed Text predicate. source and target must be exact "
                            + "admitted names in their semantic directed roles, not convenient names selected from "
                            + "the admitted list.",
                    Map.of(
                            "type", "object",
                            "additionalProperties", false,
                            "required", List.of("relations"),
                            "properties", Map.of(
                                    "relations", Map.of(
                                            "type", "array",
                                            "minItems", 2,
                                            "maxItems", 2,
                                            "uniqueItems", true,
                                            "items", relationItem))));
            StructuredChatLanguageModel.Request request =
                    new StructuredChatLanguageModel.Request(
                            List.of(
                                    new StructuredChatLanguageModel.Message(
                                            "system",
                                            "Extract directed relations only. Use thinking to process Text one "
                                                    + "sentence at a time. For every explicit predicate, copy both "
                                                    + "endpoint names from that same sentence and independently match "
                                                    + "them to admitted names. For WORKS_AT, the PERSON performing "
                                                    + "'works at' is source and the COMPANY workplace is target. Use "
                                                    + "Text only, never background knowledge or an endpoint from "
                                                    + "another sentence. Then call submit_relations exactly once with "
                                                    + "every distinct fact and no prose after thinking."),
                                    new StructuredChatLanguageModel.Message(
                                            "user",
                                            "Admitted entities: Alex Rivera; Morgan Chen; Acme Robotics; Nova Labs. "
                                                    + "Text: Alex Rivera works at Acme Robotics. "
                                                    + "Morgan Chen works at Nova Labs.")),
                            List.of(tool),
                            true,
                            StructuredChatLanguageModel.ToolDefinitionFormat.FLAT,
                            StructuredChatLanguageModel.ToolCallFormat.MODEL,
                            StructuredChatLanguageModel.ToolChoice.REQUIRED);

            response = modelSession.generateChat(request, MAX_TOKENS);
            System.err.printf(
                    "MODEL_RELATION_ONLY_RESPONSE raw=%s content=%s calls=%s parseErrors=%s%n",
                    response.rawText(),
                    response.content(),
                    response.toolCalls(),
                    response.parseErrors());

            assertTrue(response.parseErrors().isEmpty(),
                    () -> "Relation-only native response did not parse: " + response);
            StructuredChatLanguageModel.ToolCall submission = response.toolCalls().stream()
                    .filter(call -> "submit_relations".equals(call.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Model did not call submit_relations: " + response));
            Object rawRelations = submission.arguments().get("relations");
            assertTrue(rawRelations instanceof List<?>,
                    () -> "submit_relations.relations was not an array: " + submission.arguments());
            List<?> relations = (List<?>) rawRelations;
            assertTrue(containsRelation(
                            relations, "Alex Rivera", "Acme Robotics", "WORKS_AT"),
                    () -> "Model missed Alex Rivera -> Acme Robotics: " + submission.arguments());
            assertTrue(containsRelation(
                            relations, "Morgan Chen", "Nova Labs", "WORKS_AT"),
                    () -> "Model missed Morgan Chen -> Nova Labs: " + submission.arguments());
        }

        assertFalse(modelSession.isAvailable(),
                "The parent did not release the model lease after relation-only extraction");
    }

    private static boolean containsEntity(List<?> entities, String expectedName, String expectedType) {
        return entities.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(entity -> expectedName.equals(entity.get("name"))
                        && expectedType.equals(entity.get("type")));
    }

    private static boolean containsRelation(
            List<?> relations, String expectedSource, String expectedTarget, String expectedType) {
        return relations.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(relation -> expectedSource.equals(relation.get("source"))
                        && expectedTarget.equals(relation.get("target"))
                        && expectedType.equals(relation.get("type")));
    }

    @Test
    @Timeout(value = 50, unit = TimeUnit.MINUTES)
    void actualModelDerivesAccurateOntologyThroughProductionCrawlPath() throws Exception {
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

            modelSession.assertVocabularyPrepass(result);
            assertTrue(modelSession.boundedDocumentRequests() > 0,
                    "The ordinary document request never carried its source-derived proposal plan");

            assertFalse(result.failed(), () -> "Model-backed derived-schema crawl failed: "
                    + result.errors() + "; last response=" + modelSession.lastResponseSummary());
            assertEquals(0, result.parseFailures(),
                    () -> "Derived-schema extraction output was not accepted: " + result.errors());
            assertNotNull(result.graph().getEntities(),
                    "The derived-schema crawl returned no entity list");
            assertNotNull(result.graph().getRelationships(),
                    "The derived-schema crawl returned no relationship list");
            assertEquals(2, result.graph().getEntities().size(),
                    () -> "Final graph added or missed entities: " + result.graph().getEntities());
            assertEquals(1, result.graph().getRelationships().size(),
                    () -> "Final graph added or missed relations: " + result.graph().getRelationships());
            Map<String, ai.kompile.core.graphrag.model.Entity> entitiesByTitle =
                    result.graph().getEntities().stream().collect(java.util.stream.Collectors.toMap(
                            entity -> entity.getTitle().toLowerCase(java.util.Locale.ROOT),
                            entity -> entity,
                            (left, right) -> left,
                            LinkedHashMap::new));
            assertEquals("PERSON", entitiesByTitle.get("jordan lee").getType());
            assertEquals("COMPANY", entitiesByTitle.get("helios dynamics").getType());
            var finalRelation = result.graph().getRelationships().get(0);
            Map<String, String> titlesById = result.graph().getEntities().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            ai.kompile.core.graphrag.model.Entity::getId,
                            ai.kompile.core.graphrag.model.Entity::getTitle));
            assertEquals("FOUNDED", finalRelation.getType());
            assertEquals("Jordan Lee", titlesById.get(finalRelation.getSource()));
            assertEquals("Helios Dynamics", titlesById.get(finalRelation.getTarget()));
        }

        assertFalse(modelSession.isAvailable(),
                "The parent did not release the model lease after derived-schema crawl completion");
    }

    private record SchemaObservation(
            int call,
            StructuredChatLanguageModel.Request request,
            StructuredChatLanguageModel.Response response) {
        private String tool() {
            return request.tools().get(0).name();
        }

        private JsonNode arguments() throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            if (response.toolCalls().size() == 1
                    && tool().equals(response.toolCalls().get(0).name())) {
                return mapper.valueToTree(response.toolCalls().get(0).arguments());
            }
            // Production also accepts native validated JSON without a synthetic tool call.
            if (response.toolCalls().isEmpty() && response.content() != null
                    && !response.content().isBlank()) {
                return mapper.readTree(response.content());
            }
            throw new AssertionError("Missing response for " + tool() + ": " + response);
        }

        private JsonNode promptJson(String marker) throws Exception {
            String prompt = request.messages().stream()
                    .map(StructuredChatLanguageModel.Message::content)
                    .filter(message -> message.contains(marker)).findFirst()
                    .orElseThrow(() -> new AssertionError("Missing schema prompt section: " + marker));
            return new ObjectMapper().readTree(prompt.substring(prompt.indexOf(marker) + marker.length()));
        }

        private boolean hasFoundedSignature() {
            try {
                boolean topic = "bind_topics_to_schema".equals(tool());
                JsonNode arguments = arguments();
                JsonNode options = promptJson("BINDING_OPTION_IDS_JSON=");
                for (String signature : arguments.path(topic ? "b" : "s").asText().split(";")) {
                    String[] ids = signature.split("\\|", -1);
                    if (ids.length != (topic ? 8 : 4)) continue;
                    int relation = topic ? 3 : 0;
                    int source = topic ? 5 : 1;
                    int target = topic ? 6 : 2;
                    int evidence = topic ? 7 : 3;
                    if ("FOUNDED".equals(option(options, "relationshipIds", ids[relation]))
                            && "PERSON".equals(option(options, "endpointIds", ids[source]))
                            && "COMPANY".equals(option(options, "endpointIds", ids[target]))
                            && !option(options, "evidenceIds", ids[evidence]).isBlank()) {
                        return true;
                    }
                }
            } catch (Exception | AssertionError invalidAttempt) {
                // Retain failed attempts for diagnostics; a later production retry may succeed.
            }
            return false;
        }

        private static String option(JsonNode options, String field, String id) {
            return options.path(field).path(Integer.parseInt(id) - 1).asText();
        }
    }

    private record ModelRuntimeKey(
            String modelId,
            Path modelPath,
            long modelSize,
            long modelModifiedMillis,
            Path tokenizerPath,
            long tokenizerSize,
            long tokenizerModifiedMillis,
            Map<String, Object> options,
            List<Long> deviceMemoryLimitsBytes) {

        private static ModelRuntimeKey of(
                String modelId,
                Path modelPath,
                Path tokenizerPath,
                Map<String, Object> options,
                List<Long> deviceMemoryLimitsBytes) throws Exception {
            Path normalizedModel = modelPath.toAbsolutePath().normalize();
            Path normalizedTokenizer = tokenizerPath.toAbsolutePath().normalize();
            return new ModelRuntimeKey(
                    modelId,
                    normalizedModel,
                    Files.size(normalizedModel),
                    Files.getLastModifiedTime(normalizedModel).toMillis(),
                    normalizedTokenizer,
                    Files.size(normalizedTokenizer),
                    Files.getLastModifiedTime(normalizedTokenizer).toMillis(),
                    Map.copyOf(options),
                    List.copyOf(deviceMemoryLimitsBytes));
        }
    }

    /** Process-global overrides live as long as the pooled model, not an individual lease. */
    private static final class RuntimeOverrides implements AutoCloseable {
        private final Map<Integer, Long> previousLimits = new LinkedHashMap<>();
        private final Map<Integer, Long> effectiveLimits = new LinkedHashMap<>();
        private final Map<String, String> previousProperties = new LinkedHashMap<>();

        private void apply(Map<String, Object> options, List<Long> requested) {
            if (!requested.isEmpty()) {
                var environment = Nd4j.getEnvironment();
                int deviceCount = Nd4j.getAffinityManager().getNumberOfDevices();
                if (requested.size() != deviceCount) {
                    throw new IllegalArgumentException(DEVICE_MEMORY_LIMITS_PROPERTY
                            + " requires one positive MiB limit for each of " + deviceCount
                            + " visible logical devices");
                }
                // Same contract as ServingSubprocessMain.applyDeviceMemoryLimits: validate
                // all devices before mutation, retain tighter ceilings, and verify setters.
                for (int device = 0; device < deviceCount; device++) {
                    long previous = environment.getDeviceLimit(device);
                    long limit = previous > 0 ? Math.min(previous, requested.get(device))
                            : requested.get(device);
                    long allocated = environment.getDeviceCounter(device);
                    if (allocated > limit) {
                        throw new IllegalStateException("Logical device " + device + " already has "
                                + allocated + " bytes allocated, exceeding ceiling " + limit);
                    }
                    effectiveLimits.put(device, limit);
                }
                for (int device = 0; device < deviceCount; device++) {
                    long previous = environment.getDeviceLimit(device);
                    long effective = effectiveLimits.get(device);
                    if (previous > 0) effective = Math.min(previous, effective);
                    effectiveLimits.put(device, effective);
                    if (previous != effective) {
                        previousLimits.put(device, previous);
                        environment.setDeviceLimit(device, effective);
                    }
                    if (environment.getDeviceLimit(device) != effective) {
                        throw new IllegalStateException("Backend did not enforce memory ceiling for "
                                + "logical device " + device);
                    }
                    System.err.printf("MODEL_RUNTIME_DEVICE_LIMIT device=%d requestedBytes=%d "
                                    + "previousBytes=%d effectiveBytes=%d%n",
                            device, requested.get(device), previous, effective);
                }
            }
            overrideProperty(options, "graphOptimizerEnabled", ND4JSystemProperties.OPTIMIZER_ENABLED);
            overrideProperty(options, "optimizerFp16", ND4JSystemProperties.OPTIMIZER_FP16);
            System.err.printf("MODEL_RUNTIME_BOUNDS maxNewTokens=%s maxPrefillLength=%s "
                            + "maxKvCacheLength=%s graphOptimizerEnabled=%s optimizerEnabledProperty=%s "
                            + "optimizerFp16Property=%s requestedDeviceLimitsBytes=%s%n",
                    options.get("maxNewTokens"), options.getOrDefault("maxPrefillLength", "model-default"),
                    options.getOrDefault("maxKvCacheLength", "model-default"),
                    options.getOrDefault("graphOptimizerEnabled", true),
                    System.getProperty(ND4JSystemProperties.OPTIMIZER_ENABLED, "unset"),
                    System.getProperty(ND4JSystemProperties.OPTIMIZER_FP16, "unset"), requested);
        }

        private void overrideProperty(Map<String, Object> options, String option, String property) {
            if (options.containsKey(option)) {
                String previous = System.getProperty(property);
                String value = options.get(option).toString();
                if (!value.equals(previous)) {
                    previousProperties.put(property, previous);
                    System.setProperty(property, value);
                }
            }
        }

        private void logMemory(String phase, int call) {
            if (effectiveLimits.isEmpty()) return;
            var environment = Nd4j.getEnvironment();
            for (var entry : effectiveLimits.entrySet()) {
                int device = entry.getKey();
                long limit = environment.getDeviceLimit(device);
                long allocated = environment.getDeviceCounter(device);
                System.err.printf("MODEL_RUNTIME_DEVICE_MEMORY phase=%s call=%d device=%d "
                                + "counterBytes=%d limitBytes=%d effectiveCeilingBytes=%d%n",
                        phase, call, device, allocated, limit, entry.getValue());
                if (limit <= 0 || limit > entry.getValue() || allocated < 0 || allocated > limit) {
                    throw new IllegalStateException("Logical device " + device + " violated memory "
                            + "bounds: counter=" + allocated + ", limit=" + limit
                            + ", effectiveCeiling=" + entry.getValue());
                }
            }
        }

        @Override
        public void close() {
            List<Throwable> failures = new ArrayList<>();
            for (var entry : previousLimits.entrySet()) {
                try {
                    var environment = Nd4j.getEnvironment();
                    int device = entry.getKey();
                    long current = environment.getDeviceLimit(device);
                    long applied = effectiveLimits.get(device);
                    if (current > 0 && current < applied) {
                        // Do not undo a tighter ceiling installed by another owner meanwhile.
                        System.err.printf("MODEL_RUNTIME_DEVICE_LIMIT_RESTORE device=%d "
                                        + "preservedTighterBytes=%d previousBytes=%d%n",
                                device, current, entry.getValue());
                        continue;
                    }
                    if (current != entry.getValue()) {
                        environment.setDeviceLimit(device, entry.getValue());
                    }
                    if (environment.getDeviceLimit(device) != entry.getValue()) {
                        throw new IllegalStateException(
                                "Could not restore memory ceiling for logical device " + device);
                    }
                    System.err.printf("MODEL_RUNTIME_DEVICE_LIMIT_RESTORE device=%d limitBytes=%d%n",
                            device, environment.getDeviceLimit(device));
                } catch (RuntimeException | Error failure) {
                    failures.add(failure);
                }
            }
            for (var entry : previousProperties.entrySet()) {
                try {
                    if (entry.getValue() == null) System.clearProperty(entry.getKey());
                    else System.setProperty(entry.getKey(), entry.getValue());
                } catch (RuntimeException | Error failure) {
                    failures.add(failure);
                }
            }
            if (!failures.isEmpty()) {
                IllegalStateException failure =
                        new IllegalStateException("Could not restore model runtime overrides");
                failures.forEach(failure::addSuppressed);
                // The pool logs only the top-level message; retain per-device failure details.
                failure.printStackTrace(System.err);
                throw failure;
            }
        }
    }

    private static final class PooledLanguageModel implements AutoCloseable {
        private final SameDiffLanguageModelImpl languageModel;
        private final RuntimeOverrides runtimeOverrides = new RuntimeOverrides();
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        private PooledLanguageModel(
                String modelId,
                Path modelPath,
                Path tokenizerPath,
                Map<String, Object> options,
                List<Long> deviceMemoryLimitsBytes) throws Exception {
            SameDiffLanguageModelImpl candidate =
                    new SameDiffLanguageModelImpl(Optional.empty(), Optional.empty());
            try {
                runtimeOverrides.apply(options, deviceMemoryLimitsBytes);
                runtimeOverrides.logMemory("before-load", 0);
                candidate.loadModel(modelId, modelPath, tokenizerPath, options);
                runtimeOverrides.logMemory("after-load", 0);
            } catch (Exception | Error failure) {
                // Restore overrides even if shutdown fails; retain both cleanup failures.
                try (RuntimeOverrides ignored = runtimeOverrides) {
                    candidate.shutdown();
                } catch (Exception | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
            this.languageModel = candidate;
            MODEL_LOADS.incrementAndGet();
        }

        private SameDiffLanguageModelImpl languageModel() {
            return languageModel;
        }

        private boolean isLoaded() {
            return !closed.get() && languageModel.isLoaded();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                // Preserve shutdown/readback failures without skipping limit restoration.
                try (RuntimeOverrides ignored = runtimeOverrides) {
                    languageModel.shutdown();
                    runtimeOverrides.logMemory("after-shutdown", 0);
                }
                // A cleanup failure must also fail the existing teardown load/unload assertion;
                // the reusable pool itself only logs exceptions thrown by its closer.
                MODEL_UNLOADS.incrementAndGet();
            }
        }
    }

    private static final class ParentOwnedModelSession implements LocalServingBackend, AutoCloseable {
        private final String modelId;
        private final ReusableResourcePool.Lease<PooledLanguageModel> modelLease;
        private final SameDiffLanguageModelImpl languageModel;
        private final RuntimeOverrides runtimeOverrides;
        private final AtomicInteger generationCalls = new AtomicInteger();
        private final AtomicInteger activeGenerations = new AtomicInteger();
        private final AtomicBoolean forcedEmploymentEntityRepair = new AtomicBoolean();
        private final Object generationDrain = new Object();
        private final List<SchemaObservation> schemaObservations =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicInteger firstInstanceRequest = new AtomicInteger(Integer.MAX_VALUE);
        private final AtomicInteger boundedDocumentRequests = new AtomicInteger();
        private volatile String lastResponseSummary = "none";
        private volatile boolean closed;

        private ParentOwnedModelSession(String modelId, Path modelPath, Path tokenizerPath)
                throws Exception {
            this.modelId = modelId;
            Map<String, Object> modelOptions = new LinkedHashMap<>();
            modelOptions.put("maxNewTokens", MAX_TOKENS);
            modelOptions.put("dspEnabled", true);
            putRuntimeBoundOverride(modelOptions, "maxPrefillLength", MAX_PREFILL_LENGTH_PROPERTY);
            putRuntimeBoundOverride(modelOptions, "maxKvCacheLength", MAX_KV_CACHE_LENGTH_PROPERTY);
            putRuntimeBooleanOverride(modelOptions, "graphOptimizerEnabled", OPTIMIZER_ENABLED_PROPERTY);
            putRuntimeBooleanOverride(modelOptions, "optimizerFp16", OPTIMIZER_FP16_PROPERTY);
            List<Long> deviceMemoryLimitsBytes = requestedDeviceMemoryLimits();
            Map<String, Object> samplingOverrides = new LinkedHashMap<>();
            putDoubleOverride(samplingOverrides, "repetitionPenalty", REPETITION_PENALTY_PROPERTY);
            putDoubleOverride(samplingOverrides, "temperature", TEMPERATURE_PROPERTY);
            putIntegerOverride(samplingOverrides, "topK", TOP_K_PROPERTY);
            putDoubleOverride(samplingOverrides, "topP", TOP_P_PROPERTY);
            putBooleanOverride(samplingOverrides, "doSample", DO_SAMPLE_PROPERTY);
            putDoubleOverride(samplingOverrides, "presencePenalty", PRESENCE_PENALTY_PROPERTY);
            putIntegerOverride(
                    samplingOverrides, "maxOutputBlockTokens",
                    MAX_OUTPUT_BLOCK_TOKENS_PROPERTY);
            putIntegerOverride(
                    samplingOverrides, "structuredOutputTokenReserve",
                    STRUCTURED_OUTPUT_TOKEN_RESERVE_PROPERTY);
            modelOptions.putAll(samplingOverrides);
            // Exercise the production model-family sampling profile unless a focused integration
            // diagnostic explicitly overrides one or more fields.
            System.err.printf(
                    "MODEL_RUNTIME_CONFIG modelId=%s model=%s tokenizer=%s thinking=%s%n",
                    modelId,
                    modelPath,
                    tokenizerPath,
                    ENABLE_THINKING);
            System.err.printf(
                    "MODEL_RUNTIME_SAMPLING profile=%s overrides=%s thinking=%s%n",
                    samplingOverrides.isEmpty() ? "family-default" : "family-default-with-overrides",
                    samplingOverrides,
                    ENABLE_THINKING);
            ModelRuntimeKey key = ModelRuntimeKey.of(
                    modelId, modelPath, tokenizerPath, modelOptions, deviceMemoryLimitsBytes);
            int loadsBeforeAcquire = MODEL_LOADS.get();
            this.modelLease = MODEL_POOL.acquire(
                    key,
                    () -> new PooledLanguageModel(
                            modelId, modelPath, tokenizerPath, modelOptions, deviceMemoryLimitsBytes),
                    TimeUnit.MINUTES.toMillis(10));
            this.languageModel = modelLease.resource().languageModel();
            this.runtimeOverrides = modelLease.resource().runtimeOverrides;
            System.err.printf(
                    "MODEL_RUNTIME_POOL keyModel=%s reused=%s pooled=%d loads=%d unloads=%d%n",
                    modelId,
                    MODEL_LOADS.get() == loadsBeforeAcquire,
                    MODEL_POOL.pooledCount(),
                    MODEL_LOADS.get(),
                    MODEL_UNLOADS.get());
        }

        private static void putRuntimeBoundOverride(
                Map<String, Object> options, String option, String property) {
            String value = System.getProperty(property);
            if (value == null) return;
            int bound = Integer.parseInt(value.trim());
            if (bound < 0) {
                throw new IllegalArgumentException(property + " must be non-negative (0 = model default)");
            }
            options.put(option, bound);
        }

        private static void putRuntimeBooleanOverride(
                Map<String, Object> options, String option, String property) {
            String value = System.getProperty(property);
            if (value == null) return;
            value = value.trim();
            if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                throw new IllegalArgumentException(property + " must be true or false");
            }
            options.put(option, Boolean.valueOf(value));
        }

        private static List<Long> requestedDeviceMemoryLimits() {
            String value = System.getProperty(DEVICE_MEMORY_LIMITS_PROPERTY);
            if (value == null) return List.of();
            List<Long> limits = new ArrayList<>();
            for (String part : value.split(",", -1)) {
                long mib = Long.parseLong(part.trim());
                if (mib <= 0) {
                    throw new IllegalArgumentException(DEVICE_MEMORY_LIMITS_PROPERTY
                            + " requires positive MiB values");
                }
                limits.add(Math.multiplyExact(mib, 1024L * 1024L));
            }
            return List.copyOf(limits);
        }

        private static void putDoubleOverride(
                Map<String, Object> options, String option, String property) {
            String value = System.getProperty(property);
            if (value != null && !value.isBlank()) {
                options.put(option, Double.valueOf(value));
            }
        }

        private static void putIntegerOverride(
                Map<String, Object> options, String option, String property) {
            String value = System.getProperty(property);
            if (value != null && !value.isBlank()) {
                options.put(option, Integer.valueOf(value));
            }
        }

        private static void putBooleanOverride(
                Map<String, Object> options, String option, String property) {
            String value = System.getProperty(property);
            if (value != null && !value.isBlank()) {
                options.put(option, Boolean.valueOf(value));
            }
        }

        @Override
        public boolean isAvailable() {
            return !closed && modelLease.isHealthy();
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
            enterGeneration();
            try {
                String response = languageModel.generateResponse(prompt, List.of(), MAX_TOKENS);
                lastResponseSummary = bounded(response);
                return response;
            } finally {
                endGeneration(call);
            }
        }

        @Override
        public String generate(String prompt, int maxNewTokens) {
            int call = beginCall();
            enterGeneration();
            try {
                String response = languageModel.generateResponse(prompt, List.of(), maxNewTokens);
                lastResponseSummary = bounded(response);
                return response;
            } finally {
                endGeneration(call);
            }
        }

        @Override
        public StructuredChatLanguageModel.Response generateChat(
                StructuredChatLanguageModel.Request request,
                int maxNewTokens) {
            int call = beginCall();
            boolean schemaPrepass = request.tools().stream()
                    .anyMatch(tool -> SCHEMA_TOOL_NAMES.contains(tool.name()));
            if (!schemaPrepass && request.tools().stream().anyMatch(tool ->
                    Set.of(GRAPH_DELTA_TOOL_NAME, "submit_typed_entities", "submit_relations")
                            .contains(tool.name()))) {
                firstInstanceRequest.accumulateAndGet(call, Math::min);
            }
            boolean foundingDocument = !schemaPrepass
                    && request.tools().stream()
                    .anyMatch(tool -> GRAPH_DELTA_TOOL_NAME.equals(tool.name()))
                    && request.messages().stream().map(StructuredChatLanguageModel.Message::content)
                    .anyMatch(message -> message.contains("Jordan Lee")
                            && message.contains("Helios Dynamics"));
            boolean employmentDocument = !schemaPrepass
                    && request.tools().stream()
                    .anyMatch(tool -> GRAPH_DELTA_TOOL_NAME.equals(tool.name()))
                    && request.messages().stream().map(StructuredChatLanguageModel.Message::content)
                    .anyMatch(message -> message.contains("Alex Rivera")
                            && message.contains("Morgan Chen")
                            && message.contains("Acme Robotics")
                            && message.contains("Nova Labs"));
            boolean employmentEntityPhase = request.tools().stream()
                    .anyMatch(tool -> "submit_typed_entities".equals(tool.name()))
                    && request.messages().stream().map(StructuredChatLanguageModel.Message::content)
                    .anyMatch(message -> message.contains("Alex Rivera")
                            && message.contains("Morgan Chen")
                            && message.contains("Acme Robotics")
                            && message.contains("Nova Labs"));
            if (schemaPrepass) {
                System.err.printf(
                        "MODEL_TO_CRAWL_SCHEMA_REQUEST call=%d tools=%s messages=%s%n",
                        call,
                        request.tools().stream().map(StructuredChatLanguageModel.Tool::name).toList(),
                        bounded(request.messages().toString()));
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
                assertProposalCardinality(submit.parameters(), 4, 2, 13);
                boundedDocumentRequests.incrementAndGet();
                System.err.printf(
                        "MODEL_TO_CRAWL_BOUNDED_DOCUMENT_REQUEST call=%d parameters=%s messages=%s%n",
                        call, bounded(String.valueOf(submit.parameters())),
                        bounded(request.messages().toString()));
            }
            enterGeneration();
            try {
                StructuredChatLanguageModel.Request modelRequest = request;
                if (ENABLE_THINKING) {
                    Map<String, Object> templateArguments =
                            new LinkedHashMap<>(request.templateArguments());
                    templateArguments.put("enable_thinking", true);
                    modelRequest = new StructuredChatLanguageModel.Request(
                            request.messages(),
                            request.tools(),
                            request.addGenerationPrompt(),
                            request.toolDefinitionFormat(),
                            request.toolCallFormat(),
                            request.toolChoice(),
                            templateArguments);
                }
                StructuredChatLanguageModel.Response response =
                        languageModel.generateChat(modelRequest, maxNewTokens);
                if (Boolean.getBoolean(FORCE_FIRST_EMPLOYMENT_ENTITY_REPAIR_PROPERTY)
                        && employmentEntityPhase
                        && forcedEmploymentEntityRepair.compareAndSet(false, true)) {
                    System.err.printf(
                            "MODEL_TO_CRAWL_FORCED_REPAIR call=%d original=%s%n",
                            call, bounded(response.toString()));
                    response = new StructuredChatLanguageModel.Response(
                            "<forced-test-repair>", "", List.of(
                            new StructuredChatLanguageModel.ToolCall(
                                    "forced-entity-repair",
                                    "submit_typed_entities",
                                    Map.of("entities", List.of(
                                            Map.of("name", "Alex Rivera", "type", "PERSON"),
                                            Map.of("name", "Morgan Chen", "type", "COMPANY"),
                                            Map.of("name", "Acme Robotics", "type", "COMPANY"),
                                            Map.of("name", "Nova Labs", "type", "COMPANY"))))),
                            List.of());
                }
                if (schemaPrepass) {
                    schemaObservations.add(new SchemaObservation(call, request, response));
                    System.err.printf("MODEL_TO_CRAWL_SCHEMA_RESPONSE call=%d tools=%s response=%s%n",
                            call, request.tools().stream()
                                    .map(StructuredChatLanguageModel.Tool::name).toList(),
                            bounded(response.toString()));
                }
                lastResponseSummary = "raw=" + bounded(response.rawText())
                        + ", content=" + bounded(response.content())
                        + ", reasoning=" + bounded(response.reasoningContent())
                        + ", blocks=" + bounded(response.outputBlocks().toString())
                        + ", calls=" + response.toolCalls()
                        + ", parseErrors=" + response.parseErrors();
                System.err.printf(
                        "MODEL_TO_CRAWL_RESPONSE call=%d %s%n", call, lastResponseSummary);
                return response;
            } finally {
                endGeneration(call);
            }
        }

        private int beginCall() {
            int call = generationCalls.incrementAndGet();
            logDspMemory("before", call);
            return call;
        }

        private void enterGeneration() {
            synchronized (generationDrain) {
                if (closed) {
                    throw new IllegalStateException("Model session is closed");
                }
                activeGenerations.incrementAndGet();
            }
        }

        private void endGeneration(int call) {
            try {
                logDspMemory("after", call);
            } finally {
                synchronized (generationDrain) {
                    if (activeGenerations.decrementAndGet() == 0) {
                        generationDrain.notifyAll();
                    }
                }
            }
        }

        private void awaitGenerationDrain() {
            boolean interrupted = false;
            synchronized (generationDrain) {
                while (activeGenerations.get() > 0) {
                    try {
                        generationDrain.wait();
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void logDspMemory(String phase, int call) {
            // Explicit ceilings are strict: do not bury counter/limit failures in best-effort
            // DSP diagnostics, and inspect every logical device rather than only this thread's.
            runtimeOverrides.logMemory(phase, call);
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

        private int boundedDocumentRequests() {
            return boundedDocumentRequests.get();
        }

        private void assertVocabularyPrepass(HeadlessUnifiedCorpusExtractor.Result result) throws Exception {
            assertFalse(schemaObservations.isEmpty(), "The real model never ran the vocabulary prepass");
            assertTrue(firstInstanceRequest.get() < Integer.MAX_VALUE,
                    "The crawl never reached instance extraction");
            assertTrue(schemaObservations.stream().allMatch(stage -> stage.call() < firstInstanceRequest.get()),
                    "Vocabulary/hierarchy/signature generation must finish before instance extraction");
            SchemaObservation nodes = lastSchemaStage("submit_node_types");
            assertTrue(nodes.arguments().path("nodeTypes").isArray(),
                    () -> "Missing classified node vocabulary response: " + nodes.response());
            SchemaObservation relations = lastSchemaStage("submit_relationship_types");
            assertTrue(nodes.call() < relations.call(), "Node vocabulary must precede predicate vocabulary");
            assertTrue(relations.arguments().path("relationshipTypes").isArray(),
                    () -> "Missing classified predicate vocabulary response: " + relations.response());

            // The headless result exposes the job's frozen schema. Baseline types need not
            // be reproposed by the model, but the accepted hierarchy/signature must survive.
            var schema = result.canonicalGraphSchema();
            assertNotNull(schema, "The crawl did not expose its frozen corpus schema");
            assertTrue(schema.getAllNodeLabels().containsAll(List.of("PERSON", "COMPANY")),
                    () -> "Frozen vocabulary missed PERSON or COMPANY: " + schema);
            assertTrue(schema.getNodeTypes().stream().anyMatch(node ->
                            "COMPANY".equals(node.getLabel())
                                    && "ORGANIZATION".equals(node.getParentType())),
                    () -> "Frozen hierarchy missed COMPANY -> ORGANIZATION: " + schema);
            assertTrue(schema.getAllRelationshipTypes().contains("FOUNDED"));
            assertNotNull(schema.getPatterns(), "The frozen schema has no endpoint signatures");
            assertTrue(schema.getPatterns().contains("(PERSON)-[:FOUNDED]->(COMPANY)"),
                    () -> "Frozen schema missed the directed founding signature: " + schema.getPatterns());
            boolean foundedSignature = false;
            for (SchemaObservation stage : schemaObservations) {
                if (Set.of("bind_relationship_signatures", "bind_topics_to_schema").contains(stage.tool())) {
                    foundedSignature |= stage.hasFoundedSignature();
                }
            }
            assertTrue(foundedSignature,
                    () -> "Model never bound PERSON -[FOUNDED]-> COMPANY before extraction: "
                            + schemaObservations);
        }

        private SchemaObservation lastSchemaStage(String tool) {
            return schemaObservations.stream().filter(stage -> tool.equals(stage.tool()))
                    .reduce((previous, current) -> current)
                    .orElseThrow(() -> new AssertionError("Missing schema stage: " + tool));
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
            requireEquals(entityCount - 1, requireMap(relationProperties.get("source"),
                    "relation.source").get("maximum"), "relation.source.maximum");
            requireEquals(null, requireMap(relationProperties.get("target"),
                    "relation.target").get("const"), "relation.target.const");
            requireEquals(entityCount - 1, requireMap(relationProperties.get("target"),
                    "relation.target").get("maximum"), "relation.target.maximum");
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
            synchronized (generationDrain) {
                if (closed) {
                    return;
                }
                closed = true;
            }
            awaitGenerationDrain();
            modelLease.close();
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
