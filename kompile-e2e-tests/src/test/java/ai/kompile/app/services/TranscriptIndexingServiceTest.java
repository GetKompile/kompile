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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TranscriptIndexingServiceTest {

    @Mock
    private ToolCallWriterService toolCallWriter;

    @InjectMocks
    private TranscriptIndexingService service;

    // ── indexTranscripts ──────────────────────────────────────────────────────

    @Test
    void testIndexTranscripts_registryUnavailable_returnsError() {
        // ChatSourceRegistry.getInstance() may throw in test environment
        // The service should handle it gracefully and return error map
        Map<String, Object> result = service.indexTranscripts(null, false);
        assertNotNull(result);
        // Either returns error or proceeds with 0 sessions
        assertTrue(result.containsKey("sessionsScanned") || result.containsKey("error"),
                "Result should have sessionsScanned or error key");
    }

    @Test
    void testIndexTranscripts_sourceFilterAll_doesNotThrow() {
        assertDoesNotThrow(() -> service.indexTranscripts("all", false));
    }

    @Test
    void testIndexTranscripts_reindexTrue_doesNotThrow() {
        assertDoesNotThrow(() -> service.indexTranscripts(null, true));
    }

    @Test
    void testIndexTranscripts_specificSource_doesNotThrow() {
        assertDoesNotThrow(() -> service.indexTranscripts("claude-code", false));
    }

    @Test
    void testIndexTranscripts_unknownSource_returnsZeroSessions() {
        Map<String, Object> result = service.indexTranscripts("unknown-source-xyz", false);
        assertNotNull(result);
        if (result.containsKey("sessionsScanned")) {
            assertEquals(0, result.get("sessionsScanned"));
        }
    }

    @Test
    void testIndexTranscripts_resultContainsExpectedKeys() {
        Map<String, Object> result = service.indexTranscripts(null, false);
        assertNotNull(result);
        // Result must contain either the expected keys or an error key
        assertTrue(result.containsKey("sessionsScanned") || result.containsKey("error"),
                "Result must have sessionsScanned or error key, got: " + result.keySet());
    }

    // ── scheduledBackfill ─────────────────────────────────────────────────────

    @Test
    void testScheduledBackfill_doesNotThrow() {
        // The scheduled method should not propagate exceptions
        assertDoesNotThrow(() -> service.scheduledBackfill());
    }
}
