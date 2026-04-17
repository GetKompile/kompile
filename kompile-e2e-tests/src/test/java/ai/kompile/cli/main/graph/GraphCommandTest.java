/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.graph;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphCommandTest {

    @Test
    void registersAllExpectedSubcommands() {
        CommandLine cli = new CommandLine(new GraphCommand());
        Map<String, CommandLine> subs = cli.getSubcommands();
        for (String expected : new String[]{
                "stats", "add-node", "get-node", "delete-node", "list-nodes",
                "search", "add-edge", "delete-edge", "traverse", "path",
                "algorithm", "communities", "query", "import", "export", "shell"}) {
            assertTrue(subs.containsKey(expected), "Missing subcommand: " + expected);
        }
    }

    @Test
    void helpOutputListsSubcommands() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CommandLine(new GraphCommand()).usage(new PrintStream(out));
        String help = out.toString();
        assertTrue(help.contains("stats"));
        assertTrue(help.contains("add-node"));
        assertTrue(help.contains("query"));
    }

    @Test
    void statsCallsStatisticsEndpoint() throws Exception {
        GraphCommand.StatsCmd cmd = new GraphCommand.StatsCmd();
        RecordingHttpClient client = new RecordingHttpClient();
        client.getResponses.put("/api/knowledge-graph/statistics", "{\"nodes\":3,\"edges\":5}");
        injectClient(cmd, client, true);

        Integer rc = cmd.call();
        assertEquals(0, rc);
        assertEquals(1, client.calls.size());
        assertEquals("GET", client.lastCall().method);
        assertEquals("/api/knowledge-graph/statistics", client.lastCall().path);
    }

    @Test
    void addNodeBuildsExpectedJsonBody() throws Exception {
        GraphCommand.AddNodeCmd cmd = new GraphCommand.AddNodeCmd();
        RecordingHttpClient client = new RecordingHttpClient();
        client.defaultPost = "{\"nodeId\":\"abc\",\"nodeType\":\"ENTITY\",\"externalId\":\"e1\",\"title\":\"Alpha\"}";
        injectClient(cmd, client, false);
        setField(cmd, "type", "ENTITY");
        setField(cmd, "externalId", "e1");
        setField(cmd, "title", "Alpha");
        setField(cmd, "description", "first node");
        setField(cmd, "metadata", Map.of("k", "v"));

        assertEquals(0, cmd.call());
        RecordingHttpClient.Call call = client.lastCall();
        assertEquals("POST", call.method);
        assertEquals("/api/knowledge-graph/nodes", call.path);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) call.body;
        assertEquals("ENTITY", body.get("nodeType"));
        assertEquals("e1", body.get("externalId"));
        assertEquals("Alpha", body.get("title"));
        assertEquals("first node", body.get("description"));
        assertNotNull(body.get("metadata"));
    }

    @Test
    void addEdgeBuildsExpectedJsonBody() throws Exception {
        GraphCommand.AddEdgeCmd cmd = new GraphCommand.AddEdgeCmd();
        RecordingHttpClient client = new RecordingHttpClient();
        client.defaultPost = "{\"edgeId\":\"e1\",\"edgeType\":\"KNOWS\",\"weight\":2.0}";
        injectClient(cmd, client, false);
        setField(cmd, "from", "n1");
        setField(cmd, "to", "n2");
        setField(cmd, "type", "KNOWS");
        setField(cmd, "weight", 2.0);

        assertEquals(0, cmd.call());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) client.lastCall().body;
        assertEquals("/api/knowledge-graph/edges", client.lastCall().path);
        assertEquals("n1", body.get("sourceNodeId"));
        assertEquals("n2", body.get("targetNodeId"));
        assertEquals("KNOWS", body.get("edgeType"));
        assertEquals(2.0, body.get("weight"));
    }

    @Test
    void deleteNodeCallsDelete() throws Exception {
        GraphCommand.DeleteNodeCmd cmd = new GraphCommand.DeleteNodeCmd();
        RecordingHttpClient client = new RecordingHttpClient();
        injectClient(cmd, client, false);
        setField(cmd, "nodeId", "abc-123");

        assertEquals(0, cmd.call());
        assertEquals("DELETE", client.lastCall().method);
        assertEquals("/api/knowledge-graph/nodes/abc-123", client.lastCall().path);
    }

    @Test
    void searchEncodesQueryAndAppendsLimit() throws Exception {
        GraphCommand.SearchCmd cmd = new GraphCommand.SearchCmd();
        RecordingHttpClient client = new RecordingHttpClient();
        client.defaultGet = "[]";
        injectClient(cmd, client, false);
        setField(cmd, "query", "hello world");
        setField(cmd, "limit", 10);

        assertEquals(0, cmd.call());
        String path = client.lastCall().path;
        assertTrue(path.startsWith("/api/knowledge-graph/nodes?query="));
        assertTrue(path.contains("hello+world") || path.contains("hello%20world"),
                "Expected URL-encoded query, got: " + path);
        assertTrue(path.contains("limit=10"));
    }

    @Test
    void traversePostsBfsBody() throws Exception {
        GraphCommand.TraverseCmd cmd = new GraphCommand.TraverseCmd();
        RecordingHttpClient client = new RecordingHttpClient();
        client.defaultPost = "{\"0\":[\"n1\"]}";
        injectClient(cmd, client, false);
        setField(cmd, "nodeId", "n1");
        setField(cmd, "depth", 2);

        assertEquals(0, cmd.call());
        assertEquals("/api/graph/algorithms/traverse/bfs", client.lastCall().path);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) client.lastCall().body;
        assertEquals("n1", body.get("startNodeId"));
        assertEquals(2, body.get("maxDepth"));
    }

    @Test
    void shortestPathPostsExpectedBody() throws Exception {
        GraphCommand.PathCmd cmd = new GraphCommand.PathCmd();
        RecordingHttpClient client = new RecordingHttpClient();
        client.defaultPost = "{\"found\":false}";
        injectClient(cmd, client, false);
        setField(cmd, "fromId", "a");
        setField(cmd, "toId", "b");
        setField(cmd, "weighted", true);

        assertEquals(0, cmd.call());
        assertEquals("/api/graph/algorithms/path/shortest", client.lastCall().path);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) client.lastCall().body;
        assertEquals("a", body.get("fromNodeId"));
        assertEquals("b", body.get("toNodeId"));
        assertEquals(Boolean.TRUE, body.get("weighted"));
    }

    @Test
    void algorithmDispatchesToCorrectEndpoint() throws Exception {
        for (String algo : new String[]{"pagerank", "degree", "betweenness", "wcc", "jaccard"}) {
            GraphCommand.AlgorithmCmd cmd = new GraphCommand.AlgorithmCmd();
            RecordingHttpClient client = new RecordingHttpClient();
            client.defaultPost = "{}";
            injectClient(cmd, client, false);
            setField(cmd, "name", algo);
            setField(cmd, "limit", 5);

            assertEquals(0, cmd.call());
            String expected = switch (algo) {
                case "pagerank" -> "/api/graph/algorithms/pagerank";
                case "degree" -> "/api/graph/algorithms/centrality/degree";
                case "betweenness" -> "/api/graph/algorithms/centrality/betweenness";
                case "wcc" -> "/api/graph/algorithms/components/wcc";
                case "jaccard" -> "/api/graph/algorithms/similarity/jaccard";
                default -> throw new IllegalStateException();
            };
            assertEquals(expected, client.lastCall().path, "algo=" + algo);
        }
    }

    @Test
    void algorithmUnknownNameReturnsErrorCode() throws Exception {
        GraphCommand.AlgorithmCmd cmd = new GraphCommand.AlgorithmCmd();
        RecordingHttpClient client = new RecordingHttpClient();
        injectClient(cmd, client, false);
        setField(cmd, "name", "nonexistent");
        setField(cmd, "limit", 5);

        assertEquals(1, cmd.call());
        assertEquals(0, client.calls.size());
    }

    @Test
    void communitiesLouvainCallsLouvainEndpoint() throws Exception {
        GraphCommand.CommunitiesCmd cmd = new GraphCommand.CommunitiesCmd();
        RecordingHttpClient client = new RecordingHttpClient();
        client.defaultPost = "{\"n1\":0,\"n2\":0}";
        injectClient(cmd, client, false);
        setField(cmd, "algorithm", "louvain");
        setField(cmd, "summarize", false);
        setField(cmd, "maxNodesPerPrompt", 25);

        assertEquals(0, cmd.call());
        assertEquals("/api/graph/algorithms/communities/louvain", client.lastCall().path);
    }

    @Test
    void communitiesSummarizeAppendsSummarizeSegment() throws Exception {
        GraphCommand.CommunitiesCmd cmd = new GraphCommand.CommunitiesCmd();
        RecordingHttpClient client = new RecordingHttpClient();
        client.defaultPost = "[]";
        injectClient(cmd, client, false);
        setField(cmd, "algorithm", "louvain");
        setField(cmd, "summarize", true);
        setField(cmd, "maxNodesPerPrompt", 25);

        assertEquals(0, cmd.call());
        assertEquals("/api/graph/algorithms/communities/louvain/summarize", client.lastCall().path);
    }

    private static void injectClient(Object command, RecordingHttpClient client, boolean json) throws Exception {
        setField(command, "app", new StubAppClientMixin(client, json));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
