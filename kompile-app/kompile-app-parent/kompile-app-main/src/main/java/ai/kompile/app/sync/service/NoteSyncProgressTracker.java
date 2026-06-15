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
import ai.kompile.app.sync.dto.SyncRunResult;
import ai.kompile.app.sync.dto.SyncStatusUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Sends sync progress updates over WebSocket (STOMP) to /topic/sync/{connectionId}.
 */
@Service
public class NoteSyncProgressTracker {

    private static final Logger log = LoggerFactory.getLogger(NoteSyncProgressTracker.class);

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    public void start(String sessionId, NoteSyncConnection conn) {
        send(conn.getId(), SyncStatusUpdate.builder()
                .sessionId(sessionId)
                .connectionId(conn.getId())
                .status("RUNNING")
                .message("Sync started for " + conn.getProvider() + " -> " + conn.getExternalScope())
                .build());
    }

    public void progress(String sessionId, Long connectionId, String message) {
        send(connectionId, SyncStatusUpdate.builder()
                .sessionId(sessionId)
                .connectionId(connectionId)
                .status("RUNNING")
                .message(message)
                .build());
    }

    public void complete(String sessionId, SyncRunResult result) {
        send(result.getConnectionId(), SyncStatusUpdate.builder()
                .sessionId(sessionId)
                .connectionId(result.getConnectionId())
                .status("COMPLETED")
                .message("Sync completed")
                .pushed(result.getPushed())
                .pulled(result.getPulled())
                .conflicts(result.getConflicts())
                .skipped(result.getSkipped())
                .build());
    }

    public void error(String sessionId, Long connectionId, String errorMessage) {
        send(connectionId, SyncStatusUpdate.builder()
                .sessionId(sessionId)
                .connectionId(connectionId)
                .status("ERROR")
                .message(errorMessage)
                .build());
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
