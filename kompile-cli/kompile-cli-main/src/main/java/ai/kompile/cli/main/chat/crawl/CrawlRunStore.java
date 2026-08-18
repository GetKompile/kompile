/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.crawl;

import ai.kompile.cli.main.chat.agent.AgentRunController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

/**
 * Durable, append-only checkpoints for production crawl sessions.
 *
 * <p>ChatHistory remains the human-readable transcript. This store is the
 * machine-readable run ledger used to resume/inspect control state without
 * coupling the CLI to backend job persistence.</p>
 */
public final class CrawlRunStore {
    private final String runId;
    private final Path runDirectory;
    private final Path eventsFile;
    private final ObjectMapper mapper;

    public CrawlRunStore(String runId, ObjectMapper mapper) {
        this.runId = runId;
        this.mapper = mapper;
        String configuredRoot = System.getProperty("kompile.crawl.runRoot");
        if (configuredRoot == null || configuredRoot.isBlank()) {
            configuredRoot = System.getenv("KOMPILE_CRAWL_RUN_ROOT");
        }
        Path root = configuredRoot == null || configuredRoot.isBlank()
                ? Path.of(System.getProperty("user.home"), ".kompile", "crawl-runs")
                : Path.of(configuredRoot);
        this.runDirectory = root.resolve(runId);
        this.eventsFile = runDirectory.resolve("events.jsonl");
    }

    public synchronized void open(AgentRunController controller, String baseUrl, String agent) {
        try {
            Files.createDirectories(runDirectory);
            append("run_started", "baseUrl", baseUrl, "agent", agent);
            checkpoint(controller, "started");
        } catch (IOException ignored) {
            // Run persistence is best effort and must not stop an active crawl.
        }
    }

    public synchronized void checkpoint(AgentRunController controller, String phase) {
        AgentRunController.Snapshot snapshot = controller.snapshot();
        try {
            Files.createDirectories(runDirectory);
            ObjectNode node = mapper.createObjectNode();
            node.put("timestamp", Instant.now().toString());
            node.put("type", "checkpoint");
            node.put("phase", phase == null ? "" : phase);
            node.put("mode", snapshot.mode().name());
            node.put("state", snapshot.state().name());
            node.put("completedSteps", snapshot.completedSteps());
            node.put("toolCalls", snapshot.toolCalls());
            node.put("approvalPending", snapshot.approvalPending());
            appendNode(node);
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    public synchronized void event(String type, String detail) {
        try {
            Files.createDirectories(runDirectory);
            append(type, "detail", detail == null ? "" : detail);
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    public Path runDirectory() { return runDirectory; }
    public Path eventsFile() { return eventsFile; }
    public String runId() { return runId; }

    public List<String> readEvents() throws IOException {
        if (!Files.exists(eventsFile)) return List.of();
        return Files.readAllLines(eventsFile, StandardCharsets.UTF_8);
    }

    /** Return the last valid checkpoint, or {@code null} when the run is new/corrupt. */
    public synchronized AgentRunController.Snapshot latestCheckpoint() {
        try {
            List<String> lines = readEvents();
            for (int i = lines.size() - 1; i >= 0; i--) {
                JsonNode node = mapper.readTree(lines.get(i));
                if (node == null || !"checkpoint".equals(node.path("type").asText())) continue;
                AgentRunController.Mode mode = parseMode(node.path("mode").asText());
                AgentRunController.State state = parseState(node.path("state").asText());
                return new AgentRunController.Snapshot(mode, state,
                        node.path("completedSteps").asInt(0), node.path("toolCalls").asInt(0),
                        node.path("approvalPending").asBoolean(false));
            }
        } catch (IOException | RuntimeException ignored) {
            // A damaged ledger should not prevent transcript resume.
        }
        return null;
    }

    private AgentRunController.Mode parseMode(String value) {
        try {
            return AgentRunController.Mode.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return AgentRunController.Mode.SUPERVISED;
        }
    }

    private AgentRunController.State parseState(String value) {
        try {
            return AgentRunController.State.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            return AgentRunController.State.RUNNING;
        }
    }

    private void append(String type, String key, String value, String... extra) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("timestamp", Instant.now().toString());
        node.put("type", type);
        node.put(key, value);
        for (int i = 0; i + 1 < extra.length; i += 2) {
            node.put(extra[i], extra[i + 1]);
        }
        appendNode(node);
    }

    private void appendNode(ObjectNode node) throws IOException {
        Files.writeString(eventsFile, mapper.writeValueAsString(node) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
