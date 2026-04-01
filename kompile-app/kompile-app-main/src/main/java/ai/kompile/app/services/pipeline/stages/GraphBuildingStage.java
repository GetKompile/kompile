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

package ai.kompile.app.services.pipeline.stages;

import ai.kompile.app.services.pipeline.PipelineStage;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Graph building stage: Extracts entities and relationships from indexed chunks
 * and stores them in the knowledge graph.
 *
 * <p>This stage provides:</p>
 * <ul>
 *   <li>LLM-based entity extraction from document chunks</li>
 *   <li>Relationship extraction between entities</li>
 *   <li>Schema-based validation and filtering</li>
 *   <li>Integration with Neo4j or matrix-based graph stores</li>
 * </ul>
 *
 * <p>Input: {@link IndexingStage.IndexingOutput} containing indexed document IDs</p>
 * <p>Output: {@link GraphBuildingOutput} containing extracted entities and relationships</p>
 */
public class GraphBuildingStage implements PipelineStage<IndexingStage.IndexingOutput, GraphBuildingStage.GraphBuildingOutput> {

    private static final Logger logger = LoggerFactory.getLogger(GraphBuildingStage.class);

    // Default batch size for entity extraction
    private static final int DEFAULT_BATCH_SIZE = 10;
    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 50;

    private final GraphConstructor graphConstructor;
    private final StageMetrics metrics = new StageMetrics();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    // Configuration
    private boolean enabled = true;
    private int batchSize = DEFAULT_BATCH_SIZE;
    private GraphSchema schema;
    private SchemaEnforcementMode enforcementMode = SchemaEnforcementMode.LENIENT;

    // Chunks to process (set by pipeline before processing)
    private List<RetrievedDoc> chunksToProcess;

    public GraphBuildingStage(GraphConstructor graphConstructor) {
        this.graphConstructor = graphConstructor;
    }

    @Override
    public String getName() {
        return "graph-building";
    }

    /**
     * Sets the chunks to be processed for entity extraction.
     * This should be called by the pipeline coordinator before processing.
     */
    public void setChunksToProcess(List<RetrievedDoc> chunks) {
        this.chunksToProcess = chunks;
    }

    @Override
    public GraphBuildingOutput process(IndexingStage.IndexingOutput input) throws Exception {
        if (cancelled.get()) {
            throw new InterruptedException("Graph building stage cancelled");
        }

        if (!enabled) {
            logger.debug("Graph building is disabled, skipping");
            return new GraphBuildingOutput(
                    0, 0, 0, 0,
                    input.embeddingModelUsed(),
                    input.chunkerUsed(),
                    input.loaderUsed(),
                    input.taskId(),
                    input.metadata()
            );
        }

        if (graphConstructor == null) {
            logger.warn("No graph constructor configured, skipping graph building");
            return new GraphBuildingOutput(
                    0, 0, 0, 0,
                    input.embeddingModelUsed(),
                    input.chunkerUsed(),
                    input.loaderUsed(),
                    input.taskId(),
                    input.metadata()
            );
        }

        if (chunksToProcess == null || chunksToProcess.isEmpty()) {
            logger.debug("No chunks to process for graph building");
            return new GraphBuildingOutput(
                    0, 0, 0, 0,
                    input.embeddingModelUsed(),
                    input.chunkerUsed(),
                    input.loaderUsed(),
                    input.taskId(),
                    input.metadata()
            );
        }

        long startNanos = System.nanoTime();
        int totalChunks = chunksToProcess.size();
        int entitiesExtracted = 0;
        int relationshipsExtracted = 0;
        int batchCount = 0;

        try {
            logger.info("Starting graph building for {} chunks, taskId={}", totalChunks, input.taskId());

            // Process chunks in batches
            List<Entity> allEntities = new ArrayList<>();
            List<Relationship> allRelationships = new ArrayList<>();

            for (int i = 0; i < totalChunks; i += batchSize) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Graph building cancelled during batch processing");
                }

                int end = Math.min(i + batchSize, totalChunks);
                List<RetrievedDoc> batch = chunksToProcess.subList(i, end);
                batchCount++;

                long batchStartNanos = System.nanoTime();

                try {
                    // Extract entities and relationships from this batch
                    Graph batchGraph = graphConstructor.constructGraphFromDocs(batch, schema, enforcementMode);

                    if (batchGraph != null) {
                        if (batchGraph.getEntities() != null) {
                            allEntities.addAll(batchGraph.getEntities());
                            entitiesExtracted += batchGraph.getEntities().size();
                        }
                        if (batchGraph.getRelationships() != null) {
                            allRelationships.addAll(batchGraph.getRelationships());
                            relationshipsExtracted += batchGraph.getRelationships().size();
                        }
                    }

                    long batchTimeMs = (System.nanoTime() - batchStartNanos) / 1_000_000;
                    logger.debug("Batch {}: extracted {} entities, {} relationships from {} chunks in {}ms [{}/{}]",
                            batchCount,
                            batchGraph != null && batchGraph.getEntities() != null ? batchGraph.getEntities().size() : 0,
                            batchGraph != null && batchGraph.getRelationships() != null ? batchGraph.getRelationships().size() : 0,
                            batch.size(), batchTimeMs, end, totalChunks);

                } catch (Exception e) {
                    logger.error("Failed to process batch {} for graph building: {}", batchCount, e.getMessage(), e);
                    // Continue with next batch
                }
            }

