/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.codeindex.BackgroundIndexService;
import ai.kompile.cli.main.codeindex.LocalCodeIndexer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import java.net.ConnectException;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Routing contracts only: no real HTTP, indexing, maintenance, graph projection or learning. */
class CodeToolBackendRoutingTest {
    private static final String PROJECT = "routing-test";
    private static final String REMOTE = "http://explicit.example:8082";
    private static final String FRESHNESS = "[code-index] refresh still in progress; results may be stale";

    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private KompileBackendClient backend;
    private BackgroundIndexService maintenance;
    private ToolContext context;
    private MockedStatic<KompileBackendClient> backendSingleton;
    private MockedStatic<BackgroundIndexService> maintenanceSingleton;
    private MockedStatic<LocalCodeIndexer> indexPaths;
    private MockedConstruction<LocalCodeIndexer> indexers;

    @BeforeEach
    void setUp() throws Exception {
        backend = mock(KompileBackendClient.class);
        maintenance = mock(BackgroundIndexService.class);
        context = mock(ToolContext.class);
        when(context.getWorkingDirectory()).thenReturn(directory);
        // Both probes look healthy, so a URL-less tool must actively avoid the server.
        when(backend.isAvailable()).thenReturn(true);
        when(backend.isAvailable(anyString())).thenReturn(true);
        when(maintenance.prepareForRead(any(LocalCodeIndexer.class), eq(PROJECT))).thenReturn(FRESHNESS);

        backendSingleton = mockStatic(KompileBackendClient.class);
        backendSingleton.when(KompileBackendClient::getInstance).thenReturn(backend);
        maintenanceSingleton = mockStatic(BackgroundIndexService.class);
        maintenanceSingleton.when(BackgroundIndexService::getInstance).thenReturn(maintenance);
        // Keep the package-private, pure validator real for ProjectIdResolver.
        // Other static operations stay mocked; routing tests must not open an index.
        indexPaths = mockStatic(LocalCodeIndexer.class, invocation ->
                "isSafeProjectId".equals(invocation.getMethod().getName())
                        ? invocation.callRealMethod() : RETURNS_DEFAULTS.answer(invocation));
        indexPaths.when(LocalCodeIndexer::getBaseIndexDir).thenReturn(directory);
        indexPaths.when(() -> LocalCodeIndexer.getIndexDir(PROJECT)).thenReturn(directory);
        // Make the former fallback guards eligible, without creating a real index.
        Files.writeString(directory.resolve("index.db"), "mock index marker");
        Files.writeString(directory.resolve("metadata.json"), "{}");
        indexers = mockConstruction(LocalCodeIndexer.class, (indexer, construction) -> {
            when(indexer.search(eq(PROJECT), anyString(), nullable(String.class), anyInt()))
                    .thenReturn(List.of(Map.<String, Object>of("name", "LocalOnlyEntity", "entityType", "CLASS")));
            when(indexer.getStats(PROJECT)).thenReturn(Map.of("entitiesFound", 1));
            when(indexer.entitiesForFile(eq(PROJECT), anyString(), anyInt())).thenReturn(List.of());
        });
    }

    @AfterEach
    void tearDown() {
        if (indexers != null) indexers.close();
        if (indexPaths != null) indexPaths.close();
        if (maintenanceSingleton != null) maintenanceSingleton.close();
        if (backendSingleton != null) backendSingleton.close();
    }

    @Test
    void noExplicitUrlAlwaysUsesLocalEvenWithHealthyOrPreviouslyPinnedBackend() throws Exception {
        new CodeSearchTool(REMOTE, mapper);
        new CodeGraphTool(REMOTE, mapper);
        clearInvocations(backend);
        for (String url : new String[]{null, "", "   "}) {
            for (CliTool tool : tools(url)) {
                ToolResult result = tool.execute(params("search").put("auto_refresh", false), context);
                assertFalse(result.isError(), result.getOutput());
                assertEquals("folder-local", result.getMetadata().get("backend"));
                assertTrue(result.getOutput().contains("LocalOnlyEntity"));
            }
        }
        verifyNoInteractions(backend, maintenance);
    }

    @Test
    void localReadReturnsFreshnessNoteAndPreservesMetadataAndErrors() throws Exception {
        for (CliTool tool : tools(null)) {
            ToolResult result = tool.execute(params("search"), context);
            assertFalse(result.isError(), result.getOutput());
            assertTrue(result.getOutput().contains(FRESHNESS));
            assertEquals(1, result.getMetadata().get("resultCount"));
            assertEquals("folder-local", result.getMetadata().get("backend"));
            ToolResult invalid = tool.execute(params("search").put("query", ""), context);
            assertTrue(invalid.isError(), invalid.getOutput());
            assertTrue(invalid.getOutput().contains(FRESHNESS));
        }
        verify(maintenance, times(4)).prepareForRead(any(LocalCodeIndexer.class), eq(PROJECT));
        verifyNoInteractions(backend);
    }

    @Test
    void explicitRemoteLocalOnlyActionsFailBeforeProbingOrTouchingLocalState() throws Exception {
        CliTool search = new CodeSearchTool(REMOTE, mapper);
        CliTool graph = new CodeGraphTool(REMOTE, mapper);
        clearInvocations(backend);
        for (CliTool tool : List.of(search, graph)) {
            List<String> actions = tool == search
                    ? List.of("ranked_search", "blended_search", "signatures", "health", "routing")
                    : List.of("impact", "ranked_search", "blended_search", "signatures", "health", "routing",
                            "learn", "learning_config_get", "learning_config_update");
            for (String action : actions) {
                ToolResult result = tool.execute(params(action), context);
                assertTrue(result.isError(), action);
                assertTrue(result.getOutput().contains("local-only"), result.getOutput());
                assertTrue(result.getOutput().contains("Remove --url"), result.getOutput());
                assertTrue(result.getOutput().contains("separate dataset"), result.getOutput());
            }
        }
        assertTrue(indexers.constructed().isEmpty());
        verifyNoInteractions(backend, maintenance);
    }

