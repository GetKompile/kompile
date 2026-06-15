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

package ai.kompile.core.crawler;

import java.time.Instant;

/**
 * Progress snapshot of a running crawl job.
 */
public record CrawlProgress(
    /** Total items discovered so far */
    int discovered,
    /** Items successfully processed (loaded + indexed) */
    int processed,
    /** Items that failed to load or index */
    int failed,
    /** Items still in the processing queue */
    int queued,
    /** Current crawl depth being explored */
    int currentDepth,
    /** Maximum depth configured */
    int maxDepth,
    /** URL or path currently being processed */
    String currentItem,
    /** Estimated progress percentage (0-100), or -1 if unknown */
    int estimatedPercent,
    /** Timestamp of this progress snapshot */
    Instant timestamp
) {
    public CrawlProgress {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }
}
