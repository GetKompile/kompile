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

package ai.kompile.cli.main.chat.harness;

import ai.kompile.cli.main.chat.agent.SubprocessAgentRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for CliJudgeBackend persistent mode using the
 * stream-json stdin/stdout protocol. Requires the claude CLI to
 * be installed and authenticated.
 */
class CliJudgeBackendStreamJsonTest {

    @Test
    void persistentModeMultiTurn() throws Exception {
        String binary = SubprocessAgentRunner.resolveAgentBinary("claude");
        Assumptions.assumeTrue(binary != null, "claude CLI not installed");

        CliJudgeBackend backend = new CliJudgeBackend("claude");
        assertTrue(backend.isAvailable());
        assertTrue(backend.describe().contains("persistent-stream-json"));

        String systemPrompt = "You are a test judge. Respond with ONLY valid JSON. No prose, no markdown fences. Just raw JSON.";

        try {
            // Warm up — starts the persistent process
            backend.warmUp(systemPrompt);

            // Turn 1: ask for compliant JSON
            String result1 = backend.generate(
                    "Return exactly this JSON: {\"compliant\":true,\"test\":\"turn1\"}",
                    systemPrompt);
            assertNotNull(result1, "Turn 1 should return non-null");
            assertFalse(result1.isEmpty(), "Turn 1 should return non-empty");
            System.out.println("[Turn 1] " + result1);
            assertTrue(result1.contains("compliant") || result1.contains("turn1"),
                    "Turn 1 should contain expected JSON fields: " + result1);

            // Turn 2: ask for different JSON — same process, no restart
            String result2 = backend.generate(
                    "Return exactly this JSON: {\"compliant\":false,\"test\":\"turn2\"}",
                    systemPrompt);
            assertNotNull(result2, "Turn 2 should return non-null");
            assertFalse(result2.isEmpty(), "Turn 2 should return non-empty");
            System.out.println("[Turn 2] " + result2);
            assertTrue(result2.contains("compliant") || result2.contains("turn2"),
                    "Turn 2 should contain expected JSON fields: " + result2);
        } finally {
            backend.close();
        }
    }

    @Test
    void warmUpSignalsReadiness() throws Exception {
        String binary = SubprocessAgentRunner.resolveAgentBinary("claude");
        Assumptions.assumeTrue(binary != null, "claude CLI not installed");

        CliJudgeBackend backend = new CliJudgeBackend("claude");
        try {
            long start = System.currentTimeMillis();
            backend.warmUp("You are a test judge.");
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("warmUp completed in " + elapsed + "ms");

            // warmUp should complete (not hang forever)
            assertTrue(elapsed < 30_000, "warmUp should complete within 30s");
        } finally {
            backend.close();
        }
    }
}
