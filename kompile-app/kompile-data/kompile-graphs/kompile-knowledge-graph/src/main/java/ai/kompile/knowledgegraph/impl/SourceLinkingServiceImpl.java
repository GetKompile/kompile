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
package ai.kompile.knowledgegraph.impl;

import ai.kompile.knowledgegraph.domain.*;
import ai.kompile.knowledgegraph.repository.EntityMentionRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.service.SourceLinkingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of SourceLinkingService for creating links between sources.
 */
@Service
@Slf4j
public class SourceLinkingServiceImpl implements SourceLinkingService {

    private EntityMentionRepository entityMentionRepository;
    private KnowledgeGraphService knowledgeGraphService;

    @Autowired
    public SourceLinkingServiceImpl(
            EntityMentionRepository entityMentionRepository,
            KnowledgeGraphService knowledgeGraphService) {
        this.entityMentionRepository = entityMentionRepository;
        this.knowledgeGraphService = knowledgeGraphService;
    }

    /** No-arg constructor for CGLIB proxy instantiation in GraalVM native image. */
    protected SourceLinkingServiceImpl() {}


    @Override
    @Transactional
    public LinkingResult linkSourcesBySharedConcepts(Long factSheetId, LinkingConfig config) {
        log.info("Linking sources by shared concepts for fact sheet {}", factSheetId);

        List<GraphNode> sources = knowledgeGraphService.getSourcesInFactSheet(factSheetId);
        if (sources.size() < 2) {
            return new LinkingResult(sources.size(), 0, 0, 0, List.of(),
                Map.of("message", "Need at least 2 sources to create links"));
        }

        List<SourceLink> links = new ArrayList<>();
        int conceptBasedLinks = 0;

        // Build a map of source -> concepts
        Map<String, Set<String>> sourceConceptMap = new HashMap<>();
        for (GraphNode source : sources) {
            Set<String> concepts = getConceptsForSource(source, factSheetId);
            sourceConceptMap.put(source.getNodeId(), concepts);
        }

        // Compare each pair of sources
        for (int i = 0; i < sources.size(); i++) {
            for (int j = i + 1; j < sources.size(); j++) {
                GraphNode source1 = sources.get(i);
                GraphNode source2 = sources.get(j);

                Set<String> concepts1 = sourceConceptMap.get(source1.getNodeId());
                Set<String> concepts2 = sourceConceptMap.get(source2.getNodeId());

                // Calculate shared concepts
                Set<String> sharedConcepts = new HashSet<>(concepts1);
                sharedConcepts.retainAll(concepts2);

                // Calculate Jaccard similarity
                Set<String> unionConcepts = new HashSet<>(concepts1);
                unionConcepts.addAll(concepts2);
                double jaccardSimilarity = unionConcepts.isEmpty() ? 0 :
                    (double) sharedConcepts.size() / unionConcepts.size();

                // Check if we should create a link
                if (sharedConcepts.size() >= config.minSharedConcepts() &&
                    jaccardSimilarity >= config.minConceptOverlap()) {

                    // Check if link already exists via service
                    if (!knowledgeGraphService.edgeExistsInFactSheet(
                                source1.getNodeId(), source2.getNodeId(), factSheetId) &&
                        !knowledgeGraphService.edgeExistsInFactSheet(
                                source2.getNodeId(), source1.getNodeId(), factSheetId)) {

                        // Create the edge
                        double strength = Math.min(1.0, jaccardSimilarity + sharedConcepts.size() * 0.05);
                        String description = String.format("Shares %d concepts (%.0f%% overlap)",
                            sharedConcepts.size(), jaccardSimilarity * 100);

                        GraphEdge edge = knowledgeGraphService.createEdgeWithMetadata(
                            source1.getNodeId(),
                            source2.getNodeId(),
                            config.createCrossSourceEdges() ? EdgeType.CROSS_SOURCE : EdgeType.SHARED_ENTITY,
                            strength,
                            null, description,
                            serializeSharedConcepts(sharedConcepts),
                            null, factSheetId
                        );

                        links.add(new SourceLink(
                            source1.getNodeId(), source1.getTitle(),
                            source2.getNodeId(), source2.getTitle(),
                            "SHARED_CONCEPTS",
                            strength,
                            new ArrayList<>(sharedConcepts),
                            description
                        ));

                        conceptBasedLinks++;
                    }
                }
            }
        }

        Map<String, Object> stats = Map.of(
            "totalSources", sources.size(),
            "possiblePairs", sources.size() * (sources.size() - 1) / 2,
            "linksCreated", conceptBasedLinks
        );

        return new LinkingResult(sources.size(), conceptBasedLinks, conceptBasedLinks, 0, links, stats);
    }

