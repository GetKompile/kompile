/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.CodeNavigationGuidance;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The code-index brief an MCP server hands each client in its {@code initialize}
 * {@code instructions}: the canonical navigation guidance plus one status line for
 * the code project the session's working directory resolves to.
 *
 * <p>{@link #start} resolves the project and reads its index metadata on a daemon
 * thread, so {@code initialize} waits at most the budget and serves the guidance
 * alone when the line is late or failed. When the directory belongs to an indexed
 * project rooted below the user's home, the same thread starts one incremental pass
 * through {@link BackgroundIndexService#refreshOnSessionStart}; it never builds an
 * index and starts no watcher.
 */
public final class CodeIndexSessionBrief {

    /** The canonical navigation rule, as the built-in agent prompts carry it. */
    public static final String GUIDANCE = CodeNavigationGuidance.RULE;

    /** Longest {@code initialize} waits for the status line. */
    public static final long DEFAULT_BUDGET_MS = 300;

    /** Ceiling for the whole instructions text. */
    static final int MAX_CHARS = 1200;

    static final String NO_INDEX = "no index yet - run local_code_index action=index from the repository root";

    /** Resolver sources that name an existing index rather than a guess from the directory name. */
    private static final Set<String> INDEXED_SOURCES = Set.of("project-manifest", "registration", "index-root");

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();

    private final CompletableFuture<String> statusLine = new CompletableFuture<>();
    private final CompletableFuture<Void> settled = new CompletableFuture<>();
    private final long budgetMs;

    private CodeIndexSessionBrief(long budgetMs) {
        this.budgetMs = budgetMs;
    }

    /** Start resolving {@code workingDirectory} in the background; returns immediately. */
    public static CodeIndexSessionBrief start(Path workingDirectory) {
        return start(workingDirectory, dir -> ProjectIdResolver.resolve(null, dir),
                CodeIndexSessionBrief::requestRefresh, DEFAULT_BUDGET_MS);
    }

    /** Test seam: inject the resolver, the refresh request and the budget. */
    static CodeIndexSessionBrief start(Path workingDirectory,
                                       Function<Path, ProjectIdResolver.Resolution> resolver,
                                       Consumer<String> refresh, long budgetMs) {
        CodeIndexSessionBrief brief = new CodeIndexSessionBrief(budgetMs);
        Thread worker = new Thread(() -> brief.prepare(workingDirectory, resolver, refresh),
                "code-index-session-brief");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (RuntimeException | OutOfMemoryError e) {
            // No worker thread to spare: the session still gets the guidance.
            brief.statusLine.complete(null);
            brief.settled.complete(null);
        }
        return brief;
    }

    /**
     * The guidance plus the status line, or the guidance alone when the line is not
     * ready within the budget or could not be built. Never throws.
     */
    public String instructions() {
        try {
            return compose(statusLine.get(budgetMs, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return GUIDANCE;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            return GUIDANCE;
        }
    }

    /** Completes once the status line is settled and any refresh request has returned. */
    CompletableFuture<Void> settled() {
        return settled;
    }

    private void prepare(Path workingDirectory, Function<Path, ProjectIdResolver.Resolution> resolver,
                         Consumer<String> refresh) {
        try {
            Path wd = (workingDirectory != null ? workingDirectory : Path.of("."))
                    .toAbsolutePath().normalize();
            Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
            ProjectIdResolver.Resolution resolution = resolver.apply(wd);
            JsonNode metadata = resolution != null && INDEXED_SOURCES.contains(resolution.source())
                    ? readMetadata(resolution.projectId()) : null;
            statusLine.complete(describe(wd, home, resolution, metadata, Instant.now()));
            if (refreshable(wd, home, resolution, metadata)) {
                refresh.accept(resolution.projectId());
            }
        } catch (RuntimeException e) {
            CodeIndexDiagnostics.alert("[code-index] session brief for " + workingDirectory + " failed: "
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } finally {
            statusLine.complete(null);
            settled.complete(null);
        }
    }

    /** The guidance and the status line, capped at {@link #MAX_CHARS}. */
    static String compose(String line) {
        if (line == null || line.isBlank()) return GUIDANCE;
        String text = GUIDANCE + "\n\n" + line;
        return text.length() <= MAX_CHARS ? text : text.substring(0, MAX_CHARS - 3) + "...";
    }

    /** One status line for the resolved project, or null when the guidance alone fits better. */
    static String describe(Path wd, Path home, ProjectIdResolver.Resolution resolution,
                           JsonNode metadata, Instant now) {
        if (resolution == null) return null;
        String via = "project_id=" + resolution.projectId() + " (resolved via " + resolution.source() + ")";
        if (metadata != null) {
            String details = details(resolution.projectId(), metadata, now);
            String root = metadata.path("rootPath").asText("").trim();
            String withRoot = "Code index: " + via + (root.isEmpty() ? "" : ", root " + root) + details + ".";
            return GUIDANCE.length() + 2 + withRoot.length() <= MAX_CHARS
                    ? withRoot : "Code index: " + via + details + ".";
        }
        // Never point an agent at indexing the home directory or one of its ancestors.
        if (home.startsWith(wd)) return null;
        if (INDEXED_SOURCES.contains(resolution.source())) {
            return "Code index: " + via + "; index metadata unreadable - run local_code_index "
                    + "action=index from the repository root.";
        }
        if (resolution.source().endsWith("-unindexed")) {
            return "Code index: " + via + ": " + NO_INDEX + ".";
        }
        return "Code index: " + NO_INDEX + ".";
    }

    /**
     * True only for an existing index whose recorded root is a real directory that is
     * neither the user's home nor above it, reached from a directory below home.
     */
    static boolean refreshable(Path wd, Path home, ProjectIdResolver.Resolution resolution, JsonNode metadata) {
        if (resolution == null || metadata == null || home.startsWith(wd)
                || !INDEXED_SOURCES.contains(resolution.source())) {
            return false;
        }
        String rootPath = metadata.path("rootPath").asText("").trim();
        if (rootPath.isEmpty()) return false;
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        return !home.startsWith(root) && Files.isDirectory(root);
    }

    /** One incremental pass over an existing index; runs on the brief's thread. */
    static void requestRefresh(String projectId) {
        BackgroundIndexService.getInstance().refreshOnSessionStart(projectId);
    }

    static String age(Instant then, Instant now) {
        Duration elapsed = Duration.between(then, now);
        if (elapsed.toMinutes() < 1) return "just now";
        if (elapsed.toHours() < 1) return elapsed.toMinutes() + "m ago";
        if (elapsed.toHours() < 48) return elapsed.toHours() + "h ago";
        return elapsed.toDays() + "d ago";
    }

    private static String details(String projectId, JsonNode metadata, Instant now) {
        StringBuilder details = new StringBuilder();
        JsonNode files = metadata.path("filesProcessed");
        if (files.isNumber()) details.append(", ").append(files.asLong()).append(" files");
        Instant indexedAt = parseInstant(metadata.path("indexedAt").asText(""));
        if (indexedAt != null) {
            details.append(", last indexed ").append(indexedAt.truncatedTo(ChronoUnit.SECONDS))
                    .append(" (").append(age(indexedAt, now)).append(')');
        }
        String health = health(projectId);
        if (health != null) details.append(", health ").append(health);
        return details.toString();
    }

    /** Health when cheaply known: an unfinished update, or this process's last integrity check. */
    private static String health(String projectId) {
        if (Files.exists(LocalCodeIndexer.getIndexDir(projectId).resolve(IndexFileStore.UPDATE_PENDING_FILE))) {
            return "update pending (in progress or interrupted)";
        }
        // "STATUS at instant: detail", or a not-checked note before this process's first check.
        String status = IndexMaintenance.status(projectId);
        int at = status.indexOf(" at ");
        return at > 0 ? status.substring(0, at) : null;
    }

    /** The metadata object, or null when it is absent, empty (a torn write) or not a JSON object. */
    private static JsonNode readMetadata(String projectId) {
        try {
            Path file = LocalCodeIndexer.getIndexDir(projectId).resolve(IndexFileStore.METADATA_FILE);
            if (!Files.isRegularFile(file)) return null;
            JsonNode metadata = MAPPER.readTree(file.toFile());
            return metadata != null && metadata.isObject() ? metadata : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Instant parseInstant(String text) {
        try {
            return text.isBlank() ? null : Instant.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
