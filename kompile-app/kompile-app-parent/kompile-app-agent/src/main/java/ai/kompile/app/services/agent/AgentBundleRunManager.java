/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.app.services.agent;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * App-owned lifecycle for packaged agents.
 *
 * <p>The CLI bundle implementation remains the authority for manifest validation and
 * materialisation.  This service gives the app a durable catalog and run/event API by
 * invoking the CLI through an argument-safe {@link ProcessBuilder}; it never evaluates a
 * bundle command through a shell.</p>
 */
@Service
public class AgentBundleRunManager {

    private static final Logger log = LoggerFactory.getLogger(AgentBundleRunManager.class);
    private static final int MAX_INSPECT_OUTPUT = 8 * 1024 * 1024;
    private static final int MAX_EVENT_LINE = 2 * 1024 * 1024;
    private static final long MAX_BUNDLE_BYTES = 256L * 1024L * 1024L;
    private static final long DEFAULT_INSPECT_TIMEOUT_SECONDS = 30;

    private final ObjectMapper mapper = JsonUtils.standardMapper();
    private final String cliCommand;
    private final Path storeRoot;
    private final Path bundlesRoot;
    private final Path runsRoot;
    private final Path bundleCatalogPath;
    private final Path runCatalogPath;

    private final Map<String, StoredBundle> bundles = new ConcurrentHashMap<>();
    private final Map<String, StoredRun> runs = new ConcurrentHashMap<>();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();
    private final Map<String, Object> runLocks = new ConcurrentHashMap<>();
    private final Object catalogLock = new Object();

