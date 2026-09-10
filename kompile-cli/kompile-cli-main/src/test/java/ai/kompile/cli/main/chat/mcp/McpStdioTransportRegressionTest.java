/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.main.MainCommand;
import ai.kompile.cli.main.chat.tools.grounding.LocalProjectGraphBackend;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounded transport proof for the real in-process MCP child. This deliberately stops at a
 * local knowledge-graph overview; the opt-in PDF crawl must not be started until this exchange
 * has produced a response.
 */
class McpStdioTransportRegressionTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final long OVERVIEW_DEADLINE_SECONDS = 30L;
    private static final String OVERVIEW_KNOWLEDGE_BASE = "codex-luna-pdf-owl-transitive-acceptance";

    @TempDir
    Path project;

    @BeforeEach
    void seedIsolatedGraph() throws IOException {
        Path graph = project.resolve("data/crawls").resolve(OVERVIEW_KNOWLEDGE_BASE)
                .resolve(LocalProjectGraphBackend.GRAPH_FILE);
        Files.createDirectories(graph.getParent());
        new UnifiedGraph().graphId("local:kompile:" + OVERVIEW_KNOWLEDGE_BASE)
                .addEntity("transport-root", "FIXTURE", "Transport Root")
                .addEntity("transport-leaf", "FIXTURE", "Transport Leaf")
                .addRelation("transport-edge", "transport-root", "transport-leaf", "CONTAINS", 1.0)
                .saveCompact(graph);
    }

    @Test
    @Timeout(value = 75, unit = TimeUnit.SECONDS)
    void realClientCompletesInitializeToolsListAndOverviewWithRawIds() throws Exception {
        String transcript = "mcp-stdio-transport-" + UUID.randomUUID();
        try (McpStdioClient client = startClient(project, transcript)) {
            client.initialize();

            List<McpBundleToolLoader.RemoteTool> tools = client.listTools();
            assertNotNull(tools.stream().filter(tool -> "knowledge_graph".equals(tool.name()))
                    .findFirst().orElse(null), "knowledge_graph missing from real tools/list");

            ObjectNode request = MAPPER.createObjectNode().put("action", "overview")
                    .put("knowledgeBase", OVERVIEW_KNOWLEDGE_BASE);
            JsonNode response = client.callTool("knowledge_graph", request);
            assertFalse(response.path("isError").asBoolean(), response.toPrettyString());
            assertTrue(response.path("content").isArray(), response.toPrettyString());
            JsonNode graph = response.path("structuredContent");
            if (graph.path("output").isTextual()) graph = MAPPER.readTree(graph.path("output").asText());
            assertEquals("local:kompile:" + OVERVIEW_KNOWLEDGE_BASE,
                    graph.path("graphId").asText(), response.toPrettyString());
            assertEquals(2, graph.path("entities").asInt(), response.toPrettyString());
            assertEquals(1, graph.path("relations").asInt(), response.toPrettyString());
            assertEquals(1, graph.path("predicates").path("CONTAINS").asInt(),
                    response.toPrettyString());
            System.out.printf("[mcp-transport] graphId=%s entities=%d relations=%d%n",
                    graph.path("graphId").asText(), graph.path("entities").asInt(),
                    graph.path("relations").asInt());
        }
    }

    private McpStdioClient startClient(Path project, String transcript) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        List<String> args = List.of(
                "-Xmx512m", "-XX:ActiveProcessorCount=2",
                "-Dkompile.memory.dense.enabled=false",
                "-Dkompile.coordination.systemRoot=" + project.resolve(".coordination"),
                "-Dkompile.subprocess.watchdog.admissionMode=off",
                "-Dkompile.subprocess.watchdog.admissionMaxRamUsedFraction=1.0",
                "-Dkompile.subprocess.watchdog.admissionMinAvailableRamMb=0",
                "-Dkompile.subprocess.watchdog.admissionMinAvailableGpuMb=0",
                "-Dkompile.subprocess.watchdog.admissionMaxGpuUsedFraction=0",
                "-cp", classpath, MainCommand.class.getName(), "mcp-stdio", "--no-daemon",
                "--profile", "full", "--work-dir", project.toString(),
                "--transcript-id", transcript);
        return new McpStdioClient(MAPPER, java, args,
                Map.of("OMP_NUM_THREADS", "2", "OPENBLAS_NUM_THREADS", "2", "MKL_NUM_THREADS", "2"),
                project, OVERVIEW_DEADLINE_SECONDS, "transport-regression", transcript);
    }

}
