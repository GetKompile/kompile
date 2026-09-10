/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.model.GraphRelation;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Sequential dictionary-encoded topology used by compact v3 archives. */
final class CompactTopologyCodec {

    static final int MAGIC = 0x4b474c4b; // KGLK
    static final int CODEC_VERSION = 1;
    static final int FLAG_DIRECTED = 1;
    static final int FLAG_WEIGHT = 1 << 1;
    static final int FLAG_CONFIDENCE = 1 << 2;
    static final int FLAG_PROPERTIES = 1 << 3;
    private static final int SUPPORTED_FLAGS = FLAG_DIRECTED | FLAG_WEIGHT | FLAG_CONFIDENCE | FLAG_PROPERTIES;

    private CompactTopologyCodec() { }

    static Plan plan(UnifiedGraph graph) throws IOException {
        LinkedHashMap<String, Integer> nodes = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> types = new LinkedHashMap<>();
        boolean properties = false;
        int links = 0;
        for (GraphRelation relation : graph.relations()) {
            addDictionaryValue(nodes, relation.sourceId(), "relation source id", false);
            addDictionaryValue(nodes, relation.targetId(), "relation target id", false);
            addDictionaryValue(types, relation.type() == null ? "" : relation.type(), "relation type", true);
            if (relation.id() == null || relation.id().isBlank()) {
                throw new IOException("Compact topology relation id must not be blank");
            }
            properties |= hasProperties(graph, relation);
            if (links == Integer.MAX_VALUE) throw new IOException("Compact topology has too many links");
            links++;
        }
        return new Plan(nodes, types, links, properties);
    }

    private static void addDictionaryValue(
            LinkedHashMap<String, Integer> dictionary,
            String value,
            String field,
            boolean allowEmpty) throws IOException {
        if (value == null || (!allowEmpty && value.isBlank())) {
            throw new IOException("Compact topology " + field + " must not be blank");
        }
        if (!dictionary.containsKey(value)) {
            if (dictionary.size() == Integer.MAX_VALUE) {
                throw new IOException("Compact topology dictionary is too large");
            }
            dictionary.put(value, dictionary.size());
        }
    }

    static void writeLinks(Plan plan, UnifiedGraph graph, OutputStream output) throws IOException {
        DataOutputStream out = new DataOutputStream(output);
        out.writeInt(MAGIC);
        out.writeInt(CODEC_VERSION);
        out.writeInt(0);
        out.writeInt(plan.nodeOrdinals().size());
        out.writeInt(plan.typeOrdinals().size());
        out.writeInt(plan.linkCount());
        for (String id : plan.nodeOrdinals().keySet()) writeString(out, id, false);
        for (String type : plan.typeOrdinals().keySet()) writeString(out, type, true);
        int count = 0;
        for (GraphRelation relation : graph.relations()) {
            Integer source = plan.nodeOrdinals().get(relation.sourceId());
            Integer target = plan.nodeOrdinals().get(relation.targetId());
            Integer type = plan.typeOrdinals().get(relation.type() == null ? "" : relation.type());
            if (source == null || target == null || type == null) {
                throw new IOException("Compact topology plan does not cover relation " + relation.id());
            }
            int flags = relation.directed() ? FLAG_DIRECTED : 0;
            if (Double.doubleToRawLongBits(relation.weight()) != Double.doubleToRawLongBits(1.0)) {
                flags |= FLAG_WEIGHT;
            }
            if (Double.doubleToRawLongBits(relation.confidence()) != Double.doubleToRawLongBits(1.0)) {
                flags |= FLAG_CONFIDENCE;
            }
            if (hasProperties(graph, relation)) flags |= FLAG_PROPERTIES;
            out.writeInt(source);
            out.writeInt(target);
            out.writeInt(type);
            out.writeByte(flags);
            writeString(out, relation.id(), false);
            if ((flags & FLAG_WEIGHT) != 0) out.writeLong(Double.doubleToRawLongBits(relation.weight()));
            if ((flags & FLAG_CONFIDENCE) != 0) {
                out.writeLong(Double.doubleToRawLongBits(relation.confidence()));
            }
            count++;
        }
        if (count != plan.linkCount()) throw new IOException("Compact topology changed while being written");
        out.flush();
    }

