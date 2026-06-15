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

package ai.kompile.loader.gdocs;

import ai.kompile.core.crawler.CrawlConfig;
import ai.kompile.core.crawler.CrawlEventListener;
import ai.kompile.core.crawler.CrawlState;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GoogleDocsCrawlJobTest {

    // ── Fresh job state ─────────────────────────────────────────────────

    @Test
    void newJobStartsEmpty() {
        GoogleDocsCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of("accessToken", "test"))
                .build());

        assertFalse(job.isVisited("doc001"));
        assertNull(job.getLastSyncEpoch());
        assertNull(job.getLastPageToken());
    }

    // ── markVisited / isVisited ─────────────────────────────────────────

    @Test
    void markVisitedAndCheck() {
        GoogleDocsCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of("accessToken", "test"))
                .build());

        assertFalse(job.isVisited("doc001"));
        job.markVisited("doc001");
        assertTrue(job.isVisited("doc001"));
        assertFalse(job.isVisited("doc002"));
    }

    @Test
    void markMultipleVisited() {
        GoogleDocsCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of("accessToken", "test"))
                .build());

        job.markVisited("doc001");
        job.markVisited("doc002");
        job.markVisited("doc003");

        assertTrue(job.isVisited("doc001"));
        assertTrue(job.isVisited("doc002"));
        assertTrue(job.isVisited("doc003"));
        assertFalse(job.isVisited("doc004"));
    }

    // ── Sync epoch and page token ──────────────────────────────────────

    @Test
    void setsAndGetsSyncEpoch() {
        GoogleDocsCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of("accessToken", "test"))
                .build());

        job.setLastSyncEpoch("1700000000");
        assertEquals("1700000000", job.getLastSyncEpoch());
    }

    @Test
    void setsAndGetsPageToken() {
        GoogleDocsCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of("accessToken", "test"))
                .build());

        job.setLastPageToken("abc123");
        assertEquals("abc123", job.getLastPageToken());
    }

    // ── checkpoint() ───────────────────────────────────────────────────

    @Test
    void checkpointContainsVisitedIds() {
        GoogleDocsCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of("accessToken", "test"))
                .build());

        job.markVisited("doc001");
        job.markVisited("doc002");

        CrawlState state = job.checkpoint();

        assertNotNull(state);
        assertNotNull(state.getVisitedUrls());
        assertEquals(2, state.getVisitedUrls().size());
        assertTrue(state.getVisitedUrls().contains("doc001"));
        assertTrue(state.getVisitedUrls().contains("doc002"));
    }

    @Test
    void checkpointContainsSyncEpochAndPageToken() {
        GoogleDocsCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of("accessToken", "test"))
                .build());

        job.setLastSyncEpoch("1700000000");
        job.setLastPageToken("next-page-xyz");

        CrawlState state = job.checkpoint();

        assertEquals("1700000000", state.getProperties().get("lastSyncEpoch"));
        assertEquals("next-page-xyz", state.getProperties().get("lastPageToken"));
    }

    @Test
    void checkpointOmitsNullProperties() {
        GoogleDocsCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of("accessToken", "test"))
                .build());

        CrawlState state = job.checkpoint();

        assertFalse(state.getProperties().containsKey("lastSyncEpoch"));
        assertFalse(state.getProperties().containsKey("lastPageToken"));
    }

    @Test
    void checkpointIsSnapshotNotLiveReference() {
        GoogleDocsCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of("accessToken", "test"))
                .build());

        job.markVisited("doc001");
        CrawlState state1 = job.checkpoint();

        job.markVisited("doc002");
        CrawlState state2 = job.checkpoint();

        assertEquals(1, state1.getVisitedUrls().size());
        assertEquals(2, state2.getVisitedUrls().size());
    }

    // ── Restore from previous state ─────────────────────────────────────

    @Test
    void restoresVisitedIdsFromPreviousState() {
        CrawlState previousState = CrawlState.builder()
                .visitedUrls(Set.of("doc001", "doc002", "doc003"))
                .build();

        GoogleDocsCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of("accessToken", "test"))
                .previousState(previousState)
                .build());

        assertTrue(job.isVisited("doc001"));
        assertTrue(job.isVisited("doc002"));
        assertTrue(job.isVisited("doc003"));
        assertFalse(job.isVisited("doc004"));
    }

    @Test
    void restoresSyncEpochAndPageTokenFromPreviousState() {
        Map<String, Object> props = new HashMap<>();
        props.put("lastSyncEpoch", "1700000000");
        props.put("lastPageToken", "saved-token");

        CrawlState previousState = CrawlState.builder()
                .visitedUrls(Set.of("doc001"))
                .properties(props)
                .build();

        GoogleDocsCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of("accessToken", "test"))
                .previousState(previousState)
                .build());

        assertEquals("1700000000", job.getLastSyncEpoch());
        assertEquals("saved-token", job.getLastPageToken());
    }

    @Test
    void handlesNullPreviousStateGracefully() {
        GoogleDocsCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of("accessToken", "test"))
                .previousState(null)
                .build());

        assertFalse(job.isVisited("anything"));
        assertNull(job.getLastSyncEpoch());
        assertNull(job.getLastPageToken());
    }

    @Test
    void handlesPreviousStateWithEmptyProperties() {
        CrawlState previousState = CrawlState.builder()
                .visitedUrls(Set.of("doc001"))
                .properties(new HashMap<>())
                .build();

        GoogleDocsCrawlJob job = createJob(CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of("accessToken", "test"))
                .previousState(previousState)
                .build());

        assertTrue(job.isVisited("doc001"));
        assertNull(job.getLastSyncEpoch());
        assertNull(job.getLastPageToken());
    }

    // ── Round-trip: checkpoint → restore ─────────────────────────────────

    @Test
    void roundTripCheckpointAndRestore() {
        GoogleDocsCrawlJob job1 = createJob(CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of("accessToken", "test"))
                .build());

        job1.markVisited("doc001");
        job1.markVisited("doc002");
        job1.setLastSyncEpoch("1700000000");
        job1.setLastPageToken("page2");

        CrawlState savedState = job1.checkpoint();

        GoogleDocsCrawlJob job2 = createJob(CrawlConfig.builder()
                .seed("gdocs://")
                .properties(Map.of("accessToken", "test"))
                .previousState(savedState)
                .build());

        assertTrue(job2.isVisited("doc001"));
        assertTrue(job2.isVisited("doc002"));
        assertFalse(job2.isVisited("doc003"));
        assertEquals("1700000000", job2.getLastSyncEpoch());
        assertEquals("page2", job2.getLastPageToken());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private GoogleDocsCrawlJob createJob(CrawlConfig config) {
        return new GoogleDocsCrawlJob("test-job-id", config, new CrawlEventListener() {});
    }
}
