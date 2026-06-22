/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.reasoning.controller;

import ai.kompile.knowledgegraph.reasoning.CommunitySummaryService;
import ai.kompile.knowledgegraph.reasoning.CommunitySummaryService.CommunitySummaryResult;
import ai.kompile.knowledgegraph.reasoning.GraphCommunityService;
import ai.kompile.knowledgegraph.reasoning.GraphCommunityService.CommunityResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST entry point for community-detection over a fact sheet's knowledge graph.
 *
 * <p>Delegates to {@link GraphCommunityService}, which builds a bounded
 * {@link ai.kompile.graph.reasoning.model.ReasoningGraph} subgraph and applies the requested
 * community-detection algorithm (Louvain or Label Propagation).</p>
 *
 * <p>Example request:
 * {@code GET /api/graph/42/communities?method=louvain&resolution=1.0&seed=42&maxNodes=500}</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/graph/{factSheetId}/communities")
public class GraphCommunityController {

    private final GraphCommunityService communityService;
    private final CommunitySummaryService summaryService;

    public GraphCommunityController(GraphCommunityService communityService,
                                    CommunitySummaryService summaryService) {
        this.communityService = communityService;
        this.summaryService = summaryService;
    }

    /**
     * Detect communities in the knowledge graph for the given fact sheet.
     *
     * @param factSheetId the fact sheet to analyse
     * @param method      "louvain" (default) or "label_propagation"
     * @param resolution  Louvain resolution γ; ignored for label_propagation (default 1.0)
     * @param seed        random seed for reproducibility (default 42)
     * @param maxNodes    maximum nodes in the reasoning subgraph (default 500)
     * @return {@link CommunityResult} as JSON, or 503 if the service is unavailable
     */
    @GetMapping
    public ResponseEntity<?> detectCommunities(
            @PathVariable Long factSheetId,
            @RequestParam(defaultValue = "louvain") String method,
            @RequestParam(defaultValue = "1.0") double resolution,
            @RequestParam(defaultValue = "42") long seed,
            @RequestParam(defaultValue = "500") int maxNodes) {

        log.info("GET /api/graph/{}/communities method={} resolution={} seed={} maxNodes={}",
                factSheetId, method, resolution, seed, maxNodes);

        try {
            CommunityResult result = communityService.detectCommunities(
                    factSheetId, method, resolution, seed, maxNodes);
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            log.error("Community detection failed for factSheetId={}: {}", factSheetId, ex.getMessage(), ex);
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Community detection unavailable: " + ex.getMessage()));
        }
    }

    /**
     * D9 — On-demand LLM summary for a single community.
     *
     * <p>Runs community detection over the fact sheet, resolves the member nodes'
     * titles and types, and asks the LLM pool to write a short natural-language
     * paragraph describing what the community represents.  The call is lazy
     * (only performed when this endpoint is hit) and resilient (returns a graceful
     * message if the LLM pool is unavailable).</p>
     *
     * <p>Example: {@code GET /api/graph/42/communities/3/summary}</p>
     *
     * @param factSheetId the fact sheet the communities were detected over
     * @param communityId the 0-based community id to summarise
     * @return {@link CommunitySummaryResult} as JSON with fields
     *         {@code communityId}, {@code summary}, {@code memberCount}
     */
    @GetMapping("/{communityId}/summary")
    public ResponseEntity<?> getCommunitySummary(
            @PathVariable Long factSheetId,
            @PathVariable int communityId) {

        log.info("GET /api/graph/{}/communities/{}/summary", factSheetId, communityId);

        try {
            CommunitySummaryResult result = summaryService.summarise(factSheetId, communityId);
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            log.error("Community summary failed for factSheetId={} communityId={}: {}",
                    factSheetId, communityId, ex.getMessage(), ex);
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Community summary unavailable: " + ex.getMessage()));
        }
    }
}