    static void writeProperties(UnifiedGraph graph, OutputStream output) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        int index = 0;
        for (GraphRelation relation : graph.relations()) {
            Opinion opinion = graph.relationOpinion(relation.id());
            if (hasProperties(relation, opinion)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("link", index);
                if (!relation.tags().isEmpty()) row.put("tags", new ArrayList<>(relation.tags()));
                if (relation.timestamp() != null) row.put("timestamp", relation.timestamp().toString());
                if (!relation.attributes().isEmpty()) row.put("attributes", relation.attributes());
                if (opinion != null) row.put("opinion", opinionMap(opinion));
                writer.write(MiniJson.write(row));
                writer.write('\n');
            }
            index++;
        }
        writer.flush();
    }

    record OutputLink(
            String id,
            String sourceId,
            String targetId,
            String type,
            double weight,
            double confidence,
            boolean directed,
            Set<String> tags,
            Instant timestamp,
            Map<String, Object> attributes,
            Opinion opinion) {
        OutputLink {
            tags = tags == null ? Set.of() : Set.copyOf(tags);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
            type = type == null ? "" : type;
        }

        boolean hasProperties() {
            return !tags.isEmpty() || timestamp != null || !attributes.isEmpty() || opinion != null;
        }
    }

    @FunctionalInterface
    interface OutputLinkConsumer { void accept(OutputLink link) throws IOException; }

    @FunctionalInterface
    interface OutputLinkPass { void forEach(OutputLinkConsumer consumer) throws IOException; }

    static void writeLinks(Plan plan, OutputLinkPass pass, OutputStream output) throws IOException {
        DataOutputStream out = new DataOutputStream(output);
        out.writeInt(MAGIC);
        out.writeInt(CODEC_VERSION);
        out.writeInt(0);
        out.writeInt(plan.nodeOrdinals().size());
        out.writeInt(plan.typeOrdinals().size());
        out.writeInt(plan.linkCount());
        for (String id : plan.nodeOrdinals().keySet()) writeString(out, id, false);
        for (String type : plan.typeOrdinals().keySet()) writeString(out, type, true);
        int[] count = {0};
        pass.forEach(link -> {
            Integer source = plan.nodeOrdinals().get(link.sourceId());
            Integer target = plan.nodeOrdinals().get(link.targetId());
            Integer type = plan.typeOrdinals().get(link.type());
            if (link.id() == null || link.id().isBlank() || source == null || target == null || type == null) {
                throw new IOException("Compact topology plan does not cover streamed relation " + link.id());
            }
            int flags = link.directed() ? FLAG_DIRECTED : 0;
            if (Double.doubleToRawLongBits(link.weight()) != Double.doubleToRawLongBits(1.0)) {
                flags |= FLAG_WEIGHT;
            }
            if (Double.doubleToRawLongBits(link.confidence()) != Double.doubleToRawLongBits(1.0)) {
                flags |= FLAG_CONFIDENCE;
            }
            if (link.hasProperties()) flags |= FLAG_PROPERTIES;
            out.writeInt(source);
            out.writeInt(target);
            out.writeInt(type);
            out.writeByte(flags);
            writeString(out, link.id(), false);
            if ((flags & FLAG_WEIGHT) != 0) out.writeLong(Double.doubleToRawLongBits(link.weight()));
            if ((flags & FLAG_CONFIDENCE) != 0) {
                out.writeLong(Double.doubleToRawLongBits(link.confidence()));
            }
            count[0]++;
        });
        if (count[0] != plan.linkCount()) throw new IOException("Streamed compact topology count changed");
        out.flush();
    }

    static void writeProperties(OutputLinkPass pass, OutputStream output) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        int[] index = {0};
        pass.forEach(link -> {
            if (link.hasProperties()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("link", index[0]);
                if (!link.tags().isEmpty()) row.put("tags", new ArrayList<>(link.tags()));
                if (link.timestamp() != null) row.put("timestamp", link.timestamp().toString());
                if (!link.attributes().isEmpty()) row.put("attributes", link.attributes());
                if (link.opinion() != null) row.put("opinion", opinionMap(link.opinion()));
                writer.write(MiniJson.write(row));
                writer.write('\n');
            }
            index[0]++;
        });
        writer.flush();
    }

    static Cursor openCursor(
            InputStream input, int expectedNodes, int expectedTypes, int expectedLinks, int maxStringBytes)
            throws IOException {
        return new Cursor(input, expectedNodes, expectedTypes, expectedLinks, maxStringBytes);
    }

    static RecordCursor openRecordCursor(
            InputStream links,
            InputStream properties,
            int expectedNodes,
            int expectedTypes,
            int expectedLinks,
            int maxStringBytes,
            int maxPropertyRowChars) throws IOException {
        Cursor cursor = openCursor(links, expectedNodes, expectedTypes, expectedLinks, maxStringBytes);
        try {
            return new RecordCursor(cursor, new PropertyCursor(properties, maxPropertyRowChars));
        } catch (RuntimeException failure) {
            cursor.close();
            throw failure;
        }
    }

    static void readMaterialized(
            InputStream links,
            InputStream properties,
            int expectedNodes,
            int expectedTypes,
            int expectedLinks,
            int maxStringBytes,
            int maxPropertyRowChars,
            LinkConsumer consumer) throws IOException {
        try (RecordCursor cursor = openRecordCursor(
                links, properties, expectedNodes, expectedTypes, expectedLinks,
                maxStringBytes, maxPropertyRowChars)) {
            LinkRecord link;
            while ((link = cursor.next()) != null) consumer.accept(link);
        }
    }

    static final class RecordCursor implements AutoCloseable {
        private final Cursor links;
        private final PropertyCursor properties;
        private boolean finished;

        private RecordCursor(Cursor links, PropertyCursor properties) {
            this.links = links;
            this.properties = properties;
        }

        LinkRecord next() throws IOException {
            CoreLink core = links.next();
            if (core == null) {
                if (!finished) {
                    finished = true;
                    if (properties.next() != null) {
                        throw new IOException("Compact topology has unexpected trailing property rows");
                    }
                }
                return null;
            }
            PropertyData property = PropertyData.EMPTY;
            if (core.hasProperties()) {
                Map<String, Object> row = properties.next();
                double propertyIndex;
                try {
                    propertyIndex = row == null ? -1 : number(row.get("link"), -1);
                } catch (RuntimeException malformed) {
                    throw new IOException("Invalid compact topology property link index", malformed);
                }
                if (row == null || propertyIndex != core.index()) {
                    throw new IOException("Compact topology property row is missing or out of order at link "
                            + core.index());
                }
                property = property(row);
            }
            return new LinkRecord(core, property);
        }

        int position() { return links.position(); }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                properties.close();
            } catch (IOException error) {
                failure = error;
            }
            try {
                links.close();
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }

    static final class Cursor implements AutoCloseable {
        private final DataInputStream in;
        private final List<String> nodes;
        private final List<String> types;
        private final int linkCount;
        private final int maxStringBytes;
        private final DiskStringSet relationIds;
        private int position;
        private boolean endValidated;

        Cursor(InputStream input, int expectedNodes, int expectedTypes, int expectedLinks, int maxStringBytes)
                throws IOException {
            this.in = new DataInputStream(input);
            this.maxStringBytes = Math.max(1, maxStringBytes);
            int magic = in.readInt();
            int version = in.readInt();
            int flags = in.readInt();
            int nodeCount = in.readInt();
            int typeCount = in.readInt();
            this.linkCount = in.readInt();
            if (magic != MAGIC || version != CODEC_VERSION || flags != 0) {
                throw new IOException("Invalid compact topology header");
            }
            if (nodeCount < 0 || typeCount < 0 || linkCount < 0
                    || nodeCount != expectedNodes || typeCount != expectedTypes
                    || linkCount != expectedLinks) {
                throw new IOException("Compact topology header count mismatch");
            }
            this.nodes = readDictionary(in, nodeCount, this.maxStringBytes, false, "node");
            this.types = readDictionary(in, typeCount, this.maxStringBytes, true, "relation type");
            this.relationIds = new DiskStringSet(linkCount);
        }

        CoreLink next() throws IOException {
            if (position >= linkCount) {
                validateEnd();
                return null;
            }
            int source = in.readInt();
            int target = in.readInt();
            int type = in.readInt();
            int flags = in.readUnsignedByte();
            if (source < 0 || source >= nodes.size() || target < 0 || target >= nodes.size()
                    || type < 0 || type >= types.size()) {
                throw new IOException("Compact topology link ordinal is out of range at " + position);
            }
            if ((flags & ~SUPPORTED_FLAGS) != 0) {
                throw new IOException("Compact topology link uses unsupported flags at " + position);
            }
            String id = readString(in, maxStringBytes, false, "relation id");
            if (!relationIds.add(id)) {
                throw new IOException("Duplicate relation id in compact topology: " + id);
            }
            double weight = (flags & FLAG_WEIGHT) == 0
                    ? 1.0 : Double.longBitsToDouble(in.readLong());
            double confidence = (flags & FLAG_CONFIDENCE) == 0
                    ? 1.0 : Double.longBitsToDouble(in.readLong());
            CoreLink result = new CoreLink(position, id, nodes.get(source), nodes.get(target),
                    types.get(type), weight, confidence, (flags & FLAG_DIRECTED) != 0,
                    (flags & FLAG_PROPERTIES) != 0);
            position++;
            return result;
        }

        int position() { return position; }
        int linkCount() { return linkCount; }

        private void validateEnd() throws IOException {
            if (!endValidated) {
                endValidated = true;
                if (in.read() != -1) throw new IOException("Compact topology contains trailing bytes");
            }
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                relationIds.close();
            } catch (IOException error) {
                failure = error;
            }
            try {
                in.close();
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }

    record CoreLink(
            int index, String id, String sourceId, String targetId, String type,
            double weight, double confidence, boolean directed, boolean hasProperties) { }

    record LinkRecord(CoreLink core, PropertyData property) { }

    record PropertyData(Set<String> tags, Instant timestamp, Map<String, Object> attributes, Opinion opinion) {
        static final PropertyData EMPTY = new PropertyData(Set.of(), null, Map.of(), null);
    }

    record Plan(
            LinkedHashMap<String, Integer> nodeOrdinals,
            LinkedHashMap<String, Integer> typeOrdinals,
            int linkCount,
            boolean hasProperties) { }

    @FunctionalInterface
    interface LinkConsumer { void accept(LinkRecord link) throws IOException; }

    private static boolean hasProperties(UnifiedGraph graph, GraphRelation relation) {
        return hasProperties(relation, graph.relationOpinion(relation.id()));
    }

    private static boolean hasProperties(GraphRelation relation, Opinion opinion) {
        return !relation.tags().isEmpty() || relation.timestamp() != null
                || !relation.attributes().isEmpty() || opinion != null;
    }

    private static List<String> readDictionary(
            DataInputStream in, int count, int maxStringBytes, boolean allowEmpty, String field)
            throws IOException {
        List<String> values = new ArrayList<>(count);
        Set<String> unique = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            String value = readString(in, maxStringBytes, allowEmpty, field);
            if (!unique.add(value)) throw new IOException("Duplicate compact topology " + field);
            values.add(value);
        }
        return values;
    }

    private static void writeString(DataOutputStream out, String value, boolean allowEmpty) throws IOException {
        if (value == null || (!allowEmpty && value.isBlank())) {
            throw new IOException("Compact topology string must not be blank");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(
            DataInputStream in, int maxBytes, boolean allowEmpty, String field) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > maxBytes || (!allowEmpty && length == 0)) {
            throw new IOException("Invalid compact topology " + field + " byte length: " + length);
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new IOException("Truncated compact topology " + field);
        try {
            String value = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (!allowEmpty && value.isBlank()) throw new IOException("Compact topology " + field + " is blank");
            return value;
        } catch (CharacterCodingException malformed) {
            throw new IOException("Invalid UTF-8 in compact topology " + field, malformed);
        }
    }

    private static PropertyData property(Map<String, Object> row) throws IOException {
        Set<String> tags = new LinkedHashSet<>();
        Object rawTags = row.get("tags");
        if (rawTags instanceof Iterable<?> iterable) {
            for (Object tag : iterable) if (tag != null) tags.add(String.valueOf(tag));
        }
        Instant timestamp = null;
        Object rawTimestamp = row.get("timestamp");
        if (rawTimestamp != null) {
            try {
                timestamp = Instant.parse(String.valueOf(rawTimestamp));
            } catch (DateTimeParseException malformed) {
                throw new IOException("Invalid compact topology property timestamp", malformed);
            }
        }
        Map<String, Object> attributes = Map.of();
        if (row.containsKey("attributes") && !(row.get("attributes") instanceof Map<?, ?>)) {
            throw new IOException("Compact topology attributes must be an object");
        }
        if (row.get("attributes") instanceof Map<?, ?> raw) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    throw new IOException("Compact topology attributes must not contain null keys or values");
                }
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            attributes = copy;
        }
        return new PropertyData(tags, timestamp, attributes, opinion(row.get("opinion")));
    }

    private static Opinion opinion(Object raw) throws IOException {
        if (!(raw instanceof Map<?, ?> map)) return null;
        try {
            return new Opinion(number(map.get("b"), 0.0), number(map.get("d"), 0.0),
                    number(map.get("u"), 1.0), number(map.get("a"), 0.5));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid compact topology relation opinion", invalid);
        }
    }

    private static double number(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String string) {
            return switch (string) {
                case "NaN" -> Double.NaN;
                case "Infinity" -> Double.POSITIVE_INFINITY;
                case "-Infinity" -> Double.NEGATIVE_INFINITY;
                default -> Double.parseDouble(string);
            };
        }
        return fallback;
    }

    private static Map<String, Object> opinionMap(Opinion opinion) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("b", opinion.belief());
        value.put("d", opinion.disbelief());
        value.put("u", opinion.uncertainty());
        value.put("a", opinion.baseRate());
        return value;
    }

    private static final class PropertyCursor implements AutoCloseable {
        private final BufferedReader reader;
        private final int maxRowChars;
        private long lineNumber;

        PropertyCursor(InputStream input, int maxRowChars) {
            this.reader = input == null ? null
                    : new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            this.maxRowChars = Math.max(1, maxRowChars);
        }

        Map<String, Object> next() throws IOException {
            if (reader == null) return null;
            StringBuilder row = new StringBuilder(256);
            int value;
            while ((value = reader.read()) != -1) {
                if (value == '\n') {
                    lineNumber++;
                    if (!row.isEmpty() && !row.toString().isBlank()) return parse(row);
                    row.setLength(0);
                    continue;
                }
                if (row.length() >= maxRowChars) {
                    throw new IOException("Compact topology property row exceeds character limit at line "
                            + (lineNumber + 1));
                }
                row.append((char) value);
            }
            if (!row.isEmpty() && !row.toString().isBlank()) {
                lineNumber++;
                return parse(row);
            }
            return null;
        }

        private Map<String, Object> parse(StringBuilder row) throws IOException {
            try {
                return MiniJson.parseObject(row.toString());
            } catch (IllegalArgumentException malformed) {
                throw new IOException("Invalid compact topology property JSON at line " + lineNumber, malformed);
            }
        }

        @Override public void close() throws IOException { if (reader != null) reader.close(); }
    }

    /** Exact disk-backed relation-id set; keeps cursor heap independent of link count. */
    private static final class DiskStringSet implements AutoCloseable {
        private static final int SLOT_BYTES = 16;
        private final Path indexPath;
        private final Path valuesPath;
        private final RandomAccessFile index;
        private final RandomAccessFile values;
        private final int slotMask;

        DiskStringSet(int expectedSize) throws IOException {
            int desired = Math.max(2, expectedSize <= Integer.MAX_VALUE / 2
                    ? expectedSize * 2 : Integer.MAX_VALUE);
            int slots = 1;
            while (slots < desired && slots < (1 << 27)) slots <<= 1;
            if (expectedSize > (long) slots * 3L / 4L) {
                throw new IOException("Compact topology relation-id index exceeds supported size");
            }
            Path createdIndex = null;
            Path createdValues = null;
            try {
                createdIndex = Files.createTempFile("kompile-kgraph-link-ids-", ".idx");
                createdValues = Files.createTempFile("kompile-kgraph-link-ids-", ".dat");
            } catch (IOException failure) {
                if (createdIndex != null) Files.deleteIfExists(createdIndex);
                if (createdValues != null) Files.deleteIfExists(createdValues);
                throw failure;
            }
            indexPath = createdIndex;
            valuesPath = createdValues;
            RandomAccessFile openedIndex = null;
            RandomAccessFile openedValues = null;
            try {
                openedIndex = new RandomAccessFile(indexPath.toFile(), "rw");
                openedValues = new RandomAccessFile(valuesPath.toFile(), "rw");
                openedIndex.setLength((long) slots * SLOT_BYTES);
            } catch (IOException failure) {
                if (openedIndex != null) openedIndex.close();
                if (openedValues != null) openedValues.close();
                Files.deleteIfExists(indexPath);
                Files.deleteIfExists(valuesPath);
                throw failure;
            }
            index = openedIndex;
            values = openedValues;
            slotMask = slots - 1;
        }

        boolean add(String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            long hash = hash(bytes);
            int slot = (int) hash & slotMask;
            for (int probe = 0; probe <= slotMask; probe++) {
                long position = (long) slot * SLOT_BYTES;
                index.seek(position);
                long storedHash = index.readLong();
                long storedOffset = index.readLong();
                if (storedOffset == 0) {
                    long offset = values.length();
                    values.seek(offset);
                    values.writeInt(bytes.length);
                    values.write(bytes);
                    index.seek(position);
                    index.writeLong(hash);
                    index.writeLong(offset + 1);
                    return true;
                }
                if (storedHash == hash && equalsAt(storedOffset - 1, bytes)) return false;
                slot = (slot + 1) & slotMask;
            }
            throw new IOException("Compact topology relation-id index is full");
        }

        private boolean equalsAt(long offset, byte[] expected) throws IOException {
            values.seek(offset);
            int length = values.readInt();
            if (length != expected.length) return false;
            byte[] actual = new byte[length];
            values.readFully(actual);
            return java.util.Arrays.equals(actual, expected);
        }

        private static long hash(byte[] bytes) {
            long hash = 0xcbf29ce484222325L;
            for (byte value : bytes) {
                hash ^= value & 0xffL;
                hash *= 0x100000001b3L;
            }
            return hash == 0 ? 1 : hash;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try { index.close(); } catch (IOException error) { failure = error; }
            try { values.close(); } catch (IOException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
            try { Files.deleteIfExists(indexPath); } catch (IOException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
            try { Files.deleteIfExists(valuesPath); } catch (IOException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }
}
