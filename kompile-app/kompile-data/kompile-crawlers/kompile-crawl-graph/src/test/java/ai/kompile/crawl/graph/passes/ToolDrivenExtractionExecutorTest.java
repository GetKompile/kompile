/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.crawl.graph.passes;

import ai.kompile.core.crawl.graph.GraphExtractionConfig.DecomposedPromptTier;
import ai.kompile.core.crawl.graph.GraphExtractionConfig.ExtractionTarget;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.graphrag.GraphConstructor.ConceptHint;
import ai.kompile.core.graphrag.GraphConstructor.ExtractionTaskContext;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusPassage;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusSnapshot;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.unified.GraphReasoningQueryService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolDrivenExtractionExecutorTest {

    @Test
    void modelUsesToolsThenSubmitsAnUnboundedValidatedDelta() {
        CrawlCorpusSnapshot corpus = new CrawlCorpusSnapshot("pooled-corpus", List.of(
                new CrawlCorpusPassage(
                        "prior-shard", 0,
                        "Orchid is also named Project Orchid in an earlier complete source passage.",
                        "hash", Map.of("source", "prior"), true)));
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "active-shard",
                "active-document",
                "lfm",
                "graph",
                null,
                GraphExtractionValidationPolicy.defaults(),
                null,
                corpus,
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null));

        List<String> scripted = List.of(
                "I should search first.",
                """
                {"tool":"unified_corpus","args":{
                  "action":"SEARCH","query":"Project Orchid reviewer","limit":5
                }}
                """,
                """
                {"tool":"submit_graph_delta","args":{
                  "entities":[
                    {"id":"orchid","name":"Project Orchid","type":"PROJECT",
                     "aliases":["Orchid"],"description":"The project named in the source","confidence":0.94},
                    {"id":"mira","name":"Mira","type":"PERSON",
                     "description":"The reviewer named in the source","confidence":0.91},
                    {"id":"review","name":"quarterly review","type":"PROCESS_STEP",
                     "description":"The review activity named in the source","confidence":0.84}
                  ],
                  "relations":[
                    {"source":"mira","target":"orchid","type":"REVIEWED",
                     "description":"Mira reviewed Project Orchid","confidence":0.9},
                    {"source":"orchid","target":"review","type":"PARTICIPATED_IN",
                     "description":"Project Orchid was the subject of the review","confidence":0.82}
                  ]
                }}
                """);
        AtomicInteger index = new AtomicInteger();
        List<String> prompts = new ArrayList<>();

        ToolDrivenExtractionExecutor.Result result = new ToolDrivenExtractionExecutor().extract(
                "Mira reviewed Project Orchid during the quarterly review.",
                null,
                backend,
                (passId, prompt) -> {
                    assertEquals(ToolDrivenExtractionExecutor.PASS_ID, passId);
                    prompts.add(prompt);
                    return scripted.get(index.getAndIncrement());
                },
                new DecomposedExtractionExecutor.PromptProfile(
                        DecomposedPromptTier.RICH,
                        4_096,
                        8_192,
                        1_024,
                        3.5,
                        "TEST"));

        assertTrue(result.usable(), () -> result.notes().toString());
        assertEquals(3, result.extraction().entities().size(),
                "the tool protocol must not impose a one-proposal cap");
        assertEquals(2, result.extraction().relations().size());
        assertEquals(List.of("unified_corpus", "submit_graph_delta"), result.toolsUsed());
        assertTrue(prompts.get(1).contains("invalid_tool_call"),
                "malformed output is returned as model-visible protocol feedback");
        assertFalse(prompts.get(1).contains("I should search first."),
                "malformed model text must not be echoed into the retry context");
        assertTrue(prompts.get(2).contains("pooled-corpus"));
        assertFalse(prompts.get(2).contains("invalid_tool_call"),
                "only the current explicit tool state belongs in a fresh request");
        assertTrue(prompts.get(1).contains("CURRENT EXPLICIT TOOL STATE"));
        assertTrue(prompts.get(2).contains("CURRENT EXPLICIT TOOL STATE"));
        assertTrue(prompts.stream().noneMatch(prompt -> prompt.contains("RECENT TOOL HISTORY")));
        assertTrue(prompts.get(0).contains("\"graphState\":\"EMPTY\""));
        assertTrue(prompts.get(0).contains("\"recommendedTool\":\"submit_graph_delta\""));
        assertTrue(prompts.get(0).contains("\"tool\":\"submit_graph_delta\""));
        assertTrue(prompts.get(0).contains("exact JSON envelope"));
        assertTrue(prompts.get(0).contains("CAPABILITIES"));
        assertTrue(prompts.get(0).contains("first-order logic"));
        assertTrue(prompts.get(0).contains("embeddings"));
        assertEquals(1, prompts.get(0).split("\\\"tool\\\"", -1).length - 1,
                "the prompt must expose one call envelope, not repeated nested calls to copy");
        assertTrue(prompts.get(0).length() < 3_000,
                () -> "the initial rich tool prompt must remain query-on-demand sized: "
                        + prompts.get(0).length());
        assertFalse(prompts.get(0).contains("Project validation rules:"),
                "validation detail should arrive only after a rejected submission");
        assertFalse(prompts.get(0).contains("ENTITIES:\n- id="),
                "the model should query graph state, not receive a fixed flattened graph dump");
        assertFalse(prompts.get(0).contains("Japanese"));
        assertFalse(prompts.get(0).contains("Acme Corp"));
    }

    @Test
    void acceptedSubmissionOwnsMetadataAndTerminatesWithoutExtraCalls() {
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "chunk-owned",
                "document-owned",
                "lfm",
                "graph-owned",
                "parent-owned",
                GraphExtractionValidationPolicy.defaults(),
                null,
                new CrawlCorpusSnapshot("empty", List.of()),
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null));
        AtomicInteger calls = new AtomicInteger();

        ToolDrivenExtractionExecutor.Result result = new ToolDrivenExtractionExecutor().extract(
                "The source contains no graph facts.",
                null,
                backend,
                (passId, prompt) -> {
                    calls.incrementAndGet();
                    return """
                            {"tool":"submit_graph_delta","args":{"entities":[],"relations":[]}}
                            """;
                },
                new DecomposedExtractionExecutor.PromptProfile(
                        DecomposedPromptTier.COMPACT,
                        2_048,
                        4_096,
                        512,
                        3.5,
                        "TEST"));

        assertTrue(result.usable());
        assertEquals(1, calls.get());
        assertEquals("chunk-owned", result.extraction().metadata().sourceChunkId());
        assertEquals("document-owned", result.extraction().metadata().sourceDocumentId());
        assertEquals("graph-owned", result.extraction().metadata().graphId());
    }

    @Test
    void malformedToolCallEnvelopeInLegacyModeThrowsBeforeBackendExecution() {
        CrawlExtractionToolBackend backend = retryBackend("invalid-envelope");
        AtomicInteger calls = new AtomicInteger();

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> new ToolDrivenExtractionExecutor().extract(
                        "The source contains a broken envelope.",
                        null,
                        backend,
                        (passId, prompt) -> {
                            calls.incrementAndGet();
                            return """
                                    {"tool":submit_graph_delta, "args": {"entities":[], "relations":[]}}
                                    """;
                        },
                        testProfile()));

        assertTrue(failure.getMessage().contains("Malformed tool-call envelope"));
        assertEquals(1, calls.get());
    }

    @Test
    void structuredAssessmentSeparatesParsedDeclaredAndSchemaValidCalls() {
        Map<String, Object> item = Map.of(
                "type", "object",
                "properties", Map.of("id", Map.of("type", "string")),
                "required", List.of("id"),
                "additionalProperties", false);
        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", Map.of(
                        "entities", Map.of("type", "array", "items", item),
                        "relations", Map.of("type", "array")),
                "required", List.of("entities", "relations"),
                "additionalProperties", false);
        ToolDrivenExtractionExecutor.StructuredRequest request =
                new ToolDrivenExtractionExecutor.StructuredRequest(
                        List.of(),
                        List.of(new ExtractionToolBackend.ToolDefinition(
                                "submit_graph_delta", "submit", parameters)));

        Map<String, Object> validArguments = Map.of(
                "entities", List.of(Map.of("id", "m-chen")),
                "relations", List.of());
        Map<String, Object> invalidArguments = new LinkedHashMap<>();
        invalidArguments.put("entities", List.of(Map.of("id", "m-chen")));
        invalidArguments.put("relationships", List.of("HAS_ROLE"));
        ToolDrivenExtractionExecutor.StructuredResponse response =
                new ToolDrivenExtractionExecutor.StructuredResponse(
                        "<captured-native-output>",
                        "",
                        List.of(
                                new ToolDrivenExtractionExecutor.ToolRequest(
                                        "valid", "submit_graph_delta", validArguments),
                                new ToolDrivenExtractionExecutor.ToolRequest(
                                        "unknown", "submit_graph", validArguments),
                                new ToolDrivenExtractionExecutor.ToolRequest(
                                        "invalid", "submit_graph_delta", invalidArguments)),
                        List.of());

        ToolDrivenExtractionExecutor.StructuredResponseAssessment assessment =
                ToolDrivenExtractionExecutor.assessStructuredResponse(request, response);

        assertTrue(assessment.parserClean());
        assertEquals(3, assessment.parsedCalls());
        assertEquals(2, assessment.declaredCalls());
        assertEquals(1, assessment.schemaValidCalls());
        assertFalse(assessment.protocolValid());
        assertTrue(assessment.calls().get(0).executable());
        assertFalse(assessment.calls().get(1).declared());
        assertFalse(assessment.calls().get(2).schemaValid());
        assertEquals(invalidArguments, assessment.calls().get(2).request().arguments(),
                "protocol assessment must retain the exact model-authored arguments");
        assertTrue(assessment.calls().get(2).errors().stream()
                .anyMatch(error -> error.contains("$.relations is required")));
        assertTrue(assessment.calls().get(2).errors().stream()
                .anyMatch(error -> error.contains("$.relationships is not declared")));

        ToolDrivenExtractionExecutor.StructuredResponseAssessment valid =
                ToolDrivenExtractionExecutor.assessStructuredResponse(
                        request,
                        new ToolDrivenExtractionExecutor.StructuredResponse(
                                "<valid>", "", List.of(response.toolCalls().get(0)), List.of()));
        assertTrue(valid.protocolValid());
    }

    @Test
    void malformedNativeResponseParseErrorsRetryWithProtocolFeedback() {
        CrawlExtractionToolBackend backend = retryBackend("invalid-structured-response");
        List<ToolDrivenExtractionExecutor.StructuredRequest> requests = new ArrayList<>();

        ToolDrivenExtractionExecutor.Result result = new ToolDrivenExtractionExecutor().extractStructured(
                "Mira reviewed Project Orchid.",
                null,
                backend,
                (passId, request) -> {
                    requests.add(request);
                    return new ToolDrivenExtractionExecutor.StructuredResponse(
                            "REPEATED PHASE ANALYSIS THAT MUST NOT BE REINJECTED",
                            "",
                            List.of(),
                            List.of("incomplete model output block: think"));
                },
                testProfile());

        assertFalse(result.usable());
        assertTrue(result.notes().stream().anyMatch(
                note -> note.contains("parser returned malformed tool-call diagnostics")));
        assertTrue(result.notes().stream().anyMatch(
                note -> note.contains("incomplete model output block: think")));
        assertTrue(result.notes().stream().anyMatch(
                note -> note.contains("round 1 parser returned malformed tool-call diagnostics")));
        assertEquals(2, requests.size());
        String retry = requests.get(1).messages().get(1).content();
        assertTrue(retry.contains("Reasoning reached the output limit"));
        assertTrue(retry.contains("Recheck the evidence concisely, end thinking"));
        assertTrue(retry.contains(
                "NEXT: end thinking and invoke submit_graph_delta({entities: [...], relations: [...]})"));
        assertFalse(retry.contains("Protocol diagnostic"));
        assertFalse(retry.contains("REPEATED PHASE ANALYSIS THAT MUST NOT BE REINJECTED"));
    }

    @Test
    void malformedStructuredResponseParseErrorsNeverExecuteRecoveredCall() {
        CrawlExtractionToolBackend backend = retryBackend("invalid-structured-response");

        ToolDrivenExtractionExecutor.Result result = new ToolDrivenExtractionExecutor().extractStructured(
                "Mira reviewed Project Orchid.",
                null,
                backend,
                (passId, request) -> new ToolDrivenExtractionExecutor.StructuredResponse(
                        "{\"tool\":\"submit_graph_delta\",\"args\":{\"entities\":["
                                + "{\"id\":\"mira\",\"name\":\"Mira\",\"type\":\"PERSON\"}],"
                                + "\"relations\":[{\"source\":\"mira\",\"target\":\"orchid\",\"type\":\"REVIEWED\"}]}}",
                        "", List.of(new ToolDrivenExtractionExecutor.ToolRequest(
                                "call-1",
                                "submit_graph_delta",
                        Map.of(
                                "entities", List.of(Map.of(
                                        "id", "mira", "name", "Mira", "type", "PERSON")),
                                        "relations", List.of(Map.of(
                                                "source", "mira", "target", "missing", "type", "REVIEWED"))))),
                                List.of("Recovered declared JSON tool call without explicit envelope")),
                testProfile());

        assertFalse(result.usable(), () -> result.notes().toString());
        assertEquals(0, result.toolCalls(),
                "a parser-diagnosed response must never reach the mutation backend");
        assertTrue(result.notes().stream().anyMatch(
                note -> note.contains("parser returned malformed tool-call diagnostics")));
    }

    @Test
    void entityOnlyNativeModeUsesOneDirectSubmissionContract() {
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "entity-chunk",
                "entity-document",
                "lfm",
                "entity-graph",
                null,
                GraphExtractionValidationPolicy.defaults(),
                null,
                new CrawlCorpusSnapshot("entity-corpus", List.of()),
                null,
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null),
                ExtractionTarget.ENTITIES_ONLY);
        AtomicReference<ToolDrivenExtractionExecutor.StructuredRequest> observed =
                new AtomicReference<>();

        ToolDrivenExtractionExecutor.Result result =
                new ToolDrivenExtractionExecutor().extractStructured(
                        "M. Chen and J. Park reviewed the forecast.",
                        null,
                        backend,
                        (passId, request) -> {
                            observed.set(request);
                            return new ToolDrivenExtractionExecutor.StructuredResponse(
                                    "<native-submit>", "", List.of(
                                            new ToolDrivenExtractionExecutor.ToolRequest(
                                                    "submit-1", "submit_entities",
                                                    Map.of("names", List.of("M. Chen", "J. Park")))),
                                    List.of());
                        },
                        testProfile());

        assertTrue(result.usable(), () -> result.notes().toString());
        assertEquals(2, result.extraction().entities().size());
        assertEquals(0, result.extraction().relations().size());
        assertEquals(List.of("submit_entities"), result.toolsUsed());
        assertTrue(result.extraction().entities().stream()
                .allMatch(entity -> "ENTITY".equals(entity.type())));

        ToolDrivenExtractionExecutor.StructuredRequest request = observed.get();
        assertEquals(1, request.tools().size());
        assertEquals("submit_entities", request.tools().get(0).name());
        assertEquals("Call submit_entities with each distinct name copied exactly from the text.",
                request.messages().get(0).content());
        assertTrue(request.messages().get(0).content().length() < 100);
        assertEquals("Text: M. Chen and J. Park reviewed the forecast.",
                request.messages().get(1).content());
        assertFalse(request.messages().get(1).content().contains("CURRENT GRAPH"));
        assertFalse(request.messages().get(1).content().contains("SOURCE SHARD"));
        assertEquals(List.of("names"), ((List<?>) request.tools().get(0).parameters()
                .get("required")));
        assertFalse(request.tools().get(0).parameters().toString().contains("relations"));
    }

    @Test
    void nativeToolModeUsesFreshRequestsSchemasAndMultipleCalls() {
        CrawlCorpusSnapshot corpus = new CrawlCorpusSnapshot("native-corpus", List.of(
                new CrawlCorpusPassage(
                        "prior", 0, "Orchid is Project Orchid.", "hash",
                        Map.of("source", "prior"), true)));
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "native-chunk",
                "native-document",
                "lfm",
                "native-graph",
                null,
                GraphExtractionValidationPolicy.defaults(),
                null,
                corpus,
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null));
        List<ToolDrivenExtractionExecutor.StructuredRequest> requests = new ArrayList<>();
        AtomicInteger round = new AtomicInteger();
        ExtractionTaskContext task = new ExtractionTaskContext(
                "native-task",
                "native-partition",
                "native-corpus",
                List.of("Mira", "Project Orchid"),
                "native-chunk",
                "SOURCE_EVENT",
                "review relation",
                1.0d,
                "native-graph:0:0",
                null,
                List.of(
                        new ConceptHint("Mira", "PERSON", "unified-corpus-prepass", null),
                        new ConceptHint("Project Orchid", "PROJECT",
                                "unified-corpus-prepass", null)),
                List.of());

        ToolDrivenExtractionExecutor.Result result =
                new ToolDrivenExtractionExecutor().extractStructured(
                        "Mira reviewed Project Orchid.",
                        task,
                        backend,
                        (passId, request) -> {
                            assertEquals(ToolDrivenExtractionExecutor.PASS_ID, passId);
                            requests.add(request);
                            return switch (round.getAndIncrement()) {
                                case 0 -> new ToolDrivenExtractionExecutor.StructuredResponse(
                                        "I should use a tool.", "I should use a tool.",
                                        List.of(), List.of());
                                case 1 -> new ToolDrivenExtractionExecutor.StructuredResponse(
                                        "<native-graph-call>", "", List.of(
                                                new ToolDrivenExtractionExecutor.ToolRequest(
                                                        "graph-1", "graph_reasoning_query",
                                                        Map.of("operation", "CAPABILITIES"))),
                                        List.of());
                                default -> new ToolDrivenExtractionExecutor.StructuredResponse(
                                        "<native-submit>", "", List.of(
                                                new ToolDrivenExtractionExecutor.ToolRequest(
                                                        "submit-1", "submit_graph_delta",
                                                        Map.of(
                                                                "entities", List.of(
                                                                        Map.of(
                                                                                "id", "orchid",
                                                                                "name", "Project Orchid",
                                                                                "type", "PROJECT",
                                                                                "confidence", 0.94),
                                                                        Map.of(
                                                                                "id", "mira",
                                                                                "name", "Mira",
                                                                                "type", "PERSON",
                                                                                "confidence", 0.91)),
                                                                "relations", List.of(Map.of(
                                                                        "source", "mira",
                                                                        "target", "orchid",
                                                                        "type", "REVIEWED",
                                                                        "confidence", 0.90))))),
                                        List.of());
                            };
                        },
                        new DecomposedExtractionExecutor.PromptProfile(
                                DecomposedPromptTier.RICH,
                                4_096,
                                8_192,
                                1_024,
                                3.5,
                                "TEST"),
                        "Extract the explicit REVIEWED relation and preserve exact source names.");

        assertTrue(result.usable(), () -> result.notes().toString());
        assertEquals(2, result.extraction().entities().size());
        assertEquals(1, result.extraction().relations().size());
        assertEquals(List.of("graph_reasoning_query", "submit_graph_delta"),
                result.toolsUsed());

        ToolDrivenExtractionExecutor.StructuredRequest initial = requests.get(0);
        assertEquals(List.of(
                        "submit_graph_delta", "graph_reasoning_query",
                        "update_ontology", "unified_corpus"),
                initial.tools().stream().map(ExtractionToolBackend.ToolDefinition::name).toList());
        assertEquals(List.of("system", "user"),
                initial.messages().stream()
                        .map(ToolDrivenExtractionExecutor.ChatMessage::role).toList());
        String systemPrompt = initial.messages().get(0).content();
        assertTrue(systemPrompt.startsWith("Extract every SOURCE-supported fact"));
        assertTrue(systemPrompt.contains("submit_graph_delta"));
        assertTrue(systemPrompt.contains("every supported entity and typed relation"));
        assertTrue(systemPrompt.contains("must copy an id"));
        assertTrue(systemPrompt.contains("recommendedFirstTool"));
        assertTrue(systemPrompt.contains("is guidance"));
        assertTrue(systemPrompt.contains("Call a function, not a plan"));
        assertTrue(systemPrompt.contains("declared tools"));
        assertFalse(systemPrompt.contains("native function calls"));
        assertFalse(systemPrompt.contains("recommendedFirstTool now"));
        assertTrue(systemPrompt.contains("schema prepass"));
        assertTrue(systemPrompt.contains("relationship type"));
        assertTrue(systemPrompt.contains("graph_reasoning_query"));
        assertTrue(systemPrompt.contains("unified_corpus"));
        assertTrue(systemPrompt.contains("update_ontology"));
        assertTrue(systemPrompt.contains("one unified logical corpus"));
        assertTrue(systemPrompt.contains("not evidence"));
        assertTrue(systemPrompt.length() < 1_800,
                () -> "the core tool prompt should stay small-model legible: "
                        + systemPrompt.length());
        assertFalse(systemPrompt.contains("Both values are"),
                "exact field shape belongs in the native function schema");
        assertTrue(initial.messages().get(1).content().contains("\"graphState\":\"EMPTY\""));
        assertTrue(initial.messages().get(1).content()
                .contains("\"submitArgumentKeys\":[\"entities\",\"relations\"]"));
        assertTrue(initial.messages().get(1).content()
                .contains("TASK ROUTING CONTEXT (not evidence)"));
        assertFalse(initial.messages().get(1).content().contains("concepts:"));
        assertFalse(initial.messages().get(1).content().contains("Mira [PERSON]"));
        assertFalse(initial.messages().get(1).content().contains("Project Orchid [PROJECT]"));
        String initialUser = initial.messages().get(1).content();
        assertTrue(initialUser.contains("PROJECT EXTRACTION INSTRUCTIONS (rules, not evidence):"));
        assertTrue(initialUser.contains(
                "Extract the explicit REVIEWED relation and preserve exact source names."));
        assertTrue(initialUser.contains(
                "Mira reviewed Project Orchid.\nEND SOURCE SHARD\n"));
        assertTrue(initialUser.endsWith(ToolDrivenExtractionExecutor.TOOL_USE_REQUIREMENT));
        assertTrue(initialUser.contains("chat template owns the wire format"));
        assertFalse(initialUser.contains("<|tool_call_start|>"));
        assertFalse(initialUser.contains("<|tool_call_end|>"));
        assertFalse(initialUser.contains("<|python_tag|>"));
        assertFalse(initialUser.contains("NEXT ACTION:"));

        assertTrue(requests.stream().allMatch(request -> request.messages().stream()
                        .map(ToolDrivenExtractionExecutor.ChatMessage::role).toList()
                        .equals(List.of("system", "user"))),
                "every tool iteration must be a fresh system/user request");
        assertTrue(requests.get(1).messages().get(1).content()
                .contains("CURRENT EXPLICIT TOOL STATE"));
        assertTrue(requests.get(1).messages().get(1).content()
                .contains("No executable native function call"));
        assertFalse(requests.get(1).messages().get(1).content()
                .contains("top-level JSON tool envelope"));
        assertTrue(requests.stream().allMatch(request -> request.messages().get(1).content()
                .endsWith(ToolDrivenExtractionExecutor.TOOL_USE_REQUIREMENT)));
        String finalState = requests.get(2).messages().get(1).content();
        assertFalse(finalState.contains("Function: unified_corpus"),
                "the unified-document structured workflow must not replay cross-document state");
        assertTrue(finalState.contains("Function: graph_reasoning_query"));
        assertTrue(finalState.contains("Project Orchid"));
        assertTrue(requests.stream().noneMatch(request -> request.messages().stream()
                .anyMatch(message -> "assistant".equals(message.role())
                        || "tool".equals(message.role()))),
                "audit transcript roles must never become extraction prompt memory");
    }

    @Test
    void typedEntityPassFeedsImmutableEntityTableIntoRelationPass() {
        GraphSchema schema = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A named person", null),
                        new NodeType("COMPANY", "A named company", null)),
                List.of(new RelationshipType(
                        "WORKS_AT", "A person works at a company", null)),
                List.of("(PERSON)-[:WORKS_AT]->(COMPANY)"));
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "phase-chunk",
                "phase-document",
                "qwen",
                "phase-graph",
                null,
                GraphExtractionValidationPolicy.defaults(),
                schema,
                new CrawlCorpusSnapshot("phase-corpus", List.of()),
                null,
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null),
                ExtractionTarget.FULL_GRAPH,
                null,
                false);
        backend.configureCompactProposalCardinality(2, 1);
        backend.configureCompactEntityCandidates(
                List.of("Alex Rivera", "Acme Robotics"),
                List.of("PERSON", "COMPANY"));
        backend.configureCompactRelationCandidates(
                List.of(0), List.of(1), List.of("WORKS_AT"));
        backend.beginTypedEntityPhase();

        List<ToolDrivenExtractionExecutor.StructuredRequest> requests = new ArrayList<>();
        ToolDrivenExtractionExecutor.Result entities =
                new ToolDrivenExtractionExecutor().extractStructured(
                        "Alex Rivera works at Acme Robotics.",
                        null,
                        backend,
                        (passId, request) -> {
                            requests.add(request);
                            return new ToolDrivenExtractionExecutor.StructuredResponse(
                                    "<entities>", "", List.of(
                                            new ToolDrivenExtractionExecutor.ToolRequest(
                                                    "entities-1", "submit_typed_entities", Map.of(
                                                            "entities", List.of(
                                                                    Map.of("name", "Alex Rivera",
                                                                            "type", "PERSON"),
                                                                    Map.of("name", "Acme Robotics",
                                                                            "type", "COMPANY"))))),
                                    List.of());
                        },
                        new DecomposedExtractionExecutor.PromptProfile(
                                DecomposedPromptTier.COMPACT,
                                4_096,
                                8_192,
                                512,
                                3.5,
                                "TEST"),
                        null);

        assertTrue(entities.usable(), () -> entities.notes().toString());
        assertEquals(2, entities.extraction().entities().size());
        assertEquals(List.of("submit_typed_entities"),
                requests.get(0).tools().stream()
                        .map(ExtractionToolBackend.ToolDefinition::name).toList());
        assertEquals(List.of("entities"), new ArrayList<>(
                ((Map<?, ?>) requests.get(0).tools().get(0).parameters()
                        .get("properties")).keySet()));
        String entitySystem = requests.get(0).messages().get(0).content().strip();
        assertEquals(
                "Use Text to verify each listed candidate's exact name and ontology type.\n"
                        + "Invoke submit_typed_entities once.",
                entitySystem);
        assertFalse(entitySystem.contains("prompt"));
        assertFalse(entitySystem.contains("format"));
        String entityUser = requests.get(0).messages().get(1).content();
        assertFalse(entityUser.contains("EXPECTED ENTITIES"));
        assertTrue(entityUser.contains("ALLOWED ENTITY TYPE LABELS: COMPANY, PERSON"));
        assertTrue(entityUser.contains("ENTITY TYPE DEFINITIONS (LABEL = MEANING):"));
        assertTrue(entityUser.contains("COMPANY = A named company"));
        assertTrue(entityUser.contains("PERSON = A named person"));
        assertTrue(entityUser.contains(
                "ENTITY CANDIDATES (2):\n"
                        + "1. \"Alex Rivera\" => PERSON\n"
                        + "2. \"Acme Robotics\" => COMPANY"));
        assertFalse(entityUser.contains("checklist"));
        assertFalse(entityUser.contains("TASK MODE"));
        assertFalse(entityUser.contains("Do not"));
        assertFalse(entityUser.contains(
                "TYPE VERIFICATION (perform independently for every name before the native call)"));
        assertTrue(entityUser.contains(
                "TEXT:\nAlex Rivera works at Acme Robotics.\nEND TEXT"));
        Map<?, ?> entityArraySchema = (Map<?, ?>) ((Map<?, ?>)
                requests.get(0).tools().get(0).parameters().get("properties")).get("entities");
        List<?> positionalEntities = (List<?>) entityArraySchema.get("prefixItems");
        assertEquals(2, positionalEntities.size());
        assertEquals(false, entityArraySchema.get("items"));
        Map<?, ?> firstEntityProperties = (Map<?, ?>) ((Map<?, ?>)
                positionalEntities.get(0)).get("properties");
        Map<?, ?> secondEntityProperties = (Map<?, ?>) ((Map<?, ?>)
                positionalEntities.get(1)).get("properties");
        assertEquals("Alex Rivera",
                ((Map<?, ?>) firstEntityProperties.get("name")).get("const"));
        assertEquals("Acme Robotics",
                ((Map<?, ?>) secondEntityProperties.get("name")).get("const"));
        assertFalse(((Map<?, ?>) firstEntityProperties.get("type")).containsKey("const"));
        assertFalse(((Map<?, ?>) secondEntityProperties.get("type")).containsKey("const"));
        String entityTypeDescription =
                ((Map<?, ?>) firstEntityProperties.get("type")).get("description").toString();
        assertTrue(entityTypeDescription.contains("PERSON = A named person"));
        assertTrue(entityTypeDescription.contains("COMPANY = A named company"));
        assertTrue(entityTypeDescription.contains(
                "Classify this exact name independently from its Text evidence"));
        assertTrue(entityTypeDescription.contains(
                "Enum order, row count, and covering every label are not evidence"));
        assertTrue(requests.get(0).tools().get(0).description().contains(
                "Classify each name independently from its Text referent"));

        backend.beginRelationPhase(entities.extraction().entities());
        AtomicInteger relationRound = new AtomicInteger();
        ToolDrivenExtractionExecutor.Result relations =
                new ToolDrivenExtractionExecutor().extractStructured(
                        "Alex Rivera works at Acme Robotics.",
                        null,
                        backend,
                        (passId, request) -> {
                            requests.add(request);
                            if (relationRound.getAndIncrement() == 0) {
                                return new ToolDrivenExtractionExecutor.StructuredResponse(
                                        "REPEATED RELATION PLAN", "", List.of(),
                                        List.of("incomplete model output block: think"));
                            }
                            String retry = request.messages().get(1).content();
                            assertTrue(retry.contains(
                                    "RELATION RETRY. Recheck each listed candidate against Text: predicate, direction, "
                                            + "endpoint types, and ontology label. Then invoke submit_relations once."));
                            assertTrue(retry.contains("RELATION CANDIDATES (1; fixed endpoints):"));
                            assertFalse(retry.contains("REPEATED RELATION PLAN"));
                            return new ToolDrivenExtractionExecutor.StructuredResponse(
                                    "<relations>", "", List.of(
                                            new ToolDrivenExtractionExecutor.ToolRequest(
                                                    "relations-1", "submit_relations", Map.of(
                                                            "relations", List.of(Map.of(
                                                                    "source", 0,
                                                                    "target", 1,
                                                                    "type", "WORKS_AT"))))),
                                    List.of());
                        },
                        new DecomposedExtractionExecutor.PromptProfile(
                                DecomposedPromptTier.COMPACT,
                                4_096,
                                8_192,
                                512,
                                3.5,
                                "TEST"),
                        null);

        assertTrue(relations.usable(), () -> relations.notes().toString());
        assertEquals(2, relations.extraction().entities().size());
        assertEquals(1, relations.extraction().relations().size());
        assertEquals(List.of("submit_relations"),
                requests.get(1).tools().stream()
                        .map(ExtractionToolBackend.ToolDefinition::name).toList());
        assertEquals(List.of("relations"), new ArrayList<>(
                ((Map<?, ?>) requests.get(1).tools().get(0).parameters()
                        .get("properties")).keySet()));
        String relationSystem = requests.get(1).messages().get(0).content().strip();
        assertEquals(
                "Use Text to verify each listed candidate's predicate, direction, endpoints, and ontology label.\n"
                        + "Invoke submit_relations once.",
                relationSystem);
        assertFalse(relationSystem.contains("prompt"));
        assertFalse(relationSystem.contains("format"));
        String relationUser = requests.get(1).messages().get(1).content();
        assertFalse(relationUser.contains("EXPECTED RELATIONS"));
        assertTrue(relationUser.contains(
                "IMMUTABLE ENTITY INDEX TABLE (source and target must use these integers):\n"
                        + "- 0: \"Alex Rivera\" [PERSON]\n"
                        + "- 1: \"Acme Robotics\" [COMPANY]"));
        assertTrue(relationUser.contains(
                "RELATION CANDIDATES (1; fixed endpoints):\n"
                        + "1. \"Alex Rivera\" [0] -> \"Acme Robotics\" [1] | WORKS_AT"));
        assertFalse(relationUser.contains("checklist"));
        assertTrue(relationUser.contains(
                "RELATION TYPE TABLE (LABEL = MEANING | DIRECTED ENDPOINTS):"));
        assertTrue(relationUser.contains("WORKS_AT = A person works at a company"));
        assertTrue(relationUser.contains("(PERSON)-[:WORKS_AT]->(COMPANY)"), relationUser);
        assertFalse(relationUser.contains("TASK MODE"));
        assertFalse(relationUser.contains("THINKING LIMIT"));
        assertFalse(relationUser.contains("Do not"));
        Map<?, ?> relationArraySchema = (Map<?, ?>) ((Map<?, ?>)
                requests.get(1).tools().get(0).parameters().get("properties")).get("relations");
        List<?> positionalRelations = (List<?>) relationArraySchema.get("prefixItems");
        assertEquals(1, positionalRelations.size());
        assertEquals(false, relationArraySchema.get("items"));
        Map<?, ?> relationProperties = (Map<?, ?>) ((Map<?, ?>)
                positionalRelations.get(0)).get("properties");
        assertEquals(0, ((Map<?, ?>) relationProperties.get("source")).get("const"));
        assertEquals(1, ((Map<?, ?>) relationProperties.get("target")).get("const"));
        assertFalse(((Map<?, ?>) relationProperties.get("type")).containsKey("const"));
        assertTrue(((Map<?, ?>) relationProperties.get("source")).get("description").toString()
                .contains("independently"));
        assertTrue(((Map<?, ?>) relationProperties.get("target")).get("description").toString()
                .contains("independently"));
        String relationTypeDescription =
                ((Map<?, ?>) relationProperties.get("type")).get("description").toString();
        assertTrue(relationTypeDescription.contains("WORKS_AT = A person works at a company"));
        assertTrue(relationTypeDescription.contains(
                "directed endpoints (PERSON)-[:WORKS_AT]->(COMPANY)"));
        String relationPrompt = requests.get(1).messages().get(1).content();
        assertTrue(relationPrompt.contains(
                "IMMUTABLE ENTITY INDEX TABLE (source and target must use these integers):"));
        assertFalse(relationPrompt.contains("ALLOWED DIRECTED ENDPOINT PATTERNS:"));
        assertTrue(relationPrompt.contains("TEXT:\nAlex Rivera works at Acme Robotics.\nEND TEXT"));
        assertFalse(relationPrompt.contains("conversation"));
        assertFalse(relationPrompt.contains("previous context"));
    }

    @Test
    void relationValidationRetryNamesRejectedRowSchemaAndExactRepairShape() {
        GraphSchema schema = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A named person", null),
                        new NodeType("COMPANY", "A named company", null)),
                List.of(new RelationshipType(
                        "WORKS_AT", "A person works at a company", null)),
                List.of("(PERSON)-[:WORKS_AT]->(COMPANY)"));
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "repair-phase-chunk",
                "repair-phase-document",
                "qwen",
                "repair-phase-graph",
                null,
                GraphExtractionValidationPolicy.defaults(),
                schema,
                new CrawlCorpusSnapshot("repair-phase-corpus", List.of()),
                null,
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null),
                ExtractionTarget.FULL_GRAPH,
                null,
                false);
        backend.configureCompactProposalCardinality(2, 1);
        DecomposedExtractionExecutor.PromptProfile profile =
                new DecomposedExtractionExecutor.PromptProfile(
                        DecomposedPromptTier.COMPACT,
                        4_096,
                        8_192,
                        512,
                        3.5,
                        "TEST");

        backend.beginTypedEntityPhase();
        ToolDrivenExtractionExecutor.Result entities =
                new ToolDrivenExtractionExecutor().extractStructured(
                        "Alex Rivera works at Acme Robotics.",
                        null,
                        backend,
                        (passId, request) -> new ToolDrivenExtractionExecutor.StructuredResponse(
                                "<entities>", "", List.of(
                                        new ToolDrivenExtractionExecutor.ToolRequest(
                                                "entities-1", "submit_typed_entities", Map.of(
                                                        "entities", List.of(
                                                                Map.of("name", "Alex Rivera",
                                                                        "type", "PERSON"),
                                                                Map.of("name", "Acme Robotics",
                                                                        "type", "COMPANY"))))),
                                List.of()),
                        profile,
                        null);
        assertTrue(entities.usable(), () -> entities.notes().toString());

        backend.beginRelationPhase(entities.extraction().entities());
        AtomicInteger round = new AtomicInteger();
        List<ToolDrivenExtractionExecutor.StructuredRequest> requests = new ArrayList<>();
        ToolDrivenExtractionExecutor.Result relations =
                new ToolDrivenExtractionExecutor().extractStructured(
                        "Alex Rivera works at Acme Robotics.",
                        null,
                        backend,
                        (passId, request) -> {
                            requests.add(request);
                            if (round.getAndIncrement() == 0) {
                                return new ToolDrivenExtractionExecutor.StructuredResponse(
                                        "<reversed>", "", List.of(
                                                new ToolDrivenExtractionExecutor.ToolRequest(
                                                        "relations-bad", "submit_relations", Map.of(
                                                                "relations", List.of(Map.of(
                                                                        "source", 1,
                                                                        "target", 0,
                                                                        "type", "WORKS_AT"))))),
                                        List.of());
                            }
                            String retry = request.messages().get(1).content();
                            assertTrue(retry.contains("PREVIOUS RELATION SUBMISSION WAS REJECTED"));
                            assertTrue(retry.contains(
                                    "RELATION REPAIR CARD (replace rejected rows only)"));
                            assertTrue(retry.contains("Rejected rows:"));
                            assertTrue(retry.contains("row 0"));
                            assertTrue(retry.contains("\"source\":1"));
                            assertTrue(retry.contains("\"target\":0"));
                            assertTrue(retry.contains(
                                    "Immutable entity index: 0=Alex Rivera [PERSON]; 1=Acme Robotics [COMPANY]"));
                            assertTrue(retry.contains("Allowed directed patterns"));
                            assertTrue(retry.contains(
                                    "(PERSON)-[:WORKS_AT]->(COMPANY)"));
                            assertTrue(retry.contains(
                                    "NEXT: invoke this native call with corrected or missing rows only: "
                                            + "submit_relations with a relations array populated only with actual "
                                            + "integer lookup indices and allowed ontology relation labels"));
                            assertTrue(retry.contains(
                                    "copy both endpoint names from the same explicit predicate"));
                            assertTrue(retry.contains(
                                    "map each name independently to the immutable index"));
                            assertTrue(retry.contains("Relation type definitions"));
                            assertTrue(retry.contains("WORKS_AT = A person works at a company"));
                            assertTrue(retry.contains("HOW TO CORRECT EACH REJECTED RELATION"));
                            assertTrue(retry.contains(
                                    "Use no background knowledge and do not substitute a convenient endpoint "
                                            + "from another sentence"));
                            assertTrue(retry.contains(
                                    "do not emit a second row format or prose"));
                            assertTrue(retry.contains("definition matches the predicate"));
                            assertTrue(retry.contains(
                                    "directed endpoint pattern matches source type to target type"));
                            assertFalse(retry.contains("SOURCE_NAME [SOURCE_INDEX]"));
                            assertFalse(retry.contains("SOURCE_INDEX_INTEGER"));
                            assertFalse(retry.contains("ALLOWED_RELATION_TYPE"));
                            assertFalse(retry.contains("Do not explain, restate this card"));
                            assertFalse(retry.contains("Rejected candidate draft"));
                            assertFalse(retry.contains("\"correction\""));
                            return new ToolDrivenExtractionExecutor.StructuredResponse(
                                    "<corrected>", "", List.of(
                                            new ToolDrivenExtractionExecutor.ToolRequest(
                                                    "relations-good", "submit_relations", Map.of(
                                                            "relations", List.of(Map.of(
                                                                    "source", 0,
                                                                    "target", 1,
                                                                    "type", "WORKS_AT"))))),
                                    List.of());
                        },
                        profile,
                        null);

        assertTrue(relations.usable(), () -> relations.notes().toString());
        assertEquals(1, relations.extraction().relations().size());
        assertEquals("Alex Rivera",
                relations.extraction().entities().get(0).name());
        assertEquals(2, requests.size());
    }

    @Test
    void strictCompactPromptDirectsSubmissionAndDoesNotReplayUngroundedDrafts() {
        GraphSchema schema = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", null),
                        new NodeType("COMPANY", "A company", null)),
                List.of(new RelationshipType(
                        "WORKS_AT", "A person works at a company", null)),
                List.of("(PERSON)-[:WORKS_AT]->(COMPANY)"));
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "strict-chunk",
                "strict-document",
                "lfm",
                "strict-graph",
                null,
                GraphExtractionValidationPolicy.defaults(),
                schema,
                new CrawlCorpusSnapshot("strict-corpus", List.of()),
                null,
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null),
                ExtractionTarget.FULL_GRAPH,
                null,
                false);
        List<ToolDrivenExtractionExecutor.StructuredRequest> requests = new ArrayList<>();
        AtomicInteger round = new AtomicInteger();

        ToolDrivenExtractionExecutor.Result result =
                new ToolDrivenExtractionExecutor().extractStructured(
                        "Alex Rivera works at Acme Robotics.",
                        null,
                        backend,
                        (passId, request) -> {
                            requests.add(request);
                            if (round.getAndIncrement() == 0) {
                                return new ToolDrivenExtractionExecutor.StructuredResponse(
                                        "<bad-submit>", "", List.of(
                                                new ToolDrivenExtractionExecutor.ToolRequest(
                                                        "submit-1", "submit_graph_delta", Map.of(
                                                                "format", "indexed",
                                                                "entities", List.of(Map.of(
                                                                        "name", "submit_graph_delta instruction text",
                                                                        "type", "PERSON")),
                                                                "relations", List.of()))),
                                        List.of());
                            }
                            return new ToolDrivenExtractionExecutor.StructuredResponse(
                                    "<good-submit>", "", List.of(
                                            new ToolDrivenExtractionExecutor.ToolRequest(
                                                    "submit-2", "submit_graph_delta", Map.of(
                                                            "format", "indexed",
                                                            "entities", List.of(
                                                                    Map.of("name", "Alex Rivera",
                                                                            "type", "PERSON"),
                                                                    Map.of("name", "Acme Robotics",
                                                                            "type", "COMPANY")),
                                                            "relations", List.of(Map.of(
                                                                    "source", 0,
                                                                    "target", 1,
                                                                    "type", "WORKS_AT"))))),
                                    List.of());
                        },
                        new DecomposedExtractionExecutor.PromptProfile(
                                DecomposedPromptTier.COMPACT,
                                4_096,
                                8_192,
                                1_024,
                                3.5,
                                "TEST"),
                        "Extract PERSON, COMPANY, and WORKS_AT facts.");

        assertTrue(result.usable(), () -> result.notes().toString());
        assertEquals(2, requests.size());
        ToolDrivenExtractionExecutor.StructuredRequest initial = requests.get(0);
        assertEquals(List.of("submit_graph_delta"),
                initial.tools().stream().map(ExtractionToolBackend.ToolDefinition::name).toList());
        String toolContract = initial.tools().get(0).parameters().toString();
        assertTrue(toolContract.contains(
                "Classify this exact name independently from its Text evidence."));
        assertTrue(toolContract.contains(
                "Enum order, row count, and covering every label are not evidence"));
        assertTrue(toolContract.contains(
                "Classify the directed predicate stated by Text. Use one ontology relation label and its endpoint pattern."));
        assertTrue(toolContract.contains("PERSON = A person"));
        assertTrue(toolContract.contains("COMPANY = A company"));
        assertTrue(toolContract.contains("WORKS_AT = A person works at a company"));
        assertTrue(toolContract.contains(
                "Each explicit Text-supported directed fact between extracted entities once."));
        String system = initial.messages().get(0).content();
        assertTrue(system.startsWith(
                "Extract Text with submit_graph_delta(format=\"indexed\")."));
        assertTrue(system.contains(
                "An entity is a distinct named node explicitly mentioned in Text"));
        assertTrue(system.contains(
                "A relation is an explicit directed fact connecting two extracted entities"));
        assertTrue(system.contains(
                "Ontology type labels classify nodes or edges; they are not entity names or source evidence."));
        assertTrue(system.contains(
                "First collect names from Text, then type them, then add only Text-supported relations."));
        assertFalse(system.contains("Extract PERSON, COMPANY, and WORKS_AT facts."));
        assertFalse(system.contains("schema prepass"));
        assertFalse(system.contains("update_ontology"));
        assertFalse(system.contains("unified_corpus"));
        assertTrue(system.length() < 900, () -> "strict direct prompt is too large: " + system.length());

        String initialUser = initial.messages().get(1).content();
        assertTrue(initialUser.startsWith(
                "SCOPED EXTRACTION RULE (instruction, not evidence): "
                        + "Extract PERSON, COMPANY, and WORKS_AT facts.\n"));
        assertTrue(initialUser.endsWith("Text: Alex Rivera works at Acme Robotics."));
        assertFalse(initialUser.contains("FINAL:"));
        assertFalse(initialUser.contains("CURRENT GRAPH"));
        assertFalse(initialUser.contains("SOURCE SHARD"));
        assertFalse(initialUser.contains("TASK ROUTING CONTEXT"));
        assertTrue(initialUser.length() < 300,
                () -> "strict direct user prompt is too large: " + initialUser.length());

        String retry = requests.get(1).messages().get(1).content();
        assertTrue(retry.contains("[SOURCE_GROUNDING]"));
        assertTrue(retry.contains("zero-based indices into this submission's entities array only"));
        assertTrue(retry.contains("never use graph ids or retained entity ids"));
        assertFalse(retry.contains("Rejected candidate draft"));
        assertFalse(retry.contains("submit_graph_delta instruction text"),
                "ungrounded model text must not be replayed into the next prompt");
    }

    @Test
    void identicalRejectedNativeSubmissionStopsAfterTwoRounds() {
        GraphSchema schema = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", null),
                        new NodeType("COMPANY", "A company", null)),
                List.of(new RelationshipType(
                        "WORKS_AT", "A person works at a company", null)),
                List.of("(PERSON)-[:WORKS_AT]->(COMPANY)"));
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "repeated-rejection-chunk",
                "repeated-rejection-document",
                "lfm",
                "repeated-rejection-graph",
                null,
                GraphExtractionValidationPolicy.defaults(),
                schema,
                new CrawlCorpusSnapshot("repeated-rejection-corpus", List.of()),
                null,
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null),
                ExtractionTarget.FULL_GRAPH,
                null,
                false);
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

        ToolDrivenExtractionExecutor.Result result =
                new ToolDrivenExtractionExecutor().extractStructured(
                        "Alex Rivera works at Acme Robotics.",
                        null,
                        backend,
                        (passId, request) -> {
                            int call = modelCalls.getAndIncrement();
                            return new ToolDrivenExtractionExecutor.StructuredResponse(
                                    "<repeated-submit>", "", List.of(
                                            new ToolDrivenExtractionExecutor.ToolRequest(
                                                    "same-call", "submit_graph_delta",
                                                    call == 0
                                                            ? firstArguments : repeatedArguments)),
                                    List.of());
                        },
                        new DecomposedExtractionExecutor.PromptProfile(
                                DecomposedPromptTier.COMPACT,
                                4_096,
                                8_192,
                                1_024,
                                3.5,
                                "TEST"));

        assertFalse(result.usable(), () -> result.notes().toString());
        assertEquals(2, modelCalls.get(),
                "numeric serialization drift must not disguise an unchanged rejected call");
        assertEquals(ToolDrivenExtractionExecutor.FailureKind.TERMINAL,
                result.failureKind());
        assertFalse(result.retryableFailure());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains(
                "identical rejected native tool rounds")));
    }

    @Test
    void structuredOntologyUpdateRefreshesNextTurnPromptAndNativeTypeEnums() {
        GraphSchema initial = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", null),
                        new NodeType("ORGANIZATION", "An organization", null)),
                List.of(new RelationshipType(
                        "WORKS_AT", "A person works at an organization", null)),
                List.of("(PERSON)-[:WORKS_AT]->(ORGANIZATION)"));
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "ontology-chunk",
                "ontology-document",
                "lfm",
                "ontology-graph",
                null,
                GraphExtractionValidationPolicy.defaults(),
                initial,
                new CrawlCorpusSnapshot("ontology-corpus", List.of()),
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null));

        List<ToolDrivenExtractionExecutor.StructuredRequest> requests = new ArrayList<>();
        AtomicInteger round = new AtomicInteger();
        ToolDrivenExtractionExecutor.Result result =
                new ToolDrivenExtractionExecutor().extractStructured(
                        "The email finance@example.com identifies Mira.",
                        null,
                        backend,
                        (passId, request) -> {
                            requests.add(request);
                            if (round.getAndIncrement() == 0) {
                                assertFalse(request.tools().toString().contains("EMAIL_ADDRESS"));
                                return new ToolDrivenExtractionExecutor.StructuredResponse(
                                        "<update-ontology>", "", List.of(
                                                new ToolDrivenExtractionExecutor.ToolRequest(
                                                        "ontology-1",
                                                        "update_ontology",
                                                        Map.of(
                                                                "nodeTypes", List.of(Map.of(
                                                                        "label", "EMAIL_ADDRESS",
                                                                        "description", "An email address identifying a graph entity")),
                                                                "relationshipTypes", List.of(Map.of(
                                                                        "type", "IDENTIFIES",
                                                                        "description", "An email address identifies a person",
                                                                        "aliases", List.of("email_for"))),
                                                                "patterns", List.of(
                                                                        "(EMAIL_ADDRESS)-[:IDENTIFIES]->(PERSON)")))),
                                        List.of());
                            }
                            assertTrue(request.tools().toString().contains("EMAIL_ADDRESS"));
                            assertTrue(request.tools().toString().contains("IDENTIFIES"));
                            assertTrue(request.messages().get(1).content()
                                    .contains("\"ontologyRevision\":2"));
                            return new ToolDrivenExtractionExecutor.StructuredResponse(
                                    "<submit-typed-relation>", "", List.of(
                                            new ToolDrivenExtractionExecutor.ToolRequest(
                                                    "submit-1",
                                                    "submit_graph_delta",
                                                    Map.of(
                                                            "entities", List.of(
                                                                    Map.of(
                                                                            "id", "email-finance",
                                                                            "name", "finance@example.com",
                                                                            "type", "EMAIL_ADDRESS"),
                                                                    Map.of(
                                                                            "id", "mira",
                                                                            "name", "Mira",
                                                                            "type", "PERSON")),
                                                            "relations", List.of(Map.of(
                                                                    "source", "email-finance",
                                                                    "target", "mira",
                                                                    "type", "IDENTIFIES"))))),
                                    List.of());
                        },
                        new DecomposedExtractionExecutor.PromptProfile(
                                DecomposedPromptTier.STANDARD,
                                8_192,
                                20_000,
                                1_024,
                                3.5,
                                "TEST"));

        assertTrue(result.usable(), () -> result.notes().toString());
        assertEquals(List.of("update_ontology", "submit_graph_delta"), result.toolsUsed());
        assertEquals(2, requests.size());
        assertEquals(2, result.extraction().entities().size());
        assertEquals("IDENTIFIES", result.extraction().relations().get(0).type());
    }

    @Test
    void populatedGraphCommandPreservesReasoningFacetsAndReturnsThemToTheNextFreshRequest() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(GraphEntity.builder("lead")
                .type("PERSON").label("Finance Lead").confidence(0.9)
                .embedding(new double[]{1.0, 0.0}).build());
        graph.addEntity(GraphEntity.builder("close")
                .type("PROCESS_STEP").label("Close Review").confidence(0.9)
                .embedding(new double[]{0.9, 0.1}).build());
        graph.addRelation(GraphRelation.builder("existing-approval", "lead", "close")
                .type("APPROVED").confidence(0.92).weight(0.92).directed(true).build());
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "graph-command-chunk",
                "graph-command-document",
                "lfm",
                "graph-command-graph",
                null,
                GraphExtractionValidationPolicy.defaults(),
                null,
                new CrawlCorpusSnapshot("graph-command-corpus", List.of()),
                null,
                () -> graph,
                new GraphReasoningQueryService(null));
        List<ToolDrivenExtractionExecutor.StructuredRequest> requests = new ArrayList<>();
        AtomicInteger round = new AtomicInteger();

        ToolDrivenExtractionExecutor.Result result =
                new ToolDrivenExtractionExecutor().extractStructured(
                        "Finance Lead participates in Close Review.",
                        null,
                        backend,
                        (passId, request) -> {
                            requests.add(request);
                            if (round.getAndIncrement() == 0) {
                                return new ToolDrivenExtractionExecutor.StructuredResponse(
                                        "<graph-call>", "", List.of(
                                                new ToolDrivenExtractionExecutor.ToolRequest(
                                                        "graph-1", "graph_reasoning_query", Map.of(
                                                                "operation", "SIMILAR",
                                                                "entityId", "lead",
                                                                "topK", 5,
                                                                "structural", "BAYESIAN"))),
                                        List.of());
                            }
                            String explicitState = request.messages().get(1).content();
                            assertTrue(explicitState.contains("Function: graph_reasoning_query"));
                            assertTrue(explicitState.contains("semanticVectorAvailable"));
                            assertTrue(explicitState.contains("BAYESIAN"),
                                    "the declared structural selector must survive schema conformance");
                            return new ToolDrivenExtractionExecutor.StructuredResponse(
                                    "<submit-call>", "", List.of(
                                            new ToolDrivenExtractionExecutor.ToolRequest(
                                                    "submit-1", "submit_graph_delta", Map.of(
                                                            "entities", List.of(),
                                                            "relations", List.of(Map.of(
                                                                    "source", "lead",
                                                                    "target", "close",
                                                                    "type", "PARTICIPATES_IN"))))),
                                    List.of());
                        },
                        new DecomposedExtractionExecutor.PromptProfile(
                                DecomposedPromptTier.STANDARD,
                                4_096,
                                8_192,
                                1_024,
                                3.5,
                                "TEST"));

        assertTrue(result.usable(), () -> result.notes().toString());
        assertEquals(List.of("graph_reasoning_query", "submit_graph_delta"), result.toolsUsed());
        assertEquals(2, requests.size());
        assertTrue(requests.get(0).messages().get(1).content().contains("\"graphState\":\"POPULATED\""));
        assertEquals("graph_reasoning_query", requests.get(0).tools().get(0).name());
        assertEquals(1, result.extraction().relations().size());
    }

    @Test
    void structuredModeReducesPresentationTierBeforeRejectingAnExecutablePrompt() {
        List<NodeType> nodeTypes = new ArrayList<>();
        List<RelationshipType> relationshipTypes = new ArrayList<>();
        List<String> patterns = new ArrayList<>();
        String detailedDefinition = "A deliberately detailed standardized schema definition "
                .repeat(8);
        for (int index = 0; index < 32; index++) {
            nodeTypes.add(new NodeType(
                    "TYPE_" + index, detailedDefinition + "entity " + index, null));
            relationshipTypes.add(new RelationshipType(
                    "REL_" + index, detailedDefinition + "relation " + index, null));
            patterns.add("(TYPE_" + index + ")-[:REL_" + index + "]->(TYPE_"
                    + ((index + 1) % 32) + ")");
        }
        GraphSchema schema = new GraphSchema(nodeTypes, relationshipTypes, patterns);
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "compact-chunk",
                "compact-document",
                "lfm",
                "compact-graph",
                null,
                GraphExtractionValidationPolicy.defaults(),
                schema,
                new CrawlCorpusSnapshot("compact-corpus", List.of()),
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null));
        List<ToolDrivenExtractionExecutor.StructuredRequest> requests = new ArrayList<>();

        ToolDrivenExtractionExecutor.Result result =
                new ToolDrivenExtractionExecutor().extractStructured(
                        "Widget Alpha is explicitly identified in this source.",
                        null,
                        backend,
                        (passId, request) -> {
                            requests.add(request);
                            return new ToolDrivenExtractionExecutor.StructuredResponse(
                                    "<native-submit>", "", List.of(
                                            new ToolDrivenExtractionExecutor.ToolRequest(
                                                    "submit-1", "submit_graph_delta", Map.of(
                                                            "entities", List.of(Map.of(
                                                                    "id", "widget-alpha",
                                                                    "name", "Widget Alpha",
                                                                    "type", "TYPE_0")),
                                                            "relations", List.of()))),
                                    List.of());
                        },
                        new DecomposedExtractionExecutor.PromptProfile(
                                DecomposedPromptTier.STANDARD,
                                2_048,
                                8_192,
                                512,
                                3.5,
                                "MODEL_CAPABILITY"));

        assertTrue(result.usable(), () -> result.notes().toString());
        assertEquals(1, requests.size(), "a fitting compact contract must reach the model");
        assertEquals(4, requests.get(0).tools().size(),
                "the unified-corpus workflow keeps graph, ontology, corpus, and submission operations executable");
        assertTrue(requests.get(0).tools().stream().anyMatch(tool ->
                CrawlExtractionToolBackend.UNIFIED_CORPUS.equals(tool.name())));
        assertTrue(requests.get(0).tools().stream().anyMatch(tool ->
                CrawlExtractionToolBackend.UPDATE_ONTOLOGY.equals(tool.name())));
        assertFalse(requests.get(0).messages().get(1).content().contains("nodeDefinitions"),
                "compact presentation should omit verbose schema definitions");
        assertTrue(result.notes().stream().anyMatch(note -> note.contains(
                        "native tool presentation reduced from STANDARD to COMPACT")),
                () -> "the short model context should deliberately select compact native tools: "
                        + result.notes());
    }

    @Test
    void shortContextRendersRejectedProposalAsFreshValidationState() {
        List<NodeType> nodeTypes = new ArrayList<>(List.of(
                new NodeType("PERSON", "A person", null),
                new NodeType("APPROVAL_ROLE", "A role that can approve a business action", null),
                new NodeType("VARIANCE_TRIAGE", "A variance triage candidate", null)));
        List<RelationshipType> relationshipTypes = new ArrayList<>(List.of(
                new RelationshipType(
                        "HAS_ROLE", "A person holds an approval role", null),
                new RelationshipType(
                        "ESCALATED_TO", "A variance is escalated to an approval role", null)));
        List<String> patterns = new ArrayList<>(List.of(
                "(PERSON)-[:HAS_ROLE]->(APPROVAL_ROLE)",
                "(VARIANCE_TRIAGE)-[:ESCALATED_TO]->(APPROVAL_ROLE)"));
        String detailedDefinition = "A deliberately detailed standardized schema definition "
                .repeat(8);
        for (int index = 0; index < 30; index++) {
            nodeTypes.add(new NodeType(
                    "TYPE_" + index, detailedDefinition + "entity " + index, null));
            relationshipTypes.add(new RelationshipType(
                    "REL_" + index, detailedDefinition + "relation " + index, null));
            patterns.add("(TYPE_" + index + ")-[:REL_" + index + "]->(TYPE_"
                    + ((index + 1) % 30) + ")");
        }
        GraphSchema schema = new GraphSchema(nodeTypes, relationshipTypes, patterns);
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "repair-chunk",
                "repair-document",
                "lfm",
                "repair-graph",
                null,
                GraphExtractionValidationPolicy.defaults(),
                schema,
                new CrawlCorpusSnapshot("repair-corpus", List.of()),
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null));
        List<ToolDrivenExtractionExecutor.StructuredRequest> requests = new ArrayList<>();
        AtomicInteger round = new AtomicInteger();

        ToolDrivenExtractionExecutor.Result result =
                new ToolDrivenExtractionExecutor().extractStructured(
                        "The ingestion Inventory identifies M. Chen as VP, FP&A and J. Park as Sr. Analyst; Controller owns escalation.",
                        null,
                        backend,
                        (passId, request) -> {
                            requests.add(request);
                            int turn = round.getAndIncrement();
                            if (turn == 0) {
                                return new ToolDrivenExtractionExecutor.StructuredResponse(
                                        "<rejected-native-submit>", "", List.of(
                                                new ToolDrivenExtractionExecutor.ToolRequest(
                                                        "submit-1", "submit_graph_delta", Map.of(
                                                                "entities", List.of(
                                                                        Map.of(
                                                                                "id", "M. Chen",
                                                                                "name", "M. Chen",
                                                                                "type", "PERSON"),
                                                                        Map.of(
                                                                                "id", "J. Park",
                                                                                "name", "J. Park",
                                                                                "type", "PERSON"),
                                                                        Map.of(
                                                                                "id", "VP, FP&A",
                                                                                "name", "VP, FP&A",
                                                                                "type", "APPROVAL_ROLE"),
                                                                        Map.of(
                                                                                "id", "Controller",
                                                                                "name", "Controller",
                                                                                "type", "APPROVAL_ROLE"),
                                                                        Map.of(
                                                                                "id", "inventory",
                                                                                "name", "Inventory",
                                                                                "type", "VARIANCE_TRIAGE")),
                                                                "relations", List.of(
                                                                        Map.of(
                                                                                "source", "M. Chen",
                                                                                "target", "VP, FP&A",
                                                                                "type", "HAS_ROLE"),
                                                                        Map.of(
                                                                                "source", "J. Park",
                                                                                "target", "VP, FP&A",
                                                                                "type", "HAS_ROLE"),
                                                                        Map.of(
                                                                                "source", "J. Park",
                                                                                "target", "Controller",
                                                                                "type", "ESCALATED_TO"),
                                                                        Map.of(
                                                                                "source", "inventory",
                                                                                "target", "VARIANCE_TRIAGE",
                                                                                "type",
                                                                                "ESCALATED_TO"))))),
                                        List.of());
                            }

                            assertEquals(List.of("system", "user"),
                                    request.messages().stream()
                                            .map(ToolDrivenExtractionExecutor.ChatMessage::role)
                                            .toList());
                            String retryPrompt = request.messages().get(1).content();
                            assertTrue(retryPrompt.contains("CURRENT EXPLICIT TOOL STATE"),
                                    "the rejected result must be explicit in a fresh request");
                            assertTrue(retryPrompt.contains("PREVIOUS SUBMISSION HAD REJECTED ITEMS"));
                            assertTrue(retryPrompt.contains(
                                    "Already retained validator-clean subset (ACCEPTED; NOT EVIDENCE"));
                            assertTrue(retryPrompt.contains("already accumulated, not a replacement draft"));
                            assertTrue(retryPrompt.contains("SOURCE recheck required"));
                            assertTrue(retryPrompt.contains("M. Chen"));
                            assertTrue(retryPrompt.contains("Controller"));
                            assertFalse(retryPrompt.contains("Previous arguments:"),
                                    "a rejected draft must never be presented as accepted history");
                            assertTrue(retryPrompt.contains("Tool or validator result"),
                                    "validator diagnostics should follow the complete repair seed");
                            assertTrue(request.messages().get(1).content()
                                            .contains("APPROVAL_ROLE"),
                                    "the unchanged original graph schema must remain available");
                            int requestChars = request.messages().stream()
                                    .mapToInt(message -> message.content().length()).sum()
                                    + request.tools().toString().length();
                            assertTrue(requestChars < 10_752,
                                    () -> "the repair request must remain executable: "
                                            + requestChars);

                            if (turn == 1) {
                                return new ToolDrivenExtractionExecutor.StructuredResponse(
                                        "<regressive-patch-submit>", "", List.of(
                                                new ToolDrivenExtractionExecutor.ToolRequest(
                                                        "submit-patch",
                                                        "submit_graph_delta",
                                                        Map.of(
                                                                "entities", List.of(),
                                                                "relations", List.of(
                                                                        Map.of(
                                                                                "source", "M. Chen",
                                                                                "target", "VP, FP&A",
                                                                                "type", "HAS_ROLE"),
                                                                        Map.of(
                                                                                "source", "J. Park",
                                                                                "target", "Sr. Analyst",
                                                                                "type", "HAS_ROLE"))))),
                                        List.of());
                            }
                            assertTrue(retryPrompt.contains("\"id\":\"M. Chen\""),
                                    "a regressive patch must not erase the prior complete seed");
                            assertTrue(retryPrompt.contains("\"id\":\"VP, FP&A\""),
                                    "prior relation endpoints must survive into the next fresh request");
                            return new ToolDrivenExtractionExecutor.StructuredResponse(
                                    "<corrected-native-submit>", "", List.of(
                                            new ToolDrivenExtractionExecutor.ToolRequest(
                                                    "submit-2", "submit_graph_delta", Map.of(
                                                            "entities", List.of(
                                                                    Map.of(
                                                                            "id", "m-chen",
                                                                            "name", "M. Chen",
                                                                            "type", "PERSON"),
                                                                    Map.of(
                                                                            "id", "vp-fpa",
                                                                            "name", "VP, FP&A",
                                                                            "type",
                                                                            "APPROVAL_ROLE")),
                                                            "relations", List.of(Map.of(
                                                                    "source", "m-chen",
                                                                    "target", "vp-fpa",
                                                                    "type", "HAS_ROLE"))))),
                                    List.of());
                        },
                        new DecomposedExtractionExecutor.PromptProfile(
                                DecomposedPromptTier.STANDARD,
                                3_072,
                                4_096,
                                512,
                                3.5,
                                "MODEL_CAPABILITY"));

        assertTrue(result.usable(), () -> result.notes().toString());
        assertEquals(3, requests.size());
        assertEquals(7, result.extraction().entities().size(),
                "the corrected submission must merge with validator-clean facts retained from the mixed delta");
        assertEquals(3, result.extraction().relations().size(),
                "the corrected submission must retain prior validator-clean relations as well");
        assertEquals(List.of(
                        "submit_graph_delta", "submit_graph_delta", "submit_graph_delta"),
                result.toolsUsed());
        assertTrue(result.notes().stream()
                .anyMatch(note -> note.contains(
                        "fresh request instead of conversation history")));
    }

    @Test
    void invalidFunctionArgumentsArePreservedAndRejectedBeforeBackendExecution() {
        GraphSchema schema = new GraphSchema(
                List.of(
                        new NodeType("PERSON", "A person", null),
                        new NodeType("ROLE", "An organizational role", null)),
                List.of(new RelationshipType(
                        "HAS_ROLE", "A person holds a role", null)),
                List.of("(PERSON)-[:HAS_ROLE]->(ROLE)"));
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "conformance-chunk",
                "conformance-document",
                "lfm",
                "conformance-graph",
                null,
                GraphExtractionValidationPolicy.defaults(),
                schema,
                new CrawlCorpusSnapshot("conformance-corpus", List.of()),
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null));
        List<ToolDrivenExtractionExecutor.StructuredRequest> requests = new ArrayList<>();
        AtomicInteger round = new AtomicInteger();

        ToolDrivenExtractionExecutor.Result result =
                new ToolDrivenExtractionExecutor().extractStructured(
                        "M. Chen is VP, FP&A.",
                        null,
                        backend,
                        (passId, request) -> {
                            requests.add(request);
                            if (round.getAndIncrement() == 0) {
                                return new ToolDrivenExtractionExecutor.StructuredResponse(
                                        "<invalid-native-call>", "", List.of(
                                                new ToolDrivenExtractionExecutor.ToolRequest(
                                                        "submit-1", "submit_graph_delta", Map.of(
                                                                "entities", List.of(
                                                                        Map.of(
                                                                                "id", "person",
                                                                                "name", "M. Chen",
                                                                                "type", "PERSON",
                                                                                "properties", Map.of(
                                                                                        "unsupported",
                                                                                        "value")),
                                                                        Map.of(
                                                                                "id", "role",
                                                                                "name", "VP, FP&A",
                                                                                "type", "ROLE")),
                                                                "relationships", List.of(Map.of(
                                                                        "source", "person",
                                                                        "target", "role",
                                                                        "type", "HAS_ROLE"))))),
                                        List.of());
                            }
                            String retry = request.messages().get(1).content();
                            assertTrue(retry.contains("$.relations is required"));
                            assertTrue(retry.contains("$.relationships is not declared"));
                            assertTrue(retry.contains("$.entities[0].properties is not declared"));
                            assertTrue(retry.contains("\"relationships\""));
                            assertTrue(retry.contains("\"HAS_ROLE\""),
                                    "the exact rejected proposal must remain available for repair");
                            assertTrue(retry.contains("\"argumentsPreserved\":true"));
                            return new ToolDrivenExtractionExecutor.StructuredResponse(
                                    "<valid-native-call>", "", List.of(
                                            new ToolDrivenExtractionExecutor.ToolRequest(
                                                    "submit-2", "submit_graph_delta", Map.of(
                                                            "entities", List.of(
                                                                    Map.of(
                                                                            "id", "person",
                                                                            "name", "M. Chen",
                                                                            "type", "PERSON"),
                                                                    Map.of(
                                                                            "id", "role",
                                                                            "name", "VP, FP&A",
                                                                            "type", "ROLE")),
                                                            "relations", List.of(Map.of(
                                                                    "source", "person",
                                                                    "target", "role",
                                                                    "type", "HAS_ROLE"))))),
                                    List.of());
                        },
                        new DecomposedExtractionExecutor.PromptProfile(
                                DecomposedPromptTier.RICH,
                                4_096,
                                8_192,
                                1_024,
                                3.5,
                                "TEST"));

        assertTrue(result.usable(), () -> result.notes().toString());
        assertEquals(2, result.extraction().entities().size());
        assertEquals(1, result.extraction().relations().size());
        assertEquals(2, result.rounds());
        assertEquals(1, result.toolCalls(),
                "the invalid call must never reach the backend");
        assertEquals(List.of("submit_graph_delta"), result.toolsUsed());
        assertEquals(2, requests.size());
        assertTrue(result.notes().stream()
                .anyMatch(note -> note.contains(
                        "without mutation or backend execution")));
    }

    @Test
    void schemaIntegerAcceptsMathematicallyIntegralJsonNumbers() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("target", Map.of("type", "integer")),
                "required", List.of("target"));

        ToolArgumentSchemaValidator.ValidationResult integralDecimal =
                ToolArgumentSchemaValidator.validate(schema, Map.of("target", 1.0d));
        ToolArgumentSchemaValidator.ValidationResult fractionalDecimal =
                ToolArgumentSchemaValidator.validate(schema, Map.of("target", 1.5d));

        assertTrue(integralDecimal.valid(), () -> integralDecimal.errors().toString());
        assertFalse(fractionalDecimal.valid());
        assertTrue(fractionalDecimal.errors().stream()
                .anyMatch(error -> error.contains("$.target must be integer")));
    }

    @Test
    void undeclaredAndEmptyRequiredCallsNeverReachTheBackend() {
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "protocol-chunk",
                "protocol-document",
                "lfm",
                "protocol-graph",
                null,
                GraphExtractionValidationPolicy.defaults(),
                null,
                new CrawlCorpusSnapshot("protocol-corpus", List.of()),
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null));
        AtomicInteger round = new AtomicInteger();

        ToolDrivenExtractionExecutor.Result result =
                new ToolDrivenExtractionExecutor().extractStructured(
                        "M. Chen leads finance.",
                        null,
                        backend,
                        (passId, request) -> switch (round.getAndIncrement()) {
                            case 0 -> new ToolDrivenExtractionExecutor.StructuredResponse(
                                    "<unknown-tool>", "", List.of(
                                            new ToolDrivenExtractionExecutor.ToolRequest(
                                                    "unknown-1", "submit_graph", Map.of(
                                                            "entities", List.of(),
                                                            "relations", List.of()))),
                                    List.of());
                            case 1 -> {
                                String retry = request.messages().get(1).content();
                                assertTrue(retry.contains("\"error\":\"undeclared_tool\""));
                                assertTrue(retry.contains("\"submit_graph\""));
                                assertTrue(retry.contains("\"submit_graph_delta\""));
                                yield new ToolDrivenExtractionExecutor.StructuredResponse(
                                        "<empty-arguments>", "", List.of(
                                                new ToolDrivenExtractionExecutor.ToolRequest(
                                                        "empty-1", "submit_graph_delta", Map.of())),
                                        List.of());
                            }
                            default -> {
                                String retry = request.messages().get(1).content();
                                assertTrue(retry.contains(
                                        "\"error\":\"invalid_tool_arguments\""));
                                assertTrue(retry.contains("$.entities is required"));
                                assertTrue(retry.contains("$.relations is required"));
                                yield new ToolDrivenExtractionExecutor.StructuredResponse(
                                        "<valid-submit>", "", List.of(
                                                new ToolDrivenExtractionExecutor.ToolRequest(
                                                        "submit-1", "submit_graph_delta", Map.of(
                                                                "entities", List.of(Map.of(
                                                                        "id", "m-chen",
                                                                        "name", "M. Chen",
                                                                        "type", "PERSON")),
                                                                "relations", List.of()))),
                                        List.of());
                            }
                        },
                        new DecomposedExtractionExecutor.PromptProfile(
                                DecomposedPromptTier.STANDARD,
                                4_096,
                                8_192,
                                1_024,
                                3.5,
                                "TEST"));

        assertTrue(result.usable(), () -> result.notes().toString());
        assertEquals(3, result.rounds());
        assertEquals(1, result.toolCalls());
        assertEquals(List.of("submit_graph_delta"), result.toolsUsed());
        assertTrue(result.notes().stream()
                .anyMatch(note -> note.contains("rejected undeclared function")));
        assertTrue(result.notes().stream()
                .anyMatch(note -> note.contains("$.entities is required")));
    }

    @Test
    void rejectedNativeSubmissionCanRecoverWithoutReinforcingProse() {
        CrawlExtractionToolBackend backend = new CrawlExtractionToolBackend(
                "retry-chunk",
                "retry-document",
                "lfm",
                "retry-graph",
                null,
                GraphExtractionValidationPolicy.builder()
                        .enabledValidators(List.of(
                                GraphExtractionValidationPolicy.REQUIRED_DESCRIPTIONS))
                        .build(),
                null,
                new CrawlCorpusSnapshot("retry-corpus", List.of()),
                null,
                UnifiedGraph::new,
                new GraphReasoningQueryService(null));
        List<ToolDrivenExtractionExecutor.StructuredRequest> requests = new ArrayList<>();
        AtomicInteger round = new AtomicInteger();

        ToolDrivenExtractionExecutor.Result result =
                new ToolDrivenExtractionExecutor().extractStructured(
                        "Mira reviewed Project Orchid.",
                        null,
                        backend,
                        (passId, request) -> {
                            requests.add(request);
                            Map<String, Object> entityMira = Map.of(
                                    "id", "mira",
                                    "name", "Mira",
                                    "type", "PERSON",
                                    "description", "The reviewer");
                            Map<String, Object> entityOrchid = Map.of(
                                    "id", "orchid",
                                    "name", "Project Orchid",
                                    "type", "PROJECT",
                                    "description", "The reviewed project");
                            return switch (round.getAndIncrement()) {
                                case 0 -> new ToolDrivenExtractionExecutor.StructuredResponse(
                                        "<submit-invalid-endpoint>", "", List.of(
                                                new ToolDrivenExtractionExecutor.ToolRequest(
                                                        "submit-1", "submit_graph_delta", Map.of(
                                                                "entities", List.of(
                                                                        entityMira, entityOrchid),
                                                                "relations", List.of(Map.of(
                                                                        "source", "mira",
                                                                        "target", "orchid-unknown",
                                                                        "type", "REVIEWED",
                                                                        "description",
                                                                        "Mira reviewed Project Orchid"))))),
                                        List.of());
                                case 1 -> new ToolDrivenExtractionExecutor.StructuredResponse(
                                        "I will fix it now.", "I will fix it now.",
                                        List.of(), List.of());
                                default -> new ToolDrivenExtractionExecutor.StructuredResponse(
                                        "<submit-corrected>", "", List.of(
                                                new ToolDrivenExtractionExecutor.ToolRequest(
                                                        "submit-2", "submit_graph_delta", Map.of(
                                                                "entities", List.of(
                                                                        entityMira, entityOrchid),
                                                                "relations", List.of(Map.of(
                                                                        "source", "mira",
                                                                        "target", "orchid",
                                                                        "type", "REVIEWED",
                                                                        "description",
                                                                        "Mira reviewed Project Orchid"))))),
                                        List.of());
                            };
                        },
                        new DecomposedExtractionExecutor.PromptProfile(
                                DecomposedPromptTier.STANDARD,
                                4_096,
                                8_192,
                                1_024,
                                3.5,
                                "TEST"));

        assertTrue(result.usable(), () -> result.notes().toString());
        assertEquals(List.of("submit_graph_delta", "submit_graph_delta"),
                result.toolsUsed());
        assertEquals(3, requests.size());
        assertTrue(requests.stream().allMatch(request -> request.messages().stream()
                        .map(ToolDrivenExtractionExecutor.ChatMessage::role).toList()
                        .equals(List.of("system", "user"))),
                "validation retries must be fresh requests, not replayed chat turns");
        String validationState = requests.get(1).messages().get(1).content();
        assertTrue(validationState.contains("CURRENT EXPLICIT TOOL STATE"));
        assertTrue(validationState.contains("PREVIOUS SUBMISSION HAD REJECTED ITEMS"));
        assertTrue(validationState.contains(
                "Already retained validator-clean subset (ACCEPTED; NOT EVIDENCE"));
        assertTrue(validationState.contains("repairSeedIsEvidence"));
        assertTrue(validationState.contains("SOURCE"));
        assertTrue(validationState.contains("Mira"));
        assertTrue(validationState.contains("Project Orchid"));
        assertTrue(validationState.contains("Repair or omit only rejected"));
        assertFalse(validationState.contains("Previous arguments:"),
                "already retained state must not be presented as a previous unaccepted draft");
        assertFalse(validationState.contains("Rejected candidate draft"),
                "actionable clean state must take priority over the invalid original draft");
        assertTrue(validationState.contains("Tool or validator result"));
        assertTrue(validationState.contains("description"));
        String protocolRetry = requests.get(2).messages().get(1).content();
        assertTrue(protocolRetry.contains(
                "No executable native function call was returned"));
        assertTrue(protocolRetry.contains("Tool or validator result"),
                "a malformed repair response must not erase the prior validator state");
        assertTrue(protocolRetry.contains(
                        "Already retained validator-clean subset (ACCEPTED; NOT EVIDENCE"),
                "a malformed repair response must not erase the retained subset");
        assertTrue(protocolRetry.contains("Mira"));
        assertTrue(protocolRetry.contains("description"),
                "the actionable validation error must survive a protocol-only response");
        assertTrue(result.notes().stream()
                .anyMatch(note -> note.contains(
                        "fresh request instead of conversation history")));
    }

    @Test
    void stringRetryExhaustionReturnsRetainedValidatorCleanFacts() {
        CrawlExtractionToolBackend backend = retryBackend("string-exhaustion");
        AtomicInteger round = new AtomicInteger();

        ToolDrivenExtractionExecutor.Result result = new ToolDrivenExtractionExecutor().extract(
                "Mira reviewed Project Orchid.", null, backend,
                (passId, prompt) -> round.getAndIncrement() == 0 ? """
                        {"tool":"submit_graph_delta","args":{"entities":[
                          {"id":"mira","name":"Mira","type":"PERSON"}
                        ],"relations":[
                          {"source":"mira","target":"missing","type":"REVIEWED"}
                        ]}}
                        """ : "no executable tool call",
                testProfile());

        assertTrue(result.usable(), () -> result.notes().toString());
        assertEquals(3, round.get(),
                "two identical protocol rejections must stop the repair loop");
        assertEquals(1, result.extraction().entities().size());
        assertEquals(0, result.extraction().relations().size());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains(
                "retry rounds ended after retaining validator-clean facts")));
    }

    @Test
    void structuredRetryExhaustionReturnsRetainedValidatorCleanFacts() {
        CrawlExtractionToolBackend backend = retryBackend("structured-exhaustion");
        AtomicInteger round = new AtomicInteger();

        ToolDrivenExtractionExecutor.Result result = new ToolDrivenExtractionExecutor().extractStructured(
                "Mira reviewed Project Orchid.", null, backend,
                (passId, request) -> round.getAndIncrement() == 0
                        ? new ToolDrivenExtractionExecutor.StructuredResponse("", "", List.of(
                                new ToolDrivenExtractionExecutor.ToolRequest("submit-1",
                                        "submit_graph_delta", Map.of(
                                                "entities", List.of(Map.of(
                                                        "id", "mira", "name", "Mira", "type", "PERSON")),
                                                "relations", List.of(Map.of(
                                                        "source", "mira", "target", "missing", "type", "REVIEWED"))))),
                                List.of())
                        : new ToolDrivenExtractionExecutor.StructuredResponse(
                                "no call", "no call", List.of(), List.of()),
                testProfile());

        assertTrue(result.usable(), () -> result.notes().toString());
        assertEquals(3, round.get(),
                "two identical native protocol rejections must stop the repair loop");
        assertEquals(1, result.extraction().entities().size());
        assertEquals(0, result.extraction().relations().size());
        assertTrue(result.notes().stream().anyMatch(note -> note.contains(
                "native retry rounds ended after retaining validator-clean facts")));
    }

    private static CrawlExtractionToolBackend retryBackend(String chunkId) {
        return new CrawlExtractionToolBackend(chunkId, "document", "lfm", "graph", null,
                GraphExtractionValidationPolicy.defaults(), null,
                new CrawlCorpusSnapshot("corpus", List.of()), null,
                UnifiedGraph::new, new GraphReasoningQueryService(null));
    }

    private static DecomposedExtractionExecutor.PromptProfile testProfile() {
        return new DecomposedExtractionExecutor.PromptProfile(
                DecomposedPromptTier.STANDARD, 4_096, 8_192, 1_024, 3.5, "TEST");
    }
}
