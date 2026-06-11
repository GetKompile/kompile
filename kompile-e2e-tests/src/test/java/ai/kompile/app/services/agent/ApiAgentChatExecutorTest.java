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

package ai.kompile.app.services.agent;

import ai.kompile.app.services.agent.ModelCapabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ApiAgentChatExecutor} — URL normalization, endpoint testing,
 * stream cancellation state, and API stream tracking.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiAgentChatExecutorTest {

    private ApiAgentChatExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new ApiAgentChatExecutor(new ModelCapabilityService(null));
    }

    // ── isApiStream ─────────────────────────────────────────────────────────────

    @Test
    void isApiStream_unknownProcessId_returnsFalse() {
        assertFalse(executor.isApiStream("unknown-process-id"));
    }

    @Test
    void cancelApiStream_unknownProcessId_returnsFalse() {
        assertFalse(executor.cancelApiStream("no-such-process"));
    }

    // ── testEndpoint — unreachable host ─────────────────────────────────────────

    @Test
    void testEndpoint_unreachableHost_reachableFalse() {
        Map<String, Object> result = executor.testEndpoint("http://localhost:19999", null);
        assertNotNull(result);
        assertFalse((Boolean) result.get("reachable"),
                "Unreachable host should return reachable=false");
        assertTrue(result.containsKey("error"));
    }

    @Test
    void testEndpoint_nullUrl_returnsError() {
        // null endpoint URL should not throw NPE — should return error result
        Map<String, Object> result = executor.testEndpoint(null, null);
        assertNotNull(result);
        assertFalse((Boolean) result.get("reachable"));
    }

    @Test
    void testEndpoint_emptyUrl_returnsError() {
        Map<String, Object> result = executor.testEndpoint("", null);
        assertNotNull(result);
        assertFalse((Boolean) result.get("reachable"));
    }

    @Test
    void testEndpoint_malformedUrl_returnsError() {
        Map<String, Object> result = executor.testEndpoint("not-a-url", null);
        assertNotNull(result);
        assertFalse((Boolean) result.get("reachable"));
    }

    @Test
    void testEndpoint_urlWithTrailingSlash_handled() {
        // Should normalize trailing slash and not crash
        Map<String, Object> result = executor.testEndpoint("http://localhost:19998/", null);
        assertNotNull(result);
        // Unreachable, but shouldn't throw
        assertFalse((Boolean) result.get("reachable"));
    }

    @Test
    void testEndpoint_urlWithMultipleTrailingSlashes_handled() {
        Map<String, Object> result = executor.testEndpoint("http://localhost:19997///", null);
        assertNotNull(result);
        assertFalse((Boolean) result.get("reachable"));
    }
}
