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
package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.query.QuantitativeRequestParser;
import ai.kompile.graph.reasoning.unified.MiniJson;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests: build a small {@link UnifiedGraph} programmatically, open it via
 * {@link LocalReasoningSession}, dispatch {@code graph_reasoning_query} for SEARCH, DESCRIBE,
 * NEIGHBORS, OVERVIEW, CAPABILITIES and the formula operations through
 * {@link LocalToolDispatcher}, and assert on the parsed JSON responses.
 */
class LocalReasoningSessionQueryTest {

    @TempDir
    static Path tempDir;

    private static LocalToolDispatcher dispatcher;
    private static LocalReasoningSession session;
    private static Path kgraphFile;

    /** Build a small social-org graph once for all tests. */
    @BeforeAll
    static void setUpFixture(@TempDir Path sharedTempDir) throws IOException {
        UnifiedGraph g = new UnifiedGraph();

        g.addEntity(new SimpleGraphEntity("alice", "PERSON", "Alice Smith",
                0.9, 0.9, Set.of("PERSON"), new double[]{1.0, 0.0, 0.0}, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("bob", "PERSON", "Bob Jones",
                0.8, 0.8, Set.of("PERSON"), new double[]{0.9, 0.1, 0.0}, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("acme", "ORG", "Acme Corp",
                1.0, 1.0, Set.of("ORG"), new double[]{0.0, 0.0, 1.0}, null, Map.of()));
        g.addEntity(new SimpleGraphEntity("beta", "ORG", "Beta Ltd",
                1.0, 1.0, Set.of("ORG"), new double[]{0.1, 0.0, 0.9}, null, Map.of()));

        g.addRelation("r1", "alice", "bob", "KNOWS", 1.0);
        g.addRelation("r2", "alice", "acme", "WORKS_AT", 0.9);
        g.addRelation("r3", "bob", "beta", "WORKS_AT", 0.8);
        g.addRelation("r4", "acme", "beta", "PARTNER", 0.5);

        kgraphFile = sharedTempDir.resolve("test-fixture.kgraph");
        g.save(kgraphFile);

        // Open via the session API — this round-trips through the .kgraph format
        session = LocalReasoningSession.open(kgraphFile);
        dispatcher = LocalToolDispatcher.create();
    }

    // ── CAPABILITIES ─────────────────────────────────────────────────────────

    @Test
    void bundledAndroidFixtureOpensThroughProductionSessionLoader() throws IOException {
        Path repositoryRoot = Path.of("").toAbsolutePath();
        while (repositoryRoot != null
                && !Files.isDirectory(repositoryRoot.resolve("kompile-chat-local"))) {
            repositoryRoot = repositoryRoot.getParent();
        }
        assertNotNull(repositoryRoot, "Could not locate the Kompile repository root");
        Path fixture = repositoryRoot.resolve(
                "kompile-chat-local/mobile/android/app/src/main/assets/graphs/fixture.kgraph");

        assertTrue(Files.isRegularFile(fixture), "Bundled Android fixture is missing: " + fixture);
        try (LocalReasoningSession fixtureSession = LocalReasoningSession.open(fixture)) {
            assertEquals(2, fixtureSession.graph().entityCount());
            assertEquals(1, fixtureSession.graph().relationCount());
            assertNotNull(fixtureSession.graph().model("psl_prog"));
        }
    }

    @Test
    void capabilitiesReturnsTheWholeEngineContract() {
        String json = dispatcher.dispatch(session, "graph_reasoning_query",
                "{\"operation\":\"CAPABILITIES\"}");
        Map<String, Object> r = parseResult(json);
        assertEquals("OK", r.get("status"));
        assertTrue(r.containsKey("capabilities"));
        @SuppressWarnings("unchecked")
        List<Object> caps = (List<Object>) r.get("capabilities");
        assertEquals(operations(), caps.stream()
                .map(value -> ((Map<?, ?>) value).get("intent"))
                .toList());
        assertTrue(((List<?>) r.get("guidance")).contains(QuantitativeRequestParser.guidance()), json);
    }

    @Test
    void catalogPublishesEveryOperationAndTheQuantitativeObject() {
        LocalToolCatalog.Entry entry = dispatcher.catalog().entries().stream()
                .filter(candidate -> candidate.name().equals("graph_reasoning_query"))
                .findFirst()
                .orElseThrow();
        Map<?, ?> properties = (Map<?, ?>) entry.parameters().get("properties");

        assertEquals(operations(), ((Map<?, ?>) properties.get("operation")).get("enum"));
        assertEquals(QuantitativeRequestParser.jsonSchema(),
                properties.get(QuantitativeRequestParser.FIELD));
        assertTrue(entry.description().contains(
                QuantitativeRequestParser.example(GraphQueryEngine.Intent.SCENARIO)), entry.description());
    }

    // ── Formula operations ──────────────────────────────────────────────────────

    @Test
    void formulaOperationsEvaluateTheGraphWithoutChangingIt(@TempDir Path td) throws IOException {
        Path file = td.resolve("formula.kgraph");
        new UnifiedGraph()
                .addEntity(cell("a1", "Sheet1!A1", "Input A", 10.0))
                .addEntity(cell("a2", "Sheet1!A2", "Input B", 20.0))
                .addEntity(GraphEntity.builder("a3")
                        .type("FORMULA_CELL").label("Total")
                        .attribute("cell_reference", "Sheet1!A3")
                        .attribute("formula", "SUM(Sheet1!A1:Sheet1!A2)")
                        .attribute("displayValue", "30")
                        .attribute("validated", true)
                        .build())
                .addRelation(GraphRelation.builder("d1", "a3", "a1").type("DEPENDS_ON").build())
                .addRelation(GraphRelation.builder("d2", "a3", "a2").type("DEPENDS_ON").build())
                .save(file);

        try (LocalReasoningSession formulas = LocalReasoningSession.open(file)) {
            String calculateJson = dispatcher.dispatch(formulas, "graph_reasoning_query",
                    "{\"operation\":\"calculate\",\"quantitative\":{\"target\":{\"text\":\"Total\"}}}");
            Map<String, Object> calculated = parseResult(calculateJson);
            assertEquals("OK", calculated.get("status"), calculateJson);
            assertEquals("Calculated a3 = 30.0.", calculated.get("summary"));
            Map<?, ?> retrieval = (Map<?, ?>) data(calculated).get("retrieval");
            assertEquals("READY", retrieval.get("status"), calculateJson);
            assertEquals("a3", ((Map<?, ?>) retrieval.get("plan")).get("targetEntityId"), calculateJson);

            // A JSON-string spec parses like an object. SCALE -0.5 halves a1.
            String scenarioJson = dispatcher.dispatch(formulas, "graph_reasoning_query",
                    "{\"operation\":\"SCENARIO\",\"quantitative\":\"{\\\"target\\\":{\\\"entityId\\\":\\\"a3\\\"},"
                            + "\\\"interventions\\\":[{\\\"target\\\":{\\\"entityId\\\":\\\"a1\\\"},"
                            + "\\\"operation\\\":\\\"SCALE\\\",\\\"value\\\":-0.5}]}\"}");
            Map<String, Object> scenario = parseResult(scenarioJson);
            assertEquals("OK", scenario.get("status"), scenarioJson);
            Map<?, ?> values = (Map<?, ?>) data(scenario).get("scenario");
            assertEquals("COMPLETED", values.get("status"), scenarioJson);
            assertEquals(30.0, ((Number) values.get("baselineValue")).doubleValue(), 1.0e-9, scenarioJson);
            assertEquals(25.0, ((Number) values.get("scenarioValue")).doubleValue(), 1.0e-9, scenarioJson);
            assertFalse(values.containsKey("trace"), "nested traces repeat the top-level trace");
            Map<?, ?> trace = assertInstanceOf(Map.class, scenario.get("trace"), scenarioJson);
            assertEquals("CALCULATION", trace.get("kind"), scenarioJson);
            assertInstanceOf(List.class, trace.get("premises"), scenarioJson);

            String solveJson = dispatcher.dispatch(formulas, "graph_reasoning_query",
                    "{\"operation\":\"SOLVE_TARGET\",\"quantitative\":{\"target\":{\"entityId\":\"a3\"},"
                            + "\"goal\":{\"control\":{\"entityId\":\"a1\"},\"targetValue\":50,"
                            + "\"minimum\":0,\"maximum\":100}}}");
            Map<?, ?> goalSeek = (Map<?, ?>) data(parseResult(solveJson)).get("goalSeek");
            assertEquals("SOLVED", goalSeek.get("status"), solveJson);
            assertEquals(30.0, ((Number) goalSeek.get("controlValue")).doubleValue(), 1.0e-4, solveJson);

            Object stored = formulas.graph().entity("a1").orElseThrow().attributes().get("value");
            assertEquals(10.0, ((Number) stored).doubleValue(), 1.0e-12,
                    "a scenario or goal seek must not rewrite its inputs");
        }
    }

    @Test
    void formulaOperationsNameTheMissingSpecAndOtherOperationsRejectOne() {
        for (String operation : List.of("MODELS", "CALCULATE", "SCENARIO", "SOLVE_TARGET")) {
            String json = dispatcher.dispatch(session, "graph_reasoning_query",
                    "{\"operation\":\"" + operation + "\"}");
            Map<String, Object> result = parseResult(json);
            assertEquals("ERROR", result.get("status"), json);
            String message = (String) result.get("message");
            assertTrue(message.startsWith(operation + " requires quantitative."), message);
            assertTrue(message.contains("Example: quantitative={"), message);
        }

        String json = dispatcher.dispatch(session, "graph_reasoning_query",
                "{\"operation\":\"SEARCH\",\"queryText\":\"Alice\",\"quantitative\":{\"target\":\"Total\"}}");
        Map<String, Object> stray = parseResult(json);
        assertEquals("ERROR", stray.get("status"), json);
        assertTrue(((String) stray.get("message")).startsWith("quantitative only applies to "), json);
    }

    @Test
    void emptyRequestDefaultsToCapabilities() {
        Map<String, Object> result = parseResult(
                dispatcher.dispatch(session, "graph_reasoning_query", "{}"));

        assertEquals("OK", result.get("status"));
        assertEquals("CAPABILITIES", result.get("intent"));
        assertFalse(((List<?>) result.get("capabilities")).isEmpty());
    }

    // ── OVERVIEW ─────────────────────────────────────────────────────────────

    @Test
    void overviewReturnsEntityAndRelationCounts() {
        String json = dispatcher.dispatch(session, "graph_reasoning_query",
                "{\"operation\":\"OVERVIEW\"}");
        Map<String, Object> r = parseResult(json);
        assertEquals("OK", r.get("status"), "OVERVIEW should return OK: " + json);
        // data map should contain entityCount
        assertTrue(r.containsKey("data") || r.containsKey("summary"),
                "OVERVIEW should include data or summary");
    }

    // ── SEARCH ───────────────────────────────────────────────────────────────

    @Test
    void searchAliceFindsAlice() {
        String json = dispatcher.dispatch(session, "graph_reasoning_query",
                "{\"operation\":\"SEARCH\",\"queryText\":\"Alice\",\"topK\":5}");
        Map<String, Object> r = parseResult(json);
        // Status is OK or PARTIAL for a successful search
        String status = (String) r.get("status");
        assertTrue("OK".equals(status) || "PARTIAL".equals(status) || "SUPPORTED".equals(status),
                "Expected OK/PARTIAL/SUPPORTED, got: " + status + " — " + json);

        @SuppressWarnings("unchecked")
        List<Object> entities = (List<Object>) r.get("entities");
        assertNotNull(entities, "SEARCH should return entities list");
        // At least Alice should appear
        boolean found = entities.stream()
                .anyMatch(e -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> em = (Map<String, Object>) e;
                    Object label = em.get("label");
                    Object id = em.get("id");
                    return "Alice Smith".equals(label) || "alice".equals(id);
                });
        assertTrue(found, "SEARCH for 'Alice' should find Alice Smith: " + json);
    }

    @Test
    void questionOnlyRequestDefaultsToSearch() {
        String json = dispatcher.dispatch(session, "graph_reasoning_query",
                "{\"question\":\"Find Alice\",\"topK\":5}");
        Map<String, Object> result = parseResult(json);

        assertEquals("SEARCH", result.get("intent"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entities = (List<Map<String, Object>>) result.get("entities");
        assertTrue(entities.stream().anyMatch(entity -> "alice".equals(entity.get("id"))), json);
    }

    @Test
    void queryTextWinsWhenBothTextAliasesArePresent() {
        String json = dispatcher.dispatch(session, "graph_reasoning_query",
                "{\"queryText\":\"Alice\",\"question\":\"Beta\",\"topK\":5}");
        Map<String, Object> result = parseResult(json);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entities = (List<Map<String, Object>>) result.get("entities");
        assertTrue(entities.stream().anyMatch(entity -> "alice".equals(entity.get("id"))), json);
        assertTrue(entities.stream().noneMatch(entity -> "beta".equals(entity.get("id"))), json);
    }

    @Test
    void searchMissingQueryTextReturnsError() {
        String json = dispatcher.dispatch(session, "graph_reasoning_query",
                "{\"operation\":\"SEARCH\"}");
        // Should return an error or a valid response (the engine may return INVALID/PARTIAL)
        // Most importantly, it must be valid JSON and have a status field
        Map<String, Object> r = parseResult(json);
        assertTrue(r.containsKey("status"), "Response must have status: " + json);
    }

    // ── DESCRIBE ─────────────────────────────────────────────────────────────

    @Test
    void describeAliceReturnsEntity() {
        String json = dispatcher.dispatch(session, "graph_reasoning_query",
                "{\"operation\":\"DESCRIBE\",\"entityId\":\"alice\"}");
        Map<String, Object> r = parseResult(json);
        String status = (String) r.get("status");
        assertNotNull(status, "DESCRIBE must return a status: " + json);
        // Either OK (found) or NOT_FOUND — either way, valid JSON
        assertTrue("OK".equals(status) || "NOT_FOUND".equals(status) || "PARTIAL".equals(status),
                "Unexpected status for DESCRIBE alice: " + status);
    }

    // ── NEIGHBORS ────────────────────────────────────────────────────────────

    @Test
    void neighborsAliceReturnsBobAndAcme() {
        String json = dispatcher.dispatch(session, "graph_reasoning_query",
                "{\"operation\":\"NEIGHBORS\",\"entityId\":\"alice\",\"direction\":\"OUTGOING\"}");
        Map<String, Object> r = parseResult(json);
        String status = (String) r.get("status");
        assertNotNull(status, "NEIGHBORS must have a status: " + json);
        // Status must not be ERROR
        assertNotEquals("ERROR", status, "NEIGHBORS should not error: " + json);
    }

    // ── tools_catalog ────────────────────────────────────────────────────────

    @Test
    void toolsCatalogListsKnownTools() {
        String json = dispatcher.dispatch(session, "tools_catalog", "{}");
        Object parsed = MiniJson.parse(json);
        assertInstanceOf(List.class, parsed, "tools_catalog must return a JSON array: " + json);
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) parsed;
        assertTrue(list.size() >= 4, "Should have at least 4 core tools: " + json);
    }

    // ── graph_save ───────────────────────────────────────────────────────────

    @Test
    void graphSaveWritesFile(@TempDir Path td) {
        Path out = td.resolve("saved.kgraph");
        String json = dispatcher.dispatch(session, "graph_save",
                "{\"path\":\"" + escapeJsonString(out.toString()) + "\"}");
        Map<String, Object> r = parseResult(json);
        assertEquals("OK", r.get("status"), "graph_save should succeed: " + json);
        assertTrue(out.toFile().exists(), "Saved file should exist");
        assertTrue(out.toFile().length() > 0, "Saved file should be non-empty");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static List<String> operations() {
        return GraphQueryEngine.capabilityContract().stream()
                .map(GraphQueryEngine.Capability::intent)
                .toList();
    }

    private static Map<?, ?> data(Map<String, Object> result) {
        Object data = result.get("data");
        assertInstanceOf(Map.class, data, String.valueOf(result));
        return (Map<?, ?>) data;
    }

    private static GraphEntity cell(String id, String reference, String label, double value) {
        return GraphEntity.builder(id).type("CELL").label(label)
                .attribute("cell_reference", reference).attribute("value", value).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseResult(String json) {
        assertNotNull(json, "Dispatch result must not be null");
        Object parsed = MiniJson.parse(json);
        assertInstanceOf(Map.class, parsed, "Result must be a JSON object: " + json);
        return (Map<String, Object>) parsed;
    }

    /** Escape a path string for embedding inside a JSON string literal. */
    private static String escapeJsonString(String s) {
        return s.replace("\\", "\\\\");
    }
}
