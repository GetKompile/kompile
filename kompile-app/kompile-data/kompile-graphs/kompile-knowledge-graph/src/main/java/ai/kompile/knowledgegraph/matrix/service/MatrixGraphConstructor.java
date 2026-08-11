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
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.core.crawl.graph.AgentCallContext;
import ai.kompile.core.crawl.graph.GraphExtractionValidationPolicy;
import ai.kompile.core.crawl.graph.LlmTranscriptLogger;
import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.graphrag.GraphConstructor;
import ai.kompile.core.graphrag.format.GraphExtractionSchema;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.format.GraphExtractionValidator.ValidationResult;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.core.llm.fallback.LlmFallbackExecutor;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import ai.kompile.knowledgegraph.matrix.store.MatrixGraphStore;
import ai.kompile.knowledgegraph.builder.dto.ExtractedGraphDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Matrix-based implementation of GraphConstructor.
 * <p>
 * Constructs graphs using an LLM for entity/relationship extraction and
 * stores the results in a matrix-based graph backed by a vector store.
 * </p>
 * <p>
 * This is the default graph constructor. LLMChat and EmbeddingModel are optional;
 * if not available, graph construction from raw text is not supported but
 * programmatic graph building still works.
 * </p>
 */
@Service
@Slf4j
public class MatrixGraphConstructor implements GraphConstructor {

    @Autowired
    private MatrixGraphStore graphStore;
    @Autowired(required = false)
    private LLMChat llmChat;
    @Autowired(required = false)
    private EmbeddingModel embeddingModel;
    @Autowired
    private ObjectMapper objectMapper;
    /**
     * Optional transcript logger — wired by kompile-app-main where JobLogService is available.
     * When absent (e.g. test slices, subprocess contexts) transcript recording is silently skipped.
     */
    @Autowired(required = false)
    private LlmTranscriptLogger transcriptLogger;

    /**
     * Optional model-fallback executor injected by kompile-app-main.
     * When present, LLM calls in {@code extractBatched} are routed through this executor
     * which automatically retries with the next agent/model on timeout or throttle signals.
     * When absent, a direct {@code llmChat} call is used as before.
     */
    @Autowired(required = false)
    private LlmFallbackExecutor fallbackExecutor;

    private volatile GraphExtractionValidationPolicy validationPolicy =
            GraphExtractionValidationPolicy.defaults();
    private volatile ExtractionModelConfig extractionModelConfig = ExtractionModelConfig.defaults();

    /** No-arg constructor for Spring. */
    public MatrixGraphConstructor() {}

