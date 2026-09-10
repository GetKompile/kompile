/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * Durable append-only overlay for small compact-KGraph mutations.
 *
 * <p>The base {@code .kgraph} remains immutable between compactions. Each forced journal append is
 * one length-prefixed, CRC-protected transaction, so assertions and retractions do not rewrite the
 * million-link archive. Readers load the bounded journal once and overlay its last-write-wins entity
 * and relation states. Topology rebuilds compact the journal through {@link UnifiedGraphArchiveEditor}.</p>
 */
public final class UnifiedGraphMutationJournal {

    private static final int MAGIC = 0x4b474d4a; // KGMJ
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 8;
    private static final int MAX_RECORD_BYTES = Math.max(1,
            Integer.getInteger("kompile.graph.journal.maxRecordBytes", 1024 * 1024));
    private static final long MAX_JOURNAL_BYTES = Math.max(HEADER_BYTES,
            Long.getLong("kompile.graph.journal.maxBytes", 64L * 1024L * 1024L));
    private static final int MAX_RECORDS = Math.max(1,
            Integer.getInteger("kompile.graph.journal.maxRecords", 100_000));
    private static final int COMPACT_RECORDS = Math.max(1,
            Integer.getInteger("kompile.graph.journal.compactRecords", 10_000));
    private static final long COMPACT_BYTES = Math.max(HEADER_BYTES,
            Long.getLong("kompile.graph.journal.compactBytes", 16L * 1024L * 1024L));

    private UnifiedGraphMutationJournal() { }

    public static Path pathFor(Path archive) {
        Path normalized = Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        return normalized.resolveSibling(normalized.getFileName() + ".journal");
    }

    private static Path lockPath(Path archive) {
        Path journal = pathFor(archive);
        return journal.resolveSibling("." + journal.getFileName() + ".lock");
    }

    public static boolean exists(Path archive) throws IOException {
        Path journal = pathFor(archive);
        return Files.isRegularFile(journal) && Files.size(journal) > HEADER_BYTES;
    }

    public static Snapshot load(Path archive) throws IOException {
        Path journal = pathFor(archive);
        if (!Files.isRegularFile(journal)) return Snapshot.empty();
        Path lock = lockPath(archive);
        if (lock.getParent() != null) Files.createDirectories(lock.getParent());
        try (FileChannel lockChannel = FileChannel.open(lock,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.READ,
                java.nio.file.StandardOpenOption.WRITE);
             FileLock journalLock = lockChannel.lock(0L, Long.MAX_VALUE, true);
             RandomAccessFile file = new RandomAccessFile(journal.toFile(), "r")) {
            if (!journalLock.isValid()) throw new IOException("Could not lock KGraph mutation journal");
            return readSnapshot(file);
        }
    }

    /** Append one entity/relation assertion transaction and force it to stable storage. */
    public static AppendResult appendAssertion(
            Path archive,
            Collection<? extends GraphEntity> newEntities,
            UnifiedGraphArchive.Link asserted,
            UnifiedGraphArchive.Link previousLogicalValue) throws IOException {
        Objects.requireNonNull(asserted, "asserted");
        Collection<? extends GraphEntity> entities = newEntities == null ? List.of() : newEntities;
        return append(archive, snapshot -> {
            List<Object> entityRows = new ArrayList<>();
            for (GraphEntity entity : entities) {
                EntityState prior = snapshot.entityStates().get(entity.id());
                boolean basePresent = prior != null && prior.basePresent();
                entityRows.add(Map.of(
                        "id", entity.id(),
                        "basePresent", basePresent,
                        "value", entityMap(entity)));
            }
            RelationState prior = snapshot.relationStates().get(asserted.id());
            boolean basePresent = prior != null ? prior.basePresent() : previousLogicalValue != null;
            UnifiedGraphArchive.Link baseLink = prior != null ? prior.baseLink() : previousLogicalValue;
            Map<String, Object> relation = new LinkedHashMap<>();
            relation.put("id", asserted.id());
            relation.put("basePresent", basePresent);
            if (baseLink != null) relation.put("base", linkMap(baseLink));
            relation.put("value", linkMap(asserted));
            return transaction(entityRows, List.of(relation));
        });
    }

