package ai.kompile.crawler.web;

import ai.kompile.core.crawler.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WebCrawlJob Resume Features")
class WebCrawlJobResumeTest {

    private CrawlConfig baseConfig(String seed) {
        CrawlConfig config = new CrawlConfig();
        config.setCrawlerId("web");
        config.setSeed(seed);
        config.setMaxDepth(3);
        return config;
    }

    @Nested
    @DisplayName("Frontier checkpoint and restore")
    class FrontierCheckpointRestore {

        @Test
        void freshJobHasEmptyFrontier() {
            CrawlConfig config = baseConfig("https://example.com");
            WebCrawlJob job = new WebCrawlJob("job-1", config, null);

            assertTrue(job.pendingFrontier.isEmpty());
            assertTrue(job.visitedUrls.isEmpty());
        }

        @Test
        void checkpointCapturesFrontier() {
            CrawlConfig config = baseConfig("https://example.com");
            WebCrawlJob job = new WebCrawlJob("job-1", config, null);

            // Simulate some visited URLs and pending frontier
            job.visitedUrls.add("https://example.com");
            job.visitedUrls.add("https://example.com/page1");
            job.pendingFrontier.add(new AbstractMap.SimpleImmutableEntry<>("https://example.com/page2", 1));
            job.pendingFrontier.add(new AbstractMap.SimpleImmutableEntry<>("https://example.com/page3", 2));

            CrawlState state = job.checkpoint();

            assertEquals(2, state.getVisitedUrls().size());
            assertTrue(state.getVisitedUrls().contains("https://example.com"));
            assertTrue(state.getVisitedUrls().contains("https://example.com/page1"));

            assertEquals(2, state.getPendingUrls().size());
            assertEquals("https://example.com/page2::1", state.getPendingUrls().get(0));
            assertEquals("https://example.com/page3::2", state.getPendingUrls().get(1));
        }

        @Test
        void restoresFromPreviousState() {
            CrawlState prev = CrawlState.builder()
                    .timestamp(Instant.now())
                    .visitedUrls(Set.of("https://example.com", "https://example.com/a"))
                    .contentHashes(Map.of("https://example.com", "hash1"))
                    .pendingUrls(List.of(
                            "https://example.com/b::1",
                            "https://example.com/c::2"
                    ))
                    .build();

            CrawlConfig config = baseConfig("https://example.com");
            config.setPreviousState(prev);

            WebCrawlJob job = new WebCrawlJob("job-2", config, null);

            // Visited URLs should be restored
            assertEquals(2, job.visitedUrls.size());
            assertTrue(job.visitedUrls.contains("https://example.com"));
            assertTrue(job.visitedUrls.contains("https://example.com/a"));

            // Content hashes should be restored
            assertEquals("hash1", job.contentHashes.get("https://example.com"));

            // Frontier should be restored with correct depth
            assertEquals(2, job.pendingFrontier.size());
            Map.Entry<String, Integer> first = job.pendingFrontier.poll();
            assertEquals("https://example.com/b", first.getKey());
            assertEquals(1, first.getValue());

            Map.Entry<String, Integer> second = job.pendingFrontier.poll();
            assertEquals("https://example.com/c", second.getKey());
            assertEquals(2, second.getValue());
        }

        @Test
        void skipsMalformedPendingUrls() {
            CrawlState prev = CrawlState.builder()
                    .pendingUrls(List.of(
                            "https://valid.com::1",
                            "no-depth-separator",    // missing "::"
                            "https://bad.com::notanumber",  // non-numeric depth
                            "https://also-valid.com::0"
                    ))
                    .build();

            CrawlConfig config = baseConfig("https://example.com");
            config.setPreviousState(prev);

            WebCrawlJob job = new WebCrawlJob("job-3", config, null);

            // Only 2 valid entries should be restored
            assertEquals(2, job.pendingFrontier.size());
        }

        @Test
        void roundTripCheckpointRestore() {
            // Create job, add state, checkpoint
            CrawlConfig config1 = baseConfig("https://example.com");
            WebCrawlJob job1 = new WebCrawlJob("job-a", config1, null);
            job1.visitedUrls.addAll(Set.of("https://example.com", "https://example.com/1", "https://example.com/2"));
            job1.contentHashes.put("https://example.com", "abc123");
            job1.pendingFrontier.add(new AbstractMap.SimpleImmutableEntry<>("https://example.com/3", 1));
            job1.pendingFrontier.add(new AbstractMap.SimpleImmutableEntry<>("https://example.com/4", 2));

            CrawlState checkpoint = job1.checkpoint();

            // Create new job from checkpoint
            CrawlConfig config2 = baseConfig("https://example.com");
            config2.setPreviousState(checkpoint);
            WebCrawlJob job2 = new WebCrawlJob("job-b", config2, null);

            // Verify state was fully restored
            assertEquals(3, job2.visitedUrls.size());
            assertEquals("abc123", job2.contentHashes.get("https://example.com"));
            assertEquals(2, job2.pendingFrontier.size());
        }

        @Test
        void emptyPreviousStateProducesEmptyJob() {
            CrawlState prev = CrawlState.builder().build();

            CrawlConfig config = baseConfig("https://example.com");
            config.setPreviousState(prev);

            WebCrawlJob job = new WebCrawlJob("job-4", config, null);

            assertTrue(job.visitedUrls.isEmpty());
            assertTrue(job.contentHashes.isEmpty());
            assertTrue(job.pendingFrontier.isEmpty());
        }
    }

    @Nested
    @DisplayName("Checkpoint immutability")
    class CheckpointImmutability {

        @Test
        void checkpointSnapshotIsImmutable() {
            CrawlConfig config = baseConfig("https://example.com");
            WebCrawlJob job = new WebCrawlJob("job-5", config, null);
            job.visitedUrls.add("https://example.com");

            CrawlState snapshot = job.checkpoint();

            // Mutating job after checkpoint should not affect snapshot
            job.visitedUrls.add("https://new.com");

            assertFalse(snapshot.getVisitedUrls().contains("https://new.com"));
        }

        @Test
        void checkpointHasTimestamp() {
            CrawlConfig config = baseConfig("https://example.com");
            WebCrawlJob job = new WebCrawlJob("job-6", config, null);

            CrawlState state = job.checkpoint();

            assertNotNull(state.getTimestamp());
        }
    }
}