    @Override
    @Transactional
    public LinkingResult linkSourcesByEmbeddingSimilarity(Long factSheetId, LinkingConfig config) {
        // This would require access to the vector store
        // For now, return an empty result indicating this feature needs vector store integration
        log.info("Embedding similarity linking requested for fact sheet {} - requires vector store integration",
            factSheetId);

        return new LinkingResult(0, 0, 0, 0, List.of(),
            Map.of("message", "Embedding similarity linking requires vector store integration"));
    }

    @Override
    @Transactional
    public LinkingResult linkAllSources(Long factSheetId, LinkingConfig config) {
        log.info("Linking all sources for fact sheet {} using all methods", factSheetId);

        // Link by shared concepts
        LinkingResult conceptResult = linkSourcesBySharedConcepts(factSheetId, config);

        // Link by embedding similarity (if enabled and available)
        LinkingResult similarityResult = null;
        if (config.useEmbeddingSimilarity()) {
            similarityResult = linkSourcesByEmbeddingSimilarity(factSheetId, config);
        }

        // Combine results
        List<SourceLink> allLinks = new ArrayList<>(conceptResult.links());
        if (similarityResult != null) {
            allLinks.addAll(similarityResult.links());
        }

        int totalLinks = conceptResult.linksCreated() +
            (similarityResult != null ? similarityResult.linksCreated() : 0);

        Map<String, Object> combinedStats = new HashMap<>();
        combinedStats.putAll(conceptResult.statistics());
        if (similarityResult != null) {
            combinedStats.put("similarityStats", similarityResult.statistics());
        }

        return new LinkingResult(
            conceptResult.sourcesAnalyzed(),
            totalLinks,
            conceptResult.conceptBasedLinks(),
            similarityResult != null ? similarityResult.similarityBasedLinks() : 0,
            allLinks,
            combinedStats
        );
    }

    @Override
    public List<SourceLink> getSourceLinks(Long factSheetId) {
        List<GraphEdge> edges = knowledgeGraphService.getEdgesByTypeInFactSheet(factSheetId, EdgeType.CROSS_SOURCE);

        return edges.stream()
            .map(this::edgeToSourceLink)
            .collect(Collectors.toList());
    }

