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

package ai.kompile.app.ingest.service;

import ai.kompile.app.ingest.domain.JobLogEntry;
import ai.kompile.app.web.dto.IngestProgressUpdate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JobLogLlmTranscriptLoggerTest {

    @Test
    void persistsTranscriptAndStreamsToTranscriptTopicWithSharedSequence() {
        JobLogService jobLogService = mock(JobLogService.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        when(jobLogService.isEnabled()).thenReturn(true);
        JobLogEntry persisted = JobLogEntry.builder()
                .taskId("crawl-job1")
                .level(JobLogEntry.LogLevel.INFO)
                .source(JobLogEntry.LogSource.LLM_TRANSCRIPT)
                .message("transcript")
                .sequenceNumber(42L)
                .timestamp(Instant.now())
                .build();
        when(jobLogService.logEntry(anyString(), any(), any(), anyString(), any(), any())).thenReturn(persisted);

        JobLogLlmTranscriptLogger logger = new JobLogLlmTranscriptLogger(jobLogService, messaging, 65536);
        logger.logTranscript("job1", "default", "GRAPH_EXTRACTION", "the prompt", "the response",
                123L, true, null, null);

        // Persisted under the crawl- taskId with the LLM_TRANSCRIPT source.
        verify(jobLogService).logEntry(eq("crawl-job1"), eq(JobLogEntry.LogLevel.INFO),
                eq(JobLogEntry.LogSource.LLM_TRANSCRIPT), anyString(), eq("default"), anyString());

        // Streamed to the dedicated transcript topic carrying the persisted entry's sequence number, so a
        // live tail merges with the HTTP history without the viewer's dedup dropping it.
        ArgumentCaptor<IngestProgressUpdate.IngestLogEntry> captor =
                ArgumentCaptor.forClass(IngestProgressUpdate.IngestLogEntry.class);
        verify(messaging).convertAndSend(eq("/topic/ingest/crawl-job1/transcripts"), captor.capture());
        assertEquals(42L, captor.getValue().sequenceNumber());
        assertEquals("LLM_TRANSCRIPT", captor.getValue().source());
        assertEquals("crawl-job1", captor.getValue().taskId());
    }

    @Test
    void noOpWhenLoggingDisabled() {
        JobLogService jobLogService = mock(JobLogService.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        when(jobLogService.isEnabled()).thenReturn(false);

        JobLogLlmTranscriptLogger logger = new JobLogLlmTranscriptLogger(jobLogService, messaging, 65536);
        logger.logTranscript("job1", "default", "X", "p", "r", 1L, true, null, null);

        verify(jobLogService, never()).logEntry(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(messaging);
    }

    @Test
    void worksWithoutMessagingTemplate() {
        JobLogService jobLogService = mock(JobLogService.class);
        when(jobLogService.isEnabled()).thenReturn(true);
        when(jobLogService.logEntry(anyString(), any(), any(), anyString(), any(), any())).thenReturn(null);

        JobLogLlmTranscriptLogger logger = new JobLogLlmTranscriptLogger(jobLogService, null, 65536);
        // Must not throw when no SimpMessagingTemplate is wired (WS streaming simply doesn't happen).
        logger.logTranscript("job1", "default", "X", "p", "r", 1L, true, null, null);

        verify(jobLogService).logEntry(anyString(), any(), any(), anyString(), any(), any());
    }
}
