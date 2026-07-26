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

import ai.kompile.app.services.ProcessGraphWritebackService;
import ai.kompile.app.services.ProcessWritebackDeadLetterStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST inspection and manual-retry surface for the process writeback dead-letter store.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET  /api/process/writeback/dead-letters}
 *       — list current entries (capped at {@value #MAX_LIST_ENTRIES}); returns
 *       {@code {count, enabled, entries:[{id,callbackType,runId,stepId,ts,failureSummary,attemptNumber}]}}.
 *   </li>
 *   <li>{@code POST /api/process/writeback/dead-letters/retry}
 *       — replay all entries now; returns {@code {total,succeeded,failed}}.
 *   </li>
 *   <li>{@code DELETE /api/process/writeback/dead-letters/{id}}
 *       — discard one entry by its UUID; returns 200 (removed) or 404 (not found).
 *   </li>
 * </ul>
 *
 * <p>All endpoints are no-ops when the dead-letter store is disabled (no dataDir),
 * returning an appropriate response with {@code enabled:false}.</p>
 */
@RestController
@RequestMapping("/api/process/writeback")
@CrossOrigin(origins = "*")
@ConditionalOnBean(ProcessGraphWritebackService.class)
public class ProcessWritebackController {

    private static final Logger log = LoggerFactory.getLogger(ProcessWritebackController.class);

    /** Maximum number of entry summaries returned in a single GET response. */
    static final int MAX_LIST_ENTRIES = 200;

    private final ProcessGraphWritebackService writebackService;

    @Autowired
    public ProcessWritebackController(ProcessGraphWritebackService writebackService) {
        this.writebackService = writebackService;
    }

    /**
     * List dead-letter entries.
     *
     * <p>Returns a summary view — not the full payloads — to keep response sizes reasonable.
     * Full payloads live on disk and are only needed during replay.</p>
     *
     * @return {@code {count, enabled, entries:[...]}}
     */
    @GetMapping("/dead-letters")
    public ResponseEntity<Map<String, Object>> listDeadLetters() {
        try {
            ProcessWritebackDeadLetterStore store = writebackService.deadLetterStore();
            boolean enabled = store.isEnabled();
            List<ProcessWritebackDeadLetterStore.DeadLetterEntry> all = store.readAll();
            int total = all.size();

            List<Map<String, Object>> summaries = new ArrayList<>();
            for (int i = 0; i < Math.min(total, MAX_LIST_ENTRIES); i++) {
                ProcessWritebackDeadLetterStore.DeadLetterEntry e = all.get(i);
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", e.id);
                s.put("callbackType", e.callbackType);
                s.put("runId", e.runId);
                if (e.stepId != null) s.put("stepId", e.stepId);
                s.put("processDefinitionId", e.processDefinitionId);
                s.put("ts", e.ts);
                s.put("attemptNumber", e.attemptNumber);
                if (e.failureSummary != null) s.put("failureSummary", e.failureSummary);
                summaries.add(s);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("enabled", enabled);
            resp.put("count", total);
            if (total > MAX_LIST_ENTRIES) {
                resp.put("capped", true);
                resp.put("shown", MAX_LIST_ENTRIES);
            }
            resp.put("entries", summaries);
            return ResponseEntity.ok(resp);
        } catch (Exception ex) {
            log.warn("ProcessWritebackController GET /dead-letters failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Replay all dead-letter entries now.
     *
     * @return {@code {total, succeeded, failed}}
     */
    @PostMapping("/dead-letters/retry")
    public ResponseEntity<Map<String, Object>> retryAll() {
        try {
            ProcessWritebackDeadLetterStore store = writebackService.deadLetterStore();
            if (!store.isEnabled()) {
                return ResponseEntity.ok(Map.of(
                        "enabled", false,
                        "message", "Dead-letter store is disabled (no kompile.data.dir configured)",
                        "total", 0, "succeeded", 0, "failed", 0));
            }
            Map<String, Object> result = writebackService.replayDeadLetters();
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            log.warn("ProcessWritebackController POST /dead-letters/retry failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Discard a single dead-letter entry by its UUID.
     *
     * @param id the entry UUID
     * @return 200 with {@code {removed:true}} or 404 with {@code {removed:false}}
     */
    @DeleteMapping("/dead-letters/{id}")
    public ResponseEntity<Map<String, Object>> deleteEntry(@PathVariable("id") String id) {
        try {
            ProcessWritebackDeadLetterStore store = writebackService.deadLetterStore();
            boolean removed = store.removeById(id);
            if (removed) {
                log.info("ProcessWritebackController: deleted dead-letter entry id={}", id);
                return ResponseEntity.ok(Map.of("removed", true, "id", id));
            } else {
                return ResponseEntity.status(404)
                        .body(Map.of("removed", false, "id", id,
                                "message", "Entry not found"));
            }
        } catch (Exception ex) {
            log.warn("ProcessWritebackController DELETE /dead-letters/{} failed: {}", id, ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", ex.getMessage()));
        }
    }
}
