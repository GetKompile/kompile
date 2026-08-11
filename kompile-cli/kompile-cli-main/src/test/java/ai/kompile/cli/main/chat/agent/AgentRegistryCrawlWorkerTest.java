/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.agent;

import org.junit.jupiter.api.Test;

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
    }
}
