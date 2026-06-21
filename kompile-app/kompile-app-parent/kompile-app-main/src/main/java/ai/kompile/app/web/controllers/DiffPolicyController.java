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

import ai.kompile.app.services.diffpolicy.DiffPolicyService;
import ai.kompile.app.services.diffpolicy.DiffPolicyViolation;
import ai.kompile.app.services.diffpolicy.PathRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for the diff-policy / agent-change governance view: rules,
 * scanning captured diffs, and querying violations by file/agent/session/time/severity.
 */
@RestController
@RequestMapping("/api/diff-policy")
public class DiffPolicyController {

    private final DiffPolicyService policyService;

    public DiffPolicyController(@Autowired(required = false) DiffPolicyService policyService) {
        this.policyService = policyService;
    }

    /** Current policy: path-of-concern globs + the content rule text. */
    @GetMapping("/rules")
    public ResponseEntity<Map<String, Object>> getRules() {
        if (policyService == null) return ResponseEntity.ok(Map.of("pathRules", List.of(), "contentRulesText", ""));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pathRules", policyService.getPathRules());
        body.put("contentRulesText", policyService.getContentRulesText());
        body.put("llmAvailable", policyService.isLlmAvailable());
        return ResponseEntity.ok(body);
    }

    @PutMapping("/rules")
    public ResponseEntity<Map<String, Object>> saveRules(@RequestBody RulesRequest request) {
        if (policyService == null) return ResponseEntity.status(503).body(Map.of("status", "unavailable"));
        policyService.saveRules(request.pathRules(), request.contentRulesText());
        return ResponseEntity.ok(Map.of("status", "saved", "pathRules", policyService.getPathRules().size()));
    }

    /** Evaluate the (optionally filtered) captured diffs against policy; returns a summary. */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan(
            @RequestParam(required = false) String agent,
            @RequestParam(required = false) String filePath,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "false") boolean useLlm) {
        if (policyService == null) return ResponseEntity.status(503).body(Map.of("status", "unavailable"));
        return ResponseEntity.ok(policyService.scan(agent, filePath, sessionId, since, until, limit, useLlm));
    }

    /** Query recorded violations. {@code filePath} accepts a glob (e.g. {@code **}{@code /*.env}). */
    @GetMapping("/violations")
    public ResponseEntity<List<DiffPolicyViolation>> violations(
            @RequestParam(required = false) String filePath,
            @RequestParam(required = false) String agent,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String detector,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String since,
            @RequestParam(required = false) String until,
            @RequestParam(required = false) Integer limit) {
        if (policyService == null) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(policyService.listViolations(
                filePath, agent, sessionId, detector, severity, since, until, limit));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        if (policyService == null) return ResponseEntity.ok(Map.of("total", 0));
        return ResponseEntity.ok(policyService.getStats());
    }

    @DeleteMapping("/violations")
    public ResponseEntity<Map<String, Object>> clear() {
        if (policyService == null) return ResponseEntity.status(503).body(Map.of("status", "unavailable"));
        policyService.clearViolations();
        return ResponseEntity.ok(Map.of("status", "cleared"));
    }

    public record RulesRequest(List<PathRule> pathRules, String contentRulesText) {
    }
}
