/*
 * Copyright 2025 Kompile Inc.
 */
package ai.kompile.graph.reasoning.debug;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedGraphDebugRendererTest {

    @Test
    void asciiIncludesPropertiesConnectionsAndObservedSchema() {
        UnifiedGraph graph = sample();

        String output = UnifiedGraphDebugRenderer.toAscii(graph);

        assertTrue(output.startsWith("KGRAPH DEBUG"));
        assertTrue(output.contains("[OBSERVED SCHEMA]"));
        assertTrue(output.contains("[DECLARED SCHEMAS]"));
        assertTrue(output.contains("SCHEMA schema/declared.json"));
        assertTrue(output.contains("content[0] = {\"kind\":\"declared\"}"));
        assertTrue(output.contains("entityPropertyPaths"));
        assertTrue(output.contains("attributes.nested.child"));
        assertTrue(output.contains("NODE n1"));
        assertTrue(output.contains("attributes.note = hello"));
        assertTrue(output.contains("attributes.ordered = [first, second]"));
        assertTrue(output.contains("attributes.nested = {child: value}"));
        assertTrue(output.contains("invalid: DateTimeParseException"));
        assertTrue(output.contains("outConnection e1 -> KNOWS"));
        assertTrue(output.contains("inConnection e1 <- KNOWS"));
        assertTrue(output.contains("RELATION e1"));
        assertTrue(output.contains("sourceId = n1"));
        assertTrue(output.contains("vector(dim=2"));
        assertTrue(output.contains("declaredSchemaArtifacts"));
        assertFalse(output.contains("\u0000"));
    }

    @Test
    void asciiIsReadableLineOrientedAndKeepsSectionsInGraphOrder() {
        String output = UnifiedGraphDebugRenderer.toAscii(sample());
        String[] lines = output.split("\\R", -1);

        assertTrue(lines.length > 30);
        assertTrue(indexOf(lines, "[GRAPH META]") < indexOf(lines, "[OBSERVED SCHEMA]"));
        assertTrue(indexOf(lines, "[OBSERVED SCHEMA]") < indexOf(lines, "[DECLARED SCHEMAS]"));
        assertTrue(indexOf(lines, "[DECLARED SCHEMAS]") < indexOf(lines, "[NODES]"));
        assertTrue(indexOf(lines, "[NODES]") < indexOf(lines, "[RELATIONS]"));
        assertTrue(indexOf(lines, "[RELATIONS]") < indexOf(lines, "[VECTOR LAYERS]"));
        assertTrue(indexOf(lines, "[VECTOR LAYERS]") < indexOf(lines, "[WEIGHT MAPS]"));
        assertTrue(indexOf(lines, "[WEIGHT MAPS]") < indexOf(lines, "[ARTIFACTS]"));

        assertTrue(Arrays.stream(lines)
                .filter(line -> !line.isEmpty())
                .allMatch(line -> line.length() <= UnifiedGraphDebugRenderer.DEFAULT_COLUMNS));
        assertTrue(Arrays.stream(lines)
                .filter(line -> !line.isEmpty())
                .flatMapToInt(String::chars)
                .allMatch(codePoint -> codePoint >= 0x20 && codePoint <= 0x7e));
        assertFalse(output.contains("content[1] ="), "a trailing schema newline is not a phantom record");
    }

    @Test
    void asciiWrapsLongPropertiesWithoutLosingThePropertyPrefix() {
        String prefix = "  attributes.longText = ";
        UnifiedGraph graph = new UnifiedGraph().graphId("wrap-test")
                .addEntity(GraphEntity.builder("long")
                        .attribute("longText", "word ".repeat(50).trim())
                        .build());

        String[] lines = UnifiedGraphDebugRenderer.toAscii(graph,
                        new UnifiedGraphDebugRenderer.Options(false, true, 72, 70))
                .split("\\R", -1);
        int first = indexStartingWith(lines, prefix);

        assertTrue(first >= 0);
        assertTrue(first + 1 < lines.length);
        assertTrue(lines[first + 1].startsWith(" ".repeat(prefix.length())));
        assertTrue(Arrays.stream(lines)
                .filter(line -> !line.isEmpty())
                .allMatch(line -> line.length() <= 72));
    }

    @Test
    void asciiShowsPopulatedVectorWeightAndOpinionSectionsDeterministically() {
        UnifiedGraph graph = sample()
                .putEntityVector("semantic", "n1", new double[] {3.0, 4.0})
                .putRelationVector("relationSemantic", "e1", new double[] {5.0, 6.0})
                .putWeightMap("psl", Map.of("rule.z", 0.2, "rule.a", 0.8))
                .putEntityOpinion("n1", Opinion.fromSoftTruth(0.8, 4))
                .putRelationOpinion("e1", Opinion.fromSoftTruth(0.6, 2));

        String output = UnifiedGraphDebugRenderer.toAscii(graph);

        assertTrue(output.contains("semantic target=ENTITY size=1 dimension=2"));
        assertTrue(output.contains("row.n1 = vector(dim=2"));
        assertTrue(output.contains("relationSemantic target=RELATION size=1 dimension=2"));
        assertTrue(output.contains("row.e1 = vector(dim=2"));
        assertTrue(output.contains("psl.rule.a = 0.8"));
        assertTrue(output.contains("psl.rule.z = 0.2"));
        assertTrue(output.indexOf("psl.rule.a =") < output.indexOf("psl.rule.z ="));
        assertTrue(output.contains("opinion = Opinion[belief="));
    }

    @Test
    void asciiFlagsDanglingConnectionsAndEscapesControlCharacters() {
        UnifiedGraph graph = new UnifiedGraph().graphId("diagnostic-test")
                .addEntity(GraphEntity.builder("n\u0001").label("bad\nlabel").build())
                .addRelation(GraphRelation.builder("broken", "n\u0001", "missing\u0007")
                        .type("BROKEN")
                        .build());

        String output = UnifiedGraphDebugRenderer.toAscii(graph);

        assertTrue(output.contains("NODE n" + "\\u0001"));
        String escapedNewline = "\\" + "n";
        assertTrue(output.contains("label = bad" + escapedNewline + "label"));
        assertTrue(output.contains("entityTypes.<empty> = 1"));
        assertTrue(output.contains("diagnostic = dangling endpoint"));
        assertFalse(output.contains("\u0001"));
        assertFalse(output.contains("\u0007"));
    }

    @Test
    void asciiEscapesDynamicKeysAndNestedMapKeys() {
        String fieldKey = "bad\n\u2603";
        String nestedKey = "inner\t\u0001";
        String escapedFieldKey = "bad\\n\\u2603";
        String escapedNestedKey = "inner\\t\\u0001";
        String escapedValue = "v\\u2603";

        UnifiedGraph graph = new UnifiedGraph()
                .meta(fieldKey, Map.of(nestedKey, "v\u2603"))
                .addEntity(GraphEntity.builder("node")
                        .attribute(fieldKey, Map.of(nestedKey, "v\u2603"))
                        .build());

        String output = UnifiedGraphDebugRenderer.toAscii(graph);

        assertTrue(output.contains("meta." + escapedFieldKey + " = {" + escapedNestedKey
                + ": " + escapedValue + "}"));
        assertTrue(output.contains("attributes." + escapedFieldKey + " = {" + escapedNestedKey
                + ": " + escapedValue + "}"));
        assertTrue(output.contains("entityAttributeKeys." + escapedFieldKey + " = 1"));
        assertTrue(output.contains("entityPropertyPaths.attributes." + escapedFieldKey + " = 1"));
        assertFalse(output.contains(fieldKey));
        assertFalse(output.contains(nestedKey));
        assertTrue(output.chars().allMatch(codePoint -> codePoint >= 0x20 && codePoint <= 0x7e
                || codePoint == '\n'));
    }

    @Test
    void vectorValuesCanBeEnabledAndOutputIsDeterministic() {
        UnifiedGraph graph = sample();
        UnifiedGraphDebugRenderer.Options options = UnifiedGraphDebugRenderer.Options.defaults()
                .withVectorValues(true);

        String first = UnifiedGraphDebugRenderer.toAscii(graph, options);
        String second = UnifiedGraphDebugRenderer.toAscii(graph, options);

        assertArrayEquals(first.getBytes(StandardCharsets.US_ASCII),
                second.getBytes(StandardCharsets.US_ASCII));
        assertTrue(first.contains("[1.0, 2.0]"));
    }

    @Test
    void emptyGraphMakesEmptySectionsExplicit() {
        String output = UnifiedGraphDebugRenderer.toAscii(new UnifiedGraph());

        assertTrue(output.contains("[NODES]\n  (none)"));
        assertTrue(output.contains("[RELATIONS]\n  (none)"));
        assertTrue(output.contains("[VECTOR LAYERS]\n  (none)"));
        assertTrue(output.contains("[WEIGHT MAPS]\n  (none)"));
        assertTrue(output.contains("[ARTIFACTS]\n  (none)"));
    }

    @Test
    void pngBundleContainsEveryPageAndManifestDeterministically() throws Exception {
        UnifiedGraphDebugRenderer.Options options =
                new UnifiedGraphDebugRenderer.Options(false, true, 80, 10);
        byte[] first = UnifiedGraphDebugRenderer.toPngBundle(sample(), options);
        byte[] second = UnifiedGraphDebugRenderer.toPngBundle(sample(), options);
        assertArrayEquals(first, second);

        Set<String> entries = new HashSet<>();
        int pngPages = 0;
        String manifest = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(first))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
                if (entry.getName().endsWith(".png")) {
                    pngPages++;
                } else if ("render-manifest.txt".equals(entry.getName())) {
                    manifest = new String(zip.readAllBytes(), StandardCharsets.US_ASCII);
                }
            }
        }
        assertTrue(entries.contains("render-manifest.txt"));
        assertTrue(pngPages > 1);
        assertTrue(manifest != null && manifest.contains("pages=" + pngPages + "\n"));
        assertTrue(manifest.contains("columns=80\n"));
        assertTrue(manifest.contains("pageLines=10\n"));
    }

    private static UnifiedGraph sample() {
        UnifiedGraph graph = new UnifiedGraph().graphId("debug-test").factSheetId(42L);
        graph.putArtifactText("schema/declared.json", "{\"kind\":\"declared\"}\n");
        graph.addEntity(GraphEntity.builder("n1")
                .type("PERSON")
                .label("Alice")
                .attribute("note", "hello")
                .attribute("entityType", "Person")
                .attribute("nested", java.util.Map.of("child", "value"))
                .attribute("ordered", java.util.List.of("first", "second"))
                .attribute("validFrom", "not-an-instant")
                .embedding(new double[] {1.0, 2.0})
                .build());
        graph.addEntity(GraphEntity.builder("n2")
                .type("PERSON")
                .label("Bob")
                .build());
        graph.addRelation(GraphRelation.builder("e1", "n1", "n2")
                .type("KNOWS")
                .attribute("source", "fixture")
                .build());
        return graph;
    }

    private static int indexOf(String[] lines, String expected) {
        return indexStartingWith(lines, expected);
    }

    private static int indexStartingWith(String[] lines, String prefix) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith(prefix)) return i;
        }
        return -1;
    }
}
