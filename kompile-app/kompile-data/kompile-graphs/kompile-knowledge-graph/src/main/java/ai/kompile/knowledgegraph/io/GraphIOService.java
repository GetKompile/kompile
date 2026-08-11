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
import ai.kompile.graph.reasoning.debug.UnifiedGraphDebugRenderer;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import ai.kompile.knowledgegraph.io.format.CsvGraphExporter;
import ai.kompile.knowledgegraph.io.format.CsvGraphImporter;
import ai.kompile.knowledgegraph.io.format.CypherDumpExporter;
import ai.kompile.knowledgegraph.io.format.CypherDumpImporter;
import ai.kompile.knowledgegraph.io.format.GraphMLExporter;
import ai.kompile.knowledgegraph.io.format.JsonGraphExporter;
import ai.kompile.knowledgegraph.io.format.JsonGraphImporter;
import ai.kompile.knowledgegraph.io.format.JsonLdGraphExporter;
import ai.kompile.knowledgegraph.io.format.JsonLdGraphImporter;
import ai.kompile.knowledgegraph.io.format.NTriplesGraphExporter;
import ai.kompile.knowledgegraph.io.format.NTriplesGraphImporter;
import ai.kompile.knowledgegraph.io.format.PortableGraph;
import ai.kompile.knowledgegraph.io.format.TurtleGraphExporter;
import ai.kompile.knowledgegraph.io.format.TurtleGraphImporter;
import ai.kompile.knowledgegraph.io.model.EdgeMetadata;
import ai.kompile.knowledgegraph.io.model.ExportResult;
import ai.kompile.knowledgegraph.io.model.ImportResult;
import ai.kompile.knowledgegraph.io.model.PortableEdge;
import ai.kompile.knowledgegraph.io.model.PortableNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Orchestrates import/export across all graph formats.
 */
@Service
public class GraphIOService {

    private static final Logger log = LoggerFactory.getLogger(GraphIOService.class);

    private final KnowledgeGraphService graphService;
    private final ObjectMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private UnifiedGraphBridge unifiedGraphBridge;

    public GraphIOService(KnowledgeGraphService graphService, ObjectMapper mapper) {
        this.graphService = graphService;
        this.mapper = mapper;
    }

    public ImportResult importGraph(String format, byte[] payload, byte[] secondary) throws Exception {
        return importGraph(format, payload, secondary, null);
    }

    public ImportResult importGraph(String format, byte[] payload, byte[] secondary, Long factSheetId)
            throws Exception {
        if ("kgraph".equalsIgnoreCase(format)) {
            requireUnifiedBridge();
            UnifiedGraphBridge.ImportSummary summary = unifiedGraphBridge.importGraph(
                    UnifiedGraph.load(new ByteArrayInputStream(payload)), factSheetId);
            return new ImportResult("kgraph", summary.nodes(), 0, summary.edges(), 0, null);
        }
        PortableGraph graph = switch (format.toLowerCase()) {
            case "json" -> new JsonGraphImporter(mapper).parse(payload);
            case "jsonld", "json-ld" -> new JsonLdGraphImporter(mapper).parse(payload);
            case "csv" -> new CsvGraphImporter().parse(payload, secondary);
            case "cypher" -> new CypherDumpImporter().parse(payload);
            case "ntriples", "nt" -> new NTriplesGraphImporter().parse(payload);
            case "turtle", "ttl" -> new TurtleGraphImporter().parse(payload);
            default -> throw new IllegalArgumentException("Unknown import format: " + format);
        };
        return apply(format, graph);
    }

    public ExportResult exportGraph(String format, Long factSheetId) throws Exception {
        if (isUnifiedFormat(format)) {
            return serializeUnified(format, factSheetId);
        }
        return serialize(format, collect(factSheetId, false));
    }

