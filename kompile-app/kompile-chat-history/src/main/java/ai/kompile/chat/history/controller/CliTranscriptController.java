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

package ai.kompile.chat.history.controller;

import ai.kompile.chat.history.domain.ChatSession;
import ai.kompile.chat.history.dto.ChatSessionDto;
import ai.kompile.chat.history.service.CliTranscriptService;
import ai.kompile.chat.history.service.CliTranscriptService.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * REST controller for CLI transcript operations.
 * Enables the web UI to browse, import, and export CLI chat transcripts.
 */
@Slf4j
@RestController
@RequestMapping("/api/chat-history/cli")
@RequiredArgsConstructor
@ConditionalOnBean(CliTranscriptService.class)
public class CliTranscriptController {

    private final CliTranscriptService cliTranscriptService;

    /**
     * Discover available conversation sources with counts.
     */
    @GetMapping("/sources")
    public ResponseEntity<Map<String, SourceInfo>> discoverSources() {
        log.debug("Discovering CLI conversation sources");
        return ResponseEntity.ok(cliTranscriptService.discoverSources());
    }

    /**
     * List CLI sessions, optionally filtered by source.
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<CliSessionSummary>> listSessions(
            @RequestParam(name = "source", required = false) String source) {
        log.debug("Listing CLI sessions, source filter: {}", source);
        return ResponseEntity.ok(cliTranscriptService.listSessions(source));
    }

    /**
     * Read a specific CLI transcript with parsed turns.
     */
    @GetMapping("/sessions/{sessionId}")
    public ResponseEntity<CliTranscriptDetail> readTranscript(
            @PathVariable String sessionId,
            @RequestParam(name = "source", defaultValue = "kompile") String source) {
        log.debug("Reading CLI transcript: {} (source: {})", sessionId, source);
        try {
            CliTranscriptDetail detail = cliTranscriptService.readTranscript(sessionId, source);
            return ResponseEntity.ok(detail);
        } catch (IllegalArgumentException e) {
            log.warn("Transcript not found: {} - {}", sessionId, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Import a CLI transcript into the app database.
     */
    @PostMapping("/sessions/{sessionId}/import")
    public ResponseEntity<?> importTranscript(
            @PathVariable String sessionId,
            @RequestParam(name = "source", defaultValue = "kompile") String source) {
        log.info("Importing CLI transcript: {} (source: {})", sessionId, source);
        try {
            ChatSession imported = cliTranscriptService.importTranscript(sessionId, source);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ChatSessionDto.fromEntity(imported, false));
        } catch (IllegalStateException e) {
            log.warn("Duplicate import: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            log.warn("Import failed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Export an app session to CLI transcript format.
     */
    @PostMapping("/export/{sessionId}")
    public ResponseEntity<?> exportToTranscript(@PathVariable String sessionId) {
        log.info("Exporting app session to CLI transcript: {}", sessionId);
        try {
            Path path = cliTranscriptService.exportToTranscript(sessionId);
            return ResponseEntity.ok(Map.of(
                    "transcriptPath", path.toString(),
                    "sessionId", sessionId
            ));
        } catch (IllegalArgumentException e) {
            log.warn("Export failed: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Export error: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Export failed: " + e.getMessage()));
        }
    }
}
