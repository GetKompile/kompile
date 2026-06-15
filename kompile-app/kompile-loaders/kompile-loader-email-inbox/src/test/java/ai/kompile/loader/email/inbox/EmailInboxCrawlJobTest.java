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

package ai.kompile.loader.email.inbox;

import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlEventListener;
import ai.kompile.core.crawler.CrawlState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EmailInboxCrawlJob} — shouldProcess incremental logic,
 * markVisited state updates, checkpoint snapshots, and previousState restoration.
 */
class EmailInboxCrawlJobTest {

    private final CrawlEventListener listener = new CrawlEventListener() {};

    private EmailInboxCrawlJob freshJob() {
        CrawlConfig config = CrawlConfig.builder().seed("/mnt/mail").build();
        return new EmailInboxCrawlJob("job-1", config, listener);
    }

    private EmailInboxCrawlJob jobWithPreviousState(CrawlState previous) {
        CrawlConfig config = CrawlConfig.builder()
                .seed("/mnt/mail")
                .previousState(previous)
                .build();
        return new EmailInboxCrawlJob("job-2", config, listener);
    }

    // ─── shouldProcess ────────────────────────────────────────────────

    @Test
    void shouldProcess_neverSeen_returnsTrue() {
        EmailInboxCrawlJob job = freshJob();
        assertTrue(job.shouldProcess("/mnt/mail/inbox/msg1.eml", 1000L));
    }

    @Test
    void shouldProcess_seenButModified_returnsTrue() {
        EmailInboxCrawlJob job = freshJob();
        job.markVisited("/mnt/mail/inbox/msg1.eml", 1000L);

        assertTrue(job.shouldProcess("/mnt/mail/inbox/msg1.eml", 2000L));
    }

    @Test
    void shouldProcess_seenAndUnmodified_returnsFalse() {
        EmailInboxCrawlJob job = freshJob();
        job.markVisited("/mnt/mail/inbox/msg1.eml", 1000L);

        assertFalse(job.shouldProcess("/mnt/mail/inbox/msg1.eml", 1000L));
    }

    @Test
    void shouldProcess_seenWithOlderTimestamp_returnsFalse() {
        EmailInboxCrawlJob job = freshJob();
        job.markVisited("/mnt/mail/inbox/msg1.eml", 2000L);

        assertFalse(job.shouldProcess("/mnt/mail/inbox/msg1.eml", 1500L));
    }

    // ─── markVisited ──────────────────────────────────────────────────

    @Test
    void markVisited_addsToVisitedPaths() {
        EmailInboxCrawlJob job = freshJob();
        job.markVisited("/mnt/mail/inbox/msg1.eml", 1000L);

        assertTrue(job.visitedPaths.contains("/mnt/mail/inbox/msg1.eml"));
    }

    @Test
    void markVisited_recordsLastModifiedTime() {
        EmailInboxCrawlJob job = freshJob();
        job.markVisited("/mnt/mail/inbox/msg1.eml", 5000L);

        assertEquals(5000L, job.lastModifiedTimes.get("/mnt/mail/inbox/msg1.eml"));
    }

    @Test
    void markVisited_updatesExistingTime() {
        EmailInboxCrawlJob job = freshJob();
        job.markVisited("/mnt/mail/inbox/msg1.eml", 1000L);
        job.markVisited("/mnt/mail/inbox/msg1.eml", 3000L);

        assertEquals(3000L, job.lastModifiedTimes.get("/mnt/mail/inbox/msg1.eml"));
    }

    @Test
    void markVisited_multipleFiles() {
        EmailInboxCrawlJob job = freshJob();
        job.markVisited("/mnt/mail/inbox/msg1.eml", 1000L);
        job.markVisited("/mnt/mail/inbox/msg2.eml", 2000L);
        job.markVisited("/mnt/mail/sent/msg3.eml", 3000L);

        assertEquals(3, job.visitedPaths.size());
        assertEquals(3, job.lastModifiedTimes.size());
    }

    // ─── checkpoint ───────────────────────────────────────────────────

    @Test
    void checkpoint_emptyJob_returnsEmptyState() {
        EmailInboxCrawlJob job = freshJob();
        CrawlState state = job.checkpoint();

        assertNotNull(state);
        assertNotNull(state.getTimestamp());
        assertTrue(state.getVisitedUrls().isEmpty());
        assertTrue(state.getLastModifiedTimes().isEmpty());
    }

    @Test
    void checkpoint_capturesVisitedPaths() {
        EmailInboxCrawlJob job = freshJob();
        job.markVisited("/mnt/mail/inbox/msg1.eml", 1000L);
        job.markVisited("/mnt/mail/inbox/msg2.eml", 2000L);

        CrawlState state = job.checkpoint();

        assertEquals(2, state.getVisitedUrls().size());
        assertTrue(state.getVisitedUrls().contains("/mnt/mail/inbox/msg1.eml"));
        assertTrue(state.getVisitedUrls().contains("/mnt/mail/inbox/msg2.eml"));
    }

