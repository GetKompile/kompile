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

package ai.kompile.cli.main.chat.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** A subagent session's identity holds on the threads that work for it, and nowhere else. */
class SessionEnvironmentTest {

    private static final String DEPTH = "KOMPILE_SUBAGENT_DEPTH";

    @Test
    void theSessionsValueWinsOnlyWhileItsWorkRuns() throws Exception {
        assertNull(System.getenv(DEPTH), "this test JVM must run as a top-level process");

        String inside = SessionEnvironment.call(Map.of(DEPTH, "2"), () -> SessionEnvironment.get(DEPTH));

        assertEquals("2", inside);
        assertNull(SessionEnvironment.get(DEPTH));
        assertTrue(SessionEnvironment.current().isEmpty());
        assertEquals("fallback", SessionEnvironment.getOrDefault(DEPTH, "fallback"));
    }

    @Test
    void nestedSessionsRestoreTheOuterOne() {
        SessionEnvironment.run(Map.of(DEPTH, "1"), () -> {
            SessionEnvironment.run(Map.of(DEPTH, "2"), () -> assertEquals("2", SessionEnvironment.get(DEPTH)));
            assertEquals("1", SessionEnvironment.get(DEPTH));
        });
        assertNull(SessionEnvironment.get(DEPTH));
    }

    @Test
    void wrappedWorkCarriesTheSessionToAnotherThreadAndOnlyThatWork() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            String[] seen = new String[2];
            SessionEnvironment.run(Map.of(DEPTH, "1"), () -> {
                try {
                    seen[0] = pool.submit(SessionEnvironment.wrap(() -> SessionEnvironment.get(DEPTH))).get();
                    // The pool thread was first created by this session's call: it must not keep it.
                    seen[1] = pool.submit(() -> SessionEnvironment.get(DEPTH)).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            assertEquals("1", seen[0]);
            assertNull(seen[1]);
        } finally {
            pool.shutdownNow();
        }
    }
}
