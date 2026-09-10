/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.mcp;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatSessionContext;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tui.KompileTui;
import ai.kompile.utils.AnsiConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Owns one trusted, model-free MCP dashboard in Standard Chat.
 *
 * <p>The controller never accepts a tool id from a user or model. It can call only
 * the exact source retained by {@link McpBundleToolLoader} after project-workspace
 * trust succeeds. Refreshes occur once at startup, on explicit operator request,
 * or after an exact configured successful tool call; there is no polling loop.</p>
 */
public final class McpDashboardController implements AutoCloseable {
    static final String SCHEMA_VERSION = "kompile.dashboard.v1";
    static final int MAX_RESPONSE_CHARS = 65_536;
    static final int MAX_TITLE_CHARS = 80;
    static final int MAX_LINES = 8;
    static final int MAX_LINE_CHARS = 100;
    static final long SOURCE_TIMEOUT_MILLIS = 10_000L;

    @FunctionalInterface
    interface DashboardFetcher {
        String fetch() throws Exception;
    }

    public record RefreshResult(boolean configured, boolean refreshed, String message) {
    }

    record DashboardSnapshot(String title, List<String> lines, String contextVersion) {
        DashboardSnapshot {
            lines = lines == null ? List.of() : List.copyOf(lines);
            contextVersion = contextVersion == null ? "" : contextVersion;
        }
    }

