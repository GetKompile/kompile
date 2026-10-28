package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.main.chat.config.SubagentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SubagentAdmissionTest {
    @Test void limitsAreSharedAndLoweringTheCapDoesNotTerminateExistingLeases() {
        var unlimited = SubagentConfig.defaults();
        var one = new SubagentConfig(1, List.of());
        try (var first = SubagentAdmission.acquire(one, "openai", "gpt-5")) {
            assertEquals(1, SubagentAdmission.activeCount());
            assertThrows(IllegalStateException.class, () -> SubagentAdmission.acquire(one, "other", "other"));
            assertThrows(IllegalStateException.class, () -> SubagentAdmission.acquire(new SubagentConfig(0, List.of()), null, null));
            try (var second = SubagentAdmission.acquire(unlimited, null, null)) {
                assertEquals(2, SubagentAdmission.activeCount());
            }
            assertEquals(1, SubagentAdmission.activeCount());
            first.close();
            first.close();
            assertEquals(0, SubagentAdmission.activeCount());
        }
        assertEquals(0, SubagentAdmission.activeCount());
    }

    @Test void modelBansAndUnverifiableRoutesDoNotConsumeCapacity() {
        var policy = new SubagentConfig(2, List.of("*haiku*", "openai/gpt-5"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> SubagentAdmission.acquire(policy, "anthropic", "claude-haiku-4.5")).getMessage().contains("*haiku*"));
        assertThrows(IllegalStateException.class, () -> SubagentAdmission.acquire(policy, "openai", "gpt-5"));
        assertThrows(IllegalStateException.class, () -> SubagentAdmission.acquire(policy, "server", null));
        assertEquals(0, SubagentAdmission.activeCount());
        try (var lease = SubagentAdmission.acquire(policy, "other", "gpt-5")) {
            assertEquals(1, SubagentAdmission.activeCount());
        }
    }

    @Test @Timeout(10) void concurrentAcquisitionNeverExceedsCapAndReleasesAfterExceptions() throws Exception {
        var policy = new SubagentConfig(3, List.of());
        var pool = Executors.newFixedThreadPool(16);
        var attempted = new CountDownLatch(16);
        var release = new CountDownLatch(1);
        var accepted = new AtomicInteger();
        var peak = new AtomicInteger();
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int i = 0; i < 16; i++) futures.add(pool.submit(() -> {
                SubagentAdmission.Lease lease;
                try { lease = SubagentAdmission.acquire(policy, "custom", "model"); }
                catch (IllegalStateException rejected) { attempted.countDown(); return; }
                try (lease) {
                    accepted.incrementAndGet();
                    peak.accumulateAndGet(SubagentAdmission.activeCount(), Math::max);
                    attempted.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    throw new IllegalArgumentException("simulated provider failure");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (IllegalArgumentException expected) { }
            }));
            assertTrue(attempted.await(5, TimeUnit.SECONDS));
            assertEquals(3, accepted.get());
            assertEquals(3, peak.get());
            release.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
        assertEquals(0, SubagentAdmission.activeCount());
    }
}
