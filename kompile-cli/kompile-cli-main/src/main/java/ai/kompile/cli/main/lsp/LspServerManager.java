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

package ai.kompile.cli.main.lsp;

import ai.kompile.cli.common.KompileHome;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the pool of live {@link LspServerConnection}s, keyed by {@code root + "|" + language}.
 * Starts servers lazily, restarts crashed ones within a bounded budget, and reaps idle ones.
 *
 * <p>A single instance is shared process-wide via {@link #getInstance()} so the three tool
 * registration sites can each {@code new LspTool(...)} without threading lifecycle state.</p>
 */
public class LspServerManager {

    private static final long IDLE_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long IDLE_TIMEOUT_JAVA_MS = TimeUnit.MINUTES.toMillis(30);
    private static final long RESTART_WINDOW_MS = TimeUnit.MINUTES.toMillis(10);
    private static final int MAX_RESTARTS_PER_WINDOW = 2;

    private static volatile LspServerManager instance;

    private final LspServerRegistry registry;
    private final Map<String, LspServerConnection> connections = new ConcurrentHashMap<>();
    private final Map<String, RestartRecord> restarts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper;
    private volatile Path workingDirectory;

    LspServerManager(LspServerRegistry registry) {
        this.registry = registry;
        this.reaper = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lsp-idle-reaper");
            thread.setDaemon(true);
            return thread;
        });
        this.reaper.scheduleWithFixedDelay(this::reapIdle, 5, 5, TimeUnit.MINUTES);
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopAll, "lsp-shutdown"));
    }

    /** Process-wide instance, reading the server table from {@code ~/.kompile/lsp-servers.json}. */
    public static LspServerManager getInstance() {
        if (instance == null) {
            synchronized (LspServerManager.class) {
                if (instance == null) {
                    instance = new LspServerManager(LspServerRegistry.forHome());
                }
            }
        }
        return instance;
    }

    public LspServerRegistry registry() {
        return registry;
    }

    /** Set the fallback workspace root used when no {@code rootMarkers} match above a file. */
    public void setWorkingDirectory(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    // ── Start / resolve ──────────────────────────────────────────────────────

    /** Resolve config + root for {@code file} and return a ready server, starting it if needed. */
    public LspServerConnection getOrStart(Path file) {
        LspServerConfig cfg = registry.resolveForFile(file).orElseThrow(() -> new LspException(
                "no language server configured for " + file.getFileName()
                        + " (extension " + LspLanguages.extensionOf(file) + ")"));
        Path fallback = workingDirectory != null ? workingDirectory : file.toAbsolutePath().getParent();
        Path root = registry.resolveRoot(file, cfg, fallback);
        return getOrStartForRoot(cfg, root);
    }

    /** Start (or return) the server for an explicit language + root. */
    public LspServerConnection getOrStartByLanguage(String language, Path root) {
        LspServerConfig cfg = registry.forLanguage(language);
        if (cfg == null) {
            throw new LspException("unknown language: " + language);
        }
        return getOrStartForRoot(cfg, root);
    }

    private synchronized LspServerConnection getOrStartForRoot(LspServerConfig cfg, Path root) {
        if (!cfg.enabled()) {
            throw new LspException(cfg.language() + " language server is disabled in lsp-servers.json");
        }
        String key = key(root, cfg.language());
        LspServerConnection existing = connections.get(key);
        if (existing != null && existing.isAlive()
                && existing.state() != LspServerConnection.State.STOPPED
                && existing.state() != LspServerConnection.State.CRASHED) {
            return existing;
        }
        if (existing != null) {
            connections.remove(key);
            try {
                existing.stop();
            } catch (Exception ignore) {
                // best-effort cleanup of the dead connection
            }
            if (!allowRestart(key)) {
                throw new LspException(cfg.language() + " server crashed repeatedly; "
                        + "fix the environment then run: lsp action=servers server_action=restart language=" + cfg.language());
            }
        }
        if (!registry.isAvailable(cfg)) {
            String binary = cfg.command().isEmpty() ? "?" : cfg.command().get(0);
            throw new IllegalStateException("language server binary not found: " + binary
                    + " for " + cfg.language() + ". " + cfg.installHint());
        }
        LspServerConnection conn = startConnection(cfg, root);
        connections.put(key, conn);
        return conn;
    }

    private LspServerConnection startConnection(LspServerConfig cfg, Path root) {
        try {
            LspServerConnection conn = new LspServerConnection(cfg, root, logFileFor(cfg, root));
            try {
                conn.initialize();
                return conn;
            } catch (RuntimeException | Error failure) {
                // Not pooled yet: neither the idle reaper nor stopAll can reach this child.
                try {
                    conn.stop();
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        } catch (IOException e) {
            throw new LspException("failed to launch " + cfg.language() + " server: " + e.getMessage(), e);
        }
    }

    // ── Explicit server actions ──────────────────────────────────────────────

    public LspServerConnection startServer(String language, Path root) {
        restarts.remove(key(root, language));
        return getOrStartByLanguage(language, root);
    }

    public boolean stopServer(String language, Path root) {
        LspServerConnection conn = connections.remove(key(root, language));
        if (conn != null) {
            conn.stop();
            return true;
        }
        return false;
    }

    public LspServerConnection restartServer(String language, Path root) {
        stopServer(language, root);
        restarts.remove(key(root, language));
        return getOrStartByLanguage(language, root);
    }

    public synchronized void stopAll() {
        for (LspServerConnection conn : connections.values()) {
            try {
                conn.stop();
            } catch (Exception ignore) {
                // shutdown best-effort
            }
        }
        connections.clear();
    }

    // ── Status ───────────────────────────────────────────────────────────────

    public List<ServerStatus> status() {
        List<ServerStatus> out = new ArrayList<>();
        for (LspServerConnection conn : connections.values()) {
            out.add(new ServerStatus(
                    conn.language(),
                    conn.root().toString(),
                    conn.state().name(),
                    conn.pid(),
                    conn.uptimeMs(),
                    conn.openDocCount(),
                    conn.diagnosticsCount(),
                    conn.logFile() != null ? conn.logFile().toString() : "",
                    conn.lastError() != null ? conn.lastError() : ""));
        }
        return out;
    }

    /** Immutable snapshot of one running server, for the {@code servers status} view. */
    public record ServerStatus(String language, String root, String state, long pid, long uptimeMs,
                               int openDocs, int diagnostics, String logFile, String lastError) {
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void reapIdle() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, LspServerConnection> entry : connections.entrySet()) {
            LspServerConnection conn = entry.getValue();
            long idle = now - conn.lastUsed();
            long threshold = "java".equals(conn.language()) ? IDLE_TIMEOUT_JAVA_MS : IDLE_TIMEOUT_MS;
            if (idle > threshold) {
                connections.remove(entry.getKey());
                try {
                    conn.stop();
                } catch (Exception ignore) {
                    // reaper must not throw
                }
            }
        }
    }

    private boolean allowRestart(String key) {
        long now = System.currentTimeMillis();
        RestartRecord record = restarts.computeIfAbsent(key, k -> new RestartRecord());
        synchronized (record) {
            if (now - record.windowStart > RESTART_WINDOW_MS) {
                record.windowStart = now;
                record.count = 0;
            }
            if (record.count >= MAX_RESTARTS_PER_WINDOW) {
                return false;
            }
            record.count++;
            return true;
        }
    }

    private static Path logFileFor(LspServerConfig cfg, Path root) {
        String hash = Integer.toHexString(root.toAbsolutePath().normalize().toString().hashCode());
        return KompileHome.homeDirectory().toPath()
                .resolve("logs").resolve("lsp")
                .resolve(cfg.language() + "-" + hash + ".log");
    }

    private static String key(Path root, String language) {
        return root.toAbsolutePath().normalize() + "|" + language;
    }

    private static final class RestartRecord {
        long windowStart = System.currentTimeMillis();
        int count;
    }
}
