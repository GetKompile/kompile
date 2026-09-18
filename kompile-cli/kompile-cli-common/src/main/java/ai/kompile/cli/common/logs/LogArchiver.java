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
 *  limitations under the License.
 */

package ai.kompile.cli.common.logs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Moves retired log runs into the cold archive tree and prunes expired archives.
 *
 * <p>Layout:
 * <pre>
 *   ~/.kompile/logs/archive/
 *     agents/2026-08/&lt;processId&gt;.log (+ .meta.json)
 *     subprocesses/2026-08/&lt;runId&gt;.log (+ .meta.json)
 *     crawls/2026-08/&lt;jobId&gt;.log
 * </pre>
 *
 * <p>The month bucket is derived from the log's modification time. Cold retention
 * deletes a bucket only once the entire month is older than {@code coldRetention}.
 * {@link Files#move} preserves the original mtime so read-back ordering is stable.
 */
public final class LogArchiver {

    private static final Logger log = LoggerFactory.getLogger(LogArchiver.class);

    /** {@code ~/.kompile/logs/archive} */
    public File archiveRoot() {
        return new File(LogPaths.logsDirectory(), "archive");
    }

    /** {@code ~/.kompile/logs/archive/<destination>} */
    public File destinationRoot(String destination) {
        return new File(archiveRoot(), LogPaths.safePathSegment(destination));
    }

    /**
     * Moves a single log file (and optionally its {@code .meta.json} sidecar) into the
     * archive tree under the given destination's month bucket. Returns {@code true} if the
     * log file was successfully moved (the sidecar is best-effort).
     */
    public boolean archive(File logFile, File metaFile, String destination) {
        if (logFile == null || !logFile.isFile()) {
            return false;
        }
        try {
            File monthDir = monthDir(destination, logFile.lastModified());
            Files.createDirectories(monthDir.toPath());
            moveInto(logFile, monthDir);
            if (metaFile != null && metaFile.isFile()) {
                moveInto(metaFile, monthDir);
            }
            return true;
        } catch (IOException e) {
            log.warn("Failed to archive {}: {}", logFile, e.getMessage());
            return false;
        }
    }

    /** Archive a log with no metadata sidecar (e.g. crawl logs). */
    public boolean archive(File logFile, String destination) {
        return archive(logFile, null, destination);
    }

    /** Deletes expired month buckets for one destination. Returns the number of buckets removed. */
    public int applyColdRetention(String destination, Duration coldRetention) {
        File root = destinationRoot(destination);
        File[] months = root.listFiles(File::isDirectory);
        if (months == null) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(coldRetention);
        int deleted = 0;
        for (File month : months) {
            YearMonth ym = parseYearMonth(month.getName());
            if (ym == null) {
                continue;
            }
            Instant monthEnd = ym.atEndOfMonth().atTime(23, 59, 59).toInstant(ZoneOffset.UTC);
            if (monthEnd.isBefore(cutoff) && deleteRecursively(month)) {
                deleted++;
            }
        }
        return deleted;
    }

    /** Applies cold retention across every destination under the archive root. */
    public int applyColdRetentionAll(Duration coldRetention) {
        File root = archiveRoot();
        File[] destinations = root.listFiles(File::isDirectory);
        if (destinations == null) {
            return 0;
        }
        int total = 0;
        for (File dest : destinations) {
            total += applyColdRetention(dest.getName(), coldRetention);
        }
        return total;
    }

    private File monthDir(String destination, long epochMillis) {
        YearMonth ym = YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC));
        return new File(destinationRoot(destination),
                String.format("%04d-%02d", ym.getYear(), ym.getMonthValue()));
    }

    private static void moveInto(File src, File monthDir) throws IOException {
        Path target = monthDir.toPath().resolve(src.getName());
        if (Files.exists(target)) {
            target = monthDir.toPath().resolve(uniqueName(src.getName()));
        }
        Files.move(src.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String uniqueName(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return base + "-" + UUID.randomUUID().toString().substring(0, 8) + ext;
    }

    private static YearMonth parseYearMonth(String name) {
        try {
            return YearMonth.parse(name);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static boolean deleteRecursively(File dir) {
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort prune
                }
            });
            return true;
        } catch (IOException e) {
            log.warn("Failed to delete archive bucket {}: {}", dir, e.getMessage());
            return false;
        }
    }
}