    @Test
    void unavailableExplicitRemoteDoesNotReadOrBuildLocalIndex() throws Exception {
        when(backend.isAvailable(anyString())).thenReturn(false);
        for (CliTool tool : tools(REMOTE)) {
            for (String action : List.of("search", "stats", tool.id().equals("code_search") ? "index" : "build")) {
                ToolResult result = tool.execute(params(action), context);
                assertTrue(result.isError(), result.getOutput());
                assertTrue(result.getOutput().contains("No local fallback"), result.getOutput());
            }
        }
        assertTrue(indexers.constructed().isEmpty());
        verifyNoInteractions(maintenance);
        verify(backend, never()).get(anyString(), any(Duration.class));
        verify(backend, never()).post(anyString(), nullable(String.class), any(Duration.class));
        verify(backend, never()).isAvailable();
    }

    @Test
    void remoteHttpFailuresNeverFallBackEvenWithLocalIndexMarkers() throws Exception {
        for (int status : new int[]{404, 405, 501, 500}) {
            stubResponse(status, "remote failure");
            for (CliTool tool : tools(REMOTE)) {
                List<String> actions = tool.id().equals("code_search")
                        ? List.of("search", "stats", "entities", "index")
                        : List.of("search", "symbol", "file", "stats", "build", "connectivity",
                                "add_directory", "remove_directory", "list_directories");
                for (String action : actions) {
                    ToolResult result = tool.execute(params(action), context);
                    assertTrue(result.isError(), tool.id() + ":" + action + " " + result.getOutput());
                    assertEquals("remote", result.getMetadata().get("backend"));
                    assertTrue(result.getOutput().contains("remote failure"), result.getOutput());
                }
            }
        }
        assertTrue(indexers.constructed().isEmpty());
        verifyNoInteractions(maintenance);
    }

    @Test
    void emptyRemoteResultsRemainRemote() throws Exception {
        stubResponse(200, "[]");
        CliTool search = new CodeSearchTool(REMOTE, mapper);
        for (String action : List.of("search", "entities")) {
            ToolResult result = search.execute(params(action), context);
            assertFalse(result.isError(), result.getOutput());
            assertEquals("remote", result.getMetadata().get("backend"));
            assertTrue(result.getOutput().contains("No code entities"));
        }
        stubResponse(200, "{\"codeEntities\":[],\"graphNodes\":[]}");
        // The response echoes the query; keep it distinct from the local-only result sentinel.
        ToolResult graph = new CodeGraphTool(REMOTE, mapper).execute(
                params("search").put("query", "absent-remote-entity"), context);
        assertFalse(graph.isError(), graph.getOutput());
        assertEquals("remote", graph.getMetadata().get("backend"));
        assertFalse(graph.getOutput().contains("LocalOnlyEntity"));
        assertTrue(indexers.constructed().isEmpty());
        verifyNoInteractions(maintenance);
    }

    @Test
    void connectionLossAndTimeoutDoNotChangeDataset() throws Exception {
        for (Exception failure : List.of(new ConnectException("gone"), new HttpTimeoutException("late"))) {
            doThrow(failure).when(backend).get(anyString(), any(Duration.class));
            doThrow(failure).when(backend).post(anyString(), nullable(String.class), any(Duration.class));
            for (CliTool tool : tools(REMOTE)) {
                for (String action : List.of("search", tool.id().equals("code_search") ? "index" : "build")) {
                    ToolResult result = tool.execute(params(action), context);
                    assertTrue(result.isError(), result.getOutput());
                    assertTrue(result.getOutput().contains("no local fallback"), result.getOutput());
                }
            }
        }
        assertTrue(indexers.constructed().isEmpty());
        verifyNoInteractions(maintenance);
    }

    @Test
    void successfulRemoteIndexAndBuildDiscloseNoFolderLocalProjection() throws Exception {
        stubResponse(200, "{\"projectId\":\"routing-test\",\"filesProcessed\":2}");
        for (CliTool tool : tools(REMOTE)) {
            ToolResult result = tool.execute(params(tool.id().equals("code_search") ? "index" : "build"), context);
            assertFalse(result.isError(), result.getOutput());
            assertEquals("remote", result.getMetadata().get("backend"));
            assertTrue(result.getOutput().contains("Folder-local graph**: not updated"), result.getOutput());
            assertTrue(result.getOutput().contains("local_code_index action='index'"), result.getOutput());
        }
        assertTrue(indexers.constructed().isEmpty());
        verifyNoInteractions(maintenance);
    }

    private List<CliTool> tools(String url) {
        return List.of(new CodeSearchTool(url, mapper), new CodeGraphTool(url, mapper));
    }

    private ObjectNode params(String action) {
        return mapper.createObjectNode().put("action", action).put("project_id", PROJECT)
                .put("query", "LocalOnlyEntity").put("file_path", "Example.java")
                .put("fqn", "Example").put("directory_path", directory.toString());
    }

    @SuppressWarnings("unchecked")
    private void stubResponse(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(backend.get(anyString(), any(Duration.class))).thenReturn(response);
        when(backend.post(anyString(), nullable(String.class), any(Duration.class))).thenReturn(response);
        when(backend.delete(anyString(), any(Duration.class))).thenReturn(response);
    }
}
