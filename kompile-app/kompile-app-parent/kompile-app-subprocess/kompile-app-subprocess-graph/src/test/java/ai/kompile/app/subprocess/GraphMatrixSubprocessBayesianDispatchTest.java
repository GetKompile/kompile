/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.subprocess;

import ai.kompile.event.attribution.service.BayesianNetworkService;
import ai.kompile.graph.reasoning.domain.BayesianInferenceResult;
import ai.kompile.graph.reasoning.domain.MTheoryStructure;
import ai.kompile.graph.reasoning.mebn.MTheory;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryArtifactCodec;
import ai.kompile.graph.reasoning.mebn.RelationalMTheoryBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphMatrixSubprocessBayesianDispatchTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final BayesianNetworkService service = mock(BayesianNetworkService.class);

    @Test
    void theFactSheetReachesTheQuery() throws Exception {
        when(service.queryMebnFromKg(anyCollection(), anyMap(), anyInt(), anyInt(), any(), any()))
                .thenReturn(BayesianInferenceResult.builder()
                        .posteriors(Map.of("isRelevant(alice)", 0.8)).build());

        JsonNode result = dispatch("queryMebnFromKg",
                List.of("seed"), Map.of("isRelevant(bob)", 1), 2, 50, null, 42L);

        verify(service).queryMebnFromKg(List.of("seed"), Map.of("isRelevant(bob)", 1), 2, 50, null, 42L);
        assertEquals(0.8, result.path("posteriors").path("isRelevant(alice)").asDouble(), 1e-9);
    }

    @Test
    void aCallerWithoutAFactSheetStillQueries() throws Exception {
        dispatch("queryMebnFromKg", List.of("seed"), Map.of(), 2, 50);

        verify(service).queryMebnFromKg(List.of("seed"), Map.of(), 2, 50, null, null);
    }

    @Test
    void aWhatIfQueryRunsHereWithItsHypotheticalEvidence() throws Exception {
        when(service.whatIfQuery(anyCollection(), anyMap(), anyInt(), anyInt()))
                .thenReturn(BayesianInferenceResult.builder().posteriors(Map.of("b", 0.9)).build());

        JsonNode result = dispatch("whatIfQuery", List.of("seed"), Map.of("a", 1), 2, 50);

        verify(service).whatIfQuery(List.of("seed"), Map.of("a", 1), 2, 50);
        assertEquals(0.9, result.path("posteriors").path("b").asDouble(), 1e-9);
    }

    @Test
    void networkStatisticsAreComputedHere() throws Exception {
        when(service.getNetworkStatistics(anyCollection(), anyInt(), anyInt())).thenReturn(Map.of("nodes", 3));

        JsonNode result = dispatch("getNetworkStatistics", List.of("seed"), 2, 50);

        verify(service).getNetworkStatistics(List.of("seed"), 2, 50);
        assertEquals(3, result.path("nodes").asInt());
    }

    @Test
    void theTheoryStructureIsReturnedAsPlainData() throws Exception {
        when(service.describeMebnTheory(anyCollection(), anyInt(), anyInt())).thenReturn(new MTheoryStructure("kg",
                List.of(new MTheoryStructure.Fragment("works",
                        List.of(new MTheoryStructure.Variable("worksFor", "worksFor(PERSON, ORG)",
                                List.of("FALSE", "TRUE"), "RESIDENT")),
                        List.of(), List.of("exists(PERSON_0)"),
                        List.of(new MTheoryStructure.Edge("isRelevant", "worksFor", 0.7))))));

        JsonNode result = dispatch("describeMebnTheory", List.of("seed"), 2, 50);

        verify(service).describeMebnTheory(List.of("seed"), 2, 50);
        JsonNode fragment = result.path("fragments").path(0);
        assertEquals("worksFor(PERSON, ORG)", fragment.path("residentNodes").path(0).path("signature").asText());
        assertEquals(0.7, fragment.path("edges").path(0).path("strength").asDouble(), 1e-9);
    }

    @Test
    void aTheorySentAsItsArtifactIsRebuiltBeforeGrounding() throws Exception {
        MTheory theory = RelationalMTheoryBuilder.build("portable", List.of(
                new RelationalMTheoryBuilder.RelationDescriptor(
                        "supports", "PERSON", "DOCUMENT", 0.77, List.of("alice"), List.of("doc-1"))));
        when(service.queryWithMTheory(any(), anyMap(), any(), any()))
                .thenReturn(BayesianInferenceResult.builder().posteriors(Map.of("isRelevant(alice)", 0.6)).build());

        JsonNode result = dispatch("queryWithMTheory", RelationalMTheoryArtifactCodec.toJson(theory),
                Map.of("isRelevant(doc-1)", 1), null, 42L);

        ArgumentCaptor<MTheory> rebuilt = ArgumentCaptor.forClass(MTheory.class);
        verify(service).queryWithMTheory(rebuilt.capture(), eq(Map.of("isRelevant(doc-1)", 1)), isNull(), eq(42L));
        assertEquals(0.77, rebuilt.getValue().getMFrag("supports").getEdgeStrength("isRelevant", "supports"), 1e-12);
        assertEquals(Set.of("alice"), rebuilt.getValue().getEntityType("PERSON").getEntityIds());
        assertEquals(0.6, result.path("posteriors").path("isRelevant(alice)").asDouble(), 1e-9);
    }

    @Test
    void aMethodThisBuildLacksIsReportedAsUnknown() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> GraphMatrixSubprocessMain.dispatchBayesianNetworkService(
                        service, "noSuchMethod", List.of(), mapper));

        assertEquals("UNKNOWN_METHOD", GraphMatrixSubprocessMain.rpcErrorCode(missing));
    }

    @Test
    void theExceptionTypeDecidesTheCodeBeforeItsText() {
        assertEquals("INVALID_ARGUMENT", GraphMatrixSubprocessMain.rpcErrorCode(new IllegalArgumentException(
                "Unknown MEBN evidence variable(s): staleGeneration(x). Use names from posteriors")),
                "an evidence name must not read as a generation failure");
        assertEquals("UNKNOWN_METHOD", GraphMatrixSubprocessMain.rpcErrorCode(new IllegalArgumentException(
                "[graph-matrix] unknown service FQCN: ai.kompile.Missing")));
        assertEquals("STALE_REVISION", GraphMatrixSubprocessMain.rpcErrorCode(
                new IllegalStateException("stale revision 3")));
        assertEquals("INVALID_GENERATION", GraphMatrixSubprocessMain.rpcErrorCode(
                new IllegalStateException("no such generation")));
        assertEquals("INTERNAL_ERROR", GraphMatrixSubprocessMain.rpcErrorCode(new IllegalStateException("boom")));
    }

    private JsonNode dispatch(String method, Object... values) throws Exception {
        List<JsonNode> args = new ArrayList<>();
        for (Object value : values) args.add(value == null ? mapper.nullNode() : mapper.valueToTree(value));
        JsonNode reply = mapper.readTree(
                GraphMatrixSubprocessMain.dispatchBayesianNetworkService(service, method, args, mapper));
        assertTrue(reply.path("ok").asBoolean(false));
        return reply.path("result");
    }
}
