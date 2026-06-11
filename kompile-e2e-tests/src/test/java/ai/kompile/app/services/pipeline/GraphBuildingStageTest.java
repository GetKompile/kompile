/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.services.pipeline;

import ai.kompile.app.services.pipeline.stages.GraphBuildingStage;
import ai.kompile.app.services.pipeline.stages.IndexingStage;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link GraphBuildingStage} verifying entity/relationship extraction,
 * batching, disabled state, and error handling.
 */
@ExtendWith(MockitoExtension.class)
class GraphBuildingStageTest {

    @Mock
    private GraphConstructor graphConstructor;

    private GraphBuildingStage stage;

    // Reusable input for tests
    private IndexingStage.IndexingOutput dummyInput;

    @BeforeEach
    void setUp() {
        stage = new GraphBuildingStage(graphConstructor);
        dummyInput = new IndexingStage.IndexingOutput(
                List.of("doc1", "doc2"), 5, 1, 100L, "bge-base", "recursive",
                "excel-loader", "task-1", new HashMap<>());
    }

    @Test
    void disabledStageReturnsZeroCounts() throws Exception {
        stage.setEnabled(false);

        GraphBuildingStage.GraphBuildingOutput output = stage.process(dummyInput);

        assertEquals(0, output.entitiesExtracted());
        assertEquals(0, output.relationshipsExtracted());
        assertEquals(0, output.batchCount());
        verifyNoInteractions(graphConstructor);
    }

    @Test
    void nullGraphConstructorReturnsZeroCounts() throws Exception {
        GraphBuildingStage nullStage = new GraphBuildingStage(null);

        GraphBuildingStage.GraphBuildingOutput output = nullStage.process(dummyInput);

        assertEquals(0, output.entitiesExtracted());
        assertEquals(0, output.relationshipsExtracted());
    }

    @Test
    void emptyChunksReturnsZeroCounts() throws Exception {
        stage.setChunksToProcess(List.of());

        GraphBuildingStage.GraphBuildingOutput output = stage.process(dummyInput);

        assertEquals(0, output.entitiesExtracted());
        verifyNoInteractions(graphConstructor);
    }

    @Test
    void nullChunksReturnsZeroCounts() throws Exception {
        // Don't set chunks at all (null)
        GraphBuildingStage.GraphBuildingOutput output = stage.process(dummyInput);

        assertEquals(0, output.entitiesExtracted());
        verifyNoInteractions(graphConstructor);
    }

    @Test
    void extractsEntitiesAndRelationships() throws Exception {
        // Setup mock graph response
        Graph mockGraph = new Graph();
        Entity person = new Entity();
        person.setId("e1");
        person.setTitle("John Doe");
        person.setType("PERSON");
        Entity org = new Entity();
        org.setId("e2");
        org.setTitle("Acme Corp");
        org.setType("ORGANIZATION");
        mockGraph.setEntities(List.of(person, org));

        Relationship rel = new Relationship();
        rel.setSource("e1");
        rel.setTarget("e2");
        rel.setType("WORKS_AT");
        mockGraph.setRelationships(List.of(rel));

        when(graphConstructor.constructGraphFromDocs(anyList(), any(), any()))
                .thenReturn(mockGraph);

        // Provide chunks
        List<RetrievedDoc> chunks = List.of(
                new RetrievedDoc("c1", "John Doe works at Acme Corp.", new HashMap<>()));
        stage.setChunksToProcess(chunks);

        GraphBuildingStage.GraphBuildingOutput output = stage.process(dummyInput);

        assertEquals(2, output.entitiesExtracted());
        assertEquals(1, output.relationshipsExtracted());
        assertEquals(1, output.batchCount());
        assertTrue(output.graphBuildingTimeMs() >= 0);
    }

    @Test
    void batchesChunksCorrectly() throws Exception {
        Graph batchGraph = new Graph();
        Entity entity = new Entity();
        entity.setId("e1");
        entity.setTitle("Entity");
        entity.setType("CONCEPT");
        batchGraph.setEntities(List.of(entity));
        batchGraph.setRelationships(List.of());

        when(graphConstructor.constructGraphFromDocs(anyList(), any(), any()))
                .thenReturn(batchGraph);

        // Set batch size to 3
        stage.configure(Map.of("batchSize", 3));

        // Provide 7 chunks → should produce 3 batches (3+3+1)
        List<RetrievedDoc> chunks = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            chunks.add(new RetrievedDoc("c" + i, "text " + i, new HashMap<>()));
        }
        stage.setChunksToProcess(chunks);

        GraphBuildingStage.GraphBuildingOutput output = stage.process(dummyInput);

        assertEquals(3, output.batchCount());
        // 3 batches × 1 entity each = 3 entities
        assertEquals(3, output.entitiesExtracted());

