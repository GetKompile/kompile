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

package ai.kompile.app.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cleans up stale subprocess temp directories left behind by previous JVM
 * sessions that crashed or were killed without running {@code @PreDestroy} hooks.
 *
 * <p>Runs once at startup. Targets files/dirs in {@code java.io.tmpdir} matching
 * known subprocess naming patterns and older than 1 hour.</p>
 */
@Component
public class SubprocessTempCleanup {

    private static final Logger logger = LoggerFactory.getLogger(SubprocessTempCleanup.class);

    /** Only delete entries older than this many hours. */
    private static final int STALE_HOURS = 1;

    /** Prefixes of temp entries created by subprocess launchers. */
    private static final String[] STALE_PREFIXES = {
            "embedding-subprocess-javacpp-",
            "serving-subprocess-javacpp-",
            "serving-subprocess-args-",
            "ingest-args-",
            "vector-pop-args-",
            "training-args-",
            "vlm-test-args-",
            "model-init-args-",
    };

    @PostConstruct
    public void cleanupStaleTempEntries() {
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir", "/tmp"));
        if (!Files.isDirectory(tmpDir)) {
            return;
        }

        Instant cutoff = Instant.now().minus(STALE_HOURS, ChronoUnit.HOURS);
        AtomicInteger cleaned = new AtomicInteger(0);
        AtomicLong freedBytes = new AtomicLong(0);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmpDir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                boolean isStaleSubprocessEntry = false;
                for (String prefix : STALE_PREFIXES) {
                    if (name.startsWith(prefix)) {
                        isStaleSubprocessEntry = true;
                        break;
                    }
                }
                if (!isStaleSubprocessEntry) {
                    continue;
                }

                try {
                    BasicFileAttributes attrs = Files.readAttributes(entry, BasicFileAttributes.class);
                    Instant lastModified = attrs.lastModifiedTime().toInstant();
                    if (lastModified.isBefore(cutoff)) {
                        long size = deleteAndMeasure(entry);
                        cleaned.incrementAndGet();
                        freedBytes.addAndGet(size);
                    }
                } catch (IOException e) {
                    logger.debug("Could not check/clean stale entry {}: {}", entry, e.getMessage());
                }
            }
        } catch (IOException e) {
            logger.debug("Could not scan tmpdir for stale subprocess entries: {}", e.getMessage());
        }

        if (cleaned.get() > 0) {
            logger.info("[STARTUP CLEANUP] Removed {} stale subprocess temp entries, freed {} MB",
                    cleaned.get(), freedBytes.get() / (1024 * 1024));
        }
    }

    private static long deleteAndMeasure(Path path) throws IOException {
        AtomicLong size = new AtomicLong(0);
        if (Files.isDirectory(path)) {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    size.addAndGet(attrs.size());
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } else {
            size.addAndGet(Files.size(path));
            Files.deleteIfExists(path);
        }
        return size.get();
    }
}