    /** Constructor for tests. */
    public MatrixGraphConstructor(MatrixGraphStore graphStore, LLMChat llmChat,
                                   EmbeddingModel embeddingModel, ObjectMapper objectMapper) {
        this.graphStore = graphStore;
        this.llmChat = llmChat;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public void configure(ExtractionModelConfig config) {
        this.extractionModelConfig = config == null ? ExtractionModelConfig.defaults() : config;
    }

    @Override
    public void configureValidation(GraphExtractionValidationPolicy policy) {
        this.validationPolicy = policy == null
                ? GraphExtractionValidationPolicy.defaults()
                : policy.copy();
    }

    /**
     * Default edge type for entity relationships.
     */
    private static final String ENTITY_EDGE_TYPE = "RELATED_TO";

    @Override
    public Graph constructGraph(String collectionName) throws IOException {
        log.warn("constructGraph(collectionName) requires an IndexerService to fetch documents. " +
                "Use constructGraphFromDocs for direct document processing.");
        return new Graph();
    }

    @Override
    public Graph constructGraphFromDocs(List<RetrievedDoc> docs, GraphSchema schema,
                                         SchemaEnforcementMode enforcementMode) {
        String graphId = MatrixKnowledgeGraphService.graphIdForFactSheet(null);
        AdjacencyMatrixGraph matrixGraph = graphStore.createGraph(graphId, null);

        List<ExtractedGraphDTO.ExtractedEntity> allEntities = new ArrayList<>();
        List<ExtractedGraphDTO.ExtractedRelationship> allRelationships = new ArrayList<>();
        SchemaEnforcementMode mode = (enforcementMode == null) ? SchemaEnforcementMode.NONE : enforcementMode;

        // Batch documents into a single LLM call for efficiency.
        // LLMs have large context windows — sending multiple docs per call
        // drastically reduces API round-trips and avoids rate limiting.
        extractBatched(docs, schema, mode, allEntities, allRelationships, null, null);

        // Add entities to matrix graph
        List<String> nodeIds = new ArrayList<>();
        List<String> textForEmbedding = new ArrayList<>();

        for (ExtractedGraphDTO.ExtractedEntity entity : allEntities) {
            MatrixGraphNode node = MatrixGraphNode.builder()
                    .nodeId(entity.getId())
                    .nodeType(entity.getNodeLabel() != null ? entity.getNodeLabel() : "ENTITY")
                    .title(entity.getTitle())
                    .description(entity.getDescription())
                    .metadata(entity.getMetadata())
                    .build();

            graphStore.addNode(graphId, node);
            nodeIds.add(entity.getId());
            textForEmbedding.add(entity.getTitle() + " " +
                    (entity.getDescription() != null ? entity.getDescription() : ""));
        }

        // Generate and store embeddings
        if (!textForEmbedding.isEmpty() && embeddingModel != null) {
            try {
                INDArray embeddings = embeddingModel.embed(textForEmbedding);
                graphStore.storeNodeEmbeddings(graphId, nodeIds, embeddings);
            } catch (Exception e) {
                log.error("Failed to generate embeddings for entities", e);
            }
        }

        // Add relationships to matrix graph
        for (ExtractedGraphDTO.ExtractedRelationship rel : allRelationships) {
            double weight = rel.getWeight() != null ? rel.getWeight() : 1.0;
            String edgeType = rel.getRelationshipType() != null ? rel.getRelationshipType() : ENTITY_EDGE_TYPE;

            graphStore.addEdge(graphId, rel.getSource(), rel.getTarget(), weight, edgeType, false,
                    rel.getRelationshipType());
        }

        // Save the graph
        try {
            Optional<AdjacencyMatrixGraph> loadedGraph = graphStore.loadGraph(graphId);
            if (loadedGraph.isPresent()) {
                graphStore.saveGraph(loadedGraph.get());
            }
        } catch (IOException e) {
            log.error("Failed to save constructed graph", e);
        }

        // Convert to core Graph model
        return convertToGraph(allEntities, allRelationships);
    }

    /**
     * Constructs a graph and returns both the core Graph model and the matrix graph ID.
     *
     * @param docs            Documents to process
     * @param schema          Optional schema
     * @param enforcementMode Schema enforcement mode
     * @param factSheetId     Optional fact sheet ID for scoping
     * @return GraphConstructionResult with both graph ID and Graph object
     */
    public GraphConstructionResult constructGraphWithId(List<RetrievedDoc> docs, GraphSchema schema,
                                                         SchemaEnforcementMode enforcementMode,
                                                         Long factSheetId) {
        String graphId = MatrixKnowledgeGraphService.graphIdForFactSheet(factSheetId);
        AdjacencyMatrixGraph matrixGraph = graphStore.createGraph(graphId, factSheetId);

        List<ExtractedGraphDTO.ExtractedEntity> allEntities = new ArrayList<>();
        List<ExtractedGraphDTO.ExtractedRelationship> allRelationships = new ArrayList<>();
        SchemaEnforcementMode mode = (enforcementMode == null) ? SchemaEnforcementMode.NONE : enforcementMode;

        extractBatched(docs, schema, mode, allEntities, allRelationships, null, null);

        // Add entities
        List<String> nodeIds = new ArrayList<>();
        List<String> textForEmbedding = new ArrayList<>();

        for (ExtractedGraphDTO.ExtractedEntity entity : allEntities) {
            MatrixGraphNode node = MatrixGraphNode.builder()
                    .nodeId(entity.getId())
                    .nodeType(entity.getNodeLabel() != null ? entity.getNodeLabel() : "ENTITY")
                    .title(entity.getTitle())
                    .description(entity.getDescription())
                    .metadata(entity.getMetadata())
                    .factSheetId(factSheetId)
                    .build();

            graphStore.addNode(graphId, node);
            nodeIds.add(entity.getId());
            textForEmbedding.add(entity.getTitle() + " " +
                    (entity.getDescription() != null ? entity.getDescription() : ""));
        }

        // Embeddings
        if (!textForEmbedding.isEmpty() && embeddingModel != null) {
            try {
                INDArray embeddings = embeddingModel.embed(textForEmbedding);
                graphStore.storeNodeEmbeddings(graphId, nodeIds, embeddings);
            } catch (Exception e) {
                log.error("Failed to generate embeddings", e);
            }
        }

        // Add relationships
        for (ExtractedGraphDTO.ExtractedRelationship rel : allRelationships) {
            double weight = rel.getWeight() != null ? rel.getWeight() : 1.0;
            String edgeType = rel.getRelationshipType() != null ? rel.getRelationshipType() : ENTITY_EDGE_TYPE;
            graphStore.addEdge(graphId, rel.getSource(), rel.getTarget(), weight, edgeType, false,
                    rel.getRelationshipType());
        }

        // Save
        try {
            Optional<AdjacencyMatrixGraph> loadedGraph = graphStore.loadGraph(graphId);
            if (loadedGraph.isPresent()) {
                graphStore.saveGraph(loadedGraph.get());
            }
        } catch (IOException e) {
            log.error("Failed to save graph", e);
        }

        return new GraphConstructionResult(graphId, convertToGraph(allEntities, allRelationships));
    }

    /**
     * Result of graph construction including the graph ID.
     */
    public record GraphConstructionResult(String graphId, Graph graph) {}

    /**
     * Maximum characters per batched LLM prompt for the constructor's internal re-batching.
     *
     * <p>This is a <em>pass-through safety ceiling</em> only — the caller
     * ({@link ai.kompile.crawl.graph.GraphExtractionOrchestrator}) is the real sizing authority.
     * It hands us output-token-safe, adaptively-sized batches via the AIMD char sizer, so this
     * value must be large enough to <em>never</em> re-split a batch the caller deliberately sized.</p>
     *
     * <p>Raised from 300 000 to 3 000 000 chars (≈ 750 000 tokens) so a 1 M-token-context model
     * (e.g. DeepSeek V4 / opencode-cli) receives the full orchestrator batch in a single LLM call
     * rather than being silently re-split here. The orchestrator's own yield-gated AIMD sizer
     * (starting at ~10 % of {@code maxInputChars} ≈ 396 k chars for DeepSeek) enforces the
     * real per-call budget; this constant just ensures the constructor does not undo that work.
     * For small-context models the orchestrator batch is already small, so this cap is never hit.</p>
     */
    private static final int BATCH_PROMPT_MAX_CHARS = 3_000_000;

    /**
     * Extracts entities and relationships from docs by batching multiple documents
     * into single LLM calls. This drastically reduces the number of API round-trips
     * compared to one-doc-per-call, which is critical for CLI/subprocess-based LLMs
     * where each call has significant overhead and rate limits apply.
     */
    private void extractBatched(List<RetrievedDoc> docs, GraphSchema schema,
                                SchemaEnforcementMode mode,
                                List<ExtractedGraphDTO.ExtractedEntity> allEntities,
                                List<ExtractedGraphDTO.ExtractedRelationship> allRelationships,
                                ProgressListener progressListener,
                                ExtractionTaskContext taskContext) {
        // Partition docs into batches that fit within the prompt size limit
        List<List<RetrievedDoc>> batches = new ArrayList<>();
        List<RetrievedDoc> currentBatch = new ArrayList<>();
        int currentChars = 0;

        for (RetrievedDoc doc : docs) {
            int docChars = doc.getText() != null ? doc.getText().length() : 0;
            if (!currentBatch.isEmpty() && currentChars + docChars > BATCH_PROMPT_MAX_CHARS) {
                batches.add(currentBatch);
                currentBatch = new ArrayList<>();
                currentChars = 0;
            }
            currentBatch.add(doc);
            currentChars += docChars;
        }
        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }

        log.info("Batched {} documents into {} LLM call(s) (max {}k chars/call)",
                docs.size(), batches.size(), BATCH_PROMPT_MAX_CHARS / 1000);

        // Capture jobId and sessionId HERE, before any LLM call can clear them.
        // CliAgentLLMChat.executeAgent() calls AgentCallContext.clear() at the start of every
        // invocation (to reset pooled-thread state), which wipes the jobId that
        // GraphExtractionOrchestrator published on this worker thread via setJobId().
        // Reading them once before the batch loop guarantees the transcript guard sees the
        // correct values even after the first LLM call clears the ThreadLocal.
        final String capturedJobId = AgentCallContext.getJobId();
        final String capturedSessionId = AgentCallContext.getSessionId();

        int docIndex = 0;
        for (List<RetrievedDoc> batch : batches) {
            long batchStart = System.currentTimeMillis();
            String prompt = null;
            String jsonResponse = null;
            String llmErrorMsg = null;
            boolean llmSuccess = false;
            ExtractedGraphDTO.ExtractedGraph extracted = null;
            try {
                if (taskContext != null) {
                    extracted = extractPartitionBatchInPhases(batch, schema, mode, taskContext,
                            capturedJobId, capturedSessionId);
                } else {
                    if (batch.size() == 1) {
                        prompt = createExtractionPrompt(batch.get(0).getText(), schema, null);
                    } else {
                        prompt = createBatchExtractionPrompt(batch, schema, null);
                    }

                    // Restore jobId/sessionId on this thread immediately before the LLM call so
                    // that any downstream code (including the LLM adapter itself) can read it.
                    // The finally block below uses the captured values directly, which is safe even
                    // if the LLM adapter clears the ThreadLocal during the call.
                    if (capturedJobId != null) AgentCallContext.setJobId(capturedJobId);
                    if (capturedSessionId != null) AgentCallContext.setSessionId(capturedSessionId);

                    long llmCallStart = System.currentTimeMillis();
                    try {
                        if (fallbackExecutor != null) {
                            jsonResponse = fallbackExecutor.executeWithFallback(prompt, "graph-constructor",
                                    response -> isValidExtractionResponse(response, schema, mode));
                        } else {
                            jsonResponse = llmChat.prompt().user(prompt).call().content();
                        }
                        llmSuccess = jsonResponse != null && !jsonResponse.isBlank();
                    } catch (Exception llmEx) {
                        llmErrorMsg = llmEx.getMessage() != null ? llmEx.getMessage() : llmEx.getClass().getSimpleName();
                        throw llmEx;
                    } finally {
                        // Persist one transcript entry per LLM call so every GraphConstructor
                        // extraction appears in the unified-crawl transcript viewer.
                        // Uses capturedJobId (snapshotted before the loop) because
                        // CliAgentLLMChat.executeAgent() calls AgentCallContext.clear() which
                        // would otherwise make getJobId() return null here.
                        if (transcriptLogger != null && capturedJobId != null) {
                            log.debug("Recording GraphConstructor LLM transcript for job {} (session {})",
                                    capturedJobId, capturedSessionId);
                            long llmLatencyMs = System.currentTimeMillis() - llmCallStart;
                            String backendId = "graph-constructor";
                            try {
                                transcriptLogger.logTranscript(
                                        capturedJobId,
                                        backendId,
                                        "llm",
                                        prompt,
                                        jsonResponse,
                                        llmLatencyMs,
                                        llmSuccess,
                                        llmErrorMsg,
                                        capturedSessionId);
                            } catch (Exception transcriptEx) {
                                log.debug("Failed to persist GraphConstructor LLM transcript for job {}: {}",
                                        capturedJobId, transcriptEx.getMessage());
                            }
                        }
                        // Clear ThreadLocal to avoid leaking into the next task that reuses this thread.
                        AgentCallContext.clear();
                    }

                    extracted = parseExtractionResponse(jsonResponse);
                }

                if (extracted != null) {
                    if (mode == SchemaEnforcementMode.STRICT && schema != null) {
                        cleanGraph(extracted, schema);
                    } else if (extracted.getRelationships() != null && !extracted.getRelationships().isEmpty()) {
                        pruneInvalidRelationshipsForNonStrictMode(extracted);
                    }

                    ValidationResult validation = validateExtractedGraph(extracted, schema, mode);
                    if (!validation.valid()) {
                        throw new IllegalArgumentException(
                                "Graph extraction validation failed: " + validationFeedback(validation.errors()));
                    }
                    if (!validation.warnings().isEmpty()) {
                        log.warn("Accepted Matrix graph extraction with {} validation warning(s): {}",
                                validation.warnings().size(), validationFeedback(validation.warnings()));
                    }

                    // For batched extraction, prefix each entity/rel with a batch-unique prefix
                    // to avoid ID collisions across documents
                    if (extracted.getEntities() != null) {
                        for (ExtractedGraphDTO.ExtractedEntity entity : extracted.getEntities()) {
                            if (entity.getMetadata() == null) {
                                entity.setMetadata(new HashMap<>());
                            }
                            allEntities.add(entity);
                        }
                    }
                    if (extracted.getRelationships() != null) {
                        for (ExtractedGraphDTO.ExtractedRelationship rel : extracted.getRelationships()) {
                            if (rel.getMetadata() == null) {
                                rel.setMetadata(new HashMap<>());
                            }
                            allRelationships.add(rel);
                        }
                    }
                }

                long elapsed = System.currentTimeMillis() - batchStart;
                int batchEntities = extracted != null && extracted.getEntities() != null ? extracted.getEntities().size() : 0;
                int batchRels = extracted != null && extracted.getRelationships() != null ? extracted.getRelationships().size() : 0;
                log.info("Batch extraction: {} docs → {} entities, {} rels in {}ms",
                        batch.size(), batchEntities, batchRels, elapsed);

                // Report progress for each doc in the batch
                if (progressListener != null) {
                    for (RetrievedDoc doc : batch) {
                        progressListener.onProgress(new DocumentExtractionProgress(
                                doc.getId(), docIndex++, docs.size(),
                                DocumentExtractionStatus.COMPLETED, null,
                                batchEntities / batch.size(), batchRels / batch.size(),
                                doc.getText() != null ? doc.getText().length() : 0,
                                elapsed));
                    }
                } else {
                    docIndex += batch.size();
                }
            } catch (Exception e) {
                log.error("Failed to process batch of {} documents: {}", batch.size(), e.getMessage(), e);
                if (progressListener != null) {
                    for (RetrievedDoc doc : batch) {
                        progressListener.onProgress(new DocumentExtractionProgress(
                                doc.getId(), docIndex++, docs.size(),
                                DocumentExtractionStatus.FAILED, e.getMessage(),
                                0, 0, doc.getText() != null ? doc.getText().length() : 0, 0));
                    }
                } else {
                    docIndex += batch.size();
                }
            }
        }
    }