        // Verify constructGraphFromDocs was called 3 times
        verify(graphConstructor, times(3)).constructGraphFromDocs(anyList(), any(), any());
    }

    @Test
    void batchSizeIsClamped() {
        stage.configure(Map.of("batchSize", 0)); // below min
        // Should be clamped to MIN_BATCH_SIZE (1)

        stage.configure(Map.of("batchSize", 100)); // above max (50)
        // Should be clamped to MAX_BATCH_SIZE (50)

        // No exception = pass
    }

    @Test
    void errorInOneBatchDoesNotStopOthers() throws Exception {
        Graph goodGraph = new Graph();
        Entity entity = new Entity();
        entity.setId("e1");
        entity.setTitle("Good");
        entity.setType("CONCEPT");
        goodGraph.setEntities(List.of(entity));
        goodGraph.setRelationships(List.of());

        // First batch throws, second succeeds
        when(graphConstructor.constructGraphFromDocs(anyList(), any(), any()))
                .thenThrow(new RuntimeException("LLM unavailable"))
                .thenReturn(goodGraph);

        stage.configure(Map.of("batchSize", 2));

        List<RetrievedDoc> chunks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            chunks.add(new RetrievedDoc("c" + i, "text " + i, new HashMap<>()));
        }
        stage.setChunksToProcess(chunks);

        GraphBuildingStage.GraphBuildingOutput output = stage.process(dummyInput);

        // Second batch succeeded with 1 entity
        assertEquals(1, output.entitiesExtracted());
        assertEquals(2, output.batchCount());
    }

    @Test
    void cancellationStopsProcessing() throws Exception {
        stage.configure(Map.of("batchSize", 1));

        List<RetrievedDoc> chunks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            chunks.add(new RetrievedDoc("c" + i, "text " + i, new HashMap<>()));
        }
        stage.setChunksToProcess(chunks);

        // Cancel immediately
        stage.cancel();

        assertThrows(InterruptedException.class, () -> stage.process(dummyInput));
    }

    @Test
    void resetClearsState() {
        stage.setEnabled(false);
        stage.cancel();
        stage.setChunksToProcess(List.of(new RetrievedDoc("c1", "t", new HashMap<>())));

        stage.reset();

        assertFalse(stage.isCancelled());
        // After reset, chunks should be null
    }

    @Test
    void configureFromMapSetsAllOptions() {
        Map<String, Object> options = new HashMap<>();
        options.put("enabled", false);
        options.put("batchSize", 20);
        options.put("schemaEnforcementMode", "STRICT");

        stage.configure(options);

        assertFalse(stage.isEnabled());
    }

    @Test
    void configureWithGraphBuildingEnabledKey() {
        stage.configure(Map.of("graphBuildingEnabled", true));
        assertTrue(stage.isEnabled());

        stage.configure(Map.of("graphBuildingEnabled", false));
        assertFalse(stage.isEnabled());
    }

    @Test
    void outputMetadataContainsEntityTypeCounts() throws Exception {
        Graph graph = new Graph();
        Entity person = new Entity();
        person.setId("e1");
        person.setTitle("John");
        person.setType("PERSON");
        Entity org = new Entity();
        org.setId("e2");
        org.setTitle("Acme");
        org.setType("ORGANIZATION");
        Entity person2 = new Entity();
        person2.setId("e3");
        person2.setTitle("Jane");
        person2.setType("PERSON");
        graph.setEntities(List.of(person, org, person2));
        graph.setRelationships(List.of());

        when(graphConstructor.constructGraphFromDocs(anyList(), any(), any()))
                .thenReturn(graph);

        stage.setChunksToProcess(List.of(
                new RetrievedDoc("c1", "text", new HashMap<>())));

        GraphBuildingStage.GraphBuildingOutput output = stage.process(dummyInput);

        Map<String, Object> metadata = output.metadata();
        assertNotNull(metadata.get("entityTypes"));
        @SuppressWarnings("unchecked")
        Map<String, Long> entityTypes = (Map<String, Long>) metadata.get("entityTypes");
        assertEquals(2L, entityTypes.get("PERSON"));
        assertEquals(1L, entityTypes.get("ORGANIZATION"));
    }

    @Test
    void entitiesPerSecondCalculation() {
        var output = new GraphBuildingStage.GraphBuildingOutput(
                100, 50, 10, 2000L, "model", "chunker", "loader", "task-1", Map.of());

        assertEquals(50.0, output.entitiesPerSecond(), 0.001);
        assertEquals(150, output.totalGraphElements());
    }

    @Test
    void entitiesPerSecondZeroTime() {
        var output = new GraphBuildingStage.GraphBuildingOutput(
                100, 50, 10, 0L, "model", "chunker", "loader", "task-1", Map.of());

        assertEquals(0.0, output.entitiesPerSecond());
    }
}
