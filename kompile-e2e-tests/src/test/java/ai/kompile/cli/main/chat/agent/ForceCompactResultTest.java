/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.chat.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AgenticChatLoop.ForceCompactResult")
class ForceCompactResultTest {

    @Test
    void okFactoryPopulatesAllFields() {
        AgenticChatLoop.ForceCompactResult r =
                AgenticChatLoop.ForceCompactResult.ok(50_000, 5_000, 3, "summary text");

        assertEquals(AgenticChatLoop.ForceCompactResult.Status.OK, r.getStatus());
        assertTrue(r.isSuccess());
        assertEquals(50_000, r.getTokensBefore());
        assertEquals(5_000, r.getTokensAfter());
        assertEquals(3, r.getPreservedTurns());
        assertEquals("summary text", r.getSummary());
        assertNull(r.getMessage());
    }

    @Test
    void noopCarriesMessageAndIsNotSuccess() {
        AgenticChatLoop.ForceCompactResult r =
                AgenticChatLoop.ForceCompactResult.noop("nothing to compact");

        assertEquals(AgenticChatLoop.ForceCompactResult.Status.NOOP, r.getStatus());
        assertFalse(r.isSuccess());
        assertEquals("nothing to compact", r.getMessage());
        assertNull(r.getSummary());
        assertEquals(0, r.getTokensBefore());
        assertEquals(0, r.getTokensAfter());
    }

    @Test
    void unsupportedCarriesMessageAndIsNotSuccess() {
        AgenticChatLoop.ForceCompactResult r =
                AgenticChatLoop.ForceCompactResult.unsupported("server mode");

        assertEquals(AgenticChatLoop.ForceCompactResult.Status.UNSUPPORTED, r.getStatus());
        assertFalse(r.isSuccess());
        assertEquals("server mode", r.getMessage());
    }

    @Test
    void failedCarriesMessageAndIsNotSuccess() {
        AgenticChatLoop.ForceCompactResult r =
                AgenticChatLoop.ForceCompactResult.failed("empty summary");

        assertEquals(AgenticChatLoop.ForceCompactResult.Status.FAILED, r.getStatus());
        assertFalse(r.isSuccess());
        assertEquals("empty summary", r.getMessage());
    }

    @Test
    void allFourStatusValuesAreDistinct() {
        // Sanity: the enum has exactly the four statuses we expect, no stray values
        AgenticChatLoop.ForceCompactResult.Status[] values =
                AgenticChatLoop.ForceCompactResult.Status.values();
        assertEquals(4, values.length);
    }
}