    /**
     * Partition work is deliberately decomposed into two bounded model decisions. The first call
     * can only identify entities. The engine then replaces all new model-chosen ids with stable
     * ids before the second call is allowed to choose relationships over that fixed ballot.
     */
    private ExtractedGraphDTO.ExtractedGraph extractPartitionBatchInPhases(
            List<RetrievedDoc> batch,
            GraphSchema schema,
            SchemaEnforcementMode mode,
            ExtractionTaskContext taskContext,
            String capturedJobId,
            String capturedSessionId) throws JsonProcessingException {
        String entityPrompt = createEntityPhasePrompt(batch, schema, taskContext);
        String entityResponse = executePartitionPhase(entityPrompt, "graph-constructor/entities",
                response -> isValidEntityPhaseResponse(response, schema, mode, batch, taskContext),
                capturedJobId, capturedSessionId);
        ExtractedGraphDTO.ExtractedGraph entityGraph = parseExtractionResponse(entityResponse);
        if (entityGraph == null) {
            throw new IllegalArgumentException("Partition entity phase returned no graph JSON");
        }
        fixPartitionEntityIds(entityGraph, batch, taskContext);
        entityGraph.setRelationships(new ArrayList<>());
        validatePhaseGraph(entityGraph, schema, mode, "entity");

        if (entityGraph.getEntities() == null || entityGraph.getEntities().isEmpty()) {
            return entityGraph;
        }

        try {
            String relationPrompt = createRelationshipPhasePrompt(batch, schema, taskContext,
                    entityGraph.getEntities());
            String relationResponse = executePartitionPhase(
                    relationPrompt,
                    "graph-constructor/relationships",
                    response -> isValidRelationshipPhaseResponse(response, entityGraph.getEntities(),
                            schema, mode),
                    capturedJobId,
                    capturedSessionId);
            ExtractedGraphDTO.ExtractedGraph relationOnly = parseExtractionResponse(relationResponse);
            if (relationOnly == null) {
                throw new IllegalArgumentException("Partition relationship phase returned no graph JSON");
            }
            ExtractedGraphDTO.ExtractedGraph combined = new ExtractedGraphDTO.ExtractedGraph();
            combined.setEntities(new ArrayList<>(entityGraph.getEntities()));
            combined.setRelationships(relationOnly.getRelationships() == null
                    ? new ArrayList<>() : new ArrayList<>(relationOnly.getRelationships()));
            validatePhaseGraph(combined, schema, mode, "relationship");
            return combined;
        } catch (Exception relationFailure) {
            // Entity evidence is still useful and already validated. Do not erase it merely because
            // the smaller model could not complete the independent relationship decision.
            log.warn("Partition relationship phase failed; preserving {} validated entities: {}",
                    entityGraph.getEntities().size(), relationFailure.getMessage());
            return entityGraph;
        }
    }

    private String executePartitionPhase(String prompt,
                                         String backendId,
                                         Predicate<String> validator,
                                         String capturedJobId,
                                         String capturedSessionId) {
        String response = null;
        String errorMessage = null;
        boolean success = false;
        if (capturedJobId != null) AgentCallContext.setJobId(capturedJobId);
        if (capturedSessionId != null) AgentCallContext.setSessionId(capturedSessionId);
        long started = System.currentTimeMillis();
        try {
            if (fallbackExecutor != null) {
                response = fallbackExecutor.executeWithFallback(prompt, backendId, validator);
            } else {
                response = llmChat.prompt().user(prompt).call().content();
            }
            success = response != null && !response.isBlank();
            return response;
        } catch (Exception failure) {
            errorMessage = failure.getMessage() != null
                    ? failure.getMessage() : failure.getClass().getSimpleName();
            throw failure;
        } finally {
            if (transcriptLogger != null && capturedJobId != null) {
                try {
                    transcriptLogger.logTranscript(
                            capturedJobId,
                            backendId,
                            "llm",
                            prompt,
                            response,
                            System.currentTimeMillis() - started,
                            success,
                            errorMessage,
                            capturedSessionId);
                } catch (Exception transcriptFailure) {
                    log.debug("Failed to persist {} transcript for job {}: {}", backendId,
                            capturedJobId, transcriptFailure.getMessage());
                }
            }
            AgentCallContext.clear();
        }
    }

