/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.CRC32;

/**
 * Closeable, bounded-access view of a {@code .kgraph} archive.
 *
 * <p>{@link UnifiedGraph#load(Path)} remains the compatibility API that materializes every relation.
 * This view exposes v3 compact links sequentially using only endpoint/type dictionaries plus one
 * current link. Callers must explicitly use this cursor; the compatibility loader materializes a
 * {@link UnifiedGraph}, while the archive migrator transcodes legacy topology directly.</p>
 */
public final class UnifiedGraphArchive implements AutoCloseable {

    private static final int DEFAULT_MAX_STRING_BYTES = Math.max(1,
            Integer.getInteger("kompile.graph.maxTopologyStringBytes", 16 * 1024 * 1024));

    private final ZipFile zip;
    private final Path path;
    private final UnifiedGraphReader.StableArchiveReference stableReference;
    private final UnifiedGraphReader.Limits limits;
    private final Map<String, Object> manifest;
    private final int formatVersion;
    private final int baseEntityCount;
    private final int baseLinkCount;
    private final UnifiedGraphMutationJournal.Snapshot journal;
    private final int topologyNodeCount;
    private final int topologyTypeCount;
    private boolean closed;

    private UnifiedGraphArchive(
            ZipFile zip,
            Path path,
            UnifiedGraphReader.StableArchiveReference stableReference,
            UnifiedGraphReader.Limits limits,
            Map<String, Object> manifest) throws IOException {
        this.zip = zip;
        this.path = path;
        this.stableReference = stableReference;
        this.limits = limits;
        this.manifest = manifest;
        this.formatVersion = integer(manifest.get("formatVersion"), "formatVersion");
        Map<?, ?> counts = object(manifest.get("counts"), "counts");
        this.baseEntityCount = integer(counts.get("entities"), "counts.entities");
        this.baseLinkCount = integer(counts.get("relations"), "counts.relations");
        this.journal = UnifiedGraphMutationJournal.load(path);
        if (formatVersion >= 3) {
            Map<?, ?> topology = object(manifest.get("topology"), "topology");
            this.topologyNodeCount = integer(topology.get("nodeCount"), "topology.nodeCount");
            this.topologyTypeCount = integer(
                    topology.get("relationTypeCount"), "topology.relationTypeCount");
        } else {
            this.topologyNodeCount = 0;
            this.topologyTypeCount = 0;
        }
    }

