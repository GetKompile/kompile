/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the graph snapshot (versioning / undo) actions added to {@link KnowledgeGraphTool}.
 * Uses a lightweight built-in HttpServer stub — no Spring context required.
 */
@DisplayName("KnowledgeGraphTool — snapshot actions")
class KnowledgeGraphToolSnapshotTest {

    private ObjectMapper om;
    private ToolContext ctx;
    private HttpServer httpServer;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        om = new ObjectMapper();

        AgentConfig agent = AgentConfig.builder("coder").enabledTools(Set.of("*")).build();
        PermissionService perms = new PermissionService();
        perms.setUserOverride("knowledge_graph", PermissionService.PermissionLevel.ALLOW);
        ToolRegistry registry = new ToolRegistry(om);
        ctx = new ToolContext("test-session", agent, perms, Paths.get("."), registry);

        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        port = httpServer.getAddress().getPort();
        httpServer.start();
    }

    @AfterEach
    void tearDown() {
        httpServer.stop(0);
    }

    private void stub(String path, int status, String body) {
        httpServer.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private KnowledgeGraphTool tool() {
        return new KnowledgeGraphTool("http://localhost:" + port, om);
    }

    private ObjectNode params(String action) {
        ObjectNode p = om.createObjectNode();
        p.put("action", action);
        return p;
    }

    // ─── metadata ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("description mentions create_snapshot and restore_snapshot")
    void description_mentionsSnapshotActions() {
        String desc = tool().description();
        assertTrue(desc.contains("create_snapshot"), "description must mention create_snapshot");
        assertTrue(desc.contains("restore_snapshot"), "description must mention restore_snapshot");
    }

    @Test
    @DisplayName("compactHint mentions snapshot/undo")
    void compactHint_mentionsSnapshots() {
        String hint = tool().compactHint();
        assertTrue(hint.contains("snapshot") || hint.contains("undo"),
                "compactHint must mention snapshot/undo feature");
    }

    @Test
    @DisplayName("parameter schema includes snapshot_id")
    void schema_hasSnapshotIdParam() {
        var schema = tool().parameterSchema();
        assertFalse(schema.path("properties").path("snapshot_id").isMissingNode(),
                "schema must include snapshot_id");
    }

    // ─── create_snapshot ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("create_snapshot")
    class CreateSnapshot {

        @Test
        @DisplayName("missing fact_sheet_id returns error")
        void missingFactSheetId_returnsError() throws Exception {
            ToolResult result = tool().execute(params("create_snapshot"), ctx);
            assertTrue(result.isError(), "should error on missing fact_sheet_id");
        }

        @Test
        @DisplayName("success returns snapshotId")
        void success_returnsSnapshotId() throws Exception {
            stub("/api/graph/snapshots", 200, """
                    {"snapshotId":"20260709T120000Z_label.kgraph","factSheetId":1,"label":"label",
                     "createdAt":"2026-07-09T12:00:00Z","sizeBytes":2048}
                    """);

            ObjectNode p = params("create_snapshot");
            p.put("fact_sheet_id", 1);
            p.put("label", "label");

            ToolResult result = tool().execute(p, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("20260709T120000Z_label.kgraph"),
                    "output must include snapshotId");
        }

        @Test
        @DisplayName("503 from server propagates as error")
        void serviceUnavailable_returnsError() throws Exception {
            stub("/api/graph/snapshots", 503, """
                    {"error":"Graph snapshot feature is disabled"}
                    """);

            ObjectNode p = params("create_snapshot");
            p.put("fact_sheet_id", 1);

            ToolResult result = tool().execute(p, ctx);
            assertTrue(result.isError(), "503 should propagate as error");
        }
    }

    // ─── list_snapshots ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("list_snapshots")
    class ListSnapshots {

        @Test
        @DisplayName("missing fact_sheet_id returns error")
        void missingFactSheetId_returnsError() throws Exception {
            ToolResult result = tool().execute(params("list_snapshots"), ctx);
            assertTrue(result.isError());
        }

        @Test
        @DisplayName("returns snapshots list")
        void returnsSnapshots() throws Exception {
            stub("/api/graph/snapshots", 200, """
                    [
                      {"snapshotId":"20260709T120000Z_b.kgraph","factSheetId":2,"label":"b",
                       "createdAt":"2026-07-09T12:00:00Z","sizeBytes":1024},
                      {"snapshotId":"20260709T110000Z_a.kgraph","factSheetId":2,"label":"a",
                       "createdAt":"2026-07-09T11:00:00Z","sizeBytes":512}
                    ]
                    """);

            ObjectNode p = params("list_snapshots");
            p.put("fact_sheet_id", 2);

            ToolResult result = tool().execute(p, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("20260709T120000Z_b.kgraph"), "should list first snapshot");
            assertTrue(result.getOutput().contains("20260709T110000Z_a.kgraph"), "should list second snapshot");
        }

        @Test
        @DisplayName("empty list shows no snapshots message")
        void emptyList() throws Exception {
            stub("/api/graph/snapshots", 200, "[]");

            ObjectNode p = params("list_snapshots");
            p.put("fact_sheet_id", 3);

            ToolResult result = tool().execute(p, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().toLowerCase().contains("no snapshots"),
                    "should say 'no snapshots'");
        }
    }

    // ─── restore_snapshot ────────────────────────────────────────────────────

    @Nested
    @DisplayName("restore_snapshot")
    class RestoreSnapshot {

        @Test
        @DisplayName("missing fact_sheet_id returns error")
        void missingFactSheetId_returnsError() throws Exception {
            ObjectNode p = params("restore_snapshot");
            p.put("snapshot_id", "snap.kgraph");
            ToolResult result = tool().execute(p, ctx);
            assertTrue(result.isError());
        }

        @Test
        @DisplayName("missing snapshot_id returns error")
        void missingSnapshotId_returnsError() throws Exception {
            ObjectNode p = params("restore_snapshot");
            p.put("fact_sheet_id", 1);
            ToolResult result = tool().execute(p, ctx);
            assertTrue(result.isError());
        }

        @Test
        @DisplayName("success shows nodes/edges/atoms + pre-restore id")
        void success_showsImportDetails() throws Exception {
            stub("/api/graph/snapshots/snap.kgraph/restore", 200, """
                    {"factSheetId":1,"restoredSnapshotId":"snap.kgraph",
                     "preRestoreSnapshotId":"20260709T115900Z_pre-restore.kgraph",
                     "nodes":10,"edges":5,"atoms":20,"graphBuildEventPublished":true}
                    """);

            ObjectNode p = params("restore_snapshot");
            p.put("fact_sheet_id", 1);
            p.put("snapshot_id", "snap.kgraph");

            ToolResult result = tool().execute(p, ctx);

            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("snap.kgraph"), "output must mention snapshot id");
            assertTrue(result.getOutput().contains("pre-restore"), "output must mention pre-restore snapshot");
            assertTrue(result.getOutput().contains("reasoning-ready"), "output must confirm reasoning-ready");
        }
    }

    // ─── delete_snapshot ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("delete_snapshot")
    class DeleteSnapshot {

        @Test
        @DisplayName("missing fact_sheet_id returns error")
        void missingFactSheetId_returnsError() throws Exception {
            ObjectNode p = params("delete_snapshot");
            p.put("snapshot_id", "snap.kgraph");
            ToolResult result = tool().execute(p, ctx);
            assertTrue(result.isError());
        }

        @Test
        @DisplayName("missing snapshot_id returns error")
        void missingSnapshotId_returnsError() throws Exception {
            ObjectNode p = params("delete_snapshot");
            p.put("fact_sheet_id", 1);
            ToolResult result = tool().execute(p, ctx);
            assertTrue(result.isError());
        }

        @Test
        @DisplayName("404 propagates as error")
        void notFound_returnsError() throws Exception {
            stub("/api/graph/snapshots/missing.kgraph", 404, """
                    {"error":"Snapshot not found"}
                    """);

            ObjectNode p = params("delete_snapshot");
            p.put("fact_sheet_id", 1);
            p.put("snapshot_id", "missing.kgraph");

            ToolResult result = tool().execute(p, ctx);
            assertTrue(result.isError());
        }

        @Test
        @DisplayName("success returns confirmation")
        void success_returnsConfirmation() throws Exception {
            stub("/api/graph/snapshots/snap.kgraph", 200, """
                    {"deleted":true,"snapshotId":"snap.kgraph","factSheetId":1}
                    """);

            ObjectNode p = params("delete_snapshot");
            p.put("fact_sheet_id", 1);
            p.put("snapshot_id", "snap.kgraph");

            ToolResult result = tool().execute(p, ctx);
            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains("snap.kgraph"), "must confirm which snapshot was deleted");
        }
    }
}
