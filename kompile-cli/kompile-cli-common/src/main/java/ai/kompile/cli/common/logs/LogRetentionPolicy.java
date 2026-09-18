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

package ai.kompile.cli.common.logs;

import java.time.Duration;

/**
 * Retention thresholds applied by {@link LogRetentionManager}.
 *
 * <p>Three independent caps — any single cap being violated triggers retirement
 * (archive or delete) of the oldest logs first (by file modification time). Caps
 * are applied in the order: age, per-agent count, total size.
 *
 * <p>When {@link #archiveEnabled()} is {@code true}, "retirement" means moving the
 * log (and its metadata sidecar) into the archive tree under
 * {@code ~/.kompile/logs/archive/&lt;destination&gt;/&lt;yyyy-MM&gt;/} instead of deleting it.
 * Archived files are then subject to {@link #coldRetention()}, after which they are
 * permanently deleted. Transcripts are intentionally NOT governed by this policy —
 * see {@code ChatTranscriptRetention} for the separate transcript lifecycle.
 */
public record LogRetentionPolicy(
        Duration maxAge,
        long maxTotalBytes,
        int maxFilesPerAgent,
        boolean archiveEnabled,
        Duration coldRetention) {

    public static final LogRetentionPolicy DEFAULT = new LogRetentionPolicy(
            Duration.ofDays(30),
            2L * 1024 * 1024 * 1024,
            100,
            true,
            Duration.ofDays(90));

    public static LogRetentionPolicy of(long maxAgeDays, long maxTotalMb, int maxFilesPerAgent) {
        return of(maxAgeDays, maxTotalMb, maxFilesPerAgent, true, 90);
    }

    public static LogRetentionPolicy of(long maxAgeDays, long maxTotalMb, int maxFilesPerAgent,
                                        boolean archiveEnabled, long coldRetentionDays) {
        return new LogRetentionPolicy(
                Duration.ofDays(maxAgeDays),
                maxTotalMb * 1024L * 1024L,
                maxFilesPerAgent,
                archiveEnabled,
                Duration.ofDays(coldRetentionDays));
    }
}
