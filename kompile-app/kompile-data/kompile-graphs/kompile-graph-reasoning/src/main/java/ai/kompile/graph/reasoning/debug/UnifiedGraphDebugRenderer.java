/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.graph.reasoning.debug;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.VectorLayer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Deterministic, property-complete diagnostics for a {@link UnifiedGraph}.
 *
 * <p>This is deliberately a renderer, not a second graph transfer model. Both the text and
 * raster outputs are generated from the same ordered line representation, which keeps debugging
 * output comparable across terminals, CI logs and image attachments.</p>
 */
public final class UnifiedGraphDebugRenderer {

    public static final int DEFAULT_COLUMNS = 132;
    public static final int DEFAULT_PAGE_LINES = 70;

    private UnifiedGraphDebugRenderer() {
    }

    public record Options(
            boolean includeVectorValues,
            boolean includeNullValues,
            int columns,
            int pageLines) {

        public Options {
            if (columns < 40) throw new IllegalArgumentException("columns must be >= 40");
            if (pageLines < 10) throw new IllegalArgumentException("pageLines must be >= 10");
        }

        public static Options defaults() {
            return new Options(false, true, DEFAULT_COLUMNS, DEFAULT_PAGE_LINES);
        }

        public Options withVectorValues(boolean enabled) {
            return new Options(enabled, includeNullValues, columns, pageLines);
        }

        public Options withNullValues(boolean enabled) {
            return new Options(includeVectorValues, enabled, columns, pageLines);
        }
    }

    /** Render the complete graph as deterministic US-ASCII text. */
    public static String toAscii(UnifiedGraph graph) {
        return toAscii(graph, Options.defaults());
    }