    @Test
    void checkpoint_capturesLastModifiedTimes() {
        EmailInboxCrawlJob job = freshJob();
        job.markVisited("/mnt/mail/inbox/msg1.eml", 1000L);

        CrawlState state = job.checkpoint();

        assertEquals(1000L, state.getLastModifiedTimes().get("/mnt/mail/inbox/msg1.eml"));
    }

    @Test
    void checkpoint_returnsImmutableCopy() {
        EmailInboxCrawlJob job = freshJob();
        job.markVisited("/mnt/mail/inbox/msg1.eml", 1000L);

        CrawlState state = job.checkpoint();

        // Modifying the job after checkpoint should not affect the snapshot
        job.markVisited("/mnt/mail/inbox/msg2.eml", 2000L);

        assertEquals(1, state.getVisitedUrls().size());
        assertEquals(1, state.getLastModifiedTimes().size());
    }

    // ─── Constructor: previousState restoration ───────────────────────

    @Test
    void constructor_nullPreviousState_startsEmpty() {
        EmailInboxCrawlJob job = freshJob();

        assertTrue(job.visitedPaths.isEmpty());
        assertTrue(job.lastModifiedTimes.isEmpty());
    }

    @Test
    void constructor_restoresVisitedUrls() {
        CrawlState previous = CrawlState.builder()
                .visitedUrls(Set.of("/mnt/mail/inbox/msg1.eml", "/mnt/mail/inbox/msg2.eml"))
                .build();

        EmailInboxCrawlJob job = jobWithPreviousState(previous);

        assertEquals(2, job.visitedPaths.size());
        assertTrue(job.visitedPaths.contains("/mnt/mail/inbox/msg1.eml"));
        assertTrue(job.visitedPaths.contains("/mnt/mail/inbox/msg2.eml"));
    }

    @Test
    void constructor_restoresLastModifiedTimes() {
        Map<String, Long> times = new HashMap<>();
        times.put("/mnt/mail/inbox/msg1.eml", 1000L);
        times.put("/mnt/mail/inbox/msg2.eml", 2000L);

        CrawlState previous = CrawlState.builder()
                .lastModifiedTimes(times)
                .build();

        EmailInboxCrawlJob job = jobWithPreviousState(previous);

        assertEquals(2, job.lastModifiedTimes.size());
        assertEquals(1000L, job.lastModifiedTimes.get("/mnt/mail/inbox/msg1.eml"));
        assertEquals(2000L, job.lastModifiedTimes.get("/mnt/mail/inbox/msg2.eml"));
    }

    @Test
    void constructor_previousStateWithNullVisitedUrls_handledGracefully() {
        CrawlState previous = new CrawlState();
        previous.setVisitedUrls(null);
        previous.setLastModifiedTimes(null);

        EmailInboxCrawlJob job = jobWithPreviousState(previous);

        assertTrue(job.visitedPaths.isEmpty());
        assertTrue(job.lastModifiedTimes.isEmpty());
    }

    // ─── Incremental re-crawl: end-to-end ─────────────────────────────

    @Test
    void incrementalCrawl_previousStateAffectsShouldProcess() {
        Map<String, Long> times = new HashMap<>();
        times.put("/mnt/mail/inbox/msg1.eml", 1000L);
        times.put("/mnt/mail/inbox/msg2.eml", 2000L);

        CrawlState previous = CrawlState.builder()
                .visitedUrls(Set.of("/mnt/mail/inbox/msg1.eml", "/mnt/mail/inbox/msg2.eml"))
                .lastModifiedTimes(times)
                .build();

        EmailInboxCrawlJob job = jobWithPreviousState(previous);

        // Already seen, same timestamp → skip
        assertFalse(job.shouldProcess("/mnt/mail/inbox/msg1.eml", 1000L));

        // Already seen, newer timestamp → re-process
        assertTrue(job.shouldProcess("/mnt/mail/inbox/msg2.eml", 3000L));

        // Never seen → process
        assertTrue(job.shouldProcess("/mnt/mail/inbox/msg3.eml", 500L));
    }

    @Test
    void incrementalCrawl_checkpointAfterNewWork_mergesState() {
        Map<String, Long> times = new HashMap<>();
        times.put("/mnt/mail/inbox/msg1.eml", 1000L);

        CrawlState previous = CrawlState.builder()
                .visitedUrls(Set.of("/mnt/mail/inbox/msg1.eml"))
                .lastModifiedTimes(times)
                .build();

        EmailInboxCrawlJob job = jobWithPreviousState(previous);

        // Process a new file and re-process an updated one
        job.markVisited("/mnt/mail/inbox/msg2.eml", 2000L);
        job.markVisited("/mnt/mail/inbox/msg1.eml", 1500L);

        CrawlState state = job.checkpoint();

        // Both old and new files should be in the checkpoint
        assertEquals(2, state.getVisitedUrls().size());
        assertTrue(state.getVisitedUrls().contains("/mnt/mail/inbox/msg1.eml"));
        assertTrue(state.getVisitedUrls().contains("/mnt/mail/inbox/msg2.eml"));

        // Updated timestamp should be captured
        assertEquals(1500L, state.getLastModifiedTimes().get("/mnt/mail/inbox/msg1.eml"));
        assertEquals(2000L, state.getLastModifiedTimes().get("/mnt/mail/inbox/msg2.eml"));
    }
}
