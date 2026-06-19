/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.io;

import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.io.format.CsvGraphExporter;
import ai.kompile.knowledgegraph.io.format.CsvGraphImporter;
import ai.kompile.knowledgegraph.io.format.CypherDumpExporter;
import ai.kompile.knowledgegraph.io.format.CypherDumpImporter;
import ai.kompile.knowledgegraph.io.format.GraphMLExporter;
import ai.kompile.knowledgegraph.io.format.JsonGraphExporter;
import ai.kompile.knowledgegraph.io.format.JsonGraphImporter;
import ai.kompile.knowledgegraph.io.format.JsonLdGraphExporter;
import ai.kompile.knowledgegraph.io.format.JsonLdGraphImporter;
import ai.kompile.knowledgegraph.io.format.PortableGraph;
import ai.kompile.knowledgegraph.io.model.ExportResult;
import ai.kompile.knowledgegraph.io.model.ImportResult;
import ai.kompile.knowledgegraph.io.model.PortableEdge;
import ai.kompile.knowledgegraph.io.model.PortableNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Orchestrates import/export across all graph formats.
 */
@Service
public class GraphIOService {

    private static final Logger log = LoggerFactory.getLogger(GraphIOService.class);

    private final KnowledgeGraphService graphService;
    private final ObjectMapper mapper;

    public GraphIOService(KnowledgeGraphService graphService, ObjectMapper mapper) {
        this.graphService = graphService;
        this.mapper = mapper;
    }

    public ImportResult importGraph(String format, byte[] payload, byte[] secondary) throws Exception {
        PortableGraph graph = switch (format.toLowerCase()) {
            case "json" -> new JsonGraphImporter(mapper).parse(payload);
            case "jsonld", "json-ld" -> new JsonLdGraphImporter(mapper).parse(payload);
            case "csv" -> new CsvGraphImporter().parse(payload, secondary);
            case "cypher" -> new CypherDumpImporter().parse(payload);
            default -> throw new IllegalArgumentException("Unknown import format: " + format);
        };
        return apply(format, graph);
    }

    public ExportResult exportGraph(String format, Long factSheetId) throws Exception {
        return serialize(format, collect(factSheetId, false));
    }

    /**
     * Export only the nodes/edges not scoped to any fact sheet (factSheetId == null).
     * Complements the per-fact-sheet exports so a portability dump covers the whole
     * graph exactly once.
     */
    public ExportResult exportGlobalGraph(String format) throws Exception {
        return serialize(format, collect(null, true));
    }

    private ExportResult serialize(String format, PortableGraph graph) throws Exception {
        return switch (format.toLowerCase()) {
            case "json" -> new ExportResult("json",
                    graph.nodes().size(),
                    graph.edges().size(),
                    new JsonGraphExporter(mapper).toBytes(graph),
                    "application/json",
                    "graph.json");
            case "jsonld", "json-ld" -> new ExportResult("jsonld",
                    graph.nodes().size(),
                    graph.edges().size(),
                    new JsonLdGraphExporter(mapper).toBytes(graph),
                    "application/ld+json",
                    "graph.jsonld");
            case "csv" -> new ExportResult("csv",
                    graph.nodes().size(),
                    graph.edges().size(),
                    new CsvGraphExporter().toZip(graph),
                    "application/zip",
                    "graph-csv.zip");
            case "graphml" -> new ExportResult("graphml",
                    graph.nodes().size(),
                    graph.edges().size(),
                    new GraphMLExporter().toBytes(graph),
                    "application/xml",
                    "graph.graphml");
            case "cypher" -> new ExportResult("cypher",
                    graph.nodes().size(),
                    graph.edges().size(),
                    new CypherDumpExporter().toBytes(graph),
                    "text/plain",
                    "graph.cypher");
            default -> throw new IllegalArgumentException("Unknown export format: " + format);
        };
    }

