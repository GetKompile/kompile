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

package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.main.chat.harness.JudgeBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Activation-logic tests for the shared realtime enforcement tap (WP9/WP12/F5i). */
class RealtimeEnforcementTapTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JudgeBackend STUB_BACKEND = new JudgeBackend() {
        @Override public String generate(String userPrompt, String systemPrompt) { return "{}"; }
        @Override public boolean isAvailable() { return true; }
    };

    private static final EnforcerJsonlTailer.ViolationHandler NOOP = (r, c, t) -> { };

    @Test
    void activeWithJudgeAndRules(@TempDir Path tmp) {
        EnforcerJudge judge = new EnforcerJudge(STUB_BACKEND, MAPPER);
        EnforcerPolicy policy = new EnforcerPolicy("BAN: rm -rf", 2, false);
        RealtimeEnforcementTap tap = RealtimeEnforcementTap.fromComponents(
                "claude", tmp, MAPPER, judge, policy, NOOP);
        assertTrue(tap.isActive(), "judge + rules + handler → active");
        assertDoesNotThrow(tap::start);
        assertDoesNotThrow(tap::close);
    }

    @Test
    void inactiveWithoutJudge(@TempDir Path tmp) {
        RealtimeEnforcementTap tap = RealtimeEnforcementTap.fromComponents(
                "claude", tmp, MAPPER, null, new EnforcerPolicy("BAN: x", 2, false), NOOP);
        assertFalse(tap.isActive(), "no judge → inactive");
    }

    @Test
    void inactiveWithoutRules(@TempDir Path tmp) {
        EnforcerJudge judge = new EnforcerJudge(STUB_BACKEND, MAPPER);
        RealtimeEnforcementTap tap = RealtimeEnforcementTap.fromComponents(
                "claude", tmp, MAPPER, judge, new EnforcerPolicy("", 2, false), NOOP);
        assertFalse(tap.isActive(), "policy with no rules → inactive");
    }

    @Test
    void inactiveForKeywordConfig(@TempDir Path tmp) {
        EnforcerConfig config = new EnforcerConfig();
        config.setKeywordMode(true);
        config.setInlineRules("BAN: rm -rf");
        RealtimeEnforcementTap tap = RealtimeEnforcementTap.fromConfig(
                "claude", tmp, config, MAPPER, NOOP);
        assertFalse(tap.isActive(), "keyword mode uses prompt-injection, not the judge tap");
    }

    @Test
    void inactiveTapIsANoOp() {
        RealtimeEnforcementTap tap = RealtimeEnforcementTap.inactive();
        assertFalse(tap.isActive());
        assertDoesNotThrow(tap::start);
        assertDoesNotThrow(tap::close);
    }
}
