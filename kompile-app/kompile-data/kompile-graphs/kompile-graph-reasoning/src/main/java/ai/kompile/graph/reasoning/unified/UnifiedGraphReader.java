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
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.model.SimpleGraphRelation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Reconstructs a {@link UnifiedGraph} from the single-file form written by {@link UnifiedGraphWriter}.
 * Internal to {@link UnifiedGraph#load(java.nio.file.Path)}.
 *
 * <p>Reading buffers each ZIP entry into memory and then reconstructs the graph — vector layers are
 * read first (self-describing {@link VectorBlobCodec} blobs), so the primary embeddings are attached
 * back onto entities/relations as they are rebuilt. Every aspect written round-trips: topology,
 * scalar/temporal properties, tags, the full {@code attributes} bag, subjective-logic opinions,
 * additional vector layers, named weight maps, and graph-level meta.</p>
 */
final class UnifiedGraphReader {

    private static final Logger log = LoggerFactory.getLogger(UnifiedGraphReader.class);

    private UnifiedGraphReader() { }

    /** Load from a file (uses {@link ZipFile} for efficient per-entry access). */
    static UnifiedGraph read(java.nio.file.Path file) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(file.toFile())) {
            var e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory()) continue;
                try (InputStream in = zf.getInputStream(entry)) {
                    entries.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return reconstruct(entries);
    }

    /** Load from a stream. The stream is read to end but not closed. */
    static UnifiedGraph read(InputStream in) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory()) { zis.closeEntry(); continue; }
            entries.put(entry.getName(), zis.readAllBytes());
            zis.closeEntry();
        }
        return reconstruct(entries);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Reconstruction
    // ═════════════════════════════════════════════════════════════════════════

    private static UnifiedGraph reconstruct(Map<String, byte[]> entries) throws IOException {
        byte[] manifestBytes = entries.get(UnifiedGraphFormat.ENTRY_MANIFEST);
        if (manifestBytes == null) {
            throw new IOException("Not a unified-graph file: missing " + UnifiedGraphFormat.ENTRY_MANIFEST);
        }
        Map<String, Object> manifest = MiniJson.parseObject(new String(manifestBytes, StandardCharsets.UTF_8));
        Object format = manifest.get("format");
        if (!UnifiedGraphFormat.FORMAT.equals(format)) {
            throw new IOException("Unexpected graph file format: " + format);
        }
        long version = asLong(manifest.get("formatVersion"), 0);
        if (version > UnifiedGraphFormat.FORMAT_VERSION) {
            log.warn("Reading unified-graph formatVersion {} with reader version {} — newer sections may be ignored",
                    version, UnifiedGraphFormat.FORMAT_VERSION);
        }

        UnifiedGraph graph = new UnifiedGraph();

        // 1. Vector layers first (needed to attach embeddings while building nodes).
        VectorLayer entityEmbeddings = null;
        VectorLayer relationEmbeddings = null;
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (!name.startsWith(UnifiedGraphFormat.VECTOR_DIR) || !name.endsWith(UnifiedGraphFormat.VECTOR_SUFFIX)) {
                continue;
            }
            VectorLayer layer = readVectorLayer(e.getValue());
            if (UnifiedGraphFormat.PRIMARY_ENTITY_VECTORS.equals(layer.name())) {
                entityEmbeddings = layer;
            } else if (UnifiedGraphFormat.PRIMARY_RELATION_VECTORS.equals(layer.name())) {
                relationEmbeddings = layer;
            } else {
                graph.putVectorLayer(layer);
            }
        }

        // 2. Entities.
        byte[] entityBytes = entries.get(UnifiedGraphFormat.ENTRY_ENTITIES);
        if (entityBytes != null) {
            for (String line : lines(entityBytes)) {
                Map<String, Object> m = MiniJson.parseObject(line);
                String id = str(m.get("id"));
                if (id == null) continue;
                double[] emb = entityEmbeddings == null ? null : entityEmbeddings.get(id);
                SimpleGraphEntity entity = new SimpleGraphEntity(
                        id,
                        str(m.get("type")),
                        str(m.get("label")),
                        asDouble(m.get("weight"), 1.0),
                        asDouble(m.get("confidence"), 1.0),
                        tags(m.get("tags")),
                        emb,
                        timestamp(m.get("timestamp")),
                        attributes(m.get("attributes")));
                graph.addEntity(entity);
                Opinion op = opinion(m.get("opinion"));
                if (op != null) graph.putEntityOpinion(id, op);
            }
        }

        // 3. Relations.
        byte[] relationBytes = entries.get(UnifiedGraphFormat.ENTRY_RELATIONS);
        if (relationBytes != null) {
            for (String line : lines(relationBytes)) {
                Map<String, Object> m = MiniJson.parseObject(line);
                String id = str(m.get("id"));
                String sourceId = str(m.get("sourceId"));
                String targetId = str(m.get("targetId"));
                if (id == null || sourceId == null || targetId == null) continue;
                double[] emb = relationEmbeddings == null ? null : relationEmbeddings.get(id);
                SimpleGraphRelation relation = new SimpleGraphRelation(
                        id,
                        sourceId,
                        targetId,
                        str(m.get("type")),
                        asDouble(m.get("weight"), 1.0),
                        asDouble(m.get("confidence"), 1.0),
                        asBoolean(m.get("directed"), true),
                        tags(m.get("tags")),
                        emb,
                        timestamp(m.get("timestamp")),
                        attributes(m.get("attributes")));
                graph.addRelation(relation);
                Opinion op = opinion(m.get("opinion"));
                if (op != null) graph.putRelationOpinion(id, op);
            }
        }

        // 4. Optional orphan opinions keyed to ids not present in topology rows.
        byte[] opinionBytes = entries.get(UnifiedGraphFormat.ENTRY_OPINIONS);
        if (opinionBytes != null) {
            for (String line : lines(opinionBytes)) {
                Map<String, Object> m = MiniJson.parseObject(line);
                String kind = str(m.get("kind"));
                String id = str(m.get("id"));
                Opinion op = opinion(m.get("opinion"));
                if (id == null || op == null) continue;
                if ("entity".equals(kind)) {
                    graph.putEntityOpinion(id, op);
                } else if ("relation".equals(kind)) {
                    graph.putRelationOpinion(id, op);
                }
            }
        }

        // 5. Named weight maps.
        byte[] weightBytes = entries.get(UnifiedGraphFormat.ENTRY_WEIGHTS);
        if (weightBytes != null) {
            Map<String, Object> weights = MiniJson.parseObject(new String(weightBytes, StandardCharsets.UTF_8));
            for (Map.Entry<String, Object> e : weights.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> inner) {
                    Map<String, Double> map = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> w : inner.entrySet()) {
                        map.put(String.valueOf(w.getKey()), asDouble(w.getValue(), 0.0));
                    }
                    graph.putWeightMap(e.getKey(), map);
                }
            }
        }

        // 6. Graph-level meta.
        Object meta = manifest.get("meta");
        if (meta instanceof Map<?, ?> metaMap) {
            for (Map.Entry<?, ?> e : metaMap.entrySet()) {
                graph.meta(String.valueOf(e.getKey()), e.getValue());
            }
        }

        // 7. Bundled model artifacts (any serialized model attached under models/).
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (name.startsWith(UnifiedGraphFormat.MODELS_DIR) && !name.endsWith("/")) {
                graph.putArtifact(name.substring(UnifiedGraphFormat.MODELS_DIR.length()), e.getValue());
            }
        }

        return graph;
    }

    private static VectorLayer readVectorLayer(byte[] blob) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob))) {
            return VectorBlobCodec.read(in);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Field decoders
    // ═════════════════════════════════════════════════════════════════════════

    private static List<String> lines(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static double asDouble(Object v, double dflt) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            switch (s) {
                case "NaN": return Double.NaN;
                case "Infinity": return Double.POSITIVE_INFINITY;
                case "-Infinity": return Double.NEGATIVE_INFINITY;
                default:
                    try { return Double.parseDouble(s); }
                    catch (NumberFormatException e) { return dflt; }
            }
        }
        return dflt;
    }

    private static long asLong(Object v, long dflt) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return dflt; }
        }
        return dflt;
    }

    private static boolean asBoolean(Object v, boolean dflt) {
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s);
        return dflt;
    }

    private static Set<String> tags(Object v) {
        if (!(v instanceof List<?> list)) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (Object o : list) {
            if (o != null) {
                String s = String.valueOf(o).trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    /** Build an attributes map, dropping JSON-null values (the model's map forbids null values). */
    private static Map<String, Object> attributes(Object v) {
        if (!(v instanceof Map<?, ?> map)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return out;
    }

    private static Instant timestamp(Object v) {
        if (!(v instanceof String s) || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Opinion opinion(Object v) {
        if (!(v instanceof Map<?, ?> map)) return null;
        double b = asDouble(map.get("b"), Double.NaN);
        double d = asDouble(map.get("d"), Double.NaN);
        double u = asDouble(map.get("u"), Double.NaN);
        double a = asDouble(map.get("a"), 0.5);
        if (Double.isNaN(b) || Double.isNaN(d) || Double.isNaN(u)) return null;
        return new Opinion(b, d, u, a);
    }
}
