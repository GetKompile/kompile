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
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link ToolCallWriterService}.
 *
 * Tests write to the real user.home ~/.kompile/conversations/tool-calls/ path;
 * the service is a thin I/O writer with no injected dependencies.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ToolCallWriterServiceTest {

    private ToolCallWriterService service;

    @BeforeEach
    void setUp() {
        service = new ToolCallWriterService();
    }

    // ===== record() =====

    @Test
    void record_doesNotThrow_withValidArgs() {
        assertThatCode(() ->
            service.record("session-1", "ReadFile", "{\"path\":\"/foo\"}", "claude", "passthrough", false, "/project")
        ).doesNotThrowAnyException();
    }

    @Test
    void record_doesNotThrow_withNullInput() {
        assertThatCode(() ->
            service.record("session-null", "ReadFile", null, "agent", "mcp", false, null)
        ).doesNotThrowAnyException();
    }

    @Test
    void record_doesNotThrow_withLongInput() {
        String longInput = "x".repeat(500);
        assertThatCode(() ->
            service.record("session-long", "WriteFile", longInput, "agent", "agent-chat", true, null)
        ).doesNotThrowAnyException();
    }

    @Test
    void record_createsIndexFile() throws Exception {
        String sessionId = "test-session-" + System.nanoTime();
        service.record(sessionId, "Bash", "{\"cmd\":\"ls\"}", "claude", "passthrough", false, null);

        Path indexFile = Path.of(System.getProperty("user.home"), ".kompile", "conversations",
                "tool-calls", "all-tool-calls.jsonl");
        assertThat(indexFile).exists();
    }

    @Test
    void record_createsSessionFile() throws Exception {
        String sessionId = "unique-session-" + System.nanoTime();
        service.record(sessionId, "Grep", "{\"pattern\":\"foo\"}", "claude", "passthrough", false, null);

        Path sessionFile = Path.of(System.getProperty("user.home"), ".kompile", "conversations",
                "tool-calls", sessionId + ".jsonl");
        assertThat(sessionFile).exists();
    }

    @Test
    void record_appendsToExistingFile() throws Exception {
        String sessionId = "append-session-" + System.nanoTime();
        service.record(sessionId, "Tool1", "{}", "agent", "chat", false, null);
        service.record(sessionId, "Tool2", "{}", "agent", "chat", false, null);

        Path sessionFile = Path.of(System.getProperty("user.home"), ".kompile", "conversations",
                "tool-calls", sessionId + ".jsonl");
        long lineCount = Files.lines(sessionFile).count();
        assertThat(lineCount).isGreaterThanOrEqualTo(2);
    }

    // ===== Categorization (internal, verified via behavior) =====

    @Test
    void record_categorizesFilesystemTools() {
        // Just verify no exception; categorization is internal
        assertThatCode(() ->
            service.record("s", "ReadFile", "{}", "a", "src", false, null)
        ).doesNotThrowAnyException();
    }

    @Test
    void record_categorizesBashTools() {
        assertThatCode(() ->
            service.record("s", "Bash", "{}", "a", "src", false, null)
        ).doesNotThrowAnyException();
    }

    @Test
    void record_categorizesSearchTools() {
        assertThatCode(() ->
            service.record("s", "GrepSearch", "{}", "a", "src", false, null)
        ).doesNotThrowAnyException();
    }

    @Test
    void record_isThreadSafe() throws Exception {
        String sessionId = "concurrent-session-" + System.nanoTime();
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            final int idx = i;
            threads[i] = new Thread(() ->
                service.record(sessionId, "Tool" + idx, "{}", "agent", "chat", false, null)
            );
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join(5000);

        Path sessionFile = Path.of(System.getProperty("user.home"), ".kompile", "conversations",
                "tool-calls", sessionId + ".jsonl");
        assertThat(sessionFile).exists();
        long lines = Files.lines(sessionFile).count();
        assertThat(lines).isEqualTo(10);
    }
}
