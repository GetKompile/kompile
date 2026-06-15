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

import java.util.concurrent.atomic.AtomicLong;

/**
 * A single memory pool tracking allocated bytes against a capacity.
 * Thread-safe via atomic operations.
 */
public class MemoryPool {

    public enum PoolType { WEIGHTS, ACTIVATIONS, KV_CACHE }

    private final PoolType type;
    private volatile long capacityBytes;
    private final AtomicLong allocatedBytes = new AtomicLong(0);

    public MemoryPool(PoolType type, long capacityBytes) {
        this.type = type;
        this.capacityBytes = capacityBytes;
    }

    public PoolType getType() {
        return type;
    }

    public long getCapacityBytes() {
        return capacityBytes;
    }

    public void setCapacityBytes(long capacityBytes) {
        this.capacityBytes = capacityBytes;
    }

    public long getAllocatedBytes() {
        return allocatedBytes.get();
    }

    public long getAvailableBytes() {
        return Math.max(0, capacityBytes - allocatedBytes.get());
    }

    public double getUtilization() {
        if (capacityBytes <= 0) return 0.0;
        return (double) allocatedBytes.get() / capacityBytes;
    }

    /**
     * Try to allocate bytes from this pool. Returns true if successful.
     */
    public boolean tryAllocate(long bytes) {
        while (true) {
            long current = allocatedBytes.get();
            long next = current + bytes;
            if (next > capacityBytes) {
                return false;
            }
            if (allocatedBytes.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    /**
     * Free bytes back to this pool.
     */
    public void free(long bytes) {
        allocatedBytes.addAndGet(-bytes);
        // Clamp to 0
        long current;
        while ((current = allocatedBytes.get()) < 0) {
            allocatedBytes.compareAndSet(current, 0);
        }
    }

    /**
     * Reset allocated bytes to zero.
     */
    public void reset() {
        allocatedBytes.set(0);
    }
}
