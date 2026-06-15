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
package ai.kompile.knowledgegraph.agent;

import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder;
import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder.GraphMergeStrategy;
import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder.MergedGraphResult;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.ExtractionConfig;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.retrievers.RetrievedDoc;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spring service that orchestrates the multi-agent graph extraction workflow.
 *
 * <p>Collects all registered {@link RelationExtractionAgent} beans, runs them through
 * {@link DefaultMultiAgentGraphBuilder}, and can optionally persist the merged result to
 * the {@link KnowledgeGraphService}.
 *
 * <p>The {@link DefaultMultiAgentGraphBuilder} is instantiated directly (not as a Spring bean)
 * because it has no dependencies.
 */
@Service
@Slf4j
public class MultiAgentExtractionService {

    private final MultiAgentGraphBuilder builder = new DefaultMultiAgentGraphBuilder();

    /** All {@link RelationExtractionAgent} beans registered in the application context. */
    private final List<RelationExtractionAgent> registeredAgents;

    @Autowired
    public MultiAgentExtractionService(
            @Autowired(required = false) List<RelationExtractionAgent> agents) {
        this.registeredAgents = agents != null ? agents : List.of();
        log.info("MultiAgentExtractionService initialized with {} registered agents",
                this.registeredAgents.size());
        this.registeredAgents.forEach(a ->
                log.info("  - agent '{}': {}", a.getId(), a.getDescription()));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CORE EXTRACTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Run multi-agent extraction on the supplied chunks.
     *
     * @param chunks         document chunks to process
     * @param agentIds       IDs of agents to use; if null or empty all registered agents are used
     * @param mergeStrategy  string name of {@link GraphMergeStrategy} (e.g. "UNION");
     *                       defaults to {@code UNION} if unrecognised
     * @param config         extraction configuration; {@link ExtractionConfig#defaults()} is used
     *                       when null
     * @return the merged graph result
     */
    public MergedGraphResult runExtraction(
            List<RetrievedDoc> chunks,
            List<String> agentIds,
            String mergeStrategy,
            ExtractionConfig config) {

        List<RelationExtractionAgent> agents = selectAgents(agentIds);
        if (agents.isEmpty()) {
            log.warn("No agents available for extraction (requested: {})", agentIds);
        }

        GraphMergeStrategy strategy = parseStrategy(mergeStrategy);
        ExtractionConfig effectiveConfig = config != null ? config : ExtractionConfig.defaults();

        log.info("Running multi-agent extraction: {} agents, strategy={}, {} chunks",
                agents.size(), strategy, chunks != null ? chunks.size() : 0);

        return builder.buildGraph(
                chunks != null ? chunks : List.of(),
                agents,
                strategy,
                effectiveConfig);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AGENT DISCOVERY
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns metadata about every registered extraction agent.
     */
    public List<AgentInfo> getAvailableAgents() {
        List<AgentInfo> infos = new ArrayList<>();
        for (RelationExtractionAgent agent : registeredAgents) {
            infos.add(new AgentInfo(
                    agent.getId(),
                    agent.getDescription(),
                    agent.supportedContentTypes()
            ));
        }
        return infos;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PERSIST TO GRAPH
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Persists the entities and relationships in a {@link MergedGraphResult} to the
     * {@link KnowledgeGraphService} as ENTITY nodes linked by SHARED_ENTITY edges.
     *
     * <p>Each entity in the merged graph becomes a {@link NodeLevel#ENTITY} node.
     * Each relationship between entities that were successfully persisted becomes a
     * {@link EdgeType#SHARED_ENTITY} edge (the closest semantic match in the existing
     * {@link EdgeType} enum for extracted co-occurrence / semantic links).
     *
     * @param result      the merged extraction result
     * @param graphService the graph service to persist to
     * @param factSheetId  optional fact sheet scope (may be null)
     * @return a summary of what was persisted
     */
    public PersistenceSummary persistToGraph(
            MergedGraphResult result,
            KnowledgeGraphService graphService,
            Long factSheetId) {

        if (result == null || result.mergedGraph() == null) {
            return new PersistenceSummary(0, 0, 0, 0, List.of());
        }

        List<Entity> entities = result.mergedGraph().getEntities();
        List<Relationship> relationships = result.mergedGraph().getRelationships();

        int entitiesCreated = 0;
        int entitiesSkipped = 0;
        int edgesCreated = 0;
        int edgesSkipped = 0;
        List<String> errors = new ArrayList<>();

        // Map from graph entity ID -> persisted graph node ID (UUID string)
        Map<String, String> entityIdToNodeId = new HashMap<>();

        // Persist entities
        if (entities != null) {
            for (Entity entity : entities) {
                try {
                    Map<String, Object> metadata = entity.getMetadata() != null
                            ? new HashMap<>(entity.getMetadata())
                            : new HashMap<>();
                    metadata.put("entityType", entity.getType());
                    if (entity.getConfidence() != null) {
                        metadata.put("confidence", entity.getConfidence());
                    }
                    if (factSheetId != null) {
                        metadata.put("factSheetId", factSheetId.toString());
                    }

                    String externalId = "entity:" + entity.getId();
                    GraphNode node = graphService.createNode(
                            NodeLevel.ENTITY,
                            externalId,
                            entity.getTitle() != null ? entity.getTitle() : entity.getId(),
                            entity.getDescription(),
                            metadata
                    );

                    entityIdToNodeId.put(entity.getId(), node.getNodeId());
                    entitiesCreated++;

                } catch (Exception e) {
                    log.warn("Failed to persist entity '{}': {}", entity.getId(), e.getMessage());
                    errors.add("entity:" + entity.getId() + " - " + e.getMessage());
                    entitiesSkipped++;
                }
            }
        }

        // Persist relationships as edges between the created nodes
        if (relationships != null) {
            for (Relationship rel : relationships) {
                String sourceNodeId = entityIdToNodeId.get(rel.getSource());
                String targetNodeId = entityIdToNodeId.get(rel.getTarget());

                if (sourceNodeId == null || targetNodeId == null) {
                    log.debug("Skipping edge {}->{}: endpoint node not persisted",
                            rel.getSource(), rel.getTarget());
                    edgesSkipped++;
                    continue;
                }

                try {
                    String description = buildEdgeDescription(rel);
                    graphService.createEdge(
                            sourceNodeId,
                            targetNodeId,
                            EdgeType.SHARED_ENTITY,
                            rel.getWeight() != null ? rel.getWeight() : rel.getConfidence(),
                            description
                    );
                    edgesCreated++;

                } catch (Exception e) {
                    log.warn("Failed to persist edge {}->{}: {}",
                            rel.getSource(), rel.getTarget(), e.getMessage());
                    errors.add("edge:" + rel.getSource() + "->" + rel.getTarget()
                            + " - " + e.getMessage());
                    edgesSkipped++;
                }
            }
        }

        log.info("Persisted merged graph: {} entities created, {} skipped; {} edges created, {} skipped",
                entitiesCreated, entitiesSkipped, edgesCreated, edgesSkipped);

        return new PersistenceSummary(entitiesCreated, entitiesSkipped, edgesCreated, edgesSkipped, errors);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private List<RelationExtractionAgent> selectAgents(List<String> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return new ArrayList<>(registeredAgents);
        }
        List<RelationExtractionAgent> selected = new ArrayList<>();
        for (String id : agentIds) {
            registeredAgents.stream()
                    .filter(a -> a.getId().equals(id))
                    .findFirst()
                    .ifPresentOrElse(
                            selected::add,
                            () -> log.warn("Requested agent '{}' not found in registry", id)
                    );
        }
        return selected;
    }

    private GraphMergeStrategy parseStrategy(String mergeStrategy) {
        if (mergeStrategy == null || mergeStrategy.isBlank()) {
            return GraphMergeStrategy.UNION;
        }
        try {
            return GraphMergeStrategy.valueOf(mergeStrategy.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown merge strategy '{}', defaulting to UNION", mergeStrategy);
            return GraphMergeStrategy.UNION;
        }
    }

    private String buildEdgeDescription(Relationship rel) {
        StringBuilder sb = new StringBuilder();
        if (rel.getType() != null) {
            sb.append(rel.getType());
        }
        if (rel.getDescription() != null && !rel.getDescription().isBlank()) {
            if (!sb.isEmpty()) sb.append(": ");
            sb.append(rel.getDescription());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NESTED DTOs
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Lightweight descriptor for an available extraction agent.
     */
    public record AgentInfo(
            String id,
            String description,
            Set<String> supportedContentTypes
    ) {}

    /**
     * Summary of what was written to the knowledge graph during persistence.
     */
    public record PersistenceSummary(
            int entitiesCreated,
            int entitiesSkipped,
            int edgesCreated,
            int edgesSkipped,
            List<String> errors
    ) {}
}
