/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.app.services.agent;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentBundleRunManagerTest {

    @Test
    void importsRunsAndPersistsNativeToolAndMcpActivity(@TempDir Path temp) throws Exception {
        Assumptions.assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                "the fake CLI uses a POSIX shell");
        Path fakeCli = temp.resolve("kompile-agent");
        Files.writeString(fakeCli, "#!/bin/sh\n"
                + "if [ \"$1\" = \"inspect\" ]; then\n"
                + "  printf '%s\\n' '{\"manifest\":{\"metadata\":{\"name\":\"Fixture Agent\"},\"engine\":\"cli-loop\"},\"entries\":[\"agent.yaml\"]}'\n"
                + "  exit 0\n"
                + "fi\n"
                + "printf '%s\\n' '{\"type\":\"session\",\"session_id\":\"fixture\"}'\n"
                + "printf '%s\\n' '{\"type\":\"text\",\"text\":\"hello\"}'\n"
                + "printf '%s\\n' '{\"type\":\"tool\",\"name\":\"mcp__files__read\",\"ok\":true,\"ms\":4}'\n"
                + "printf '%s\\n' '{\"type\":\"result\",\"text\":\"hello\",\"tools\":1,\"exit\":0}'\n"
                + "exit 0\n");
        assertTrue(fakeCli.toFile().setExecutable(true));
        Path source = temp.resolve("fixture.kagent");
        Files.writeString(source, "fixture");

        AgentBundleRunManager manager = new AgentBundleRunManager(
                fakeCli.toString(), temp.resolve("store").toString());
        manager.initialize();
        try {
            AgentBundleRunManager.BundleSummary bundle = manager.importBundle(source);
            assertEquals("Fixture Agent", bundle.name());
            assertFalse(bundle.entries().isEmpty());

            AgentBundleRunManager.RunSummary started = manager.startRun(
                    bundle.id(), "say hello", 10);
            AgentBundleRunManager.RunSummary terminal = awaitTerminal(manager, started.runId());
            assertEquals(AgentBundleRunManager.RunState.COMPLETED, terminal.state());

            List<AgentBundleRunManager.RunEvent> events = manager.events(started.runId(), 0);
            assertTrue(events.stream().anyMatch(event -> "TEXT_DELTA".equals(event.type())));
            assertTrue(events.stream().anyMatch(event -> "MCP_TOOL_COMPLETED".equals(event.type())));
            assertTrue(events.stream().anyMatch(event -> "RUN_COMPLETED".equals(event.type())));
            assertEquals(terminal.lastSequence(), events.get(events.size() - 1).sequence());
        } finally {
            manager.shutdown();
        }
    }

    private static AgentBundleRunManager.RunSummary awaitTerminal(
            AgentBundleRunManager manager, String runId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        AgentBundleRunManager.RunSummary current;
        do {
            current = manager.getRun(runId).orElseThrow();
            if (current.state().terminal()) return current;
            Thread.sleep(25);
        } while (Instant.now().isBefore(deadline));
        return manager.getRun(runId).orElseThrow();
    }
}
