/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Mmap-friendly fixed-width link table plus outgoing/incoming CSR rows for compact KGraphs. */
final class CompactAdjacencyCodec {

    static final int MAGIC = 0x4b474149; // KGAI
    static final int VERSION = 1;
    private static final int HEADER_BYTES = 36;
    private static final int CORE_BYTES = 32;
    private static final int MAX_STRING_BYTES = Math.max(1,
            Integer.getInteger("kompile.graph.maxTopologyStringBytes", 16 * 1024 * 1024));

    private CompactAdjacencyCodec() { }

    record RawLink(
            int index, String id, int source, int target, int type,
            double weight, double confidence, boolean directed, boolean hasProperties) { }

    @FunctionalInterface
    interface LinkConsumer { void accept(RawLink link) throws IOException; }

    @FunctionalInterface
    interface LinkPass { void forEach(LinkConsumer consumer) throws IOException; }

    @FunctionalInterface
    interface AdjacentConsumer { void accept(int ordinal) throws IOException; }

    @FunctionalInterface
    interface CoreConsumer {
        void accept(int ordinal, int source, int target, int type,
                    double weight, double confidence, boolean directed) throws IOException;
    }

    record Plan(
            LinkedHashMap<String, Integer> nodes,
            LinkedHashMap<String, Integer> types,
            int links,
            int adjacencyEntries,
            long relationIdBytes,
            long[] outgoingRows,
            long[] incomingRows) { }

    static Plan plan(UnifiedGraph graph, CompactTopologyCodec.Plan topology) throws IOException {
        return plan(topology.nodeOrdinals(), topology.typeOrdinals(), topology.linkCount(),
                graphPass(graph, topology));
    }

    static LinkPass graphPass(UnifiedGraph graph, CompactTopologyCodec.Plan topology) {
        return consumer -> {
            int index = 0;
            for (GraphRelation relation : graph.relations()) {
                Integer source = topology.nodeOrdinals().get(relation.sourceId());
                Integer target = topology.nodeOrdinals().get(relation.targetId());
                Integer type = topology.typeOrdinals().get(
                        relation.type() == null ? "" : relation.type());
                if (source == null || target == null || type == null) {
                    throw new IOException("Compact adjacency plan does not cover relation " + relation.id());
                }
                consumer.accept(new RawLink(index++, relation.id(), source, target, type,
                        relation.weight(), relation.confidence(), relation.directed(),
                        !relation.tags().isEmpty() || relation.timestamp() != null
                                || !relation.attributes().isEmpty()
                                || graph.relationOpinion(relation.id()) != null));
            }
        };
    }

    static Plan plan(
            LinkedHashMap<String, Integer> nodes,
            LinkedHashMap<String, Integer> types,
            int links,
            LinkPass pass) throws IOException {
        long[] outDegrees = new long[nodes.size()];
        long[] inDegrees = new long[nodes.size()];
        long[] idBytes = {0L};
        int[] count = {0};
        pass.forEach(link -> {
            validateLink(link, nodes.size(), types.size(), links, count[0]);
            int bytes = link.id().getBytes(StandardCharsets.UTF_8).length;
            if (bytes < 1 || bytes > MAX_STRING_BYTES
                    || idBytes[0] > Long.MAX_VALUE - bytes) {
                throw new IOException("Compact adjacency relation id is invalid or too large");
            }
            idBytes[0] += bytes;
            outDegrees[link.source()]++;
            inDegrees[link.target()]++;
            if (!link.directed() && link.source() != link.target()) {
                outDegrees[link.target()]++;
                inDegrees[link.source()]++;
            }
            count[0]++;
        });
        if (count[0] != links) throw new IOException("Compact adjacency link count changed");
        long[] outRows = prefix(outDegrees);
        long[] inRows = prefix(inDegrees);
        long adjacencyEntries = outRows[outRows.length - 1];
        if (adjacencyEntries != inRows[inRows.length - 1]
                || adjacencyEntries > Integer.MAX_VALUE) {
            throw new IOException("Compact adjacency index is too large for this codec version");
        }
        return new Plan(new LinkedHashMap<>(nodes), new LinkedHashMap<>(types), links,
                (int) adjacencyEntries, idBytes[0], outRows, inRows);
    }

