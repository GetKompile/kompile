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

import java.util.concurrent.CompletableFuture;

/**
 * Handle to a running crawl job. Returned by {@link Crawler#start(CrawlConfig, CrawlEventListener)}
 * and used to monitor, pause, resume, or cancel the crawl.
 */
public interface CrawlJob {

    /** Unique identifier for this crawl job */
    String getJobId();

    /** Current status of the job */
    CrawlStatus getStatus();

    /** Latest progress snapshot */
    CrawlProgress getProgress();

    /** The configuration this job was started with */
    CrawlConfig getConfig();

    /** Pause the crawl. Can be resumed later. */
    void pause();

    /** Resume a paused crawl. */
    void resume();

    /** Cancel the crawl. Partial results are preserved. */
    void cancel();

    /**
     * Capture the current crawl state for persistence.
     * This state can later be passed as {@link CrawlConfig#getPreviousState()}
     * to resume or do an incremental re-crawl.
     */
    CrawlState checkpoint();

    /**
     * Future that completes when the crawl finishes (for any reason).
     * The summary contains final counts, duration, errors, and the final state.
     */
    CompletableFuture<CrawlSummary> getCompletionFuture();
}