    @Override
    public List<SourceLink> getLinksForSource(Long factSheetId, String sourceNodeId) {
        List<GraphEdge> edges = knowledgeGraphService.getEdgesForNodeInFactSheet(sourceNodeId, factSheetId);

        return edges.stream()
            .filter(e -> e.getEdgeType() == EdgeType.CROSS_SOURCE ||
                        e.getEdgeType() == EdgeType.SHARED_ENTITY)
            .filter(e -> e.getSourceNode().getNodeType() == NodeLevel.SOURCE &&
                        e.getTargetNode().getNodeType() == NodeLevel.SOURCE)
            .map(this::edgeToSourceLink)
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public SourceLink createManualLink(Long factSheetId, String sourceNodeId1, String sourceNodeId2,
                                       String description, double strength) {
        GraphNode source1 = knowledgeGraphService.getNode(sourceNodeId1)
            .orElseThrow(() -> new IllegalArgumentException("Source node not found: " + sourceNodeId1));
        GraphNode source2 = knowledgeGraphService.getNode(sourceNodeId2)
            .orElseThrow(() -> new IllegalArgumentException("Source node not found: " + sourceNodeId2));

        if (source1.getNodeType() != NodeLevel.SOURCE || source2.getNodeType() != NodeLevel.SOURCE) {
            throw new IllegalArgumentException("Both nodes must be SOURCE type");
        }

        // Create the edge via service (factSheetId and bidirectional carried through metadata)
        GraphEdge edge = knowledgeGraphService.createEdgeWithMetadata(
            sourceNodeId1, sourceNodeId2,
            EdgeType.USER_DEFINED,
            strength,
            null, description != null ? description : "Manual link",
            null, null, factSheetId
        );

        return new SourceLink(
            sourceNodeId1, source1.getTitle(),
            sourceNodeId2, source2.getTitle(),
            "USER_DEFINED",
            strength,
            List.of(),
            description
        );
    }

    @Override
    @Transactional
    public boolean removeLink(Long factSheetId, String sourceNodeId1, String sourceNodeId2) {
        // Check both directions via service
        GraphEdge fwd = knowledgeGraphService.findEdgeBetweenNodes(sourceNodeId1, sourceNodeId2);
        GraphEdge rev = knowledgeGraphService.findEdgeBetweenNodes(sourceNodeId2, sourceNodeId1);
        GraphEdge toDelete = fwd != null ? fwd : rev;

        if (toDelete != null) {
            knowledgeGraphService.deleteEdge(toDelete.getEdgeId());
            return true;
        }

        return false;
    }

    @Override
    public Map<String, Object> getConnectivitySummary(Long factSheetId) {
        List<GraphNode> sources = knowledgeGraphService.getSourcesInFactSheet(factSheetId);
        List<GraphEdge> edges = knowledgeGraphService.getEdgesInFactSheet(factSheetId);

        // Count edges between sources
        long sourceEdges = edges.stream()
            .filter(e -> e.getSourceNode().getNodeType() == NodeLevel.SOURCE &&
                        e.getTargetNode().getNodeType() == NodeLevel.SOURCE)
            .count();

        // Build connectivity map
        Map<String, Integer> connectionCounts = new HashMap<>();
        for (GraphNode source : sources) {
            connectionCounts.put(source.getNodeId(), 0);
        }

        for (GraphEdge edge : edges) {
            if (edge.getSourceNode().getNodeType() == NodeLevel.SOURCE) {
                connectionCounts.merge(edge.getSourceNode().getNodeId(), 1, Integer::sum);
            }
            if (edge.getTargetNode().getNodeType() == NodeLevel.SOURCE) {
                connectionCounts.merge(edge.getTargetNode().getNodeId(), 1, Integer::sum);
            }
        }

        int isolatedCount = (int) connectionCounts.values().stream().filter(c -> c == 0).count();
        double avgConnections = connectionCounts.values().stream()
            .mapToInt(Integer::intValue)
            .average()
            .orElse(0.0);

        return Map.of(
            "totalSources", sources.size(),
            "totalSourceLinks", sourceEdges,
            "isolatedSources", isolatedCount,
            "averageConnectionsPerSource", avgConnections,
            "maxPossibleLinks", sources.size() * (sources.size() - 1) / 2,
            "connectivityRatio", sources.size() > 1 ?
                (double) sourceEdges / (sources.size() * (sources.size() - 1) / 2) : 0
        );
    }

    @Override
    public List<String> findIsolatedSources(Long factSheetId) {
        List<GraphNode> sources = knowledgeGraphService.getSourcesInFactSheet(factSheetId);
        Set<String> connectedSources = new HashSet<>();

        List<GraphEdge> edges = knowledgeGraphService.getEdgesInFactSheet(factSheetId);
        for (GraphEdge edge : edges) {
            if (edge.getSourceNode().getNodeType() == NodeLevel.SOURCE) {
                connectedSources.add(edge.getSourceNode().getNodeId());
            }
            if (edge.getTargetNode().getNodeType() == NodeLevel.SOURCE) {
                connectedSources.add(edge.getTargetNode().getNodeId());
            }
        }

        return sources.stream()
            .map(GraphNode::getNodeId)
            .filter(id -> !connectedSources.contains(id))
            .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> findMostConnectedSources(Long factSheetId, int limit) {
        List<GraphNode> sources = knowledgeGraphService.getSourcesInFactSheet(factSheetId);
        Map<String, Integer> connectionCounts = new HashMap<>();
        Map<String, String> sourceTitles = new HashMap<>();

        for (GraphNode source : sources) {
            connectionCounts.put(source.getNodeId(), 0);
            sourceTitles.put(source.getNodeId(), source.getTitle());
        }

        List<GraphEdge> edges = knowledgeGraphService.getEdgesInFactSheet(factSheetId);
        for (GraphEdge edge : edges) {
            if (edge.getSourceNode().getNodeType() == NodeLevel.SOURCE) {
                connectionCounts.merge(edge.getSourceNode().getNodeId(), 1, Integer::sum);
            }
            if (edge.getTargetNode().getNodeType() == NodeLevel.SOURCE) {
                connectionCounts.merge(edge.getTargetNode().getNodeId(), 1, Integer::sum);
            }
        }

        return connectionCounts.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .limit(limit)
            .map(e -> Map.<String, Object>of(
                "sourceId", e.getKey(),
                "sourceTitle", sourceTitles.get(e.getKey()),
                "connectionCount", e.getValue()
            ))
            .collect(Collectors.toList());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private Set<String> getConceptsForSource(GraphNode source, Long factSheetId) {
        // Get children of source (documents) via service
        List<GraphNode> documents = knowledgeGraphService.getChildren(source.getNodeId()).stream()
            .filter(n -> n.getNodeType() == NodeLevel.DOCUMENT)
            .collect(Collectors.toList());

        Set<String> concepts = new HashSet<>();
        for (GraphNode doc : documents) {
            List<EntityMention> mentions = entityMentionRepository.findByNode(doc);
            for (EntityMention mention : mentions) {
                if (mention.getFactSheetId() == null || mention.getFactSheetId().equals(factSheetId)) {
                    concepts.add(mention.getEntityName());
                }
            }
        }

        return concepts;
    }

    private String serializeSharedConcepts(Set<String> concepts) {
        // Simple JSON array serialization
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String concept : concepts) {
            if (!first) sb.append(",");
            sb.append("\"").append(concept.replace("\"", "\\\"")).append("\"");
            first = false;
        }
        sb.append("]");
        return sb.toString();
    }

    private SourceLink edgeToSourceLink(GraphEdge edge) {
        List<String> sharedConcepts = parseSharedConcepts(edge.getSharedEntitiesJson());

        return new SourceLink(
            edge.getSourceNode().getNodeId(),
            edge.getSourceNode().getTitle(),
            edge.getTargetNode().getNodeId(),
            edge.getTargetNode().getTitle(),
            edge.getEdgeType().name(),
            edge.getWeight(),
            sharedConcepts,
            edge.getDescription()
        );
    }

    private List<String> parseSharedConcepts(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }

        // Simple JSON array parsing
        List<String> concepts = new ArrayList<>();
        String content = json.trim();
        if (content.startsWith("[") && content.endsWith("]")) {
            content = content.substring(1, content.length() - 1);
            String[] parts = content.split(",");
            for (String part : parts) {
                String concept = part.trim();
                if (concept.startsWith("\"") && concept.endsWith("\"")) {
                    concept = concept.substring(1, concept.length() - 1);
                    concepts.add(concept);
                }
            }
        }
        return concepts;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TERM-BASED LINKING IMPLEMENTATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public TermLinkingResult linkNodesByTerm(String term, Long factSheetId, EdgeType edgeType, Double weight) {
        if (term == null || term.isBlank()) {
            return new TermLinkingResult(term, 0, 0, List.of(), "Term cannot be empty");
        }

        String normalizedTerm = normalizeTerm(term);
        EdgeType linkType = edgeType != null ? edgeType : EdgeType.SHARED_ENTITY;
        double linkWeight = weight != null ? weight : 0.7;

        log.info("Linking nodes by term '{}' (normalized: '{}')", term, normalizedTerm);

        // Find all nodes with this term
        List<GraphNode> nodesWithTerm;
        if (factSheetId != null) {
            nodesWithTerm = entityMentionRepository.findByEntityNameAndFactSheet(normalizedTerm, factSheetId)
                .stream()
                .map(EntityMention::getNode)
                .distinct()
                .collect(Collectors.toList());
        } else {
            nodesWithTerm = entityMentionRepository.findNodesWithEntity(normalizedTerm);
        }

        if (nodesWithTerm.size() < 2) {
            return new TermLinkingResult(term, nodesWithTerm.size(), 0,
                nodesWithTerm.stream().map(GraphNode::getNodeId).collect(Collectors.toList()),
                "Need at least 2 nodes with this term to create links");
        }

        int linksCreated = 0;
        List<String> linkedNodeIds = new ArrayList<>();

        // Create edges between all pairs of nodes with this term
        for (int i = 0; i < nodesWithTerm.size(); i++) {
            for (int j = i + 1; j < nodesWithTerm.size(); j++) {
                GraphNode node1 = nodesWithTerm.get(i);
                GraphNode node2 = nodesWithTerm.get(j);

                // Check if edge already exists
                if (knowledgeGraphService.findEdgeBetweenNodesBidirectional(
                        node1.getNodeId(), node2.getNodeId()).isEmpty()) {

                    String description = String.format("Linked by term: %s", term);
                    knowledgeGraphService.createEdgeWithMetadata(
                        node1.getNodeId(),
                        node2.getNodeId(),
                        linkType,
                        linkWeight,
                        null, description,
                        "[\"" + normalizedTerm + "\"]",
                        null, factSheetId
                    );

                    linksCreated++;
                    if (!linkedNodeIds.contains(node1.getNodeId())) {
                        linkedNodeIds.add(node1.getNodeId());
                    }
                    if (!linkedNodeIds.contains(node2.getNodeId())) {
                        linkedNodeIds.add(node2.getNodeId());
                    }
                }
            }
        }

        return new TermLinkingResult(term, nodesWithTerm.size(), linksCreated, linkedNodeIds,
            String.format("Created %d links between %d nodes for term '%s'",
                linksCreated, linkedNodeIds.size(), term));
    }

    @Override
    @Transactional
    public List<TermLinkingResult> linkNodesByTerms(List<String> terms, Long factSheetId,
                                                     EdgeType edgeType, Double weight) {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }

        return terms.stream()
            .map(term -> linkNodesByTerm(term, factSheetId, edgeType, weight))
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public SourceLink createTermBasedRelation(String sourceNodeId, String targetNodeId, String relationTerm,
                                               String description, double weight, boolean bidirectional) {
        GraphNode source = knowledgeGraphService.getNode(sourceNodeId)
            .orElseThrow(() -> new IllegalArgumentException("Source node not found: " + sourceNodeId));
        GraphNode target = knowledgeGraphService.getNode(targetNodeId)
            .orElseThrow(() -> new IllegalArgumentException("Target node not found: " + targetNodeId));

        String normalizedTerm = normalizeTerm(relationTerm);
        String edgeDescription = description != null ? description :
            String.format("Related by: %s", relationTerm);

        // Create the edge via service with full metadata
        knowledgeGraphService.createEdgeWithMetadata(
            sourceNodeId,
            targetNodeId,
            EdgeType.USER_DEFINED,
            weight,
            relationTerm, edgeDescription,
            "[\"" + normalizedTerm + "\"]",
            null, null
        );

        // Also create entity mentions if they don't exist
        createEntityMentionIfNotExists(source, normalizedTerm, "USER_DEFINED");
        createEntityMentionIfNotExists(target, normalizedTerm, "USER_DEFINED");

        return new SourceLink(
            sourceNodeId, source.getTitle(),
            targetNodeId, target.getTitle(),
            "USER_DEFINED",
            weight,
            List.of(relationTerm),
            edgeDescription
        );
    }

    @Override
    public List<String> findNodesWithTerm(String term, Long factSheetId, int limit) {
        if (term == null || term.isBlank()) {
            return List.of();
        }

        String normalizedTerm = normalizeTerm(term);
        List<GraphNode> nodes;

        if (factSheetId != null) {
            nodes = entityMentionRepository.findByEntityNameAndFactSheet(normalizedTerm, factSheetId)
                .stream()
                .map(EntityMention::getNode)
                .distinct()
                .limit(limit)
                .collect(Collectors.toList());
        } else {
            nodes = entityMentionRepository.findNodesWithEntity(normalizedTerm)
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
        }

        return nodes.stream()
            .map(GraphNode::getNodeId)
            .collect(Collectors.toList());
    }

    @Override
    public List<Map<String, Object>> getAllTerms(Long factSheetId, int limit) {
        List<Object[]> topEntities;

        if (factSheetId != null) {
            topEntities = entityMentionRepository.findTopEntitiesByFactSheet(
                factSheetId,
                org.springframework.data.domain.PageRequest.of(0, limit)
            );
        } else {
            topEntities = entityMentionRepository.findTopEntities(
                org.springframework.data.domain.PageRequest.of(0, limit)
            );
        }

        return topEntities.stream()
            .map(row -> Map.<String, Object>of(
                "term", row[0],
                "count", row[1]
            ))
            .collect(Collectors.toList());
    }

    @Override
    public List<String> getSharedTerms(String nodeId1, String nodeId2, Long factSheetId) {
        List<String> terms1 = entityMentionRepository.findEntitiesByNodeId(nodeId1);
        List<String> terms2 = entityMentionRepository.findEntitiesByNodeId(nodeId2);

        Set<String> shared = new HashSet<>(terms1);
        shared.retainAll(new HashSet<>(terms2));

        return new ArrayList<>(shared);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ADDITIONAL HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private String normalizeTerm(String term) {
        if (term == null) return "";
        return term.trim().toLowerCase().replaceAll("[^a-z0-9\\s]", "").replaceAll("\\s+", " ");
    }

    private void createEntityMentionIfNotExists(GraphNode node, String normalizedTerm, String entityType) {
        Optional<EntityMention> existing = entityMentionRepository.findByNodeAndEntityName(node, normalizedTerm);
        if (existing.isEmpty()) {
            EntityMention mention = EntityMention.builder()
                .node(node)
                .entityName(normalizedTerm)
                .entityType(entityType)
                .mentionCount(1)
                .confidence(1.0)
                .build();
            entityMentionRepository.save(mention);
        }
    }
}
