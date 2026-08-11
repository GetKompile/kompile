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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.Locale;
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

    private static final int MAX_ENTRY_COUNT =
            Math.max(1, Integer.getInteger("kompile.graph.maxEntries", 10_000));
    private static final long MAX_ENTRY_BYTES =
            Math.max(1L, Long.getLong("kompile.graph.maxEntryBytes", 512L * 1024 * 1024));
    private static final long MAX_TOTAL_BYTES =
            Math.max(MAX_ENTRY_BYTES, Long.getLong("kompile.graph.maxTotalBytes", 2L * 1024 * 1024 * 1024));
    private static final long MAX_MANIFEST_BYTES = 16L * 1024 * 1024;
    private static final int MAX_ENTRY_NAME_LENGTH = 4_096;
    private static final long MAX_TOTAL_DECODED_VECTOR_VALUES = Math.max(
            1L, Long.getLong("kompile.graph.maxTotalDecodedVectorValues", 128_000_000L));

    private UnifiedGraphReader() { }

    /** Load from a file (uses {@link ZipFile} for efficient per-entry access). */
    static UnifiedGraph read(java.nio.file.Path file) throws IOException {
        EntryAccumulator accumulator = new EntryAccumulator();
        try (ZipFile zf = new ZipFile(file.toFile())) {
            var e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                validateEntryName(entry.getName(), entry.isDirectory());
                if (entry.isDirectory()) {
                    throw new IOException("Directory entries are not allowed in unified graphs: "
                            + entry.getName());
                }
                try (InputStream in = zf.getInputStream(entry)) {
                    accumulator.add(entry, in);
                }
            }
        }
        return reconstruct(accumulator.entries);
    }

    /** Load from a stream. The stream is read to end but not closed. */
    static UnifiedGraph read(InputStream in) throws IOException {
        EntryAccumulator accumulator = new EntryAccumulator();
        ZipInputStream zis = new ZipInputStream(in);
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            validateEntryName(entry.getName(), entry.isDirectory());
            if (entry.isDirectory()) {
                throw new IOException("Directory entries are not allowed in unified graphs: "
                        + entry.getName());
            }
            accumulator.add(entry, zis);
            zis.closeEntry();
        }
        return reconstruct(accumulator.entries);
    }

    private static final class EntryAccumulator {
        private final Map<String, byte[]> entries = new LinkedHashMap<>();
        private final Set<String> names = new LinkedHashSet<>();
        private final Set<String> caseFoldedNames = new LinkedHashSet<>();
        private long totalBytes;

        private void add(ZipEntry entry, InputStream in) throws IOException {
            String name = entry.getName();
            if (entries.size() >= MAX_ENTRY_COUNT) {
                throw new IOException("Unified graph has too many entries (limit " + MAX_ENTRY_COUNT + ")");
            }
            if (!names.add(name)) {
                throw new IOException("Duplicate unified-graph entry: " + name);
            }
            if (!caseFoldedNames.add(name.toLowerCase(Locale.ROOT))) {
                throw new IOException("Case-colliding unified-graph entry: " + name);
            }

            long entryLimit = UnifiedGraphFormat.ENTRY_MANIFEST.equals(name)
                    ? Math.min(MAX_ENTRY_BYTES, MAX_MANIFEST_BYTES)
                    : MAX_ENTRY_BYTES;
            long declaredSize = entry.getSize();
            if (declaredSize > entryLimit) {
                throw new IOException("Unified-graph entry exceeds size limit: " + name);
            }
            long remaining = MAX_TOTAL_BYTES - totalBytes;
            if (remaining < 0) {
                throw new IOException("Unified graph exceeds total size limit");
            }
            byte[] data = readBounded(in, Math.min(entryLimit, remaining), name);
            totalBytes += data.length;
            entries.put(name, data);
        }
    }

    private static byte[] readBounded(InputStream in, long limit, String entryName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(limit, 8_192L));
        byte[] buffer = new byte[8_192];
        long count = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (read == 0) {
                continue;
            }
            if (count > limit - read) {
                throw new IOException("Unified-graph entry exceeds size limit: " + entryName);
            }
            out.write(buffer, 0, read);
            count += read;
        }
        return out.toByteArray();
    }

    private static void validateEntryName(String name, boolean directory) throws IOException {
        if (name == null || name.isBlank() || name.length() > MAX_ENTRY_NAME_LENGTH
                || name.startsWith("/") || name.indexOf('\\') >= 0) {
            throw new IOException("Unsafe unified-graph entry name: " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new IOException("Unsafe unified-graph entry name");
            }
        }
        String candidate = directory && name.endsWith("/")
                ? name.substring(0, name.length() - 1)
                : name;
        if (candidate.isBlank()) {
            throw new IOException("Unsafe unified-graph entry name: " + name);
        }
        String[] segments = candidate.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || (i == 0 && segment.matches("[A-Za-z]:"))) {
                throw new IOException("Unsafe unified-graph entry name: " + name);
            }
        }
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
        if (!UnifiedGraphFormat.supportsRead(version)) {
            throw new IOException("Unsupported unified-graph formatVersion " + version
                    + " (reader supports " + UnifiedGraphFormat.MIN_READABLE_VERSION + ".."
                    + UnifiedGraphFormat.CURRENT_VERSION + ")");
        }
        GraphLayout layout = validateManifestLayout(manifest, entries, (int) version);

        UnifiedGraph graph = new UnifiedGraph();

        // 1. Vector layers first (needed to attach embeddings while building nodes).
        VectorLayer entityEmbeddings = null;
        VectorLayer relationEmbeddings = null;
        long decodedVectorValues = 0;
        int vectorLayerCount = 0;
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String name = e.getKey();
            if (!name.startsWith(UnifiedGraphFormat.VECTOR_DIR) || !name.endsWith(UnifiedGraphFormat.VECTOR_SUFFIX)) {
                continue;
            }
            VectorLayer layer = readVectorLayer(e.getValue());
            String expectedLayerName = name.substring(
                    UnifiedGraphFormat.VECTOR_DIR.length(),
                    name.length() - UnifiedGraphFormat.VECTOR_SUFFIX.length());
            if (!expectedLayerName.equals(layer.name())) {
                throw new IOException("Vector entry name does not match embedded layer name: " + name);
            }
            VectorSpec spec = layout.vectors().get(name);
            validateVectorSpec(name, layer, spec);
            long layerValues;
            try {
                layerValues = Math.multiplyExact((long) layer.size(), layer.dim());
                decodedVectorValues = Math.addExact(decodedVectorValues, layerValues);
            } catch (ArithmeticException overflow) {
                throw new IOException("Decoded vector size overflows for " + name, overflow);
            }
            if (decodedVectorValues > MAX_TOTAL_DECODED_VECTOR_VALUES) {
                throw new IOException("Unified graph exceeds decoded vector value limit of "
                        + MAX_TOTAL_DECODED_VECTOR_VALUES);
            }
            vectorLayerCount++;
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
        Set<String> entityIds = new LinkedHashSet<>();
        for (String line : lines(entityBytes)) {
                Map<String, Object> m = MiniJson.parseObject(line);
                String id = str(m.get("id"));
                if (id == null || id.isBlank() || !entityIds.add(id)) {
                    throw new IOException("Invalid or duplicate entity id in entities.jsonl: " + id);
                }
                double[] emb = entityEmbeddings == null ? null : entityEmbeddings.get(id);
                Map<String, Object> entityAttributes = attributes(m.get("attributes"));
                mergeExplicitTypeMemberships(entityAttributes, m.get("typeMemberships"), str(m.get("type")));
                SimpleGraphEntity entity = new SimpleGraphEntity(
                        id,
                        str(m.get("type")),
                        str(m.get("label")),
                        asDouble(m.get("weight"), 1.0),
                        asDouble(m.get("confidence"), 1.0),
                        tags(m.get("tags")),
                        emb,
                        timestamp(m.get("timestamp")),
                        entityAttributes);
                graph.addEntity(entity);
                Opinion op = opinion(m.get("opinion"));
                if (op != null) graph.putEntityOpinion(id, op);
        }

        // 3. Relations.
        byte[] relationBytes = entries.get(UnifiedGraphFormat.ENTRY_RELATIONS);
        Set<String> relationIds = new LinkedHashSet<>();
        for (String line : lines(relationBytes)) {
                Map<String, Object> m = MiniJson.parseObject(line);
                String id = str(m.get("id"));
                String sourceId = str(m.get("sourceId"));
                String targetId = str(m.get("targetId"));
                if (id == null || id.isBlank() || sourceId == null || targetId == null
                        || !relationIds.add(id)) {
                    throw new IOException("Invalid or duplicate relation row in relations.jsonl");
                }
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

        if (graph.entityCount() != layout.entityCount()) {
            throw new IOException("Unified-graph entity count mismatch: manifest="
                    + layout.entityCount() + ", loaded=" + graph.entityCount());
        }
        if (graph.relationCount() != layout.relationCount()) {
            throw new IOException("Unified-graph relation count mismatch: manifest="
                    + layout.relationCount() + ", loaded=" + graph.relationCount());
        }
        if (vectorLayerCount != layout.vectorCount()) {
            throw new IOException("Unified-graph vector-layer count mismatch: manifest="
                    + layout.vectorCount() + ", loaded=" + vectorLayerCount);
        }
        int loadedEmbeddingDim = entityEmbeddings == null ? 0 : entityEmbeddings.dim();
        if (loadedEmbeddingDim != layout.embeddingDim()) {
            throw new IOException("Unified-graph embedding dimension mismatch: manifest="
                    + layout.embeddingDim() + ", loaded=" + loadedEmbeddingDim);
        }

        byte[] schemaBytes = entries.get(UnifiedGraphFormat.ENTRY_SCHEMA_INDEX);
        if (schemaBytes != null) {
            validateSchemaIndex(schemaBytes, graph, entries);
        }

        return graph;
    }

    private static GraphLayout validateManifestLayout(
            Map<String, Object> manifest, Map<String, byte[]> entries, int formatVersion) throws IOException {
        Set<String> expected = new LinkedHashSet<>();
        expected.add(UnifiedGraphFormat.ENTRY_MANIFEST);

        Object rawSections = manifest.get("sections");
        if (!(rawSections instanceof List<?> sections)) {
            throw new IOException("Unified-graph manifest sections must be an array");
        }
        Set<String> structural = new LinkedHashSet<>();
        Set<String> allowedSections = Set.of(
                UnifiedGraphFormat.ENTRY_SCHEMA_INDEX,
                UnifiedGraphFormat.ENTRY_ENTITIES,
                UnifiedGraphFormat.ENTRY_RELATIONS,
                UnifiedGraphFormat.ENTRY_WEIGHTS,
                UnifiedGraphFormat.ENTRY_OPINIONS);
        for (Object raw : sections) {
            String section = requiredString(raw, "sections[]");
            if (!allowedSections.contains(section) || !structural.add(section)) {
                throw new IOException("Invalid or duplicate unified-graph section: " + section);
            }
            expected.add(section);
        }
        if (!structural.contains(UnifiedGraphFormat.ENTRY_ENTITIES)
                || !structural.contains(UnifiedGraphFormat.ENTRY_RELATIONS)) {
            throw new IOException("Unified-graph manifest must declare entities.jsonl and relations.jsonl");
        }
        if (formatVersion >= 2 && !structural.contains(UnifiedGraphFormat.ENTRY_SCHEMA_INDEX)) {
            throw new IOException("Unified-graph v2 manifest must declare schemas/index.json");
        }

        Object rawArtifacts = manifest.get("artifacts");
        if (rawArtifacts != null) {
            if (!(rawArtifacts instanceof List<?> artifacts)) {
                throw new IOException("Unified-graph manifest artifacts must be an array");
            }
            for (Object raw : artifacts) {
                String artifact = requiredString(raw, "artifacts[]");
                String entry = UnifiedGraphFormat.modelEntry(artifact);
                validateEntryName(entry, false);
                if (!expected.add(entry)) {
                    throw new IOException("Duplicate unified-graph artifact: " + artifact);
                }
            }
        }

        Object rawLayers = manifest.get("vectorLayers");
        if (!(rawLayers instanceof List<?> layers)) {
            throw new IOException("Unified-graph manifest vectorLayers must be an array");
        }
        Map<String, VectorSpec> vectors = new LinkedHashMap<>();
        for (Object raw : layers) {
            if (!(raw instanceof Map<?, ?> item)) {
                throw new IOException("Unified-graph vector layer descriptor must be an object");
            }
            String name = requiredString(item.get("name"), "vectorLayers[].name");
            String entry = requiredString(item.get("entry"), "vectorLayers[].entry");
            String expectedEntry = UnifiedGraphFormat.vectorEntry(name);
            if (!expectedEntry.equals(entry)) {
                throw new IOException("Vector layer entry does not match its name: " + name);
            }
            validateEntryName(entry, false);
            VectorLayer.Target target;
            Dtype dtype;
            try {
                target = VectorLayer.Target.valueOf(requiredString(
                        item.get("target"), "vectorLayers[].target"));
                dtype = Dtype.valueOf(requiredString(item.get("dtype"), "vectorLayers[].dtype"));
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid vector layer target or dtype for " + name, e);
            }
            int dim = requiredNonNegativeInt(item.get("dim"), "vectorLayers[].dim");
            int count = requiredNonNegativeInt(item.get("count"), "vectorLayers[].count");
            if (vectors.putIfAbsent(entry, new VectorSpec(name, target, dtype, dim, count)) != null
                    || !expected.add(entry)) {
                throw new IOException("Duplicate vector layer entry: " + entry);
            }
        }

        if (!entries.keySet().equals(expected)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(entries.keySet());
            Set<String> unexpected = new LinkedHashSet<>(entries.keySet());
            unexpected.removeAll(expected);
            throw new IOException("Unified-graph manifest inventory mismatch; missing="
                    + missing + ", unexpected=" + unexpected);
        }

        Object rawCounts = manifest.get("counts");
        if (!(rawCounts instanceof Map<?, ?> counts)) {
            throw new IOException("Unified-graph manifest counts must be an object");
        }
        int entityCount = requiredNonNegativeInt(counts.get("entities"), "counts.entities");
        int relationCount = requiredNonNegativeInt(counts.get("relations"), "counts.relations");
        int vectorCount = requiredNonNegativeInt(counts.get("vectorLayers"), "counts.vectorLayers");
        if (vectorCount != vectors.size()) {
            throw new IOException("Manifest vector-layer count does not match its inventory");
        }
        int embeddingDim = requiredNonNegativeInt(manifest.get("embeddingDim"), "embeddingDim");
        return new GraphLayout(entityCount, relationCount, vectorCount, embeddingDim, vectors);
    }

    private static void validateSchemaIndex(
            byte[] bytes, UnifiedGraph graph, Map<String, byte[]> entries) throws IOException {
        Map<String, Object> schema = MiniJson.parseObject(new String(bytes, StandardCharsets.UTF_8));
        if (!"kompile-unified-schema".equals(schema.get("format"))) {
            throw new IOException("Invalid unified-graph schema index format");
        }
        if (requiredNonNegativeInt(schema.get("version"), "schemas/index.json.version") != 1) {
            throw new IOException("Unsupported unified-graph schema index version");
        }
        if (requiredNonNegativeInt(schema.get("entityCount"), "schemas/index.json.entityCount")
                != graph.entityCount()
                || requiredNonNegativeInt(schema.get("relationCount"), "schemas/index.json.relationCount")
                != graph.relationCount()) {
            throw new IOException("Unified-graph schema index count mismatch");
        }

        Set<String> entityTypes = new LinkedHashSet<>();
        Set<String> entityAttributeKeys = new LinkedHashSet<>();
        collectSchemaFacts(entries.get(UnifiedGraphFormat.ENTRY_ENTITIES), entityTypes, entityAttributeKeys);
        Set<String> relationTypes = new LinkedHashSet<>();
        Set<String> relationAttributeKeys = new LinkedHashSet<>();
        collectSchemaFacts(entries.get(UnifiedGraphFormat.ENTRY_RELATIONS), relationTypes, relationAttributeKeys);
        Set<String> schemaArtifacts = graph.artifacts().keySet().stream()
                .filter(name -> name.startsWith("schema/"))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        validateSchemaSet(schema, "entityTypes", entityTypes);
        validateSchemaSet(schema, "relationTypes", relationTypes);
        validateSchemaSet(schema, "entityAttributeKeys", entityAttributeKeys);
        validateSchemaSet(schema, "relationAttributeKeys", relationAttributeKeys);
        validateSchemaSet(schema, "declaredSchemaArtifacts", schemaArtifacts);
    }

    private static void collectSchemaFacts(
            byte[] rows, Set<String> types, Set<String> attributeKeys) throws IOException {
        for (String line : lines(rows)) {
            Map<String, Object> row = MiniJson.parseObject(line);
            Object type = row.get("type");
            if (type instanceof String string) {
                types.add(string);
            }
            Object attributes = row.get("attributes");
            if (attributes instanceof Map<?, ?> map) {
                for (Object key : map.keySet()) {
                    if (key != null) {
                        attributeKeys.add(String.valueOf(key));
                    }
                }
            }
        }
    }

    private static void validateSchemaSet(
            Map<String, Object> schema, String field, Set<String> actual) throws IOException {
        Object value = schema.get(field);
        if (!(value instanceof List<?> list)) {
            throw new IOException("Unified-graph schema index field " + field + " must be an array");
        }
        Set<String> declared = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof String string) || !declared.add(string)) {
                throw new IOException("Invalid or duplicate unified-graph schema index value in " + field);
            }
        }
        if (!declared.equals(actual)) {
            throw new IOException("Unified-graph schema index mismatch for " + field);
        }
    }

    private static void validateVectorSpec(String entry, VectorLayer layer, VectorSpec spec)
            throws IOException {
        if (spec == null || !spec.name().equals(layer.name()) || spec.target() != layer.target()
                || spec.dtype() != layer.dtype() || spec.dim() != layer.dim()
                || spec.count() != layer.size()) {
            throw new IOException("Vector layer metadata mismatch for " + entry);
        }
    }

    private static String requiredString(Object value, String field) throws IOException {
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IOException("Unified-graph manifest field " + field + " must be a non-empty string");
        }
        return string;
    }

    private static int requiredNonNegativeInt(Object value, String field) throws IOException {
        if (!(value instanceof Number number)) {
            throw new IOException("Unified-graph manifest field " + field + " must be an integer");
        }
        long result = number.longValue();
        double exact = number.doubleValue();
        if (!Double.isFinite(exact) || exact != result || result < 0 || result > Integer.MAX_VALUE) {
            throw new IOException("Unified-graph manifest field " + field
                    + " must be a non-negative integer");
        }
        return (int) result;
    }

    private record VectorSpec(
            String name, VectorLayer.Target target, Dtype dtype, int dim, int count) {}

    private record GraphLayout(
            int entityCount, int relationCount, int vectorCount, int embeddingDim,
            Map<String, VectorSpec> vectors) {}

    private static VectorLayer readVectorLayer(byte[] blob) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob))) {
            VectorLayer layer = VectorBlobCodec.read(in);
            if (in.available() != 0) {
                throw new IOException("Trailing data in vector layer " + layer.name());
            }
            return layer;
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
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(v instanceof Map<?, ?> map)) return out;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        return out;
    }

    private static void mergeExplicitTypeMemberships(
            Map<String, Object> attributes, Object rawMemberships, String primaryType) throws IOException {
        if (rawMemberships == null) {
            return;
        }
        if (!(rawMemberships instanceof List<?> memberships)) {
            throw new IOException("Entity typeMemberships must be an array");
        }
        Set<String> additional = new LinkedHashSet<>();
        Object existing = attributes.get("additionalTypes");
        if (existing instanceof List<?> existingTypes) {
            for (Object item : existingTypes) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    additional.add(String.valueOf(item));
                }
            }
        }
        for (Object item : memberships) {
            if (!(item instanceof String membership) || membership.isBlank()) {
                throw new IOException("Entity typeMemberships must contain non-empty strings");
            }
            if (!membership.equals(primaryType)) {
                additional.add(membership);
            }
        }
        if (!additional.isEmpty()) {
            attributes.put("additionalTypes", List.copyOf(additional));
        }
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
