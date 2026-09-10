/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.confidence.Opinion;

import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Direct, bounded-heap v1/v2 JSONL to v3 compact-topology transcoder. */
final class LegacyGraphTranscoder {

    private static final int MAX_ROW_CHARS = Math.max(1,
            Integer.getInteger("kompile.graph.maxJsonlRowChars", 16 * 1024 * 1024));
    private static final int MAX_STRING_BYTES = Math.max(1,
            Integer.getInteger("kompile.graph.maxTopologyStringBytes", 16 * 1024 * 1024));
    private static final long MAX_EXPANDED_BYTES = Math.max(1L,
            Long.getLong("kompile.graph.maxTotalBytes", 2L * 1024 * 1024 * 1024));

    private LegacyGraphTranscoder() { }

    static void transcode(
            Path source, Path target, String sourceHash, int sourceVersion) throws IOException {
        if (sourceVersion < UnifiedGraphFormat.MIN_READABLE_VERSION || sourceVersion >= 3) {
            throw new IOException("Legacy transcoder requires unified-graph format v1 or v2");
        }
        Path links = Files.createTempFile("kompile-kgraph-transcode-", ".links");
        Path properties = Files.createTempFile("kompile-kgraph-transcode-", ".properties");
        Path adjacency = Files.createTempFile("kompile-kgraph-transcode-", ".adjacency");
        try (ZipFile zip = new ZipFile(source.toFile())) {
            Map<String, Object> manifest = readManifest(zip);
            Counts counts = counts(manifest);
            Analysis analysis = analyze(zip, counts);
            writeTopology(zip, links, properties, analysis, counts);
            CompactAdjacencyCodec.LinkPass adjacencyPass = linkPass(zip, analysis, counts);
            CompactAdjacencyCodec.Plan adjacencyPlan = CompactAdjacencyCodec.plan(
                    analysis.endpoints(), analysis.relationTypes(), counts.relations(), adjacencyPass);
            try (OutputStream out = Files.newOutputStream(
                    adjacency, StandardOpenOption.TRUNCATE_EXISTING)) {
                CompactAdjacencyCodec.write(adjacencyPlan, adjacencyPass, out);
            }
            Map<String, Object> targetManifest = targetManifest(
                    manifest, analysis, counts, adjacencyPlan, sourceHash, sourceVersion);
            writeArchive(zip, target, targetManifest, analysis, links, properties, adjacency);
        } finally {
            Files.deleteIfExists(links);
            Files.deleteIfExists(properties);
            Files.deleteIfExists(adjacency);
        }
        validateTarget(target);
    }

