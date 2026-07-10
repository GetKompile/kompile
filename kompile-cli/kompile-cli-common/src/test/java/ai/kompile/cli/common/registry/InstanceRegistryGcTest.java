/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.common.registry;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InstanceRegistry#gcDeadInstances()} and the
 * {@link InstanceRegistry#isPortBound(int)} helper.
 *
 * GC tests write unique-named entries to the real {@code ~/.kompile/instances/}
 * directory and clean them up in finally blocks so they are safe to run in CI.
 */
class InstanceRegistryGcTest {

    // ── isPortBound ───────────────────────────────────────────────────────────

    @Test
    void isPortBound_returnsFalse_whenPortIsFree() throws IOException {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        // The socket is closed; the port should be free now
        assertFalse(InstanceRegistry.isPortBound(freePort),
                "A newly-freed port should not appear bound");
    }

    @Test
    void isPortBound_returnsTrue_whenPortIsOccupied() throws IOException {
        try (ServerSocket occupied = new ServerSocket(0)) {
            int port = occupied.getLocalPort();
            assertTrue(InstanceRegistry.isPortBound(port),
                    "A port held open by a ServerSocket should be reported as bound");
        }
    }

    // ── gcDeadInstances — PID dead + port free ────────────────────────────────

    @Test
    void gcDeadInstances_removesStaleEntry_whenPidDeadAndPortFree() throws Exception {
        // Allocate a port then immediately release it so the port is free
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }

        // PID 0 is never a valid live process handle
        String entryName = "test-gc-stale-" + System.currentTimeMillis();
        InstanceInfo stale = InstanceInfo.builder()
                .name(entryName)
                .type("kompile-app-main")
                .port(freePort)
                .pid(0L)
                .startedAt(Instant.now())
                .build();
        InstanceRegistry.register(stale);

        try {
            List<InstanceInfo> removed = InstanceRegistry.gcDeadInstances();

            assertTrue(removed.stream().anyMatch(r -> entryName.equals(r.getName())),
                    "stale entry (dead PID + free port) should be removed by GC");
            assertNull(InstanceRegistry.get(entryName),
                    "stale entry should not survive GC");
        } finally {
            // Safety net: remove in case assertion fires before unregister
            InstanceRegistry.unregister(entryName);
        }
    }

    // ── gcDeadInstances — port still bound keeps entry ────────────────────────

    @Test
    void gcDeadInstances_keepsEntry_whenPortStillBound() throws Exception {
        // Hold the port open for the duration of the test
        try (ServerSocket occupied = new ServerSocket(0)) {
            int port = occupied.getLocalPort();

            String entryName = "test-gc-portbound-" + System.currentTimeMillis();
            InstanceInfo live = InstanceInfo.builder()
                    .name(entryName)
                    .type("kompile-app-main")
                    .port(port)
                    .pid(0L)   // PID dead, but port is still bound
                    .startedAt(Instant.now())
                    .build();
            InstanceRegistry.register(live);

            try {
                List<InstanceInfo> removed = InstanceRegistry.gcDeadInstances();

                assertTrue(removed.stream().noneMatch(r -> entryName.equals(r.getName())),
                        "entry with a still-bound port should NOT be GC'd even when PID is dead");
                assertNotNull(InstanceRegistry.get(entryName),
                        "entry should still be registered when its port is occupied");
            } finally {
                InstanceRegistry.unregister(entryName);
            }
        }
    }
}
