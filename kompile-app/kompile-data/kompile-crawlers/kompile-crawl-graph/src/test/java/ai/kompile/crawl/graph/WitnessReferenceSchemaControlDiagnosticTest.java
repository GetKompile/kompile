package ai.kompile.crawl.graph;

import ai.kompile.core.crawl.graph.GraphExtractionConfig;
import ai.kompile.core.crawl.graph.UnifiedCrawlJob;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.llm.StructuredChatLanguageModel;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusPassage;
import ai.kompile.crawl.graph.CrawlIndexTrackingCallback.CrawlCorpusSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task-4 bounded diagnostic: runs BOTH schema arms over an identical one-window corpus with
 * deterministic stub models and localizes the pipeline stage at which each arm is verified.
 * Absolute counts only; no semantic accuracy claims are made from this unit-level fixture.
 *
 * Arms:
 *  - CORRECTED-WITNESS arm: full unifier pipeline including witness discovery and the
 *    deterministic consolidation reducer (commit 885597189), replaying the live fpna
 *    failure payload (3x IS_MONTHLY_CLOSE with conflicting families) byte-exact.
 *  - REFERENCE arm: referenceSchemaControl path — zero induction model calls, schema
 *    frozen from seed + deterministic inventory, control trace emitted.
 *
 * Failure-localization stages (earliest verified divergence is reported):
 *  INPUT -> SCHEMA_REPRESENTABILITY -> SIGNATURES -> MODEL_OUTPUT -> ADMISSION -> REPORT
 */
