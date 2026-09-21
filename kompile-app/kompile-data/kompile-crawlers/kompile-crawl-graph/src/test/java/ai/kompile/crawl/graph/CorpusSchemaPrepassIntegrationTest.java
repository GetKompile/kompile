package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.ExtractionMode;
import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.SchemaHierarchyVocabulary;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusPassage;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusSnapshot;
import ai.kompile.knowledgegraph.service.ConceptExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorpusSchemaPrepassIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void productionPrepassCombinesSeedsExtractorsAndModelBeforeExtraction() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        orchestrator.corpusSchemaUnifier = new CorpusSchemaUnifier();
        orchestrator.conceptExtractor = mock(ConceptExtractor.class);
        orchestrator.llmDispatcher = mock(CrawlLlmDispatcher.class);

        when(orchestrator.conceptExtractor.extractConceptsFromPassages(anyMap(), any()))
                .thenReturn(Map.of("chunk-1", new ConceptExtractor.ExtractionResult(
                        List.of(new ConceptExtractor.ExtractedConcept(
                                "Approver", "approver", "APPROVAL_ROLE",
                                0.96d, 2, "Approver reviewed the release.")),
                        List.of(),
                        Map.of())));
        when(orchestrator.llmDispatcher.hasStructuredChatBackend()).thenReturn(true);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("prepass-integration");
        stubTypePasses(
                orchestrator.llmDispatcher, job,
                nodeResponse(List.of("FORECAST")),
                relationshipResponse(List.of()));
        GraphExtractionConfig config = GraphExtractionConfig.builder()
                .extractionMode(ExtractionMode.DECOMPOSED)
                .decomposedPassStrategy(
                        GraphExtractionConfig.DecomposedPassStrategy.ENTITIES_THEN_RELATIONS)
                .schemaMode(SchemaEnforcementMode.LENIENT)
                .standardizedSchema(new GraphSchema(
                        List.of(new NodeType(
                                "SEEDED_DOCUMENT", "A configured document type.", null)),
                        null,
                        null))
                .build();
        CrawlCorpusSnapshot corpus = new CrawlCorpusSnapshot(
                "snapshot-integration",
                List.of(new CrawlCorpusPassage(
                        "chunk-1", 0,
                        "Approver reviewed a release message about the monthly forecast.",
                        "hash-1", Map.of(), true)));
        Entity message = entity("message", "EXTRACTOR_MESSAGE");
        Entity actor = entity("actor", "DETERMINISTIC_ACTOR");
        Relationship emittedBy = new Relationship();
        emittedBy.setSource("message");
        emittedBy.setTarget("actor");
        emittedBy.setType("EMITTED_BY");
        Graph deterministicGraph = Graph.builder()
                .entities(new ArrayList<>(List.of(message, actor)))
                .relationships(new ArrayList<>(List.of(emittedBy)))
                .build();
        GraphSchema schema = orchestrator.deriveCorpusSchema(
                job, corpus, config, deterministicGraph);

        assertTrue(schema.getAllNodeLabels().containsAll(List.of(
                "SEEDED_DOCUMENT",
                "EXTRACTOR_MESSAGE",
                "DETERMINISTIC_ACTOR",
                "APPROVAL_ROLE",
                "FORECAST")));
        assertTrue(schema.getAllRelationshipTypes().contains("EMITTED_BY"));
        assertTrue(schema.getPatterns().contains(
                "(EXTRACTOR_MESSAGE)-[:EMITTED_BY]->(DETERMINISTIC_ACTOR)"));

        ArgumentCaptor<StructuredChatLanguageModel.Request> request =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        ArgumentCaptor<CrawlLlmDispatcher.LlmCallScope> scope =
                ArgumentCaptor.forClass(CrawlLlmDispatcher.LlmCallScope.class);
        // This test's corpus window does not state a consolidation-declared predicate in
        // stable wording, so witness discovery legitimately abstains (valid empty) and no
        // consolidation call follows: 4 calls total.
        verify(orchestrator.llmDispatcher, times(4)).promptStructuredWithCapacityFallback(
                request.capture(), eq("llm"), same(job), scope.capture());
        assertEquals(List.of(
                        CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                        CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                        CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                        CorpusSchemaUnifier.WITNESS_TOOL_NAME),
                request.getAllValues().stream()
                        .map(value -> value.tools().get(0).name()).toList());
        assertTrue(scope.getAllValues().stream().allMatch(
                value -> "SCHEMA_PREPASS".equals(value.phase())));
        assertEquals(List.of(
                        "entity-classifications-1", "node-types-1", "node-types-consolidation",
                        "relationship-witness-discovery-1"),
                scope.getAllValues().stream().map(
                        CrawlLlmDispatcher.LlmCallScope::passId).toList());
        assertTrue(request.getAllValues().stream().allMatch(value -> {
            String prompt = value.messages().get(1).content();
            return value.tools().get(0).name().equals(CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME)
                    || (prompt.contains("SEEDED_DOCUMENT")
                    && prompt.contains("EXTRACTOR_MESSAGE")
                    && prompt.contains("APPROVAL_ROLE"));
        }));
        assertTrue(request.getAllValues().get(1).messages().get(1).content().contains(
                "monthly forecast"));
        assertTrue(request.getAllValues().get(3).messages().get(1).content().contains(
                "monthly forecast"));
        assertTrue(request.getAllValues().get(2).messages().get(1).content().contains(
                "FORECAST"));
    }

    @Test
    void nativeValidatedJsonContentFeedsSchemaProposalsWithoutSyntheticToolCalls() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        orchestrator.corpusSchemaUnifier = new CorpusSchemaUnifier();
        orchestrator.llmDispatcher = mock(CrawlLlmDispatcher.class);
        when(orchestrator.llmDispatcher.hasStructuredChatBackend()).thenReturn(true);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("prepass-native-json");
        when(orchestrator.llmDispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class))).thenAnswer(invocation -> {
            StructuredChatLanguageModel.Request request = invocation.getArgument(0);
            String tool = request.tools().get(0).name();
            if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(tool)) {
                return new StructuredChatLanguageModel.Response(
                        "<native-tool-call>", "",
                        List.of(new StructuredChatLanguageModel.ToolCall(
                                "schema-call",
                                CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                                Map.of("classifications", List.of()))),
                        List.of());
            }
            String json = CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)
                    ? MAPPER.writeValueAsString(CorpusSchemaUnifierTest.withDiscoveryEvidence(
                            request, nodeResponse(List.of("FORECAST"))).toolCalls().get(0).arguments())
                    : "{\"relationshipTypes\":[]}";
            return new StructuredChatLanguageModel.Response(json, json, List.of(), List.of());
        });

        GraphSchema schema = orchestrator.deriveCorpusSchema(
                job,
                new CrawlCorpusSnapshot("native-json",
                        List.of(new CrawlCorpusPassage("chunk", 0,
                                "The monthly forecast was reviewed.", "hash", Map.of(), true))),
                GraphExtractionConfig.builder().schemaMode(SchemaEnforcementMode.LENIENT).build(),
                Graph.builder().build());

        assertTrue(schema.getAllNodeLabels().contains("FORECAST"));
        verify(orchestrator.llmDispatcher, atLeast(2)).promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
    }

    @Test
    void productionPrepassUsesTypeOnlyToolAndRejectsIncidentalAssertionVocabulary() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        orchestrator.corpusSchemaUnifier = new CorpusSchemaUnifier();
        orchestrator.llmDispatcher = mock(CrawlLlmDispatcher.class);
        when(orchestrator.llmDispatcher.hasStructuredChatBackend()).thenReturn(true);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("prepass-type-only");
        stubTypePasses(
                orchestrator.llmDispatcher, job,
                nodeResponse(List.of()),
                relationshipResponse(List.of("WORKS_FOR")));

        CrawlCorpusSnapshot corpus = new CrawlCorpusSnapshot(
                "snapshot-type-only",
                List.of(new CrawlCorpusPassage(
                        "incidental-1", 0,
                        "Slack DMs are the actual channel. SKUs are AU-001. "
                                + "KAA is a one-shot consulting deliverable. "
                                + "The platform thesis: customer-N is faster than customer-(N-1). "
                                + "The SKU master is on our shared drive. "
                                + "Mei Chen works for Northstar Goods.",
                        "hash-incidental", Map.of(), true)));

        GraphSchema schema = orchestrator.deriveCorpusSchema(
                job,
                corpus,
                GraphExtractionConfig.builder()
                        .schemaMode(SchemaEnforcementMode.LENIENT)
                        .build(),
                Graph.builder().build());

        assertTrue(schema.getAllNodeLabels().containsAll(
                SchemaHierarchyVocabulary.BASE_ENTITY_TYPES));
        assertEquals(java.util.Set.of("WORKS_FOR"), schema.getAllRelationshipTypes());
        assertTrue(schema.getPatterns() != null && !schema.getPatterns().isEmpty());
        assertFalse(schema.getAllNodeLabels().stream().anyMatch(List.of(
                "ACTUAL", "AU_001", "ONE_SHOT", "FASTER", "ON")::contains));
        assertFalse(schema.getAllRelationshipTypes().stream().anyMatch(List.of(
                "EW", "EXECUTES_FOR")::contains));

        ArgumentCaptor<StructuredChatLanguageModel.Request> request =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(orchestrator.llmDispatcher, times(5)).promptStructuredWithCapacityFallback(
                request.capture(), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        // Witness pipeline: discovery (witnesses) then evidence-backed consolidation (types);
        // the legacy second design attempt and its repair are gone.
        assertEquals(List.of(
                        CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                        CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                        CorpusSchemaUnifier.WITNESS_TOOL_NAME,
                        CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                        CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME),
                request.getAllValues().stream()
                        .map(value -> value.tools().get(0).name()).toList());
        // The discovery prompt asks for observations, never for label design; the consolidation
        // prompt carries the checked witness observations.
        assertTrue(request.getAllValues().get(2).messages().get(0).content()
                .contains("relationship observations"));
        assertTrue(request.getAllValues().get(3).messages().get(1).content()
                .contains("CHECKED RELATIONSHIP OBSERVATIONS"));
        // Untrusted-data guards: node/witness prompts and consolidation prompts each carry an
        // explicit treat-data-as-data instruction (witness prompts mark the corpus JSON as
        // untrusted; consolidation marks the observations as inputs, not admitted facts).
        assertTrue(request.getAllValues().stream()
                .filter(value -> !CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME.equals(
                        value.tools().get(0).name()))
                .filter(value -> !CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(
                        value.tools().get(0).name()))
                .allMatch(value -> {
                    String system = value.messages().get(0).content();
                    String user = value.messages().get(1).content();
                    return system.contains("Do not extract entities or relations")
                            || system.contains("List source-supported relationship observations")
                            || system.contains("Consolidate checked relationship observations")
                            || user.contains("UNTRUSTED CORPUS DATA")
                            || user.contains("provenance verified by the host");
                }));
    }

    @Test
    void failedModelOntologyPathsDoNotReturnConfiguredOrDeterministicSchema() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        orchestrator.corpusSchemaUnifier = new CorpusSchemaUnifier();
        orchestrator.llmDispatcher = mock(CrawlLlmDispatcher.class);
        when(orchestrator.llmDispatcher.hasStructuredChatBackend()).thenReturn(true);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("prepass-fail-loudly");
        when(orchestrator.llmDispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"),
                same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(null);

        CrawlCorpusSnapshot corpus = new CrawlCorpusSnapshot(
                "snapshot-fail-loudly",
                List.of(new CrawlCorpusPassage(
                        "founding-1", 0,
                        "Jordan Lee is a person. Helios Dynamics is a company. "
                                + "Jordan Lee founded Helios Dynamics.",
                        "hash-founding", Map.of(), true)));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> orchestrator.deriveCorpusSchema(
                        job,
                        corpus,
                        GraphExtractionConfig.builder()
                                .schemaMode(SchemaEnforcementMode.LENIENT)
                                .standardizedSchema(new GraphSchema(
                                        List.of(new NodeType(
                                                "SEEDED_DOCUMENT",
                                                "A configured seed that must not mask model failure.",
                                                null)),
                                        null,
                                        null))
                                .build(),
                        Graph.builder().build()));

        assertTrue(failure.getMessage().contains("Structured model response was null"));
    }

    @Test
    void conceptCandidateExtractionFailureStopsTheOntologyPrepass() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        orchestrator.corpusSchemaUnifier = new CorpusSchemaUnifier();
        orchestrator.conceptExtractor = mock(ConceptExtractor.class);
        orchestrator.llmDispatcher = mock(CrawlLlmDispatcher.class);
        when(orchestrator.llmDispatcher.hasStructuredChatBackend()).thenReturn(true);
        when(orchestrator.conceptExtractor.extractConceptsFromPassages(anyMap(), any()))
                .thenThrow(new IllegalStateException("concept prepass unavailable"));
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("prepass-concept-failure");
        CrawlCorpusSnapshot corpus = new CrawlCorpusSnapshot(
                "snapshot-concept-failure",
                List.of(new CrawlCorpusPassage(
                        "chunk-1", 0, "Jordan Lee founded Helios Dynamics.",
                        "hash-1", Map.of(), true)));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> orchestrator.deriveCorpusSchema(
                        job,
                        corpus,
                        GraphExtractionConfig.builder()
                                .schemaMode(SchemaEnforcementMode.LENIENT)
                                .build(),
                        Graph.builder().build()));

        assertTrue(failure.getMessage().contains("concept prepass unavailable"));
    }

    @Test
    void productionPrepassDoesNotTruncateLongCorpusPassages() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        orchestrator.corpusSchemaUnifier = new CorpusSchemaUnifier();
        orchestrator.llmDispatcher = mock(CrawlLlmDispatcher.class);
        when(orchestrator.llmDispatcher.hasStructuredChatBackend()).thenReturn(true);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("prepass-full-passage");
        stubTypePasses(
                orchestrator.llmDispatcher, job,
                nodeResponse(List.of()), relationshipResponse(List.of()));

        String tailMarker = "FULL_CORPUS_TAIL_MARKER";
        String longPassage = "x".repeat(13_500) + tailMarker;
        CrawlCorpusSnapshot corpus = new CrawlCorpusSnapshot(
                "snapshot-full-passage",
                List.of(new CrawlCorpusPassage(
                        "long-chunk", 0, longPassage, "hash-long", Map.of(), true)));

        GraphSchema schema = orchestrator.deriveCorpusSchema(
                job,
                corpus,
                GraphExtractionConfig.builder()
                        .schemaMode(SchemaEnforcementMode.LENIENT)
                        .build(),
                Graph.builder().build());

        assertNotNull(schema);
        assertTrue(schema.getAllNodeLabels().containsAll(
                SchemaHierarchyVocabulary.BASE_ENTITY_TYPES));
        assertTrue(schema.getAllRelationshipTypes().isEmpty());
        ArgumentCaptor<StructuredChatLanguageModel.Request> requests =
                ArgumentCaptor.forClass(StructuredChatLanguageModel.Request.class);
        verify(orchestrator.llmDispatcher, atLeast(2)).promptStructuredWithCapacityFallback(
                requests.capture(), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        String modelPrompts = requests.getAllValues().stream()
                .map(request -> request.messages().get(1).content())
                .reduce("", String::concat);
        assertTrue(modelPrompts.contains(tailMarker),
                "corpus schema pre-pass truncated the tail of a long passage");
    }

    @Test
    void duplicateChunkIdsRetainAllTextWithPermutationStableKeys() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        CrawlCorpusPassage first = new CrawlCorpusPassage(
                "duplicate", 0, "first distinct passage", "ignored-a", Map.of(), true);
        CrawlCorpusPassage second = new CrawlCorpusPassage(
                "duplicate", 0, "second distinct passage", "ignored-b", Map.of(), true);

        Map<String, String> forward = orchestrator.collisionSafePassageTexts(List.of(first, second));
        Map<String, String> reverse = orchestrator.collisionSafePassageTexts(List.of(second, first));

        assertEquals(2, forward.size());
        assertEquals(forward, reverse);
        assertTrue(forward.containsKey("duplicate"));
        assertTrue(forward.keySet().stream().anyMatch(key -> key.startsWith("duplicate#")));
        assertTrue(forward.values().containsAll(List.of(
                "first distinct passage", "second distinct passage")));
    }

    @Test
    void schemaPrepassLeavesCallerConfigurationSchemaUntouched() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        GraphSchema callerSchema = new GraphSchema(
                List.of(new NodeType("CALLER_TYPE", "caller seed", null, null)),
                List.of(), List.of("(CALLER_TYPE)-[:REFERENCES]->(CALLER_TYPE)"));
        GraphExtractionConfig config = GraphExtractionConfig.builder()
                .schemaMode(SchemaEnforcementMode.LENIENT)
                .standardizedSchema(callerSchema)
                .build();
        CrawlCorpusSnapshot corpus = new CrawlCorpusSnapshot(
                "snapshot-config-unchanged",
                List.of(new CrawlCorpusPassage(
                        "chunk-config", 0, "Caller seed remains input.",
                        "hash-config", Map.of(), true)));

        orchestrator.deriveCorpusSchema(
                mock(UnifiedCrawlJob.class), corpus, config, Graph.builder().build());

        assertSame(callerSchema, config.getStandardizedSchema());
        assertEquals("CALLER_TYPE", config.getStandardizedSchema().getNodeTypes().get(0).getLabel());
        assertEquals(List.of("(CALLER_TYPE)-[:REFERENCES]->(CALLER_TYPE)"),
                config.getStandardizedSchema().getPatterns());
    }

    private static StructuredChatLanguageModel.Response nodeResponse(List<String> labels) {
        List<Map<String, Object>> definitions = labels.stream()
                .filter(label -> !SchemaHierarchyVocabulary.isBaseEntityType(label))
                .map(label -> Map.<String, Object>of(
                        "label", label,
                        "parentType", "CONCEPT"))
                .toList();
        return typeResponse(
                CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                Map.of("nodeTypes", definitions));
    }

    private static StructuredChatLanguageModel.Response relationshipResponse(List<String> labels) {
        List<Map<String, Object>> definitions = labels.stream()
                .map(label -> Map.<String, Object>of(
                        "type", label,
                        "connectionFamily", "WORKS_FOR".equals(label)
                                ? "AFFILIATION" : "REFERENCE"))
                .toList();
        return typeResponse(
                CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                Map.of("relationshipTypes", definitions));
    }

    private static StructuredChatLanguageModel.Response typeResponse(
            String toolName, Map<String, Object> arguments) {
        return new StructuredChatLanguageModel.Response(
                "<native-tool-call>",
                "",
                List.of(new StructuredChatLanguageModel.ToolCall(
                        "schema-call", toolName, arguments)),
                List.of());
    }

    /**
     * Derives one grounded witness row per declared consolidation row: the quote is the first
     * actual window sentence containing the row's predicate wording (host anchoring requires
     * an exact window substring), with subject/object lifted from that sentence around the
     * predicate. Rows whose wording no window states produce nothing — the model cannot
     * witness what its windows do not say.
     */
    private static List<Map<String, Object>> witnessesFor(
            StructuredChatLanguageModel.Response relationshipResponse, String windowJson) {
        if (relationshipResponse == null || relationshipResponse.toolCalls().isEmpty()
                || windowJson.isBlank()) {
            return List.of();
        }
        Object raw = relationshipResponse.toolCalls().get(0).arguments().get("relationshipTypes");
        if (!(raw instanceof List<?> rows)) {
            return List.of();
        }
        List<Map<String, Object>> witnesses = new java.util.ArrayList<>();
        for (Object value : rows) {
            if (!(value instanceof Map<?, ?> row)) continue;
            String label = String.valueOf(row.get("type")).toLowerCase(java.util.Locale.ROOT)
                    .replace('_', ' ').trim();
            // Find the predicate wording directly in the raw window JSON block (ASCII corpus
            // text passes through JSON unescaped) and lift the anchor sentence around it.
            String lower = windowJson.toLowerCase(java.util.Locale.ROOT);
            int at = lower.indexOf(label);
            if (at <= 0) continue;
            int start = Math.max(windowJson.lastIndexOf(".", at), windowJson.lastIndexOf("[", at));
            int end = windowJson.indexOf(".", at + label.length());
            if (end < 0) continue;
            String quote = windowJson.substring(start + 1, end + 1).trim()
                    .replaceFirst("^[^\"]*\"", "");
            String suffix = "";
            if (!quote.contains(label)) {
                // Quote anchoring failed (escaping or boundary); retry on the raw text.
                quote = windowJson.substring(Math.max(0, at - 40),
                        Math.min(windowJson.length(), at + label.length() + 40));
                suffix = "";
            }
            String subject = quote.substring(0, quote.toLowerCase(java.util.Locale.ROOT)
                    .indexOf(label)).trim();
            String object = quote.substring(quote.toLowerCase(java.util.Locale.ROOT)
                    .indexOf(label) + label.length()).trim();
            witnesses.add(Map.of(
                    "sourceId", "s1",
                    "subject", subject,
                    "predicateText", label,
                    "object", object,
                    "quote", quote,
                    "qualifier", suffix));
        }
        return witnesses;
    }

    /** Returns the UNTRUSTED_CORPUS_PASSAGES_JSON block from a discovery prompt. */
    private static String windowJson(StructuredChatLanguageModel.Request request) {
        String prompt = request.messages().get(request.messages().size() - 1).content();
        int start = prompt.indexOf("UNTRUSTED_CORPUS_PASSAGES_JSON=");
        return start < 0 ? "" : prompt.substring(start);
    }

    /** Extracts checked witness rows from a consolidation prompt. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> checkedWitnesses(String prompt) {
        int start = prompt.indexOf("CHECKED RELATIONSHIP OBSERVATIONS");
        if (start < 0) return List.of();
        String block = prompt.substring(start);
        int from = block.indexOf('[');
        int to = block.indexOf("\n\n");
        try {
            return (List<Map<String, Object>>) (List<?>) MAPPER.readValue(
                    block.substring(from, to < 0 ? block.length() : to).trim(), List.class);
        } catch (java.io.IOException failure) {
            throw new AssertionError("Unable to parse checked witnesses", failure);
        }
    }

    /** Adds resolved witnessIds to each declared consolidation row (first matching witness). */
    private static List<Map<String, Object>> citeWitnesses(
            StructuredChatLanguageModel.Response relationshipResponse,
            List<Map<String, Object>> checked) {
        Object raw = relationshipResponse.toolCalls().get(0).arguments().get("relationshipTypes");
        if (!(raw instanceof List<?> rows)) return List.of();
        List<Map<String, Object>> cited = new java.util.ArrayList<>();
        for (Object value : rows) {
            if (!(value instanceof Map<?, ?> row)) continue;
            String label = String.valueOf(row.get("type"));
            List<String> ids = checked.stream()
                    .filter(witness -> String.valueOf(witness.get("predicateText"))
                            .equalsIgnoreCase(label.toLowerCase(java.util.Locale.ROOT)
                                    .replace('_', ' ').trim()))
                    .map(witness -> String.valueOf(witness.get("witnessId")))
                    .toList();
            if (ids.isEmpty()) continue;
            cited.add(Map.of(
                    "type", label,
                    "connectionFamily", String.valueOf(row.get("connectionFamily")),
                    "witnessIds", ids));
        }
        return cited;
    }

    private static void stubTypePasses(
            CrawlLlmDispatcher dispatcher,
            UnifiedCrawlJob job,
            StructuredChatLanguageModel.Response nodeResponse,
            StructuredChatLanguageModel.Response relationshipResponse) {
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class),
                eq("llm"), same(job), any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenAnswer(invocation -> {
                    StructuredChatLanguageModel.Request request = invocation.getArgument(0);
                    String tool = request.tools().get(0).name();
                    if (CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME.equals(tool)) {
                        return CorpusSchemaUnifierTest.withDiscoveryEvidence(request, nodeResponse);
                    }
                    if (CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME.equals(tool)) {
                        return new StructuredChatLanguageModel.Response(
                                "<native-tool-call>", "",
                                List.of(new StructuredChatLanguageModel.ToolCall(
                                        "schema-call",
                                        CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                                        Map.of("classifications", List.of()))),
                                List.of());
                    }
                    if (CorpusSchemaUnifier.WITNESS_TOOL_NAME.equals(tool)) {
                        // Witness discovery precedes consolidation. When the test declares a
                        // relationship response, emit one witness anchored in the actual window
                        // sentence per consolidation row; otherwise abstain (valid empty).
                        List<Map<String, Object>> declared =
                                witnessesFor(relationshipResponse, windowJson(request));
                        return new StructuredChatLanguageModel.Response(
                                "<native-tool-call>", "",
                                List.of(new StructuredChatLanguageModel.ToolCall(
                                        "schema-call",
                                        CorpusSchemaUnifier.WITNESS_TOOL_NAME,
                                        Map.of("witnesses", declared))),
                                List.of());
                    }
                    if (CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME.equals(tool)) {
                        // Cite the checked witnesses for each declared predicate row so the
                        // host citation validation passes (rows lack witnessIds otherwise).
                        List<Map<String, Object>> checked =
                                checkedWitnesses(request.messages().get(1).content());
                        return new StructuredChatLanguageModel.Response(
                                "<native-tool-call>", "",
                                List.of(new StructuredChatLanguageModel.ToolCall(
                                        "schema-call",
                                        CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                                        Map.of("relationshipTypes",
                                                citeWitnesses(relationshipResponse, checked)))),
                                List.of());
                    }
                    if (CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME.equals(tool)) {
                        return endpointSignatureResponse(request);
                    }
                    throw new AssertionError("Unexpected schema type tool: " + tool);
                });
    }

    @SuppressWarnings("unchecked")
    private static StructuredChatLanguageModel.Response endpointSignatureResponse(
            StructuredChatLanguageModel.Request request) {
        String prompt = request.messages().get(request.messages().size() - 1).content();
        int start = prompt.lastIndexOf("BINDING_OPTION_IDS_JSON=");
        try {
            Map<String, Object> root = MAPPER.readValue(
                    prompt.substring(start + "BINDING_OPTION_IDS_JSON=".length()).trim(), Map.class);
            List<?> relationships = (List<?>) root.get("relationshipIds");
            List<?> endpoints = (List<?>) root.get("endpointIds");
            List<?> evidence = (List<?>) root.get("evidenceIds");
            if (relationships == null || relationships.isEmpty()
                    || endpoints == null || endpoints.isEmpty()
                    || evidence == null || evidence.isEmpty()) {
                return typeResponse(CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME,
                        Map.of("s", "0"));
            }
            int endpointTarget = endpoints.size() > 1 ? 2 : 1;
            String packed = java.util.stream.IntStream.rangeClosed(
                            1, Math.min(4, relationships.size()))
                    .mapToObj(index -> index + "|1|" + endpointTarget + "|1")
                    .collect(java.util.stream.Collectors.joining(";"));
            return typeResponse(CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME,
                    Map.of("s", packed));
        } catch (java.io.IOException failure) {
            throw new AssertionError("Unable to parse endpoint signature options", failure);
        }
    }

    private static Entity entity(String id, String type) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setType(type);
        return entity;
    }
}
