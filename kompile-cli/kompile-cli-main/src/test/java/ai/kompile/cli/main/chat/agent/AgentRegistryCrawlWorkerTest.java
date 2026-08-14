/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.tools.KnowledgeSearchCliTool;
import ai.kompile.cli.main.chat.tools.KnowledgeStatusCliTool;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphAssertTool;
import ai.kompile.cli.main.chat.tools.grounding.AskGraphRetractTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlControlTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlDiscoveryTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlDocumentsTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlResultTool;
import ai.kompile.cli.main.chat.tools.grounding.CrawlSourceTool;
import ai.kompile.cli.main.chat.tools.grounding.GraphReasoningQueryTool;
import ai.kompile.cli.main.chat.tools.grounding.ModelRuntimeTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRegistryCrawlWorkerTest {
    @Test
    void offlineWorkerKeepsTheNormalToolAndDelegationSurface() {
        AgentConfig worker = new AgentRegistry().get("crawl-worker");

        assertNotNull(worker);
        assertTrue(worker.getEnabledTools().contains("*"));
        assertTrue(worker.canSpawnSubagents());
        assertTrue(worker.getSystemPrompt().contains("workspace MCP tools remain available"));
        assertTrue(worker.getSystemPrompt().contains("crawl_discover"));
        assertTrue(worker.getSystemPrompt().contains("crawl_documents"));
        assertTrue(worker.getSystemPrompt().contains("crawl_control"));
        assertTrue(worker.getSystemPrompt().contains("crawlResult"));
        assertTrue(worker.getSystemPrompt().contains("nextActions"));
    }

    @Test
    void crawlerCanRunInspectAndActOnTheSameCrawl() {
        AgentConfig crawler = new AgentRegistry().get("crawler");

        assertNotNull(crawler);
        assertTrue(crawler.getEnabledTools().contains("model_runtime"));
        assertTrue(crawler.getEnabledTools().contains("crawl_documents"));
        assertTrue(crawler.getEnabledTools().contains("crawl_control"));
        assertTrue(crawler.getEnabledTools().contains("crawl_result"));
        assertTrue(crawler.getEnabledTools().contains("knowledge_status"));
        assertTrue(crawler.getEnabledTools().contains("knowledge_search"));
        assertTrue(crawler.getEnabledTools().contains("graph_reasoning_query"));
        assertTrue(crawler.getEnabledTools().contains("ask_graph_assert"));
    }

    @Test
    void restrictedAgentsStillReceiveTheCompleteCrawlLifecycle() {
        ObjectMapper mapper = new ObjectMapper();
        ToolRegistry registry = new ToolRegistry(mapper);
        registry.register(new CrawlDiscoveryTool(null, mapper));
        registry.register(new ModelRuntimeTool(mapper));
        registry.register(new CrawlDocumentsTool(null, mapper));
        registry.register(new CrawlSourceTool(null, mapper));
        registry.register(new CrawlControlTool(null, mapper));
        registry.register(new CrawlResultTool((String) null, mapper));
        registry.register(new KnowledgeStatusCliTool(null, mapper));
        registry.register(new KnowledgeSearchCliTool(null, mapper));
        registry.register(new GraphReasoningQueryTool(null, mapper));
        registry.register(new AskGraphAssertTool(null, mapper));
        registry.register(new AskGraphRetractTool(null, mapper));
        AgentConfig restricted = AgentConfig.builder("restricted")
                .enabledTools(Set.of("read"))
                .build();

        Set<String> lifecycleTools = Set.of(
                "crawl_discover", "model_runtime", "crawl_documents", "crawl_source", "crawl_control", "crawl_result",
                "knowledge_status", "knowledge_search", "graph_reasoning_query",
                "ask_graph_assert", "ask_graph_retract");
        for (String toolId : lifecycleTools) {
            assertTrue(registry.getToolsForAgent(restricted).stream()
                    .anyMatch(tool -> toolId.equals(tool.id())), toolId);
        }
    }
}
