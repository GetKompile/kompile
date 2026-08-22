package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelDiscoveryCacheTest {

    @Test
    void freshEntriesBecomeStaleThenExpire() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ModelDiscoveryCache cache = new ModelDiscoveryCache(
                clock, Duration.ofMinutes(5), Duration.ofHours(1));
        ModelDiscovery.Result result = ModelDiscovery.Result.success(
                List.of(new LiveModelDiscovery.Model("provider/model", List.of())),
                List.of("https://example.test/models"));

        cache.put("Provider", "https://example.test/v1/", result);
        assertEquals(1, cache.size());
        assertTrue(cache.fresh("provider", "https://example.test/v1").isPresent());

        clock.advance(Duration.ofMinutes(10));
        assertFalse(cache.fresh("provider", "https://example.test/v1").isPresent());
        assertTrue(cache.stale("provider", "https://example.test/v1").isPresent());

        clock.advance(Duration.ofHours(2));
        assertFalse(cache.stale("provider", "https://example.test/v1").isPresent());
    }

    @Test
    void emptyOrFailedResultsAreNeverCached() {
        ModelDiscoveryCache cache = new ModelDiscoveryCache();
        cache.put("provider", "https://example.test", ModelDiscovery.Result.success(
                List.of(), List.of("https://example.test/models")));
        cache.put("provider", "https://example.test", ModelDiscovery.Result.failure(
                ModelDiscovery.Status.UNAVAILABLE, "offline", List.of()));
        assertEquals(0, cache.size());
    }

    @Test
    void credentialsDoNotShareModelLists() {
        ModelDiscoveryCache cache = new ModelDiscoveryCache();
        ModelDiscovery.Result result = ModelDiscovery.Result.success(
                List.of(new LiveModelDiscovery.Model("provider/model", List.of())),
                List.of("https://example.test/models"));

        cache.put("provider", "https://example.test", "account-a", result);
        assertTrue(cache.fresh("provider", "https://example.test", "account-a").isPresent());
        assertFalse(cache.fresh("provider", "https://example.test", "account-b").isPresent());
    }

    @Test
    void cacheSizeIsBounded() {
        ModelDiscoveryCache cache = new ModelDiscoveryCache();
        ModelDiscovery.Result result = ModelDiscovery.Result.success(
                List.of(new LiveModelDiscovery.Model("provider/model", List.of())),
                List.of("https://example.test/models"));

        for (int i = 0; i < 129; i++) {
            cache.put("provider-" + i, "https://example.test", result);
        }
        assertEquals(128, cache.size());
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
