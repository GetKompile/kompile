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

import ai.kompile.chat.history.config.ChatHistoryProperties;
import ai.kompile.chat.history.domain.ChatSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CliTranscriptSyncService Tests")
class CliTranscriptSyncServiceTest {

    @Mock
    private CliTranscriptService cliTranscriptService;

    @Mock
    private ChatHistoryProperties properties;

    @InjectMocks
    private CliTranscriptSyncService syncService;

    @BeforeEach
    void setUp() {
        when(properties.getCliSyncBatchSize()).thenReturn(50);
    }

    @Nested
    @DisplayName("Initial Sync (PostConstruct)")
    class InitialSync {

        @Test
        @DisplayName("should sync all five sources on startup")
        void shouldSyncAllSourcesOnStartup() {
            when(cliTranscriptService.syncSource(anyString(), eq(50))).thenReturn(0);

            syncService.initialSync();

            verify(cliTranscriptService).syncSource(CliTranscriptService.SOURCE_KOMPILE, 50);
            verify(cliTranscriptService).syncSource(CliTranscriptService.SOURCE_CLAUDE_CODE, 50);
            verify(cliTranscriptService).syncSource(CliTranscriptService.SOURCE_OPENCODE, 50);
            verify(cliTranscriptService).syncSource(CliTranscriptService.SOURCE_CODEX, 50);
            verify(cliTranscriptService).syncSource(CliTranscriptService.SOURCE_QWEN, 50);
        }

        @Test
        @DisplayName("should import sessions from available sources")
        void shouldImportFromAvailableSources() {
            when(cliTranscriptService.syncSource(CliTranscriptService.SOURCE_KOMPILE, 50)).thenReturn(3);
            when(cliTranscriptService.syncSource(CliTranscriptService.SOURCE_CLAUDE_CODE, 50)).thenReturn(5);
            when(cliTranscriptService.syncSource(CliTranscriptService.SOURCE_OPENCODE, 50)).thenReturn(0);
            when(cliTranscriptService.syncSource(CliTranscriptService.SOURCE_CODEX, 50)).thenReturn(0);
            when(cliTranscriptService.syncSource(CliTranscriptService.SOURCE_QWEN, 50)).thenReturn(0);

            syncService.initialSync();

            verify(cliTranscriptService, times(5)).syncSource(anyString(), eq(50));
        }
    }

    @Nested
    @DisplayName("Error Isolation")
    class ErrorIsolation {

        @Test
        @DisplayName("should continue syncing other sources when one fails")
        void shouldContinueWhenOneFails() {
            when(cliTranscriptService.syncSource(CliTranscriptService.SOURCE_KOMPILE, 50))
                    .thenThrow(new RuntimeException("disk error"));
            when(cliTranscriptService.syncSource(CliTranscriptService.SOURCE_CLAUDE_CODE, 50)).thenReturn(2);
            when(cliTranscriptService.syncSource(CliTranscriptService.SOURCE_OPENCODE, 50)).thenReturn(0);
            when(cliTranscriptService.syncSource(CliTranscriptService.SOURCE_CODEX, 50)).thenReturn(0);
            when(cliTranscriptService.syncSource(CliTranscriptService.SOURCE_QWEN, 50)).thenReturn(1);

            syncService.scheduledSync();

            // All sources still attempted despite kompile failing
            verify(cliTranscriptService).syncSource(CliTranscriptService.SOURCE_KOMPILE, 50);
            verify(cliTranscriptService).syncSource(CliTranscriptService.SOURCE_CLAUDE_CODE, 50);
            verify(cliTranscriptService).syncSource(CliTranscriptService.SOURCE_OPENCODE, 50);
            verify(cliTranscriptService).syncSource(CliTranscriptService.SOURCE_CODEX, 50);
            verify(cliTranscriptService).syncSource(CliTranscriptService.SOURCE_QWEN, 50);
        }

        @Test
        @DisplayName("should handle all sources failing without throwing")
        void shouldHandleAllSourcesFailing() {
            when(cliTranscriptService.syncSource(anyString(), eq(50)))
                    .thenThrow(new RuntimeException("error"));

            // Should not throw
            syncService.scheduledSync();

            verify(cliTranscriptService, times(5)).syncSource(anyString(), eq(50));
        }
    }

    @Nested
    @DisplayName("Batch Size Configuration")
    class BatchSizeConfig {

        @Test
        @DisplayName("should use configured batch size")
        void shouldUseConfiguredBatchSize() {
            when(properties.getCliSyncBatchSize()).thenReturn(10);
            when(cliTranscriptService.syncSource(anyString(), eq(10))).thenReturn(0);

            syncService.scheduledSync();

            verify(cliTranscriptService, times(5)).syncSource(anyString(), eq(10));
        }
    }

    @Nested
    @DisplayName("Scheduled Sync")
    class ScheduledSync {

        @Test
        @DisplayName("should run same sync logic as initial")
        void shouldRunSameSyncLogic() {
            when(cliTranscriptService.syncSource(anyString(), eq(50))).thenReturn(0);

            syncService.scheduledSync();

            verify(cliTranscriptService, times(5)).syncSource(anyString(), eq(50));
        }

        @Test
        @DisplayName("should be idempotent with no new sessions")
        void shouldBeIdempotentWithNoNewSessions() {
            when(cliTranscriptService.syncSource(anyString(), eq(50))).thenReturn(0);

            syncService.scheduledSync();
            syncService.scheduledSync();

            verify(cliTranscriptService, times(10)).syncSource(anyString(), eq(50));
        }
    }
}
