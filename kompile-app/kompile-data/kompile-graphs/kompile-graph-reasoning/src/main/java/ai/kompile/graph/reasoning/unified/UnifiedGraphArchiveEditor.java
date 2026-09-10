/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;

import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Bounded-heap copy-on-write editor for compact KGraph archives.
 *
 * <p>Entity rows and links are streamed repeatedly from the stable source. The editor retains only
 * O(V) entity ids/topology dictionaries plus ids actually deleted by the edit; it never builds the
 * source relation collection. Existing vectors are filtered row-at-a-time, while weights and model
 * artifacts are copied byte-for-byte. Added entities/relations replace rows carrying the same id.</p>
 */
public final class UnifiedGraphArchiveEditor {

    private UnifiedGraphArchiveEditor() { }

    public static Result rewrite(
            Path source,
            Path target,
            UnifiedGraph additions,
            Predicate<UnifiedGraphArchive.EntityRecord> keepEntity,
            Predicate<UnifiedGraphArchive.Link> keepLink,
            Map<String, Object> metaOverrides) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        additions = additions == null ? new UnifiedGraph() : additions;
        keepEntity = keepEntity == null ? ignored -> true : keepEntity;
        keepLink = keepLink == null ? ignored -> true : keepLink;
        metaOverrides = metaOverrides == null ? Map.of() : Map.copyOf(metaOverrides);

        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path staged = Files.createTempFile(parent, normalizedTarget.getFileName() + ".edit-", ".tmp");
        boolean published = false;
        try {
            Result result = rewriteStaged(normalizedSource, staged, additions,
                    keepEntity, keepLink, metaOverrides);
            try {
                Files.move(staged, normalizedTarget, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staged, normalizedTarget, StandardCopyOption.REPLACE_EXISTING);
            }
            if (normalizedSource.equals(normalizedTarget)) {
                UnifiedGraphMutationJournal.clear(normalizedTarget);
            }
            published = true;
            return new Result(normalizedTarget, result.entities(), result.relations(),
                    result.removedEntities(), result.removedRelations());
        } finally {
            if (!published) Files.deleteIfExists(staged);
        }
    }

    private static Result rewriteStaged(
            Path source,
            Path staged,
            UnifiedGraph additions,
            Predicate<UnifiedGraphArchive.EntityRecord> keepEntity,
            Predicate<UnifiedGraphArchive.Link> keepLink,
            Map<String, Object> metaOverrides) throws IOException {
        Set<String> replacementEntities = ids(additions.entities());
        Set<String> replacementRelations = relationIds(additions.relations());
        Set<String> finalEntities = new LinkedHashSet<>();
        Set<String> removedEntities = new LinkedHashSet<>();
        Set<String> entityTypes = new TreeSet<>();
        Set<String> entityAttributes = new TreeSet<>();
        Map<String, Object> sourceManifest;

        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(source)) {
            if (!archive.hasCompactTopology()) {
                throw new IOException("Archive-native edits require compact KGraph v3");
            }
            sourceManifest = new LinkedHashMap<>(archive.manifest());
            try (UnifiedGraphArchive.EntityRecordCursor cursor = archive.openEntityRecords()) {
                UnifiedGraphArchive.EntityRecord record;
                while ((record = cursor.next()) != null) {
                    GraphEntity entity = record.entity();
                    boolean keep = !replacementEntities.contains(entity.id()) && keepEntity.test(record);
                    if (keep) {
                        if (!finalEntities.add(entity.id())) throw new IOException("Duplicate entity " + entity.id());
                        entityTypes.add(entity.type());
                        entityAttributes.addAll(entity.attributes().keySet());
                    } else if (!replacementEntities.contains(entity.id())) {
                        removedEntities.add(entity.id());
                    }
                }
            }
        }
        for (GraphEntity entity : additions.entities()) {
            if (!finalEntities.add(entity.id())) throw new IOException("Duplicate added entity " + entity.id());
            entityTypes.add(entity.type());
            entityAttributes.addAll(entity.attributes().keySet());
        }

        LinkedHashMap<String, Integer> endpoints = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> relationTypes = new LinkedHashMap<>();
        Set<String> relationSchemaTypes = new TreeSet<>();
        Set<String> relationAttributes = new TreeSet<>();
        Set<String> removedRelations = new LinkedHashSet<>();
        Map<String, Opinion> replacementRelationOpinions = replacementRelationOpinions(
                source, replacementRelations, additions);
        int[] relationCount = {0};
        boolean[] hasProperties = {false};
        CompactTopologyCodec.OutputLinkPass pass = outputPass(
                source, additions, keepLink, replacementRelations, finalEntities,
                replacementRelationOpinions);
        pass.forEach(link -> {
            addDictionary(endpoints, link.sourceId(), false);
            addDictionary(endpoints, link.targetId(), false);
            addDictionary(relationTypes, link.type(), true);
            relationSchemaTypes.add(link.type());
            relationAttributes.addAll(link.attributes().keySet());
            hasProperties[0] |= link.hasProperties();
            relationCount[0]++;
        });
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(source);
             UnifiedGraphArchive.LinkCursor cursor = archive.openLinks()) {
            UnifiedGraphArchive.Link link;
            while ((link = cursor.next()) != null) {
                if (!retain(link, keepLink, replacementRelations, finalEntities)
                        && !replacementRelations.contains(link.id())) {
                    removedRelations.add(link.id());
                }
            }
            for (UnifiedGraphMutationJournal.RelationState state
                    : archive.journalSnapshot().relationStates().values()) {
                if (state.basePresent() && state.current() == null) {
                    removedRelations.add(state.id());
                }
            }
        }

        CompactTopologyCodec.Plan topology = new CompactTopologyCodec.Plan(
                endpoints, relationTypes, relationCount[0], hasProperties[0]);
        CompactAdjacencyCodec.LinkPass adjacencyPass = adjacencyPass(pass, topology);
        CompactAdjacencyCodec.Plan adjacency = CompactAdjacencyCodec.plan(
                endpoints, relationTypes, relationCount[0], adjacencyPass);
        Path links = Files.createTempFile("kompile-kgraph-edit-", ".links");
        Path properties = Files.createTempFile("kompile-kgraph-edit-", ".properties");
        Path adjacencyFile = Files.createTempFile("kompile-kgraph-edit-", ".adjacency");
        try {
            try (OutputStream output = Files.newOutputStream(links, StandardOpenOption.TRUNCATE_EXISTING)) {
                CompactTopologyCodec.writeLinks(topology, pass, output);
            }
            if (hasProperties[0]) {
                try (OutputStream output = Files.newOutputStream(
                        properties, StandardOpenOption.TRUNCATE_EXISTING)) {
                    CompactTopologyCodec.writeProperties(pass, output);
                }
            }
            try (OutputStream output = Files.newOutputStream(
                    adjacencyFile, StandardOpenOption.TRUNCATE_EXISTING)) {
                CompactAdjacencyCodec.write(adjacency, adjacencyPass, output);
            }

            List<VectorPlan> vectors = vectorPlans(source, finalEntities, removedRelations);
            Map<String, Object> manifest = manifest(sourceManifest, metaOverrides,
                    finalEntities.size(), relationCount[0], topology, adjacency, hasProperties[0], vectors);
            Map<String, Object> schema = schema(finalEntities.size(), relationCount[0],
                    entityTypes, relationSchemaTypes, entityAttributes, relationAttributes, sourceManifest);
            writeArchive(source, staged, additions, keepEntity, replacementEntities,
                    finalEntities, manifest, schema, links, properties, adjacencyFile,
                    hasProperties[0], vectors, removedRelations);
        } finally {
            Files.deleteIfExists(links);
            Files.deleteIfExists(properties);
            Files.deleteIfExists(adjacencyFile);
        }

        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(staged)) {
            archive.validateAllEntries();
            archive.validateAdjacencyIndex();
        }
        return new Result(staged, finalEntities.size(), relationCount[0],
                removedEntities.size(), removedRelations.size());
    }

    private static CompactTopologyCodec.OutputLinkPass outputPass(
            Path source,
            UnifiedGraph additions,
            Predicate<UnifiedGraphArchive.Link> keepLink,
            Set<String> replacementRelations,
            Set<String> finalEntities,
            Map<String, Opinion> replacementRelationOpinions) {
        return consumer -> {
            try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(source);
                 UnifiedGraphArchive.LinkCursor cursor = archive.openLinks()) {
                UnifiedGraphArchive.Link link;
                while ((link = cursor.next()) != null) {
                    if (retain(link, keepLink, replacementRelations, finalEntities)) {
                        consumer.accept(output(link));
                    }
                }
            }
            for (GraphRelation relation : additions.relations()) {
                if (!finalEntities.contains(relation.sourceId()) || !finalEntities.contains(relation.targetId())) {
                    throw new IOException("Added relation references a missing entity: " + relation.id());
                }
                consumer.accept(new CompactTopologyCodec.OutputLink(
                        relation.id(), relation.sourceId(), relation.targetId(), relation.type(),
                        relation.weight(), relation.confidence(), relation.directed(), relation.tags(),
                        relation.timestamp(), relation.attributes(),
                        additions.relationOpinion(relation.id()) != null
                                ? additions.relationOpinion(relation.id())
                                : replacementRelationOpinions.get(relation.id())));
            }
        };
    }

    private static Map<String, Opinion> replacementRelationOpinions(
            Path source, Set<String> replacements, UnifiedGraph additions) throws IOException {
        if (replacements.isEmpty()) return Map.of();
        Map<String, Opinion> result = new LinkedHashMap<>();
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(source);
             UnifiedGraphArchive.LinkCursor cursor = archive.openLinks()) {
            UnifiedGraphArchive.Link link;
            while ((link = cursor.next()) != null) {
                if (replacements.contains(link.id()) && link.opinion() != null
                        && additions.relationOpinion(link.id()) == null) {
                    result.put(link.id(), link.opinion());
                }
            }
        }
        return result;
    }

    private static boolean retain(
            UnifiedGraphArchive.Link link,
            Predicate<UnifiedGraphArchive.Link> keepLink,
            Set<String> replacementRelations,
            Set<String> finalEntities) {
        return !replacementRelations.contains(link.id())
                && finalEntities.contains(link.sourceId())
                && finalEntities.contains(link.targetId())
                && keepLink.test(link);
    }

    private static CompactTopologyCodec.OutputLink output(UnifiedGraphArchive.Link link) {
        return new CompactTopologyCodec.OutputLink(link.id(), link.sourceId(), link.targetId(),
                link.type(), link.weight(), link.confidence(), link.directed(), link.tags(),
                link.timestamp(), link.attributes(), link.opinion());
    }

    private static CompactAdjacencyCodec.LinkPass adjacencyPass(
            CompactTopologyCodec.OutputLinkPass pass, CompactTopologyCodec.Plan topology) {
        return consumer -> {
            int[] index = {0};
            pass.forEach(link -> {
                Integer source = topology.nodeOrdinals().get(link.sourceId());
                Integer target = topology.nodeOrdinals().get(link.targetId());
                Integer type = topology.typeOrdinals().get(link.type());
                if (source == null || target == null || type == null) {
                    throw new IOException("Adjacency plan does not cover " + link.id());
                }
                consumer.accept(new CompactAdjacencyCodec.RawLink(index[0], link.id(), source, target, type,
                        link.weight(), link.confidence(), link.directed(), link.hasProperties()));
                index[0]++;
            });
        };
    }

    private static List<VectorPlan> vectorPlans(
            Path source, Set<String> entityIds, Set<String> removedRelationIds) throws IOException {
        List<VectorPlan> result = new ArrayList<>();
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(source)) {
            for (Map<String, Object> descriptor : archive.vectorLayerDescriptors()) {
                String name = String.valueOf(descriptor.get("name"));
                VectorLayer.Target target;
                Dtype dtype;
                try {
                    target = VectorLayer.Target.valueOf(String.valueOf(descriptor.get("target")));
                    dtype = Dtype.valueOf(String.valueOf(descriptor.get("dtype")));
                } catch (IllegalArgumentException invalid) {
                    throw new IOException("Invalid vector descriptor " + name, invalid);
                }
                int count = 0;
                int dimension;
                double scale;
                try (VectorBlobCodec.RowCursor cursor = archive.openVectorRows(name)) {
                    dimension = cursor.dimension();
                    scale = cursor.scale();
                    VectorBlobCodec.VectorRow row;
                    while ((row = cursor.next()) != null) {
                        if (keepVector(target, row.id(), entityIds, removedRelationIds)) count++;
                    }
                }
                result.add(new VectorPlan(name, target, dtype, dimension, count, scale));
            }
        }
        return List.copyOf(result);
    }

    private static boolean keepVector(
            VectorLayer.Target target, String id, Set<String> entities, Set<String> removedRelations) {
        return switch (target) {
            case GLOBAL -> true;
            case ENTITY -> entities.contains(id);
            case RELATION -> !removedRelations.contains(id);
        };
    }

    private static Map<String, Object> manifest(
            Map<String, Object> source,
            Map<String, Object> metaOverrides,
            int entities,
            int relations,
            CompactTopologyCodec.Plan topology,
            CompactAdjacencyCodec.Plan adjacency,
            boolean hasProperties,
            List<VectorPlan> vectors) {
        Map<String, Object> manifest = new LinkedHashMap<>(source);
        manifest.put("formatVersion", UnifiedGraphFormat.CURRENT_VERSION);
        Map<String, Object> meta = stringMap(source.get("meta"));
        meta.putAll(metaOverrides);
        manifest.put("meta", meta);
        Map<String, Object> counts = stringMap(source.get("counts"));
        counts.put("entities", entities);
        counts.put("relations", relations);
        counts.put("vectorLayers", vectors.size());
        manifest.put("counts", counts);
        List<Object> sections = new ArrayList<>();
        sections.add(UnifiedGraphFormat.ENTRY_SCHEMA_INDEX);
        sections.add(UnifiedGraphFormat.ENTRY_ENTITIES);
        sections.add(UnifiedGraphFormat.ENTRY_COMPACT_LINKS);
        sections.add(UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY);
        if (hasProperties) sections.add(UnifiedGraphFormat.ENTRY_RELATION_PROPERTIES);
        if (strings(source.get("sections")).contains(UnifiedGraphFormat.ENTRY_WEIGHTS)) {
            sections.add(UnifiedGraphFormat.ENTRY_WEIGHTS);
        }
        manifest.put("sections", sections);
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("encoding", UnifiedGraphFormat.COMPACT_TOPOLOGY_ENCODING);
        descriptor.put("encodingVersion", UnifiedGraphFormat.COMPACT_TOPOLOGY_VERSION);
        descriptor.put("entry", UnifiedGraphFormat.ENTRY_COMPACT_LINKS);
        descriptor.put("nodeCount", topology.nodeOrdinals().size());
        descriptor.put("relationTypeCount", topology.typeOrdinals().size());
        descriptor.put("linkCount", topology.linkCount());
        descriptor.put("adjacencyEntry", UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY);
        descriptor.put("adjacencyEncodingVersion", CompactAdjacencyCodec.VERSION);
        descriptor.put("adjacencyEntries", adjacency.adjacencyEntries());
        if (hasProperties) descriptor.put("propertiesEntry", UnifiedGraphFormat.ENTRY_RELATION_PROPERTIES);
        manifest.put("topology", descriptor);
        List<Object> vectorDescriptors = new ArrayList<>();
        for (VectorPlan vector : vectors) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", vector.name());
            row.put("target", vector.target().name());
            row.put("dtype", vector.dtype().name());
            row.put("dim", vector.dimension());
            row.put("count", vector.count());
            row.put("entry", UnifiedGraphFormat.vectorEntry(vector.name()));
            vectorDescriptors.add(row);
        }
        manifest.put("vectorLayers", vectorDescriptors);
        return manifest;
    }

    private static Map<String, Object> schema(
            int entities,
            int relations,
            Set<String> entityTypes,
            Set<String> relationTypes,
            Set<String> entityAttributes,
            Set<String> relationAttributes,
            Map<String, Object> sourceManifest) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("format", "kompile-unified-schema");
        schema.put("version", 1);
        schema.put("entityCount", entities);
        schema.put("relationCount", relations);
        schema.put("entityTypes", new ArrayList<>(entityTypes));
        schema.put("relationTypes", new ArrayList<>(relationTypes));
        schema.put("entityAttributeKeys", new ArrayList<>(entityAttributes));
        schema.put("relationAttributeKeys", new ArrayList<>(relationAttributes));
        schema.put("declaredSchemaArtifacts", strings(sourceManifest.get("artifacts")).stream()
                .filter(name -> name.startsWith("schema/")).sorted().toList());
        return schema;
    }

    private static void writeArchive(
            Path source,
            Path target,
            UnifiedGraph additions,
            Predicate<UnifiedGraphArchive.EntityRecord> keepEntity,
            Set<String> replacements,
            Set<String> finalEntities,
            Map<String, Object> manifest,
            Map<String, Object> schema,
            Path links,
            Path properties,
            Path adjacency,
            boolean hasProperties,
            List<VectorPlan> vectors,
            Set<String> removedRelations) throws IOException {
        try (ZipFile sourceZip = new ZipFile(source.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(
                     target, StandardOpenOption.TRUNCATE_EXISTING))) {
            output.setLevel(Deflater.BEST_SPEED);
            writeEntry(output, UnifiedGraphFormat.ENTRY_MANIFEST,
                    MiniJson.write(manifest).getBytes(StandardCharsets.UTF_8));
            writeEntry(output, UnifiedGraphFormat.ENTRY_SCHEMA_INDEX,
                    MiniJson.write(schema).getBytes(StandardCharsets.UTF_8));
            writeEntities(output, source, additions, keepEntity, replacements);
            writeStored(output, UnifiedGraphFormat.ENTRY_COMPACT_LINKS, links);
            writeStored(output, UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY, adjacency);
            if (hasProperties) writeFile(output, UnifiedGraphFormat.ENTRY_RELATION_PROPERTIES, properties);
            for (VectorPlan vector : vectors) writeVector(output, source, vector, finalEntities, removedRelations);

            ZipEntry weights = sourceZip.getEntry(UnifiedGraphFormat.ENTRY_WEIGHTS);
            if (weights != null) copyEntry(sourceZip, output, weights);
            for (UnifiedGraphArchive.ArtifactInfo artifact : artifacts(source)) {
                ZipEntry entry = sourceZip.getEntry(UnifiedGraphFormat.modelEntry(artifact.name()));
                if (entry != null) copyEntry(sourceZip, output, entry);
            }
        }
    }

    private static void writeEntities(
            ZipOutputStream output,
            Path source,
            UnifiedGraph additions,
            Predicate<UnifiedGraphArchive.EntityRecord> keepEntity,
            Set<String> replacements) throws IOException {
        output.putNextEntry(new ZipEntry(UnifiedGraphFormat.ENTRY_ENTITIES));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        Map<String, Opinion> replacementOpinions = new LinkedHashMap<>();
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(source);
             UnifiedGraphArchive.EntityRecordCursor cursor = archive.openEntityRecords()) {
            UnifiedGraphArchive.EntityRecord record;
            while ((record = cursor.next()) != null) {
                if (!replacements.contains(record.entity().id()) && keepEntity.test(record)) {
                    writer.write(MiniJson.write(entityRow(record.entity(), record.opinion())));
                    writer.write('\n');
                } else if (replacements.contains(record.entity().id()) && record.opinion() != null
                        && additions.entityOpinion(record.entity().id()) == null) {
                    replacementOpinions.put(record.entity().id(), record.opinion());
                }
            }
        }
        for (GraphEntity entity : additions.entities()) {
            Opinion opinion = additions.entityOpinion(entity.id()) != null
                    ? additions.entityOpinion(entity.id()) : replacementOpinions.get(entity.id());
            writer.write(MiniJson.write(entityRow(entity, opinion)));
            writer.write('\n');
        }
        writer.flush();
        output.closeEntry();
    }

    private static Map<String, Object> entityRow(GraphEntity entity, Opinion opinion) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", entity.id());
        if (!entity.type().isEmpty()) row.put("type", entity.type());
        if (!entity.typeMemberships().isEmpty()) row.put("typeMemberships", new ArrayList<>(entity.typeMemberships()));
        if (!entity.label().isEmpty()) row.put("label", entity.label());
        if (entity.weight() != 1.0) row.put("weight", entity.weight());
        if (entity.confidence() != 1.0) row.put("confidence", entity.confidence());
        if (!entity.tags().isEmpty()) row.put("tags", new ArrayList<>(entity.tags()));
        if (entity.timestamp() != null) row.put("timestamp", entity.timestamp().toString());
        if (!entity.attributes().isEmpty()) row.put("attributes", entity.attributes());
        if (opinion != null) row.put("opinion", opinionMap(opinion));
        return row;
    }

    private static void writeVector(
            ZipOutputStream output,
            Path source,
            VectorPlan vector,
            Set<String> entityIds,
            Set<String> removedRelations) throws IOException {
        output.putNextEntry(new ZipEntry(UnifiedGraphFormat.vectorEntry(vector.name())));
        DataOutputStream data = new DataOutputStream(output);
        VectorBlobCodec.writeRows(data, vector.name(), vector.target(), vector.dtype(),
                vector.count(), vector.dimension(), vector.scale(), consumer -> {
                    try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(source);
                         VectorBlobCodec.RowCursor cursor = archive.openVectorRows(vector.name())) {
                        VectorBlobCodec.VectorRow row;
                        while ((row = cursor.next()) != null) {
                            if (keepVector(vector.target(), row.id(), entityIds, removedRelations)) {
                                consumer.accept(row.id(), row.values());
                            }
                        }
                    }
                });
        data.flush();
        output.closeEntry();
    }

    private static List<UnifiedGraphArchive.ArtifactInfo> artifacts(Path source) throws IOException {
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(source)) {
            return archive.artifacts();
        }
    }

    private static void writeEntry(ZipOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static void writeFile(ZipOutputStream output, String name, Path file) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        Files.copy(file, output);
        output.closeEntry();
    }

    private static void writeStored(ZipOutputStream output, String name, Path file) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8_192];
            int count;
            while ((count = input.read(buffer)) != -1) crc.update(buffer, 0, count);
        }
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(Files.size(file));
        entry.setCompressedSize(Files.size(file));
        entry.setCrc(crc.getValue());
        output.putNextEntry(entry);
        Files.copy(file, output);
        output.closeEntry();
    }

    private static void copyEntry(ZipFile source, ZipOutputStream target, ZipEntry entry) throws IOException {
        ZipEntry copy = new ZipEntry(entry.getName());
        target.putNextEntry(copy);
        try (InputStream input = source.getInputStream(entry)) { input.transferTo(target); }
        target.closeEntry();
    }

    private static Set<String> ids(Iterable<GraphEntity> values) {
        Set<String> result = new LinkedHashSet<>();
        for (GraphEntity value : values) result.add(value.id());
        return result;
    }

    private static Set<String> relationIds(Iterable<GraphRelation> values) {
        Set<String> result = new LinkedHashSet<>();
        for (GraphRelation value : values) result.add(value.id());
        return result;
    }

    private static void addDictionary(
            LinkedHashMap<String, Integer> values, String value, boolean allowEmpty) throws IOException {
        if (value == null || !allowEmpty && value.isBlank()) throw new IOException("Invalid topology dictionary value");
        values.computeIfAbsent(value, ignored -> values.size());
    }

    private static Map<String, Object> stringMap(Object raw) {
        if (!(raw instanceof Map<?, ?> values)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> value : values.entrySet()) {
            if (value.getKey() != null) result.put(String.valueOf(value.getKey()), value.getValue());
        }
        return result;
    }

    private static Set<String> strings(Object raw) {
        if (!(raw instanceof List<?> values)) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (Object value : values) if (value != null) result.add(String.valueOf(value));
        return Collections.unmodifiableSet(result);
    }

    private static Map<String, Object> opinionMap(Opinion opinion) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("b", opinion.belief());
        result.put("d", opinion.disbelief());
        result.put("u", opinion.uncertainty());
        result.put("a", opinion.baseRate());
        return result;
    }

    public record Result(
            Path path, int entities, int relations, int removedEntities, int removedRelations) { }

    private record VectorPlan(
            String name, VectorLayer.Target target, Dtype dtype,
            int dimension, int count, double scale) { }
}