    private final ChatSessionContext sessionContext = ChatSessionContext.current();
    private final McpConfigStore.DashboardConfig config;
    private final DashboardFetcher fetcher;
    private final KompileTui tui;
    private final ObjectMapper mapper;
    private final long sourceTimeoutMillis;
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kompile-mcp-dashboard");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService sourceExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kompile-mcp-dashboard-source");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean refreshScheduled = new AtomicBoolean(false);
    private final AtomicBoolean refreshAgain = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong dirtyGeneration = new AtomicLong(1L);
    private volatile DashboardSnapshot lastGood;
    private volatile boolean hiddenByUser;
    private volatile long appliedGeneration;

    public McpDashboardController(
            McpBundleToolLoader loader, KompileTui tui,
            Supplier<ToolContext> contextSupplier) {
        this(loader == null ? null : loader.dashboardConfig().orElse(null),
                () -> {
                    ToolContext context = contextSupplier == null ? null : contextSupplier.get();
                    McpBundleToolLoader.RemoteCallResult result = loader.callDashboardSource(context);
                    if (result.error()) {
                        throw new IllegalStateException(safeFailure(result.content()));
                    }
                    return result.content();
                }, tui, JsonUtils.standardMapper(), SOURCE_TIMEOUT_MILLIS);
    }

    McpDashboardController(McpConfigStore.DashboardConfig config,
                           DashboardFetcher fetcher,
                           KompileTui tui,
                           ObjectMapper mapper) {
        this(config, fetcher, tui, mapper, SOURCE_TIMEOUT_MILLIS);
    }

    McpDashboardController(McpConfigStore.DashboardConfig config,
                           DashboardFetcher fetcher,
                           KompileTui tui,
                           ObjectMapper mapper,
                           long sourceTimeoutMillis) {
        this.config = config;
        this.fetcher = fetcher;
        this.tui = tui;
        this.mapper = mapper == null ? JsonUtils.standardMapper() : mapper;
        this.sourceTimeoutMillis = Math.max(1L,
                Math.min(SOURCE_TIMEOUT_MILLIS, sourceTimeoutMillis));
    }

    public boolean isConfigured() {
        return config != null;
    }

    /** Reserve the persistent dashboard region before the TUI establishes its layout. */
    public void prepare() {
        if (!isConfigured() || hiddenByUser || tui == null) return;
        tui.setDashboard("Game dashboard", List.of("Loading current state…"));
    }

    public RefreshResult refresh() {
        if (!isConfigured()) {
            return new RefreshResult(false, false, "No project dashboard is configured.");
        }
        long requestedGeneration = dirtyGeneration.get();
        try {
            DashboardSnapshot snapshot = parse(fetchWithTimeout(), mapper);
            if (closed.get()) {
                return new RefreshResult(true, false, "Dashboard is closed.");
            }
            if (requestedGeneration != dirtyGeneration.get()) {
                return new RefreshResult(true, false,
                        "Dashboard refresh was superseded by newer state.");
            }
            lastGood = snapshot;
            appliedGeneration = requestedGeneration;
            if (!hiddenByUser && tui != null) {
                tui.setDashboard(snapshot.title(), snapshot.lines());
            }
            return new RefreshResult(true, true, "Dashboard refreshed.");
        } catch (Exception failure) {
            String message = "Dashboard refresh failed: " + safeFailure(failure.getMessage());
            if (lastGood == null && !hiddenByUser && tui != null) {
                tui.setDashboard("Game dashboard", List.of(
                        "Dashboard unavailable · use /dashboard refresh",
                        safeFailure(failure.getMessage())));
            } else if (tui != null) {
                tui.showAlert(message);
            }
            return new RefreshResult(true, false, message);
        }
    }

    private String fetchWithTimeout() throws Exception {
        Future<String> future = sourceExecutor.submit(sessionContext.wrapCallable(fetcher::fetch));
        try {
            return future.get(sourceTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new IllegalStateException(
                    "source timed out after " + sourceTimeoutMillis + "ms", timeout);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("source refresh was interrupted", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("source refresh failed", cause);
        }
    }

    /** Schedule a coalesced refresh without blocking the input or tool-completion thread. */
    public boolean requestRefresh() {
        if (!isConfigured() || closed.get()) return false;
        dirtyGeneration.incrementAndGet();
        if (!refreshScheduled.compareAndSet(false, true)) {
            refreshAgain.set(true);
            return true;
        }
        refreshExecutor.execute(sessionContext.wrap(this::runRefreshLoop));
        return true;
    }

    private void runRefreshLoop() {
        try {
            do {
                refreshAgain.set(false);
                refresh();
            } while (!closed.get() && refreshAgain.get());
        } finally {
            refreshScheduled.set(false);
            if (!closed.get() && refreshAgain.getAndSet(false)) {
                requestRefresh();
            }
        }
    }

    /** Refresh after a successful exact trigger, including the mcp_tool_call gateway. */
    public void onToolComplete(String toolName, String rawInput, ToolResult result) {
        if (!isConfigured() || result == null || result.isError()) return;
        String effectiveTool = effectiveToolName(toolName, rawInput, mapper);
        if (config.refreshAfterTools().contains(effectiveTool)) {
            if (hiddenByUser || tui == null || !tui.isStarted()) {
                dirtyGeneration.incrementAndGet();
                return;
            }
            requestRefresh();
        }
    }

    public RefreshResult command(String arguments) {
        if (!isConfigured()) {
            return new RefreshResult(false, false, "No project dashboard is configured.");
        }
        String action = arguments == null || arguments.isBlank()
                ? "refresh" : arguments.trim().toLowerCase(Locale.ROOT);
        return switch (action) {
            case "refresh", "reload" -> {
                hiddenByUser = false;
                prepare();
                requestRefresh();
                yield new RefreshResult(true, false, "Dashboard refresh scheduled.");
            }
            case "show", "on" -> {
                hiddenByUser = false;
                DashboardSnapshot snapshot = lastGood;
                if (snapshot != null && !isDirty() && tui != null) {
                    tui.setDashboard(snapshot.title(), snapshot.lines());
                    yield new RefreshResult(true, false, "Dashboard shown.");
                }
                prepare();
                requestRefresh();
                yield new RefreshResult(true, false, "Dashboard shown; refresh scheduled.");
            }
            case "hide", "off" -> {
                hiddenByUser = true;
                if (tui != null) tui.clearDashboard();
                yield new RefreshResult(isConfigured(), false,
                        isConfigured() ? "Dashboard hidden." : "No project dashboard is configured.");
            }
            case "status" -> new RefreshResult(isConfigured(), false,
                    !isConfigured() ? "No project dashboard is configured."
                            : hiddenByUser ? "Dashboard configured and hidden."
                            : lastGood == null ? "Dashboard configured; no successful snapshot yet."
                            : "Dashboard visible" + (isDirty() ? " (refresh pending)" : "")
                                    + " · context " + lastGood.contextVersion());
            default -> new RefreshResult(isConfigured(), false,
                    "Usage: /dashboard [refresh|show|hide|status]");
        };
    }

    static DashboardSnapshot parse(String raw, ObjectMapper mapper) throws Exception {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("source returned no content");
        }
        if (raw.length() > MAX_RESPONSE_CHARS) {
            throw new IllegalArgumentException("source response exceeds " + MAX_RESPONSE_CHARS + " characters");
        }
        JsonNode root = mapper.readTree(raw);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("source must return one JSON object");
        }
        if (!SCHEMA_VERSION.equals(root.path("schemaVersion").asText())) {
            throw new IllegalArgumentException("unsupported dashboard schema version");
        }

        String title = bounded(sanitize(root.path("title").asText("Dashboard")), MAX_TITLE_CHARS);
        if (title.isBlank()) title = "Dashboard";
        JsonNode sourceLines = root.path("lines");
        if (!sourceLines.isArray()) {
            throw new IllegalArgumentException("dashboard lines must be a JSON array");
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode sourceLine : sourceLines) {
            if (lines.size() >= MAX_LINES) break;
            String line = bounded(sanitize(sourceLine.asText("")), MAX_LINE_CHARS);
            if (!line.isBlank()) lines.add(line);
        }
        if (lines.isEmpty()) lines.add("No dashboard details available.");
        String contextVersion = bounded(
                sanitize(root.path("contextVersion").asText("unknown")), MAX_TITLE_CHARS);
        return new DashboardSnapshot(title, lines, contextVersion);
    }

    private boolean isDirty() {
        return appliedGeneration != dirtyGeneration.get();
    }

    static String effectiveToolName(String toolName, String rawInput, ObjectMapper mapper) {
        if (!"mcp_tool_call".equals(toolName)) return toolName == null ? "" : toolName;
        if (rawInput == null || rawInput.isBlank()) return "";
        try {
            JsonNode arguments = mapper.readTree(rawInput);
            return arguments.path("tool").asText("").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String sanitize(String value) {
        String stripped = AnsiConstants.stripAnsi(value == null ? "" : value);
        StringBuilder safe = new StringBuilder(stripped.length());
        stripped.codePoints().forEach(codePoint -> {
            if (!Character.isISOControl(codePoint)
                    && Character.getType(codePoint) != Character.FORMAT) {
                safe.appendCodePoint(codePoint);
            } else if (Character.isWhitespace(codePoint)) {
                safe.append(' ');
            }
        });
        return safe.toString().replaceAll("\\s+", " ").trim();
    }

    private static String bounded(String value, int maxCodePoints) {
        if (value == null || value.isEmpty()) return "";
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) return value;
        int end = value.offsetByCodePoints(0, Math.max(0, maxCodePoints - 1));
        return value.substring(0, end) + "…";
    }

    private static String safeFailure(String message) {
        String safe = sanitize(Optional.ofNullable(message).orElse("unknown error"));
        return bounded(safe.isBlank() ? "unknown error" : safe, MAX_LINE_CHARS);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            refreshExecutor.shutdownNow();
            sourceExecutor.shutdownNow();
        }
    }
}
