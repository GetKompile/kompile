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
package ai.kompile.chat.history.service;

import ai.kompile.cli.common.chat.sources.ChatSourceAdapter;
import ai.kompile.cli.common.chat.sources.ChatSourceRegistry;
import ai.kompile.chat.history.service.CliTranscriptService.CliSessionSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for the scope-registered background sync: sync only runs for explicitly registered
 * code-project directories, iterates the {@link ChatSourceRegistry} adapters, imports each
 * discovered session individually, and reports progress through {@code getStatus()}.
 *
 * <p>The sync worker is asynchronous (daemon thread), so tests await the status transition to
 * completed rather than asserting immediately after the trigger.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CliTranscriptSyncService Tests")
class CliTranscriptSyncServiceTest {

    @Mock
    private CliTranscriptService cliTranscriptService;

    private CliTranscriptSyncService syncService;
    private ChatSourceRegistry originalRegistry;

    @TempDir
    private Path projectDir;

    @BeforeEach
    void setUp() {
        originalRegistry = ChatSourceRegistry.getInstance();
        ChatSourceRegistry.setInstance(ChatSourceRegistry.of(List.of(
                adapter("claude-code"), adapter("opencode"))));
        syncService = new CliTranscriptSyncService(cliTranscriptService, null);
    }

    @AfterEach
    void tearDown() {
        ChatSourceRegistry.setInstance(originalRegistry);
    }

    private static ChatSourceAdapter adapter(String id) {
        ChatSourceAdapter adapter = mock(ChatSourceAdapter.class);
        when(adapter.id()).thenReturn(id);
        return adapter;
    }

    private static CliSessionSummary session(String sessionId, String source, long lastModified) {
        return new CliSessionSummary(sessionId, source, sessionId, source, 3, lastModified);
    }

