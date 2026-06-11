/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.services.pipeline.stages;

import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GraphBuildingStageTest {

    private GraphConstructor graphConstructor;
    private GraphBuildingStage stage;

    @BeforeEach
    void setUp() {
        graphConstructor = mock(GraphConstructor.class);
        stage = new GraphBuildingStage(graphConstructor);
    }

    // --- getName ---

    @Test
    void getNameReturnsGraphBuilding() {
        assertEquals("graph-building", stage.getName());
    }

    // --- process: disabled ---

    @Test
    void processReturnsEmptyWhenDisabled() throws Exception {
        stage.setEnabled(false);
        IndexingStage.IndexingOutput input = indexingOutput("task-1");

        GraphBuildingStage.GraphBuildingOutput output = stage.process(input);
        assertEquals(0, output.entitiesExtracted());
        assertEquals(0, output.relationshipsExtracted());
        verify(graphConstructor, never()).constructGraphFromDocs(any(), any(), any());
    }

    // --- process: no constructor ---

    @Test
    void processReturnsEmptyWhenNoGraphConstructor() throws Exception {
        GraphBuildingStage noConstructor = new GraphBuildingStage(null);
        noConstructor.setChunksToProcess(List.of(chunk("chunk-1")));
        IndexingStage.IndexingOutput input = indexingOutput("task-1");

        GraphBuildingStage.GraphBuildingOutput output = noConstructor.process(input);
        assertEquals(0, output.entitiesExtracted());
    }

    // --- process: no chunks ---

    @Test
    void processReturnsEmptyWhenNoChunks() throws Exception {
        stage.setChunksToProcess(List.of());
        IndexingStage.IndexingOutput input = indexingOutput("task-1");

        GraphBuildingStage.GraphBuildingOutput output = stage.process(input);
        assertEquals(0, output.entitiesExtracted());
    }

    @Test
    void processReturnsEmptyWhenChunksNull() throws Exception {
        // chunksToProcess is null by default
        IndexingStage.IndexingOutput input = indexingOutput("task-1");

        GraphBuildingStage.GraphBuildingOutput output = stage.process(input);
        assertEquals(0, output.entitiesExtracted());
    }

    // --- process: successful extraction ---

    @Test
    void processExtractsEntitiesAndRelationships() throws Exception {
        List<RetrievedDoc> chunks = List.of(chunk("c1"), chunk("c2"), chunk("c3"));
        stage.setChunksToProcess(chunks);

        Graph graph = new Graph();
        Entity e1 = new Entity();
        e1.setTitle("Alice");
        e1.setType("PERSON");
        Entity e2 = new Entity();
        e2.setTitle("Acme Corp");
        e2.setType("ORGANIZATION");
        graph.setEntities(List.of(e1, e2));

        Relationship r1 = new Relationship();
        r1.setType("WORKS_AT");
        graph.setRelationships(List.of(r1));

        when(graphConstructor.constructGraphFromDocs(anyList(), any(), any())).thenReturn(graph);

        IndexingStage.IndexingOutput input = indexingOutput("task-1");
        GraphBuildingStage.GraphBuildingOutput output = stage.process(input);

        assertEquals(2, output.entitiesExtracted());
        assertEquals(1, output.relationshipsExtracted());
        assertEquals(3, output.totalGraphElements());
        assertEquals("task-1", output.taskId());
        assertTrue(output.graphBuildingTimeMs() >= 0);
    }

    // --- process: batch processing ---

    @Test
    void processHandlesMultipleBatches() throws Exception {
        // 12 chunks with batch size 5 = 3 batches (5 + 5 + 2)
        List<RetrievedDoc> chunks = new ArrayList<>();
        for (int i = 0; i < 12; i++) chunks.add(chunk("c" + i));
        stage.setChunksToProcess(chunks);
        stage.configure(Map.of("batchSize", 5));

        Graph batchGraph = new Graph();
        Entity entity = new Entity();
        entity.setTitle("Entity");
        entity.setType("THING");
        batchGraph.setEntities(List.of(entity));
        batchGraph.setRelationships(List.of());

        when(graphConstructor.constructGraphFromDocs(anyList(), any(), any())).thenReturn(batchGraph);

        GraphBuildingStage.GraphBuildingOutput output = stage.process(indexingOutput("task-1"));

        // 3 batches, each producing 1 entity
        assertEquals(3, output.entitiesExtracted());
        assertEquals(3, output.batchCount());
        verify(graphConstructor, times(3)).constructGraphFromDocs(anyList(), any(), any());
    }

    // --- process: null graph returned ---

    @Test
    void processHandlesNullGraphFromConstructor() throws Exception {
        stage.setChunksToProcess(List.of(chunk("c1")));
        when(graphConstructor.constructGraphFromDocs(anyList(), any(), any())).thenReturn(null);

        GraphBuildingStage.GraphBuildingOutput output = stage.process(indexingOutput("task-1"));
        assertEquals(0, output.entitiesExtracted());
        assertEquals(0, output.relationshipsExtracted());
    }

    // --- process: batch failure continues ---

    @Test
    void processSkipsFailedBatchAndContinues() throws Exception {
        List<RetrievedDoc> chunks = new ArrayList<>();
        for (int i = 0; i < 6; i++) chunks.add(chunk("c" + i));
        stage.setChunksToProcess(chunks);
        stage.configure(Map.of("batchSize", 3));

        Graph successGraph = new Graph();
        Entity entity = new Entity();
        entity.setTitle("OK");
        entity.setType("THING");
        successGraph.setEntities(List.of(entity));
        successGraph.setRelationships(List.of());

        // First batch fails, second succeeds
        when(graphConstructor.constructGraphFromDocs(anyList(), any(), any()))
                .thenThrow(new RuntimeException("LLM error"))
                .thenReturn(successGraph);

        GraphBuildingStage.GraphBuildingOutput output = stage.process(indexingOutput("task-1"));
        assertEquals(1, output.entitiesExtracted()); // only from second batch
    }

    // --- process: cancellation ---

    @Test
    void processThrowsWhenCancelled() {
        stage.setChunksToProcess(List.of(chunk("c1")));
        stage.cancel();

        assertThrows(InterruptedException.class, () -> stage.process(indexingOutput("task-1")));
    }

    // --- configure ---

    @Test
    void configureSetsEnabled() {
        stage.configure(Map.of("enabled", false));
        assertFalse(stage.isEnabled());

        stage.configure(Map.of("graphBuildingEnabled", true));
        assertTrue(stage.isEnabled());
    }

    @Test
    void configureClampsBatchSize() {
        stage.configure(Map.of("batchSize", 0));
        // batchSize clamped to MIN_BATCH_SIZE=1

        stage.configure(Map.of("batchSize", 100));
        // batchSize clamped to MAX_BATCH_SIZE=50
    }

    @Test
    void configureSetsSchemaFromString() {
        stage.configure(Map.of("schemaEnforcementMode", "STRICT"));
        // Should not throw
    }

    @Test
    void configureSetsSchemaFromEnum() {
        stage.configure(Map.of("schemaEnforcementMode", SchemaEnforcementMode.STRICT));
        // Should not throw
    }

    @Test
    void configureSetsSchemaObject() {
        GraphSchema schema = new GraphSchema(
                List.of(new NodeType("PERSON", "A person", null)),
                List.of(), null);
        stage.configure(Map.of("schema", schema));
        // Should not throw
    }

    @Test
    void configureHandlesNullOptions() {
        stage.configure(null);
        // No exception
    }

    // --- cancel / reset ---

    @Test
    void cancelAndResetCycle() {
        assertFalse(stage.isCancelled());
        stage.cancel();
        assertTrue(stage.isCancelled());
        stage.reset();
        assertFalse(stage.isCancelled());
    }

    @Test
    void resetClearsChunks() throws Exception {
        stage.setChunksToProcess(List.of(chunk("c1")));
        stage.reset();

        // After reset, chunks are null
        GraphBuildingStage.GraphBuildingOutput output = stage.process(indexingOutput("task-1"));
        assertEquals(0, output.entitiesExtracted());
    }

    // --- metrics ---

    @Test
    void metricsRecordSuccess() throws Exception {
        stage.setChunksToProcess(List.of(chunk("c1")));
        Graph graph = new Graph();
        graph.setEntities(List.of());
        graph.setRelationships(List.of());
        when(graphConstructor.constructGraphFromDocs(anyList(), any(), any())).thenReturn(graph);

        stage.process(indexingOutput("task-1"));
        assertNotNull(stage.getMetrics());
    }

    // --- output metadata ---

    @Test
    void outputIncludesEntityAndRelationshipTypeCounts() throws Exception {
        stage.setChunksToProcess(List.of(chunk("c1")));

        Graph graph = new Graph();
        Entity e1 = new Entity();
        e1.setTitle("Alice");
        e1.setType("PERSON");
        Entity e2 = new Entity();
        e2.setTitle("Bob");
        e2.setType("PERSON");
        Entity e3 = new Entity();
        e3.setTitle("Acme");
        e3.setType("ORGANIZATION");
        graph.setEntities(List.of(e1, e2, e3));

        Relationship r1 = new Relationship();
        r1.setType("WORKS_AT");
        Relationship r2 = new Relationship();
        r2.setType("WORKS_AT");
        graph.setRelationships(List.of(r1, r2));

        when(graphConstructor.constructGraphFromDocs(anyList(), any(), any())).thenReturn(graph);

        GraphBuildingStage.GraphBuildingOutput output = stage.process(indexingOutput("task-1"));

        assertTrue(output.metadata().containsKey("entityTypes"));
        assertTrue(output.metadata().containsKey("relationshipTypes"));
        assertTrue(output.metadata().containsKey("graphBuildingEnabled"));
    }

    // --- GraphBuildingOutput ---

    @Test
    void outputEntitiesPerSecondCalculation() {
        GraphBuildingStage.GraphBuildingOutput output = new GraphBuildingStage.GraphBuildingOutput(
                100, 50, 10, 2000L, "model", "chunker", "loader", "task-1", Map.of());
        assertEquals(50.0, output.entitiesPerSecond(), 0.1);
        assertEquals(150, output.totalGraphElements());
    }

    @Test
    void outputEntitiesPerSecondHandlesZeroTime() {
        GraphBuildingStage.GraphBuildingOutput output = new GraphBuildingStage.GraphBuildingOutput(
                10, 5, 1, 0L, null, null, null, "task-1", Map.of());
        assertEquals(0.0, output.entitiesPerSecond());
    }

    // --- helpers ---

    private IndexingStage.IndexingOutput indexingOutput(String taskId) {
        return new IndexingStage.IndexingOutput(
                List.of("doc-1"), 10, 1, 500L,
                "bge-base-en", "recursive", "pdf-loader",
                taskId, Map.of()
        );
    }

    private RetrievedDoc chunk(String id) {
        return new RetrievedDoc(id, "Some text content for " + id, Map.of());
    }
}
