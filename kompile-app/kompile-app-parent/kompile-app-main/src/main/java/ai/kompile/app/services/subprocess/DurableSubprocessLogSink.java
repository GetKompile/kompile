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
 * Events are appended to {@code ~/.kompile/logs/subprocesses/<subprocessId>.log}, complementing the
 * real-time stream the crawl UI consumes via its per-job sink.</p>
 */
@Component
public class DurableSubprocessLogSink implements SubprocessLogSink {

    private static final Logger log = LoggerFactory.getLogger(DurableSubprocessLogSink.class);

    private final Path baseDir = Paths.get(System.getProperty("user.home"), ".kompile", "logs", "subprocesses");
    private final ConcurrentHashMap<String, BufferedWriter> writers = new ConcurrentHashMap<>();
    private final DateTimeFormatter ts =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    @Override
    public void onLog(SubprocessLogEvent ev) {
        if (ev == null || ev.subprocessId() == null) {
            return;
        }
        try {
            BufferedWriter w = writers.computeIfAbsent(ev.subprocessId(), this::openWriter);
            if (w == null) {
                return;
            }
            String jobTag = ev.jobId() != null ? " [job " + ev.jobId() + "]" : "";
            String record = ts.format(Instant.ofEpochMilli(ev.timestampMs()))
                    + " [" + ev.stream() + "/" + ev.level() + "]" + jobTag + " " + ev.message();
            synchronized (w) {
                w.write(record);
                w.newLine();
                w.flush();
            }
        } catch (Exception e) {
            log.debug("Durable subprocess log write failed for {}: {}", ev.subprocessId(), e.getMessage());
        }
    }

    private BufferedWriter openWriter(String subprocessId) {
        try {
            Files.createDirectories(baseDir);
            Path file = baseDir.resolve(subprocessId.replaceAll("[^a-zA-Z0-9._-]", "_") + ".log");
            return Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Cannot open durable subprocess log for {}: {}", subprocessId, e.getMessage());
            return null;
        }
    }

    @PreDestroy
    public void close() {
        for (BufferedWriter w : writers.values()) {
            try {
                w.close();
            } catch (IOException ignored) {
                // best-effort flush-on-shutdown
            }
        }
        writers.clear();
    }
}