    /**
     * Export only the nodes/edges belonging to a specific named graph within a fact sheet.
     * Nodes are included when their indexed {@code namedGraphId} field or metadata value equals
     * {@code namedGraphId}. Both representations are persisted by the Lucene graph store.
     *
     * @param format      the target export format (e.g. "json", "ntriples", "turtle")
     * @param factSheetId the owning fact sheet (may be null to scan all fact sheets)
     * @param namedGraphId the named graph to restrict to; must not be null
     */
    public ExportResult exportGraph(String format, Long factSheetId, String namedGraphId) throws Exception {
        if (namedGraphId == null) {
            return exportGraph(format, factSheetId);
        }
        if (isUnifiedFormat(format)) {
            return serializeUnified(format, factSheetId, namedGraphId);
        }
        return serialize(format, collect(factSheetId, false, namedGraphId));
    }

    /**
     * Export only the nodes/edges not scoped to any fact sheet (factSheetId == null).
     * Complements the per-fact-sheet exports so a portability dump covers the whole
     * graph exactly once.
     */
    public ExportResult exportGlobalGraph(String format) throws Exception {
        if (isUnifiedFormat(format)) {
            return serializeUnified(format, null);
        }
        return serialize(format, collect(null, true));
    }

    private ExportResult serializeUnified(String format, Long factSheetId) throws Exception {
        return serializeUnified(format, factSheetId, null);
    }

