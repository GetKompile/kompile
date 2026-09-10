/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.app.subprocess;

import ai.kompile.knowledgegraph.generation.GraphGeneration;
import ai.kompile.knowledgegraph.generation.GraphGenerationCoordinator;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphMatrixSubprocessGenerationDispatchTest {

    @Test
    void knowledgeGraphDispatcherRoutesLifecycleToAuthoritativeCoordinator() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        KnowledgeGraphService service = mock(KnowledgeGraphService.class);
        GraphGenerationCoordinator coordinator = mock(GraphGenerationCoordinator.class);
        GraphGeneration.Ref ref = new GraphGeneration.Ref(
                7L, "factsheet_7", "factsheet_7~gen~g1", "g1", "factsheet_7", 0L);
        when(coordinator.begin(7L, "factsheet_7", "g1", "job-7")).thenReturn(ref);

        String response = GraphMatrixSubprocessMain.dispatchKnowledgeGraphService(
                service, coordinator, "beginFactSheetGeneration",
                List.of(mapper.valueToTree(7L), mapper.valueToTree("g1"), mapper.valueToTree("job-7")),
                mapper);
        JsonNode decoded = mapper.readTree(response);

        assertTrue(decoded.path("ok").asBoolean());
        assertEquals(2, decoded.path("protocolVersion").asInt());
        assertEquals(ref.physicalGraphId(), decoded.path("result").path("physicalGraphId").asText());
        verify(coordinator).begin(7L, "factsheet_7", "g1", "job-7");
    }
}
