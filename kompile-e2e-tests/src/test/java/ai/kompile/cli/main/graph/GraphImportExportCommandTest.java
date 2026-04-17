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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphImportExportCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void importRejectsUnknownFormat() throws Exception {
        Path file = Files.writeString(tempDir.resolve("graph.json"), "{}");
        GraphImportCommand cmd = new GraphImportCommand();
        RecordingHttpClient client = new RecordingHttpClient();
        injectClient(cmd, client, false);
        setField(cmd, "filePath", file);
        setField(cmd, "format", "xml");

        assertEquals(1, cmd.call());
        assertEquals(0, client.calls.size());
    }

    @Test
    void importPostsMultipartWithFormatField() throws Exception {
        Path file = Files.writeString(tempDir.resolve("graph.json"), "{}");
        GraphImportCommand cmd = new GraphImportCommand();
        RecordingHttpClient client = new RecordingHttpClient();
        client.uploadResponse = "{\"format\":\"json\",\"nodesCreated\":1,\"nodesUpdated\":0,\"edgesCreated\":0,\"errors\":0,\"errorMessages\":[]}";
        injectClient(cmd, client, false);
        setField(cmd, "filePath", file);
        setField(cmd, "format", "json");

        assertEquals(0, cmd.call());
        RecordingHttpClient.Call call = client.lastCall();
        assertEquals("MULTIPART", call.method);
        assertEquals("/api/graph/io/import", call.path);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) call.body;
        @SuppressWarnings("unchecked")
        Map<String, Path> files = (Map<String, Path>) body.get("files");
        assertEquals(file, files.get("file"));
        @SuppressWarnings("unchecked")
        Map<String, String> form = (Map<String, String>) body.get("form");
        assertEquals("json", form.get("format"));
        assertNull(form.get("factSheetId"));
    }

    @Test
    void importIncludesEdgesFileAndFactSheetId() throws Exception {
        Path nodes = Files.writeString(tempDir.resolve("nodes.csv"), "");
        Path edges = Files.writeString(tempDir.resolve("edges.csv"), "");
        GraphImportCommand cmd = new GraphImportCommand();
        RecordingHttpClient client = new RecordingHttpClient();
        injectClient(cmd, client, false);
        setField(cmd, "filePath", nodes);
        setField(cmd, "edgesFile", edges);
        setField(cmd, "format", "csv");
        setField(cmd, "factSheetId", 7L);

        assertEquals(0, cmd.call());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) client.lastCall().body;
        @SuppressWarnings("unchecked")
        Map<String, Path> files = (Map<String, Path>) body.get("files");
        assertEquals(nodes, files.get("file"));
        assertEquals(edges, files.get("edgesFile"));
        @SuppressWarnings("unchecked")
        Map<String, String> form = (Map<String, String>) body.get("form");
        assertEquals("csv", form.get("format"));
        assertEquals("7", form.get("factSheetId"));
    }

    @Test
    void exportRejectsUnknownFormat() throws Exception {
        GraphExportCommand cmd = new GraphExportCommand();
        RecordingHttpClient client = new RecordingHttpClient();
        injectClient(cmd, client, false);
        setField(cmd, "format", "xml");
        setField(cmd, "output", tempDir.resolve("out.xml"));

        assertEquals(1, cmd.call());
        assertEquals(0, client.calls.size());
    }

    @Test
    void exportWritesPayloadToOutputFile() throws Exception {
        GraphExportCommand cmd = new GraphExportCommand();
        RecordingHttpClient client = new RecordingHttpClient();
        client.downloadPayload = "<graphml/>".getBytes();
        client.downloadDispositionHeader = "attachment; filename=\"graph.graphml\"";
        injectClient(cmd, client, false);
        Path output = tempDir.resolve("out.graphml");
        setField(cmd, "format", "graphml");
        setField(cmd, "output", output);

        assertEquals(0, cmd.call());
        RecordingHttpClient.Call call = client.lastCall();
        assertEquals("DOWNLOAD", call.method);
        assertTrue(call.path.startsWith("/api/graph/io/export?format=graphml"));
        assertEquals("<graphml/>", Files.readString(output));
    }

    @Test
    void exportAppendsFactSheetIdToQuery() throws Exception {
        GraphExportCommand cmd = new GraphExportCommand();
        RecordingHttpClient client = new RecordingHttpClient();
        client.downloadPayload = new byte[]{1, 2, 3};
        injectClient(cmd, client, false);
        setField(cmd, "format", "json");
        setField(cmd, "output", tempDir.resolve("out.json"));
        setField(cmd, "factSheetId", 42L);

        assertEquals(0, cmd.call());
        assertTrue(client.lastCall().path.contains("factSheetId=42"),
                "Expected factSheetId=42 in path: " + client.lastCall().path);
    }

    @Test
    void parseFilenameHandlesQuotedAndUnquotedHeaders() {
        assertEquals("graph.json",
                GraphExportCommand.parseFilename("attachment; filename=\"graph.json\""));
        assertEquals("graph.cypher",
                GraphExportCommand.parseFilename("attachment; filename=graph.cypher"));
        assertEquals("graph.csv",
                GraphExportCommand.parseFilename("attachment; filename=graph.csv; size=1024"));
        assertNull(GraphExportCommand.parseFilename("attachment"));
        assertNull(GraphExportCommand.parseFilename(null));
    }

    @Test
    void supportedFormatsCoverExpectedSet() {
        for (String fmt : new String[]{"json", "jsonld", "json-ld", "csv", "cypher"}) {
            assertTrue(GraphImportCommand.SUPPORTED_FORMATS.contains(fmt),
                    "Import should accept " + fmt);
        }
        for (String fmt : new String[]{"json", "jsonld", "json-ld", "csv", "graphml", "cypher"}) {
            assertTrue(GraphExportCommand.SUPPORTED_FORMATS.contains(fmt),
                    "Export should accept " + fmt);
        }
        assertNotNull(GraphImportCommand.SUPPORTED_FORMATS);
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