    private boolean isValidEntityPhaseResponse(String response,
                                               GraphSchema schema,
                                               SchemaEnforcementMode mode,
                                               List<RetrievedDoc> batch,
                                               ExtractionTaskContext taskContext) {
        ExtractedGraphDTO.ExtractedGraph graph = parseExtractionResponse(response);
        if (graph == null) {
            return false;
        }
        fixPartitionEntityIds(graph, batch, taskContext);
        graph.setRelationships(new ArrayList<>());
        return isValidPhaseGraph(graph, schema, mode, "entity");
    }

    private boolean isValidRelationshipPhaseResponse(
            String response,
            List<ExtractedGraphDTO.ExtractedEntity> fixedEntities,
            GraphSchema schema,
            SchemaEnforcementMode mode) {
        ExtractedGraphDTO.ExtractedGraph relationOnly = parseExtractionResponse(response);
        if (relationOnly == null) {
            return false;
        }
        ExtractedGraphDTO.ExtractedGraph combined = new ExtractedGraphDTO.ExtractedGraph();
        combined.setEntities(new ArrayList<>(fixedEntities));
        combined.setRelationships(relationOnly.getRelationships() == null
                ? new ArrayList<>() : new ArrayList<>(relationOnly.getRelationships()));
        return isValidPhaseGraph(combined, schema, mode, "relationship");
    }

    private boolean isValidPhaseGraph(ExtractedGraphDTO.ExtractedGraph graph,
                                      GraphSchema schema,
                                      SchemaEnforcementMode mode,
                                      String phase) {
        if (mode == SchemaEnforcementMode.STRICT && schema != null) {
            cleanGraph(graph, schema);
        } else if (graph.getRelationships() != null && !graph.getRelationships().isEmpty()) {
            pruneInvalidRelationshipsForNonStrictMode(graph);
        }
        if ("relationship".equals(phase) && !relationshipTypesArePredicates(graph, schema)) {
            log.debug("Rejecting partition relationship phase response: a relationship type reused an entity label");
            return false;
        }
        ValidationResult validation = validateExtractedGraph(graph, schema, mode);
        if (!validation.valid()) {
            log.debug("Rejecting partition {} phase response: {}", phase,
                    validationFeedback(validation.errors()));
        }
        return validation.valid();
    }

    private void validatePhaseGraph(ExtractedGraphDTO.ExtractedGraph graph,
                                    GraphSchema schema,
                                    SchemaEnforcementMode mode,
                                    String phase) {
        if (mode == SchemaEnforcementMode.STRICT && schema != null) {
            cleanGraph(graph, schema);
        } else if (graph.getRelationships() != null && !graph.getRelationships().isEmpty()) {
            pruneInvalidRelationshipsForNonStrictMode(graph);
        }
        if ("relationship".equals(phase) && !relationshipTypesArePredicates(graph, schema)) {
            throw new IllegalArgumentException("Partition relationship phase validation failed: "
                    + "relationship type must be a predicate, not an entity label");
        }
        ValidationResult validation = validateExtractedGraph(graph, schema, mode);
        if (!validation.valid()) {
            throw new IllegalArgumentException("Partition " + phase + " phase validation failed: "
                    + validationFeedback(validation.errors()));
        }
        if (!validation.warnings().isEmpty()) {
            log.warn("Accepted partition {} phase with {} validation warning(s): {}", phase,
                    validation.warnings().size(), validationFeedback(validation.warnings()));
        }
    }

    private boolean relationshipTypesArePredicates(ExtractedGraphDTO.ExtractedGraph graph,
                                                    GraphSchema schema) {
        Set<String> entityLabels = graph.getEntities() == null ? Set.of() : graph.getEntities().stream()
                .filter(Objects::nonNull)
                .map(ExtractedGraphDTO.ExtractedEntity::getNodeLabel)
                .filter(Objects::nonNull)
                .map(this::normalizeTypeToken)
                .filter(label -> !label.isBlank())
                .collect(Collectors.toSet());
        Set<String> configuredRelationshipTypes = schema == null || schema.getRelationshipTypes() == null
                ? Set.of() : schema.getRelationshipTypes().stream()
                .filter(Objects::nonNull)
                .map(type -> type.getType())
                .filter(Objects::nonNull)
                .map(this::normalizeTypeToken)
                .filter(type -> !type.isBlank())
                .collect(Collectors.toSet());
        return graph.getRelationships() == null || graph.getRelationships().stream()
                .filter(Objects::nonNull)
                .map(ExtractedGraphDTO.ExtractedRelationship::getRelationshipType)
                .filter(Objects::nonNull)
                .map(this::normalizeTypeToken)
                .allMatch(type -> !entityLabels.contains(type) || configuredRelationshipTypes.contains(type));
    }

