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

package ai.kompile.app.services.crawl;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Durable, crash-recoverable storage for {@link DistributedCrawlSession} manifests (Phase 1). Each session is
 * one JSON file under {@code <kompile.data.dir>/distributed-crawl/sessions/<sessionId>.json}, so a coordinator
 * restart can reload in-flight distributed crawls and reconcile each worker against the live scheduler instead
 * of silently losing them.
 *
 * <p>Writes are atomic (temp file + move) and offloaded to a single daemon thread so a persist never blocks the
 * crawl hot path. The live progress snapshot is NOT stored — only the small {@link DistributedCrawlSession.Manifest}
 * (counts, per-worker status, completed-source keys, {@code lastProgressAt}) needed for reconcile/reassign.</p>
 */
@Component
public class DistributedCrawlSessionStore {

    private static final Logger log = LoggerFactory.getLogger(DistributedCrawlSessionStore.class);
    private static final String SUFFIX = ".json";

    private final Path sessionsDir;
    private final ObjectMapper objectMapper;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "dist-crawl-session-store");
        t.setDaemon(true);
        return t;
    });

    public DistributedCrawlSessionStore(@Value("${kompile.data.dir:#{null}}") String dataDir,
                                        ObjectMapper objectMapper) {
        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.sessionsDir = Paths.get(effectiveDataDir, "distributed-crawl", "sessions");
        this.objectMapper = objectMapper;
    }

    /** Fire-and-forget persist on the store's writer thread; never throws into the caller. */
    public void persistAsync(DistributedCrawlSession session) {
        if (session == null || session.getSessionId() == null) {
            return;
        }
        DistributedCrawlSession.Manifest manifest = session.toManifest();
        writer.submit(() -> writeManifest(manifest));
    }

    /** Synchronous persist (used by tests and shutdown); never throws. */
    public void persist(DistributedCrawlSession session) {
        if (session == null || session.getSessionId() == null) {
            return;
        }
        writeManifest(session.toManifest());
    }

    private void writeManifest(DistributedCrawlSession.Manifest manifest) {
        try {
            Files.createDirectories(sessionsDir);
            Path target = sessionsDir.resolve(safe(manifest.getSessionId()) + SUFFIX);
            Path tmp = sessionsDir.resolve(safe(manifest.getSessionId()) + SUFFIX + ".tmp");
            objectMapper.writeValue(tmp.toFile(), manifest);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("Failed to persist distributed-crawl session {}: {}",
                    manifest.getSessionId(), e.getMessage());
        }
    }

    /** Load every persisted session manifest; skips (and logs) any unreadable/corrupt file. */
    public List<DistributedCrawlSession.Manifest> loadAll() {
        List<DistributedCrawlSession.Manifest> out = new ArrayList<>();
        if (!Files.isDirectory(sessionsDir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(sessionsDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                    .forEach(p -> {
                        try {
                            out.add(objectMapper.readValue(p.toFile(), DistributedCrawlSession.Manifest.class));
                        } catch (Exception e) {
                            log.warn("Skipping unreadable distributed-crawl manifest {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list distributed-crawl sessions dir {}: {}", sessionsDir, e.getMessage());
        }
        return out;
    }

    /** Remove a session's persisted file (best-effort). */
    public void delete(String sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            Files.deleteIfExists(sessionsDir.resolve(safe(sessionId) + SUFFIX));
        } catch (IOException e) {
            log.debug("Failed to delete distributed-crawl session {}: {}", sessionId, e.getMessage());
        }
    }

    /** Block until queued async writes have drained (graceful shutdown + deterministic tests). */
    public void flush() {
        try {
            writer.submit(() -> { }).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // best-effort drain
        }
    }

    /** Defensive filename sanitization (session ids are UUIDs, but never let one escape the dir). */
    private static String safe(String sessionId) {
        return sessionId.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    @PreDestroy
    void shutdown() {
        writer.shutdown();
    }
}
