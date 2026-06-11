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

package ai.kompile.app.web.controllers;

import ai.kompile.app.ingest.service.IndexingJobHistoryService;
import ai.kompile.app.ingest.service.IngestEventService;
import ai.kompile.app.subprocess.HttpIngestCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InternalIngestCallbackControllerTest {

    @Mock
    private IngestEventService eventService;

    @Mock
    private IndexingJobHistoryService jobHistoryService;

    private InternalIngestCallbackController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalIngestCallbackController(eventService, jobHistoryService);
    }

    // ─── logEvent ─────────────────────────────────────────────────────────────

    @Test
    void logEvent_noEventService_returnsOk() {
        InternalIngestCallbackController ctrl = new InternalIngestCallbackController(null, null);
        HttpIngestCallback.EventCallback callback = new HttpIngestCallback.EventCallback(
                "t1", "test.pdf", "QUEUED", null, null, null, null, null);

        ResponseEntity<Void> resp = ctrl.logEvent(callback);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void logEvent_queuedEvent_logsQueued() {
        HttpIngestCallback.EventCallback callback = new HttpIngestCallback.EventCallback(
                "t1", "test.pdf", "QUEUED", null, null, null, null, null);

        ResponseEntity<Void> resp = controller.logEvent(callback);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(eventService).logQueued("t1", "test.pdf");
    }

    @Test
    void logEvent_completedEvent_logsCompleted() {
        Map<String, Object> details = Map.of("totalItemsProcessed", 10, "summary", "Done");
        HttpIngestCallback.EventCallback callback = new HttpIngestCallback.EventCallback(
                "t1", "test.pdf", "COMPLETED", null, null, null, null, details);

        ResponseEntity<Void> resp = controller.logEvent(callback);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(eventService).logCompleted(eq("t1"), eq("test.pdf"), eq(10), eq("Done"));
    }

    @Test
    void logEvent_unknownEventType_returnsOkAndLogs() {
        HttpIngestCallback.EventCallback callback = new HttpIngestCallback.EventCallback(
                "t1", "test.pdf", "UNKNOWN_TYPE", null, null, null, null, null);

        ResponseEntity<Void> resp = controller.logEvent(callback);

        // Should still return OK even for unknown types (just logs warning)
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void logEvent_phaseStartedEvent_logsPhaseStarted() {
        HttpIngestCallback.EventCallback callback = new HttpIngestCallback.EventCallback(
                "t1", "test.pdf", "PHASE_STARTED", "LOADING", null, null, null, null);

        ResponseEntity<Void> resp = controller.logEvent(callback);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(eventService).logPhaseStarted(eq("t1"), eq("test.pdf"), any(), isNull());
    }

    @Test
    void logEvent_failedEvent_logsFailed() {
        HttpIngestCallback.EventCallback callback = new HttpIngestCallback.EventCallback(
                "t1", "test.pdf", "FAILED", "EMBEDDING", null, "OutOfMemory", null, null);

        ResponseEntity<Void> resp = controller.logEvent(callback);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(eventService).logFailed(eq("t1"), eq("test.pdf"), any(), eq("OutOfMemory"), isNull());
    }

    // ─── updateJobHistory ─────────────────────────────────────────────────────

    @Test
    void updateJobHistory_noService_returnsOk() {
        InternalIngestCallbackController ctrl = new InternalIngestCallbackController(null, null);
        HttpIngestCallback.JobHistoryCallback callback = new HttpIngestCallback.JobHistoryCallback(
                "t1", "RUNNING", null, null, null, null, null);

        ResponseEntity<Void> resp = ctrl.updateJobHistory(callback);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void updateJobHistory_statusRunning_marksJobRunning() {
        HttpIngestCallback.JobHistoryCallback callback = new HttpIngestCallback.JobHistoryCallback(
                "t1", "RUNNING", null, null, null, null, null);

        ResponseEntity<Void> resp = controller.updateJobHistory(callback);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(jobHistoryService).markJobRunning("t1");
    }

    @Test
    void updateJobHistory_statusCompleted_marksJobCompleted() {
        HttpIngestCallback.JobHistoryCallback callback = new HttpIngestCallback.JobHistoryCallback(
                "t1", "COMPLETED", null, null, null, null, null);

        ResponseEntity<Void> resp = controller.updateJobHistory(callback);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(jobHistoryService).markJobCompleted("t1");
    }

}