    private String normalizeTypeToken(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private void fixPartitionEntityIds(ExtractedGraphDTO.ExtractedGraph graph,
                                       List<RetrievedDoc> batch,
                                       ExtractionTaskContext taskContext) {
        List<ExtractedGraphDTO.ExtractedEntity> source = graph.getEntities() == null
                ? List.of() : graph.getEntities();
        Set<String> reusableIds = existingGraphEntityIds(taskContext == null
                ? null : taskContext.graphContext());
        LinkedHashMap<String, ExtractedGraphDTO.ExtractedEntity> unique = new LinkedHashMap<>();
        for (ExtractedGraphDTO.ExtractedEntity entity : source) {
            if (entity == null) {
                continue;
            }
            normalizePartitionEntity(entity, batch, taskContext);
            String proposed = entity.getId() == null ? "" : entity.getId().strip();
            String fixedId = reusableIds.contains(proposed)
                    ? proposed
                    : stableEntityId(entity, batch, taskContext);
            entity.setId(fixedId);
            unique.putIfAbsent(fixedId, entity);
        }
        graph.setEntities(new ArrayList<>(unique.values()));
    }

    private void normalizePartitionEntity(ExtractedGraphDTO.ExtractedEntity entity,
                                          List<RetrievedDoc> batch,
                                          ExtractionTaskContext taskContext) {
        String groundedTitle = sourceGroundedSubject(entity.getTitle(), batch, taskContext);
        if (groundedTitle != null) {
            entity.setTitle(groundedTitle);
        }
        String rawLabel = entity.getNodeLabel();
        String normalizedLabel = rawLabel == null ? "" : rawLabel.strip()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        entity.setNodeLabel(normalizedLabel.isBlank() ? "ENTITY" : normalizedLabel);
        if (entity.getDescription() == null || entity.getDescription().isBlank()) {
            entity.setDescription(entity.getTitle());
        }
    }

    private String sourceGroundedSubject(String proposedTitle,
                                         List<RetrievedDoc> batch,
                                         ExtractionTaskContext taskContext) {
        if (proposedTitle == null || proposedTitle.isBlank()
                || taskContext == null || taskContext.subjects() == null
                || taskContext.subjects().isEmpty()) {
            return proposedTitle;
        }
        String proposed = normalizeIdentityComponent(proposedTitle);
        String source = batch.stream().map(RetrievedDoc::getText).filter(Objects::nonNull)
                .collect(Collectors.joining("\n")).toLowerCase(Locale.ROOT);
        List<String> matches = taskContext.subjects().stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(subject -> !subject.isBlank())
                .filter(subject -> source.contains(subject.toLowerCase(Locale.ROOT)))
                .filter(subject -> {
                    String candidate = normalizeIdentityComponent(subject);
                    return candidate.equals(proposed)
                            || (" " + candidate + " ").contains(" " + proposed + " ");
                })
                .distinct()
                .toList();
        return matches.size() == 1 ? matches.get(0) : proposedTitle;
    }

    private String stableEntityId(ExtractedGraphDTO.ExtractedEntity entity,
                                  List<RetrievedDoc> batch,
                                  ExtractionTaskContext taskContext) {
        String scope = taskContext != null && taskContext.corpusSnapshotId() != null
                ? taskContext.corpusSnapshotId()
                : batch.stream().map(RetrievedDoc::getId).filter(Objects::nonNull)
                        .collect(Collectors.joining("|"));
        String identity = normalizeIdentityComponent(entity.getTitle()) + "|"
                + normalizeIdentityComponent(entity.getNodeLabel());
        UUID uuid = UUID.nameUUIDFromBytes((scope + "|" + identity)
                .getBytes(StandardCharsets.UTF_8));
        return "entity-" + uuid;
    }

    private String normalizeIdentityComponent(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private Set<String> existingGraphEntityIds(String graphContext) {
        if (graphContext == null || graphContext.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String line : graphContext.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("- id=")) {
                String candidate = trimmed.substring(5);
                int separator = candidate.indexOf(" |");
                ids.add((separator >= 0 ? candidate.substring(0, separator) : candidate).strip());
            }
            int cursor = 0;
            while ((cursor = trimmed.indexOf("entity[", cursor)) >= 0) {
                int end = trimmed.indexOf(']', cursor + 7);
                if (end < 0) {
                    break;
                }
                ids.add(trimmed.substring(cursor + 7, end).strip());
                cursor = end + 1;
            }
        }
        ids.removeIf(String::isBlank);
        return Set.copyOf(ids);
    }

    @Override
    public Graph constructGraphFromDocs(List<RetrievedDoc> docs, GraphSchema graphSchema,
                                         SchemaEnforcementMode enforcementMode,
                                         boolean skipEmbedding, boolean skipMatrixGraph,
                                         ProgressListener progressListener) {
        String graphId = skipMatrixGraph ? null : MatrixKnowledgeGraphService.graphIdForFactSheet(null);
        if (!skipMatrixGraph) {
            graphStore.createGraph(graphId, null);
        }

        List<ExtractedGraphDTO.ExtractedEntity> allEntities = new ArrayList<>();
        List<ExtractedGraphDTO.ExtractedRelationship> allRelationships = new ArrayList<>();
        SchemaEnforcementMode mode = (enforcementMode == null) ? SchemaEnforcementMode.NONE : enforcementMode;

        extractBatched(docs, graphSchema, mode, allEntities, allRelationships, progressListener,
                null);

        if (!skipMatrixGraph && graphId != null) {
            List<String> nodeIds = new ArrayList<>();
            List<String> textForEmbedding = new ArrayList<>();

            for (ExtractedGraphDTO.ExtractedEntity entity : allEntities) {
                MatrixGraphNode node = MatrixGraphNode.builder()
                        .nodeId(entity.getId())
                        .nodeType(entity.getNodeLabel() != null ? entity.getNodeLabel() : "ENTITY")
                        .title(entity.getTitle())
                        .description(entity.getDescription())
                        .metadata(entity.getMetadata())
                        .build();
                graphStore.addNode(graphId, node);
                nodeIds.add(entity.getId());
                textForEmbedding.add(entity.getTitle() + " " +
                        (entity.getDescription() != null ? entity.getDescription() : ""));
            }

            if (!skipEmbedding && !textForEmbedding.isEmpty() && embeddingModel != null) {
                try {
                    INDArray embeddings = embeddingModel.embed(textForEmbedding);
                    graphStore.storeNodeEmbeddings(graphId, nodeIds, embeddings);
                } catch (Exception e) {
                    log.error("Failed to generate embeddings for entities", e);
                }
            }

            for (ExtractedGraphDTO.ExtractedRelationship rel : allRelationships) {
                double weight = rel.getWeight() != null ? rel.getWeight() : 1.0;
                String edgeType = rel.getRelationshipType() != null ? rel.getRelationshipType() : ENTITY_EDGE_TYPE;
                graphStore.addEdge(graphId, rel.getSource(), rel.getTarget(), weight, edgeType, false,
                    rel.getRelationshipType());
            }

            try {
                Optional<AdjacencyMatrixGraph> loadedGraph = graphStore.loadGraph(graphId);
                if (loadedGraph.isPresent()) {
                    graphStore.saveGraph(loadedGraph.get());
                }
            } catch (IOException e) {
                log.error("Failed to save constructed graph", e);
            }
        }

        return convertToGraph(allEntities, allRelationships);
    }

    @Override
    public Graph constructGraphFromDocs(List<RetrievedDoc> docs, GraphSchema graphSchema,
                                         SchemaEnforcementMode enforcementMode,
                                         boolean skipEmbedding, boolean skipMatrixGraph,
                                         ProgressListener progressListener,
                                         ExtractionTaskContext taskContext) {
        String graphId = skipMatrixGraph ? null : MatrixKnowledgeGraphService.graphIdForFactSheet(null);
        if (!skipMatrixGraph) {
            graphStore.createGraph(graphId, null);
        }

        List<ExtractedGraphDTO.ExtractedEntity> entities = new ArrayList<>();
        List<ExtractedGraphDTO.ExtractedRelationship> relationships = new ArrayList<>();
        SchemaEnforcementMode mode = enforcementMode == null
                ? SchemaEnforcementMode.NONE : enforcementMode;
        extractBatched(docs, graphSchema, mode, entities, relationships, progressListener,
                taskContext);

        if (!skipMatrixGraph && graphId != null) {
            persistExtractedGraph(graphId, entities, relationships, skipEmbedding);
        }
        return convertToGraph(entities, relationships);
    }

    private void persistExtractedGraph(String graphId,
                                       List<ExtractedGraphDTO.ExtractedEntity> entities,
                                       List<ExtractedGraphDTO.ExtractedRelationship> relationships,
                                       boolean skipEmbedding) {
        List<String> nodeIds = new ArrayList<>();
        List<String> textForEmbedding = new ArrayList<>();
        for (ExtractedGraphDTO.ExtractedEntity entity : entities) {
            MatrixGraphNode node = MatrixGraphNode.builder()
                    .nodeId(entity.getId())
                    .nodeType(entity.getNodeLabel() != null ? entity.getNodeLabel() : "ENTITY")
                    .title(entity.getTitle())
                    .description(entity.getDescription())
                    .metadata(entity.getMetadata())
                    .build();
            graphStore.addNode(graphId, node);
            nodeIds.add(entity.getId());
            textForEmbedding.add(entity.getTitle() + " "
                    + (entity.getDescription() != null ? entity.getDescription() : ""));
        }
        if (!skipEmbedding && !textForEmbedding.isEmpty() && embeddingModel != null) {
            try {
                INDArray embeddings = embeddingModel.embed(textForEmbedding);
                graphStore.storeNodeEmbeddings(graphId, nodeIds, embeddings);
            } catch (Exception e) {
                log.error("Failed to generate embeddings for entities", e);
            }
        }
        for (ExtractedGraphDTO.ExtractedRelationship relation : relationships) {
            double weight = relation.getWeight() != null ? relation.getWeight() : 1.0;
            String edgeType = relation.getRelationshipType() != null
                    ? relation.getRelationshipType() : ENTITY_EDGE_TYPE;
            graphStore.addEdge(graphId, relation.getSource(), relation.getTarget(), weight,
                    edgeType, false, relation.getRelationshipType());
        }
        try {
            Optional<AdjacencyMatrixGraph> loadedGraph = graphStore.loadGraph(graphId);
            if (loadedGraph.isPresent()) {
                graphStore.saveGraph(loadedGraph.get());
            }
        } catch (IOException e) {
            log.error("Failed to save constructed graph", e);
        }
    }

    /**
     * Creates a batched prompt with no semantic example facts that a small model
     * could copy into the extraction.
     */
    private String createBatchExtractionPrompt(List<RetrievedDoc> docs, GraphSchema schema,
                                               ExtractionTaskContext taskContext) {
        StringBuilder docsSection = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            docsSection.append("--- SOURCE DOCUMENT ").append(i + 1).append(" ---\n");
            docsSection.append(docs.get(i).getText() == null ? "" : docs.get(i).getText());
            docsSection.append("\n\n");
        }
        return extractionPromptPreamble(schema, taskContext)
                + "\nSOURCE DOCUMENTS:\n"
                + docsSection
                + extractionPromptOutputContract();
    }

