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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.loader.gmail;

import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlEventListener;
import ai.kompile.core.crawler.CrawlState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GmailCrawlJobTest {

    // ── Fresh job state ─────────────────────────────────────────────────

    @Test
    void newJobStartsEmpty() {
        GmailCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of("accessToken", "test"))
                .build());

        assertFalse(job.isVisited("msg001"));
        assertNull(job.getLastHistoryId());
        assertNull(job.getLastSyncEpoch());
    }

    // ── markVisited / isVisited ─────────────────────────────────────────

    @Test
    void markVisitedAndCheck() {
        GmailCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of("accessToken", "test"))
                .build());

        assertFalse(job.isVisited("msg001"));
        job.markVisited("msg001");
        assertTrue(job.isVisited("msg001"));
        assertFalse(job.isVisited("msg002"));
    }

    @Test
    void markMultipleVisited() {
        GmailCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of("accessToken", "test"))
                .build());

        job.markVisited("msg001");
        job.markVisited("msg002");
        job.markVisited("msg003");

        assertTrue(job.isVisited("msg001"));
        assertTrue(job.isVisited("msg002"));
        assertTrue(job.isVisited("msg003"));
        assertFalse(job.isVisited("msg004"));
    }

    // ── History ID and sync epoch ───────────────────────────────────────

    @Test
    void setsAndGetsHistoryId() {
        GmailCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of("accessToken", "test"))
                .build());

        job.setLastHistoryId("12345");
        assertEquals("12345", job.getLastHistoryId());
    }

    @Test
    void setsAndGetsSyncEpoch() {
        GmailCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of("accessToken", "test"))
                .build());

        job.setLastSyncEpoch("1700000000");
        assertEquals("1700000000", job.getLastSyncEpoch());
    }

    // ── checkpoint() ────────────────────────────────────────────────────

    @Test
    void checkpointContainsVisitedIds() {
        GmailCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of("accessToken", "test"))
                .build());

        job.markVisited("msg001");
        job.markVisited("msg002");

        CrawlState state = job.checkpoint();

        assertNotNull(state);
        assertNotNull(state.getVisitedUrls());
        assertEquals(2, state.getVisitedUrls().size());
        assertTrue(state.getVisitedUrls().contains("msg001"));
        assertTrue(state.getVisitedUrls().contains("msg002"));
    }

    @Test
    void checkpointContainsHistoryIdAndEpoch() {
        GmailCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of("accessToken", "test"))
                .build());

        job.setLastHistoryId("99999");
        job.setLastSyncEpoch("1700000000");

        CrawlState state = job.checkpoint();

        assertEquals("99999", state.getProperties().get("lastHistoryId"));
        assertEquals("1700000000", state.getProperties().get("lastSyncEpoch"));
    }

    @Test
    void checkpointOmitsNullProperties() {
        GmailCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of("accessToken", "test"))
                .build());

        CrawlState state = job.checkpoint();

        assertFalse(state.getProperties().containsKey("lastHistoryId"));
        assertFalse(state.getProperties().containsKey("lastSyncEpoch"));
    }

    @Test
    void checkpointIsSnapshotNotLiveReference() {
        GmailCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of("accessToken", "test"))
                .build());

        job.markVisited("msg001");
        CrawlState state1 = job.checkpoint();

        job.markVisited("msg002");
        CrawlState state2 = job.checkpoint();

        assertEquals(1, state1.getVisitedUrls().size(), "First checkpoint should have 1 visited");
        assertEquals(2, state2.getVisitedUrls().size(), "Second checkpoint should have 2 visited");
    }

    // ── Restore from previous state ─────────────────────────────────────

    @Test
    void restoresVisitedIdsFromPreviousState() {
        CrawlState previousState = CrawlState.builder()
                .visitedUrls(Set.of("msg001", "msg002", "msg003"))
                .build();

        GmailCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of("accessToken", "test"))
                .previousState(previousState)
                .build());

        assertTrue(job.isVisited("msg001"));
        assertTrue(job.isVisited("msg002"));
        assertTrue(job.isVisited("msg003"));
        assertFalse(job.isVisited("msg004"));
    }

    @Test
    void restoresHistoryIdAndEpochFromPreviousState() {
        Map<String, Object> props = new HashMap<>();
        props.put("lastHistoryId", "12345");
        props.put("lastSyncEpoch", "1700000000");

        CrawlState previousState = CrawlState.builder()
                .visitedUrls(Set.of("msg001"))
                .properties(props)
                .build();

        GmailCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of("accessToken", "test"))
                .previousState(previousState)
                .build());

        assertEquals("12345", job.getLastHistoryId());
        assertEquals("1700000000", job.getLastSyncEpoch());
    }

    @Test
    void handlesNullPreviousStateGracefully() {
        GmailCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of("accessToken", "test"))
                .previousState(null)
                .build());

        assertFalse(job.isVisited("anything"));
        assertNull(job.getLastHistoryId());
        assertNull(job.getLastSyncEpoch());
    }

    @Test
    void handlesPreviousStateWithEmptyProperties() {
        CrawlState previousState = CrawlState.builder()
                .visitedUrls(Set.of("msg001"))
                .properties(new HashMap<>())
                .build();

        GmailCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of("accessToken", "test"))
                .previousState(previousState)
                .build());

        assertTrue(job.isVisited("msg001"));
        assertNull(job.getLastHistoryId());
        assertNull(job.getLastSyncEpoch());
    }

    // ── Round-trip: checkpoint → restore ─────────────────────────────────

    @Test
    void roundTripCheckpointAndRestore() {
        // First crawl
        GmailCrawlJob job1 = createJob(CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of("accessToken", "test"))
                .build());

        job1.markVisited("msg001");
        job1.markVisited("msg002");
        job1.setLastHistoryId("55555");
        job1.setLastSyncEpoch("1700000000");

        CrawlState savedState = job1.checkpoint();

        // Second crawl restores from saved state
        GmailCrawlJob job2 = createJob(CrawlConfig.builder()
                .seed("gmail://inbox")
                .properties(Map.of("accessToken", "test"))
                .previousState(savedState)
                .build());

        assertTrue(job2.isVisited("msg001"));
        assertTrue(job2.isVisited("msg002"));
        assertFalse(job2.isVisited("msg003"));
        assertEquals("55555", job2.getLastHistoryId());
        assertEquals("1700000000", job2.getLastSyncEpoch());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private GmailCrawlJob createJob(CrawlConfig config) {
        return new GmailCrawlJob("test-job-id", config, new CrawlEventListener() {});
    }
}
