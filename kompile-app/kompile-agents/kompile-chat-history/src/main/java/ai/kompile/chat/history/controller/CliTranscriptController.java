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
import ai.kompile.chat.history.service.CliTranscriptRetentionService;
import ai.kompile.chat.history.service.CliTranscriptService;
import ai.kompile.chat.history.service.CliTranscriptService.*;
import ai.kompile.chat.history.service.CliTranscriptSyncService;
import ai.kompile.cli.common.chat.aggregate.AggregateChatSourceService;
import ai.kompile.cli.common.chat.aggregate.ChatTranscriptRetention;
import ai.kompile.cli.common.chat.aggregate.ChatTranscriptSearch;
import ai.kompile.cli.common.chat.sources.ChatSourceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for CLI transcript operations.
 * Enables the web UI to browse, import, and export CLI chat transcripts.
 */
@Slf4j
@RestController
@RequestMapping("/api/chat-history/cli")
@ConditionalOnBean(CliTranscriptService.class)
public class CliTranscriptController {

    private final CliTranscriptService cliTranscriptService;
    private final AggregateChatSourceService aggregateService;
    private final ChatTranscriptSearch searchService;
    private final CliTranscriptRetentionService retentionService;
    private final CliTranscriptSyncService syncService;

    @Autowired
    public CliTranscriptController(CliTranscriptService cliTranscriptService,
                                   CliTranscriptRetentionService retentionService,
                                   CliTranscriptSyncService syncService) {
        this.cliTranscriptService = cliTranscriptService;
        this.retentionService = retentionService;
        this.syncService = syncService;
        ChatSourceRegistry registry = ChatSourceRegistry.getInstance();
        this.aggregateService = new AggregateChatSourceService(registry);
        this.searchService = new ChatTranscriptSearch(registry);
    }

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

    /**
     * Get the current sync status (running/idle, progress, errors).
     */
    @GetMapping("/sync/status")
    public ResponseEntity<CliTranscriptSyncService.SyncStatus> getSyncStatus() {
        return ResponseEntity.ok(syncService.getStatus());
    }

    /**
     * Manually trigger a full sync of all chat sources.
     */
    @PostMapping("/sync/trigger")
    public ResponseEntity<Map<String, Object>> triggerSync() {
        boolean started = syncService.triggerSync();
        return ResponseEntity.ok(Map.of(
                "triggered", started,
                "message", started ? "Sync started" : "Sync already in progress"
        ));
    }

    /**
     * Cross-source grep-style search over transcripts. Accepts repeated
     * {@code source=} params or a comma-separated list.
     */
    @GetMapping("/search")
    public ResponseEntity<List<ChatTranscriptSearch.Hit>> search(
            @RequestParam("q") String pattern,
            @RequestParam(name = "source", required = false) List<String> sources,
            @RequestParam(name = "ctx", defaultValue = "0") int contextLines,
            @RequestParam(name = "limit", defaultValue = "500") int limit,
            @RequestParam(name = "regex", defaultValue = "false") boolean regex,
            @RequestParam(name = "ignoreCase", defaultValue = "false") boolean ignoreCase) {
        log.debug("Search: q='{}' sources={} ctx={} limit={} regex={} ignoreCase={}",
                pattern, sources, contextLines, limit, regex, ignoreCase);
        List<String> expandedSources = expandSources(sources);
        ChatTranscriptSearch.Query query = new ChatTranscriptSearch.Query()
                .pattern(pattern)
                .sources(expandedSources)
                .contextLines(contextLines)
                .limit(limit)
                .regex(regex)
                .caseInsensitive(ignoreCase);
        return ResponseEntity.ok(searchService.search(query));
    }

    /**
     * Aggregate counts + discovery info for every known chat source.
     */
    @GetMapping("/aggregate")
    public ResponseEntity<Map<String, Object>> aggregate() {
        log.debug("Aggregate: collecting per-source counts + discovery");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sources", aggregateService.discoveryBySource());
        out.put("counts", aggregateService.countsBySource());
        out.put("knownSourceIds", aggregateService.knownSourceIds());
        return ResponseEntity.ok(out);
    }

    /**
     * Trigger a retention pass over Kompile transcript files. Caps come from
     * {@link ChatHistoryProperties} unless overridden via query params.
     */
    @DeleteMapping("/retention")
    public ResponseEntity<Map<String, Object>> runRetention(
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun,
            @RequestParam(name = "maxAgeDays", required = false) Long maxAgeDays,
            @RequestParam(name = "maxTotalMb", required = false) Long maxTotalMb,
            @RequestParam(name = "maxPerSource", required = false) Integer maxPerSource) {
        log.info("Retention: dryRun={} maxAgeDays={} maxTotalMb={} maxPerSource={}",
                dryRun, maxAgeDays, maxTotalMb, maxPerSource);
        ChatTranscriptRetention.Result result;
        if (maxAgeDays != null || maxTotalMb != null || maxPerSource != null) {
            result = retentionService.runCleanup(
                    maxAgeDays != null ? maxAgeDays : 0L,
                    maxTotalMb != null ? maxTotalMb : 0L,
                    maxPerSource != null ? maxPerSource : 0,
                    dryRun);
        } else {
            result = retentionService.runCleanup(dryRun);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dryRun", dryRun);
        body.put("totalDeleted", result.totalDeleted());
        body.put("deletedByAge", result.deletedByAge());
        body.put("deletedByCount", result.deletedByCount());
        body.put("deletedBySize", result.deletedBySize());
        List<String> paths = new ArrayList<>();
        for (File f : result.deletedFiles()) paths.add(f.getAbsolutePath());
        body.put("deletedFiles", paths);
        return ResponseEntity.ok(body);
    }

    private static List<String> expandSources(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            if (s == null) continue;
            for (String part : s.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) out.add(trimmed);
            }
        }
        return out;
    }
}