    private final ExecutorService runExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "agent-bundle-run");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService outputExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "agent-bundle-output");
        thread.setDaemon(true);
        return thread;
    });

    public AgentBundleRunManager(
            @Value("${kompile.agent.bundle.cli:kompile-agent}") String cliCommand,
            @Value("${kompile.agent.bundle.store:}") String configuredStore) {
        this.cliCommand = requireCommand(cliCommand);
        this.storeRoot = configuredStore == null || configuredStore.isBlank()
                ? Path.of(System.getProperty("user.home"), ".kompile", "agent-bundles")
                : Path.of(configuredStore).toAbsolutePath().normalize();
        this.bundlesRoot = storeRoot.resolve("bundles");
        this.runsRoot = storeRoot.resolve("runs");
        this.bundleCatalogPath = storeRoot.resolve("bundles.json");
        this.runCatalogPath = storeRoot.resolve("runs.json");
    }

    @PostConstruct
    public void initialize() {
        try {
            Files.createDirectories(bundlesRoot);
            Files.createDirectories(runsRoot);
            loadCatalogs();
            recoverInterruptedRuns();
        } catch (IOException e) {
            throw new IllegalStateException("Could not initialize agent bundle store at " + storeRoot, e);
        }
    }

    /** Import and validate an uploaded bundle archive. */
    public BundleSummary importBundle(InputStream input, String originalFilename) throws IOException {
        Objects.requireNonNull(input, "input");
        String id = UUID.randomUUID().toString();
        String filename = safeFilename(originalFilename);
        Path bundleDirectory = bundlesRoot.resolve(id);
        Path temporary = bundlesRoot.resolve(id + ".upload");
        Files.createDirectories(bundleDirectory);
        try {
            copyLimited(input, temporary);
            Inspection inspection = inspect(temporary);
            Path storedPath = bundleDirectory.resolve(filename);
            Files.move(temporary, storedPath, StandardCopyOption.REPLACE_EXISTING);
            StoredBundle stored = StoredBundle.from(id, storedPath, filename, inspection,
                    sha256(storedPath), Instant.now());
            bundles.put(id, stored);
            persistBundles();
            return stored.summary();
        } catch (Exception e) {
            Files.deleteIfExists(temporary);
            deleteTree(bundleDirectory);
            if (e instanceof IOException io) throw io;
            throw new IOException("Could not validate agent bundle: " + e.getMessage(), e);
        }
    }

    /** Register an existing local bundle by copying it into the managed store. */
    public BundleSummary importBundle(Path source) throws IOException {
        Path normalized = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) throw new IOException("Bundle is not a regular file: " + source);
        try (InputStream input = Files.newInputStream(normalized)) {
            return importBundle(input, normalized.getFileName().toString());
        }
    }

    public List<BundleSummary> listBundles() {
        return bundles.values().stream()
                .map(StoredBundle::summary)
                .sorted(Comparator.comparing(BundleSummary::importedAt).reversed())
                .toList();
    }

    public Optional<BundleSummary> getBundle(String bundleId) {
        return Optional.ofNullable(bundles.get(bundleId)).map(StoredBundle::summary);
    }

    /** Discover the live MCP catalog declared by a managed bundle. */
    public List<String> discoverTools(String bundleId) throws IOException, InterruptedException {
        StoredBundle bundle = bundles.get(bundleId);
        if (bundle == null) throw new IllegalArgumentException("Unknown agent bundle: " + bundleId);
        Process process = new ProcessBuilder(cliCommand, "tools", bundle.path.toString(), "--json")
                .redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && output.length() < MAX_INSPECT_OUTPUT) {
                output.append(line).append('\n');
            }
        }
        if (!process.waitFor(DEFAULT_INSPECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("MCP tool discovery timed out");
        }
        if (process.exitValue() != 0) throw new IOException("MCP tool discovery failed: " + output);
        JsonNode result = parseJsonOutput(output.toString());
        if (!result.isArray()) throw new IOException("MCP tool discovery returned no JSON array");
        List<String> tools = new ArrayList<>();
        result.forEach(tool -> tools.add(tool.asText()));
        return tools;
    }

    public boolean deleteBundle(String bundleId) throws IOException {
        StoredBundle bundle = bundles.get(bundleId);
        if (bundle == null) return false;
        boolean active = runs.values().stream().anyMatch(run -> bundleId.equals(run.bundleId)
                && !run.state.terminal());
        if (active) throw new IllegalStateException("Bundle has an active run: " + bundleId);
        bundles.remove(bundleId);
        deleteTree(bundle.path.getParent());
        persistBundles();
        return true;
    }

    /** Start a managed run and return immediately with its durable run id. */
    public RunSummary startRun(String bundleId, String prompt, long timeoutSeconds) {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("prompt is required");
        StoredBundle bundle = bundles.get(bundleId);
        if (bundle == null) throw new IllegalArgumentException("Unknown agent bundle: " + bundleId);
        long boundedTimeout = Math.max(0, Math.min(timeoutSeconds, TimeUnit.DAYS.toSeconds(1)));

        String runId = UUID.randomUUID().toString();
        StoredRun run = StoredRun.start(runId, bundleId, prompt, Instant.now());
        runs.put(runId, run);
        sequenceCounters.put(runId, new AtomicLong());
        persistRun(run);
        appendEvent(runId, "RUN_STARTED", Map.of(
                "bundleId", bundleId,
                "prompt", prompt,
                "timeoutSeconds", boundedTimeout));
        runExecutor.submit(() -> executeRun(run, bundle, boundedTimeout));
        return run.summary();
    }

    public List<RunSummary> listRuns() {
        return runs.values().stream()
                .map(StoredRun::summary)
                .sorted(Comparator.comparing(RunSummary::startedAt).reversed())
                .toList();
    }

    public Optional<RunSummary> getRun(String runId) {
        return Optional.ofNullable(runs.get(runId)).map(StoredRun::summary);
    }

    public List<RunEvent> events(String runId, long afterSequence) throws IOException {
        if (!runs.containsKey(runId)) return List.of();
        Path eventPath = eventPath(runId);
        if (!Files.isRegularFile(eventPath)) return List.of();
        List<RunEvent> result = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(eventPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > MAX_EVENT_LINE) continue;
                try {
                    RunEvent event = mapper.readValue(line, RunEvent.class);
                    if (event.sequence() > afterSequence) result.add(event);
                } catch (JsonProcessingException e) {
                    log.debug("Ignoring incomplete agent run event for {}", runId);
                }
            }
        }
        return result;
    }

    /** Create an SSE stream with replay from a sequence number followed by live events. */
    public SseEmitter streamEvents(String runId, long afterSequence) throws IOException {
        StoredRun run = runs.get(runId);
        if (run == null) return null;
        SseEmitter emitter = new SseEmitter(0L);
        CopyOnWriteArrayList<SseEmitter> runSubscribers =
                subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>());
        runSubscribers.add(emitter);
        emitter.onCompletion(() -> removeSubscriber(runId, emitter));
        emitter.onTimeout(() -> removeSubscriber(runId, emitter));
        emitter.onError(ignored -> removeSubscriber(runId, emitter));

        for (RunEvent event : events(runId, afterSequence)) send(emitter, event);
        if (run.state.terminal()) {
            removeSubscriber(runId, emitter);
            emitter.complete();
        }
        return emitter;
    }

    public Optional<RunSummary> cancelRun(String runId) {
        StoredRun run = runs.get(runId);
        if (run == null) return Optional.empty();
        synchronized (lockFor(runId)) {
            if (run.state.terminal()) return Optional.of(run.summary());
            run.cancelRequested = true;
            Process process = processes.get(runId);
            if (process != null) process.destroyForcibly();
            setState(run, RunState.CANCELLED, null, null);
            appendEvent(runId, "RUN_CANCELLED", Map.of());
        }
        return Optional.of(run.summary());
    }

    @PreDestroy
    public void shutdown() {
        processes.values().forEach(Process::destroyForcibly);
        runExecutor.shutdownNow();
        outputExecutor.shutdownNow();
    }

    private void executeRun(StoredRun run, StoredBundle bundle, long timeoutSeconds) {
        Process process = null;
        Future<?> outputReader = null;
        try {
            setState(run, RunState.RUNNING, null, null);
            List<String> command = List.of(cliCommand, "run", bundle.path.toString(), run.prompt,
                    "--timeout", Long.toString(timeoutSeconds));
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true);
            builder.environment().put("KOMPILE_AGENT_RUN_ID", run.runId);
            builder.environment().put("KOMPILE_AGENT_BUNDLE_ID", run.bundleId);
            process = builder.start();
            processes.put(run.runId, process);
            Process managedProcess = process;
            outputReader = outputExecutor.submit(() -> readOutput(run, managedProcess));

            boolean finished = timeoutSeconds <= 0
                    ? process.waitFor(1, TimeUnit.DAYS)
                    : process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                setState(run, RunState.FAILED, 124, "Agent bundle run timed out");
                appendEvent(run.runId, "RUN_FAILED", Map.of("error", "timeout", "exitCode", 124));
            } else {
                int exitCode = process.exitValue();
                if (run.cancelRequested) {
                    setState(run, RunState.CANCELLED, exitCode, null);
                } else if (exitCode == 0) {
                    setState(run, RunState.COMPLETED, exitCode, null);
                    appendEvent(run.runId, "RUN_COMPLETED", Map.of("exitCode", exitCode));
                } else {
                    setState(run, RunState.FAILED, exitCode, "Agent exited with code " + exitCode);
                    appendEvent(run.runId, "RUN_FAILED", Map.of("exitCode", exitCode));
                }
            }
            if (outputReader != null) outputReader.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) process.destroyForcibly();
            setState(run, RunState.CANCELLED, null, "Run interrupted");
            appendEvent(run.runId, "RUN_CANCELLED", Map.of("reason", "interrupted"));
        } catch (Exception e) {
            log.warn("Agent bundle run {} failed: {}", run.runId, e.getMessage());
            if (process != null) process.destroyForcibly();
            setState(run, RunState.FAILED, null, e.getMessage());
            appendEvent(run.runId, "RUN_FAILED", Map.of("error", String.valueOf(e.getMessage())));
        } finally {
            if (outputReader != null && !outputReader.isDone()) outputReader.cancel(true);
            processes.remove(run.runId);
            persistRun(run);
            completeSubscribersIfTerminal(run);
        }
    }

    private void readOutput(StoredRun run, Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > MAX_EVENT_LINE) line = line.substring(0, MAX_EVENT_LINE);
                JsonNode json = parseEventJson(line);
                if (json == null) {
                    appendEvent(run.runId, line.startsWith("[MCP]") ? "MCP_DISCOVERY" : "OUTPUT",
                            Map.of("text", line));
                } else {
                    appendEvent(run.runId, nativeEventType(json),
                            Map.of("native", json, "text", json.path("text").asText("")));
                }
            }
        } catch (IOException e) {
            if (!run.state.terminal()) appendEvent(run.runId, "LOG",
                    Map.of("level", "WARN", "message", String.valueOf(e.getMessage())));
        }
    }

    private JsonNode parseEventJson(String line) {
        String candidate = line == null ? "" : line.trim();
        if (!candidate.startsWith("{")) return null;
        try {
            JsonNode node = mapper.readTree(candidate);
            return node != null && node.isObject() && node.has("type") ? node : null;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private static String nativeEventType(JsonNode event) {
        String type = event.path("type").asText();
        if ("tool".equals(type) && event.path("name").asText("").startsWith("mcp__")) {
            return "MCP_TOOL_COMPLETED";
        }
        return switch (type) {
            case "session" -> "SESSION_ATTACHED";
            case "text" -> "TEXT_DELTA";
            case "tool" -> "TOOL_COMPLETED";
            case "result" -> "RESULT";
            case "error" -> "AGENT_ERROR";
            default -> "NATIVE_EVENT";
        };
    }

    private Inspection inspect(Path source) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(cliCommand, "inspect", source.toString(), "--json")
                .redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && output.length() < MAX_INSPECT_OUTPUT) {
                output.append(line).append('\n');
            }
        }
        if (!process.waitFor(DEFAULT_INSPECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Bundle inspection timed out");
        }
        if (process.exitValue() != 0) throw new IOException("Bundle inspection failed: " + output);
        JsonNode result = parseJsonOutput(output.toString());
        JsonNode manifest = result.path("manifest");
        if (!manifest.isObject()) throw new IOException("Bundle inspection returned no manifest");
        List<String> entries = new ArrayList<>();
        result.path("entries").forEach(entry -> entries.add(entry.asText()));
        return new Inspection(manifest, entries);
    }

    private JsonNode parseJsonOutput(String output) throws IOException {
        try {
            return mapper.readTree(output);
        } catch (JsonProcessingException ignored) {
            String[] lines = output.split("\\R");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.startsWith("{") || line.startsWith("[")) {
                    try { return mapper.readTree(line); } catch (JsonProcessingException ignoredLine) { }
                }
            }
            throw new IOException("Bundle inspection did not return JSON: " + output);
        }
    }

    private void appendEvent(String runId, String type, Map<String, Object> payload) {
        StoredRun run = runs.get(runId);
        if (run == null) return;
        synchronized (lockFor(runId)) {
            long sequence = sequenceCounters.computeIfAbsent(runId, ignored -> new AtomicLong()).incrementAndGet();
            RunEvent event = new RunEvent(sequence, runId, Instant.now(), type,
                    mapper.valueToTree(payload));
            try {
                Files.createDirectories(runsRoot);
                Files.writeString(eventPath(runId), mapper.writeValueAsString(event) + "\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                run.lastSequence = sequence;
                persistRun(run);
                for (SseEmitter emitter : subscribers.getOrDefault(runId, new CopyOnWriteArrayList<>())) {
                    send(emitter, event);
                }
            } catch (Exception e) {
                log.warn("Could not persist agent run event {}: {}", runId, e.getMessage());
            }
        }
    }

    private void send(SseEmitter emitter, RunEvent event) {
        try {
            emitter.send(SseEmitter.event().id(Long.toString(event.sequence()))
                    .name(event.type()).data(event));
        } catch (Exception e) {
            try { emitter.completeWithError(e); } catch (Exception ignored) { }
        }
    }

    private void completeSubscribersIfTerminal(StoredRun run) {
        if (!run.state.terminal()) return;
        for (SseEmitter emitter : subscribers.getOrDefault(run.runId, new CopyOnWriteArrayList<>())) {
            try { emitter.complete(); } catch (Exception ignored) { }
        }
        subscribers.remove(run.runId);
    }

    private void removeSubscriber(String runId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> current = subscribers.get(runId);
        if (current != null) {
            current.remove(emitter);
            if (current.isEmpty()) subscribers.remove(runId, current);
        }
    }

    private void setState(StoredRun run, RunState state, Integer exitCode, String error) {
        synchronized (lockFor(run.runId)) {
            if (run.state.terminal() && state != RunState.CANCELLED) return;
            run.state = state;
            run.exitCode = exitCode;
            run.error = error;
            if (state.terminal()) run.finishedAt = Instant.now();
            persistRun(run);
            if (state == RunState.RUNNING) appendEvent(run.runId, "RUN_RUNNING", Map.of());
        }
    }

    private Object lockFor(String runId) { return runLocks.computeIfAbsent(runId, ignored -> new Object()); }

    private void loadCatalogs() throws IOException {
        if (Files.isRegularFile(bundleCatalogPath)) {
            List<StoredBundle> loaded = mapper.readValue(bundleCatalogPath.toFile(),
                    new TypeReference<List<StoredBundle>>() { });
            loaded.forEach(bundle -> {
                bundle.path = Path.of(bundle.pathString).toAbsolutePath().normalize();
                if (bundle.path.startsWith(bundlesRoot) && Files.isRegularFile(bundle.path)) {
                    bundles.put(bundle.id, bundle);
                }
            });
        }
        if (Files.isRegularFile(runCatalogPath)) {
            List<StoredRun> loaded = mapper.readValue(runCatalogPath.toFile(),
                    new TypeReference<List<StoredRun>>() { });
            loaded.forEach(run -> {
                runs.put(run.runId, run);
                sequenceCounters.put(run.runId, new AtomicLong(run.lastSequence));
            });
        }
    }

    private void recoverInterruptedRuns() {
        for (StoredRun run : runs.values()) {
            if (run.state == RunState.STARTING || run.state == RunState.RUNNING) {
                run.state = RunState.ORPHANED;
                run.error = "Application restarted while the run was active";
                run.finishedAt = Instant.now();
                appendEvent(run.runId, "RUN_ORPHANED", Map.of("reason", "application-restart"));
                persistRun(run);
            }
        }
    }

    private void persistBundles() throws IOException {
        synchronized (catalogLock) {
            Files.createDirectories(storeRoot);
            mapper.writerWithDefaultPrettyPrinter().writeValue(bundleCatalogPath.toFile(), new ArrayList<>(bundles.values()));
        }
    }

    private void persistRun(StoredRun run) {
        synchronized (catalogLock) {
            try {
                Files.createDirectories(storeRoot);
                mapper.writerWithDefaultPrettyPrinter().writeValue(runCatalogPath.toFile(), new ArrayList<>(runs.values()));
            } catch (IOException e) {
                log.warn("Could not persist agent run {}: {}", run.runId, e.getMessage());
            }
        }
    }

    private Path eventPath(String runId) { return runsRoot.resolve(runId + ".jsonl"); }

    private static String requireCommand(String command) {
        if (command == null || command.isBlank() || command.contains("\n") || command.contains("\r")) {
            throw new IllegalArgumentException("kompile.agent.bundle.cli must be a command path");
        }
        return command.trim();
    }

    private static String safeFilename(String filename) {
        String value = filename == null || filename.isBlank() ? "agent.kagent" : filename;
        value = Path.of(value).getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        return value.isBlank() ? "agent.kagent" : value;
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format("%02x", value));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static void copyLimited(InputStream input, Path target) throws IOException {
        try (OutputStream output = Files.newOutputStream(target, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                total += read;
                if (total > MAX_BUNDLE_BYTES) {
                    throw new IOException("Agent bundle exceeds the " + MAX_BUNDLE_BYTES + " byte limit");
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException e) { throw new TreeDeleteException(e); }
            });
        } catch (TreeDeleteException e) { throw e.ioException; }
    }

    public enum RunState {
        STARTING, RUNNING, COMPLETED, FAILED, CANCELLED, ORPHANED;
        public boolean terminal() { return this == COMPLETED || this == FAILED || this == CANCELLED || this == ORPHANED; }
    }

    public record BundleSummary(String id, String name, String engine, String digest,
                                Instant importedAt, String filename, List<String> entries,
                                JsonNode manifest) { }

    public record RunSummary(String runId, String bundleId, String prompt, RunState state,
                              Instant startedAt, Instant finishedAt, Integer exitCode,
                              String error, long lastSequence) { }

    public record RunEvent(long sequence, String runId, Instant timestamp,
                           String type, JsonNode payload) { }

    private record Inspection(JsonNode manifest, List<String> entries) { }

    private static final class StoredBundle {
        public String id;
        public String name;
        public String engine;
        public String digest;
        public Instant importedAt;
        public String filename;
        public String pathString;
        public List<String> entries = List.of();
        public JsonNode manifest;
        private transient Path path;

        static StoredBundle from(String id, Path path, String filename, Inspection inspection,
                                 String digest, Instant importedAt) {
            StoredBundle result = new StoredBundle();
            result.id = id;
            result.name = inspection.manifest.path("metadata").path("name").asText(id);
            result.engine = inspection.manifest.path("engine").asText("cli-loop");
            result.digest = digest;
            result.importedAt = importedAt;
            result.filename = filename;
            result.path = path;
            result.pathString = path.toString();
            result.entries = List.copyOf(inspection.entries);
            result.manifest = inspection.manifest;
            return result;
        }

        BundleSummary summary() {
            return new BundleSummary(id, name, engine, digest, importedAt, filename, entries, manifest);
        }
    }

    private static final class StoredRun {
        public String runId;
        public String bundleId;
        public String prompt;
        public RunState state;
        public Instant startedAt;
        public Instant finishedAt;
        public Integer exitCode;
        public String error;
        public long lastSequence;
        private transient volatile boolean cancelRequested;

        static StoredRun start(String runId, String bundleId, String prompt, Instant startedAt) {
            StoredRun result = new StoredRun();
            result.runId = runId;
            result.bundleId = bundleId;
            result.prompt = prompt;
            result.state = RunState.STARTING;
            result.startedAt = startedAt;
            return result;
        }

        RunSummary summary() {
            return new RunSummary(runId, bundleId, prompt, state, startedAt, finishedAt,
                    exitCode, error, lastSequence);
        }
    }

    private static final class TreeDeleteException extends RuntimeException {
        private final IOException ioException;
        private TreeDeleteException(IOException ioException) { this.ioException = ioException; }
    }
}
