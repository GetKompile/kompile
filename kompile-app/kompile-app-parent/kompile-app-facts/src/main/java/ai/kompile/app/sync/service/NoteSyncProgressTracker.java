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

package ai.kompile.app.sync.service;

import ai.kompile.app.sync.domain.NoteSyncConnection;
import ai.kompile.app.sync.domain.NoteSyncRun;
import ai.kompile.app.sync.dto.SyncRunResult;
import ai.kompile.app.sync.dto.SyncStatusUpdate;
import ai.kompile.app.sync.repository.NoteSyncRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Sends sync progress updates over WebSocket (STOMP) to /topic/sync/{connectionId}.
 */
@Service
public class NoteSyncProgressTracker {

    private static final Logger log = LoggerFactory.getLogger(NoteSyncProgressTracker.class);

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    @Autowired
    private NoteSyncRunRepository syncRunRepository;

    @Autowired
    private NoteSyncConnectionService connectionService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void start(String sessionId, NoteSyncConnection conn) {
        String message = "Sync started for " + conn.getProvider() + " -> " + conn.getExternalScope();
        assertLeaseIfDurable(sessionId, conn.getId());
        updateRun(sessionId, run -> {
            run.setStatus("RUNNING");
            run.setStage("SOURCE_SYNC");
            run.setMessage(message);
            if (run.getStartedAt() == null) {
                run.setStartedAt(Instant.now());
            }
        });
        send(conn.getId(), SyncStatusUpdate.builder()
                .sessionId(sessionId)
                .connectionId(conn.getId())
                .status("RUNNING")
                .message(message)
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void progress(String sessionId, Long connectionId, String message) {
        progress(sessionId, connectionId, "SOURCE_SYNC", message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void progress(String sessionId, Long connectionId, String stage, String message) {
        assertLeaseIfDurable(sessionId, connectionId);
        updateRun(sessionId, run -> {
            run.setStatus("RUNNING");
            run.setStage(stage);
            run.setMessage(message);
        });
        send(connectionId, SyncStatusUpdate.builder()
                .sessionId(sessionId)
                .connectionId(connectionId)
                .status("RUNNING")
                .message(message)
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String sessionId, SyncRunResult result) {
        complete(sessionId, result, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String sessionId, SyncRunResult result, Instant checkpointAfter) {
        assertLeaseIfDurable(sessionId, result.getConnectionId());
        boolean partial = result.getErrors() > 0;
        String status = partial ? "PARTIAL" : "COMPLETED";
        String message = partial
                ? "Sync completed with " + result.getErrors() + " error(s); checkpoint retained"
                : "Sync completed";
        updateRun(sessionId, run -> {
            run.setStatus(status);
            run.setStage("SOURCE_COMPLETE");
            run.setMessage(message);
            run.setErrorMessage(partial ? message : null);
            run.setPushed(result.getPushed());
            run.setPulled(result.getPulled());
            run.setDeleted(result.getDeleted());
            run.setConflicts(result.getConflicts());
            run.setSkipped(result.getSkipped());
            run.setErrors(result.getErrors());
            run.setCheckpointAfter(checkpointAfter);
            run.setFinishedAt(Instant.now());
        });
        send(result.getConnectionId(), SyncStatusUpdate.builder()
                .sessionId(sessionId)
                .connectionId(result.getConnectionId())
                .status(status)
                .message(message)
                .pushed(result.getPushed())
                .pulled(result.getPulled())
                .deleted(result.getDeleted())
                .conflicts(result.getConflicts())
                .skipped(result.getSkipped())
                .errors(result.getErrors())
                .build());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void error(String sessionId, Long connectionId, String errorMessage) {
        NoteSyncRun run = syncRunRepository.findById(sessionId).orElse(null);
        if (run == null) {
            log.debug("No durable sync run found for legacy session {}", sessionId);
        } else {
            if (isSourceTerminal(run.getStatus())) {
                return;
            }
            run.setStatus("ERROR");
            run.setStage("ERROR");
            run.setMessage(errorMessage);
            run.setErrorMessage(errorMessage);
            run.setErrors(Math.max(1, run.getErrors()));
            run.setFinishedAt(Instant.now());
            syncRunRepository.save(run);
        }
        send(connectionId, SyncStatusUpdate.builder()
                .sessionId(sessionId)
                .connectionId(connectionId)
                .status("ERROR")
                .message(errorMessage)
                .errors(1)
                .build());
    }

    private void assertLeaseIfDurable(String sessionId, Long connectionId) {
        if (syncRunRepository.existsById(sessionId)) {
            connectionService.renewRunLeaseOrThrow(connectionId, sessionId);
        }
    }

    private boolean isSourceTerminal(String status) {
        return "COMPLETED".equals(status)
                || "PARTIAL".equals(status)
                || "ERROR".equals(status)
                || "INTERRUPTED".equals(status);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void graphPending(String sessionId) {
        updateRun(sessionId, run -> {
            run.setGraphStatus("PENDING");
            run.setMessage("Graph maintenance pending");
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void graphQueued(String sessionId, String crawlJobId) {
        updateRun(sessionId, run -> {
            run.setGraphStatus("QUEUED");
            run.setCrawlJobId(crawlJobId);
            run.setMessage("Graph maintenance crawl queued");
        });
        syncRunRepository.findById(sessionId).ifPresent(run ->
                send(run.getConnectionId(), statusUpdate(run)));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void graphError(String sessionId, String errorMessage) {
        updateRun(sessionId, run -> {
            run.setGraphStatus("ERROR");
            run.setGraphError(errorMessage);
            run.setMessage("Graph maintenance failed");
        });
        syncRunRepository.findById(sessionId).ifPresent(run ->
                send(run.getConnectionId(), statusUpdate(run)));
    }

    private void updateRun(String sessionId, java.util.function.Consumer<NoteSyncRun> update) {
        syncRunRepository.findById(sessionId).ifPresentOrElse(run -> {
            update.accept(run);
            syncRunRepository.save(run);
        }, () -> log.debug("No durable sync run found for legacy session {}", sessionId));
    }

    private SyncStatusUpdate statusUpdate(NoteSyncRun run) {
        return SyncStatusUpdate.builder()
                .sessionId(run.getId())
                .connectionId(run.getConnectionId())
                .status(run.getStatus())
                .message(run.getMessage())
                .pushed(run.getPushed())
                .pulled(run.getPulled())
                .deleted(run.getDeleted())
                .conflicts(run.getConflicts())
                .skipped(run.getSkipped())
                .errors(run.getErrors())
                .crawlJobId(run.getCrawlJobId())
                .graphStatus(run.getGraphStatus())
                .build();
    }

    private void send(Long connectionId, SyncStatusUpdate update) {
        if (messagingTemplate != null) {
            try {
                messagingTemplate.convertAndSend("/topic/sync/" + connectionId, update);
                messagingTemplate.convertAndSend("/topic/sync/all", update);
            } catch (Exception e) {
                log.warn("Failed to send sync status update: {}", e.getMessage());
            }
        }
    }
}
