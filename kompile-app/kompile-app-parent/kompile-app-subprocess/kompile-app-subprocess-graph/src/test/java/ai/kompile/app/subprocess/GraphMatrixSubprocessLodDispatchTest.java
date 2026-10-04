/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.subprocess;

import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The graph page's level-of-detail reads reach the store with their arguments intact. They had no
 * dispatch case, so the apps never got past the interface's empty default.
 */
class GraphMatrixSubprocessLodDispatchTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final KnowledgeGraphService service = mock(KnowledgeGraphService.class);

    @Test
    void theExpandKeepsItsEdgeTypesAndFactSheet() throws Exception {
        when(service.expandNeighborhoodVisualization("a", 25, List.of("MENTIONS", "CALLS"), 7L))
                .thenReturn(Map.of("nodes", List.of(Map.of("id", "a"), Map.of("id", "b"))));

        JsonNode result = dispatch("expandNeighborhoodVisualization", "a", 25, List.of("MENTIONS", "CALLS"), 7L);

        assertEquals(List.of("a", "b"), ids(result.path("nodes")));
    }

    @Test
    void aMissingEdgeTypeFilterStaysNullSoEveryTypeIsRead() throws Exception {
        dispatch("expandNeighborhoodVisualization", "a", 25, null, 7L);

        verify(service).expandNeighborhoodVisualization("a", 25, null, 7L);
    }

    @Test
    void anOlderThreeArgumentCallerReadsWithoutASheet() throws Exception {
        dispatch("expandNeighborhoodVisualization", "a", 25, List.of("MENTIONS"));

        verify(service).expandNeighborhoodVisualization("a", 25, List.of("MENTIONS"), null);
    }

    @Test
    void theTopKOverviewKeepsItsFactSheet() throws Exception {
        when(service.getTopKVisualizationData(7L, 50, "degree"))
                .thenReturn(Map.of("nodes", List.of(Map.of("id", "hub"))));

        JsonNode result = dispatch("getTopKVisualizationData", 7L, 50, "degree");

        assertEquals(List.of("hub"), ids(result.path("nodes")));
    }

    private JsonNode dispatch(String method, Object... values) throws Exception {
        List<JsonNode> args = new ArrayList<>();
        for (Object value : values) args.add(value == null ? mapper.nullNode() : mapper.valueToTree(value));
        JsonNode reply = mapper.readTree(GraphMatrixSubprocessMain.dispatchKnowledgeGraphService(
                service, null, method, args, mapper));
        assertTrue(reply.path("ok").asBoolean(false), reply.toString());
        return reply.path("result");
    }

    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : array) ids.add(item.path("id").asText());
        return ids;
    }
}