    /** Await the async worker: the status completes with {@code running=false}. */
    private CliTranscriptSyncService.SyncStatus awaitSyncComplete() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            CliTranscriptSyncService.SyncStatus status = syncService.getStatus();
            if (status.getCompletedAt() != null && !status.isRunning()) {
                return status;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while awaiting sync completion");
            }
        }
        return fail("sync did not complete within 10s; status: " + syncService.getStatus());
    }

    @Nested
    @DisplayName("Scope gating")
    class ScopeGating {

        @Test
        @DisplayName("trigger without a registered code project is a no-op")
        void triggerWithoutRegisteredScopeIsNoOp() {
            assertFalse(syncService.triggerSync());
            verifyNoInteractions(cliTranscriptService);
        }

        @Test
        @DisplayName("scheduled sync without a registered code project is a no-op")
        void scheduledSyncWithoutRegisteredScopeIsNoOp() {
            syncService.scheduledSync();
            verifyNoInteractions(cliTranscriptService);
        }

        @Test
        @DisplayName("registering a code project triggers a sync of every adapter, scoped to it")
        void registerCodeProjectTriggersScopedSyncOfAllAdapters() {
            when(cliTranscriptService.listNewSessions(anyString(), any(Path.class)))
                    .thenReturn(List.of());

            assertTrue(syncService.registerCodeProject(projectDir));

            verify(cliTranscriptService, timeout(10_000))
                    .listNewSessions(eq("claude-code"), any(Path.class));
            verify(cliTranscriptService, timeout(10_000))
                    .listNewSessions(eq("opencode"), any(Path.class));
            CliTranscriptSyncService.SyncStatus status = awaitSyncComplete();
            assertEquals(2, status.getTotalSources());
            assertEquals(0, status.getTotalImported());
            assertNotNull(status.getStartedAt());
        }

        @Test
        @DisplayName("registering null does not trigger anything")
        void registeringNullDoesNotTrigger() {
            assertFalse(syncService.registerCodeProject(null));
            verifyNoInteractions(cliTranscriptService);
        }
    }

    @Nested
    @DisplayName("Session import")
    class SessionImport {

        @Test
        @DisplayName("discovered sessions import individually with their original timestamps")
        void discoveredSessionsAreImported() {
            when(cliTranscriptService.listNewSessions(eq("claude-code"), any(Path.class)))
                    .thenReturn(List.of(session("s1", "claude-code", 1111L),
                            session("s2", "claude-code", 0L)));
            when(cliTranscriptService.listNewSessions(eq("opencode"), any(Path.class)))
                    .thenReturn(List.of());

            syncService.registerCodeProject(projectDir);
            CliTranscriptSyncService.SyncStatus status = awaitSyncComplete();

            verify(cliTranscriptService).importTranscript("s1", "claude-code", 1111L);
            // A missing lastModified falls back to the current time, never zero.
            verify(cliTranscriptService).importTranscript(eq("s2"), eq("claude-code"), anyLong());
            assertEquals(2, status.getTotalImported());
            assertEquals(0, status.getTotalFailed());
            assertEquals(2, status.getTotalPending());
        }

        @Test
        @DisplayName("empty-session imports ('No messages found') are skips, not failures")
        void emptySessionSkipIsNotAFailure() {
            when(cliTranscriptService.listNewSessions(eq("claude-code"), any(Path.class)))
                    .thenReturn(List.of(session("empty", "claude-code", 1L)));
            when(cliTranscriptService.listNewSessions(eq("opencode"), any(Path.class)))
                    .thenReturn(List.of());
            when(cliTranscriptService.importTranscript(anyString(), anyString(), anyLong()))
                    .thenThrow(new RuntimeException("No messages found for session"));

            syncService.registerCodeProject(projectDir);
            CliTranscriptSyncService.SyncStatus status = awaitSyncComplete();

            assertEquals(0, status.getTotalImported());
            assertEquals(0, status.getTotalFailed(), "placeholder sessions do not count as failures");
        }

        @Test
        @DisplayName("a genuine import failure is counted and does not stop the sync")
        void realImportFailureCountsAndSyncContinues() {
            when(cliTranscriptService.listNewSessions(eq("claude-code"), any(Path.class)))
                    .thenReturn(List.of(session("bad", "claude-code", 1L),
                            session("good", "claude-code", 2L)));
            when(cliTranscriptService.listNewSessions(eq("opencode"), any(Path.class)))
                    .thenReturn(List.of());
            when(cliTranscriptService.importTranscript(eq("bad"), anyString(), anyLong()))
                    .thenThrow(new RuntimeException("corrupt transcript"));

            syncService.registerCodeProject(projectDir);
            CliTranscriptSyncService.SyncStatus status = awaitSyncComplete();

            verify(cliTranscriptService).importTranscript(eq("good"), eq("claude-code"), anyLong());
            assertEquals(1, status.getTotalImported());
            assertEquals(1, status.getTotalFailed());
        }
    }

    @Nested
    @DisplayName("Error isolation")
    class ErrorIsolation {

        @Test
        @DisplayName("one adapter failing does not stop the others")
        void adapterFailureDoesNotStopOtherAdapters() {
            when(cliTranscriptService.listNewSessions(eq("claude-code"), any(Path.class)))
                    .thenThrow(new RuntimeException("disk error"));
            when(cliTranscriptService.listNewSessions(eq("opencode"), any(Path.class)))
                    .thenReturn(List.of(session("s1", "opencode", 5L)));

            syncService.registerCodeProject(projectDir);
            CliTranscriptSyncService.SyncStatus status = awaitSyncComplete();

            verify(cliTranscriptService).importTranscript(eq("s1"), eq("opencode"), anyLong());
            assertEquals(1, status.getTotalImported());
            assertTrue(status.getErrors().stream().anyMatch(error -> error.contains("claude-code")),
                    "the failing adapter is recorded in the status errors: " + status.getErrors());
        }

        @Test
        @DisplayName("all adapters failing completes without throwing")
        void allAdaptersFailingCompletesCleanly() {
            when(cliTranscriptService.listNewSessions(anyString(), any(Path.class)))
                    .thenThrow(new RuntimeException("error"));

            syncService.registerCodeProject(projectDir);
            CliTranscriptSyncService.SyncStatus status = awaitSyncComplete();

            assertEquals(0, status.getTotalImported());
            assertEquals(2, status.getErrors().size(), "each adapter records its failure");
        }
    }
}
