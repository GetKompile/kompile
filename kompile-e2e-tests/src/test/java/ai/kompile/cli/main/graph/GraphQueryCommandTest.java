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
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GraphQueryCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void postsCypherBodyToCorrectEndpoint() throws Exception {
        GraphQueryCommand cmd = new GraphQueryCommand();
        RecordingHttpClient client = new RecordingHttpClient();
        client.defaultPost = "{\"columns\":[\"n\"],\"rows\":[[\"alice\"]],\"stats\":{},\"elapsedMs\":3}";
        injectClient(cmd, client, false);
        setField(cmd, "cypherOrFile", "MATCH (n) RETURN n LIMIT 5");
        setField(cmd, "readOnly", false);
        setField(cmd, "limit", 100);

        assertEquals(0, cmd.call());
        assertEquals("/api/graph/cypher/query", client.lastCall().path);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) client.lastCall().body;
        assertEquals("MATCH (n) RETURN n LIMIT 5", body.get("cypher"));
        assertEquals(Boolean.FALSE, body.get("readOnly"));
    }

    @Test
    void includesParamsWhenProvided() throws Exception {
        GraphQueryCommand cmd = new GraphQueryCommand();
        RecordingHttpClient client = new RecordingHttpClient();
        injectClient(cmd, client, false);
        setField(cmd, "cypherOrFile", "MATCH (n {name:$name}) RETURN n");
        setField(cmd, "params", Map.of("name", "alice"));
        setField(cmd, "readOnly", true);
        setField(cmd, "limit", 100);

        assertEquals(0, cmd.call());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) client.lastCall().body;
        assertEquals(Boolean.TRUE, body.get("readOnly"));
        @SuppressWarnings("unchecked")
        Map<String, String> params = (Map<String, String>) body.get("params");
        assertEquals("alice", params.get("name"));
    }

    @Test
    void resolveCypherReadsFileWhenPrefixedWithAt() throws Exception {
        Path file = Files.writeString(tempDir.resolve("query.cypher"), "MATCH (n) RETURN count(n)");
        String resolved = GraphQueryCommand.resolveCypher("@" + file);
        assertEquals("MATCH (n) RETURN count(n)", resolved);
    }

    @Test
    void resolveCypherReturnsLiteralStringWhenNotPrefixed() throws Exception {
        assertEquals("MATCH (n) RETURN n", GraphQueryCommand.resolveCypher("MATCH (n) RETURN n"));
    }

    @Test
    void resolveCypherReturnsEmptyForBlankInput() throws Exception {
        assertEquals("", GraphQueryCommand.resolveCypher(""));
        assertEquals("", GraphQueryCommand.resolveCypher(null));
    }

    @Test
    void omitsParamsKeyWhenNoneProvided() throws Exception {
        GraphQueryCommand cmd = new GraphQueryCommand();
        RecordingHttpClient client = new RecordingHttpClient();
        injectClient(cmd, client, false);
        setField(cmd, "cypherOrFile", "RETURN 1");
        setField(cmd, "limit", 100);

        assertEquals(0, cmd.call());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) client.lastCall().body;
        assertNotNull(body.get("cypher"));
        // params should be absent when none supplied
        assertEquals(false, body.containsKey("params"));
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