    private String createExtractionPrompt(String text, GraphSchema schema,
                                          ExtractionTaskContext taskContext) {
        return extractionPromptPreamble(schema, taskContext)
                + "\nSOURCE TEXT:\n"
                + (text == null ? "" : text)
                + extractionPromptOutputContract();
    }

    private String createEntityPhasePrompt(List<RetrievedDoc> docs,
                                           GraphSchema schema,
                                           ExtractionTaskContext taskContext) {
        StringBuilder prompt = new StringBuilder("""
                PHASE 1 OF 2 — ENTITY IDENTIFICATION ONLY.
                Identify the distinct real-world entities explicitly mentioned in SOURCE TEXT.
                Do not infer or emit relationships, claims, process steps, or dates in this phase.
                Existing graph context is only an identity ballot: reuse an existing id only when
                SOURCE TEXT clearly refers to that same entity. For every new entity, any temporary
                id is acceptable because the engine will replace it before the next phase.
                Never create an entity from a routing hint or existing graph context alone.
                """);
        prompt.append(getEntitySchemaDescription(schema));
        appendConfiguredContext(prompt, taskContext);
        appendSourceDocuments(prompt, docs);
        prompt.append("""

                OUTPUT CONTRACT FOR PHASE 1:
                - Return exactly one raw JSON object and nothing else.
                - Begin with the exact lowercase characters {"entities":[.
                - The object MUST have exactly one top-level array named "entities".
                - JSON property names remain lowercase exactly as written here. Uppercase formatting applies
                  only to entity label string values, never to JSON keys.
                - Every entity MUST contain string fields "id", "title", "label", and "description".
                - "title" MUST be an exact meaningful source mention.
                - "metadata" is optional and, when present, MUST be a JSON object.
                - Do not emit a "relationships" key in this phase.
                - If SOURCE TEXT supports no entities, return exactly {"entities":[]}.
                """);
        return prompt.toString();
    }

    private String createRelationshipPhasePrompt(
            List<RetrievedDoc> docs,
            GraphSchema schema,
            ExtractionTaskContext taskContext,
            List<ExtractedGraphDTO.ExtractedEntity> fixedEntities) throws JsonProcessingException {
        StringBuilder prompt = new StringBuilder("""
                PHASE 2 OF 2 — RELATIONSHIP IDENTIFICATION ONLY.
                Decide which relationships are explicitly supported by SOURCE TEXT between the
                engine-fixed entity candidates below. The candidate list is an endpoint ballot,
                not evidence. Do not create entities, rename ids, or use an endpoint outside it.
                Abstain whenever the same source passage does not support both endpoints and the
                relationship. Existing graph context never proves a new relationship.
                The relationship "type" answers how source relates to target. Normalize an explicit
                source predicate or action; never copy either candidate's entity label or category.
                A concept action hint is only a predicate candidate when that term occurs in SOURCE TEXT.
                """);
        prompt.append(getRelationshipSchemaDescription(schema));
        prompt.append(GraphExtractionValidator.semanticPromptInstructions(validationPolicy, schema));
        appendConfiguredContext(prompt, taskContext);
        prompt.append("\nENGINE-FIXED ENTITY CANDIDATES (use these ids exactly):\n")
                .append(objectMapper.writeValueAsString(fixedEntities)).append('\n');
        appendSourceDocuments(prompt, docs);
        prompt.append("""

                OUTPUT CONTRACT FOR PHASE 2:
                - Return exactly one raw JSON object and nothing else.
                - The object MUST have exactly one top-level array named "relationships".
                - Every relationship MUST contain string fields "source", "target", "type", and "description".
                - "source" and "target" MUST be ids copied exactly from ENGINE-FIXED ENTITY CANDIDATES.
                - "type" MUST name the source-grounded predicate/action, never an entity label or category.
                - "confidence" is optional and, when present, MUST be in [0.0, 1.0].
                - "weight" and ISO-8601 "occurredAt" are optional; "metadata", when present, MUST be an object.
                - Do not emit an "entities" key or repeat candidate objects in this phase.
                - If SOURCE TEXT supports no relationship, return exactly {"relationships":[]}.
                """);
        return prompt.toString();
    }

    private void appendConfiguredContext(StringBuilder prompt, ExtractionTaskContext taskContext) {
        if (extractionModelConfig.customPrompt() != null
                && !extractionModelConfig.customPrompt().isBlank()) {
            prompt.append("\nAdditional project instructions (subject to this phase contract):\n")
                    .append(extractionModelConfig.customPrompt().trim())
                    .append('\n');
        }
        if (taskContext != null) {
            prompt.append(taskContext.promptBlock());
        }
    }

    private void appendSourceDocuments(StringBuilder prompt, List<RetrievedDoc> docs) {
        prompt.append("\nSOURCE TEXT:\n");
        for (int i = 0; i < docs.size(); i++) {
            if (docs.size() > 1) {
                prompt.append("--- SOURCE DOCUMENT ").append(i + 1).append(" ---\n");
            }
            prompt.append(docs.get(i).getText() == null ? "" : docs.get(i).getText())
                    .append('\n');
        }
    }

    private String getEntitySchemaDescription(GraphSchema schema) {
        if (schema == null || schema.getNodeTypes() == null || schema.getNodeTypes().isEmpty()) {
            return "\nEntity label VALUES use precise UPPERCASE_WITH_UNDERSCORES strings; "
                    + "JSON property names remain lowercase.\n";
        }
        StringBuilder description = new StringBuilder("\nAllowed entity labels:\n");
        schema.getNodeTypes().forEach(type -> {
            description.append("- ").append(type.getLabel());
            if (type.getDescription() != null && !type.getDescription().isBlank()) {
                description.append(": ").append(type.getDescription());
            }
            description.append('\n');
        });
        return description.toString();
    }

    private String getRelationshipSchemaDescription(GraphSchema schema) {
        if (schema == null || schema.getRelationshipTypes() == null
                || schema.getRelationshipTypes().isEmpty()) {
            return "\nUse precise UPPERCASE_WITH_UNDERSCORES relationship types.\n";
        }
        StringBuilder description = new StringBuilder("\nAllowed relationship types:\n");
        schema.getRelationshipTypes().forEach(type -> {
            description.append("- ").append(type.getType());
            if (type.getDescription() != null && !type.getDescription().isBlank()) {
                description.append(": ").append(type.getDescription());
            }
            description.append('\n');
        });
        return description.toString();
    }

