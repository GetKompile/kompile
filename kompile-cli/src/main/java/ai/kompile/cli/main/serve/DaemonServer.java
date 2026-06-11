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

package ai.kompile.cli.main.serve;

import ai.kompile.cli.common.KompileHome;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Unix domain socket server that multiplexes MCP and chat connections
 * over a single daemon process.
 * <p>
 * Design inspired by jcode's daemon architecture: one socket, protocol header
 * on connect determines routing, shared resource pool across all sessions.
 * <p>
 * Socket path: {@code ~/.kompile/runtime/kompile.sock}
 */
public class DaemonServer {

    /** Grace period after last session disconnects before daemon shuts down. */
    private static final long DRAIN_GRACE_MS = 60_000; // 60 seconds

    private final SharedResourcePool pool;
    private final int maxSessions;
    private final long idleTimeoutMs;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger activeSessionCount = new AtomicInteger(0);
    private final AtomicInteger totalSessionsServed = new AtomicInteger(0);
    private final AtomicInteger sessionCounter = new AtomicInteger(0);
    private final AtomicLong lastActivityMs = new AtomicLong(System.currentTimeMillis());

    /** Scheduled drain shutdown — cancelled if a new session connects before it fires. */
    private volatile ScheduledFuture<?> drainFuture;

    private volatile ServerSocketChannel serverChannel;
    private volatile ExecutorService sessionExecutor;
    private volatile ScheduledExecutorService scheduler;

    /**
     * @param pool           shared resources for all sessions
     * @param maxSessions    max concurrent connections (0 = unlimited)
     * @param idleTimeoutMs  self-terminate after this many ms with zero sessions (0 = never)
     */
    public DaemonServer(SharedResourcePool pool, int maxSessions, long idleTimeoutMs) {
        this.pool = pool;
        this.maxSessions = maxSessions;
        this.idleTimeoutMs = idleTimeoutMs;
    }

    /**
     * Start the server. Binds the Unix socket and enters the accept loop.
     * Blocks until {@link #stop()} is called or idle timeout triggers.
     *
     * @param readySignal called once after the socket is bound and accepting, before blocking
     */
    public void start(Runnable readySignal) throws IOException {
        Path socketPath = KompileHome.daemonSocketFile().toPath();
        Files.createDirectories(socketPath.getParent());

        // Remove stale socket file from a previous crashed daemon
        Files.deleteIfExists(socketPath);

        UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(socketPath);
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(addr);
        running.set(true);

        // Thread pool for session handlers — bounded to prevent OOM under load
        int poolSize = maxSessions > 0 ? maxSessions : 64;
        sessionExecutor = new ThreadPoolExecutor(
                2, poolSize, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(poolSize * 2),
                r -> {
                    Thread t = new Thread(r);
                    t.setDaemon(true);
                    t.setName("daemon-session-" + sessionCounter.incrementAndGet());
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );

        // Single scheduler for both idle timeout and drain shutdown
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "daemon-scheduler");
            t.setDaemon(true);
            return t;
        });

        // Idle timeout watcher (safety net — primary shutdown is drain-on-last-disconnect)
        if (idleTimeoutMs > 0) {
            scheduler.scheduleAtFixedRate(this::checkIdleTimeout, 30, 30, TimeUnit.SECONDS);
        }

        // Signal readiness (used by ServeCommand to print "ready" or signal parent via pipe)
        if (readySignal != null) readySignal.run();

        System.err.println("[daemon] Listening on " + socketPath);

        // Accept loop
        try {
            while (running.get()) {
                SocketChannel client = serverChannel.accept();
                if (client == null) continue;

                if (maxSessions > 0 && activeSessionCount.get() >= maxSessions) {
                    System.err.println("[daemon] Max sessions reached (" + maxSessions + "), rejecting connection");
                    client.close();
                    continue;
                }

                lastActivityMs.set(System.currentTimeMillis());
                activeSessionCount.incrementAndGet();
                totalSessionsServed.incrementAndGet();
                cancelDrain();

                String sid = "s" + sessionCounter.incrementAndGet();
                McpSocketSession session = new McpSocketSession(client, pool, sid,
                        () -> {
                            int remaining = activeSessionCount.decrementAndGet();
                            lastActivityMs.set(System.currentTimeMillis());
                            if (remaining == 0) {
                                scheduleDrain();
                            }
                        });
                sessionExecutor.submit(session);
            }
        } catch (IOException e) {
            if (running.get()) {
                System.err.println("[daemon] Accept error: " + e.getMessage());
            }
            // else: server was stopped normally
        } finally {
            cleanup(socketPath);
        }
    }

    public void stop() {
        running.set(false);
        try {
            if (serverChannel != null) serverChannel.close();
        } catch (IOException ignored) {}
    }

    public int activeSessionCount() {
        return activeSessionCount.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Schedule a drain shutdown — after the grace period, if still at zero
     * sessions, shut down. Called when the last active session disconnects.
     */
    private void scheduleDrain() {
        // Only drain if we've actually served at least one session.
        // Don't drain on startup before any client has connected.
        if (totalSessionsServed.get() == 0) return;

        System.err.println("[daemon] Last session disconnected — shutting down in "
                + (DRAIN_GRACE_MS / 1000) + "s unless a new session connects");

        drainFuture = scheduler.schedule(() -> {
            if (activeSessionCount.get() == 0) {
                System.err.println("[daemon] Drain timeout — no new sessions, shutting down");
                stop();
            }
        }, DRAIN_GRACE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Cancel a pending drain shutdown — called when a new session connects.
     */
    private void cancelDrain() {
        ScheduledFuture<?> f = drainFuture;
        if (f != null && !f.isDone()) {
            f.cancel(false);
            drainFuture = null;
            System.err.println("[daemon] New session connected — drain cancelled");
        }
    }

    private void checkIdleTimeout() {
        if (activeSessionCount.get() == 0) {
            long idleMs = System.currentTimeMillis() - lastActivityMs.get();
            if (idleMs >= idleTimeoutMs) {
                System.err.println("[daemon] Idle timeout reached (" + (idleTimeoutMs / 1000) + "s), shutting down");
                stop();
            }
        }
    }

    private void cleanup(Path socketPath) {
        if (scheduler != null) scheduler.shutdownNow();
        if (sessionExecutor != null) sessionExecutor.shutdownNow();
        try { Files.deleteIfExists(socketPath); } catch (IOException ignored) {}
    }
}
