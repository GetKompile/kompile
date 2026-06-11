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

package ai.kompile.app.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MemoryPoolTest {

    private static final long CAPACITY = 1024L * 1024L; // 1 MB

    private MemoryPool pool;

    @BeforeEach
    void setUp() {
        pool = new MemoryPool(MemoryPool.PoolType.WEIGHTS, CAPACITY);
    }

    @Test
    void initialStateIsCorrect() {
        assertThat(pool.getType()).isEqualTo(MemoryPool.PoolType.WEIGHTS);
        assertThat(pool.getCapacityBytes()).isEqualTo(CAPACITY);
        assertThat(pool.getAllocatedBytes()).isZero();
        assertThat(pool.getAvailableBytes()).isEqualTo(CAPACITY);
        assertThat(pool.getUtilization()).isEqualTo(0.0);
    }

    @Test
    void tryAllocate_successWhenFits() {
        boolean result = pool.tryAllocate(512 * 1024L);
        assertThat(result).isTrue();
        assertThat(pool.getAllocatedBytes()).isEqualTo(512 * 1024L);
        assertThat(pool.getAvailableBytes()).isEqualTo(512 * 1024L);
    }

    @Test
    void tryAllocate_failsWhenExceedsCapacity() {
        boolean result = pool.tryAllocate(CAPACITY + 1);
        assertThat(result).isFalse();
        assertThat(pool.getAllocatedBytes()).isZero();
    }

    @Test
    void tryAllocate_exactCapacitySucceeds() {
        boolean result = pool.tryAllocate(CAPACITY);
        assertThat(result).isTrue();
        assertThat(pool.getAllocatedBytes()).isEqualTo(CAPACITY);
        assertThat(pool.getAvailableBytes()).isZero();
    }

    @Test
    void tryAllocate_poolFullReturnsFalse() {
        pool.tryAllocate(CAPACITY);
        boolean result = pool.tryAllocate(1);
        assertThat(result).isFalse();
    }

    @Test
    void free_reducesAllocatedBytes() {
        pool.tryAllocate(CAPACITY);
        pool.free(512 * 1024L);
        assertThat(pool.getAllocatedBytes()).isEqualTo(512 * 1024L);
        assertThat(pool.getAvailableBytes()).isEqualTo(512 * 1024L);
    }

    @Test
    void free_doesNotGoNegative() {
        pool.tryAllocate(100);
        pool.free(1000); // free more than allocated
        assertThat(pool.getAllocatedBytes()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void reset_clearsAllocated() {
        pool.tryAllocate(CAPACITY);
        pool.reset();
        assertThat(pool.getAllocatedBytes()).isZero();
        assertThat(pool.getAvailableBytes()).isEqualTo(CAPACITY);
    }

    @Test
    void utilization_calculatedCorrectly() {
        pool.tryAllocate(CAPACITY / 2);
        assertThat(pool.getUtilization()).isCloseTo(0.5, within(0.001));
    }

    @Test
    void utilization_zeroWhenCapacityIsZero() {
        MemoryPool emptyPool = new MemoryPool(MemoryPool.PoolType.KV_CACHE, 0);
        assertThat(emptyPool.getUtilization()).isEqualTo(0.0);
    }

    @Test
    void setCapacityBytes_updatesCapacity() {
        long newCapacity = 2 * CAPACITY;
        pool.setCapacityBytes(newCapacity);
        assertThat(pool.getCapacityBytes()).isEqualTo(newCapacity);
    }

    @Test
    void allPoolTypesWork() {
        for (MemoryPool.PoolType type : MemoryPool.PoolType.values()) {
            MemoryPool p = new MemoryPool(type, CAPACITY);
            assertThat(p.getType()).isEqualTo(type);
        }
    }

    @Test
    void concurrentAllocations_doNotExceedCapacity() throws InterruptedException {
        int threads = 8;
        long allocPerThread = CAPACITY / 4; // only 4 can fit
        AtomicInteger successCount = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                if (pool.tryAllocate(allocPerThread)) {
                    successCount.incrementAndGet();
                }
                latch.countDown();
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Only 4 allocations should succeed (4 * CAPACITY/4 = CAPACITY)
        assertThat(successCount.get()).isLessThanOrEqualTo(4);
        assertThat(pool.getAllocatedBytes()).isLessThanOrEqualTo(CAPACITY);
    }

    @Test
    void allocateThenFree_allowsReallocation() {
        assertThat(pool.tryAllocate(CAPACITY)).isTrue();
        pool.free(CAPACITY);
        assertThat(pool.tryAllocate(CAPACITY)).isTrue();
    }
}
