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

package ai.kompile.cli.common.chat.aggregate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Enforces retention on Kompile transcript files at {@code ~/.kompile/conversations/*.txt}.
 *
 * <p>Three caps, applied in order of age → per-source cap → total size. Oldest
 * files are deleted first (by mtime). External-source transcripts (e.g. from
 * Claude Code, Codex) are <em>not</em> deleted — only Kompile-authored
 * {@code .txt} files under {@code ~/.kompile/conversations/} are in scope.</p>
 */
public class ChatTranscriptRetention {

    private static final Logger log = LoggerFactory.getLogger(ChatTranscriptRetention.class);

    private final Policy policy;

    public ChatTranscriptRetention(Policy policy) {
        this.policy = policy;
    }

    public Result apply(File conversationsDir) {
        return apply(conversationsDir, false);
    }

    public Result apply(File conversationsDir, boolean dryRun) {
        if (conversationsDir == null || !conversationsDir.isDirectory()) {
            return Result.empty();
        }
        File[] files = conversationsDir.listFiles((d, name) -> name.endsWith(".txt"));
        if (files == null || files.length == 0) {
            return Result.empty();
        }
        List<File> all = new ArrayList<>(List.of(files));
        all.sort(Comparator.comparingLong(File::lastModified));

        List<File> deleted = new ArrayList<>();
        int byAge = 0;
        if (policy.maxAge != null && !policy.maxAge.isZero()) {
            Instant cutoff = Instant.now().minus(policy.maxAge);
            for (File f : new ArrayList<>(all)) {
                if (Instant.ofEpochMilli(f.lastModified()).isBefore(cutoff)) {
                    if (delete(f, dryRun)) {
                        deleted.add(f);
                        all.remove(f);
                        byAge++;
                    }
                }
            }
        }

        int byCount = 0;
        if (policy.maxFilesPerSource > 0 && all.size() > policy.maxFilesPerSource) {
            int excess = all.size() - policy.maxFilesPerSource;
            for (int i = 0; i < excess; i++) {
                File f = all.get(0);
                if (delete(f, dryRun)) {
                    deleted.add(f);
                    all.remove(0);
                    byCount++;
                }
            }
        }

        int bySize = 0;
        if (policy.maxTotalBytes > 0) {
            long total = totalSize(all);
            while (total > policy.maxTotalBytes && !all.isEmpty()) {
                File f = all.get(0);
                long size = f.length();
                if (!delete(f, dryRun)) break;
                deleted.add(f);
                all.remove(0);
                total -= size;
                bySize++;
            }
        }
        return new Result(byAge, byCount, bySize, Collections.unmodifiableList(deleted));
    }

    private boolean delete(File f, boolean dryRun) {
        if (dryRun) return true;
        try {
            return Files.deleteIfExists(f.toPath());
        } catch (IOException e) {
            log.warn("Failed to delete transcript {}: {}", f, e.getMessage());
            return false;
        }
    }

    private static long totalSize(List<File> files) {
        long total = 0L;
        for (File f : files) total += f.length();
        return total;
    }

    public record Policy(Duration maxAge, long maxTotalBytes, int maxFilesPerSource) {

        public static final Policy DEFAULT = new Policy(
                Duration.ofDays(90),
                2L * 1024 * 1024 * 1024,
                1000);

        public static Policy of(long maxAgeDays, long maxTotalMb, int maxFilesPerSource) {
            return new Policy(
                    maxAgeDays > 0 ? Duration.ofDays(maxAgeDays) : Duration.ZERO,
                    maxTotalMb > 0 ? maxTotalMb * 1024L * 1024L : 0L,
                    maxFilesPerSource);
        }
    }

    public record Result(int deletedByAge, int deletedByCount, int deletedBySize, List<File> deletedFiles) {
        public int totalDeleted() {
            return deletedByAge + deletedByCount + deletedBySize;
        }

        public static Result empty() {
            return new Result(0, 0, 0, List.of());
        }
    }
}
