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

package ai.kompile.cli.main.coordination;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for the silent coordination heartbeat failure.
 *
 * <p>{@link EditLockEntry}, {@link AgentEntry} and {@link ProcessCoordEntry} were Lombok
 * {@code @Data} with field-level {@code @JsonProperty} and an all-args constructor but no
 * no-arg constructor and no {@code @JsonCreator}, so Jackson threw
 * {@code "no Creators, like default constructor, exist"} on every read — failing the
 * coordination heartbeat (and edit-lock/agent/process queries) silently every 30s.
 * {@code @NoArgsConstructor} restores property-based deserialization. Uses the same
 * {@link JsonUtils#standardMapper()} the coordination manager uses.</p>
 */
class CoordinationEntryDeserializationTest {

    private final ObjectMapper mapper = JsonUtils.standardMapper();

    @Test
    void editLockEntryRoundTrips() throws Exception {
        EditLockEntry e = new EditLockEntry("lock1", "sess1", "coder",
                "src/A.java", "/abs/src/A.java", "write", Instant.now(), 300);

        EditLockEntry back = mapper.readValue(mapper.writeValueAsString(e), EditLockEntry.class);

        assertEquals("lock1", back.getLockId());
        assertEquals("sess1", back.getSessionId());
        assertEquals("/abs/src/A.java", back.getAbsolutePath());
        assertEquals(300, back.getTtlSeconds());
    }

    @Test
    void agentEntryRoundTrips() throws Exception {
        AgentEntry e = new AgentEntry("sess1", "coder", "agent", "parent", 1,
                "do things", "/wd", 1234L, Instant.now(), 60);

        AgentEntry back = mapper.readValue(mapper.writeValueAsString(e), AgentEntry.class);

        assertEquals("sess1", back.getSessionId());
        assertEquals("coder", back.getAgentName());
        assertEquals(1234L, back.getPid());
    }

    @Test
    void processCoordEntryRoundTrips() throws Exception {
        ProcessCoordEntry e = new ProcessCoordEntry("proc1", "sess1", "coder",
                "run x", "desc", 4321L, "RUNNING", Instant.now(), "/out.log", 120);

        ProcessCoordEntry back =
                mapper.readValue(mapper.writeValueAsString(e), ProcessCoordEntry.class);

        assertEquals("proc1", back.getProcessId());
        assertEquals(4321L, back.getPid());
        assertEquals("RUNNING", back.getState());
    }
}
