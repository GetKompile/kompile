/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Durable, project-scoped storage for the externally visible {@code local-UUID} crawl lifecycle.
 *
 * <p>Knowledge-base summaries are mutable corpus state and are deliberately not used as job
 * history. Every asynchronous execution instead owns an immutable directory under
 * {@code .kompile/state/crawl-jobs/<jobId>/}. State snapshots are replaced atomically while the
 * transcript is append-only JSONL.</p>
 */
final class LocalCrawlJobStore {
    static final String SCHEMA = "kompile-local-crawl-job/v1";
    static final String TRACE_SCHEMA = "kompile-crawl-trace/v1";

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final String STATE_FILE = "state.json";
    private static final String REQUEST_FILE = "request.json";
    private static final String TRACE_FILE = "trace.jsonl";
    private static final long RETENTION_MS = Long.getLong(
            "kompile.crawl.asyncDiskRetentionMs", 86_400_000L);
    private static final int MAX_RETAINED = Math.max(16,
            Integer.getInteger("kompile.crawl.asyncDiskMaxCompleted", 128));
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "apikey", "api_key", "password", "secret", "access_token", "refresh_token",
            "authorization", "credential", "credentials", "privatekey", "private_key");
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Path, AtomicLong> SEQUENCES = new ConcurrentHashMap<>();

    private LocalCrawlJobStore() {
    }

    static Path normalizeRoot(Path root) {
        return root == null ? null : root.toAbsolutePath().normalize();
    }

    static void initialize(Path root, String jobId, String knowledgeBase, JsonNode request) {
        Path normalized = normalizeRoot(root);
        if (normalized == null || !LocalCrawlJobRegistry.isJobId(jobId)) return;
        try {
            prune(normalized);
            Path directory = jobDirectory(normalized, jobId);
            Files.createDirectories(directory);
            ObjectNode submitted = MAPPER.createObjectNode();
            submitted.put("schema", SCHEMA);
            submitted.put("jobId", jobId);
            if (knowledgeBase != null && !knowledgeBase.isBlank()) {
                submitted.put("knowledgeBaseId", knowledgeBase);
            }
            submitted.put("ownerPid", ProcessHandle.current().pid());
            submitted.put("status", "QUEUED");
            submitted.put("terminal", false);
            submitted.put("resultAvailable", false);
            submitted.put("stage", "QUEUED");
            submitted.put("stageDetail", "Waiting for a project-local crawl worker");
            submitted.put("progressPercent", 0);
            submitted.put("createdAt", Instant.now().toString());
            submitted.put("stageUpdatedAt", submitted.path("createdAt").asText());
            if (request != null && !request.isNull()) {
                atomicWrite(directory.resolve(REQUEST_FILE), redact(request));
            }
            persist(normalized, submitted, "JOB_SUBMITTED");
        } catch (IOException e) {
            throw new IllegalStateException("Unable to persist local crawl job " + jobId, e);
        }
    }

    static void persist(Path root, ObjectNode state, String eventType) {
        Path normalized = normalizeRoot(root);
        String jobId = state == null ? null : state.path("jobId").asText(null);
        if (normalized == null || !LocalCrawlJobRegistry.isJobId(jobId)) return;
        Path directory = jobDirectory(normalized, jobId);
        synchronized (lock(directory)) {
            try {
                Files.createDirectories(directory);
                atomicWrite(directory.resolve(STATE_FILE), state);
                ObjectNode event = MAPPER.createObjectNode();
                event.put("eventType", eventType == null ? "JOB_STATE" : eventType);
                event.set("state", state.deepCopy());
                appendTraceLocked(directory, event);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to persist local crawl job " + jobId, e);
            }
        }
    }

    static void appendTrace(Path root, String jobId, ObjectNode event) {
        Path normalized = normalizeRoot(root);
        if (normalized == null || !LocalCrawlJobRegistry.isJobId(jobId) || event == null) return;
        Path directory = jobDirectory(normalized, jobId);
        synchronized (lock(directory)) {
            try {
                Files.createDirectories(directory);
                appendTraceLocked(directory, event);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to append local crawl trace " + jobId, e);
            }
        }
    }

    static Optional<ObjectNode> load(Path root, String jobId) {
        Path normalized = normalizeRoot(root);
        if (normalized == null || !LocalCrawlJobRegistry.isJobId(jobId)) return Optional.empty();
        Path state = jobDirectory(normalized, jobId).resolve(STATE_FILE);
        if (!Files.isRegularFile(state)) return Optional.empty();
        try {
            JsonNode value = MAPPER.readTree(state.toFile());
            return value != null && value.isObject()
                    ? Optional.of((ObjectNode) value) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static Optional<JsonNode> request(Path root, String jobId) {
        Path normalized = normalizeRoot(root);
        if (normalized == null || !LocalCrawlJobRegistry.isJobId(jobId)) return Optional.empty();
        Path request = jobDirectory(normalized, jobId).resolve(REQUEST_FILE);
        if (!Files.isRegularFile(request)) return Optional.empty();
        try {
            return Optional.ofNullable(MAPPER.readTree(request.toFile()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static ArrayNode transcriptEvents(Path root, String jobId) {
        ArrayNode events = MAPPER.createArrayNode();
        Path normalized = normalizeRoot(root);
        if (normalized == null || !LocalCrawlJobRegistry.isJobId(jobId)) return events;
        Path trace = jobDirectory(normalized, jobId).resolve(TRACE_FILE);
        if (!Files.isRegularFile(trace)) return events;
        try {
            for (String line : Files.readAllLines(trace, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) continue;
                try {
                    events.add(MAPPER.readTree(line));
                } catch (IOException malformed) {
                    events.addObject().put("eventType", "MALFORMED_TRACE_LINE")
                            .put("raw", line);
                }
            }
        } catch (IOException ignored) {
            // A missing tail is represented by the current durable state.
        }
        return events;
    }

    static ArrayNode list(Path root) {
        ArrayNode jobs = MAPPER.createArrayNode();
        Path normalized = normalizeRoot(root);
        if (normalized == null) return jobs;
        prune(normalized);
        Path directory = jobsDirectory(normalized);
        if (!Files.isDirectory(directory)) return jobs;
        try (var children = Files.list(directory)) {
            children.filter(Files::isDirectory)
                    .map(path -> load(normalized, path.getFileName().toString()).orElse(null))
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(
                            value -> value.path("createdAt").asText(""), Comparator.reverseOrder()))
                    .forEach(jobs::add);
        } catch (IOException ignored) {
            // Inventory remains best effort; direct job lookup still reports corruption clearly.
        }
        return jobs;
    }

    static ToolResult transcript(Path root, String jobId, ObjectMapper mapper) {
        Optional<ObjectNode> stored = load(root, jobId);
        if (stored.isEmpty()) {
            return ToolResult.error("Unknown project-local crawl job: " + jobId);
        }
        reconcileInterrupted(root, stored.get());
        ObjectNode result = mapper.createObjectNode();
        result.put("schema", "kompile-local-crawl-transcript/v1");
        result.put("backend", "project-local");
        result.put("jobId", jobId);
        JsonNode state = mapper.valueToTree(stored.get());
        result.set("state", state);
        request(root, jobId).ifPresent(value -> result.set("request", mapper.valueToTree(value)));
        result.set("events", mapper.valueToTree(transcriptEvents(root, jobId)));
        Map<String, Object> metadata = Map.of(
                "backend", "project-local",
                "jobId", jobId,
                "status", stored.get().path("status").asText("UNKNOWN"),
                "terminal", stored.get().path("terminal").asBoolean(false));
        return ToolResult.success("crawl_transcript", result.toPrettyString(), metadata);
    }

    static void reconcileInterrupted(Path root, ObjectNode state) {
        if (state == null || state.path("terminal").asBoolean(false)) return;
        long ownerPid = state.path("ownerPid").asLong(-1L);
        if (ownerPid == ProcessHandle.current().pid()) return;
        boolean ownerAlive = ownerPid > 0 && ProcessHandle.of(ownerPid)
                .map(ProcessHandle::isAlive).orElse(false);
        if (ownerAlive) return;
        Instant now = Instant.now();
        state.put("status", "FAILED");
        state.put("terminal", true);
        state.put("resultAvailable", true);
        state.put("stage", "INTERRUPTED");
        state.put("stageDetail", "The owning MCP process exited before the crawl reached a terminal state");
        state.put("progressPercent", 100);
        state.put("finishedAt", now.toString());
        state.put("stageUpdatedAt", now.toString());
        ObjectNode result = state.putObject("result");
        result.put("title", "error");
        result.put("output", "Project-local crawl was interrupted when its owning MCP process exited: "
                + state.path("jobId").asText());
        result.putObject("metadata").put("status", "FAILED");
        result.put("error", true);
        persist(root, state, "JOB_INTERRUPTED");
    }

    static ToolResult storedResult(Path root, String jobId, ObjectMapper mapper) {
        Optional<ObjectNode> loaded = load(root, jobId);
        if (loaded.isEmpty()) return ToolResult.error("Unknown project-local crawl job: " + jobId);
        ObjectNode state = loaded.get();
        reconcileInterrupted(root, state);
        ObjectNode result = state.path("result").isObject()
                ? (ObjectNode) state.path("result") : null;
        if (!state.path("terminal").asBoolean(false) || result == null) {
            return storedStatus(root, jobId, mapper);
        }
        Map<String, Object> metadata = mapper.convertValue(result.path("metadata"), Map.class);
        Map<String, Object> enriched = new java.util.LinkedHashMap<>(metadata);
        enriched.put("backend", "project-local");
        enriched.put("jobId", jobId);
        enriched.put("status", state.path("status").asText());
        enriched.put("terminal", true);
        ObjectNode payload = state.deepCopy();
        CrawlResultHandle.from(payload, "project-local", jobId,
                state.path("knowledgeBaseId").asText(null)).attachTo(enriched);
        return new ToolResult(result.path("title").asText("crawl_result"),
                result.path("output").asText(""), enriched,
                result.path("error").asBoolean(false));
    }

    static ToolResult storedStatus(Path root, String jobId, ObjectMapper mapper) {
        Optional<ObjectNode> loaded = load(root, jobId);
        if (loaded.isEmpty()) return ToolResult.error("Unknown project-local crawl job: " + jobId);
        ObjectNode state = loaded.get();
        reconcileInterrupted(root, state);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("backend", "project-local");
        metadata.put("jobId", jobId);
        metadata.put("status", state.path("status").asText());
        metadata.put("terminal", state.path("terminal").asBoolean(false));
        metadata.put("stage", state.path("stage").asText());
        metadata.put("progressPercent", state.path("progressPercent").asInt());
        ObjectNode payload = state.deepCopy();
        CrawlResultHandle.from(payload, "project-local", jobId,
                state.path("knowledgeBaseId").asText(null)).attachTo(metadata);
        payload.set("crawlResult", mapper.valueToTree(metadata.get("crawlResult")));
        payload.set("nextActions", mapper.valueToTree(metadata.get("nextActions")));
        return ToolResult.success("crawl_status", payload.toPrettyString(), metadata);
    }

    static void prune(Path root) {
        Path directory = jobsDirectory(root);
        if (!Files.isDirectory(directory)) return;
        List<ObjectNode> terminal = new ArrayList<>();
        try (var children = Files.list(directory)) {
            children.filter(Files::isDirectory).forEach(path ->
                    load(root, path.getFileName().toString()).ifPresent(value -> {
                        if (value.path("terminal").asBoolean(false)) terminal.add(value);
                    }));
        } catch (IOException ignored) {
            return;
        }
        Instant cutoff = Instant.ofEpochMilli(System.currentTimeMillis() - RETENTION_MS);
        terminal.sort(Comparator.comparing(value -> value.path("finishedAt").asText("")));
        int excess = Math.max(0, terminal.size() - MAX_RETAINED);
        for (int index = 0; index < terminal.size(); index++) {
            ObjectNode value = terminal.get(index);
            Instant finished = parseInstant(value.path("finishedAt").asText(null));
            if (index < excess || (finished != null && finished.isBefore(cutoff))) {
                deleteTree(jobDirectory(root, value.path("jobId").asText()));
            }
        }
    }

    private static JsonNode redact(JsonNode value) {
        JsonNode copy = value.deepCopy();
        redactInPlace(copy);
        return copy;
    }

    private static void redactInPlace(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode child = node.get(name);
                String normalized = name.toLowerCase(Locale.ROOT).replace("-", "_");
                if (SENSITIVE_KEYS.contains(normalized)
                        || normalized.endsWith("_password")
                        || normalized.endsWith("_secret")
                        || normalized.endsWith("_api_key")) {
                    ((ObjectNode) node).put(name, "***REDACTED***");
                } else {
                    redactInPlace(child);
                }
            }
        } else if (node.isArray()) {
            node.forEach(LocalCrawlJobStore::redactInPlace);
        }
    }

    private static void appendTraceLocked(Path directory, ObjectNode event) throws IOException {
        Path trace = directory.resolve(TRACE_FILE);
        AtomicLong sequence = SEQUENCES.computeIfAbsent(trace, path ->
                new AtomicLong(existingLineCount(path)));
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("schema", TRACE_SCHEMA);
        envelope.put("sequence", sequence.incrementAndGet());
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("timestamp", Instant.now().toString());
        envelope.setAll(event.deepCopy());
        Files.writeString(trace, MAPPER.writeValueAsString(envelope) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private static long existingLineCount(Path path) {
        if (!Files.isRegularFile(path)) return 0L;
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return lines.count();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static Object lock(Path directory) {
        return LOCKS.computeIfAbsent(directory.toAbsolutePath().normalize(), ignored -> new Object());
    }

    private static Path jobsDirectory(Path root) {
        return normalizeRoot(root).resolve(".kompile/state/crawl-jobs");
    }

    private static Path jobDirectory(Path root, String jobId) {
        return jobsDirectory(root).resolve(jobId);
    }

    private static void atomicWrite(Path target, JsonNode value) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), value);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Retention cleanup is best effort.
                }
            });
        } catch (IOException ignored) {
            // Retention cleanup is best effort.
        }
    }
}