    static void write(Plan plan, LinkPass pass, OutputStream output) throws IOException {
        Path ids = Files.createTempFile("kompile-kgraph-adjacency-ids-", ".bin");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(output))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(plan.nodes().size());
            out.writeInt(plan.types().size());
            out.writeInt(plan.links());
            out.writeLong(plan.adjacencyEntries());
            out.writeLong(plan.relationIdBytes());
            for (String node : plan.nodes().keySet()) writeString(out, node, false);
            for (String type : plan.types().keySet()) writeString(out, type, true);

            long[] idOffset = {0L};
            int[] count = {0};
            try (OutputStream idOut = new BufferedOutputStream(Files.newOutputStream(ids))) {
                pass.forEach(link -> {
                    validateLink(link, plan.nodes().size(), plan.types().size(), plan.links(), count[0]);
                    byte[] bytes = link.id().getBytes(StandardCharsets.UTF_8);
                    out.writeLong(idOffset[0]);
                    idOut.write(bytes);
                    idOffset[0] += bytes.length;
                    count[0]++;
                });
            }
            out.writeLong(idOffset[0]);
            if (count[0] != plan.links() || idOffset[0] != plan.relationIdBytes()) {
                throw new IOException("Compact adjacency relation-id pass changed");
            }
            Files.copy(ids, out);

            count[0] = 0;
            pass.forEach(link -> {
                validateLink(link, plan.nodes().size(), plan.types().size(), plan.links(), count[0]);
                out.writeInt(link.source());
                out.writeInt(link.target());
                out.writeInt(link.type());
                int flags = link.directed() ? CompactTopologyCodec.FLAG_DIRECTED : 0;
                if (link.hasProperties()) flags |= CompactTopologyCodec.FLAG_PROPERTIES;
                out.writeInt(flags);
                out.writeLong(Double.doubleToRawLongBits(link.weight()));
                out.writeLong(Double.doubleToRawLongBits(link.confidence()));
                count[0]++;
            });
            if (count[0] != plan.links()) throw new IOException("Compact adjacency core pass changed");