    public static UnifiedGraphArchive open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        UnifiedGraphReader.Limits limits = UnifiedGraphReader.Limits.systemDefaults();
        UnifiedGraphReader.StableArchiveReference stable =
                UnifiedGraphReader.stableArchiveReference(path, limits);
        ZipFile zip = null;
        try {
            Map<String, Object> manifest = UnifiedGraphReader.inspectManifestStable(stable.path(), limits);
            zip = new ZipFile(stable.path().toFile());
            stable.requireUnchanged();
            return new UnifiedGraphArchive(
                    zip, path.toAbsolutePath().normalize(), stable, limits, manifest);
        } catch (IOException | RuntimeException failure) {
            if (zip != null) zip.close();
            stable.close();
            throw failure;
        }
    }

    public int formatVersion() { return formatVersion; }
    public int entityCount() { return checkedLogicalCount(baseEntityCount, journal.entityDelta(), "entities"); }
    public int linkCount() { return checkedLogicalCount(baseLinkCount, journal.relationDelta(), "relations"); }
    int baseLinkCount() { return baseLinkCount; }
    public boolean hasJournalMutations() { return !journal.isEmpty(); }
    public UnifiedGraphMutationJournal.Snapshot journalSnapshot() { return journal; }
    public boolean hasCompactTopology() { return formatVersion >= 3; }
    public boolean hasAdjacencyIndex() {
        Object raw = manifest.get("topology");
        return raw instanceof Map<?, ?> topology
                && UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY.equals(topology.get("adjacencyEntry"))
                && zip.getEntry(UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY) != null;
    }
    public void validateAdjacencyIndex() throws IOException {
        if (!hasAdjacencyIndex()) throw new IOException("Compact graph has no adjacency index");
        try (CompactAdjacencyCodec.Index index = openAdjacencyIndex()) {
            if (index.nodeCount() != topologyNodeCount || index.linkCount() != baseLinkCount) {
                throw new IOException("Compact adjacency count mismatch");
            }
        }
    }
    public Map<String, Object> manifest() { return manifest; }

    /**
     * Materialize the archive for an explicitly requested full-graph reasoning operation.
     *
     * <p>Archive-native queries must remain bounded and streaming by default. Callers have to
     * opt into this compatibility path, and the logical topology counts are checked before any
     * graph objects are allocated. The regular loader is used so journals, analysis assets, and
     * learned metadata have exactly the same semantics as the non-archive query path.</p>
     */
    public UnifiedGraph materializeForReasoning(int maxEntities, int maxRelations) throws IOException {
        requireOpen();
        if (maxEntities < 1 || maxRelations < 1) {
            throw new IllegalArgumentException("Reasoning materialization limits must be positive");
        }
        int entities = entityCount();
        int relations = linkCount();
        if (entities > maxEntities || relations > maxRelations) {
            throw new IOException("Explicit graph reasoning exceeds archive materialization budget: "
                    + entities + " entities/" + relations + " relations (limits "
                    + maxEntities + "/" + maxRelations + ")");
        }
        UnifiedGraph graph = UnifiedGraph.load(path);
        stableReference.requireUnchanged();
        return graph;
    }

    /** Open a bounded row cursor over entities.jsonl. Only the current entity is materialized. */
    public EntityCursor openEntities() throws IOException {
        return new ArchiveEntityCursor(openEntityRecords());
    }

    /** Open entity rows together with their inline subjective opinion. */
    public EntityRecordCursor openEntityRecords() throws IOException {
        EntityRecordCursor base = openBaseEntityRecords();
        return journal.isEmpty() ? base : new OverlayEntityRecordCursor(base, journal);
    }

    private EntityRecordCursor openBaseEntityRecords() throws IOException {
        requireOpen();
        ZipEntry entry = zip.getEntry(UnifiedGraphFormat.ENTRY_ENTITIES);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("Unified graph is missing entities.jsonl");
        }
        UnifiedGraphReader.ExpansionBudget budget =
                new UnifiedGraphReader.ExpansionBudget(limits.maxTotalBytes());
        InputStream input = new UnifiedGraphReader.BudgetInputStream(
                zip.getInputStream(entry), budget, entry.getName());
        return new ArchiveEntityRecordCursor(input, baseEntityCount, limits.maxJsonlRowChars());
    }

    /** Open a row-at-a-time vector layer by case-insensitive manifest name. */
    public VectorBlobCodec.RowCursor openVectorRows(String layerName) throws IOException {
        requireOpen();
        if (layerName == null || layerName.isBlank()) return null;
        Map<String, Object> descriptor = vectorLayerDescriptor(layerName);
        if (descriptor == null) return null;
        Object rawEntry = descriptor.get("entry");
        if (!(rawEntry instanceof String entryName)) {
            throw new IOException("Invalid vector-layer entry descriptor");
        }
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null || entry.isDirectory()) throw new IOException("Missing vector layer " + entryName);
        UnifiedGraphReader.ExpansionBudget budget =
                new UnifiedGraphReader.ExpansionBudget(limits.maxTotalBytes());
        return VectorBlobCodec.openRows(new UnifiedGraphReader.BudgetInputStream(
                zip.getInputStream(entry), budget, entryName));
    }

    /** Immutable manifest vector-layer descriptors in archive order. */
    public List<Map<String, Object>> vectorLayerDescriptors() throws IOException {
        Object raw = manifest.get("vectorLayers");
        if (!(raw instanceof List<?> layers)) throw new IOException("Invalid vectorLayers manifest field");
        List<Map<String, Object>> result = new ArrayList<>(layers.size());
        for (Object item : layers) {
            if (!(item instanceof Map<?, ?> value)) throw new IOException("Invalid vector-layer descriptor");
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> field : value.entrySet()) {
                if (field.getKey() != null) copy.put(String.valueOf(field.getKey()), field.getValue());
            }
            result.add(Collections.unmodifiableMap(copy));
        }
        return List.copyOf(result);
    }

    public Map<String, Object> schemaIndex() throws IOException {
        return MiniJson.parseObject(new String(readEntry(
                UnifiedGraphFormat.ENTRY_SCHEMA_INDEX, limits.maxManifestBytes()),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    public List<ArtifactInfo> artifacts() throws IOException {
        Object raw = manifest.get("artifacts");
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> names)) throw new IOException("Invalid artifacts manifest field");
        List<ArtifactInfo> result = new ArrayList<>(names.size());
        for (Object item : names) {
            if (!(item instanceof String name) || name.isBlank()) {
                throw new IOException("Invalid artifact name");
            }
            ZipEntry entry = zip.getEntry(UnifiedGraphFormat.modelEntry(name));
            if (entry == null || entry.isDirectory()) throw new IOException("Missing artifact " + name);
            result.add(new ArtifactInfo(name, entry.getSize()));
        }
        return List.copyOf(result);
    }

    /** Read at most {@code maxBytes} of one declared artifact without allocating the full entry. */
    public ArtifactContent readArtifact(String name, int maxBytes) throws IOException {
        requireOpen();
        if (name == null || name.isBlank() || maxBytes < 0) return null;
        ArtifactInfo match = artifacts().stream().filter(item -> item.name().equals(name)).findFirst().orElse(null);
        if (match == null) return null;
        ZipEntry entry = zip.getEntry(UnifiedGraphFormat.modelEntry(name));
        int capacity = (int) Math.min((long) maxBytes, Math.max(0L, match.bytes()));
        ByteArrayOutputStream output = new ByteArrayOutputStream(capacity);
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] buffer = new byte[Math.min(8_192, Math.max(1, maxBytes))];
            int remaining = maxBytes;
            while (remaining > 0) {
                int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
                if (count < 0) break;
                output.write(buffer, 0, count);
                remaining -= count;
            }
        }
        return new ArtifactContent(output.toByteArray(), match.bytes(), match.bytes() > maxBytes);
    }

    /**
     * Stream analysis state compatible with {@code current} into that graph without materializing
     * this archive's topology. Primary/additional vectors are filtered by current ids; inline
     * opinions, weight maps, and artifacts are retained under the same rules as the legacy
     * materialized lifecycle path.
     */
    public void copyCompatibleAnalysisAssetsTo(UnifiedGraph current) throws IOException {
        Objects.requireNonNull(current, "current");
        for (Map<String, Object> descriptor : vectorLayerDescriptors()) {
            String name = String.valueOf(descriptor.get("name"));
            VectorLayer.Target target;
            Dtype dtype;
            try {
                target = VectorLayer.Target.valueOf(String.valueOf(descriptor.get("target")));
                dtype = Dtype.valueOf(String.valueOf(descriptor.get("dtype")));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid vector descriptor " + name, invalid);
            }
            int dimension = descriptor.get("dim") instanceof Number number ? number.intValue() : 0;
            VectorLayer retained = new VectorLayer(name, target, dimension, dtype);
            try (VectorBlobCodec.RowCursor cursor = openVectorRows(name)) {
                VectorBlobCodec.VectorRow row;
                while ((row = cursor.next()) != null) {
                    if (target == VectorLayer.Target.ENTITY && current.entity(row.id()).isEmpty()) continue;
                    if (target == VectorLayer.Target.RELATION && current.relation(row.id()).isEmpty()) continue;
                    if (UnifiedGraphFormat.PRIMARY_ENTITY_VECTORS.equals(name)) {
                        GraphEntity entity = current.entity(row.id()).orElse(null);
                        if (entity != null) current.addEntity(new SimpleGraphEntity(
                                entity.id(), entity.type(), entity.label(), entity.weight(), entity.confidence(),
                                entity.tags(), row.values(), entity.timestamp(), entity.attributes()));
                    } else if (UnifiedGraphFormat.PRIMARY_RELATION_VECTORS.equals(name)) {
                        GraphRelation relation = current.relation(row.id()).orElse(null);
                        if (relation != null) current.addRelation(new SimpleGraphRelation(
                                relation.id(), relation.sourceId(), relation.targetId(), relation.type(),
                                relation.weight(), relation.confidence(), relation.directed(), relation.tags(),
                                row.values(), relation.timestamp(), relation.attributes()));
                    } else {
                        retained.put(row.id(), row.values());
                    }
                }
            }
            if (!UnifiedGraphFormat.isReservedVectorLayerName(name) && !retained.isEmpty()) {
                current.putVectorLayer(retained);
            }
        }

        try (EntityRecordCursor cursor = openEntityRecords()) {
            EntityRecord record;
            while ((record = cursor.next()) != null) {
                if (record.opinion() != null && current.entity(record.entity().id()).isPresent()) {
                    current.putEntityOpinion(record.entity().id(), record.opinion());
                }
            }
        }
        try (LinkCursor cursor = openLinks()) {
            Link link;
            while ((link = cursor.next()) != null) {
                if (link.opinion() != null && current.relation(link.id()).isPresent()) {
                    current.putRelationOpinion(link.id(), link.opinion());
                }
            }
        }
        readWeightMaps().forEach(current::putWeightMap);
        for (ArtifactInfo artifact : artifacts()) {
            current.putArtifact(artifact.name(), readEntry(
                    UnifiedGraphFormat.modelEntry(artifact.name()), limits.maxEntryBytes()));
        }
    }

    private Map<String, Map<String, Double>> readWeightMaps() throws IOException {
        ZipEntry entry = zip.getEntry(UnifiedGraphFormat.ENTRY_WEIGHTS);
        if (entry == null) return Map.of();
        Map<String, Object> root;
        try {
            root = MiniJson.parseObject(new String(readEntry(
                    UnifiedGraphFormat.ENTRY_WEIGHTS, limits.maxEntryBytes()),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid weights.json", malformed);
        }
        Map<String, Map<String, Double>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> layer : root.entrySet()) {
            if (!(layer.getValue() instanceof Map<?, ?> values)) {
                throw new IOException("Invalid weight map " + layer.getKey());
            }
            Map<String, Double> decoded = new LinkedHashMap<>();
            for (Map.Entry<?, ?> value : values.entrySet()) {
                if (value.getKey() == null || !(value.getValue() instanceof Number number)) {
                    throw new IOException("Invalid weight entry in " + layer.getKey());
                }
                decoded.put(String.valueOf(value.getKey()), number.doubleValue());
            }
            result.put(layer.getKey(), decoded);
        }
        return result;
    }

    private Map<String, Object> vectorLayerDescriptor(String name) throws IOException {
        for (Map<String, Object> descriptor : vectorLayerDescriptors()) {
            if (name.equalsIgnoreCase(String.valueOf(descriptor.get("name")))) return descriptor;
        }
        return null;
    }

    private byte[] readEntry(String name, long maxBytes) throws IOException {
        requireOpen();
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory() || entry.getSize() < 0 || entry.getSize() > maxBytes
                || entry.getSize() > Integer.MAX_VALUE) {
            throw new IOException("Invalid or oversized unified graph entry " + name);
        }
        try (InputStream input = zip.getInputStream(entry)) {
            return input.readNBytes((int) entry.getSize());
        }
    }

    /** Stream and verify the declared size and CRC of every archive entry using this stable handle. */
    public void validateAllEntries() throws IOException {
        requireOpen();
        UnifiedGraphReader.ExpansionBudget budget =
                new UnifiedGraphReader.ExpansionBudget(limits.maxTotalBytes());
        var entries = zip.entries();
        byte[] buffer = new byte[8_192];
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            CRC32 crc = new CRC32();
            long count = 0;
            try (InputStream input = new UnifiedGraphReader.BudgetInputStream(
                    zip.getInputStream(entry), budget, entry.getName())) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) continue;
                    crc.update(buffer, 0, read);
                    count += read;
                }
            }
            if (entry.getSize() < 0 || entry.getCrc() < 0
                    || count != entry.getSize() || crc.getValue() != entry.getCrc()) {
                throw new IOException("Unified graph entry size/CRC mismatch: " + entry.getName());
            }
        }
    }

    /** Open a sequential compact-link cursor. V1/v2 archives have no compact link table. */
    public LinkCursor openLinks() throws IOException {
        LinkCursor base = openBaseLinks();
        return journal.isEmpty() ? base : new OverlayLinkCursor(base, journal);
    }

    private LinkCursor openBaseLinks() throws IOException {
        requireOpen();
        if (!hasCompactTopology()) {
            throw new IOException("Unified-graph format v" + formatVersion + " has no compact topology");
        }
        ZipEntry entry = zip.getEntry(UnifiedGraphFormat.ENTRY_COMPACT_LINKS);
        if (entry == null) throw new IOException("Compact topology entry is missing");
        if (entry.getMethod() != ZipEntry.STORED) {
            throw new IOException("Compact topology entry must use ZIP STORED mode");
        }
        ZipEntry propertyEntry = zip.getEntry(UnifiedGraphFormat.ENTRY_RELATION_PROPERTIES);
        UnifiedGraphReader.ExpansionBudget budget =
                new UnifiedGraphReader.ExpansionBudget(limits.maxTotalBytes());
        InputStream input = new UnifiedGraphReader.BudgetInputStream(
                zip.getInputStream(entry), budget, entry.getName());
        InputStream properties = null;
        try {
            properties = propertyEntry == null ? null : new UnifiedGraphReader.BudgetInputStream(
                    zip.getInputStream(propertyEntry), budget, propertyEntry.getName());
            CompactTopologyCodec.RecordCursor cursor = CompactTopologyCodec.openRecordCursor(
                    input, properties, topologyNodeCount, topologyTypeCount, baseLinkCount,
                    DEFAULT_MAX_STRING_BYTES, limits.maxJsonlRowChars());
            return new ArchiveLinkCursor(cursor);
        } catch (IOException | RuntimeException failure) {
            input.close();
            if (properties != null) properties.close();
            throw failure;
        }
    }

    /** Read one endpoint's logical incident links through CSR plus the mutation overlay. */
    public List<Link> incidentLinks(
            String nodeId, GraphQueryEngine.Direction direction, int maximum) throws IOException {
        requireOpen();
        int limit = Math.max(1, Math.min(1_000_000, maximum));
        LinkedHashMap<String, Link> result = new LinkedHashMap<>();
        if (!hasAdjacencyIndex()) {
            try (LinkCursor cursor = openLinks()) {
                Link link;
                while ((link = cursor.next()) != null) {
                    if (!isIncident(link, nodeId, direction)) continue;
                    if (result.size() >= limit) throw new IOException("Incident link limit exceeded");
                    result.put(link.id(), link);
                }
            }
            return List.copyOf(result.values());
        }

        LinkedHashMap<Integer, CompactAdjacencyCodec.RawLink> selected = new LinkedHashMap<>();
        try (CompactAdjacencyCodec.Index index = openAdjacencyIndex()) {
            index.forEachAdjacent(nodeId, direction, ordinal -> {
                CompactAdjacencyCodec.RawLink link = index.link(ordinal);
                if (journal.relationStates().containsKey(link.id())) return;
                if (selected.size() + result.size() >= limit) {
                    throw new IOException("Incident link limit exceeded");
                }
                selected.putIfAbsent(ordinal, link);
            });
            Map<Integer, CompactTopologyCodec.PropertyData> properties =
                    readSelectedProperties(selected.keySet());
            for (Map.Entry<Integer, CompactAdjacencyCodec.RawLink> item : selected.entrySet()) {
                CompactAdjacencyCodec.RawLink raw = item.getValue();
                CompactTopologyCodec.PropertyData property = properties.getOrDefault(
                        item.getKey(), CompactTopologyCodec.PropertyData.EMPTY);
                if (raw.hasProperties() && !properties.containsKey(item.getKey())) {
                    throw new IOException("Missing compact property row for link " + item.getKey());
                }
                Link link = new Link(raw.index(), raw.id(), index.nodeId(raw.source()),
                        index.nodeId(raw.target()), index.type(raw.type()), raw.weight(),
                        raw.confidence(), raw.directed(), raw.hasProperties(), property.tags(),
                        property.timestamp(), property.attributes(), property.opinion());
                result.put(link.id(), link);
            }
        }
        for (Link link : journal.incident(nodeId, direction)) {
            if (result.size() >= limit && !result.containsKey(link.id())) {
                throw new IOException("Incident link limit exceeded");
            }
            result.put(link.id(), link);
        }
        return List.copyOf(result.values());
    }

    private static boolean isIncident(
            Link link, String nodeId, GraphQueryEngine.Direction direction) {
        GraphQueryEngine.Direction effective = direction == null
                ? GraphQueryEngine.Direction.BOTH : direction;
        if (!link.directed()) {
            return link.sourceId().equals(nodeId) || link.targetId().equals(nodeId);
        }
        return effective != GraphQueryEngine.Direction.INCOMING && link.sourceId().equals(nodeId)
                || effective != GraphQueryEngine.Direction.OUTGOING && link.targetId().equals(nodeId);
    }

    /**
     * Materialize only a bounded exact-id neighborhood from compact topology. Link scans remain
     * sequential and relation heap usage is capped by {@code maxEdges}; entity JSON is scanned once
     * after the topology traversal and only selected entities are retained.
     */
    public UnifiedGraph materializeNeighborhood(
            Collection<String> seedIds,
            Collection<String> expansionSeedIds,
            GraphQueryEngine.Direction direction,
            int maxDepth,
            int maxNodes,
            int maxEdges) throws IOException {
        requireOpen();
        if (!hasCompactTopology()) {
            throw new IOException("Bounded archive neighborhoods require compact topology v3");
        }
        if (hasAdjacencyIndex()) {
            return materializeIndexedNeighborhood(
                    seedIds, expansionSeedIds, direction, maxDepth, maxNodes, maxEdges);
        }
        int depthLimit = Math.max(0, Math.min(12, maxDepth));
        int nodeLimit = Math.max(1, Math.min(100_000, maxNodes));
        int edgeLimit = Math.max(1, Math.min(500_000, maxEdges));
        GraphQueryEngine.Direction traversalDirection = direction == null
                ? GraphQueryEngine.Direction.BOTH : direction;

        LinkedHashSet<String> visited = new LinkedHashSet<>();
        ArrayDeque<NodeDepth> frontier = new ArrayDeque<>();
        boolean[] truncated = {false};
        if (seedIds != null) {
            for (String seed : seedIds) addVisited(seed, 0, false, visited, frontier, nodeLimit, truncated);
        }
        if (expansionSeedIds != null) {
            for (String seed : expansionSeedIds) addVisited(
                    seed, 0, true, visited, frontier, nodeLimit, truncated);
        }

        LinkedHashMap<String, Link> selectedLinks = new LinkedHashMap<>();
        while (!frontier.isEmpty()) {
            int currentDepth = frontier.peekFirst().depth();
            if (currentDepth >= depthLimit) break;
            LinkedHashSet<String> current = new LinkedHashSet<>();
            while (!frontier.isEmpty() && frontier.peekFirst().depth() == currentDepth) {
                current.add(frontier.removeFirst().nodeId());
            }
            LinkedHashSet<String> next = new LinkedHashSet<>();
            try (LinkCursor links = openLinks()) {
                Link link;
                while ((link = links.next()) != null) {
                    String neighbor = matchingNeighbor(link, current, traversalDirection);
                    if (neighbor == null) continue;
                    if (!visited.contains(neighbor)) {
                        if (visited.size() >= nodeLimit) {
                            truncated[0] = true;
                            continue;
                        }
                        visited.add(neighbor);
                        next.add(neighbor);
                    }
                    if (!selectedLinks.containsKey(link.id())) {
                        if (selectedLinks.size() >= edgeLimit) {
                            truncated[0] = true;
                            continue;
                        }
                        selectedLinks.put(link.id(), link);
                    }
                }
            }
            for (String node : next) frontier.addLast(new NodeDepth(node, currentDepth + 1));
        }

        UnifiedGraph graph = new UnifiedGraph();
        Object rawMeta = manifest.get("meta");
        if (rawMeta instanceof Map<?, ?> meta) {
            for (Map.Entry<?, ?> entry : meta.entrySet()) {
                if (entry.getKey() != null) graph.meta(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        readSelectedEntities(visited, graph);
        for (Link link : selectedLinks.values()) {
            if (graph.entity(link.sourceId()).isEmpty() || graph.entity(link.targetId()).isEmpty()) continue;
            graph.addRelation(new SimpleGraphRelation(
                    link.id(), link.sourceId(), link.targetId(), link.type(), link.weight(),
                    link.confidence(), link.directed(), link.tags(), null, link.timestamp(),
                    link.attributes()));
            if (link.opinion() != null) graph.putRelationOpinion(link.id(), link.opinion());
        }
        graph.meta("boundedNeighborhood", true)
                .meta("truncated", truncated[0])
                .meta("maxDepth", depthLimit)
                .meta("maxNodes", nodeLimit)
                .meta("maxEdges", edgeLimit)
                .meta("materializedNodes", graph.entityCount())
                .meta("materializedEdges", graph.relationCount());
        return graph;
    }

    private UnifiedGraph materializeIndexedNeighborhood(
            Collection<String> seedIds,
            Collection<String> expansionSeedIds,
            GraphQueryEngine.Direction direction,
            int maxDepth,
            int maxNodes,
            int maxEdges) throws IOException {
        int depthLimit = Math.max(0, Math.min(12, maxDepth));
        int nodeLimit = Math.max(1, Math.min(100_000, maxNodes));
        int edgeLimit = Math.max(1, Math.min(500_000, maxEdges));
        GraphQueryEngine.Direction traversalDirection = direction == null
                ? GraphQueryEngine.Direction.BOTH : direction;
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        ArrayDeque<NodeDepth> frontier = new ArrayDeque<>();
        boolean[] truncated = {false};
        if (seedIds != null) {
            for (String seed : seedIds) addVisited(
                    seed, 0, false, visited, frontier, nodeLimit, truncated);
        }
        if (expansionSeedIds != null) {
            for (String seed : expansionSeedIds) addVisited(
                    seed, 0, true, visited, frontier, nodeLimit, truncated);
        }
        LinkedHashMap<Integer, CompactAdjacencyCodec.RawLink> selected = new LinkedHashMap<>();
        LinkedHashMap<String, Link> overlaySelected = new LinkedHashMap<>();
        try (CompactAdjacencyCodec.Index index = openAdjacencyIndex()) {
            while (!frontier.isEmpty()) {
                NodeDepth current = frontier.removeFirst();
                if (current.depth() >= depthLimit) continue;
                index.forEachAdjacent(current.nodeId(), traversalDirection, ordinal -> {
                    CompactAdjacencyCodec.RawLink link = index.link(ordinal);
                    if (journal.relationStates().containsKey(link.id())) return;
                    String source = index.nodeId(link.source());
                    String target = index.nodeId(link.target());
                    String neighbor = current.nodeId().equals(source) ? target
                            : current.nodeId().equals(target) ? source : null;
                    if (neighbor == null) return;
                    if (!visited.contains(neighbor)) {
                        if (visited.size() >= nodeLimit) {
                            truncated[0] = true;
                            return;
                        }
                        visited.add(neighbor);
                        frontier.addLast(new NodeDepth(neighbor, current.depth() + 1));
                    }
                    if (!selected.containsKey(ordinal)) {
                        if (selected.size() + overlaySelected.size() >= edgeLimit) {
                            truncated[0] = true;
                            return;
                        }
                        selected.put(ordinal, link);
                    }
                });
                for (Link link : journal.incident(current.nodeId(), traversalDirection)) {
                    String neighbor = current.nodeId().equals(link.sourceId()) ? link.targetId()
                            : current.nodeId().equals(link.targetId()) ? link.sourceId() : null;
                    if (neighbor == null) continue;
                    if (!visited.contains(neighbor)) {
                        if (visited.size() >= nodeLimit) {
                            truncated[0] = true;
                            continue;
                        }
                        visited.add(neighbor);
                        frontier.addLast(new NodeDepth(neighbor, current.depth() + 1));
                    }
                    if (!overlaySelected.containsKey(link.id())) {
                        if (selected.size() + overlaySelected.size() >= edgeLimit) {
                            truncated[0] = true;
                            continue;
                        }
                        overlaySelected.put(link.id(), link);
                    }
                }
            }
            Map<Integer, CompactTopologyCodec.PropertyData> properties =
                    readSelectedProperties(selected.keySet());
            UnifiedGraph graph = new UnifiedGraph();
            copyManifestMeta(graph);
            readSelectedEntities(visited, graph);
            for (Map.Entry<Integer, CompactAdjacencyCodec.RawLink> item : selected.entrySet()) {
                CompactAdjacencyCodec.RawLink link = item.getValue();
                String source = index.nodeId(link.source());
                String target = index.nodeId(link.target());
                if (graph.entity(source).isEmpty() || graph.entity(target).isEmpty()) continue;
                CompactTopologyCodec.PropertyData property = properties.getOrDefault(
                        item.getKey(), CompactTopologyCodec.PropertyData.EMPTY);
                if (link.hasProperties() && !properties.containsKey(item.getKey())) {
                    throw new IOException("Missing compact property row for link " + item.getKey());
                }
                graph.addRelation(new SimpleGraphRelation(
                        link.id(), source, target, index.type(link.type()), link.weight(),
                        link.confidence(), link.directed(), property.tags(), null,
                        property.timestamp(), property.attributes()));
                if (property.opinion() != null) graph.putRelationOpinion(link.id(), property.opinion());
            }
            for (Link link : overlaySelected.values()) {
                if (graph.entity(link.sourceId()).isEmpty() || graph.entity(link.targetId()).isEmpty()) continue;
                graph.addRelation(new SimpleGraphRelation(
                        link.id(), link.sourceId(), link.targetId(), link.type(), link.weight(),
                        link.confidence(), link.directed(), link.tags(), null, link.timestamp(),
                        link.attributes()));
                if (link.opinion() != null) graph.putRelationOpinion(link.id(), link.opinion());
            }
            graph.meta("boundedNeighborhood", true)
                    .meta("adjacencyIndex", "mmap-csr")
                    .meta("truncated", truncated[0])
                    .meta("maxDepth", depthLimit)
                    .meta("maxNodes", nodeLimit)
                    .meta("maxEdges", edgeLimit)
                    .meta("materializedNodes", graph.entityCount())
                    .meta("materializedEdges", graph.relationCount());
            return graph;
        }
    }

    CompactAdjacencyCodec.Index openAdjacencyIndex() throws IOException {
        ZipEntry entry = zip.getEntry(UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY);
        if (entry == null || entry.getMethod() != ZipEntry.STORED) {
            throw new IOException("Compact adjacency index is absent or not ZIP STORED");
        }
        Path staged = Files.createTempFile("kompile-kgraph-adjacency-", ".bin");
        boolean retained = false;
        try (InputStream input = new UnifiedGraphReader.BudgetInputStream(
                     zip.getInputStream(entry),
                     new UnifiedGraphReader.ExpansionBudget(limits.maxTotalBytes()), entry.getName());
             OutputStream output = Files.newOutputStream(
                     staged, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            input.transferTo(output);
            CompactAdjacencyCodec.Index index = CompactAdjacencyCodec.open(
                    staged, topologyNodeCount, topologyTypeCount, baseLinkCount);
            retained = true;
            return index;
        } finally {
            if (!retained) Files.deleteIfExists(staged);
        }
    }

    private Map<Integer, CompactTopologyCodec.PropertyData> readSelectedProperties(
            Set<Integer> selected) throws IOException {
        if (selected.isEmpty()) return Map.of();
        ZipEntry entry = zip.getEntry(UnifiedGraphFormat.ENTRY_RELATION_PROPERTIES);
        if (entry == null) return Map.of();
        Map<Integer, CompactTopologyCodec.PropertyData> result = new LinkedHashMap<>();
        try (InputStream input = new UnifiedGraphReader.BudgetInputStream(
                     zip.getInputStream(entry),
                     new UnifiedGraphReader.ExpansionBudget(limits.maxTotalBytes()), entry.getName());
             InputStreamReader reader = new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)) {
            char[] chunk = new char[8_192];
            StringBuilder row = new StringBuilder(512);
            long line = 1;
            int read;
            while ((read = reader.read(chunk)) != -1) {
                for (int i = 0; i < read; i++) {
                    if (chunk[i] == '\n') {
                        addSelectedProperty(row, line++, selected, result);
                        row.setLength(0);
                    } else {
                        if (row.length() >= limits.maxJsonlRowChars()) {
                            throw new IOException("Compact property row exceeds character limit");
                        }
                        row.append(chunk[i]);
                    }
                }
            }
            if (!row.isEmpty()) addSelectedProperty(row, line, selected, result);
        }
        return result;
    }

    private static void addSelectedProperty(
            StringBuilder row,
            long line,
            Set<Integer> selected,
            Map<Integer, CompactTopologyCodec.PropertyData> result) throws IOException {
        if (row.toString().isBlank()) return;
        Map<String, Object> value;
        try { value = MiniJson.parseObject(row.toString()); }
        catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid compact property JSON at line " + line, malformed);
        }
        Object rawIndex = value.get("link");
        if (!(rawIndex instanceof Number number)
                || number.doubleValue() != number.intValue() || number.intValue() < 0) {
            throw new IOException("Invalid compact property link at line " + line);
        }
        int index = number.intValue();
        if (!selected.contains(index)) return;
        Map<String, Object> propertyAttributes = attributes(value, line);
        CompactTopologyCodec.PropertyData property = new CompactTopologyCodec.PropertyData(
                tags(value.get("tags")), timestamp(value.get("timestamp")), propertyAttributes,
                opinion(value.get("opinion")));
        if (result.putIfAbsent(index, property) != null) {
            throw new IOException("Duplicate compact property row for link " + index);
        }
    }

    private void copyManifestMeta(UnifiedGraph graph) {
        Object rawMeta = manifest.get("meta");
        if (rawMeta instanceof Map<?, ?> meta) {
            for (Map.Entry<?, ?> entry : meta.entrySet()) {
                if (entry.getKey() != null) graph.meta(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
    }

    private static void addVisited(
            String id,
            int depth,
            boolean expand,
            Set<String> visited,
            ArrayDeque<NodeDepth> frontier,
            int limit,
            boolean[] truncated) {
        if (id == null || id.isBlank()) return;
        if (!visited.contains(id)) {
            if (visited.size() >= limit) {
                truncated[0] = true;
                return;
            }
            visited.add(id);
        }
        if (expand) frontier.addLast(new NodeDepth(id, depth));
    }

    private static String matchingNeighbor(
            Link link, Set<String> frontier, GraphQueryEngine.Direction direction) {
        if (direction != GraphQueryEngine.Direction.INCOMING && frontier.contains(link.sourceId())) {
            return link.targetId();
        }
        if (direction != GraphQueryEngine.Direction.OUTGOING && frontier.contains(link.targetId())) {
            return link.sourceId();
        }
        if (!link.directed()) {
            if (frontier.contains(link.targetId())) return link.sourceId();
            if (frontier.contains(link.sourceId())) return link.targetId();
        }
        return null;
    }

    private void readSelectedEntities(Set<String> selected, UnifiedGraph graph) throws IOException {
        try (EntityRecordCursor cursor = openEntityRecords()) {
            EntityRecord record;
            while ((record = cursor.next()) != null) {
                if (!selected.contains(record.entity().id())) continue;
                graph.addEntity(record.entity());
                if (record.opinion() != null) {
                    graph.putEntityOpinion(record.entity().id(), record.opinion());
                }
            }
        }
    }

    private static EntityRecord parseEntity(String row, long line) throws IOException {
        if (row == null || row.isBlank()) return null;
        Map<String, Object> value;
        try {
            value = MiniJson.parseObject(row);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid entity JSON at line " + line, malformed);
        }
        String id = value.get("id") == null ? null : String.valueOf(value.get("id"));
        if (id == null || id.isBlank()) throw new IOException("Invalid entity id at line " + line);
        String type = value.get("type") instanceof String string ? string : "";
        Map<String, Object> attributes = attributes(value, line);
        mergeTypeMemberships(value, type, attributes, line);
        GraphEntity entity = new SimpleGraphEntity(
                id, type, value.get("label") == null ? null : String.valueOf(value.get("label")),
                number(value.get("weight"), 1.0), number(value.get("confidence"), 1.0),
                tags(value.get("tags")), null, timestamp(value.get("timestamp")), attributes);
        return new EntityRecord(entity, opinion(value.get("opinion")));
    }

    private static Map<String, Object> attributes(Map<String, Object> value, long line)
            throws IOException {
        if (!value.containsKey("attributes")) return new LinkedHashMap<>();
        if (!(value.get("attributes") instanceof Map<?, ?> raw)) {
            throw new IOException("Entity attributes must be an object at line " + line);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> item : raw.entrySet()) {
            if (item.getKey() == null || item.getValue() == null) {
                throw new IOException("Entity attributes contain null at line " + line);
            }
            result.put(String.valueOf(item.getKey()), item.getValue());
        }
        return result;
    }

    private static void mergeTypeMemberships(
            Map<String, Object> value, String type, Map<String, Object> attributes, long line)
            throws IOException {
        Object raw = value.get("typeMemberships");
        if (raw == null) return;
        if (!(raw instanceof List<?> memberships)) {
            throw new IOException("Entity typeMemberships must be an array at line " + line);
        }
        LinkedHashSet<String> additional = new LinkedHashSet<>();
        Object existing = attributes.get("additionalTypes");
        if (existing instanceof List<?> list) {
            for (Object item : list) if (item != null) additional.add(String.valueOf(item));
        }
        for (Object item : memberships) {
            if (!(item instanceof String membership) || membership.isBlank()) {
                throw new IOException("Entity typeMemberships contains an invalid value at line " + line);
            }
            if (!membership.equals(type)) additional.add(membership);
        }
        if (!additional.isEmpty()) attributes.put("additionalTypes", List.copyOf(additional));
    }

    private static Set<String> tags(Object raw) {
        if (!(raw instanceof List<?> list)) return Set.of();
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (Object item : list) {
            if (item == null) continue;
            String tag = String.valueOf(item).trim();
            if (!tag.isEmpty()) tags.add(tag);
        }
        return tags;
    }

    private static Instant timestamp(Object raw) {
        if (!(raw instanceof String value) || value.isBlank()) return null;
        try { return Instant.parse(value); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private static double number(Object raw, double fallback) {
        if (raw instanceof Number number) return number.doubleValue();
        if (raw instanceof String value) {
            return switch (value) {
                case "NaN" -> Double.NaN;
                case "Infinity" -> Double.POSITIVE_INFINITY;
                case "-Infinity" -> Double.NEGATIVE_INFINITY;
                default -> {
                    try { yield Double.parseDouble(value); }
                    catch (NumberFormatException ignored) { yield fallback; }
                }
            };
        }
        return fallback;
    }

    private static Opinion opinion(Object raw) {
        if (!(raw instanceof Map<?, ?> value)) return null;
        double belief = number(value.get("b"), Double.NaN);
        double disbelief = number(value.get("d"), Double.NaN);
        double uncertainty = number(value.get("u"), Double.NaN);
        if (Double.isNaN(belief) || Double.isNaN(disbelief) || Double.isNaN(uncertainty)) return null;
        return new Opinion(belief, disbelief, uncertainty, number(value.get("a"), 0.5));
    }

    private record NodeDepth(String nodeId, int depth) { }

    public record ArtifactInfo(String name, long bytes) { }

    public record ArtifactContent(byte[] bytes, long totalBytes, boolean truncated) {
        public ArtifactContent {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public interface EntityCursor extends AutoCloseable {
        GraphEntity next() throws IOException;
        int position();
        @Override void close() throws IOException;
    }

    public record EntityRecord(GraphEntity entity, Opinion opinion) { }

    public interface EntityRecordCursor extends AutoCloseable {
        EntityRecord next() throws IOException;
        int position();
        @Override void close() throws IOException;
    }

    private static final class ArchiveEntityCursor implements EntityCursor {
        private final EntityRecordCursor records;

        private ArchiveEntityCursor(EntityRecordCursor records) { this.records = records; }
        @Override public GraphEntity next() throws IOException {
            EntityRecord record = records.next();
            return record == null ? null : record.entity();
        }
        @Override public int position() { return records.position(); }
        @Override public void close() throws IOException { records.close(); }
    }

    private static final class OverlayEntityRecordCursor implements EntityRecordCursor {
        private final EntityRecordCursor base;
        private final Map<String, UnifiedGraphMutationJournal.EntityState> mutations;
        private final Iterator<UnifiedGraphMutationJournal.EntityState> appended;
        private int position;

        private OverlayEntityRecordCursor(
                EntityRecordCursor base, UnifiedGraphMutationJournal.Snapshot journal) {
            this.base = base;
            this.mutations = journal.entityStates();
            this.appended = mutations.values().iterator();
        }

        @Override
        public EntityRecord next() throws IOException {
            EntityRecord record;
            while ((record = base.next()) != null) {
                if (!mutations.containsKey(record.entity().id())) {
                    position++;
                    return record;
                }
            }
            if (!appended.hasNext()) return null;
            position++;
            return new EntityRecord(appended.next().current(), null);
        }

        @Override public int position() { return position; }
        @Override public void close() throws IOException { base.close(); }
    }

    private static final class ArchiveEntityRecordCursor implements EntityRecordCursor {
        private final InputStreamReader reader;
        private final int expectedCount;
        private final int maxRowChars;
        private final char[] buffer = new char[8_192];
        private int bufferPosition;
        private int bufferLimit;
        private int position;
        private long line = 1;
        private boolean finished;

        private ArchiveEntityRecordCursor(InputStream input, int expectedCount, int maxRowChars) {
            this.reader = new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8);
            this.expectedCount = expectedCount;
            this.maxRowChars = maxRowChars;
        }

        @Override
        public EntityRecord next() throws IOException {
            if (finished) return null;
            StringBuilder row = new StringBuilder(512);
            while (true) {
                if (bufferPosition >= bufferLimit) {
                    bufferLimit = reader.read(buffer);
                    bufferPosition = 0;
                    if (bufferLimit < 0) {
                        finished = true;
                        EntityRecord tail = parseEntity(row.toString(), line);
                        if (tail != null) return counted(tail);
                        verifyCount();
                        return null;
                    }
                }
                char value = buffer[bufferPosition++];
                if (value == '\n') {
                    EntityRecord entity = parseEntity(row.toString(), line++);
                    if (entity != null) return counted(entity);
                    row.setLength(0);
                } else {
                    if (row.length() >= maxRowChars) {
                        throw new IOException("entities.jsonl row exceeds character limit at line " + line);
                    }
                    row.append(value);
                }
            }
        }

        private EntityRecord counted(EntityRecord entity) throws IOException {
            position++;
            if (position > expectedCount) throw new IOException("entities.jsonl contains too many rows");
            return entity;
        }

        private void verifyCount() throws IOException {
            if (position != expectedCount) throw new IOException("entities.jsonl row count mismatch");
        }

        @Override public int position() { return position; }
        @Override public void close() throws IOException { reader.close(); }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            IOException failure = null;
            try { zip.close(); } catch (IOException error) { failure = error; }
            try { stableReference.requireUnchanged(); } catch (IOException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
            try { stableReference.close(); } catch (IOException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }

    public record Link(
            int index,
            String id,
            String sourceId,
            String targetId,
            String type,
            double weight,
            double confidence,
            boolean directed,
            boolean hasProperties,
            Set<String> tags,
            Instant timestamp,
            Map<String, Object> attributes,
            Opinion opinion) {
        public Link {
            tags = tags == null ? Set.of() : Set.copyOf(tags);
            attributes = attributes == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }
    }

    public interface LinkCursor extends AutoCloseable {
        /** Return the next link, or {@code null} after the validated end of the table. */
        Link next() throws IOException;
        int position();
        @Override void close() throws IOException;
    }

    private static final class ArchiveLinkCursor implements LinkCursor {
        private final CompactTopologyCodec.RecordCursor delegate;

        private ArchiveLinkCursor(CompactTopologyCodec.RecordCursor delegate) { this.delegate = delegate; }

        @Override
        public Link next() throws IOException {
            CompactTopologyCodec.LinkRecord record = delegate.next();
            if (record == null) return null;
            CompactTopologyCodec.CoreLink link = record.core();
            CompactTopologyCodec.PropertyData property = record.property();
            return new Link(link.index(), link.id(), link.sourceId(), link.targetId(), link.type(),
                    link.weight(), link.confidence(), link.directed(), link.hasProperties(),
                    property.tags(), property.timestamp(), property.attributes(), property.opinion());
        }

        @Override public int position() { return delegate.position(); }
        @Override public void close() throws IOException { delegate.close(); }
    }

    private static final class OverlayLinkCursor implements LinkCursor {
        private final LinkCursor base;
        private final Map<String, UnifiedGraphMutationJournal.RelationState> mutations;
        private final Iterator<UnifiedGraphMutationJournal.RelationState> appended;
        private int position;

        private OverlayLinkCursor(LinkCursor base, UnifiedGraphMutationJournal.Snapshot journal) {
            this.base = base;
            this.mutations = journal.relationStates();
            this.appended = mutations.values().iterator();
        }

        @Override
        public Link next() throws IOException {
            Link link;
            while ((link = base.next()) != null) {
                if (!mutations.containsKey(link.id())) {
                    position++;
                    return link;
                }
            }
            while (appended.hasNext()) {
                Link current = appended.next().current();
                if (current != null) {
                    position++;
                    return current;
                }
            }
            return null;
        }

        @Override public int position() { return position; }
        @Override public void close() throws IOException { base.close(); }
    }

    private void requireOpen() throws IOException {
        if (closed) throw new IOException("Unified graph archive is closed");
    }

    private static int checkedLogicalCount(int base, int delta, String kind) {
        long value = (long) base + delta;
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalStateException("Invalid journal-adjusted " + kind + " count");
        }
        return (int) value;
    }

    private static Map<?, ?> object(Object value, String field) throws IOException {
        if (value instanceof Map<?, ?> map) return map;
        throw new IOException("Unified-graph manifest field " + field + " must be an object");
    }

    private static int integer(Object value, String field) throws IOException {
        if (!(value instanceof Number number)) {
            throw new IOException("Unified-graph manifest field " + field + " must be an integer");
        }
        long result = number.longValue();
        if (result < 0 || result > Integer.MAX_VALUE || number.doubleValue() != result) {
            throw new IOException("Unified-graph manifest field " + field + " is out of range");
        }
        return (int) result;
    }

}
