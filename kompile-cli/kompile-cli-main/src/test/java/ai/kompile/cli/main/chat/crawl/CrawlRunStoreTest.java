/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.crawl;

import ai.kompile.cli.main.chat.agent.AgentRunController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlRunStoreTest {
    @Test
    void persistsAndReloadsLatestControllerCheckpoint() throws Exception {
        String runId = "test-" + UUID.randomUUID();
        CrawlRunStore store = new CrawlRunStore(runId, new ObjectMapper());
        AgentRunController controller = new AgentRunController(
                AgentRunController.Mode.SUPERVISED);

        try {
            store.open(controller, "http://localhost:8080", "coder");
            controller.beforeStep(1);
            controller.afterStep();
            store.checkpoint(controller, "after_step");
            store.event("tool_completed", "crawl_control");

            AgentRunController.Snapshot snapshot = store.latestCheckpoint();
            assertEquals(AgentRunController.State.RUNNING, snapshot.state());
            assertEquals(1, snapshot.completedSteps());
            assertTrue(store.readEvents().stream().anyMatch(line -> line.contains("tool_completed")));
        } finally {
            Files.deleteIfExists(store.eventsFile());
            Files.deleteIfExists(store.runDirectory());
        }
    }
}
