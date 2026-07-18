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
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;

import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a {@link UnifiedGraph} to its single-file form (see {@link UnifiedGraphFormat} for the
 * container layout). Internal to {@link UnifiedGraph#save(Path)}; streams entry-by-entry, so saving a
 * large graph does not require holding the whole serialized form in memory.
 *
 * <p>The primary per-node/edge embeddings are written as the reserved
 * {@link UnifiedGraphFormat#PRIMARY_ENTITY_VECTORS} / {@link UnifiedGraphFormat#PRIMARY_RELATION_VECTORS}
 * layers at {@code primaryVectorDtype} (default {@link Dtype#F32}, lossless for kompile's native
 * float32 embeddings). Additional layers keep their own configured dtype.</p>
 */
final class UnifiedGraphWriter {

    private UnifiedGraphWriter() { }

    static void write(UnifiedGraph graph, Path file, Dtype primaryVectorDtype) throws IOException {
        Path target = file.toAbsolutePath();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, "." + target.getFileName() + "-", ".tmp");
        boolean published = false;
        try {
            try (OutputStream out = Files.newOutputStream(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                write(graph, out, primaryVectorDtype);
            }
            moveAtomically(temporary, target);
            published = true;
        } finally {
            if (!published) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    static void write(UnifiedGraph graph, OutputStream out, Dtype primaryVectorDtype) throws IOException {
        // Assemble the full ordered set of vector layers: primary embeddings synthesized from the
        // graph, then any additional layers.
        List<VectorLayer> layers = new ArrayList<>();
        VectorLayer entityEmbeddings = buildPrimaryLayer(
                UnifiedGraphFormat.PRIMARY_ENTITY_VECTORS, VectorLayer.Target.ENTITY, primaryVectorDtype,
                graph.entities(), GraphEntity::id, GraphEntity::embedding);
        if (entityEmbeddings != null) layers.add(entityEmbeddings);
        VectorLayer relationEmbeddings = buildPrimaryLayer(
                UnifiedGraphFormat.PRIMARY_RELATION_VECTORS, VectorLayer.Target.RELATION, primaryVectorDtype,
                graph.relations(), GraphRelation::id, GraphRelation::embedding);
        if (relationEmbeddings != null) layers.add(relationEmbeddings);
        layers.addAll(graph.vectorLayers().values());
        validateEntryLayout(graph, layers);

        try (ZipOutputStream zip = new ZipOutputStream(new NonClosingOutputStream(out))) {
            zip.setLevel(Deflater.BEST_SPEED); // structural JSON compresses well even at level 1

            byte[] manifest = buildManifest(graph, layers,
                    entityEmbeddings == null ? 0 : entityEmbeddings.dim()).getBytes(StandardCharsets.UTF_8);
            putEntry(zip, UnifiedGraphFormat.ENTRY_MANIFEST, manifest);

            writeEntities(zip, graph);
            writeRelations(zip, graph);
            writeOrphanOpinions(zip, graph);

            if (!graph.weightMaps().isEmpty()) {
                putEntry(zip, UnifiedGraphFormat.ENTRY_WEIGHTS,
                        MiniJson.write(graph.weightMaps()).getBytes(StandardCharsets.UTF_8));
            }

            for (Map.Entry<String, byte[]> artifact : graph.artifacts().entrySet()) {
                putEntry(zip, UnifiedGraphFormat.modelEntry(artifact.getKey()), artifact.getValue());
            }

            for (VectorLayer layer : layers) {
                writeVectorEntry(zip, layer);
            }
        }
        out.flush();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Sections
    // ═════════════════════════════════════════════════════════════════════════

    private static void writeEntities(ZipOutputStream zip, UnifiedGraph graph) throws IOException {
        putNextEntry(zip, UnifiedGraphFormat.ENTRY_ENTITIES);
        Writer w = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
        for (GraphEntity e : graph.entities()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("type", e.type());
            m.put("label", e.label());
            m.put("weight", e.weight());
            m.put("confidence", e.confidence());
            if (!e.tags().isEmpty()) m.put("tags", new ArrayList<>(e.tags()));
            if (e.timestamp() != null) m.put("timestamp", e.timestamp().toString());
            if (!e.attributes().isEmpty()) m.put("attributes", e.attributes());
            Opinion op = graph.entityOpinion(e.id());
            if (op != null) m.put("opinion", opinionMap(op));
            w.write(MiniJson.write(m));
            w.write('\n');
        }
        w.flush();
        zip.closeEntry();
    }

    private static void writeRelations(ZipOutputStream zip, UnifiedGraph graph) throws IOException {
        putNextEntry(zip, UnifiedGraphFormat.ENTRY_RELATIONS);
        Writer w = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
        for (GraphRelation r : graph.relations()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("sourceId", r.sourceId());
            m.put("targetId", r.targetId());
            m.put("type", r.type());
            m.put("weight", r.weight());
            m.put("confidence", r.confidence());
            m.put("directed", r.directed());
            if (!r.tags().isEmpty()) m.put("tags", new ArrayList<>(r.tags()));
            if (r.timestamp() != null) m.put("timestamp", r.timestamp().toString());
            if (!r.attributes().isEmpty()) m.put("attributes", r.attributes());
            Opinion op = graph.relationOpinion(r.id());
            if (op != null) m.put("opinion", opinionMap(op));
            w.write(MiniJson.write(m));
            w.write('\n');
        }
        w.flush();
        zip.closeEntry();
    }

    private static void writeOrphanOpinions(ZipOutputStream zip, UnifiedGraph graph) throws IOException {
        Set<String> entityIds = graph.entities().stream()
                .map(GraphEntity::id)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> relationIds = graph.relations().stream()
                .map(GraphRelation::id)
                .collect(java.util.stream.Collectors.toSet());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Opinion> entry : graph.entityOpinions().entrySet()) {
            if (!entityIds.contains(entry.getKey())) {
                rows.add(opinionRow("entity", entry.getKey(), entry.getValue()));
            }
        }
        for (Map.Entry<String, Opinion> entry : graph.relationOpinions().entrySet()) {
            if (!relationIds.contains(entry.getKey())) {
                rows.add(opinionRow("relation", entry.getKey(), entry.getValue()));
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        putNextEntry(zip, UnifiedGraphFormat.ENTRY_OPINIONS);
        Writer w = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
        for (Map<String, Object> row : rows) {
            w.write(MiniJson.write(row));
            w.write('\n');
        }
        w.flush();
        zip.closeEntry();
    }

    private static Map<String, Object> opinionRow(String kind, String id, Opinion opinion) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", kind);
        row.put("id", id);
        row.put("opinion", opinionMap(opinion));
        return row;
    }

    private static boolean hasOrphanOpinions(UnifiedGraph graph) {
        Set<String> entityIds = graph.entities().stream()
                .map(GraphEntity::id)
                .collect(java.util.stream.Collectors.toSet());
        for (String opinionId : graph.entityOpinions().keySet()) {
            if (!entityIds.contains(opinionId)) return true;
        }
        Set<String> relationIds = graph.relations().stream()
                .map(GraphRelation::id)
                .collect(java.util.stream.Collectors.toSet());
        for (String opinionId : graph.relationOpinions().keySet()) {
            if (!relationIds.contains(opinionId)) return true;
        }
        return false;
    }

    private static void writeVectorEntry(ZipOutputStream zip, VectorLayer layer) throws IOException {
        putNextEntry(zip, UnifiedGraphFormat.vectorEntry(layer.name()));
        DataOutputStream dos = new DataOutputStream(zip);
        VectorBlobCodec.write(dos, layer);
        dos.flush();
        zip.closeEntry();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Manifest
    // ═════════════════════════════════════════════════════════════════════════

    private static String buildManifest(UnifiedGraph graph, List<VectorLayer> layers, int embeddingDim) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("format", UnifiedGraphFormat.FORMAT);
        manifest.put("formatVersion", UnifiedGraphFormat.FORMAT_VERSION);
        manifest.put("generator", UnifiedGraphFormat.GENERATOR);
        manifest.put("createdAtEpochMs", System.currentTimeMillis());

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("entities", graph.entityCount());
        counts.put("relations", graph.relationCount());
        counts.put("vectorLayers", layers.size());
        manifest.put("counts", counts);
        manifest.put("embeddingDim", embeddingDim);

        if (!graph.meta().isEmpty()) manifest.put("meta", graph.meta());

        List<Object> sections = new ArrayList<>();
        sections.add(UnifiedGraphFormat.ENTRY_ENTITIES);
        sections.add(UnifiedGraphFormat.ENTRY_RELATIONS);
        if (!graph.weightMaps().isEmpty()) sections.add(UnifiedGraphFormat.ENTRY_WEIGHTS);
        if (hasOrphanOpinions(graph)) sections.add(UnifiedGraphFormat.ENTRY_OPINIONS);
        manifest.put("sections", sections);

        if (!graph.artifacts().isEmpty()) {
            manifest.put("artifacts", new ArrayList<>(graph.artifacts().keySet()));
        }

        List<Object> layerIndex = new ArrayList<>();
        for (VectorLayer t : layers) {
            Map<String, Object> ti = new LinkedHashMap<>();
            ti.put("name", t.name());
            ti.put("target", t.target().name());
            ti.put("dtype", t.dtype().name());
            ti.put("dim", t.dim());
            ti.put("count", t.size());
            ti.put("entry", UnifiedGraphFormat.vectorEntry(t.name()));
            layerIndex.add(ti);
        }
        manifest.put("vectorLayers", layerIndex);
        return MiniJson.write(manifest);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    private static Map<String, Object> opinionMap(Opinion op) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", op.belief());
        m.put("d", op.disbelief());
        m.put("u", op.uncertainty());
        m.put("a", op.baseRate());
        return m;
    }

    private interface IdOf<T> { String id(T t); }
    private interface EmbeddingOf<T> { double[] embedding(T t); }

    private static <T> VectorLayer buildPrimaryLayer(String name, VectorLayer.Target target, Dtype dtype,
                                                     Iterable<T> items, IdOf<T> idOf, EmbeddingOf<T> embOf) {
        VectorLayer layer = new VectorLayer(name, target, 0, dtype);
        for (T item : items) {
            double[] emb = embOf.embedding(item);
            if (emb != null && emb.length > 0) {
                layer.put(idOf.id(item), emb);
            }
        }
        return layer.isEmpty() ? null : layer;
    }

    private static void putEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        putNextEntry(zip, name);
        zip.write(data);
        zip.closeEntry();
    }

    private static void putNextEntry(ZipOutputStream zip, String name) throws IOException {
        validateEntryName(name);
        zip.putNextEntry(new ZipEntry(name));
    }

    private static void validateEntryLayout(UnifiedGraph graph, List<VectorLayer> layers) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        addEntryName(names, UnifiedGraphFormat.ENTRY_MANIFEST);
        addEntryName(names, UnifiedGraphFormat.ENTRY_ENTITIES);
        addEntryName(names, UnifiedGraphFormat.ENTRY_RELATIONS);
        if (!graph.weightMaps().isEmpty()) {
            addEntryName(names, UnifiedGraphFormat.ENTRY_WEIGHTS);
        }
        if (hasOrphanOpinions(graph)) {
            addEntryName(names, UnifiedGraphFormat.ENTRY_OPINIONS);
        }
        for (String artifactName : graph.artifacts().keySet()) {
            addEntryName(names, UnifiedGraphFormat.modelEntry(artifactName));
        }
        for (VectorLayer layer : layers) {
            addEntryName(names, UnifiedGraphFormat.vectorEntry(layer.name()));
        }
    }

    private static void addEntryName(Set<String> names, String name) throws IOException {
        validateEntryName(name);
        if (!names.add(name.toLowerCase(Locale.ROOT))) {
            throw new IOException("Duplicate or case-colliding unified-graph entry: " + name);
        }
    }

    private static void validateEntryName(String name) throws IOException {
        if (name == null || name.isBlank() || name.length() > 4_096
                || name.startsWith("/") || name.endsWith("/") || name.indexOf('\\') >= 0) {
            throw new IOException("Unsafe unified-graph entry name: " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new IOException("Unsafe unified-graph entry name");
            }
        }
        String[] segments = name.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)
                    || (i == 0 && segment.matches("[A-Za-z]:"))) {
                throw new IOException("Unsafe unified-graph entry name: " + name);
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Wraps an {@link OutputStream} so {@code close()} flushes but does not close the delegate. */
    private static final class NonClosingOutputStream extends OutputStream {
        private final OutputStream delegate;
        NonClosingOutputStream(OutputStream delegate) { this.delegate = delegate; }
        @Override public void write(int b) throws IOException { delegate.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { delegate.write(b, off, len); }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.flush(); }
    }
}