    private ImportResult apply(String format, PortableGraph graph) {
        int created = 0, updated = 0, edgeCount = 0, errors = 0;
        List<String> errorMessages = new ArrayList<>();

        for (PortableNode n : graph.nodes()) {
            try {
                NodeLevel level = parseLevel(n.nodeType());
                Optional<GraphNode> existing = graphService.getNodeByExternalId(n.externalId(), level);
                if (existing.isPresent()) {
                    graphService.updateNode(existing.get().getNodeId(), n.title(), n.description(), n.metadata());
                    updated++;
                } else {
                    // Restore fact-sheet scope when present so rehydrated graphs land
                    // in the correct fact sheet (the factSheetId-aware overload is a no-op
                    // on stores that don't scope, preserving interop-import behavior).
                    if (n.factSheetId() != null) {
                        graphService.createNode(level, n.externalId(), n.title(), n.description(),
                                n.metadata(), n.factSheetId());
                    } else {
                        graphService.createNode(level, n.externalId(), n.title(), n.description(), n.metadata());
                    }
                    created++;
                }
            } catch (Exception e) {
                errors++;
                errorMessages.add("Node '" + n.externalId() + "': " + e.getMessage());
                log.warn("Failed to import node {}: {}", n.externalId(), e.getMessage());
            }
        }

        for (PortableEdge e : graph.edges()) {
            try {
                String fromUuid = resolveExternalId(e.fromExternalId());
                String toUuid = resolveExternalId(e.toExternalId());
                if (fromUuid == null || toUuid == null) {
                    errors++;
                    errorMessages.add("Edge skipped (missing endpoint): " + e.fromExternalId() + " -> " + e.toExternalId());
                    continue;
                }
                graphService.createEdge(fromUuid, toUuid, parseEdgeType(e.edgeType()),
                        e.weight() == null ? 1.0 : e.weight(), e.description());
                edgeCount++;
            } catch (Exception ex) {
                errors++;
                errorMessages.add("Edge '" + e.fromExternalId() + "->" + e.toExternalId() + "': " + ex.getMessage());
                log.warn("Failed to import edge {}->{}: {}", e.fromExternalId(), e.toExternalId(), ex.getMessage());
            }
        }
        return new ImportResult(format, created, updated, edgeCount, errors, errorMessages);
    }

    private String resolveExternalId(String externalId) {
        for (NodeLevel level : NodeLevel.values()) {
            Optional<GraphNode> n = graphService.getNodeByExternalId(externalId, level);
            if (n.isPresent()) return n.get().getNodeId();
        }
        return null;
    }

    private static NodeLevel parseLevel(String value) {
        if (value == null) return NodeLevel.ENTITY;
        try {
            return NodeLevel.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return NodeLevel.ENTITY;
        }
    }

    private static EdgeType parseEdgeType(String value) {
        if (value == null) return EdgeType.USER_DEFINED;
        try {
            return EdgeType.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return EdgeType.USER_DEFINED;
        }
    }

    private PortableGraph collect(Long factSheetId, boolean globalOnly) {
        List<PortableNode> nodes = new ArrayList<>();
        List<PortableEdge> edges = new ArrayList<>();
        Set<String> emittedEdgeIds = new HashSet<>();

        for (NodeLevel level : NodeLevel.values()) {
            // Enumerate every node of this level directly. (searchNodes("") is a query
            // path — on the vector-store-backed graph an empty query is not guaranteed
            // to return all nodes, which would silently truncate a portability export.)
            for (GraphNode node : graphService.getNodesByType(level)) {
                if (!inScope(node.getFactSheetId(), factSheetId, globalOnly)) continue;
                nodes.add(toPortable(node));
                for (GraphEdge edge : graphService.getEdgesForNode(node.getNodeId())) {
                    if (!inScope(edge.getFactSheetId(), factSheetId, globalOnly)) continue;
                    if (!emittedEdgeIds.add(edge.getEdgeId())) continue;
                    edges.add(toPortable(edge));
                }
            }
        }
        return new PortableGraph(nodes, edges);
    }

    /**
     * Scope predicate shared by node and edge collection.
     * <ul>
     *   <li>{@code globalOnly} → keep only rows with no fact-sheet scope (factSheetId == null);</li>
     *   <li>{@code factSheetId != null} → keep only rows in that fact sheet;</li>
     *   <li>otherwise → keep everything (interop export of the whole graph).</li>
     * </ul>
     */
    private static boolean inScope(Long rowFactSheetId, Long factSheetId, boolean globalOnly) {
        if (globalOnly) return rowFactSheetId == null;
        if (factSheetId != null) return factSheetId.equals(rowFactSheetId);
        return true;
    }

    private static PortableNode toPortable(GraphNode node) {
        // getMetadata() is the store-agnostic view of metadataJson; previously this was
        // hard-coded to null, silently dropping all structured node metadata on export.
        java.util.Map<String, Object> meta = node.getMetadata();
        return new PortableNode(
                node.getExternalId(),
                node.getTitle(),
                node.getDescription(),
                node.getNodeType() == null ? "ENTITY" : node.getNodeType().name(),
                meta == null || meta.isEmpty() ? null : meta,
                node.getFactSheetId(),
                node.getNamedGraphId(),
                node.getConfidence(),
                node.getOccurredAt() == null ? null : node.getOccurredAt().toString());
    }

    private static PortableEdge toPortable(GraphEdge edge) {
        return new PortableEdge(
                edge.getSourceNode().getExternalId(),
                edge.getTargetNode().getExternalId(),
                edge.getEdgeType() == null ? "USER_DEFINED" : edge.getEdgeType().name(),
                edge.getWeight(),
                edge.getDescription(),
                edge.getProvenance(),
                edge.getConfidence(),
                edge.getOccurredAt() == null ? null : edge.getOccurredAt().toString());
    }
}
