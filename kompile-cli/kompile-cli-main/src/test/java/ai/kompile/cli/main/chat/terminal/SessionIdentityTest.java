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

package ai.kompile.cli.main.chat.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the unified session identity (F8/WP10). */
class SessionIdentityTest {

    @Test
    void judgementsKeyOffEnforcerIdWhenEnforcementActive() {
        SessionIdentity id = SessionIdentity.of("emulated-abc123", "enf-999");
        assertTrue(id.hasEnforcerId());
        assertEquals("enf-999", id.forJudgements(), "enforcer id is canonical for judgements");
    }

    @Test
    void judgementsFallBackToKompileIdWhenNoEnforcer() {
        SessionIdentity id = SessionIdentity.of("emulated-abc123", null);
        assertFalse(id.hasEnforcerId());
        assertEquals("emulated-abc123", id.forJudgements());

        SessionIdentity blankEnf = SessionIdentity.of("emulated-abc123", "  ");
        assertFalse(blankEnf.hasEnforcerId(), "blank enforcer id is treated as absent");
        assertEquals("emulated-abc123", blankEnf.forJudgements());
    }

    @Test
    void agentNativeIdIsFilledInImmutablyOnDiscovery() {
        SessionIdentity id = SessionIdentity.of("emulated-abc123", "enf-999");
        assertNull(id.agentNativeSessionId());

        SessionIdentity withNative = id.withAgentNativeSessionId("claude-sess-42");
        assertEquals("claude-sess-42", withNative.agentNativeSessionId());
        // Original is unchanged (record copy), other ids carried over.
        assertNull(id.agentNativeSessionId());
        assertEquals("emulated-abc123", withNative.kompileSessionId());
        assertEquals("enf-999", withNative.enforcerSessionId());
    }
}