    /** Render the complete graph as deterministic US-ASCII text. */
    public static String toAscii(UnifiedGraph graph, Options options) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(options, "options");
        StringBuilder out = new StringBuilder();
        for (String line : lines(graph, options)) {
            out.append(line).append('\n');
        }
        return out.toString();
    }

    /**
     * Render the graph as a PNG containing the same text produced by {@link #toAscii}.
     * A single image is useful for focused graphs; use {@link #toPngBundle} for large graphs.
     */
    public static byte[] toPng(UnifiedGraph graph) throws IOException {
        return toPng(graph, Options.defaults());
    }

    public static byte[] toPng(UnifiedGraph graph, Options options) throws IOException {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(options, "options");
        return renderPage(lines(graph, options), options);
    }

    /**
     * Render a complete graph to a ZIP of PNG pages. No graph lines are dropped: page boundaries
     * are explicit and every page has a stable filename.
     */
    public static byte[] toPngBundle(UnifiedGraph graph) throws IOException {
        return toPngBundle(graph, Options.defaults());
    }

    public static byte[] toPngBundle(UnifiedGraph graph, Options options) throws IOException {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(options, "options");
        List<String> allLines = lines(graph, options);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            int page = 0;
            for (int start = 0; start < allLines.size(); start += options.pageLines()) {
                int end = Math.min(allLines.size(), start + options.pageLines());
                byte[] png = renderPage(allLines.subList(start, end), options);
                ZipEntry entry = new ZipEntry(String.format("graph-debug-%04d.png", ++page));
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(png);
                zip.closeEntry();
            }

            ZipEntry manifest = new ZipEntry("render-manifest.txt");
            manifest.setTime(0L);
            zip.putNextEntry(manifest);
            String manifestText = "format=kompile-unified-graph-debug\n"
                    + "version=2\n"
                    + "pages=" + page + "\n"
                    + "columns=" + options.columns() + "\n"
                    + "pageLines=" + options.pageLines() + "\n"
                    + "vectorValues=" + options.includeVectorValues() + "\n";
            zip.write(manifestText.getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    /** Exposes the ordered lines for clients that need streaming or custom page sizes. */
    public static List<String> lines(UnifiedGraph graph, Options options) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(options, "options");

        List<String> lines = new ArrayList<>();
        line(lines, "KGRAPH DEBUG");
        line(lines, "format = unified-graph");
        line(lines, "version = 2");
        line(lines, "entities = " + graph.entityCount());
        line(lines, "relations = " + graph.relationCount());
        line(lines, "factSheetId = " + graph.factSheetId());
        line(lines, "graphId = " + graph.graphId());
        lines.add("");

        section(lines, "GRAPH META");
        appendMap(lines, "meta", graph.meta(), options, 0);
        lines.add("");

        section(lines, "OBSERVED SCHEMA");
        appendObservedSchema(lines, graph, options);
        lines.add("");

        section(lines, "DECLARED SCHEMAS");
        appendDeclaredSchemas(lines, graph, options);
        lines.add("");

        section(lines, "NODES");
        List<GraphEntity> entities = new ArrayList<>(graph.entities());
        entities.sort(Comparator.comparing(GraphEntity::id, Comparator.nullsFirst(String::compareTo)));
        Map<String, List<GraphRelation>> outgoing = new LinkedHashMap<>();
        Map<String, List<GraphRelation>> incoming = new LinkedHashMap<>();
        for (GraphRelation relation : graph.relations()) {
            outgoing.computeIfAbsent(relation.sourceId(), ignored -> new ArrayList<>()).add(relation);
            incoming.computeIfAbsent(relation.targetId(), ignored -> new ArrayList<>()).add(relation);
        }
        Comparator<GraphRelation> byId = Comparator.comparing(GraphRelation::id,
                Comparator.nullsFirst(String::compareTo));
        outgoing.values().forEach(list -> list.sort(byId));
        incoming.values().forEach(list -> list.sort(byId));

        if (entities.isEmpty()) {
            line(lines, "  (none)");
        }
        for (GraphEntity entity : entities) {
            line(lines, "NODE " + printable(entity.id()));
            property(lines, "id", entity.id(), options, 1);
            property(lines, "type", entity.type(), options, 1);
            property(lines, "typeMemberships", entity.typeMemberships(), options, 1);
            property(lines, "label", entity.label(), options, 1);
            property(lines, "weight", entity.weight(), options, 1);
            property(lines, "confidence", entity.confidence(), options, 1);
            property(lines, "tags", entity.tags(), options, 1);
            property(lines, "timestamp", entity.timestamp(), options, 1);
            property(lines, "validTime", safeValidTime(entity), options, 1);
            property(lines, "embedding", vector(entity.embedding(), options), options, 1);
            appendMap(lines, "attributes", entity.attributes(), options, 1);
            property(lines, "opinion", graph.entityOpinion(entity.id()), options, 1);
            appendConnections(lines, "out", outgoing.get(entity.id()), options);
            appendConnections(lines, "in", incoming.get(entity.id()), options);
            lines.add("");
        }

        section(lines, "RELATIONS");
        List<GraphRelation> relations = new ArrayList<>(graph.relations());
        relations.sort(byId);
        Set<String> entityIds = new LinkedHashSet<>();
        entities.forEach(entity -> entityIds.add(entity.id()));
        if (relations.isEmpty()) {
            line(lines, "  (none)");
        }
        for (GraphRelation relation : relations) {
            line(lines, "RELATION " + printable(relation.id()));
            property(lines, "id", relation.id(), options, 1);
            property(lines, "sourceId", relation.sourceId(), options, 1);
            property(lines, "targetId", relation.targetId(), options, 1);
            property(lines, "type", relation.type(), options, 1);
            property(lines, "weight", relation.weight(), options, 1);
            property(lines, "confidence", relation.confidence(), options, 1);
            property(lines, "directed", relation.directed(), options, 1);
            property(lines, "tags", relation.tags(), options, 1);
            property(lines, "timestamp", relation.timestamp(), options, 1);
            property(lines, "validTime", safeValidTime(relation), options, 1);
            property(lines, "embedding", vector(relation.embedding(), options), options, 1);
            appendMap(lines, "attributes", relation.attributes(), options, 1);
            property(lines, "opinion", graph.relationOpinion(relation.id()), options, 1);
            if (!entityIds.contains(relation.sourceId()) || !entityIds.contains(relation.targetId())) {
                line(lines, "  diagnostic = dangling endpoint");
            }
            lines.add("");
        }

        section(lines, "VECTOR LAYERS");
        if (graph.vectorLayers().isEmpty()) {
            line(lines, "  (none)");
        } else {
            graph.vectorLayers().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        VectorLayer layer = entry.getValue();
                        line(lines, "  " + printable(entry.getKey())
                                + " target=" + layer.target()
                                + " size=" + layer.size()
                                + " dimension=" + layer.dim()
                                + " dtype=" + layer.dtype());
                        layer.rows().entrySet().stream().sorted(Map.Entry.comparingByKey())
                                .forEach(row -> property(lines, "row." + row.getKey(),
                                        vector(row.getValue(), options), options, 2));
                    });
        }
        lines.add("");

        section(lines, "WEIGHT MAPS");
        if (graph.weightMaps().isEmpty()) {
            line(lines, "  (none)");
        } else {
            graph.weightMaps().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> appendMap(lines, entry.getKey(), entry.getValue(), options, 1));
        }
        lines.add("");

        section(lines, "ARTIFACTS");
        if (graph.artifacts().isEmpty()) {
            line(lines, "  (none)");
        } else {
            graph.artifacts().keySet().stream().sorted()
                    .forEach(name -> line(lines, "  " + printable(name)
                            + " bytes=" + graph.artifacts().get(name).length
                            + " sha256=" + digest(graph.artifacts().get(name))));
        }
        return List.copyOf(lines);
    }

    private static void appendObservedSchema(List<String> lines, UnifiedGraph graph, Options options) {
        Map<String, Integer> entityTypes = new TreeMap<>();
        Map<String, Integer> relationTypes = new TreeMap<>();
        Map<String, Integer> entityProperties = new TreeMap<>();
        Map<String, Integer> relationProperties = new TreeMap<>();
        Map<String, Integer> entityPropertyPaths = new TreeMap<>();
        Map<String, Integer> relationPropertyPaths = new TreeMap<>();
        for (GraphEntity entity : graph.entities()) {
            entityTypes.merge(schemaKey(entity.type()), 1, Integer::sum);
            entity.attributes().keySet().forEach(k -> entityProperties.merge(schemaKey(k), 1, Integer::sum));
            addPropertyPaths(entityPropertyPaths, "attributes", entity.attributes());
        }
        for (GraphRelation relation : graph.relations()) {
            relationTypes.merge(schemaKey(relation.type()), 1, Integer::sum);
            relation.attributes().keySet().forEach(k -> relationProperties.merge(schemaKey(k), 1, Integer::sum));
            addPropertyPaths(relationPropertyPaths, "attributes", relation.attributes());
        }
        appendMap(lines, "entityTypes", entityTypes, options, 1);
        appendMap(lines, "relationTypes", relationTypes, options, 1);
        appendMap(lines, "entityAttributeKeys", entityProperties, options, 1);
        appendMap(lines, "relationAttributeKeys", relationProperties, options, 1);
        appendMap(lines, "entityPropertyPaths", entityPropertyPaths, options, 1);
        appendMap(lines, "relationPropertyPaths", relationPropertyPaths, options, 1);
        property(lines, "entityFields", List.of("id", "type", "typeMemberships", "label",
                "weight", "confidence", "tags", "timestamp", "validTime", "embedding",
                "attributes", "opinion"), options, 1);
        property(lines, "relationFields", List.of("id", "sourceId", "targetId", "type",
                "weight", "confidence", "directed", "tags", "timestamp", "validTime",
                "embedding", "attributes", "opinion"), options, 1);
        property(lines, "metaKeys", graph.meta().keySet().stream().sorted().toList(), options, 1);
        property(lines, "vectorLayers", graph.vectorLayers().keySet().stream().sorted().toList(), options, 1);
        property(lines, "weightMaps", graph.weightMaps().keySet().stream().sorted().toList(), options, 1);
        property(lines, "entityOpinionKeys", graph.entityOpinions().keySet().stream().sorted().toList(), options, 1);
        property(lines, "relationOpinionKeys", graph.relationOpinions().keySet().stream().sorted().toList(), options, 1);
        line(lines, "  declaredSchemaArtifacts = "
                + graph.artifacts().keySet().stream().filter(k -> k.startsWith("schema/")).sorted().toList());
    }

    private static void appendDeclaredSchemas(List<String> lines, UnifiedGraph graph, Options options) {
        List<String> names = graph.artifacts().keySet().stream()
                .filter(name -> name.startsWith("schema/"))
                .sorted()
                .toList();
        if (names.isEmpty()) {
            line(lines, "  (none)");
            return;
        }
        for (String name : names) {
            byte[] data = graph.artifact(name);
            line(lines, "SCHEMA " + printable(name));
            property(lines, "bytes", data == null ? 0 : data.length, options, 1);
            property(lines, "sha256", data == null ? null : digest(data), options, 1);
            String text = textArtifact(data);
            if (text == null) {
                property(lines, "content", "<binary schema artifact>", options, 1);
                continue;
            }
            String[] rows = text.split("\\R", -1);
            int rowCount = rows.length;
            // A final line terminator separates records; it does not introduce a phantom
            // empty schema record in the human-readable dump.
            if (rowCount > 0 && (text.endsWith("\n") || text.endsWith("\r"))) {
                rowCount--;
            }
            for (int row = 0; row < rowCount; row++) {
                appendWrapped(lines, "  content[" + row + "] = ", ascii(rows[row]), options.columns());
            }
        }
    }

    private static String schemaKey(Object value) {
        if (value == null) return "<null>";
        String text = String.valueOf(value);
        return text.isBlank() ? "<empty>" : text;
    }

    private static void addPropertyPaths(Map<String, Integer> target, String prefix, Map<?, ?> values) {
        if (values == null || values.isEmpty()) return;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            String path = prefix + "." + schemaKey(entry.getKey());
            target.merge(path, 1, Integer::sum);
            if (entry.getValue() instanceof Map<?, ?> nested) {
                addPropertyPaths(target, path, nested);
            }
        }
    }

    private static String textArtifact(byte[] data) {
        if (data == null) return null;
        String text = new String(data, StandardCharsets.UTF_8);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == 0 || (Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t')) {
                return null;
            }
        }
        return text;
    }

    private static Object safeValidTime(GraphEntity entity) {
        try {
            return entity.validTime();
        } catch (RuntimeException error) {
            return "invalid: " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    private static Object safeValidTime(GraphRelation relation) {
        try {
            return relation.validTime();
        } catch (RuntimeException error) {
            return "invalid: " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
    }

    private static void appendConnections(List<String> lines, String direction,
                                          List<GraphRelation> relations, Options options) {
        if (relations == null || relations.isEmpty()) {
            line(lines, "  " + direction + "Connections = []");
            return;
        }
        for (GraphRelation relation : relations) {
            String arrow = "out".equals(direction) ? " -> " : " <- ";
            line(lines, "  " + direction + "Connection "
                    + printable(relation.id()) + arrow + printable(relation.type())
                    + " [" + printable(relation.sourceId()) + ", " + printable(relation.targetId()) + "]");
        }
    }

    private static void appendMap(List<String> lines, String name, Map<?, ?> values,
                                  Options options, int indent) {
        if (values == null || values.isEmpty()) {
            property(lines, name, values, options, indent);
            return;
        }
        Map<String, Object> ordered = new TreeMap<>();
        values.forEach((key, value) -> ordered.put(String.valueOf(key), value));
        for (Map.Entry<String, Object> entry : ordered.entrySet()) {
            property(lines, name + "." + entry.getKey(), entry.getValue(), options, indent);
        }
    }

    private static void property(List<String> lines, String name, Object value,
                                 Options options, int indent) {
        if (value == null && !options.includeNullValues()) return;
        String prefix = "  ".repeat(Math.max(0, indent)) + printable(name) + " = ";
        String rendered = renderValue(value, options.includeVectorValues());
        appendWrapped(lines, prefix, rendered, options.columns());
    }

    private static void appendWrapped(List<String> lines, String prefix, String value, int columns) {
        String safe = value == null ? "null" : value;
        int available = Math.max(12, columns - prefix.length());
        if (safe.isEmpty()) {
            lines.add(prefix);
            return;
        }
        String remaining = safe;
        boolean first = true;
        while (remaining.length() > available) {
            int cut = remaining.lastIndexOf(' ', available);
            if (cut < 1) cut = available;
            lines.add((first ? prefix : " ".repeat(prefix.length())) + remaining.substring(0, cut));
            remaining = remaining.substring(cut).stripLeading();
            first = false;
        }
        lines.add((first ? prefix : " ".repeat(prefix.length())) + remaining);
    }

    private static void section(List<String> lines, String title) {
        line(lines, "[" + title + "]");
    }

    private static void line(List<String> lines, String text) {
        lines.add(ascii(text));
    }

    private static String renderValue(Object value, boolean includeVectorValues) {
        if (value == null) return "null";
        if (value instanceof double[] values) return vector(values, includeVectorValues);
        if (value instanceof float[] values) return vector(values, includeVectorValues);
        if (value instanceof byte[] values) return "bytes(" + values.length + ", sha256=" + digest(values) + ")";
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> values = new ArrayList<>(length);
            for (int i = 0; i < length; i++) values.add(java.lang.reflect.Array.get(value, i));
            return renderValue(values, includeVectorValues);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> ordered = new TreeMap<>();
            map.forEach((key, item) -> ordered.put(String.valueOf(key), item));
            StringBuilder result = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : ordered.entrySet()) {
                if (!first) result.append(", ");
                first = false;
                result.append(printable(entry.getKey())).append(": ").append(renderValue(entry.getValue(), includeVectorValues));
            }
            return result.append('}').toString();
        }
        if (value instanceof Collection<?> collection) {
            var rendered = collection.stream()
                    .map(item -> renderValue(item, includeVectorValues))
                    .toList();
            if (value instanceof Set<?>) {
                rendered = rendered.stream().sorted().toList();
            }
            return rendered.toString();
        }
        if (value instanceof Instant instant) return instant.toString();
        return printable(String.valueOf(value));
    }

    private static String vector(double[] values, Options options) {
        return vector(values, options.includeVectorValues());
    }

    private static String vector(double[] values, boolean includeValues) {
        if (values == null) return "null";
        if (!includeValues) {
            byte[] bytes = new byte[values.length * Double.BYTES];
            for (int i = 0; i < values.length; i++) {
                long bits = Double.doubleToLongBits(values[i]);
                for (int b = 0; b < Double.BYTES; b++) bytes[i * Double.BYTES + b] = (byte) (bits >>> (b * 8));
            }
            return "vector(dim=" + values.length + ", sha256=" + digest(bytes) + ")";
        }
        return Arrays.toString(values);
    }

    private static String vector(float[] values, boolean includeValues) {
        if (values == null) return "null";
        return includeValues ? Arrays.toString(values)
                : "vector(dim=" + values.length + ", values=elided)";
    }

    private static String printable(Object value) {
        return value == null ? "null" : ascii(String.valueOf(value));
    }

    private static String ascii(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\n' || ch == '\r' || ch == '\t') {
                out.append(ch == '\n' ? "\\n" : ch == '\r' ? "\\r" : "\\t");
            } else if (ch >= 0x20 && ch <= 0x7e) {
                out.append(ch);
            } else {
                out.append(String.format("\\u%04x", (int) ch));
            }
        }
        return out.toString();
    }

    private static String digest(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] renderPage(List<String> lines, Options options) throws IOException {
        int lineHeight = 18;
        int padding = 24;
        int rows = Math.max(1, lines.size());
        int width = Math.max(800, options.columns() * 9 + padding * 2);
        int height = Math.max(120, rows * lineHeight + padding * 2);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int y = padding + 14;
            for (String line : lines) {
                graphics.drawString(line, padding, y);
                y += lineHeight;
            }
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) {
            throw new IOException("No PNG writer is available");
        }
        return bytes.toByteArray();
    }
}
