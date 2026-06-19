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

package ai.kompile.cli.main.chat.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the two bug-prone pure helpers in {@link CliToolGatewayInterceptor}:
 * the cheap-local-tool bypass and the dual feature-flag key reader. Both guard
 * against the gateway turning a mundane local tool call (grep) into a blocking,
 * timeout-prone network LLM round-trip.
 */
class CliToolGatewayInterceptorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Cheap local tools must bypass network gating ────────────────────────

    @Test
    void cheapLocalToolsAreAlwaysAllowed() {
        assertTrue(CliToolGatewayInterceptor.isAlwaysAllowed("read"));
        assertTrue(CliToolGatewayInterceptor.isAlwaysAllowed("grep"));
        assertTrue(CliToolGatewayInterceptor.isAlwaysAllowed("glob"));
        assertTrue(CliToolGatewayInterceptor.isAlwaysAllowed("list"));
    }

    @Test
    void mutatingOrAgentToolsAreNotAlwaysAllowed() {
        assertFalse(CliToolGatewayInterceptor.isAlwaysAllowed("write"));
        assertFalse(CliToolGatewayInterceptor.isAlwaysAllowed("edit"));
        assertFalse(CliToolGatewayInterceptor.isAlwaysAllowed("bash"));
        assertFalse(CliToolGatewayInterceptor.isAlwaysAllowed("task"));
        assertFalse(CliToolGatewayInterceptor.isAlwaysAllowed(null));
        assertFalse(CliToolGatewayInterceptor.isAlwaysAllowed("unknown_tool"));
    }

    // ── Feature flag must honor BOTH the canonical and the short key ─────────

    @Test
    void canonicalKeyEnablesGateway() throws Exception {
        assertTrue(CliToolGatewayInterceptor.isGatewayFlagEnabled(
                objectMapper.readTree("{\"toolGatewayEnabled\": true}")));
    }

    @Test
    void shortKeyEnablesGateway() throws Exception {
        // The user's feature-flags-config.json uses the short "toolGateway" key.
        assertTrue(CliToolGatewayInterceptor.isGatewayFlagEnabled(
                objectMapper.readTree("{\"toolGateway\": true}")));
    }

    @Test
    void canonicalKeyTakesPrecedenceWhenPresent() throws Exception {
        assertTrue(CliToolGatewayInterceptor.isGatewayFlagEnabled(
                objectMapper.readTree("{\"toolGatewayEnabled\": true, \"toolGateway\": false}")));
        assertFalse(CliToolGatewayInterceptor.isGatewayFlagEnabled(
                objectMapper.readTree("{\"toolGatewayEnabled\": false, \"toolGateway\": true}")));
    }

    @Test
    void gatewayDisabledByDefault() throws Exception {
        // Matches the real feature-flags-config.json: "toolGateway": false.
        assertFalse(CliToolGatewayInterceptor.isGatewayFlagEnabled(
                objectMapper.readTree("{\"toolGateway\": false}")));
        assertFalse(CliToolGatewayInterceptor.isGatewayFlagEnabled(
                objectMapper.readTree("{\"guardrails\": false}")));   // neither key present
        assertFalse(CliToolGatewayInterceptor.isGatewayFlagEnabled(null));
    }
}