            int[] outgoing = new int[plan.adjacencyEntries()];
            int[] incoming = new int[plan.adjacencyEntries()];
            long[] outPosition = plan.outgoingRows().clone();
            long[] inPosition = plan.incomingRows().clone();
            count[0] = 0;
            pass.forEach(link -> {
                validateLink(link, plan.nodes().size(), plan.types().size(), plan.links(), count[0]);
                outgoing[index(outPosition[link.source()]++)] = link.index();
                incoming[index(inPosition[link.target()]++)] = link.index();
                if (!link.directed() && link.source() != link.target()) {
                    outgoing[index(outPosition[link.target()]++)] = link.index();
                    incoming[index(inPosition[link.source()]++)] = link.index();
                }
                count[0]++;
            });
            if (count[0] != plan.links()) throw new IOException("Compact adjacency CSR pass changed");
            for (long value : plan.outgoingRows()) out.writeLong(value);
            for (int value : outgoing) out.writeInt(value);
            for (long value : plan.incomingRows()) out.writeLong(value);
            for (int value : incoming) out.writeInt(value);
        } finally {
            Files.deleteIfExists(ids);
        }
    }

    static Index open(Path path, int expectedNodes, int expectedTypes, int expectedLinks)
            throws IOException {
        return new Index(path, expectedNodes, expectedTypes, expectedLinks);
    }

    static final class Index implements AutoCloseable {
        private final Path path;
        private final RandomAccessFile file;
        private final FileChannel channel;
        private final List<String> nodes;
        private final List<String> types;
        private final Map<String, Integer> nodeOrdinals;
        private final int links;
        private final int adjacencyEntries;
        private final ByteBuffer idOffsets;
        private final ByteBuffer idBytes;
        private final ByteBuffer cores;
        private final ByteBuffer outgoingRows;
        private final ByteBuffer outgoingOrdinals;
        private final ByteBuffer incomingRows;
        private final ByteBuffer incomingOrdinals;

        private Index(Path path, int expectedNodes, int expectedTypes, int expectedLinks)
                throws IOException {
            this.path = path;
            this.file = new RandomAccessFile(path.toFile(), "r");
            this.channel = file.getChannel();
            int magic = file.readInt();
            int version = file.readInt();
            int nodeCount = file.readInt();
            int typeCount = file.readInt();
            this.links = file.readInt();
            long adjacencyCount = file.readLong();
            long relationIdBytes = file.readLong();
            if (magic != MAGIC || version != VERSION || nodeCount != expectedNodes
                    || typeCount != expectedTypes || links != expectedLinks
                    || adjacencyCount < 0 || adjacencyCount > Integer.MAX_VALUE
                    || relationIdBytes < 0 || relationIdBytes > Integer.MAX_VALUE) {
                throw new IOException("Invalid compact adjacency header");
            }
            this.adjacencyEntries = (int) adjacencyCount;
            this.nodes = readDictionary(file, nodeCount, false);
            this.types = readDictionary(file, typeCount, true);
            this.nodeOrdinals = new LinkedHashMap<>();
            for (int i = 0; i < nodes.size(); i++) nodeOrdinals.put(nodes.get(i), i);

            long idOffsetsAt = file.getFilePointer();
            long idOffsetsBytes = multiply(links + 1L, Long.BYTES);
            long idsAt = add(idOffsetsAt, idOffsetsBytes);
            long coresAt = add(idsAt, relationIdBytes);
            long coreBytes = multiply(links, CORE_BYTES);
            long outRowsAt = add(coresAt, coreBytes);
            long rowsBytes = multiply(nodeCount + 1L, Long.BYTES);
            long outOrdinalsAt = add(outRowsAt, rowsBytes);
            long ordinalsBytes = multiply(adjacencyCount, Integer.BYTES);
            long inRowsAt = add(outOrdinalsAt, ordinalsBytes);
            long inOrdinalsAt = add(inRowsAt, rowsBytes);
            long expectedSize = add(inOrdinalsAt, ordinalsBytes);
            if (expectedSize != file.length()) throw new IOException("Compact adjacency size mismatch");

            this.idOffsets = map(idOffsetsAt, idOffsetsBytes);
            this.idBytes = map(idsAt, relationIdBytes);
            this.cores = map(coresAt, coreBytes);
            this.outgoingRows = map(outRowsAt, rowsBytes);
            this.outgoingOrdinals = map(outOrdinalsAt, ordinalsBytes);
            this.incomingRows = map(inRowsAt, rowsBytes);
            this.incomingOrdinals = map(inOrdinalsAt, ordinalsBytes);
            validateRows(outgoingRows, outgoingOrdinals, nodeCount, adjacencyEntries);
            validateRows(incomingRows, incomingOrdinals, nodeCount, adjacencyEntries);
        }

        int nodeCount() { return nodes.size(); }
        int linkCount() { return links; }
        Integer nodeOrdinal(String id) { return nodeOrdinals.get(id); }
        Map<String, Integer> nodeOrdinalsView() { return Collections.unmodifiableMap(nodeOrdinals); }

        void forEachCore(CoreConsumer consumer) throws IOException {
            for (int ordinal = 0; ordinal < links; ordinal++) {
                int base = ordinal * CORE_BYTES;
                int source = cores.getInt(base);
                int target = cores.getInt(base + 4);
                int type = cores.getInt(base + 8);
                int flags = cores.getInt(base + 12);
                if (source < 0 || source >= nodes.size() || target < 0 || target >= nodes.size()
                        || type < 0 || type >= types.size()
                        || (flags & ~(CompactTopologyCodec.FLAG_DIRECTED
                        | CompactTopologyCodec.FLAG_PROPERTIES)) != 0) {
                    throw new IOException("Invalid compact adjacency core record");
                }
                consumer.accept(ordinal, source, target, type,
                        Double.longBitsToDouble(cores.getLong(base + 16)),
                        Double.longBitsToDouble(cores.getLong(base + 24)),
                        (flags & CompactTopologyCodec.FLAG_DIRECTED) != 0);
            }
        }

        RawLink link(int ordinal) throws IOException {
            if (ordinal < 0 || ordinal >= links) throw new IOException("Link ordinal out of range");
            int base = ordinal * CORE_BYTES;
            int source = cores.getInt(base);
            int target = cores.getInt(base + 4);
            int type = cores.getInt(base + 8);
            int flags = cores.getInt(base + 12);
            if (source < 0 || source >= nodes.size() || target < 0 || target >= nodes.size()
                    || type < 0 || type >= types.size()
                    || (flags & ~(CompactTopologyCodec.FLAG_DIRECTED
                    | CompactTopologyCodec.FLAG_PROPERTIES)) != 0) {
                throw new IOException("Invalid compact adjacency core record");
            }
            long idStart = idOffsets.getLong(ordinal * Long.BYTES);
            long idEnd = idOffsets.getLong((ordinal + 1) * Long.BYTES);
            if (idStart < 0 || idEnd <= idStart || idEnd > idBytes.capacity()) {
                throw new IOException("Invalid compact adjacency relation-id bounds");
            }
            byte[] bytes = new byte[(int) (idEnd - idStart)];
            ByteBuffer duplicate = idBytes.duplicate();
            duplicate.position((int) idStart).limit((int) idEnd);
            duplicate.get(bytes);
            String id;
            try {
                id = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (Exception malformed) {
                throw new IOException("Invalid compact adjacency relation-id UTF-8", malformed);
            }
            return new RawLink(ordinal, id, source, target, type,
                    Double.longBitsToDouble(cores.getLong(base + 16)),
                    Double.longBitsToDouble(cores.getLong(base + 24)),
                    (flags & CompactTopologyCodec.FLAG_DIRECTED) != 0,
                    (flags & CompactTopologyCodec.FLAG_PROPERTIES) != 0);
        }

        void forEachAdjacent(
                String nodeId, GraphQueryEngine.Direction direction, AdjacentConsumer consumer)
                throws IOException {
            Integer node = nodeOrdinals.get(nodeId);
            if (node == null) return;
            GraphQueryEngine.Direction effective = direction == null
                    ? GraphQueryEngine.Direction.BOTH : direction;
            if (effective != GraphQueryEngine.Direction.INCOMING) {
                emitRow(outgoingRows, outgoingOrdinals, node, consumer);
            }
            if (effective != GraphQueryEngine.Direction.OUTGOING) {
                emitRow(incomingRows, incomingOrdinals, node, consumer);
            }
        }

        String nodeId(int ordinal) throws IOException {
            if (ordinal < 0 || ordinal >= nodes.size()) throw new IOException("Node ordinal out of range");
            return nodes.get(ordinal);
        }

        String type(int ordinal) throws IOException {
            if (ordinal < 0 || ordinal >= types.size()) throw new IOException("Type ordinal out of range");
            return types.get(ordinal);
        }

        private void emitRow(
                ByteBuffer rows, ByteBuffer ordinals, int node, AdjacentConsumer consumer)
                throws IOException {
            long start = rows.getLong(node * Long.BYTES);
            long end = rows.getLong((node + 1) * Long.BYTES);
            if (start < 0 || end < start || end > adjacencyEntries) {
                throw new IOException("Invalid compact adjacency row bounds");
            }
            for (long i = start; i < end; i++) {
                int ordinal = ordinals.getInt(index(i) * Integer.BYTES);
                if (ordinal < 0 || ordinal >= links) throw new IOException("Invalid adjacency link ordinal");
                consumer.accept(ordinal);
            }
        }

        private ByteBuffer map(long offset, long length) throws IOException {
            if (length == 0) return ByteBuffer.allocate(0).order(ByteOrder.BIG_ENDIAN);
            if (length > Integer.MAX_VALUE) throw new IOException("Compact adjacency section exceeds mmap limit");
            return channel.map(FileChannel.MapMode.READ_ONLY, offset, length).order(ByteOrder.BIG_ENDIAN);
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try { channel.close(); } catch (IOException error) { failure = error; }
            try { file.close(); } catch (IOException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
            try { Files.deleteIfExists(path); } catch (IOException error) {
                if (failure == null) failure = error; else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }

    private static long[] prefix(long[] degrees) throws IOException {
        long[] rows = new long[degrees.length + 1];
        for (int i = 0; i < degrees.length; i++) {
            if (degrees[i] < 0 || rows[i] > Long.MAX_VALUE - degrees[i]) {
                throw new IOException("Compact adjacency degree overflow");
            }
            rows[i + 1] = rows[i] + degrees[i];
        }
        return rows;
    }

    private static void validateLink(
            RawLink link, int nodes, int types, int links, int expectedIndex) throws IOException {
        if (link == null || link.index() != expectedIndex || link.index() < 0 || link.index() >= links
                || link.id() == null || link.id().isBlank()
                || link.source() < 0 || link.source() >= nodes
                || link.target() < 0 || link.target() >= nodes
                || link.type() < 0 || link.type() >= types) {
            throw new IOException("Invalid compact adjacency link at ordinal " + expectedIndex);
        }
    }

    private static void validateRows(
            ByteBuffer rows, ByteBuffer ordinals, int nodes, int entries) throws IOException {
        long previous = 0;
        for (int i = 0; i <= nodes; i++) {
            long value = rows.getLong(i * Long.BYTES);
            if (value < previous || value > entries) throw new IOException("Invalid CSR row pointer");
            previous = value;
        }
        if (previous != entries || ordinals.capacity() != entries * Integer.BYTES) {
            throw new IOException("Compact adjacency CSR count mismatch");
        }
    }

    private static List<String> readDictionary(RandomAccessFile file, int count, boolean allowEmpty)
            throws IOException {
        List<String> values = new ArrayList<>(count);
        Map<String, Boolean> unique = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            int length = file.readInt();
            if (length < 0 || length > MAX_STRING_BYTES || (!allowEmpty && length == 0)) {
                throw new IOException("Invalid compact adjacency dictionary string length");
            }
            byte[] bytes = new byte[length];
            file.readFully(bytes);
            String value;
            try {
                value = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (Exception malformed) {
                throw new IOException("Invalid compact adjacency dictionary UTF-8", malformed);
            }
            if (!allowEmpty && value.isBlank() || unique.putIfAbsent(value, Boolean.TRUE) != null) {
                throw new IOException("Invalid or duplicate compact adjacency dictionary value");
            }
            values.add(value);
        }
        return values;
    }

    private static void writeString(DataOutputStream out, String value, boolean allowEmpty)
            throws IOException {
        if (value == null || (!allowEmpty && value.isBlank())) {
            throw new IOException("Compact adjacency dictionary string must not be blank");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("Compact adjacency string too large");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static long add(long left, long right) throws IOException {
        if (left < 0 || right < 0 || left > Long.MAX_VALUE - right) {
            throw new IOException("Compact adjacency offset overflow");
        }
        return left + right;
    }

    private static long multiply(long left, long right) throws IOException {
        if (left < 0 || right < 0 || left != 0 && right > Long.MAX_VALUE / left) {
            throw new IOException("Compact adjacency size overflow");
        }
        return left * right;
    }

    private static int index(long value) throws IOException {
        if (value < 0 || value > Integer.MAX_VALUE) throw new IOException("Compact adjacency index overflow");
        return (int) value;
    }
}
