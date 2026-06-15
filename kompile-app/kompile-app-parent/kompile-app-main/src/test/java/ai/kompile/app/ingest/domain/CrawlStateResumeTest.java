package ai.kompile.app.ingest.domain;

import ai.kompile.core.crawler.CrawlState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CrawlState Resume Features")
class CrawlStateResumeTest {

    @Nested
    @DisplayName("pendingUrls field")
    class PendingUrls {

        @Test
        void defaultsToEmptyList() {
            CrawlState state = CrawlState.builder().build();
            assertNotNull(state.getPendingUrls());
            assertTrue(state.getPendingUrls().isEmpty());
        }

        @Test
        void storesPendingUrlsWithDepth() {
            CrawlState state = CrawlState.builder()
                    .pendingUrls(List.of(
                            "https://example.com/page1::1",
                            "https://example.com/page2::2",
                            "https://example.com/page3::0"
                    ))
                    .build();

            assertEquals(3, state.getPendingUrls().size());
            assertEquals("https://example.com/page1::1", state.getPendingUrls().get(0));
        }

        @Test
        void preservedInBuilder() {
            CrawlState state = CrawlState.builder()
                    .timestamp(Instant.now())
                    .visitedUrls(Set.of("https://example.com"))
                    .pendingUrls(List.of("https://example.com/a::1"))
                    .build();

            assertEquals(1, state.getVisitedUrls().size());
            assertEquals(1, state.getPendingUrls().size());
        }
    }

    @Nested
    @DisplayName("JSON serialization")
    class JsonSerialization {

        private final ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        @Test
        void roundTripWithPendingUrls() throws Exception {
            CrawlState original = CrawlState.builder()
                    .timestamp(Instant.now())
                    .visitedUrls(Set.of("https://a.com", "https://b.com"))
                    .pendingUrls(List.of("https://c.com::1", "https://d.com::2"))
                    .build();

            String json = mapper.writeValueAsString(original);
            CrawlState loaded = mapper.readValue(json, CrawlState.class);

            assertEquals(2, loaded.getVisitedUrls().size());
            assertTrue(loaded.getVisitedUrls().contains("https://a.com"));
            assertEquals(2, loaded.getPendingUrls().size());
            assertEquals("https://c.com::1", loaded.getPendingUrls().get(0));
        }

        @Test
        void deserializeWithoutPendingUrls() throws Exception {
            // Simulate older checkpoint that doesn't have pendingUrls
            String json = "{\"timestamp\":\"2025-01-01T00:00:00Z\",\"visitedUrls\":[\"https://a.com\"]}";
            CrawlState loaded = mapper.readValue(json, CrawlState.class);

            assertEquals(1, loaded.getVisitedUrls().size());
            // pendingUrls should be null when not in JSON (no @Builder.Default during deserialization)
            // but the code should handle it gracefully
        }

        @Test
        void emptyPendingUrlsRoundTrip() throws Exception {
            CrawlState original = CrawlState.builder()
                    .timestamp(Instant.now())
                    .pendingUrls(List.of())
                    .build();

            String json = mapper.writeValueAsString(original);
            CrawlState loaded = mapper.readValue(json, CrawlState.class);

            assertNotNull(loaded.getPendingUrls());
            assertTrue(loaded.getPendingUrls().isEmpty());
        }
    }

    @Nested
    @DisplayName("Existing CrawlState methods")
    class ExistingMethods {

        @Test
        void wasVisited() {
            CrawlState state = CrawlState.builder()
                    .visitedUrls(Set.of("https://a.com", "https://b.com"))
                    .build();

            assertTrue(state.wasVisited("https://a.com"));
            assertFalse(state.wasVisited("https://c.com"));
        }

        @Test
        void hasChanged() {
            CrawlState state = CrawlState.builder().build();
            state.getContentHashes().put("https://a.com", "hash1");

            assertFalse(state.hasChanged("https://a.com", "hash1"));
            assertTrue(state.hasChanged("https://a.com", "hash2"));
            assertTrue(state.hasChanged("https://new.com", "hash3"));
        }
    }
}
