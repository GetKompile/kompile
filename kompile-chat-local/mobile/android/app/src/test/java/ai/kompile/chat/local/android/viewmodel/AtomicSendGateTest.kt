package ai.kompile.chat.local.android.viewmodel

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicSendGateTest {

    @Test
    fun concurrentUiAndImeSubmissionsAdmitExactlyOneTurn() {
        repeat(100) {
            val gate = AtomicSendGate()
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val admitted = AtomicInteger()
            val pool = Executors.newFixedThreadPool(2)
            try {
                val submissions = List(2) {
                    pool.submit {
                        ready.countDown()
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        if (gate.tryAcquire()) admitted.incrementAndGet()
                    }
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                start.countDown()
                submissions.forEach { it.get(5, TimeUnit.SECONDS) }
                assertEquals(1, admitted.get())
                gate.release()
                assertTrue(gate.tryAcquire())
                gate.release()
            } finally {
                pool.shutdownNow()
            }
        }
    }
}
