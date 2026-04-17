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

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphShellCommandTest {

    @Test
    void exitMetaCommandTerminatesRepl() throws Exception {
        ShellHarness h = new ShellHarness(":exit\n");
        int rc = h.cmd.runRepl(h.client, h.reader);
        assertEquals(0, rc);
        assertTrue(h.stdout().contains("kompile graph shell"));
        assertEquals(0, h.client.calls.size());
    }

    @Test
    void helpMetaCommandPrintsUsage() throws Exception {
        ShellHarness h = new ShellHarness(":help\n:exit\n");
        h.cmd.runRepl(h.client, h.reader);
        String out = h.stdout();
        assertTrue(out.contains(":help"));
        assertTrue(out.contains(":history"));
        assertTrue(out.contains(":params"));
        assertTrue(out.contains(":exit"));
    }

    @Test
    void cypherStatementsArePostedToQueryEndpoint() throws Exception {
        ShellHarness h = new ShellHarness("MATCH (n) RETURN n\n:exit\n");
        h.client.defaultPost = "{\"columns\":[\"n\"],\"rows\":[[\"alice\"]],\"elapsedMs\":1}";
        int rc = h.cmd.runRepl(h.client, h.reader);
        assertEquals(0, rc);
        RecordingHttpClient.Call call = h.client.findCall("POST", "/api/graph/cypher/query");
        assertNotNull(call, "Expected a POST to /api/graph/cypher/query");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) call.body;
        assertEquals("MATCH (n) RETURN n", body.get("cypher"));
    }

    @Test
    void historyMetaCommandListsPriorStatements() throws Exception {
        ShellHarness h = new ShellHarness("RETURN 1\nRETURN 2\n:history\n:exit\n");
        h.client.defaultPost = "{\"columns\":[\"a\"],\"rows\":[],\"elapsedMs\":0}";
        h.cmd.runRepl(h.client, h.reader);
        String out = h.stdout();
        assertTrue(out.contains("RETURN 1"), "Expected history entry RETURN 1, got:\n" + out);
        assertTrue(out.contains("RETURN 2"));
    }

    @Test
    void paramsAccumulateAndAreSentWithSubsequentQueries() throws Exception {
        ShellHarness h = new ShellHarness(":params name=alice,age=30\nRETURN 1\n:exit\n");
        h.cmd.runRepl(h.client, h.reader);
        RecordingHttpClient.Call call = h.client.findCall("POST", "/api/graph/cypher/query");
        assertNotNull(call);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) call.body;
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) body.get("params");
        assertNotNull(params, "Expected params to be set on the query");
        assertEquals("alice", params.get("name"));
        assertEquals(30L, params.get("age"));
    }

    @Test
    void paramsClearResetsSessionParams() throws Exception {
        ShellHarness h = new ShellHarness(":params name=alice\n:params clear\nRETURN 1\n:exit\n");
        h.cmd.runRepl(h.client, h.reader);
        RecordingHttpClient.Call call = h.client.findCall("POST", "/api/graph/cypher/query");
        assertNotNull(call);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) call.body;
        // params should be omitted entirely once cleared
        assertEquals(false, body.containsKey("params"));
    }

    @Test
    void unknownMetaCommandIsReportedToStderr() throws Exception {
        ShellHarness h = new ShellHarness(":frobnicate\n:exit\n");
        h.cmd.runRepl(h.client, h.reader);
        assertTrue(h.stderr().contains("Unknown meta-command"));
    }

    @Test
    void sysInfoCallsInfoEndpoint() throws Exception {
        ShellHarness h = new ShellHarness(":sysinfo\n:exit\n");
        h.client.getResponses.put("/api/graph/cypher/info",
                "{\"name\":\"Neo4j\",\"versions\":[\"5.18.0\"],\"edition\":\"community\"}");
        h.cmd.runRepl(h.client, h.reader);
        assertNotNull(h.client.findCall("GET", "/api/graph/cypher/info"));
    }

    @Test
    void emptyLinesAreIgnored() throws Exception {
        ShellHarness h = new ShellHarness("\n\n   \n:exit\n");
        h.cmd.runRepl(h.client, h.reader);
        assertEquals(0, h.client.calls.size());
    }

    private static final class ShellHarness {
        final GraphShellCommand cmd = new GraphShellCommand();
        final RecordingHttpClient client = new RecordingHttpClient();
        final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        final PrintStream outStream = new PrintStream(outBuf);
        final PrintStream errStream = new PrintStream(errBuf);
        final BufferedReader reader;

        ShellHarness(String scriptedInput) throws Exception {
            ByteArrayInputStream in = new ByteArrayInputStream(scriptedInput.getBytes(StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            cmd.setStreams(in, outStream, errStream);
            // Disable interactive prompt for cleaner output assertions.
            Field input = GraphShellCommand.class.getDeclaredField("input");
            input.setAccessible(true);
            // Replacing `input` with a non-stdin stream flips interactive=false in runRepl.
            input.set(cmd, in);
        }

        String stdout() {
            outStream.flush();
            return outBuf.toString(StandardCharsets.UTF_8);
        }

        String stderr() {
            errStream.flush();
            return errBuf.toString(StandardCharsets.UTF_8);
        }
    }
}