    private String extractionPromptPreamble(GraphSchema schema,
                                            ExtractionTaskContext taskContext) {
        StringBuilder prompt = new StringBuilder("""
                Extract a knowledge graph ONLY from the source text below.
                Never invent entities, process steps, roles, dates, or relationships.
                If the source supports no graph facts, return {"entities":[],"relationships":[]}.
                Resolve repeated mentions of the same real-world item to one entity id before emitting JSON.
                Emit a relationship only when the same source passage supports its source, relationship, and target.
                """);
        prompt.append(getSchemaDescription(schema));
        prompt.append(GraphExtractionValidator.semanticPromptInstructions(validationPolicy, schema));
        if (extractionModelConfig.customPrompt() != null
                && !extractionModelConfig.customPrompt().isBlank()) {
            prompt.append("\nAdditional project instructions:\n")
                    .append(extractionModelConfig.customPrompt().trim())
                    .append('\n');
        }
        if (taskContext != null) {
            prompt.append(taskContext.promptBlock());
        }
        return prompt.toString();
    }

    private String extractionPromptOutputContract() {
        return """

                OUTPUT CONTRACT:
                - Return exactly one raw JSON object and nothing else. Start with { and end with }.
                - Begin with the exact characters {"entities": and use the lowercase keys shown below exactly.
                - The object MUST have exactly two top-level arrays: "entities" and "relationships". Do not emit top-level "metadata" or any other key.
                - Every entity MUST contain string fields "id", "title", "label", and "description".
                - Entity "title" MUST be an exact meaningful source mention, not a generic category placeholder.
                - Entity "metadata" is optional; when present it MUST be a JSON object, never a string.
                - Every relationship MUST contain string fields "source", "target", "type", and "description".
                - Relationship endpoints MUST reference ids emitted in "entities". If an endpoint reuses an id from EXISTING GRAPH CONTEXT, also emit that canonical entity in "entities"; this is an identity reference, not new evidence.
                - Relationship "confidence" is optional and, when present, MUST be in [0.0, 1.0].
                - Relationship "weight" and ISO-8601 "occurredAt" are optional. Relationship "metadata", when present, MUST be a JSON object, never a string.
                - Populate only this empty shape; do not copy facts from these instructions:
                  {"entities":[],"relationships":[]}
                """;
    }

    private String getSchemaDescription(GraphSchema schema) {
        if (schema == null) {
            return "\nUse precise UPPERCASE_WITH_UNDERSCORES labels and relationship types.\n";
        }

        StringBuilder description = new StringBuilder();
        if (schema.getNodeTypes() != null && !schema.getNodeTypes().isEmpty()) {
            description.append("\nAllowed entity labels:\n");
            schema.getNodeTypes().forEach(type -> {
                description.append("- ").append(type.getLabel());
                if (type.getDescription() != null && !type.getDescription().isBlank()) {
                    description.append(": ").append(type.getDescription());
                }
                description.append('\n');
            });
        }
        if (schema.getRelationshipTypes() != null && !schema.getRelationshipTypes().isEmpty()) {
            description.append("\nAllowed relationship types:\n");
            schema.getRelationshipTypes().forEach(type -> {
                description.append("- ").append(type.getType());
                if (type.getDescription() != null && !type.getDescription().isBlank()) {
                    description.append(": ").append(type.getDescription());
                }
                description.append('\n');
            });
        }
        return description.toString();
    }

    private boolean isValidExtractionResponse(String response,
                                              GraphSchema schema,
                                              SchemaEnforcementMode mode) {
        ExtractedGraphDTO.ExtractedGraph extracted = parseExtractionResponse(response);
        if (extracted == null) {
            return false;
        }
        if (mode == SchemaEnforcementMode.STRICT && schema != null) {
            cleanGraph(extracted, schema);
        } else if (extracted != null && extracted.getRelationships() != null
                && !extracted.getRelationships().isEmpty()) {
            pruneInvalidRelationshipsForNonStrictMode(extracted);
        }
        ValidationResult validation = validateExtractedGraph(extracted, schema, mode);
        if (!validation.valid()) {
            log.debug("Rejecting graph-constructor response: {}", validationFeedback(validation.errors()));
        }
        return validation.valid();
    }

    private ValidationResult validateExtractedGraph(ExtractedGraphDTO.ExtractedGraph extracted,
                                                    GraphSchema schema,
                                                    SchemaEnforcementMode mode) {
        List<GraphExtractionSchema.ExtractedEntity> entities = new ArrayList<>();
        if (extracted != null && extracted.getEntities() != null) {
            for (ExtractedGraphDTO.ExtractedEntity entity : extracted.getEntities()) {
                if (entity == null) {
                    entities.add(null);
                    continue;
                }
                entities.add(new GraphExtractionSchema.ExtractedEntity(
                        entity.getId(),
                        entity.getTitle(),
                        entity.getNodeLabel(),
                        List.of(),
                        entity.getDescription(),
                        null,
                        stringMetadata(entity.getMetadata())));
            }
        }

        List<GraphExtractionSchema.ExtractedRelation> relations = new ArrayList<>();
        if (extracted != null && extracted.getRelationships() != null) {
            for (ExtractedGraphDTO.ExtractedRelationship relation : extracted.getRelationships()) {
                if (relation == null) {
                    relations.add(null);
                    continue;
                }
                Double confidence = relation.getConfidence() != null
                        ? relation.getConfidence()
                        : relation.getWeight();
                relations.add(new GraphExtractionSchema.ExtractedRelation(
                        relation.getSource(),
                        relation.getTarget(),
                        relation.getRelationshipType(),
                        relation.getDescription(),
                        confidence,
                        stringMetadata(relation.getMetadata()),
                        relation.getOccurredAt()));
            }
        }

        return GraphExtractionValidator.validate(
                GraphExtractionSchema.ExtractionResult.of(entities, relations, null),
                validationPolicyForMode(mode),
                schema);
    }

    private GraphExtractionValidationPolicy validationPolicyForMode(SchemaEnforcementMode mode) {
        if (mode == SchemaEnforcementMode.STRICT) {
            return validationPolicy;
        }
        GraphExtractionValidationPolicy base = validationPolicy == null
                ? GraphExtractionValidationPolicy.defaults()
                : validationPolicy;

        List<String> enabledValidators = base.effectiveEnabledValidators().stream()
                .filter(v -> !GraphExtractionValidationPolicy.ENTITY_TYPE_SCHEMA.equals(v))
                .filter(v -> !GraphExtractionValidationPolicy.RELATION_TYPE_SCHEMA.equals(v))
                .toList();

        if (enabledValidators.size() == base.effectiveEnabledValidators().size()) {
            return base;
        }

        return GraphExtractionValidationPolicy.builder()
                .failureMode(base.effectiveFailureMode())
                .enabledValidators(new ArrayList<>(enabledValidators))
                .relationPatterns(base.effectiveRelationPatterns())
                .requiredOccurredAtRelationTypes(base.effectiveRequiredOccurredAtRelationTypes())
                .requirePatternForEveryRelationType(base.isRequirePatternForEveryRelationType())
                .maxErrorsInRetryPrompt(base.effectiveMaxErrorsInRetryPrompt())
                .build();
    }

