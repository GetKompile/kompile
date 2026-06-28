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
package ai.kompile.knowledgegraph.maintenance;

import ai.kompile.core.graphrag.maintenance.model.GraphPruneResult;
import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.pruning.OpinionPruner;
import ai.kompile.graph.reasoning.pruning.PrunePolicy;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Knowledge-graph-side P6 pass: prune edges by their Subjective-Logic {@link Opinion},
 * using the pure lib primitive {@link OpinionPruner} for the decision logic.
 *
 * <h3>Gate: only INFERRED and AMBIGUOUS edges</h3>
 * <p>EXTRACTED edges are observed truth and are never touched (same provenance gate as all
 * other prune passes in {@link PruneCompactOrchestrator}). Edges with no provenance type
 * recorded are treated as eligible (conservative default: if we do not know the source,
 * the Opinion value must do the work).</p>
 *
 * <h3>Opinion source</h3>
 * <p>Each edge's {@link GraphProvenanceKeys#OPINION} ({@code _opinion}) metadata key is read
 * from {@link GraphEdge#getMetadataJson()} via {@link Opinion#fromJson(String)}.
 * Edges whose metadata does not carry {@code _opinion} are skipped (left to other passes).</p>
 *
 * <h3>Config note (for KbConfig wiring)</h3>
 * <p>This pass accepts a {@link PrunePolicy} as a method parameter — no {@code @Value},
 * no hardcoded literals in the prune flow.  The orchestrator/caller constructs the
 * {@code PrunePolicy} from config fields. See {@link PrunePolicy} Javadoc for the full list
 * of documented defaults and ranges.</p>
 */
@Slf4j
@Component
public class OpinionPrunePass {

    // Field injection (not constructor): when Spring wires this bean via a CGLIB proxy (the native-image
    // / proxy path), a no-arg proxy constructor leaves a constructor-final field null and P6 silently
    // no-ops. Field injection lets Spring populate it on the proxy too, so the opinion prune actually runs.
    @org.springframework.beans.factory.annotation.Autowired
    private KnowledgeGraphService knowledgeGraphService;

    /**
     * Result record for one execution of this pass.
     *
     * @param scanned    total edges inspected
     * @param skipped    edges skipped (EXTRACTED provenance gate or no _opinion key)
     * @param pruned     edges pruned or, in dry-run mode, that would have been pruned
     * @param dryRun     whether this was a dry run
     */
    public record Result(int scanned, int skipped, int pruned, boolean dryRun) {
        public static Result empty(boolean dryRun) {
            return new Result(0, 0, 0, dryRun);
        }
    }

    /**
     * Execute the opinion-based prune pass for the given fact sheet.
     *
     * <p>Reads each eligible edge's {@code _opinion} from its {@code metadataJson}, feeds it
     * through an {@link OpinionPruner} configured with the given {@code policy}, and collects
     * the edges to prune. Actual deletion is delegated to {@link KnowledgeGraphService#pruneEdges}
     * (soft-delete) unless {@code dryRun} is {@code true}.</p>
     *
     * @param factSheetId the fact sheet to process; must not be {@code null}
     * @param policy      the prune thresholds to apply; must not be {@code null}; typically
     *                    constructed from KbConfig fields by the orchestrator/caller
     * @param dryRun      when {@code true} no writes are performed; counts are still computed
     * @return a {@link Result} with per-category counts
     */
    public Result execute(Long factSheetId, PrunePolicy policy, boolean dryRun) {
        if (factSheetId == null) throw new IllegalArgumentException("factSheetId must not be null");
        if (policy == null) throw new IllegalArgumentException("policy must not be null");

        // Guard: knowledgeGraphService is null when Spring wired this bean via the protected no-arg
        // constructor (CGLIB proxy path in GraalVM native image).  Rather than NPE, log and no-op.
        if (knowledgeGraphService == null) {
            log.warn("OpinionPrunePass: knowledgeGraphService is null (CGLIB proxy path) — "
                    + "skipping P6 opinion prune for factSheet={}", factSheetId);
            return Result.empty(dryRun);
        }

        List<GraphEdge> allEdges = knowledgeGraphService.getEdgesInFactSheet(factSheetId).stream()
                .filter(e -> !Boolean.TRUE.equals(e.getStale()))
                .toList();

        OpinionPruner pruner = new OpinionPruner(policy);
        List<String> toDelete = new ArrayList<>();
        int scanned = 0;
        int skipped = 0;

        for (GraphEdge edge : allEdges) {
            scanned++;

            // ── Provenance gate: only INFERRED / AMBIGUOUS (or unclassified) ─────
            EdgeProvenance prov = edge.getProvenanceType();
            if (prov == EdgeProvenance.EXTRACTED) {
                skipped++;
                continue;
            }

            // ── Opinion gate: skip edges with no _opinion in metadata ─────────────
            String metaJson = edge.getMetadataJson();
            if (metaJson == null || metaJson.isBlank()) {
                skipped++;
                continue;
            }
            Opinion opinion = readOpinion(metaJson, edge.getEdgeId());
            if (opinion == null) {
                skipped++;
                continue;
            }

            // ── Decision ─────────────────────────────────────────────────────────
            OpinionPruner.Decision decision = pruner.decide(edge.getEdgeId(), opinion);
            if (decision.shouldPrune()) {
                toDelete.add(edge.getEdgeId());
                log.debug("OpinionPrunePass: factSheet={} edge={} → PRUNE: {}",
                        factSheetId, edge.getEdgeId(), decision.reason());
            }
        }

        int pruned = toDelete.size();

        if (!toDelete.isEmpty() && !dryRun) {
            try {
                GraphPruneResult result = knowledgeGraphService.pruneEdges(
                        toDelete, /* softDelete= */ true, /* dryRun= */ false);
                log.info("OpinionPrunePass: factSheet={} pruned={} opinion-filtered edges (dryRun=false)",
                        factSheetId, result.affectedCount());
            } catch (Exception e) {
                log.warn("OpinionPrunePass: pruneEdges failed for factSheet={}: {}",
                        factSheetId, e.getMessage());
            }
        } else if (!toDelete.isEmpty()) {
            log.info("OpinionPrunePass (dryRun): factSheet={} would prune={} opinion-filtered edges",
                    factSheetId, pruned);
        }

        log.info("OpinionPrunePass: factSheet={} scanned={} skipped={} pruned={} dryRun={}",
                factSheetId, scanned, skipped, pruned, dryRun);

        return new Result(scanned, skipped, pruned, dryRun);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extract an {@link Opinion} from a raw metadataJson string.
     *
     * <p>The key {@link GraphProvenanceKeys#OPINION} ({@code "_opinion"}) must be present
     * and its value must be a JSON object parseable by {@link Opinion#fromJson(String)}.
     * Any parse failure is logged at WARN and {@code null} is returned so the edge is skipped.</p>
     *
     * @param metaJson raw JSON string from {@link GraphEdge#getMetadataJson()}
     * @param edgeId   edge identifier for diagnostic logging
     * @return the parsed {@link Opinion}, or {@code null} if absent or unparseable
     */
    static Opinion readOpinion(String metaJson, String edgeId) {
        // Quick pre-check: the key must exist before we do heavyweight JSON parsing
        if (!metaJson.contains(GraphProvenanceKeys.OPINION)) {
            return null;
        }

        // Extract the value for "_opinion" from the flat JSON map.
        // The value is itself a nested JSON object of the form {"belief":…,"disbelief":…,…}.
        // We locate it by scanning for the key, finding the opening '{' of the nested object,
        // then matching braces to locate the closing '}' — this avoids pulling in a heavy
        // JSON library dependency and is safe because Opinion.toJson() only emits scalars.
        try {
            String key = "\"" + GraphProvenanceKeys.OPINION + "\":";
            int keyIdx = metaJson.indexOf(key);
            if (keyIdx < 0) return null;

            int objectStart = metaJson.indexOf('{', keyIdx + key.length());
            if (objectStart < 0) return null;

            int depth = 0;
            int objectEnd = -1;
            for (int i = objectStart; i < metaJson.length(); i++) {
                char c = metaJson.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) { objectEnd = i; break; }
                }
            }
            if (objectEnd < 0) {
                log.warn("OpinionPrunePass: malformed _opinion JSON for edge={}", edgeId);
                return null;
            }

            String opinionJson = metaJson.substring(objectStart, objectEnd + 1);
            return Opinion.fromJson(opinionJson);
        } catch (Exception ex) {
            log.warn("OpinionPrunePass: could not parse _opinion for edge={}: {}", edgeId, ex.getMessage());
            return null;
        }
    }
}