            long elapsedNanos = System.nanoTime() - startNanos;
            long elapsedMs = elapsedNanos / 1_000_000;
            metrics.recordSuccess(elapsedNanos, 0, totalChunks);

            logger.info("Graph building complete: {} entities, {} relationships from {} chunks in {}ms",
                    entitiesExtracted, relationshipsExtracted, totalChunks, elapsedMs);

            // Add graph statistics to metadata
            Map<String, Object> outputMetadata = new HashMap<>(input.metadata() != null ? input.metadata() : Map.of());
            outputMetadata.put("graphBuildingEnabled", true);
            outputMetadata.put("entitiesExtracted", entitiesExtracted);
            outputMetadata.put("relationshipsExtracted", relationshipsExtracted);
            outputMetadata.put("entityTypes", allEntities.stream()
                    .map(Entity::getType)
                    .filter(Objects::nonNull)
                    .collect(Collectors.groupingBy(t -> t, Collectors.counting())));
            outputMetadata.put("relationshipTypes", allRelationships.stream()
                    .map(Relationship::getType)
                    .filter(Objects::nonNull)
                    .collect(Collectors.groupingBy(t -> t, Collectors.counting())));

            return new GraphBuildingOutput(
                    entitiesExtracted,
                    relationshipsExtracted,
                    batchCount,
                    elapsedMs,
                    input.embeddingModelUsed(),
                    input.chunkerUsed(),
                    input.loaderUsed(),
                    input.taskId(),
                    outputMetadata
            );

        } catch (Exception e) {
            metrics.recordFailure();
            throw e;
        }
    }

    @Override
    public void configure(Map<String, Object> options) {
        if (options == null) return;

        if (options.containsKey("enabled")) {
            this.enabled = (Boolean) options.get("enabled");
        }
        if (options.containsKey("graphBuildingEnabled")) {
            this.enabled = (Boolean) options.get("graphBuildingEnabled");
        }
        if (options.containsKey("batchSize")) {
            this.batchSize = ((Number) options.get("batchSize")).intValue();
            this.batchSize = Math.max(MIN_BATCH_SIZE, Math.min(this.batchSize, MAX_BATCH_SIZE));
        }
        if (options.containsKey("schema")) {
            this.schema = (GraphSchema) options.get("schema");
        }
        if (options.containsKey("schemaEnforcementMode")) {
            Object mode = options.get("schemaEnforcementMode");
            if (mode instanceof SchemaEnforcementMode) {
                this.enforcementMode = (SchemaEnforcementMode) mode;
            } else if (mode instanceof String) {
                this.enforcementMode = SchemaEnforcementMode.valueOf((String) mode);
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setSchema(GraphSchema schema) {
        this.schema = schema;
    }

    public void setEnforcementMode(SchemaEnforcementMode enforcementMode) {
        this.enforcementMode = enforcementMode;
    }

    @Override
    public StageMetrics getMetrics() {
        return metrics;
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void reset() {
        cancelled.set(false);
        metrics.reset();
        chunksToProcess = null;
    }

    /**
     * Output from the graph building stage.
     */
    public record GraphBuildingOutput(
            int entitiesExtracted,
            int relationshipsExtracted,
            int batchCount,
            long graphBuildingTimeMs,
            String embeddingModelUsed,
            String chunkerUsed,
            String loaderUsed,
            String taskId,
            Map<String, Object> metadata
    ) {
        public double entitiesPerSecond() {
            return graphBuildingTimeMs > 0 ? (entitiesExtracted * 1000.0) / graphBuildingTimeMs : 0;
        }

        public int totalGraphElements() {
            return entitiesExtracted + relationshipsExtracted;
        }
    }
}
