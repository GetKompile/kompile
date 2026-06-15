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
package ai.kompile.orchestrator.web.controllers;

import ai.kompile.cli.common.logs.AgentLogMetadata;
import ai.kompile.cli.common.logs.AgentLogReader;
import ai.kompile.cli.common.logs.AgentLogRecord;
import ai.kompile.cli.common.logs.LogPaths;
import ai.kompile.cli.common.logs.LogRetentionManager;
import ai.kompile.cli.common.logs.LogRetentionPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * REST endpoints for reading aggregated agent logs written by
 * {@link ai.kompile.orchestrator.integration.cli.CliAgentExecutor}.
 *
 * <p>All endpoints operate on the filesystem store under
 * {@code ~/.kompile/logs/agents}. Writers stream JSON-lines while an agent runs;
 * readers tolerate partially-written files.
 */
@Slf4j
@RestController
@RequestMapping("/api/agent-logs")
public class AgentLogController {

    @Value("${kompile.logs.retention.max-age-days:30}")
    private long retentionMaxAgeDays;

    @Value("${kompile.logs.retention.max-total-size-mb:2048}")
    private long retentionMaxTotalMb;

    @Value("${kompile.logs.retention.max-files-per-agent:100}")
    private int retentionMaxFilesPerAgent;

    /** List all known runs. Most recent first. */
    @GetMapping
    public ResponseEntity<List<AgentLogMetadata>> list(
            @RequestParam(value = "instance", required = false) String instance,
            @RequestParam(value = "agent", required = false) String agent,
            @RequestParam(value = "sessionId", required = false) Long sessionId,
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "until", required = false) String until,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        AgentLogReader.RunFilter filter = new AgentLogReader.RunFilter(
                instance, agent, sessionId, parseInstant(since), parseInstant(until));
        List<AgentLogMetadata> runs = AgentLogReader.listRuns(filter);
        if (runs.size() > limit) {
            runs = runs.subList(0, limit);
        }
        return ResponseEntity.ok(runs);
    }

    /** Metadata for a specific run. */
    @GetMapping("/{processId}")
    public ResponseEntity<AgentLogMetadata> get(@PathVariable String processId) {
        return AgentLogReader.findByProcessId(processId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** All records for a run, optionally from a given sequence onward (for tail). */
    @GetMapping("/{processId}/records")
    public ResponseEntity<List<AgentLogRecord>> records(
            @PathVariable String processId,
            @RequestParam(value = "fromSeq", required = false) Integer fromSeq,
            @RequestParam(value = "limit", defaultValue = "5000") int limit) {
        return AgentLogReader.findByProcessId(processId).map(meta -> {
            File logFile = LogPaths.agentLogFile(
                    meta.getOrchestratorInstanceId(), meta.getAgentName(), meta.getProcessId());
            if (!logFile.isFile()) {
                return ResponseEntity.ok(List.<AgentLogRecord>of());
            }
            List<AgentLogRecord> out = new ArrayList<>(Math.min(limit, 1024));
            try (Stream<AgentLogRecord> stream = AgentLogReader.readRecords(logFile)) {
                stream.forEach(r -> {
                    if (fromSeq != null && r.getSeq() != null && r.getSeq() <= fromSeq) {
                        return;
                    }
                    if (out.size() < limit) {
                        out.add(r);
                    }
                });
            } catch (Exception e) {
                log.warn("Failed to read records for {}: {}", processId, e.getMessage());
            }
            return ResponseEntity.ok(out);
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Aggregated records across runs matching the filter, in timestamp order. */
    @GetMapping("/aggregate")
    public ResponseEntity<List<AgentLogRecord>> aggregate(
            @RequestParam(value = "instance", required = false) String instance,
            @RequestParam(value = "agent", required = false) String agent,
            @RequestParam(value = "sessionId", required = false) Long sessionId,
            @RequestParam(value = "since", required = false) String since,
            @RequestParam(value = "until", required = false) String until,
            @RequestParam(value = "limit", defaultValue = "10000") int limit) {
        AgentLogReader.RunFilter filter = new AgentLogReader.RunFilter(
                instance, agent, sessionId, parseInstant(since), parseInstant(until));
        List<AgentLogMetadata> runs = AgentLogReader.listRuns(filter);
        List<AgentLogRecord> records = new ArrayList<>();
        try (Stream<AgentLogRecord> stream = AgentLogReader.aggregateAcrossRuns(runs)) {
            stream.limit(limit).forEach(records::add);
        }
        return ResponseEntity.ok(records);
    }

    /** Runs retention now and reports how many run-pairs were deleted. */
    @PostMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> cleanup() {
        LogRetentionPolicy policy = LogRetentionPolicy.of(
                retentionMaxAgeDays, retentionMaxTotalMb, retentionMaxFilesPerAgent);
        LogRetentionManager.RetentionResult result = new LogRetentionManager(policy).applyToAgents();
        return ResponseEntity.ok(Map.of(
                "deletedByAge", result.deletedByAge(),
                "deletedByPerAgentCap", result.deletedByPerAgent(),
                "deletedBySize", result.deletedBySize(),
                "totalDeleted", result.totalDeleted(),
                "policy", Map.of(
                        "maxAgeDays", retentionMaxAgeDays,
                        "maxTotalMb", retentionMaxTotalMb,
                        "maxFilesPerAgent", retentionMaxFilesPerAgent)
        ));
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