    static void copyCurrent(Path source, Path target) throws IOException {
        Map<String, Object> manifest;
        LinkedHashMap<String, Integer> endpoints = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> types = new LinkedHashMap<>();
        int links;
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(source)) {
            if (archive.hasJournalMutations()) {
                UnifiedGraphArchiveEditor.rewrite(
                        source, target, new UnifiedGraph(), null, null,
                        Map.of("mutationJournal.compactedAt", Instant.now().toString()));
                validateTarget(target);
                return;
            }
            if (archive.hasAdjacencyIndex()) {
                Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                validateTarget(target);
                return;
            }
            manifest = new LinkedHashMap<>(archive.manifest());
            links = archive.linkCount();
            try (UnifiedGraphArchive.LinkCursor cursor = archive.openLinks()) {
                UnifiedGraphArchive.Link link;
                while ((link = cursor.next()) != null) {
                    addDictionary(endpoints, link.sourceId(), false, "relation source id");
                    addDictionary(endpoints, link.targetId(), false, "relation target id");
                    addDictionary(types, link.type(), true, "relation type");
                }
            }
        }
        CompactAdjacencyCodec.LinkPass pass = currentLinkPass(source, endpoints, types, links);
        CompactAdjacencyCodec.Plan adjacency = CompactAdjacencyCodec.plan(
                endpoints, types, links, pass);
        Path staged = Files.createTempFile("kompile-kgraph-current-adjacency-", ".bin");
        try {
            try (OutputStream output = Files.newOutputStream(staged)) {
                CompactAdjacencyCodec.write(adjacency, pass, output);
            }
            addAdjacencyDescriptor(manifest, adjacency);
            rewriteCurrentWithAdjacency(source, target, manifest, staged);
        } finally {
            Files.deleteIfExists(staged);
        }
        validateTarget(target);
    }

    private static CompactAdjacencyCodec.LinkPass currentLinkPass(
            Path source,
            Map<String, Integer> endpoints,
            Map<String, Integer> types,
            int expectedLinks) {
        return consumer -> {
            int count = 0;
            try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(source);
                 UnifiedGraphArchive.LinkCursor cursor = archive.openLinks()) {
                UnifiedGraphArchive.Link link;
                while ((link = cursor.next()) != null) {
                    Integer sourceOrdinal = endpoints.get(link.sourceId());
                    Integer targetOrdinal = endpoints.get(link.targetId());
                    Integer typeOrdinal = types.get(link.type());
                    if (sourceOrdinal == null || targetOrdinal == null || typeOrdinal == null) {
                        throw new IOException("Current compact dictionaries changed during upgrade");
                    }
                    consumer.accept(new CompactAdjacencyCodec.RawLink(
                            count, link.id(), sourceOrdinal, targetOrdinal, typeOrdinal,
                            link.weight(), link.confidence(), link.directed(), link.hasProperties()));
                    count++;
                }
            }
            if (count != expectedLinks) throw new IOException("Current compact link count changed");
        };
    }

    @SuppressWarnings("unchecked")
    private static void addAdjacencyDescriptor(
            Map<String, Object> manifest, CompactAdjacencyCodec.Plan adjacency) throws IOException {
        Object raw = manifest.get("topology");
        if (!(raw instanceof Map<?, ?> sourceTopology)) {
            throw new IOException("Current compact graph is missing its topology descriptor");
        }
        Map<String, Object> topology = stringMap(sourceTopology);
        topology.put("adjacencyEntry", UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY);
        topology.put("adjacencyEncodingVersion", CompactAdjacencyCodec.VERSION);
        topology.put("adjacencyEntries", adjacency.adjacencyEntries());
        manifest.put("topology", topology);
        List<Object> sections = new ArrayList<>(strings(manifest.get("sections")));
        if (!sections.contains(UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY)) {
            int linksIndex = sections.indexOf(UnifiedGraphFormat.ENTRY_COMPACT_LINKS);
            sections.add(linksIndex < 0 ? sections.size() : linksIndex + 1,
                    UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY);
        }
        manifest.put("sections", sections);
    }

    private static void rewriteCurrentWithAdjacency(
            Path source, Path target, Map<String, Object> manifest, Path adjacency) throws IOException {
        try (ZipFile input = new ZipFile(source.toFile());
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(
                     target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            output.setLevel(Deflater.BEST_SPEED);
            writeEntry(output, UnifiedGraphFormat.ENTRY_MANIFEST,
                    MiniJson.write(manifest).getBytes(StandardCharsets.UTF_8));
            var entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()
                        || UnifiedGraphFormat.ENTRY_MANIFEST.equals(entry.getName())
                        || UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY.equals(entry.getName())) continue;
                copyEntry(input, output, entry);
            }
            writeStoredEntry(output, UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY, adjacency);
        }
    }

    private static Analysis analyze(ZipFile zip, Counts counts) throws IOException {
        LinkedHashMap<String, Integer> endpoints = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> relationTypes = new LinkedHashMap<>();
        Set<String> entityIds = new LinkedHashSet<>();
        Set<String> entityTypes = new LinkedHashSet<>();
        Set<String> relationSchemaTypes = new LinkedHashSet<>();
        Set<String> entityAttributeKeys = new LinkedHashSet<>();
        Set<String> relationAttributeKeys = new LinkedHashSet<>();
        long[] budget = {MAX_EXPANDED_BYTES};
        int[] entities = {0};
        readRows(zip, UnifiedGraphFormat.ENTRY_ENTITIES, budget, (row, line) -> {
            if (entities[0] >= counts.entities()) {
                throw new IOException("entities.jsonl contains more rows than declared");
            }
            Map<String, Object> value = parseRow(row, UnifiedGraphFormat.ENTRY_ENTITIES, line);
            String id = stringValue(value.get("id"));
            String type = optionalString(value.get("type"), "type", line);
            if (id == null || id.isBlank() || !entityIds.add(id)) {
                throw new IOException("Invalid or duplicate entity id in entities.jsonl: " + id);
            }
            String normalizedType = type == null ? "" : type;
            entityTypes.add(normalizedType);
            collectAttributes(value, entityAttributeKeys, UnifiedGraphFormat.ENTRY_ENTITIES, line);
            if (hasAdditionalTypeMembership(value, normalizedType, line)) {
                entityAttributeKeys.add("additionalTypes");
            }
            entities[0]++;
        });
        if (entities[0] != counts.entities()) {
            throw new IOException("Unified-graph entity count mismatch during migration");
        }

        int[] relations = {0};
        boolean[] hasProperties = {false};
        readRows(zip, UnifiedGraphFormat.ENTRY_RELATIONS, budget, (row, line) -> {
            if (relations[0] >= counts.relations()) {
                throw new IOException("relations.jsonl contains more rows than declared");
            }
            Map<String, Object> value = parseRow(row, UnifiedGraphFormat.ENTRY_RELATIONS, line);
            LegacyLink link = link(value, line, relations[0]);
            addDictionary(endpoints, link.sourceId(), false, "relation source id");
            addDictionary(endpoints, link.targetId(), false, "relation target id");
            addDictionary(relationTypes, link.type(), true, "relation type");
            relationSchemaTypes.add(link.type());
            Map<String, Object> property = property(value, relations[0], line);
            hasProperties[0] |= property != null;
            collectAttributes(value, relationAttributeKeys, UnifiedGraphFormat.ENTRY_RELATIONS, line);
            relations[0]++;
        });
        if (relations[0] != counts.relations()) {
            throw new IOException("Unified-graph relation count mismatch during migration");
        }
        return new Analysis(endpoints, relationTypes, hasProperties[0], entityTypes,
                relationSchemaTypes, entityAttributeKeys, relationAttributeKeys);
    }

    private static void writeTopology(
            ZipFile zip, Path linksPath, Path propertiesPath, Analysis analysis, Counts counts)
            throws IOException {
        long[] budget = {MAX_EXPANDED_BYTES};
        try (DataOutputStream links = new DataOutputStream(Files.newOutputStream(
                     linksPath, StandardOpenOption.TRUNCATE_EXISTING));
             BufferedWriter properties = new BufferedWriter(new OutputStreamWriter(
                     Files.newOutputStream(propertiesPath, StandardOpenOption.TRUNCATE_EXISTING),
                     StandardCharsets.UTF_8))) {
            links.writeInt(CompactTopologyCodec.MAGIC);
            links.writeInt(CompactTopologyCodec.CODEC_VERSION);
            links.writeInt(0);
            links.writeInt(analysis.endpoints().size());
            links.writeInt(analysis.relationTypes().size());
            links.writeInt(counts.relations());
            for (String endpoint : analysis.endpoints().keySet()) writeString(links, endpoint, false);
            for (String type : analysis.relationTypes().keySet()) writeString(links, type, true);

            int[] index = {0};
            readRows(zip, UnifiedGraphFormat.ENTRY_RELATIONS, budget, (row, line) -> {
                Map<String, Object> parsed = parseRow(
                        row, UnifiedGraphFormat.ENTRY_RELATIONS, line);
                LegacyLink link = link(parsed, line, index[0]);
                Integer source = analysis.endpoints().get(link.sourceId());
                Integer target = analysis.endpoints().get(link.targetId());
                Integer type = analysis.relationTypes().get(link.type());
                if (source == null || target == null || type == null) {
                    throw new IOException("Topology dictionary changed while transcoding relation " + link.id());
                }
                Map<String, Object> property = property(parsed, index[0], line);
                int flags = link.directed() ? CompactTopologyCodec.FLAG_DIRECTED : 0;
                if (Double.doubleToRawLongBits(link.weight()) != Double.doubleToRawLongBits(1.0)) {
                    flags |= CompactTopologyCodec.FLAG_WEIGHT;
                }
                if (Double.doubleToRawLongBits(link.confidence()) != Double.doubleToRawLongBits(1.0)) {
                    flags |= CompactTopologyCodec.FLAG_CONFIDENCE;
                }
                if (property != null) flags |= CompactTopologyCodec.FLAG_PROPERTIES;
                links.writeInt(source);
                links.writeInt(target);
                links.writeInt(type);
                links.writeByte(flags);
                writeString(links, link.id(), false);
                if ((flags & CompactTopologyCodec.FLAG_WEIGHT) != 0) {
                    links.writeLong(Double.doubleToRawLongBits(link.weight()));
                }
                if ((flags & CompactTopologyCodec.FLAG_CONFIDENCE) != 0) {
                    links.writeLong(Double.doubleToRawLongBits(link.confidence()));
                }
                if (property != null) {
                    properties.write(MiniJson.write(property));
                    properties.write('\n');
                }
                index[0]++;
            });
            if (index[0] != counts.relations()) {
                throw new IOException("Relation count changed between transcoder passes");
            }
        }
    }

    private static CompactAdjacencyCodec.LinkPass linkPass(
            ZipFile zip, Analysis analysis, Counts counts) {
        return consumer -> {
            long[] budget = {MAX_EXPANDED_BYTES};
            int[] index = {0};
            readRows(zip, UnifiedGraphFormat.ENTRY_RELATIONS, budget, (row, line) -> {
                Map<String, Object> parsed = parseRow(
                        row, UnifiedGraphFormat.ENTRY_RELATIONS, line);
                LegacyLink link = link(parsed, line, index[0]);
                Integer source = analysis.endpoints().get(link.sourceId());
                Integer target = analysis.endpoints().get(link.targetId());
                Integer type = analysis.relationTypes().get(link.type());
                if (source == null || target == null || type == null) {
                    throw new IOException("Adjacency dictionary changed at relation " + link.id());
                }
                consumer.accept(new CompactAdjacencyCodec.RawLink(
                        index[0], link.id(), source, target, type, link.weight(), link.confidence(),
                        link.directed(), property(parsed, index[0], line) != null));
                index[0]++;
            });
            if (index[0] != counts.relations()) {
                throw new IOException("Relation count changed during adjacency pass");
            }
        };
    }

    private static Map<String, Object> targetManifest(
            Map<String, Object> source,
            Analysis analysis,
            Counts counts,
            CompactAdjacencyCodec.Plan adjacency,
            String sourceHash,
            int sourceVersion) {
        Map<String, Object> manifest = new LinkedHashMap<>(source);
        manifest.put("formatVersion", 3);
        Map<String, Object> meta = source.get("meta") instanceof Map<?, ?> raw
                ? stringMap(raw) : new LinkedHashMap<>();
        meta.put(GraphArchiveMigrator.META_SOURCE_SHA256, sourceHash);
        meta.put(GraphArchiveMigrator.META_SOURCE_FORMAT_VERSION, sourceVersion);
        manifest.put("meta", meta);

        Set<String> sourceSections = strings(source.get("sections"));
        List<Object> sections = new ArrayList<>();
        sections.add(UnifiedGraphFormat.ENTRY_SCHEMA_INDEX);
        sections.add(UnifiedGraphFormat.ENTRY_ENTITIES);
        sections.add(UnifiedGraphFormat.ENTRY_COMPACT_LINKS);
        sections.add(UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY);
        if (analysis.hasProperties()) sections.add(UnifiedGraphFormat.ENTRY_RELATION_PROPERTIES);
        if (sourceSections.contains(UnifiedGraphFormat.ENTRY_WEIGHTS)) {
            sections.add(UnifiedGraphFormat.ENTRY_WEIGHTS);
        }
        if (sourceSections.contains(UnifiedGraphFormat.ENTRY_OPINIONS)) {
            sections.add(UnifiedGraphFormat.ENTRY_OPINIONS);
        }
        manifest.put("sections", sections);

        Map<String, Object> topology = new LinkedHashMap<>();
        topology.put("encoding", UnifiedGraphFormat.COMPACT_TOPOLOGY_ENCODING);
        topology.put("encodingVersion", UnifiedGraphFormat.COMPACT_TOPOLOGY_VERSION);
        topology.put("entry", UnifiedGraphFormat.ENTRY_COMPACT_LINKS);
        topology.put("nodeCount", analysis.endpoints().size());
        topology.put("relationTypeCount", analysis.relationTypes().size());
        topology.put("linkCount", counts.relations());
        topology.put("adjacencyEntry", UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY);
        topology.put("adjacencyEncodingVersion", CompactAdjacencyCodec.VERSION);
        topology.put("adjacencyEntries", adjacency.adjacencyEntries());
        if (analysis.hasProperties()) {
            topology.put("propertiesEntry", UnifiedGraphFormat.ENTRY_RELATION_PROPERTIES);
        }
        manifest.put("topology", topology);
        return manifest;
    }

    private static void writeArchive(
            ZipFile source,
            Path target,
            Map<String, Object> manifest,
            Analysis analysis,
            Path links,
            Path properties,
            Path adjacency) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(
                target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
            output.setLevel(Deflater.BEST_SPEED);
            writeEntry(output, UnifiedGraphFormat.ENTRY_MANIFEST,
                    MiniJson.write(manifest).getBytes(StandardCharsets.UTF_8));
            writeEntry(output, UnifiedGraphFormat.ENTRY_SCHEMA_INDEX,
                    MiniJson.write(schema(manifest, analysis)).getBytes(StandardCharsets.UTF_8));
            copyEntry(source, output, UnifiedGraphFormat.ENTRY_ENTITIES);
            writeStoredEntry(output, UnifiedGraphFormat.ENTRY_COMPACT_LINKS, links);
            writeStoredEntry(output, UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY, adjacency);
            if (analysis.hasProperties()) {
                copyFileEntry(output, UnifiedGraphFormat.ENTRY_RELATION_PROPERTIES, properties);
            }

            Set<String> skipped = Set.of(
                    UnifiedGraphFormat.ENTRY_MANIFEST,
                    UnifiedGraphFormat.ENTRY_SCHEMA_INDEX,
                    UnifiedGraphFormat.ENTRY_ENTITIES,
                    UnifiedGraphFormat.ENTRY_RELATIONS,
                    UnifiedGraphFormat.ENTRY_COMPACT_LINKS,
                    UnifiedGraphFormat.ENTRY_COMPACT_ADJACENCY,
                    UnifiedGraphFormat.ENTRY_RELATION_PROPERTIES);
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || skipped.contains(entry.getName())) continue;
                copyEntry(source, output, entry);
            }
        }
    }

    private static Map<String, Object> schema(
            Map<String, Object> manifest, Analysis analysis) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("format", "kompile-unified-schema");
        schema.put("version", 1);
        Counts counts = countsUnchecked(manifest);
        schema.put("entityCount", counts.entities());
        schema.put("relationCount", counts.relations());
        schema.put("entityTypes", sorted(analysis.entityTypes()));
        schema.put("relationTypes", sorted(analysis.relationSchemaTypes()));
        schema.put("entityAttributeKeys", sorted(analysis.entityAttributeKeys()));
        schema.put("relationAttributeKeys", sorted(analysis.relationAttributeKeys()));
        Set<String> artifacts = strings(manifest.get("artifacts"));
        schema.put("declaredSchemaArtifacts", artifacts.stream()
                .filter(name -> name.startsWith("schema/")).sorted().toList());
        return schema;
    }

    private static void validateTarget(Path target) throws IOException {
        try (UnifiedGraphArchive archive = UnifiedGraphArchive.open(target)) {
            archive.validateAllEntries();
            if (!archive.hasCompactTopology()) {
                throw new IOException("Transcoded archive does not contain compact topology");
            }
            if (!archive.hasAdjacencyIndex()) {
                throw new IOException("Transcoded archive does not contain compact adjacency");
            }
            archive.validateAdjacencyIndex();
            int count = 0;
            try (UnifiedGraphArchive.LinkCursor links = archive.openLinks()) {
                while (links.next() != null) count++;
            }
            if (count != archive.linkCount()) {
                throw new IOException("Transcoded compact topology link count mismatch");
            }
        }
    }

    private static Map<String, Object> readManifest(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(UnifiedGraphFormat.ENTRY_MANIFEST);
        if (entry == null) throw new IOException("Missing unified-graph manifest");
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = readBounded(input, 16 * 1024 * 1024, entry.getName());
            try {
                return MiniJson.parseObject(new String(bytes, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException malformed) {
                throw new IOException("Invalid unified-graph manifest JSON", malformed);
            }
        }
    }

    private static Counts counts(Map<String, Object> manifest) throws IOException {
        if (!(manifest.get("counts") instanceof Map<?, ?> raw)) {
            throw new IOException("Unified-graph manifest counts must be an object");
        }
        return new Counts(nonNegativeInt(raw.get("entities"), "counts.entities"),
                nonNegativeInt(raw.get("relations"), "counts.relations"));
    }

    private static Counts countsUnchecked(Map<String, Object> manifest) {
        Map<?, ?> raw = (Map<?, ?>) manifest.get("counts");
        return new Counts(((Number) raw.get("entities")).intValue(),
                ((Number) raw.get("relations")).intValue());
    }

    private static int nonNegativeInt(Object value, String field) throws IOException {
        if (!(value instanceof Number number)) throw new IOException(field + " must be an integer");
        long result = number.longValue();
        if (result < 0 || result > Integer.MAX_VALUE || number.doubleValue() != result) {
            throw new IOException(field + " must be a non-negative integer");
        }
        return (int) result;
    }

    private static void readRows(
            ZipFile zip, String name, long[] budget, RowConsumer consumer) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("Missing unified-graph entry: " + name);
        try (InputStream input = zip.getInputStream(entry)) {
            InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
            char[] chunk = new char[8_192];
            StringBuilder row = new StringBuilder(512);
            long line = 1;
            int read;
            while ((read = reader.read(chunk)) != -1) {
                budget[0] -= read;
                if (budget[0] < 0) throw new IOException("Unified graph exceeds migration expansion limit");
                for (int i = 0; i < read; i++) {
                    char value = chunk[i];
                    if (value == '\n') {
                        emitRow(row, line++, consumer);
                        row.setLength(0);
                    } else {
                        if (row.length() >= MAX_ROW_CHARS) {
                            throw new IOException(name + " row exceeds character limit at line " + line);
                        }
                        row.append(value);
                    }
                }
            }
            if (!row.isEmpty()) emitRow(row, line, consumer);
        }
    }

    private static void emitRow(
            StringBuilder row, long line, RowConsumer consumer) throws IOException {
        String value = row.toString();
        if (!value.isBlank()) consumer.accept(value, line);
    }

    private static Map<String, Object> parseRow(String row, String entry, long line)
            throws IOException {
        try {
            return MiniJson.parseObject(row);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Invalid JSON row in " + entry + " at line " + line, malformed);
        }
    }

    private static LegacyLink link(Map<String, Object> value, long line, int index)
            throws IOException {
        String id = stringValue(value.get("id"));
        String source = stringValue(value.get("sourceId"));
        String target = stringValue(value.get("targetId"));
        String type = optionalString(value.get("type"), "type", line);
        if (id == null || id.isBlank() || source == null || source.isBlank()
                || target == null || target.isBlank()) {
            throw new IOException("Invalid relation row at line " + line);
        }
        return new LegacyLink(index, id, source, target, type == null ? "" : type,
                number(value.get("weight"), 1.0), number(value.get("confidence"), 1.0),
                bool(value.get("directed"), true));
    }

    private static Map<String, Object> property(
            Map<String, Object> value, int index, long line) throws IOException {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("link", index);
        if (value.get("tags") instanceof List<?> rawTags) {
            List<String> tags = rawTags.stream().filter(java.util.Objects::nonNull)
                    .map(String::valueOf).map(String::trim).filter(tag -> !tag.isEmpty()).distinct().toList();
            if (!tags.isEmpty()) property.put("tags", tags);
        }
        if (value.get("timestamp") instanceof String rawTimestamp && !rawTimestamp.isBlank()) {
            try {
                property.put("timestamp", Instant.parse(rawTimestamp).toString());
            } catch (DateTimeParseException ignored) {
                // Legacy materialization treated malformed timestamps as absent.
            }
        }
        Map<String, Object> attributes = attributes(value, UnifiedGraphFormat.ENTRY_RELATIONS, line);
        if (!attributes.isEmpty()) property.put("attributes", attributes);
        Opinion opinion = opinion(value.get("opinion"));
        if (opinion != null) {
            property.put("opinion", Map.of("b", opinion.belief(), "d", opinion.disbelief(),
                    "u", opinion.uncertainty(), "a", opinion.baseRate()));
        }
        return property.size() == 1 ? null : property;
    }

    private static void collectAttributes(
            Map<String, Object> value, Set<String> keys, String entry, long line) throws IOException {
        keys.addAll(attributes(value, entry, line).keySet());
    }

    private static Map<String, Object> attributes(
            Map<String, Object> value, String entry, long line) throws IOException {
        if (!value.containsKey("attributes")) return Map.of();
        if (!(value.get("attributes") instanceof Map<?, ?> raw)) {
            throw new IOException("attributes must be an object in " + entry + " at line " + line);
        }
        Map<String, Object> attributes = new LinkedHashMap<>();
        for (Map.Entry<?, ?> item : raw.entrySet()) {
            if (item.getKey() == null || item.getValue() == null) {
                throw new IOException("attributes contain null in " + entry + " at line " + line);
            }
            attributes.put(String.valueOf(item.getKey()), item.getValue());
        }
        return attributes;
    }

    private static boolean hasAdditionalTypeMembership(
            Map<String, Object> value, String primary, long line) throws IOException {
        Object raw = value.get("typeMemberships");
        if (raw == null) return false;
        if (!(raw instanceof List<?> memberships)) {
            throw new IOException("typeMemberships must be an array at entity line " + line);
        }
        boolean additional = false;
        for (Object item : memberships) {
            if (!(item instanceof String membership) || membership.isBlank()) {
                throw new IOException("typeMemberships must contain non-empty strings at entity line " + line);
            }
            additional |= !membership.equals(primary);
        }
        return additional;
    }

    private static Opinion opinion(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return null;
        double belief = number(raw.get("b"), Double.NaN);
        double disbelief = number(raw.get("d"), Double.NaN);
        double uncertainty = number(raw.get("u"), Double.NaN);
        double baseRate = number(raw.get("a"), 0.5);
        if (Double.isNaN(belief) || Double.isNaN(disbelief) || Double.isNaN(uncertainty)) return null;
        return new Opinion(belief, disbelief, uncertainty, baseRate);
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String string) {
            return switch (string) {
                case "NaN" -> Double.NaN;
                case "Infinity" -> Double.POSITIVE_INFINITY;
                case "-Infinity" -> Double.NEGATIVE_INFINITY;
                default -> {
                    try { yield Double.parseDouble(string); }
                    catch (NumberFormatException ignored) { yield fallback; }
                }
            };
        }
        return fallback;
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String string) return Boolean.parseBoolean(string);
        return fallback;
    }

    private static String optionalString(Object value, String field, long line) throws IOException {
        if (value == null) return null;
        if (value instanceof String string) return string;
        throw new IOException(field + " must be a string at line " + line);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void addDictionary(
            LinkedHashMap<String, Integer> dictionary, String value, boolean allowEmpty, String field)
            throws IOException {
        if (value == null || !allowEmpty && value.isBlank()) {
            throw new IOException(field + " must not be blank");
        }
        dictionary.computeIfAbsent(value, ignored -> dictionary.size());
    }

    private static void writeString(DataOutputStream output, String value, boolean allowEmpty)
            throws IOException {
        if (value == null || !allowEmpty && value.isBlank()) {
            throw new IOException("Compact topology string must not be blank");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("Compact topology string is too large");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void writeEntry(ZipOutputStream output, String name, byte[] bytes)
            throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static void copyEntry(ZipFile source, ZipOutputStream output, String name)
            throws IOException {
        ZipEntry entry = source.getEntry(name);
        if (entry == null) throw new IOException("Missing source entry " + name);
        copyEntry(source, output, entry);
    }

    private static void copyEntry(ZipFile source, ZipOutputStream output, ZipEntry entry)
            throws IOException {
        ZipEntry copy = new ZipEntry(entry.getName());
        if (entry.getMethod() == ZipEntry.STORED) {
            copy.setMethod(ZipEntry.STORED);
            copy.setSize(entry.getSize());
            copy.setCompressedSize(entry.getSize());
            copy.setCrc(entry.getCrc());
        }
        output.putNextEntry(copy);
        try (InputStream input = source.getInputStream(entry)) {
            input.transferTo(output);
        }
        output.closeEntry();
    }

    private static void copyFileEntry(ZipOutputStream output, String name, Path file)
            throws IOException {
        output.putNextEntry(new ZipEntry(name));
        Files.copy(file, output);
        output.closeEntry();
    }

    private static void writeStoredEntry(ZipOutputStream output, String name, Path file)
            throws IOException {
        long size = Files.size(file);
        CRC32 crc = new CRC32();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] chunk = new byte[8_192];
            int read;
            while ((read = input.read(chunk)) != -1) crc.update(chunk, 0, read);
        }
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(size);
        entry.setCompressedSize(size);
        entry.setCrc(crc.getValue());
        output.putNextEntry(entry);
        Files.copy(file, output);
        output.closeEntry();
    }

    private static byte[] readBounded(InputStream input, int limit, String name) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8_192];
        int total = 0;
        int read;
        while ((read = input.read(chunk)) != -1) {
            if (total > limit - read) throw new IOException(name + " exceeds size limit");
            output.write(chunk, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private static Set<String> strings(Object value) {
        if (!(value instanceof Iterable<?> values)) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (Object item : values) if (item != null) result.add(String.valueOf(item));
        return result;
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private record Counts(int entities, int relations) { }

    private record Analysis(
            LinkedHashMap<String, Integer> endpoints,
            LinkedHashMap<String, Integer> relationTypes,
            boolean hasProperties,
            Set<String> entityTypes,
            Set<String> relationSchemaTypes,
            Set<String> entityAttributeKeys,
            Set<String> relationAttributeKeys) { }

    private record LegacyLink(
            int index, String id, String sourceId, String targetId, String type,
            double weight, double confidence, boolean directed) { }

    @FunctionalInterface
    private interface RowConsumer {
        void accept(String row, long line) throws IOException;
    }
}