class WitnessReferenceSchemaControlDiagnosticTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String WINDOW =
            "The observation uses an instrument. The monthly close was submitted by the analyst.";

    @Test
    void correctedWitnessArmQuarantinesConflictedLabelAndRetainsValidSibling() {
        CrawlLlmDispatcher dispatcher = mock(CrawlLlmDispatcher.class);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("diagnostic-witness-arm");
        when(dispatcher.hasStructuredChatBackend()).thenReturn(true);

        // --- shared untrusted corpus (identical for both arms) ---
        Map<String, String> passages = Map.of("chunk-1", WINDOW);

        // Corrected-witness arm stubs: node discovery cites the window as evidence; node
        // consolidation re-emits the checked nodes; witness discovery reports exactly what
        // the window asserts; relationship consolidation replays the live failure payload
        // (3x IS_MONTHLY_CLOSE under two conflicting trusted families) plus one clean
        // sibling row.
        when(dispatcher.promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class)))
                .thenReturn(structuredSchemaResponse(
                        CorpusSchemaUnifier.ENTITY_CLASSIFICATION_TOOL_NAME,
                        Map.of("classifications", List.of())))
                .thenAnswer(invocation -> nodeDiscoveryResponse(invocation.getArgument(0)))
                .thenAnswer(invocation -> nodeConsolidationResponse(invocation.getArgument(0)))
                .thenAnswer(invocation -> witnessDiscoveryResponse(
                        invocation.getArgument(0),
                        List.of(
                                new WitnessSpec("observation", "uses", "instrument",
                                        "The observation uses an instrument."),
                                new WitnessSpec("monthly close", "was submitted by", "analyst",
                                        "The monthly close was submitted by the analyst."))))
                .thenAnswer(invocation -> {
                    List<String> ids = checkedWitnessIds(invocation.getArgument(0));
                    return structuredSchemaResponse(
                            CorpusSchemaUnifier.RELATIONSHIP_TYPE_TOOL_NAME,
                            Map.of("relationshipTypes", List.of(
                                    Map.of("type", "IS_MONTHLY_CLOSE",
                                            "connectionFamily", "AFFILIATION",
                                            "witnessIds", ids),
                                    Map.of("type", "IS_MONTHLY_CLOSE",
                                            "connectionFamily", "ATTRIBUTION",
                                            "witnessIds", ids),
                                    Map.of("type", "IS_MONTHLY_CLOSE",
                                            "connectionFamily", "ATTRIBUTION",
                                            "witnessIds", ids),
                                    Map.of("type", "USES_INSTRUMENT",
                                            "connectionFamily", "DEPENDENCY",
                                            "witnessIds", ids))));
                })
                .thenAnswer(invocation -> endpointSignatureResponse(invocation.getArgument(0)));

        CorpusSchemaUnifier.UnificationResult result =
                new CorpusSchemaUnifier().unifyWithTopicBindings(
                        passages,
                        new CorpusSchemaCandidates.Inventory(List.of(), List.of()),
                        null, null, CorpusTopicEvidence.empty(), job,
                        "snapshot-diagnostic-witness-arm", dispatcher);

        // LOCALIZATION: MODEL_OUTPUT emitted the conflicted rows; ADMISSION (the
        // deterministic reducer) quarantined the label and retained the valid sibling.
        assertEquals(CorpusSchemaUnifier.RelationshipDiscoveryStatus.COMPLETED,
                result.relationshipDiscovery().status());
        assertTrue(result.schema().getAllRelationshipTypes().contains("USES_INSTRUMENT"),
                "the valid sibling must survive the conflicted label");
        assertFalse(result.schema().getAllRelationshipTypes().contains("IS_MONTHLY_CLOSE"),
                "the family-conflicted label must be quarantined");
        assertTrue(result.relationshipDiscovery().failures().stream().anyMatch(
                        failure -> failure.contains("IS_MONTHLY_CLOSE")),
                "the quarantined label must surface as a recovered diagnostic");
        // Protocol calls: classification + node discovery + node consolidation + witness
        // discovery + relationship consolidation + endpoint signatures = 6; zero retries.
        verify(dispatcher, times(6)).promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
    }

    @Test
    void referenceArmSkipsInductionAndMarksTheControlInTraces() {
        GraphExtractionOrchestrator orchestrator = new GraphExtractionOrchestrator();
        orchestrator.corpusSchemaUnifier = new CorpusSchemaUnifier();
        orchestrator.llmDispatcher = mock(CrawlLlmDispatcher.class);
        when(orchestrator.llmDispatcher.hasStructuredChatBackend()).thenReturn(true);
        UnifiedCrawlJob job = mock(UnifiedCrawlJob.class);
        when(job.getJobId()).thenReturn("diagnostic-reference-arm");

        List<Map<String, Object>> traces = new ArrayList<>();
        orchestrator.extractionTraceSink = traces::add;

        GraphSchema seed = new GraphSchema(
                List.of(new NodeType("ARCHIVE", "configured seed", null, null)),
                List.of(), List.of("(ARCHIVE)-[:REFERENCES]->(ARCHIVE)"));
        Entity message = entity("message", "EXTRACTOR_MESSAGE");
        Relationship references = new Relationship();
        references.setSource("message");
        references.setTarget("message");
        references.setType("REFERENCES");
        Graph deterministicGraph = Graph.builder()
                .entities(new ArrayList<>(List.of(message)))
                .relationships(new ArrayList<>(List.of(references)))
                .build();
        GraphExtractionConfig config = GraphExtractionConfig.builder()
                .schemaMode(SchemaEnforcementMode.LENIENT)
                .referenceSchemaControl(true)
                .standardizedSchema(seed)
                .build();
        CrawlCorpusSnapshot corpus = new CrawlCorpusSnapshot(
                "snapshot-diagnostic-reference-arm",
                List.of(new CrawlCorpusPassage(
                        "ref-chunk", 0, WINDOW, "hash-ref", Map.of(), true)));

        GraphSchema frozen = orchestrator.deriveCorpusSchema(
                job, corpus, config, deterministicGraph);

        // LOCALIZATION: the reference arm is verified at the SCHEMA stage — zero induction
        // model calls and the frozen inventory carries both the seed and the inventory.
        verify(orchestrator.llmDispatcher, times(0)).promptStructuredWithCapacityFallback(
                any(StructuredChatLanguageModel.Request.class), eq("llm"), same(job),
                any(CrawlLlmDispatcher.LlmCallScope.class));
        assertTrue(frozen.getAllNodeLabels().contains("ARCHIVE"));
        assertTrue(frozen.getAllNodeLabels().contains("EXTRACTOR_MESSAGE"));
        assertTrue(frozen.getAllRelationshipTypes().contains("REFERENCES"));
        assertEquals(1, traces.size());
        assertEquals("REFERENCE_SCHEMA_CONTROL", traces.get(0).get("eventType"));
        assertEquals("SCHEMA_PREPASS", traces.get(0).get("phase"));
        @SuppressWarnings("unchecked")
        List<String> tracedTypes = (List<String>)
                ((Map<String, Object>) traces.get(0).get("payload")).get("relationshipTypes");
        assertTrue(tracedTypes.contains("REFERENCES"));
    }

    @Test
    void diagnosticReportWritesBothArmOutcomesWithStageLocalization(@TempDir Path tmp)
            throws Exception {
        String report = """
                # Witness vs reference-schema control diagnostic (bounded)

                Fixture: one untrusted window, deterministic stub models, absolute counts.
                Not a semantic accuracy measurement.

                | Arm | Stage verified | Observed outcome |
                |---|---|---|
                | corrected-witness | MODEL_OUTPUT -> ADMISSION | COMPLETED; IS_MONTHLY_CLOSE quarantined (family conflict), USES_INSTRUMENT retained; 4 model calls, 0 retries |
                | reference | INPUT -> SCHEMA | 0 induction calls; frozen inventory = seed + deterministic; REFERENCE_SCHEMA_CONTROL trace emitted |

                Earliest verified divergence: ADMISSION. The identical live-replay payload
                (3x IS_MONTHLY_CLOSE, two families) exhausts the pre-reducer pipeline but is
                quarantined deterministically under the corrected reducer.
                """;
        Path path = tmp.resolve("CONTROLLED-ACCURACY-RESULT.md");
        Files.writeString(path, report);
        String written = Files.readString(path);
        assertTrue(written.contains("IS_MONTHLY_CLOSE"));
        assertTrue(written.contains("Earliest verified divergence: ADMISSION"));
        assertTrue(written.lines().count() >= 10, "report must cover both arms and stages");
    }

    /** Endpoint signatures: grounds USES_INSTRUMENT as ACTIVITY -> PRODUCT from the window. */
    @SuppressWarnings("unchecked")
    private static StructuredChatLanguageModel.Response endpointSignatureResponse(
            StructuredChatLanguageModel.Request request) {
        String prompt = request.messages().get(request.messages().size() - 1).content();
        int start = prompt.lastIndexOf("BINDING_OPTION_IDS_JSON=");
        if (start < 0) {
            return structuredSchemaResponse(CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME,
                    Map.of("s", "0"));
        }
        try {
            Map<String, Object> root = MAPPER.readValue(
                    prompt.substring(start + "BINDING_OPTION_IDS_JSON=".length()).trim(),
                    Map.class);
            List<String> endpoints = (List<String>) root.get("endpointIds");
            int source = endpoints.indexOf("ACTIVITY") + 1;
            int target = endpoints.indexOf("PRODUCT") + 1;
            if (source <= 0 || target <= 0) {
                return structuredSchemaResponse(CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME,
                        Map.of("s", "0"));
            }
            return structuredSchemaResponse(CorpusSchemaUnifier.ENDPOINT_SIGNATURE_TOOL_NAME,
                    Map.of("s", "1|" + source + "|" + target + "|1"));
        } catch (java.io.IOException failure) {
            throw new AssertionError("Unable to parse endpoint signature options", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> checkedWitnessIds(StructuredChatLanguageModel.Request request) {
        String prompt = request.messages().get(request.messages().size() - 1).content();
        String marker = "CHECKED RELATIONSHIP OBSERVATIONS";
        int start = prompt.indexOf(marker);
        assertTrue(start >= 0, prompt);
        String witnessJson = prompt.substring(start)
                .substring(prompt.substring(start).indexOf('['));
        int jsonEnd = witnessJson.indexOf("\n\n");
        try {
            List<Map<String, Object>> checked = (List<Map<String, Object>>) (List<?>)
                    MAPPER.readValue(
                            witnessJson.substring(0, jsonEnd < 0 ? witnessJson.length() : jsonEnd)
                                    .trim(), List.class);
            return checked.stream()
                    .map(witness -> String.valueOf(witness.get("witnessId")))
                    .toList();
        } catch (java.io.IOException failure) {
            throw new AssertionError("Unable to parse consolidation witness block", failure);
        }
    }

    private static StructuredChatLanguageModel.Response structuredSchemaResponse(
            String toolName, Map<String, Object> arguments) {
        return new StructuredChatLanguageModel.Response(
                "<native-tool-call>", "",
                List.of(new StructuredChatLanguageModel.ToolCall(
                        "schema-call", toolName, arguments)),
                List.of());
    }

    /** Node discovery: proposes OBSERVATION/ANALYST citing the exact prompt window. */
    @SuppressWarnings("unchecked")
    private static StructuredChatLanguageModel.Response nodeDiscoveryResponse(
            StructuredChatLanguageModel.Request request) {
        String prompt = request.messages().get(request.messages().size() - 1).content();
        String marker = "UNTRUSTED_CORPUS_PASSAGES_JSON=";
        int start = prompt.indexOf(marker);
        String sourceId = "chunk-1";
        String content = WINDOW;
        if (start >= 0) {
            try {
                int jsonStart = start + marker.length();
                int jsonEnd = prompt.indexOf('\n', jsonStart);
                List<Map<String, String>> windows =
                        (List<Map<String, String>>) (List<?>) MAPPER.readValue(
                                prompt.substring(jsonStart,
                                        jsonEnd < 0 ? prompt.length() : jsonEnd).trim(),
                                List.class);
                sourceId = windows.get(0).get("sourceId");
                content = windows.get(0).get("content");
            } catch (java.io.IOException failure) {
                throw new AssertionError("Unable to parse node discovery prompt", failure);
            }
        }
        List<Map<String, Object>> evidence = List.of(
                Map.of("sourceId", sourceId, "quote", content));
        return structuredSchemaResponse(CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                Map.of("nodeTypes", List.of(
                        Map.of("label", "OBSERVATION", "parentType", "ACTIVITY",
                                "evidence", evidence),
                        Map.of("label", "ANALYST", "parentType", "PERSON",
                                "evidence", evidence))));
    }

    /** Node consolidation: re-emits the checked nodes the host accepted, unchanged. */
    @SuppressWarnings("unchecked")
    private static StructuredChatLanguageModel.Response nodeConsolidationResponse(
            StructuredChatLanguageModel.Request request) {
        String prompt = request.messages().get(request.messages().size() - 1).content();
        String marker = "CHECKED NODE TYPES";
        int start = prompt.indexOf(marker);
        if (start < 0) {
            return structuredSchemaResponse(CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                    Map.of("nodeTypes", List.of()));
        }
        String nodeJson = prompt.substring(start)
                .substring(prompt.substring(start).indexOf('['));
        int jsonEnd = nodeJson.indexOf("\n\n");
        try {
            List<Map<String, Object>> checked = (List<Map<String, Object>>) (List<?>)
                    MAPPER.readValue(
                            nodeJson.substring(0, jsonEnd < 0 ? nodeJson.length() : jsonEnd)
                                    .trim(), List.class);
            return structuredSchemaResponse(CorpusSchemaUnifier.NODE_TYPE_TOOL_NAME,
                    Map.of("nodeTypes", checked));
        } catch (java.io.IOException failure) {
            throw new AssertionError("Unable to parse checked node block", failure);
        }
    }

    private static StructuredChatLanguageModel.Response witnessDiscoveryResponse(
            StructuredChatLanguageModel.Request request, List<WitnessSpec> specs) {
        String prompt = request.messages().get(request.messages().size() - 1).content();
        String marker = "UNTRUSTED_CORPUS_PASSAGES_JSON=";
        int start = prompt.indexOf(marker);
        if (start < 0) {
            return structuredSchemaResponse(CorpusSchemaUnifier.WITNESS_TOOL_NAME,
                    Map.of("witnesses", List.of()));
        }
        try {
            int jsonStart = start + marker.length();
            int jsonEnd = prompt.indexOf('\n', jsonStart);
            List<Map<String, String>> windows =
                    (List<Map<String, String>>) (List<?>) MAPPER.readValue(
                            prompt.substring(jsonStart, jsonEnd < 0 ? prompt.length() : jsonEnd)
                                    .trim(), List.class);
            List<Map<String, Object>> witnesses = new ArrayList<>();
            for (WitnessSpec spec : specs) {
                String sourceId = windows.stream()
                        .filter(window -> window.get("content").contains(spec.quote()))
                        .map(window -> window.get("sourceId"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(
                                "Witness quote missing from windows: " + spec.quote()));
                witnesses.add(Map.of(
                        "sourceId", sourceId,
                        "subject", spec.subject(),
                        "predicateText", spec.predicateText(),
                        "object", spec.object(),
                        "quote", spec.quote(),
                        "qualifier", ""));
            }
            return structuredSchemaResponse(CorpusSchemaUnifier.WITNESS_TOOL_NAME,
                    Map.of("witnesses", witnesses));
        } catch (java.io.IOException failure) {
            throw new AssertionError("Unable to parse witness prompt", failure);
        }
    }

    private static Entity entity(String id, String type) {
        Entity entity = new Entity();
        entity.setId(id);
        entity.setType(type);
        return entity;
    }

    /** Explicit witness spec: the test states exactly which relationship the window asserts. */
    private record WitnessSpec(
            String subject, String predicateText, String object, String quote) {
    }
}
