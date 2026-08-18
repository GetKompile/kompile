/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphAssertTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphClaimTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphExplainTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphFusedTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphMebnTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphQueryTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphRetractTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphSubscribeTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphSynthesizeTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphVerifyTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlControlTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlDiscoveryTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlDocumentsTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlSourceTool;
import ai.kompile.cli.main.chat.tools.grounding.GraphExportTool;
import ai.kompile.cli.main.chat.tools.grounding.GraphImportTool;
import ai.kompile.cli.main.chat.tools.grounding.GraphReasonTool;
import ai.kompile.cli.main.chat.tools.grounding.GraphReasoningQueryTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;

class OfflineMcpToolContractTest {

    @TempDir
    Path projectDir;

    private ObjectMapper mapper;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("offline-test")
                .enabledTools(Set.of("*"))
                .build();
        PermissionService permissions = new PermissionService();
        permissions.setAutoApproveAll(true);
        context = new ToolContext("stdio-only", agent, permissions, projectDir, new ToolRegistry(mapper));
    }

    @Test
    void everyFormerlyServiceGatedToolUsesItsLocalBackendWhenNoUrlIsConfigured() throws Exception {
        var conjunctArray = mapper.createArrayNode().add("related(a,b)");
        ObjectNode missingDocument = params();
        missingDocument.put("async", false);
        missingDocument.putArray("documents").addObject().put("path", "missing-document.txt");

        List<Invocation> invocations = List.of(
                call(new KnowledgeSearchCliTool(null, mapper), params("query", "offline")),
                call(new KnowledgeStatusCliTool(null, mapper), params()),
                call(new CodeSearchTool(null, mapper), params("query", "OfflineToolRuntime")),
                call(new CodeGraphTool(null, mapper), params("action", "stats")),
                call(new DiffIndexTool(null, mapper), params("action", "stats")),
                call(new RagSearchTool(null, mapper), params("query", "offline")),
                call(new GraphRagSearchTool(null, mapper), params("query", "offline")),
                call(new GraphAggregateTool(null, mapper), params("root_type", "Event")),
                call(new GraphForecastTool(null, mapper), params("root_type", "Event")),
                call(new GraphCentralityTool(null, mapper), params()),
                call(new GraphBayesTool(null, mapper), params("action", "stats")),
                call(new GraphEmbeddingsTool(null, mapper), params("action", "algorithms")),
                call(new GraphSimulateTool(null, mapper), params("action", "scenarios")),
                call(new ProcessMiningCliTool(null, mapper), params("action", "config_get")),
                call(new ProcessMiningCliTool(null, mapper),
                        params("action", "config_update", "config_json", "{\"miningEnabled\":true}")),
                call(new KnowledgeGraphTool(null, mapper), params("action", "list_graphs")),
                call(new GraphReasoningQueryTool(null, mapper), params("operation", "CAPABILITIES")),
                call(new GraphReasonTool(null, mapper), params("target", "related(a,b)")),
                call(new GraphExportTool(null, mapper),
                        params("format", "ascii", "path", projectDir.resolve("offline-graph.txt").toString())),
                call(new GraphImportTool(null, mapper),
                        params("path", projectDir.resolve("missing-graph.kgraph").toString())),
                call(new CrawlDiscoveryTool(null, mapper), params("section", "all")),
                call(new CrawlControlTool(null, mapper), params("action", "status")),
                call(new CrawlDocumentsTool(null, mapper), missingDocument),
                call(new CrawlSourceTool(null, mapper),
                        params("path", "missing-source.txt", "async", false)),
                call(new AskGraphVerifyTool(null, mapper), params("atom", "related(a,b)")),
                call(new AskGraphSynthesizeTool(null, mapper), params("query", "What is related?")),
                call(new AskGraphSubscribeTool(null, mapper),
                        params().set("predicates", mapper.createArrayNode().add("related"))),
                call(new AskGraphRetractTool(null, mapper), params("atomKey", "related(a,b)")),
                call(new AskGraphQueryTool(null, mapper), params().set("conjuncts", conjunctArray)),
                call(new AskGraphMebnTool(null, mapper), params("nodeId", "a")),
                call(new AskGraphFusedTool(null, mapper), params("target", "a")),
                call(new AskGraphExplainTool(null, mapper), params("atom", "related(a,b)")),
                call(new AskGraphClaimTool(null, mapper),
                        params("subject", "a", "predicate", "related", "object", "b")),
                call(new AskGraphAssertTool(null, mapper),
                        params("atom", "related(a,b)", "value", 0.8))
        );

        for (Invocation invocation : invocations) {
            ToolResult result = invocation.tool().execute(invocation.params(), context);
            String output = result.getOutput().toLowerCase();
            String message = invocation.tool().id() + " escaped its stdio-only path: " + result.getOutput();
            assertFalse(output.contains("requires a running"), message);
            assertFalse(output.contains("start kompile"), message);
            assertFalse(output.contains("--url"), message);
            assertFalse(output.contains("cannot connect"), message);
            String description = invocation.tool().description().toLowerCase();
            assertFalse(description.contains("requires kompile"),
                    invocation.tool().id() + " falsely advertises a centralized service requirement: "
                            + invocation.tool().description());
        }
    }

    private Invocation call(CliTool tool, ObjectNode params) {
        return new Invocation(tool, params);
    }

    private ObjectNode params(Object... pairs) {
        ObjectNode params = mapper.createObjectNode();
        for (int index = 0; index < pairs.length; index += 2) {
            String name = (String) pairs[index];
            Object value = pairs[index + 1];
            if (value instanceof String string) {
                params.put(name, string);
            } else if (value instanceof Integer integer) {
                params.put(name, integer);
            } else if (value instanceof Double decimal) {
                params.put(name, decimal);
            } else if (value instanceof Boolean bool) {
                params.put(name, bool);
            } else {
                throw new IllegalArgumentException("Unsupported test parameter: " + value);
            }
        }
        return params;
    }

    private record Invocation(CliTool tool, ObjectNode params) {
    }
}