    private void pruneInvalidRelationshipsForNonStrictMode(ExtractedGraphDTO.ExtractedGraph extracted) {
        Set<String> entityIds = extracted.getEntities() == null
                ? Set.of()
                : extracted.getEntities().stream()
                .filter(Objects::nonNull)
                .map(ExtractedGraphDTO.ExtractedEntity::getId)
                .filter(this::hasText)
                .collect(Collectors.toSet());

        List<ExtractedGraphDTO.ExtractedRelationship> relationships = extracted.getRelationships() == null
                ? List.of()
                : extracted.getRelationships().stream()
                .filter(Objects::nonNull)
                .filter(relation -> hasText(relation.getSource())
                        && hasText(relation.getTarget())
                        && hasText(relation.getRelationshipType())
                        && entityIds.contains(relation.getSource())
                        && entityIds.contains(relation.getTarget()))
                .toList();

        extracted.setRelationships(new ArrayList<>(relationships));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Map<String, String> stringMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (key != null && value != null) {
                values.put(key, value.toString());
            }
        });
        return values;
    }

    private String validationFeedback(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return "Unknown validation failure";
        }
        int limit = validationPolicy.effectiveMaxErrorsInRetryPrompt();
        String feedback = errors.stream().limit(limit).collect(Collectors.joining("; "));
        if (errors.size() > limit) {
            feedback += "; ... and " + (errors.size() - limit) + " more";
        }
        return feedback;
    }

    private ExtractedGraphDTO.ExtractedGraph parseExtractionResponse(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.isBlank()) {
            return null;
        }
        try {
            // 1. Strip markdown code fences: ```json ... ``` or ``` ... ```
            String stripped = jsonResponse;
            int fenceStart = jsonResponse.indexOf("```json");
            if (fenceStart >= 0) {
                int contentStart = jsonResponse.indexOf('\n', fenceStart) + 1;
                int fenceEnd = jsonResponse.indexOf("```", contentStart);
                if (fenceEnd > contentStart) {
                    stripped = jsonResponse.substring(contentStart, fenceEnd).trim();
                }
            } else {
                int fence2 = jsonResponse.indexOf("```");
                if (fence2 >= 0) {
                    int contentStart = jsonResponse.indexOf('\n', fence2) + 1;
                    int fenceEnd = jsonResponse.indexOf("```", contentStart);
                    if (fenceEnd > contentStart) {
                        String candidate = jsonResponse.substring(contentStart, fenceEnd).trim();
                        if (candidate.startsWith("{")) {
                            stripped = candidate;
                        }
                    }
                }
            }

            // 2. Find the first occurrence of {"entities" or {"relationships" to skip tool-call
            //    log prefixes emitted by CLI agents (e.g. "[kompile] read | {"filePath":...}").
            //    We search for the extraction result root object specifically.
            String json = stripped;
            int entitiesStart = stripped.indexOf("{\"entities\"");
            if (entitiesStart < 0) {
                // Also handle single-quoted or spaced variants, and fall back to first '{'
                // that is not a tool-call line (lines starting with "[kompile]" or "[llm]").
                // Walk through the string line by line to find the first '{' on a non-tool line.
                int candidateStart = -1;
                int pos = 0;
                while (pos < stripped.length()) {
                    int lineEnd = stripped.indexOf('\n', pos);
                    if (lineEnd < 0) lineEnd = stripped.length();
                    String line = stripped.substring(pos, lineEnd).trim();
                    if (!line.startsWith("[kompile]") && !line.startsWith("[llm]")
                            && !line.startsWith("[INFO]") && !line.startsWith("[DEBUG]")
                            && !line.startsWith("[WARN]") && !line.startsWith("[ERROR]")
                            && line.contains("{")) {
                        int bracePos = stripped.indexOf('{', pos);
                        if (bracePos >= 0 && bracePos < lineEnd) {
                            candidateStart = bracePos;
                            break;
                        }
                    }
                    pos = lineEnd + 1;
                }
                if (candidateStart >= 0) {
                    int jsonEnd = stripped.lastIndexOf('}');
                    if (jsonEnd > candidateStart) {
                        json = stripped.substring(candidateStart, jsonEnd + 1);
                    }
                }
            } else {
                int jsonEnd = stripped.lastIndexOf('}');
                if (jsonEnd > entitiesStart) {
                    json = stripped.substring(entitiesStart, jsonEnd + 1);
                }
            }

            return objectMapper.readValue(json, ExtractedGraphDTO.ExtractedGraph.class);
        } catch (Exception e) {
            log.error("Failed to parse LLM extraction response: {}", e.getMessage());
            return null;
        }
    }

    private void cleanGraph(ExtractedGraphDTO.ExtractedGraph graph, GraphSchema schema) {
        if (graph == null || schema == null) {
            return;
        }

        Set<String> allowedLabels = schema.getAllNodeLabels();
        Set<String> allowedTypes = schema.getAllRelationshipTypes();

        // Filter entities
        if (graph.getEntities() != null) {
            graph.setEntities(graph.getEntities().stream()
                    .filter(e -> e.getNodeLabel() == null || allowedLabels.contains(e.getNodeLabel()))
                    .collect(Collectors.toList()));
        }

        // Filter relationships
        Set<String> validEntityIds = graph.getEntities() != null
                ? graph.getEntities().stream().map(ExtractedGraphDTO.ExtractedEntity::getId).collect(Collectors.toSet())
                : Collections.emptySet();

        if (graph.getRelationships() != null) {
            graph.setRelationships(graph.getRelationships().stream()
                    .filter(r -> (r.getRelationshipType() == null || allowedTypes.contains(r.getRelationshipType()))
                            && validEntityIds.contains(r.getSource())
                            && validEntityIds.contains(r.getTarget()))
                    .collect(Collectors.toList()));
        }
    }

    /**
     * Returns true if the response string is a provider error (e.g. quota exhausted, API failure),
     * rather than a valid graph extraction response.
     * Detects explicit provider error patterns while ignoring ordinary text that contains
     * quota-related vocabulary (e.g. entity labels like "QUOTA_POLICY").
     */
    private boolean isAgentErrorResponse(String response) {
        if (response == null || response.isBlank()) return false;
        String lower = response.toLowerCase();
        // Explicit quota/capacity exhaustion errors from LLM API providers
        return lower.contains("quotaerror")
                || lower.contains("quota error")
                || (lower.contains("quota") && lower.contains("exhausted"))
                || (lower.contains("quota") && lower.contains("capacity"))
                || lower.contains("you have exhausted your capacity");
    }

    private Graph convertToGraph(List<ExtractedGraphDTO.ExtractedEntity> entities, List<ExtractedGraphDTO.ExtractedRelationship> relationships) {
        Graph graph = new Graph();

        graph.setEntities(entities.stream().map(e -> {
            Entity entity = new Entity();
            entity.setId(e.getId());
            entity.setTitle(e.getTitle());
            entity.setType(e.getNodeLabel());
            entity.setDescription(e.getDescription());
            entity.setMetadata(e.getMetadata());
            return entity;
        }).collect(Collectors.toList()));

        graph.setRelationships(relationships.stream().map(r -> {
            Relationship rel = new Relationship();
            rel.setSource(r.getSource());
            rel.setTarget(r.getTarget());
            rel.setType(r.getRelationshipType());
            rel.setDescription(r.getDescription());
            Double confidence = r.getConfidence() != null ? r.getConfidence() : r.getWeight();
            rel.setConfidence(confidence);
            rel.setWeight(r.getWeight() != null ? r.getWeight() : confidence);
            Map<String, Object> metadata = r.getMetadata() == null
                    ? new HashMap<>()
                    : new HashMap<>(r.getMetadata());
            metadata.put("relationshipType", r.getRelationshipType());
            metadata.put("weight", r.getWeight());
            if (r.getOccurredAt() != null && !r.getOccurredAt().isBlank()) {
                metadata.put("occurredAt", r.getOccurredAt());
            }
            rel.setMetadata(metadata);
            return rel;
        }).collect(Collectors.toList()));

        graph.setCommunities(new ArrayList<>());

        return graph;
    }
}