    private ExportResult serializeUnified(String format, Long factSheetId, String namedGraphId) throws Exception {
        requireUnifiedBridge();
        UnifiedGraph graph = unifiedGraphBridge.export(factSheetId, namedGraphId);
        UnifiedGraphDebugRenderer.Options options = UnifiedGraphDebugRenderer.Options.defaults();
        return switch (format.toLowerCase(java.util.Locale.ROOT)) {
            case "kgraph" -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                graph.save(out);
                yield new ExportResult("kgraph", graph.entityCount(), graph.relationCount(),
                        out.toByteArray(), "application/octet-stream", "graph.kgraph");
            }
            case "ascii", "txt" -> new ExportResult("ascii", graph.entityCount(), graph.relationCount(),
                    UnifiedGraphDebugRenderer.toAscii(graph, options)
                            .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    "text/plain;charset=US-ASCII", "graph.txt");
            case "png" -> new ExportResult("png", graph.entityCount(), graph.relationCount(),
                    UnifiedGraphDebugRenderer.toPngBundle(graph, options), "application/zip", "graph-debug.png.zip");
            default -> throw new IllegalArgumentException("Unknown unified export format: " + format);
        };
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
            case "ntriples", "nt" -> new ExportResult("ntriples",
                    graph.nodes().size(),
                    graph.edges().size(),
                    new NTriplesGraphExporter().toBytes(graph),
                    "application/n-triples",
                    "graph.nt");
            case "turtle", "ttl" -> new ExportResult("turtle",
                    graph.nodes().size(),
                    graph.edges().size(),
                    new TurtleGraphExporter().toBytes(graph),
                    "text/turtle",
                    "graph.ttl");
            default -> throw new IllegalArgumentException("Unknown export format: " + format);
        };
    }

    private boolean isUnifiedFormat(String format) {
        return "kgraph".equalsIgnoreCase(format)
                || "ascii".equalsIgnoreCase(format)
                || "txt".equalsIgnoreCase(format)
                || "png".equalsIgnoreCase(format);
    }

    private void requireUnifiedBridge() {
        if (unifiedGraphBridge == null) {
            throw new IllegalStateException("Unified graph export/import is not available in this graph context");
        }
    }

    /**
     * [L-7] Stream a fact sheet's graph as JSON straight to {@code out} without materializing the
     * whole {@link PortableGraph} or the full serialized {@code byte[]} in memory, so very large
     * graphs export without OOM. Nodes and edges are written in two passes (the JSON object needs all
     * nodes before any edge), so peak memory is bounded by a single {@code NodeLevel}'s node list
     * rather than the entire graph. Output is structurally identical to {@link #exportGraph} (same
     * {@code PortableNode}/{@code PortableEdge} serialization + {@code schemaVersion} envelope).
     *
     * <p>Non-JSON formats have no streaming path and fall back to the in-memory bytes. The caller
     * owns {@code out} — it is flushed but not closed. (A fully O(1) path would additionally need a
     * cursor/paged accessor on {@code KnowledgeGraphService}; this still enumerates per-level lists.)</p>
     */
    public void exportGraphStreaming(String format, Long factSheetId, OutputStream out) throws Exception {
        if (!"json".equalsIgnoreCase(format)) {
            out.write(exportGraph(format, factSheetId).data());
            return;
        }
        JsonGenerator gen =
                mapper.getFactory().createGenerator(out, JsonEncoding.UTF8);
        gen.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        gen.useDefaultPrettyPrinter();
        try {
            gen.writeStartObject();
            gen.writeArrayFieldStart("nodes");
            traverse(factSheetId, false, null, n -> writeValue(gen, n), null);
            gen.writeEndArray();
            gen.writeArrayFieldStart("edges");
            traverse(factSheetId, false, null, null, e -> writeValue(gen, e));
            gen.writeEndArray();
            gen.writeStringField("schemaVersion", PortableGraph.CURRENT_SCHEMA_VERSION);
            gen.writeEndObject();
        } finally {
            gen.close(); // flushes; does not close `out` (AUTO_CLOSE_TARGET disabled)
        }
    }

    /** Write one Portable* object through the shared mapper, rewrapping the checked IOException. */
    private void writeValue(JsonGenerator gen, Object value) {
        try {
            mapper.writeValue(gen, value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ImportResult apply(String format, PortableGraph graph) {
        int created = 0, updated = 0, edgeCount = 0, errors = 0;
        List<String> errorMessages = new ArrayList<>();

        // [L-8] Detect a file written by an incompatible future schema. A null version is a
        // pre-versioning file (treated as compatible); the current version is fine.
        String schemaVersion = graph.schemaVersion();
        if (schemaVersion != null && !PortableGraph.CURRENT_SCHEMA_VERSION.equals(schemaVersion)) {
            log.warn("Importing graph with schemaVersion '{}' but this build expects '{}'; "
                    + "node/edge field semantics may differ.", schemaVersion, PortableGraph.CURRENT_SCHEMA_VERSION);
        }

        for (PortableNode n : graph.nodes()) {
            try {
                NodeLevel level = parseLevel(n.nodeType());
                Optional<GraphNode> existing = graphService.getNodeByExternalId(n.externalId(), level);
                if (existing.isPresent()) {
                    // [H-1] On update, propagate quality/scoping fields that live in metadata
                    // on the matrix/vector store path so they are not silently reset.
                    Map<String, Object> updatedMeta = mergeNodeQualityFields(n.metadata(), n);
                    graphService.updateNode(existing.get().getNodeId(), n.title(), n.description(), updatedMeta);
                    updated++;
                } else {
                    // [H-1] Merge confidence, namedGraphId, occurredAt into metadata before
                    // createNode so the matrix/vector store (which routes these via metadata)
                    // persists them on the initial write.
                    Map<String, Object> enrichedMeta = mergeNodeQualityFields(n.metadata(), n);
                    // Restore fact-sheet scope when present so rehydrated graphs land
                    // in the correct fact sheet (the factSheetId-aware overload is a no-op
                    // on stores that don't scope, preserving interop-import behavior).
                    if (n.factSheetId() != null) {
                        graphService.createNode(level, n.externalId(), n.title(), n.description(),
                                enrichedMeta, n.factSheetId());
                    } else {
                        graphService.createNode(level, n.externalId(), n.title(), n.description(), enrichedMeta);
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
                // [H-2] Pass confidence and provenance through to the richer overload so the
                // matrix/vector store can persist them (as edge metadata) rather than
                // silently discarding them after a snapshot restore.
                graphService.createEdgeWithMetadata(fromUuid, toUuid, parseEdgeType(e.edgeType()),
                        e.weight() == null ? 1.0 : e.weight(),
                        e.relationType(), e.description(),
                        buildEdgeMetaJson(e),
                        null,   // EdgeProvenance enum: freetext provenance stored in metaJson above
                        e.factSheetId());  // [M-1] restore per-edge fact-sheet scope
                edgeCount++;
            } catch (Exception ex) {
                errors++;
                errorMessages.add("Edge '" + e.fromExternalId() + "->" + e.toExternalId() + "': " + ex.getMessage());
                log.warn("Failed to import edge {}->{}: {}", e.fromExternalId(), e.toExternalId(), ex.getMessage());
            }
        }
        return new ImportResult(format, created, updated, edgeCount, errors, errorMessages);
    }

    /**
     * [H-1] Merge portable quality/scoping fields (confidence, namedGraphId, occurredAt) into a
     * copy of the node's metadata map. These three fields are carried in {@link PortableNode} and
     * exported by {@link #toPortable(GraphNode)}, but the base {@code createNode} / {@code updateNode}
     * interface only accepts a metadata map — the matrix/vector-store backend reads them from
     * metadata keys {@code "confidence"}, {@code "namedGraphId"}, and {@code "occurredAt"}. Merging
     * here preserves round-trip fidelity without requiring a new interface method.
     */
    private static Map<String, Object> mergeNodeQualityFields(
            Map<String, Object> base, PortableNode n) {
        Map<String, Object> result = base != null
                ? new LinkedHashMap<>(base)
                : new LinkedHashMap<>();
        if (n.confidence() != null)   result.put("confidence",   n.confidence());
        if (n.namedGraphId() != null) result.put("namedGraphId", n.namedGraphId());
        if (n.occurredAt() != null)   result.put("occurredAt",   n.occurredAt());
        return result.isEmpty() ? null : result;
    }

    /**
     * [H-2 / M-3 / M-4 / M-7] Build the typed {@link EdgeMetadata} payload carrying the edge's quality
     * and extended fields so {@code createEdgeWithMetadata} can persist them: confidence and provenance
     * (H-2), bidirectional traversal flag (M-3), SHARED_ENTITY payload + similarity score (M-4),
     * explicit label, and the edge's own free-form metadata (M-7). The edge's arbitrary metadata is
     * nested under {@link EdgeMetadata#metadata()} so it can't collide with the typed fields.
     */
    private String buildEdgeMetaJson(PortableEdge e) {
        Map<String, Object> own = null;
        if (e.metadataJson() != null && !e.metadataJson().isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = mapper.readValue(e.metadataJson(), Map.class);
                own = parsed;
            } catch (Exception ignore) {
                // Not a JSON object — keep it raw under a sentinel key so it isn't silently dropped.
                own = Map.of("metadataJson", e.metadataJson());
            }
        }
        EdgeMetadata meta = EdgeMetadata.builder()
                .confidence(e.confidence())
                .provenance(e.provenance())
                .occurredAt(e.occurredAt())
                .bidirectional(e.bidirectional())
                .label(e.label())
                .sharedEntitiesJson(e.sharedEntitiesJson())
                .similarityScore(e.similarityScore())
                .metadata(own)
                .provenanceType(e.provenanceType())
                .build();
        if (meta.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(meta);
        } catch (Exception ex) {
            log.debug("Failed to serialize edge metadata: {}", ex.getMessage());
            return null;
        }
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

    /**
     * Collect nodes/edges scoped to a specific namedGraphId.
     * Delegates to {@link #collect(Long, boolean)} and then filters the result.
     */
    private PortableGraph collect(Long factSheetId, boolean globalOnly, String namedGraphId) {
        List<PortableNode> nodes = new ArrayList<>();
        List<PortableEdge> edges = new ArrayList<>();
        traverse(factSheetId, globalOnly, namedGraphId, nodes::add, edges::add);
        return new PortableGraph(nodes, edges);
    }

    /**
     * [L-7] Shared graph traversal used by both the in-memory {@link #collect} and the streaming
     * export. Applies the scope ({@link #inScope}), staleness ({@link #isStale}) and optional
     * namedGraph filters once, feeding each surviving {@link PortableNode}/{@link PortableEdge} to
     * the sinks. Either sink may be {@code null} to skip it — the streaming export writes nodes then
     * edges in two passes, since the JSON layout requires all nodes before any edge. Edges are
     * de-duplicated within a single call.
     */
    private void traverse(Long factSheetId, boolean globalOnly, String namedGraphId,
                          Consumer<PortableNode> nodeSink,
                          Consumer<PortableEdge> edgeSink) {
        Set<String> emittedEdgeIds = new HashSet<>();
        for (NodeLevel level : NodeLevel.values()) {
            // Enumerate every node of this level directly. (searchNodes("") is a query path — on the
            // vector-store-backed graph an empty query is not guaranteed to return all nodes, which
            // would silently truncate a portability export.)
            for (GraphNode node : graphService.getNodesByType(level)) {
                if (!inScope(node.getFactSheetId(), factSheetId, globalOnly)) continue;
                // [H-3] Exclude soft-deleted (stale) nodes so they are not resurrected on restore.
                if (isStale(node)) continue;
                if (namedGraphId != null
                        && !namedGraphId.equals(node.getNamedGraphId())
                        && !namedGraphIdMatchesMeta(node, namedGraphId)) {
                    continue;
                }
                if (nodeSink != null) {
                    nodeSink.accept(toPortable(node));
                }
                if (edgeSink != null) {
                    for (GraphEdge edge : graphService.getEdgesForNode(node.getNodeId())) {
                        if (!inScope(edge.getFactSheetId(), factSheetId, globalOnly)) continue;
                        if (!emittedEdgeIds.add(edge.getEdgeId())) continue;
                        edgeSink.accept(toPortable(edge));
                    }
                }
            }
        }
    }

    /**
     * Check namedGraphId via the metadata map (matrix/vector-store path where
     * {@link GraphNode#getNamedGraphId()} may not be populated directly).
     */
    private static boolean namedGraphIdMatchesMeta(GraphNode node, String namedGraphId) {
        Map<String, Object> meta = node.getMetadata();
        return meta != null && namedGraphId.equals(meta.get("namedGraphId"));
    }

    private PortableGraph collect(Long factSheetId, boolean globalOnly) {
        List<PortableNode> nodes = new ArrayList<>();
        List<PortableEdge> edges = new ArrayList<>();
        traverse(factSheetId, globalOnly, null, nodes::add, edges::add);
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

    /**
     * [H-3] Returns {@code true} when a node should be excluded from the portable export because
     * it has been soft-deleted. On the JPA path, {@link GraphNode#getStale()} is set to
     * {@code true}. On the matrix/vector-store path, staleness is stored as {@code "_stale": true}
     * inside the node's metadata map (see {@code MatrixKnowledgeGraphService.pruneNodes}).
     */
    private static boolean isStale(GraphNode node) {
        if (Boolean.TRUE.equals(node.getStale())) {
            return true;
        }
        Map<String, Object> meta = node.getMetadata();
        return meta != null && Boolean.TRUE.equals(meta.get("_stale"));
    }

    private static PortableNode toPortable(GraphNode node) {
        // getMetadata() is the store-agnostic view of metadataJson; previously this was
        // hard-coded to null, silently dropping all structured node metadata on export.
        Map<String, Object> meta = node.getMetadata();
        return new PortableNode(
                node.getExternalId(),
                node.getTitle(),
                node.getDescription(),
                node.getNodeType() == null ? "ENTITY" : node.getNodeType().name(),
                meta == null || meta.isEmpty() ? null : meta,
                node.getFactSheetId(),
                node.getNamedGraphId(),
                node.getConfidence(),
                formatOccurredAt(node.getOccurredAt()));
    }

    /**
     * [M-6] Serialize {@code occurredAt} with an explicit ISO-8601 local date-time formatter rather
     * than {@code LocalDateTime.toString()}, so the on-disk format is stable and intent-explicit
     * (and won't silently change if the field type ever changes). {@code occurredAt} is a zone-less
     * {@link LocalDateTime} by design — no timezone is implied.
     */
    private static String formatOccurredAt(LocalDateTime occurredAt) {
        return occurredAt == null ? null
                : occurredAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
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
                formatOccurredAt(edge.getOccurredAt()),
                edge.getRelationType(),
                // [M-1/M-3/M-4/M-7] Extended edge fields — persisted on GraphEdge but previously
                // dropped on export, so they were lost on every snapshot restore / git clone.
                edge.getFactSheetId(),
                edge.getBidirectional(),
                edge.getLabel(),
                edge.getSharedEntitiesJson(),
                edge.getSimilarityScore(),
                edge.getMetadataJson(),
                // [M-10] typed provenance classification (distinct from the freetext source provenance).
                edge.getProvenanceType() == null ? null : edge.getProvenanceType().name());
    }
}
