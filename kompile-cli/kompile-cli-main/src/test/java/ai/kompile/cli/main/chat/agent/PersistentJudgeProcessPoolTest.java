package ai.kompile.cli.main.chat.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lease semantics of the shared judge process pool: same-spec sharing, keep-warm on
 * release, idle reaping, dead-process replacement, and prompt isolation.
 */
class PersistentJudgeProcessPoolTest {

    static final class FakeProcess implements PersistentJudgeProcessPool.PooledProcess {
        final int id;
        volatile boolean alive = true;
        volatile boolean closed;

        FakeProcess(int id) {
            this.id = id;
        }

        @Override
        public String sendMessage(String message, int timeoutSeconds) {
            return "ok-" + id;
        }

        @Override
        public boolean isAlive() {
            return alive && !closed;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final AtomicInteger creates = new AtomicInteger();
    private final List<FakeProcess> created = new CopyOnWriteArrayList<>();

    private PersistentJudgeProcessPool.Spec spec(String systemPrompt) {
        return new PersistentJudgeProcessPool.Spec(
                "fake-judge", "haiku", true,
                List.of("--tools", ""), List.of("KOMPILE_ENFORCER_"),
                systemPrompt, 5);
    }

    @BeforeEach
    void installFakeFactory() {
        PersistentJudgeProcessPool.resetForTests();
        PersistentJudgeProcessPool.factory = s -> {
            FakeProcess p = new FakeProcess(creates.incrementAndGet());
            created.add(p);
            return p;
        };
    }

    @AfterEach
    void reset() {
        PersistentJudgeProcessPool.resetForTests();
    }

    @Test
    void sameSpecSharesOneProcess() throws Exception {
        PersistentJudgeProcessPool.Lease a = PersistentJudgeProcessPool.acquire(spec("P"));
        PersistentJudgeProcessPool.Lease b = PersistentJudgeProcessPool.acquire(spec("P"));

        assertEquals(1, creates.get(), "identical specs must share one process");
        assertEquals("ok-1", a.sendMessage("x", 5));
        assertEquals("ok-1", b.sendMessage("y", 5));

        a.close();
        b.close();
    }

    @Test
    void releaseKeepsProcessWarmForReacquire() throws Exception {
        PersistentJudgeProcessPool.setIdleMillisForTests(60_000);

        PersistentJudgeProcessPool.Lease a = PersistentJudgeProcessPool.acquire(spec("P"));
        a.close();
        // Within the idle window the process must still be there — no new spawn.
        PersistentJudgeProcessPool.Lease b = PersistentJudgeProcessPool.acquire(spec("P"));

        assertEquals(1, creates.get(), "re-acquire inside the idle window must reuse the warm process");
        assertFalse(created.get(0).closed);
        b.close();
    }

    @Test
    void idleProcessIsReapedAfterLastRelease() throws Exception {
        PersistentJudgeProcessPool.setIdleMillisForTests(50);

        PersistentJudgeProcessPool.Lease a = PersistentJudgeProcessPool.acquire(spec("P"));
        a.close();

        awaitTrue(() -> created.get(0).closed, 2_000,
                "idle process should be destroyed after the idle window");
        awaitTrue(() -> PersistentJudgeProcessPool.pooledCount() == 0, 2_000,
                "reaped entry should leave the pool");

        // Next acquire spawns fresh.
        PersistentJudgeProcessPool.Lease b = PersistentJudgeProcessPool.acquire(spec("P"));
        assertEquals(2, creates.get());
        b.close();
    }

    @Test
    void outstandingLeaseBlocksReaping() throws Exception {
        PersistentJudgeProcessPool.setIdleMillisForTests(50);

        PersistentJudgeProcessPool.Lease a = PersistentJudgeProcessPool.acquire(spec("P"));
        PersistentJudgeProcessPool.Lease b = PersistentJudgeProcessPool.acquire(spec("P"));
        a.close();
        a.close(); // double-close must not decrement twice

        Thread.sleep(200);
        assertFalse(created.get(0).closed,
                "process must stay alive while another lease is outstanding");
        b.close();
    }

    @Test
    void deadProcessIsReplacedOnAcquire() throws Exception {
        PersistentJudgeProcessPool.Lease a = PersistentJudgeProcessPool.acquire(spec("P"));
        created.get(0).alive = false; // simulate agent crash

        PersistentJudgeProcessPool.Lease b = PersistentJudgeProcessPool.acquire(spec("P"));
        assertEquals(2, creates.get(), "dead process must be replaced");
        assertTrue(created.get(0).closed, "dead process must be cleaned up");
        assertEquals("ok-2", b.sendMessage("x", 5));

        a.close();
        b.close();
    }

    @Test
    void differentSystemPromptsNeverShare() throws Exception {
        PersistentJudgeProcessPool.Lease a = PersistentJudgeProcessPool.acquire(spec("enforcer prompt"));
        PersistentJudgeProcessPool.Lease b = PersistentJudgeProcessPool.acquire(spec("post-feedback prompt"));

        assertEquals(2, creates.get(),
                "system prompt is applied only at spawn — different prompts must not share");

        a.close();
        b.close();
    }

    @Test
    void closeAllDestroysEverything() throws Exception {
        PersistentJudgeProcessPool.acquire(spec("P1"));
        PersistentJudgeProcessPool.acquire(spec("P2"));

        PersistentJudgeProcessPool.closeAll();

        assertTrue(created.stream().allMatch(p -> p.closed));
        assertEquals(0, PersistentJudgeProcessPool.pooledCount());
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, long timeoutMs,
                                  String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail(message);
    }
}
