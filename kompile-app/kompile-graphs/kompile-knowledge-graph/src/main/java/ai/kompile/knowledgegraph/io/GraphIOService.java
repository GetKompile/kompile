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
        PortableGraph graph = collect(factSheetId);
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
                    graphService.createNode(level, n.externalId(), n.title(), n.description(), n.metadata());
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

    private PortableGraph collect(Long factSheetId) {
        List<PortableNode> nodes = new ArrayList<>();
        List<PortableEdge> edges = new ArrayList<>();
        Set<String> emittedEdgeIds = new HashSet<>();

        for (NodeLevel level : NodeLevel.values()) {
            for (GraphNode node : graphService.searchNodes("", level, Integer.MAX_VALUE)) {
                if (factSheetId != null && !factSheetId.equals(node.getFactSheetId())) continue;
                nodes.add(toPortable(node));
                for (GraphEdge edge : graphService.getEdgesForNode(node.getNodeId())) {
                    if (factSheetId != null && !factSheetId.equals(edge.getFactSheetId())) continue;
                    if (!emittedEdgeIds.add(edge.getEdgeId())) continue;
                    edges.add(toPortable(edge));
                }
            }
        }
        return new PortableGraph(nodes, edges);
    }

    private static PortableNode toPortable(GraphNode node) {
        return new PortableNode(
                node.getExternalId(),
                node.getTitle(),
                node.getDescription(),
                node.getNodeType() == null ? "ENTITY" : node.getNodeType().name(),
                null);
    }

    private static PortableEdge toPortable(GraphEdge edge) {
        return new PortableEdge(
                edge.getSourceNode().getExternalId(),
                edge.getTargetNode().getExternalId(),
                edge.getEdgeType() == null ? "USER_DEFINED" : edge.getEdgeType().name(),
                edge.getWeight(),
                edge.getDescription());
    }
}
