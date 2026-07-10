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

package ai.kompile.process.discovery.mining.entail;

import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.discovery.mining.causal.DependencyMeasures;
import ai.kompile.process.discovery.mining.dfg.DirectlyFollowsGraph;
import ai.kompile.process.discovery.mining.extract.ActorResourceObservations;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes the discovered/entailed control flow back onto the knowledge graph — the Phase-2
 * "materialize {@code PRECEDES} edges" item — so attribution and retrieval can traverse it:
 *
 * <ul>
 *   <li><b>Instance level, observed:</b> consecutive events of each trace become
 *       {@code DIRECTLY_FOLLOWS} edges between their graph nodes (bounded by total events).</li>
 *   <li><b>Activity level, entailed:</b> pairs the reasoner accepted but discovery never directly
 *       observed become {@code PRECEDES} edges between each activity's representative node.</li>
 * </ul>
 *
 * <p>Both ride the free-form {@code relationType} seam via {@code EdgeSpec.label} — no
 * {@code EdgeType} enum surgery ({@link EdgeType#TEMPORAL} is the carrier type). The metadata bag
 * mirrors the OWL-materialization conventions: {@code provenanceType=INFERRED} (round-trips through
 * the matrix store's typed provenance), {@code bidirectional=false} (control flow is directional —
 * the store would otherwise default TEMPORAL edges to bidirectional), and {@code confidence}.
 * {@code createEdgesBatch} dedups per (source, target, type, label), so re-materialization after a
 * re-mine is idempotent.
 */
public final class PrecedenceMaterializer {

    private static final Logger log = LoggerFactory.getLogger(PrecedenceMaterializer.class);

    /** relationType of instance-level observed control-flow edges. */
    public static final String DIRECTLY_FOLLOWS = "DIRECTLY_FOLLOWS";
    /** relationType of activity-level entailed precedence edges. */
    public static final String PRECEDES = "PRECEDES";
    /** relationType of activity-level observed-performer edges (the OCPM resource perspective). */
    public static final String PERFORMED_BY = "PERFORMED_BY";

    private PrecedenceMaterializer() {
    }

    /**
     * @param directlyFollowsSpecs observed instance-level specs submitted
     * @param precedesSpecs        entailed activity-level specs submitted
     * @param performedBySpecs     observed-performer activity-level specs submitted
     * @param created              edges the store actually created (existing ones dedup to 0 on re-runs)
     */
    public record Result(int directlyFollowsSpecs, int precedesSpecs, int performedBySpecs, int created) {
        public static Result empty() {
            return new Result(0, 0, 0, 0);
        }
    }

    /** Control-flow-only overload — kept for callers without an observed-actor tally. */
    public static Result materialize(KnowledgeGraphService graph,
                                     Long factSheetId,
                                     String suggestionId,
                                     EventLog eventLog,
                                     DirectlyFollowsGraph dfg,
                                     ProcessEntailmentResult entailment,
                                     double materializeThreshold) {
        return materialize(graph, factSheetId, suggestionId, eventLog, dfg, entailment,
                materializeThreshold, Map.of());
    }

    /**
     * Materialize the control flow and resource perspective of one discovered process.
     *
     * @param graph                the @Primary (matrix/vector) knowledge-graph service
     * @param factSheetId          owning fact sheet
     * @param suggestionId         the suggestion this flow was mined for (stamped into edge metadata)
     * @param eventLog             the event log discovery ran on
     * @param dfg                  its directly-follows graph (edge weights = dependency strengths)
     * @param entailment           the settled precedence relation
     * @param materializeThreshold min posterior for entailed {@code PRECEDES} write-back
     * @param observedRoles        activity → observed majority performer; becomes representative
     *                             activity node —{@code PERFORMED_BY}→ actor node edges
     */
    public static Result materialize(KnowledgeGraphService graph,
                                     Long factSheetId,
                                     String suggestionId,
                                     EventLog eventLog,
                                     DirectlyFollowsGraph dfg,
                                     ProcessEntailmentResult entailment,
                                     double materializeThreshold,
                                     Map<String, ActorResourceObservations.ObservedRole> observedRoles) {
        if (graph == null || eventLog == null || eventLog.isEmpty()) {
            return Result.empty();
        }

        List<KnowledgeGraphService.EdgeSpec> specs = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. Observed control flow, instance level: one DIRECTLY_FOLLOWS edge per distinct
        //    consecutive node pair across all traces.
        for (Trace trace : eventLog.traces()) {
            List<Event> ordered = trace.ordered();
            for (int i = 0; i + 1 < ordered.size(); i++) {
                Event a = ordered.get(i);
                Event b = ordered.get(i + 1);
                if (a.graphNodeId() == null || b.graphNodeId() == null
                        || a.graphNodeId().equals(b.graphNodeId())) {
                    continue;
                }
                if (!seen.add(a.graphNodeId() + "::" + b.graphNodeId() + "::" + DIRECTLY_FOLLOWS)) {
                    continue;
                }
                double strength = clamp01(DependencyMeasures.dependency(dfg, a.activity(), b.activity()));
                specs.add(new KnowledgeGraphService.EdgeSpec(
                        a.graphNodeId(), b.graphNodeId(), EdgeType.TEMPORAL,
                        Math.max(0.1, strength),
                        "Directly follows: " + a.activity() + " -> " + b.activity(),
                        DIRECTLY_FOLLOWS,
                        metaJson(strength, "observed-df", suggestionId, a.activity(), b.activity()),
                        EdgeProvenance.INFERRED, factSheetId));
            }
        }
        int dfSpecs = specs.size();

        // 2. Entailed-but-unobserved precedence, activity level: representative node → representative node.
        Map<String, String> representative = representativeNodes(eventLog);
        int precedesSpecs = 0;
        if (entailment != null) {
            for (ProcessEntailmentResult.EntailedPrecedence p : entailment.assertable(materializeThreshold)) {
                if (p.observed()) {
                    continue; // instance edges above already carry directly-observed order
                }
                String fromNode = representative.get(p.from());
                String toNode = representative.get(p.to());
                if (fromNode == null || toNode == null || fromNode.equals(toNode)) {
                    continue;
                }
                if (!seen.add(fromNode + "::" + toNode + "::" + PRECEDES)) {
                    continue;
                }
                specs.add(new KnowledgeGraphService.EdgeSpec(
                        fromNode, toNode, EdgeType.TEMPORAL,
                        p.posterior(),
                        "Entailed precedence: " + p.from() + " precedes " + p.to(),
                        PRECEDES,
                        metaJson(p.posterior(), "psl-entailment", suggestionId, p.from(), p.to()),
                        EdgeProvenance.INFERRED, factSheetId));
                precedesSpecs++;
            }
        }

        // 3. Resource perspective, activity level: representative activity node → observed
        //    majority performer, so the visualizer/retrieval/ontology see WHO does the work.
        //    Directional USER_DEFINED (mirrors the crawl's own actor edges, e.g. APPROVED_BY);
        //    INFERRED provenance keeps these out of the next mine's casing AND actor tally —
        //    materialized performers never feed back into their own evidence.
        int performedBySpecs = 0;
        if (observedRoles != null && !observedRoles.isEmpty()) {
            for (Map.Entry<String, ActorResourceObservations.ObservedRole> entry : observedRoles.entrySet()) {
                ActorResourceObservations.ObservedRole role = entry.getValue();
                String activityNode = representative.get(entry.getKey());
                if (activityNode == null || role.actorNodeId() == null
                        || activityNode.equals(role.actorNodeId())) {
                    continue;
                }
                if (!seen.add(activityNode + "::" + role.actorNodeId() + "::" + PERFORMED_BY)) {
                    continue;
                }
                specs.add(new KnowledgeGraphService.EdgeSpec(
                        activityNode, role.actorNodeId(), EdgeType.USER_DEFINED,
                        Math.max(0.1, clamp01(role.share())),
                        "Observed performer: " + entry.getKey() + " performed by " + role.actorTitle(),
                        PERFORMED_BY,
                        metaJson(role.share(), "observed-actor", suggestionId, entry.getKey(), role.actorTitle()),
                        EdgeProvenance.INFERRED, factSheetId));
                performedBySpecs++;
            }
        }

        if (specs.isEmpty()) {
            return Result.empty();
        }
        int created = graph.createEdgesBatch(specs);
        log.info("Precedence materializer: {} DIRECTLY_FOLLOWS + {} PRECEDES + {} PERFORMED_BY specs "
                        + "→ {} created (existing edges dedup) for fact sheet {} suggestion {}",
                dfSpecs, precedesSpecs, performedBySpecs, created, factSheetId, suggestionId);
        return new Result(dfSpecs, precedesSpecs, performedBySpecs, created);
    }

    /**
     * One representative graph node per activity: the node of its earliest dated event, falling
     * back to the first event seen when no occurrence is dated.
     */
    private static Map<String, String> representativeNodes(EventLog eventLog) {
        Map<String, String> byActivity = new HashMap<>();
        Map<String, LocalDateTime> earliest = new HashMap<>();
        for (Trace trace : eventLog.traces()) {
            for (Event e : trace.ordered()) {
                if (e.graphNodeId() == null) {
                    continue;
                }
                byActivity.putIfAbsent(e.activity(), e.graphNodeId());
                if (e.timestamp() == null) {
                    continue;
                }
                LocalDateTime best = earliest.get(e.activity());
                if (best == null || e.timestamp().isBefore(best)) {
                    earliest.put(e.activity(), e.timestamp());
                    byActivity.put(e.activity(), e.graphNodeId());
                }
            }
        }
        return byActivity;
    }

    private static String metaJson(double confidence, String basis, String suggestionId,
                                   String fromActivity, String toActivity) {
        return "{"
                + "\"provenanceType\":\"INFERRED\","
                + "\"bidirectional\":false,"
                + "\"confidence\":" + String.format(Locale.ROOT, "%.4f", clamp01(confidence)) + ","
                + "\"basis\":\"" + esc(basis) + "\","
                + "\"minedBy\":\"PROCESS_MINING\","
                + "\"suggestionId\":\"" + esc(suggestionId) + "\","
                + "\"fromActivity\":\"" + esc(fromActivity) + "\","
                + "\"toActivity\":\"" + esc(toActivity) + "\"}";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static double clamp01(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }
}
