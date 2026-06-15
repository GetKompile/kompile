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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Summary produced when a crawl job completes (successfully or not).
 */
public record CrawlSummary(
    /** Final status of the crawl */
    CrawlStatus status,
    /** Total items discovered */
    int totalDiscovered,
    /** Items successfully processed */
    int totalProcessed,
    /** Items that failed */
    int totalFailed,
    /** Items skipped (filtered, duplicate, etc.) */
    int totalSkipped,
    /** Maximum depth reached */
    int maxDepthReached,
    /** When the crawl started */
    Instant startedAt,
    /** When the crawl ended */
    Instant completedAt,
    /** Total crawl duration */
    Duration duration,
    /** Errors encountered (URL/path -> error message) */
    List<Map.Entry<String, String>> errors,
    /** Checkpoint state for resuming or incremental re-crawl */
    CrawlState finalState
) {}
