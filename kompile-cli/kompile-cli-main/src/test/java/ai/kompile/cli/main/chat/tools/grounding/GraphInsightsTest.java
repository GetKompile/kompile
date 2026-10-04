/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.insights.InsightReport;
import ai.kompile.cli.insights.InsightsConfig;
import ai.kompile.cli.insights.InsightsQuery;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The {@code insights} graph report draws the relations around one node of a project knowledge
 * graph or a server fact sheet, in text and as a chart the web card renders.
 */
@TemporaryUserHome
class GraphInsightsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");
    private static final InsightsConfig CONFIG = InsightsConfig.defaults();
    private static final String SERVER_URL = "http://graph.test";
    private static final String FACT_SHEETS = """
            [{"id":1,"name":"Global","isActive":true,"factCount":10},
             {"id":3,"name":"Finance","isActive":false,"factCount":0}]
            """;
    private static final String GLOBAL_LAYERS = """
            {"nodes":[{"nodeId":"n1","nodeType":"METRIC","label":"Revenue"},
                      {"nodeId":"n2","nodeType":"PERIOD","label":"Q3 2026"}],
             "edges":[{"sourceNodeId":"n1","targetNodeId":"n2","edgeType":"RELATION","relationType":"reportedIn"}]}
            """;

    @TempDir
    Path project;

    @Test
    void theMostConnectedNodeIsDrawnWithItsRelations() throws IOException {
        writePeople(project);
        Map<String, String> before = CrawlInsightsTest.tree(project);

        InsightReport report = local(project).report(query("show me the graph"));

        assertEquals(before, CrawlInsightsTest.tree(project), "reading a graph must not write to the project");
        assertEquals("Graph People: 4 entities, 4 relations, 3 predicates; around Alice (PERSON)", report.getHeadline());
        String text = report.getText();
        assertTrue(text.contains("Source: knowledge base people of this project, "), text);
        assertTrue(text.contains("graph.kgraph"), text);
        assertTrue(text.contains("\nRelations around the most connected node (3 relations); name another node in "
                + "quotes to center on it:\n"), text);
        assertTrue(text.contains("""
                Alice (PERSON)
                ├─ knows → Bob (PERSON)
                ├─ worksFor → Acme (ORGANIZATION)
                └─ ← manages ─ Carol (PERSON)
                """), text);
        assertTrue(hasRow(text, "worksFor", "2", "50.0%"), text);
        assertTrue(hasRow(text, "knows", "1", "25.0%"), text);
        assertTrue(hasRow(text, "manages", "1", "25.0%"), text);
        assertTrue(hasRow(text, "PERSON", "3", "75.0%"), text);
        assertTrue(hasRow(text, "ORGANIZATION", "1", "25.0%"), text);
        assertTrue(text.indexOf("worksFor  ") < text.indexOf("knows  "), "most used predicate first: " + text);
        assertFalse(text.contains("Knowledge graphs (* shown):"), "one graph needs no list: " + text);
        assertFalse(text.contains("time window"), text);

        JsonNode chart = report.getChart();
        assertEquals("graph", chart.path("kind").asText());
        assertEquals("Around Alice in People", chart.path("title").asText());
        assertEquals("people", chart.path("factSheet").path("id").asText());
        assertEquals("People", chart.path("factSheet").path("name").asText());
        assertEquals("alice", chart.path("focus").asText());
        assertEquals(4, chart.path("nodes").size());
        assertEquals(3, chart.path("edges").size());
        assertEquals(0, chart.path("omitted").asInt());
        assertFalse(chart.has("link"), "a project graph has no page in the chat app to open");
    }

    @Test
    void aNodeTheQuestionNamesIsTheCenter() throws IOException {
        writePeople(project);

        InsightReport named = local(project).report(query("what is connected to Bob"));

        assertEquals("Graph People: 4 entities, 4 relations, 3 predicates; around Bob (PERSON)", named.getHeadline());
        assertTrue(named.getText().contains("""
                Relations around the node the question names:
                Bob (PERSON)
                ├─ worksFor → Acme (ORGANIZATION)
                └─ ← knows ─ Alice (PERSON)
                """), named.getText());
        assertEquals("bob", named.getChart().path("focus").asText());

        // A plain word names a node only when quoted or marked, so "is Bob ..." keeps the default.
        InsightReport unmarked = local(project).report(query("is Bob in the graph"));
        assertEquals("Graph People: 4 entities, 4 relations, 3 predicates; around Alice (PERSON)",
                unmarked.getHeadline());

        InsightReport quoted = local(project).report(query("show \"Carol\""));
        assertTrue(quoted.getHeadline().endsWith("; around Carol (PERSON)"), quoted.getHeadline());
    }

    @Test
    void aNumberInTheTimeWindowIsNotANodeName() throws IOException {
        Path crawl = Files.createDirectories(project.resolve("data/crawls/numbers"));
        writeSummary(crawl, "Numbers", 3, 2);
        new UnifiedGraph().graphId("local:test:numbers")
                .addEntity("hub", "THING", "Hub")
                .addEntity("17", "THING", "17")
                .addEntity("42", "THING", "42")
                .addRelation("e1", "hub", "17", "has", 1.0)
                .addRelation("e2", "hub", "42", "has", 1.0)
                .save(crawl.resolve("graph.kgraph"));

        InsightReport report = local(project).report(query("graph changes in the last 17 days"));

        assertEquals("Graph Numbers: 3 entities, 2 relations, 1 predicate; around Hub (THING)", report.getHeadline());
        assertTrue(report.getText().contains(
                "Graphs are shown as they are now; the time window does not apply to them."), report.getText());
    }

    @Test
    void anUnreadableGraphIsANoteAndTheOthersStillShow() throws IOException {
        writePeople(project);
        Path broken = Files.createDirectories(project.resolve("data/crawls/broken"));
        Files.write(broken.resolve("graph.kgraph"), "not a graph".getBytes(StandardCharsets.UTF_8));

        InsightReport report = local(project).report(query("show the graph"));

        assertEquals("Graph People: 4 entities, 4 relations, 3 predicates; around Alice (PERSON)", report.getHeadline());
        assertTrue(report.getText().contains("Knowledge base broken: Skipped unreadable legacy graph: "),
                report.getText());
    }

    @Test
    void aSessionQuestionCoversEveryGraph() throws IOException {
        writePeople(project);

        InsightReport report = local(project).report(
                InsightsQuery.parse(null, "graph for this session", List.of("session-1"), NOW, ZoneOffset.UTC, 7));

        assertTrue(report.getHeadline().startsWith("Graph People: "), report.getHeadline());
        assertTrue(report.getText().contains(
                "Knowledge graphs are not kept per chat session, so this covers all of them."), report.getText());
        assertFalse(report.getText().contains("time window"), report.getText());
    }

    @Test
    void aServerFactSheetIsDrawnWithALinkToTheGraphPage() throws IOException {
        writePeople(project);
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        server.expect(requestTo(SERVER_URL + GraphInsights.FACT_SHEETS_PATH))
                .andRespond(withSuccess(FACT_SHEETS, MediaType.APPLICATION_JSON));
        server.expect(requestTo(SERVER_URL + "/api/graph/1/reasoning-layers"))
                .andRespond(withSuccess(GLOBAL_LAYERS, MediaType.APPLICATION_JSON));

        InsightReport report = withServer(project, rt).report(query("show me the graph"));

        server.verify();
        assertEquals("Graph Global: 2 entities, 1 relation, 1 predicate; around Revenue (METRIC)", report.getHeadline());
        String text = report.getText();
        assertTrue(text.contains("Source: fact sheet 1 on the graph server " + SERVER_URL + "\n"), text);
        assertTrue(text.contains("Revenue (METRIC)\n└─ reportedIn → Q3 2026 (PERIOD)\n"), text);
        assertTrue(text.contains("\nKnowledge graphs (* shown):\n"), text);
        List<String> lines = text.lines().map(String::trim).toList();
        int global = indexOfRow(lines, "*", "Global", "2", "1", "10", "server");
        int people = indexOfRow(lines, "People", "4", "4", "-", "local");
        int finance = indexOfRow(lines, "Finance", "-", "-", "0", "server");
        assertTrue(global >= 0 && global < people && people < finance,
                "the shown graph first, then the largest: " + text);
        assertEquals("#/graph?factSheetId=1&focusNode=n1", report.getChart().path("link").asText());
        assertEquals("1", report.getChart().path("factSheet").path("id").asText());
    }

    @Test
    void aFactSheetNamedByNumberThatCannotBeReadSaysWhy() throws IOException {
        writePeople(project);
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        server.expect(requestTo(SERVER_URL + GraphInsights.FACT_SHEETS_PATH))
                .andRespond(withSuccess(FACT_SHEETS, MediaType.APPLICATION_JSON));
        server.expect(requestTo(SERVER_URL + "/api/graph/3/reasoning-layers"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("{\"error\":\"Reasoning layers unavailable: graph store is down\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        InsightReport report = withServer(project, rt).report(query("show fact sheet 3"));

        server.verify();
        assertEquals("Graph Finance: not available", report.getHeadline());
        assertNull(report.getChart());
        assertTrue(report.getText().contains("The graph of Finance could not be read (the graph server answered "
                + "HTTP 503: Reasoning layers unavailable: graph store is down)."), report.getText());
        assertTrue(report.getText().contains("Source: fact sheet 3 on the graph server " + SERVER_URL),
                report.getText());
    }

    @Test
    void aGraphServerThatFailsLeavesTheProjectGraphs() throws IOException {
        writePeople(project);
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        server.expect(requestTo(SERVER_URL + GraphInsights.FACT_SHEETS_PATH))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .body("{\"error\":\"down\"}")
                        .contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(SERVER_URL + GraphInsights.FACT_SHEETS_PATH))
                .andRespond(withException(new IOException("connection refused")));
        GraphInsights insights = withServer(project, rt);

        InsightReport failing = insights.report(query("show the graph"));
        InsightReport unreachable = insights.report(query("show the graph"));

        server.verify();
        assertTrue(failing.getHeadline().startsWith("Graph People: "), failing.getHeadline());
        assertTrue(failing.getText().contains("The graph server at " + SERVER_URL
                + " answered HTTP 503 (down), so its fact sheets are missing."), failing.getText());
        assertTrue(unreachable.getHeadline().startsWith("Graph People: "), unreachable.getHeadline());
        assertTrue(unreachable.getText().contains("The graph server at " + SERVER_URL
                + " could not be reached (connection refused), so its fact sheets are missing."), unreachable.getText());
    }

    @Test
    void aGraphTheServerCannotSendSaysWhyInAFewWords() throws IOException {
        writePeople(project);
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        server.expect(requestTo(SERVER_URL + GraphInsights.FACT_SHEETS_PATH))
                .andRespond(withSuccess(FACT_SHEETS, MediaType.APPLICATION_JSON));
        server.expect(requestTo(SERVER_URL + "/api/graph/3/reasoning-layers"))
                .andRespond(withException(new IOException("connection refused")));

        InsightReport report = withServer(project, rt).report(query("show fact sheet 3"));

        server.verify();
        assertEquals("Graph Finance: not available", report.getHeadline());
        assertTrue(report.getText().contains("The graph of Finance could not be read (the graph server could not be "
                + "reached: connection refused)."), report.getText());
    }

    @Test
    void theOverviewLineReadsOnlyTheListings() throws IOException {
        writePeople(project);
        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        server.expect(requestTo(SERVER_URL + GraphInsights.FACT_SHEETS_PATH))
                .andRespond(withSuccess(FACT_SHEETS, MediaType.APPLICATION_JSON));

        String headline = withServer(project, rt).headline(query("overview"));

        server.verify();
        assertEquals("Graphs: 3 knowledge graphs (1 in this project, 2 on the server); largest Global with 10 facts",
                headline);
        assertEquals("Graphs: 1 knowledge graph in this project; largest People with 4 relations",
                local(project).headline(query("overview")));
    }

    @Test
    void noGraphsAtAll() throws IOException {
        assertEquals("Graphs: no knowledge graphs in this project", local(project).headline(query("overview")));
        InsightReport report = local(project).report(query("show the graph"));
        assertEquals("Graphs: no knowledge graphs in this project", report.getHeadline());
        assertNull(report.getChart());

        RestTemplate rt = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(rt);
        server.expect(requestTo(SERVER_URL + GraphInsights.FACT_SHEETS_PATH))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        assertEquals("Graphs: no knowledge graphs", withServer(project, rt).report(query("graphs")).getHeadline());
        server.verify();
    }

    @Test
    void theGraphPageLinkEncodesItsParameters() {
        GraphInsights.Sheet sheet = new GraphInsights.Sheet("12", "x", GraphInsights.SERVER, false, -1, -1, 0, null);
        assertEquals("#/graph?factSheetId=12&focusNode=node%20one%2F%CE%B1", GraphInsights.link(sheet, "node one/α"));
    }

    @Test
    void reasoningLayersKeepTheFirstNodeAndFallBackToTheEdgeType() throws IOException {
        GraphInsights.Graph graph = GraphInsights.layersGraph(MAPPER.readTree("""
                {"nodes":[{"nodeId":"a","nodeType":"T","label":"A"},{"nodeId":"a","nodeType":"U","label":"A2"},
                          {"nodeId":"b"},{"label":"no id"}],
                 "edges":[{"sourceNodeId":"a","targetNodeId":"b","edgeType":"SUBCLASS"},
                          {"sourceNodeId":"a","targetNodeId":"b","edgeType":"RELATION","relationType":"owns"},
                          {"sourceNodeId":"a"}]}
                """));

        assertEquals(List.of("a", "b"), List.copyOf(graph.nodes().keySet()));
        assertEquals("A", graph.nodes().get("a").label());
        assertEquals("T", graph.nodes().get("a").type());
        assertEquals(2, graph.edges().size());
        assertEquals("SUBCLASS", graph.edges().get(0).label());
        assertEquals("owns", graph.edges().get(1).label());
        assertTrue(graph.edges().get(0).directed());
    }

    private static GraphInsights local(Path project) {
        return new GraphInsights(project, null, CONFIG);
    }

    private static GraphInsights withServer(Path project, RestTemplate rt) {
        return new GraphInsights(project, SERVER_URL, new GroundingBackendClient(SERVER_URL, rt), CONFIG);
    }

    private static InsightsQuery query(String question) {
        return InsightsQuery.parse(null, question, null, NOW, ZoneOffset.UTC, 7);
    }

    /** Alice works for Acme and knows Bob, who also works for Acme; Carol manages Alice. */
    private static void writePeople(Path project) throws IOException {
        Path crawl = Files.createDirectories(project.resolve("data/crawls/people"));
        writeSummary(crawl, "People", 4, 4);
        new UnifiedGraph().graphId("local:test:people")
                .addEntity("alice", "PERSON", "Alice")
                .addEntity("acme", "ORGANIZATION", "Acme")
                .addEntity("bob", "PERSON", "Bob")
                .addEntity("carol", "PERSON", "Carol")
                .addRelation("r1", "alice", "acme", "worksFor", 1.0)
                .addRelation("r2", "bob", "acme", "worksFor", 1.0)
                .addRelation("r3", "carol", "alice", "manages", 1.0)
                .addRelation("r4", "alice", "bob", "knows", 1.0)
                .save(crawl.resolve("graph.kgraph"));
    }

    private static void writeSummary(Path crawl, String name, int entities, int relations) throws IOException {
        ObjectNode summary = MAPPER.createObjectNode().put("name", name).put("status", "COMPLETED")
                .put("finishedAt", "2026-10-02T09:00:00Z").put("documentCount", 2).put("chunkCount", 6)
                .put("graphEntityCount", entities).put("graphRelationCount", relations);
        summary.putArray("sources").add(name.toLowerCase() + ".md");
        MAPPER.writeValue(crawl.resolve("crawl-result.json").toFile(), summary);
    }

    /** Whether a table row holds exactly these cells. */
    private static boolean hasRow(String text, String... cells) {
        return indexOfRow(text.lines().map(String::trim).toList(), cells) >= 0;
    }

    private static int indexOfRow(List<String> lines, String... cells) {
        for (int i = 0; i < lines.size(); i++) {
            if (Arrays.equals(lines.get(i).split("\\s+"), cells)) {
                return i;
            }
        }
        return -1;
    }
}
