/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.services.subprocess;

import ai.kompile.app.subprocess.SubprocessLogEvent;
import ai.kompile.app.subprocess.SubprocessLogSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable, centralized sink for every managed subprocess's logs.
 *
 * <p>Auto-collected by {@link ai.kompile.app.subprocess.SubprocessLogBus} (it injects all
 * {@link SubprocessLogSink} beans), so this receives the log + lifecycle events from <em>every</em>
 * subprocess type — not just embedding, which previously was the only one that wrote a central log.
 * Events are written to {@code ~/.kompile/logs/subprocesses/<subprocessId>.log}, complementing the
 * real-time stream the crawl UI consumes via its per-job sink.</p>
 *
 * <p><b>Rotation.</b> Each file is rotated (a) once when this sink first opens it — so every app
 * launch starts a FRESH log with the prior run preserved as {@code <id>.log.1} — and (b) whenever it
 * exceeds {@link #maxBytes} within a run. Previously the sink opened {@code CREATE, APPEND} and never
 * rotated, so a single {@code <id>.log} accumulated across every launch (graph-matrix.log reached
 * ~1.5 GB), which both wasted disk and made cumulative greps ("N batches") wildly misleading. One
 * generation is kept ({@code .log} + {@code .log.1}); the cap is overridable via
 * {@code -Dkompile.subprocess.log.maxBytes}.</p>
 */
@Component
public class DurableSubprocessLogSink implements SubprocessLogSink {

    private static final Logger log = LoggerFactory.getLogger(DurableSubprocessLogSink.class);

    /** Per-file size cap before intra-run rotation; overridable via system property. Default 100 MB. */
    private static final long DEFAULT_MAX_BYTES = 100L * 1024 * 1024;

    private final Path baseDir;
    private final long maxBytes;
    private final ConcurrentHashMap<String, RotatingWriter> writers = new ConcurrentHashMap<>();
    private final DateTimeFormatter ts =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    public DurableSubprocessLogSink() {
        this(Paths.get(System.getProperty("user.home"), ".kompile", "logs", "subprocesses"),
                Long.getLong("kompile.subprocess.log.maxBytes", DEFAULT_MAX_BYTES));
    }

    /** Test/DI seam: inject the log directory and size cap directly. */
    DurableSubprocessLogSink(Path baseDir, long maxBytes) {
        this.baseDir = baseDir;
        this.maxBytes = maxBytes > 0 ? maxBytes : DEFAULT_MAX_BYTES;
    }

    @Override
    public void onLog(SubprocessLogEvent ev) {
        if (ev == null || ev.subprocessId() == null) {
            return;
        }
        try {
            RotatingWriter w = writers.computeIfAbsent(ev.subprocessId(), this::openWriter);
            if (w == null) {
                return;
            }
            String jobTag = ev.jobId() != null ? " [job " + ev.jobId() + "]" : "";
            String record = ts.format(Instant.ofEpochMilli(ev.timestampMs()))
                    + " [" + ev.stream() + "/" + ev.level() + "]" + jobTag + " " + ev.message();
            w.writeLine(record);
        } catch (Exception e) {
            log.debug("Durable subprocess log write failed for {}: {}", ev.subprocessId(), e.getMessage());
        }
    }

    private RotatingWriter openWriter(String subprocessId) {
        try {
            Files.createDirectories(baseDir);
            Path file = baseDir.resolve(subprocessId.replaceAll("[^a-zA-Z0-9._-]", "_") + ".log");
            // Rotate on first open so each app launch starts a fresh log (prior run kept as .log.1),
            // instead of appending across launches indefinitely.
            rotateFile(file);
            return new RotatingWriter(file, maxBytes);
        } catch (IOException e) {
            log.warn("Cannot open durable subprocess log for {}: {}", subprocessId, e.getMessage());
            return null;
        }
    }

    /** Move {@code <file>} to {@code <file>.1} (replacing any existing) if it has content. Best-effort. */
    private static void rotateFile(Path file) {
        try {
            if (Files.exists(file) && Files.size(file) > 0) {
                Path prev = file.resolveSibling(file.getFileName().toString() + ".1");
                Files.move(file, prev, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Best-effort: if rotation fails the fresh open below still truncates, bounding size.
            log.debug("Log rotation failed for {}: {}", file, e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        for (RotatingWriter w : writers.values()) {
            w.close();
        }
        writers.clear();
    }

    /**
     * A durable line writer that caps file size. Lines are written under the writer's own lock; when
     * the running byte count reaches {@code maxBytes} the current file is rotated to {@code .1} and a
     * fresh file opened, so a single long-running subprocess can never grow its log without bound.
     */
    private static final class RotatingWriter {
        private final Path file;
        private final long maxBytes;
        private BufferedWriter out;
        private long bytes;

        RotatingWriter(Path file, long maxBytes) throws IOException {
            this.file = file;
            this.maxBytes = maxBytes;
            open();
        }

        private void open() throws IOException {
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            bytes = 0;
        }

        synchronized void writeLine(String record) throws IOException {
            if (bytes >= maxBytes) {
                out.close();
                rotateFile(file);
                open();
            }
            out.write(record);
            out.newLine();
            out.flush();
            bytes += (long) record.length() + 1;
        }

        synchronized void close() {
            try {
                out.close();
            } catch (IOException ignored) {
                // best-effort flush-on-shutdown
            }
        }
    }
}