    /** Append one transaction that tombstones all supplied currently visible relations. */
    public static AppendResult appendRetractions(
            Path archive, Collection<UnifiedGraphArchive.Link> currentLinks) throws IOException {
        Collection<UnifiedGraphArchive.Link> links = currentLinks == null ? List.of() : currentLinks;
        if (links.isEmpty()) {
            Snapshot snapshot = load(archive);
            return result(archive, snapshot);
        }
        return append(archive, snapshot -> {
            List<Object> relationRows = new ArrayList<>();
            for (UnifiedGraphArchive.Link current : links) {
                RelationState prior = snapshot.relationStates().get(current.id());
                boolean basePresent = prior == null || prior.basePresent();
                UnifiedGraphArchive.Link baseLink = prior != null ? prior.baseLink() : current;
                Map<String, Object> relation = new LinkedHashMap<>();
                relation.put("id", current.id());
                relation.put("basePresent", basePresent);
                if (baseLink != null) relation.put("base", linkMap(baseLink));
                relation.put("value", null);
                relationRows.add(relation);
            }
            return transaction(List.of(), relationRows);
        });
    }

    private static Map<String, Object> transaction(List<Object> entities, List<Object> relations) {
        Map<String, Object> transaction = new LinkedHashMap<>();
        transaction.put("transactionId", UUID.randomUUID().toString());
        transaction.put("timestamp", Instant.now().toString());
        transaction.put("entities", entities);
        transaction.put("relations", relations);
        return transaction;
    }

    @FunctionalInterface
    private interface TransactionFactory {
        Map<String, Object> create(Snapshot snapshot) throws IOException;
    }

    private static AppendResult append(Path archive, TransactionFactory factory) throws IOException {
        Path journal = pathFor(archive);
        Path lock = lockPath(archive);
        if (journal.getParent() != null) Files.createDirectories(journal.getParent());
        try (FileChannel lockChannel = FileChannel.open(lock,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             FileLock journalLock = lockChannel.lock();
             RandomAccessFile file = new RandomAccessFile(journal.toFile(), "rw")) {
            if (!journalLock.isValid()) throw new IOException("Could not lock KGraph mutation journal");
            if (file.length() == 0) {
                file.writeInt(MAGIC);
                file.writeInt(VERSION);
                file.getChannel().force(true);
            }
            Snapshot before = readSnapshot(file);
            if (before.recordCount() >= MAX_RECORDS) {
                throw new IOException("KGraph mutation journal exceeds record limit; compact it first");
            }
            Map<String, Object> transaction = new LinkedHashMap<>(factory.create(before));
            transaction.put("sequence", before.lastSequence() + 1L);
            byte[] payload = MiniJson.write(transaction).getBytes(StandardCharsets.UTF_8);
            if (payload.length == 0 || payload.length > MAX_RECORD_BYTES) {
                throw new IOException("KGraph mutation journal transaction exceeds record limit");
            }
            long nextLength = file.length() + Integer.BYTES * 2L + payload.length;
            if (nextLength > MAX_JOURNAL_BYTES) {
                throw new IOException("KGraph mutation journal exceeds size limit; compact it first");
            }
            CRC32 crc = new CRC32();
            crc.update(payload);
            file.seek(file.length());
            file.writeInt(payload.length);
            file.writeInt((int) crc.getValue());
            file.write(payload);
            file.getChannel().force(true);
            Snapshot after = readSnapshot(file);
            return result(archive, after);
        }
    }

    private static AppendResult result(Path archive, Snapshot snapshot) throws IOException {
        Path journal = pathFor(archive);
        long bytes = Files.isRegularFile(journal) ? Files.size(journal) : 0L;
        return new AppendResult(snapshot.recordCount(), bytes,
                snapshot.recordCount() >= COMPACT_RECORDS || bytes >= COMPACT_BYTES);
    }

    private static Snapshot readSnapshot(RandomAccessFile file) throws IOException {
        long length = file.length();
        if (length == 0) return Snapshot.empty();
        if (length < HEADER_BYTES || length > MAX_JOURNAL_BYTES) {
            throw new IOException("Invalid KGraph mutation journal size");
        }
        file.seek(0L);
        if (file.readInt() != MAGIC || file.readInt() != VERSION) {
            throw new IOException("Invalid KGraph mutation journal header");
        }
        Map<String, EntityState> entities = new LinkedHashMap<>();
        Map<String, RelationState> relations = new LinkedHashMap<>();
        long lastSequence = 0L;
        int records = 0;
        while (file.getFilePointer() < length) {
            if (length - file.getFilePointer() < Integer.BYTES * 2L) {
                throw new IOException("Truncated KGraph mutation journal record header");
            }
            int payloadLength = file.readInt();
            long expectedCrc = Integer.toUnsignedLong(file.readInt());
            if (payloadLength <= 0 || payloadLength > MAX_RECORD_BYTES
                    || payloadLength > length - file.getFilePointer()) {
                throw new IOException("Invalid or truncated KGraph mutation journal record");
            }
            byte[] payload = new byte[payloadLength];
            file.readFully(payload);
            CRC32 crc = new CRC32();
            crc.update(payload);
            if (crc.getValue() != expectedCrc) {
                throw new IOException("KGraph mutation journal CRC mismatch");
            }
            Map<String, Object> transaction;
            try {
                transaction = MiniJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException malformed) {
                throw new IOException("Invalid KGraph mutation journal JSON", malformed);
            }
            long sequence = integer(transaction.get("sequence"), "sequence");
            if (sequence != lastSequence + 1L) {
                throw new IOException("KGraph mutation journal sequence is not contiguous");
            }
            applyEntities(transaction.get("entities"), entities);
            applyRelations(transaction.get("relations"), relations);
            lastSequence = sequence;
            records++;
            if (records > MAX_RECORDS) throw new IOException("KGraph mutation journal exceeds record limit");
        }
        return new Snapshot(entities, relations, records, lastSequence);
    }

    private static void applyEntities(Object raw, Map<String, EntityState> states) throws IOException {
        if (!(raw instanceof List<?> rows)) throw new IOException("Journal entities must be an array");
        for (Object row : rows) {
            Map<String, Object> value = object(row, "entity mutation");
            String id = string(value.get("id"), "entity id");
            boolean declaredBase = bool(value.get("basePresent"), "entity basePresent");
            EntityState prior = states.get(id);
            boolean basePresent = prior != null ? prior.basePresent() : declaredBase;
            states.put(id, new EntityState(basePresent,
                    entity(object(value.get("value"), "entity value"))));
        }
    }

    private static void applyRelations(Object raw, Map<String, RelationState> states) throws IOException {
        if (!(raw instanceof List<?> rows)) throw new IOException("Journal relations must be an array");
        for (Object row : rows) {
            Map<String, Object> value = object(row, "relation mutation");
            String id = string(value.get("id"), "relation id");
            boolean declaredBase = bool(value.get("basePresent"), "relation basePresent");
            RelationState prior = states.get(id);
            boolean basePresent = prior != null ? prior.basePresent() : declaredBase;
            UnifiedGraphArchive.Link base = prior != null ? prior.baseLink()
                    : value.get("base") == null ? null : link(object(value.get("base"), "base relation"));
            UnifiedGraphArchive.Link current = value.get("value") == null ? null
                    : link(object(value.get("value"), "relation value"));
            if (!id.equals(current == null ? id : current.id()) || basePresent && base == null) {
                throw new IOException("Journal relation baseline is inconsistent for " + id);
            }
            states.put(id, new RelationState(id, basePresent, base, current));
        }
    }

    /** Materialize the overlay into the base v3 archive, then remove the journal atomically. */
    public static boolean compact(Path archive) throws IOException {
        Path journal = pathFor(archive);
        if (!Files.isRegularFile(journal) || Files.size(journal) <= HEADER_BYTES) return false;
        Path lock = lockPath(archive);
        try (FileChannel lockChannel = FileChannel.open(lock,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             FileLock journalLock = lockChannel.lock()) {
            if (!journalLock.isValid()) throw new IOException("Could not lock KGraph mutation journal");
            if (!Files.isRegularFile(journal) || Files.size(journal) <= HEADER_BYTES) return false;
            Snapshot snapshot;
            try (RandomAccessFile file = new RandomAccessFile(journal.toFile(), "r")) {
                snapshot = readSnapshot(file);
            }
            Path stagedJournal = journal.resolveSibling(journal.getFileName() + ".compacting");
            move(journal, stagedJournal);
            boolean compacted = false;
            try {
                UnifiedGraph additions = new UnifiedGraph();
                snapshot.entityStates().values().forEach(state -> additions.addEntity(state.current()));
                for (RelationState state : snapshot.relationStates().values()) {
                    if (state.current() == null) continue;
                    UnifiedGraphArchive.Link link = state.current();
                    additions.addRelation(new ai.kompile.graph.reasoning.model.SimpleGraphRelation(
                            link.id(), link.sourceId(), link.targetId(), link.type(), link.weight(),
                            link.confidence(), link.directed(), link.tags(), null, link.timestamp(),
                            link.attributes()));
                    if (link.opinion() != null) additions.putRelationOpinion(link.id(), link.opinion());
                }
                Set<String> mutated = snapshot.relationStates().keySet();
                UnifiedGraphArchiveEditor.rewrite(archive, archive, additions, null,
                        link -> !mutated.contains(link.id()),
                        Map.of("mutationJournal.compactedAt", Instant.now().toString(),
                                "mutationJournal.compactedRecords", snapshot.recordCount(),
                                "learning.reasoningStale", true, "learning.kgeStale", true));
                Files.deleteIfExists(stagedJournal);
                compacted = true;
                return true;
            } finally {
                if (!compacted && Files.isRegularFile(stagedJournal)) move(stagedJournal, journal);
            }
        }
    }

    public static void clear(Path archive) throws IOException {
        Files.deleteIfExists(pathFor(archive));
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Apply this journal to a fully materialized compatibility graph. */
    public static void apply(Path archive, UnifiedGraph graph) throws IOException {
        Snapshot snapshot = load(archive);
        // Stored models describe the pre-mutation topology, even if all entity ids survive.
        if (!snapshot.isEmpty()) {
            graph.meta("learning.reasoningStale", true).meta("learning.kgeStale", true);
        }
        snapshot.entityStates().values().forEach(state -> graph.addEntity(state.current()));
        for (RelationState state : snapshot.relationStates().values()) {
            GraphRelation previous = graph.relation(state.id()).orElse(null);
            Opinion previousOpinion = graph.relationOpinion(state.id());
            Map<String, double[]> previousVectors = new LinkedHashMap<>();
            for (VectorLayer layer : graph.vectorLayers().values()) {
                if (layer.target() == VectorLayer.Target.RELATION && layer.contains(state.id())) {
                    previousVectors.put(layer.name(), layer.get(state.id()).clone());
                }
            }
            graph.removeRelationById(state.id());
            if (state.current() == null) continue;
            UnifiedGraphArchive.Link link = state.current();
            graph.addRelation(new ai.kompile.graph.reasoning.model.SimpleGraphRelation(
                    link.id(), link.sourceId(), link.targetId(), link.type(), link.weight(),
                    link.confidence(), link.directed(), link.tags(),
                    previous == null || previous.embedding() == null
                            ? null : previous.embedding().clone(), link.timestamp(),
                    link.attributes()));
            previousVectors.forEach((name, values) -> graph.vectorLayer(name).put(link.id(), values));
            Opinion retainedOpinion = link.opinion() != null ? link.opinion() : previousOpinion;
            if (retainedOpinion != null) graph.putRelationOpinion(link.id(), retainedOpinion);
        }
    }

    private static Map<String, Object> entityMap(GraphEntity entity) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", entity.id());
        value.put("type", entity.type());
        value.put("label", entity.label());
        value.put("weight", entity.weight());
        value.put("confidence", entity.confidence());
        value.put("tags", new ArrayList<>(entity.tags()));
        if (entity.timestamp() != null) value.put("timestamp", entity.timestamp().toString());
        value.put("attributes", entity.attributes());
        return value;
    }

    private static GraphEntity entity(Map<String, Object> value) throws IOException {
        return new SimpleGraphEntity(
                string(value.get("id"), "entity id"),
                optionalString(value.get("type")),
                optionalString(value.get("label")),
                decimal(value.get("weight"), 1.0),
                decimal(value.get("confidence"), 1.0),
                strings(value.get("tags")), null,
                instant(value.get("timestamp")), attributes(value.get("attributes")));
    }

    private static Map<String, Object> linkMap(UnifiedGraphArchive.Link link) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("index", link.index());
        value.put("id", link.id());
        value.put("sourceId", link.sourceId());
        value.put("targetId", link.targetId());
        value.put("type", link.type());
        value.put("weight", link.weight());
        value.put("confidence", link.confidence());
        value.put("directed", link.directed());
        value.put("tags", new ArrayList<>(link.tags()));
        if (link.timestamp() != null) value.put("timestamp", link.timestamp().toString());
        value.put("attributes", link.attributes());
        if (link.opinion() != null) value.put("opinion", opinionMap(link.opinion()));
        return value;
    }

    private static UnifiedGraphArchive.Link link(Map<String, Object> value) throws IOException {
        Opinion opinion = value.get("opinion") == null ? null
                : opinion(object(value.get("opinion"), "relation opinion"));
        int index = value.get("index") instanceof Number number ? number.intValue() : -1;
        return new UnifiedGraphArchive.Link(index,
                string(value.get("id"), "relation id"),
                string(value.get("sourceId"), "relation sourceId"),
                string(value.get("targetId"), "relation targetId"),
                optionalString(value.get("type")),
                decimal(value.get("weight"), 1.0),
                decimal(value.get("confidence"), 1.0),
                bool(value.get("directed"), "relation directed"), true,
                strings(value.get("tags")), instant(value.get("timestamp")),
                attributes(value.get("attributes")), opinion);
    }

    private static Map<String, Object> opinionMap(Opinion opinion) {
        return Map.of("b", opinion.belief(), "d", opinion.disbelief(),
                "u", opinion.uncertainty(), "a", opinion.baseRate());
    }

    private static Opinion opinion(Map<String, Object> value) throws IOException {
        return new Opinion(decimal(value.get("b"), Double.NaN),
                decimal(value.get("d"), Double.NaN),
                decimal(value.get("u"), Double.NaN), decimal(value.get("a"), 0.5));
    }

    private static Map<String, Object> object(Object raw, String field) throws IOException {
        if (!(raw instanceof Map<?, ?> map)) throw new IOException("Journal " + field + " must be an object");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) throw new IOException("Journal " + field + " has a null key");
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static String string(Object raw, String field) throws IOException {
        if (!(raw instanceof String value) || value.isBlank()) {
            throw new IOException("Journal " + field + " must not be blank");
        }
        return value;
    }

    private static String optionalString(Object raw) throws IOException {
        if (raw == null) return "";
        if (!(raw instanceof String value)) throw new IOException("Journal string field has invalid type");
        return value;
    }

    private static boolean bool(Object raw, String field) throws IOException {
        if (!(raw instanceof Boolean value)) throw new IOException("Journal " + field + " must be boolean");
        return value;
    }

    private static long integer(Object raw, String field) throws IOException {
        if (!(raw instanceof Number value) || value.longValue() < 0
                || value.doubleValue() != value.longValue()) {
            throw new IOException("Journal " + field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private static double decimal(Object raw, double fallback) throws IOException {
        if (raw == null) return fallback;
        if (!(raw instanceof Number number)) throw new IOException("Journal numeric field has invalid type");
        return number.doubleValue();
    }

    private static Set<String> strings(Object raw) throws IOException {
        if (raw == null) return Set.of();
        if (!(raw instanceof List<?> values)) throw new IOException("Journal string set must be an array");
        Set<String> result = new LinkedHashSet<>();
        for (Object value : values) {
            if (!(value instanceof String text)) throw new IOException("Journal string set has invalid value");
            result.add(text);
        }
        return Set.copyOf(result);
    }

    private static Map<String, Object> attributes(Object raw) throws IOException {
        if (raw == null) return Map.of();
        return Collections.unmodifiableMap(object(raw, "attributes"));
    }

    private static Instant instant(Object raw) throws IOException {
        if (raw == null) return null;
        if (!(raw instanceof String value)) throw new IOException("Journal timestamp must be a string");
        try { return Instant.parse(value); }
        catch (DateTimeParseException invalid) { throw new IOException("Invalid journal timestamp", invalid); }
    }

    public record AppendResult(int recordCount, long bytes, boolean compactionRecommended) { }

    public record EntityState(boolean basePresent, GraphEntity current) {
        public EntityState { Objects.requireNonNull(current, "current"); }
    }

    public record RelationState(
            String id,
            boolean basePresent,
            UnifiedGraphArchive.Link baseLink,
            UnifiedGraphArchive.Link current) {
        public RelationState { Objects.requireNonNull(id, "id"); }
    }

    public static final class Snapshot {
        private static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), 0, 0L);
        private final Map<String, EntityState> entities;
        private final Map<String, RelationState> relations;
        private final Map<String, List<UnifiedGraphArchive.Link>> outgoing;
        private final Map<String, List<UnifiedGraphArchive.Link>> incoming;
        private final int recordCount;
        private final long lastSequence;

        private Snapshot(
                Map<String, EntityState> entities,
                Map<String, RelationState> relations,
                int recordCount,
                long lastSequence) {
            this.entities = Collections.unmodifiableMap(new LinkedHashMap<>(entities));
            this.relations = Collections.unmodifiableMap(new LinkedHashMap<>(relations));
            Map<String, List<UnifiedGraphArchive.Link>> out = new LinkedHashMap<>();
            Map<String, List<UnifiedGraphArchive.Link>> in = new LinkedHashMap<>();
            for (RelationState state : relations.values()) {
                UnifiedGraphArchive.Link link = state.current();
                if (link == null) continue;
                out.computeIfAbsent(link.sourceId(), ignored -> new ArrayList<>()).add(link);
                in.computeIfAbsent(link.targetId(), ignored -> new ArrayList<>()).add(link);
                if (!link.directed() && !link.sourceId().equals(link.targetId())) {
                    out.computeIfAbsent(link.targetId(), ignored -> new ArrayList<>()).add(link);
                    in.computeIfAbsent(link.sourceId(), ignored -> new ArrayList<>()).add(link);
                }
            }
            this.outgoing = immutableLists(out);
            this.incoming = immutableLists(in);
            this.recordCount = recordCount;
            this.lastSequence = lastSequence;
        }

        private static Map<String, List<UnifiedGraphArchive.Link>> immutableLists(
                Map<String, List<UnifiedGraphArchive.Link>> source) {
            Map<String, List<UnifiedGraphArchive.Link>> result = new LinkedHashMap<>();
            source.forEach((key, value) -> result.put(key, List.copyOf(value)));
            return Collections.unmodifiableMap(result);
        }

        public static Snapshot empty() { return EMPTY; }
        public boolean isEmpty() { return recordCount == 0; }
        public int recordCount() { return recordCount; }
        public long lastSequence() { return lastSequence; }
        public Map<String, EntityState> entityStates() { return entities; }
        public Map<String, RelationState> relationStates() { return relations; }
        public int entityDelta() {
            return (int) entities.values().stream().filter(state -> !state.basePresent()).count();
        }
        public int relationDelta() {
            int delta = 0;
            for (RelationState state : relations.values()) {
                if (state.basePresent() && state.current() == null) delta--;
                else if (!state.basePresent() && state.current() != null) delta++;
            }
            return delta;
        }
        public List<UnifiedGraphArchive.Link> incident(
                String nodeId, GraphQueryEngine.Direction direction) {
            GraphQueryEngine.Direction effective = direction == null
                    ? GraphQueryEngine.Direction.BOTH : direction;
            LinkedHashMap<String, UnifiedGraphArchive.Link> result = new LinkedHashMap<>();
            if (effective != GraphQueryEngine.Direction.INCOMING) {
                outgoing.getOrDefault(nodeId, List.of()).forEach(link -> result.put(link.id(), link));
            }
            if (effective != GraphQueryEngine.Direction.OUTGOING) {
                incoming.getOrDefault(nodeId, List.of()).forEach(link -> result.put(link.id(), link));
            }
            return List.copyOf(result.values());
        }
    }
}
