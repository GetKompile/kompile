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

package ai.kompile.process.discovery.mining.extract;

import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The resource perspective of object-centric process mining: WHO performs each activity, tallied
 * from the same actor-incident relations that {@link EventLogExtractor} deliberately excludes from
 * case correlation.
 *
 * <p>Those edges (person —SENT_BY→ email, person —BELONGS_TO→ org) are excluded from casing because
 * shared actors union unrelated threads into one mega-case — but they are exactly the evidence role
 * binding needs. This class recovers it: for every observed edge joining an actor/resource node
 * ({@link ActivityClassifier#isActorResource}) to a non-actor node, the actor is attributed to that
 * node's activity. Attribution through communication carriers is transitive one hop: an actor
 * incident to an EMAIL_MESSAGE is attributed to the business entities the message MENTIONS, because
 * the carrier and its extracted entities are facets of the same real-world event (the extraction
 * lane lifts {@code occurredAt} from the message onto them for the same reason). Carrier activities
 * themselves are never bound — they carry zero workflow identity (same rationale as
 * {@link TraceClusterer} excluding them from cluster signatures).
 *
 * <p>Performer vs. involvement: an edge whose label denotes authorship (the {@code *_BY} convention
 * the extractors emit — SENT_BY, CREATED_BY, APPROVED_BY…) marks the actor as the activity's
 * <em>performer</em>; any other actor edge (SENT_TO, CC_TO, MENTIONS) marks mere involvement.
 * Selection per activity prefers performer evidence, then involvement share; an actor with no
 * performer evidence must cover at least {@link #MIN_INVOLVEMENT_SHARE} of the activity's instances
 * (otherwise a one-off CC would win a role).
 *
 * <p>Pure — operates on explicit node/edge lists, no graph-service calls — so the whole tally is
 * unit-testable on hand-built graphs, like the rest of the extract package.
 */
public final class ActorResourceObservations {

    /** An actor with no performer-labeled edge must touch at least this share of instances. */
    public static final double MIN_INVOLVEMENT_SHARE = 0.5;

    /**
     * Entity types whose adjacency to the chosen actor names their role better than the actor
     * itself does ("Procurement Approver" beats "bob"). Deliberately excludes ORGANIZATION/COMPANY:
     * on real crawls everyone belongs to the same org node, so it discriminates nothing.
     */
    private static final List<String> ROLE_NEIGHBOR_TYPES =
            List.of("ROLE", "JOB_TITLE", "POSITION", "DEPARTMENT", "TEAM");

    private ActorResourceObservations() {
    }

    /**
     * The observed resource binding for one activity.
     *
     * @param roleLabel          what to bind: a ROLE/DEPARTMENT/TEAM neighbor's title when the actor
     *                           has one, else the actor's own title
     * @param actorTitle         the winning actor's display title
     * @param actorType          the actor's entity type (PERSON, DEPARTMENT…)
     * @param actorNodeId        the winning actor's graph node id — lets the miner materialize
     *                           activity —PERFORMED_BY→ actor edges back onto the graph
     * @param performerInstances distinct activity instances attributed via performer-labeled edges
     * @param involvedInstances  distinct activity instances attributed via ANY actor edge
     * @param activityInstances  total distinct instance nodes classified to this activity
     */
    public record ObservedRole(String roleLabel, String actorTitle, String actorType, String actorNodeId,
                               int performerInstances, int involvedInstances, int activityInstances) {

        /** Compact constructor for callers without a graph node id (KB-derived, tests). */
        public ObservedRole(String roleLabel, String actorTitle, String actorType,
                            int performerInstances, int involvedInstances, int activityInstances) {
            this(roleLabel, actorTitle, actorType, null,
                    performerInstances, involvedInstances, activityInstances);
        }

        /** Fraction of the activity's instances this actor was observed on. */
        public double share() {
            return activityInstances <= 0 ? 0.0 : involvedInstances / (double) activityInstances;
        }

        /** True when at least one performer-labeled edge backs this binding. */
        public boolean performerEvidence() {
            return performerInstances > 0;
        }

        /** Positive evidence count for Beta/Opinion promotion: performer instances when present. */
        public int evidenceInstances() {
            return performerInstances > 0 ? performerInstances : involvedInstances;
        }
    }

    /** Tally with the default involvement-share gate ({@link #MIN_INVOLVEMENT_SHARE}). */
    public static Map<String, ObservedRole> tally(List<GraphNode> nodes, List<GraphEdge> edges,
                                                  ActivityClassifier classifier) {
        return tally(nodes, edges, classifier, MIN_INVOLVEMENT_SHARE);
    }

    /** Winning bindings only — see {@link #tallyDetailed} for the contested roles too. */
    public static Map<String, ObservedRole> tally(List<GraphNode> nodes, List<GraphEdge> edges,
                                                  ActivityClassifier classifier,
                                                  double minInvolvementShare) {
        return tallyDetailed(nodes, edges, classifier, minInvolvementShare).roles();
    }

    /**
     * Tally actor observations over a fact sheet's raw nodes and edges.
     *
     * @param nodes               the fact sheet's nodes
     * @param edges               the fact sheet's edges (observed and derived; derived are skipped here)
     * @param classifier          the SAME activity classifier the event-log extraction uses, so tallied
     *                            activity names match mined step names exactly
     * @param minInvolvementShare an actor with no performer-labeled edge must touch at least this
     *                            share of the activity's instances (managed config:
     *                            {@code miningActorInvolvementMinShare})
     * @return winning role per activity PLUS the contested roles (two well-supported performers)
     */
    public static Tally tallyDetailed(List<GraphNode> nodes, List<GraphEdge> edges,
                                      ActivityClassifier classifier,
                                      double minInvolvementShare) {
        if (nodes == null || nodes.isEmpty() || edges == null || edges.isEmpty() || classifier == null) {
            return new Tally(Map.of(), List.of());
        }

        Map<String, GraphNode> byId = new LinkedHashMap<>();
        for (GraphNode n : nodes) {
            if (n != null && n.getNodeId() != null
                    && (n.getNodeType() == null || !EventLogExtractor.DEFAULT_EXCLUDED_LEVELS.contains(n.getNodeType()))) {
                byId.put(n.getNodeId(), n);
            }
        }

        // Activity per node (null = not an activity), and instance counts per activity name.
        Map<String, String> activityByNode = new LinkedHashMap<>();
        Map<String, Integer> instancesByActivity = new LinkedHashMap<>();
        for (GraphNode n : byId.values()) {
            if (ActivityClassifier.isActorResource(n)) {
                continue;
            }
            String activity = classifier.activityOf(n);
            if (activity == null || ActivityClassifier.isCommunicationScaffoldLabel(activity)) {
                // Carriers are recorded per-node (for one-hop propagation) but never counted as
                // bindable activity instances.
                if (activity != null) {
                    activityByNode.put(n.getNodeId(), activity);
                }
                continue;
            }
            activityByNode.put(n.getNodeId(), activity);
            instancesByActivity.merge(activity, 1, Integer::sum);
        }

        // Observed (non-derived) adjacency, for carrier propagation and role-neighbor lookup.
        List<GraphEdge> observed = new ArrayList<>();
        Map<String, List<GraphEdge>> incident = new LinkedHashMap<>();
        for (GraphEdge e : edges) {
            if (e == null || isDerived(e) || e.getSourceNodeId() == null || e.getTargetNodeId() == null) {
                continue;
            }
            observed.add(e);
            incident.computeIfAbsent(e.getSourceNodeId(), k -> new ArrayList<>()).add(e);
            incident.computeIfAbsent(e.getTargetNodeId(), k -> new ArrayList<>()).add(e);
        }

        // Attribution: actor node id → activity → distinct instance-node ids (performer / involved).
        Map<String, Map<String, Set<String>>> performer = new LinkedHashMap<>();
        Map<String, Map<String, Set<String>>> involved = new LinkedHashMap<>();
        for (GraphEdge e : observed) {
            GraphNode source = e.resolvedSourceNode(byId);
            GraphNode target = e.resolvedTargetNode(byId);
            boolean sourceActor = ActivityClassifier.isActorResource(source);
            boolean targetActor = ActivityClassifier.isActorResource(target);
            if (sourceActor == targetActor) {
                continue; // actor–actor (BELONGS_TO org) or plain content edge
            }
            GraphNode actor = sourceActor ? source : target;
            GraphNode other = sourceActor ? target : source;
            if (actor == null || other == null || other.getNodeId() == null) {
                continue;
            }
            boolean performs = isPerformerLabel(e.getRelationType());
            String otherActivity = activityByNode.get(other.getNodeId());
            if (otherActivity == null) {
                continue;
            }
            if (!ActivityClassifier.isCommunicationScaffoldLabel(otherActivity)) {
                attribute(performer, involved, actor.getNodeId(), otherActivity, other.getNodeId(), performs);
                continue;
            }
            // Carrier: propagate one hop to the business entities it links to.
            for (GraphEdge hop : incident.getOrDefault(other.getNodeId(), List.of())) {
                String farId = other.getNodeId().equals(hop.getSourceNodeId())
                        ? hop.getTargetNodeId() : hop.getSourceNodeId();
                GraphNode far = byId.get(farId);
                if (far == null || ActivityClassifier.isActorResource(far)) {
                    continue;
                }
                String farActivity = activityByNode.get(farId);
                if (farActivity == null || ActivityClassifier.isCommunicationScaffoldLabel(farActivity)) {
                    continue;
                }
                attribute(performer, involved, actor.getNodeId(), farActivity, farId, performs);
            }
        }

        // Per-activity selection: performer instances first, involvement second, title for
        // determinism. The runner-up is kept so a CONTESTED role (two well-supported performers —
        // conflicting sources) surfaces as a conflict instead of a silent coin-flip.
        Map<String, ObservedRole> out = new LinkedHashMap<>();
        List<RoleConflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : instancesByActivity.entrySet()) {
            String activity = entry.getKey();
            int total = entry.getValue();
            ObservedRole best = null;
            ObservedRole runnerUp = null;
            Map<String, Set<String>> performerIdsByTitle = new LinkedHashMap<>();
            for (String actorId : involved.keySet()) {
                Set<String> inv = involved.getOrDefault(actorId, Map.of()).get(activity);
                if (inv == null || inv.isEmpty()) {
                    continue;
                }
                Set<String> perf = performer.getOrDefault(actorId, Map.of()).get(activity);
                GraphNode actor = byId.get(actorId);
                ObservedRole candidate = new ObservedRole(
                        roleLabelFor(actor, incident, byId),
                        title(actor, actorId),
                        entityType(actor),
                        actorId,
                        perf == null ? 0 : perf.size(),
                        inv.size(),
                        total);
                if (perf != null && !perf.isEmpty()) {
                    performerIdsByTitle.put(candidate.actorTitle(), perf);
                }
                if (best == null || better(candidate, best)) {
                    runnerUp = best;
                    best = candidate;
                } else if (runnerUp == null || better(candidate, runnerUp)) {
                    runnerUp = candidate;
                }
            }
            if (best != null && (best.performerEvidence() || best.share() >= minInvolvementShare)) {
                out.put(activity, best);
                // A rival with ≥2 performer instances and at least half the winner's support is a
                // live disagreement about ownership, not noise.
                if (runnerUp != null && runnerUp.performerInstances() >= 2
                        && runnerUp.performerInstances() * 2 >= best.performerInstances()) {
                    conflicts.add(new RoleConflict(activity,
                            best.actorTitle(),
                            Set.copyOf(performerIdsByTitle.getOrDefault(best.actorTitle(), Set.of())),
                            runnerUp.actorTitle(),
                            Set.copyOf(performerIdsByTitle.getOrDefault(runnerUp.actorTitle(), Set.of()))));
                }
            }
        }
        return new Tally(out, List.copyOf(conflicts));
    }

    /** A contested role: two well-supported performers for the same activity. */
    public record RoleConflict(String activity,
                               String winnerTitle, Set<String> winnerInstanceIds,
                               String rivalTitle, Set<String> rivalInstanceIds) {
    }

    /** The full tally: winning bindings plus the contested ones. */
    public record Tally(Map<String, ObservedRole> roles, List<RoleConflict> conflicts) {
    }

    /** Performer edge labels follow the extractors' {@code *_BY} authorship convention. */
    static boolean isPerformerLabel(String relationType) {
        if (relationType == null) {
            return false;
        }
        String upper = relationType.trim().toUpperCase(Locale.ROOT);
        return upper.endsWith("_BY") || upper.equals("FROM") || upper.equals("PERFORMED")
                || upper.equals("PERFORMER") || upper.equals("AUTHOR");
    }

    private static void attribute(Map<String, Map<String, Set<String>>> performer,
                                  Map<String, Map<String, Set<String>>> involved,
                                  String actorId, String activity, String instanceId, boolean performs) {
        involved.computeIfAbsent(actorId, k -> new LinkedHashMap<>())
                .computeIfAbsent(activity, k -> new LinkedHashSet<>()).add(instanceId);
        if (performs) {
            performer.computeIfAbsent(actorId, k -> new LinkedHashMap<>())
                    .computeIfAbsent(activity, k -> new LinkedHashSet<>()).add(instanceId);
        }
    }

    private static boolean better(ObservedRole a, ObservedRole b) {
        if (a.performerInstances() != b.performerInstances()) {
            return a.performerInstances() > b.performerInstances();
        }
        if (a.involvedInstances() != b.involvedInstances()) {
            return a.involvedInstances() > b.involvedInstances();
        }
        return a.actorTitle().compareTo(b.actorTitle()) < 0;
    }

    /** A ROLE/DEPARTMENT/TEAM node adjacent to the actor names the role; else the actor's title. */
    private static String roleLabelFor(GraphNode actor, Map<String, List<GraphEdge>> incident,
                                       Map<String, GraphNode> byId) {
        if (actor == null || actor.getNodeId() == null) {
            return null;
        }
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        for (GraphEdge e : incident.getOrDefault(actor.getNodeId(), List.of())) {
            String neighborId = actor.getNodeId().equals(e.getSourceNodeId())
                    ? e.getTargetNodeId() : e.getSourceNodeId();
            GraphNode neighbor = byId.get(neighborId);
            String type = entityType(neighbor);
            int rank = type == null ? -1 : ROLE_NEIGHBOR_TYPES.indexOf(type.toUpperCase(Locale.ROOT));
            if (rank >= 0 && rank < bestRank && neighbor.getTitle() != null && !neighbor.getTitle().isBlank()) {
                best = neighbor.getTitle().trim();
                bestRank = rank;
            }
        }
        return best != null ? best : title(actor, actor.getNodeId());
    }


    private static String title(GraphNode node, String fallback) {
        return node != null && node.getTitle() != null && !node.getTitle().isBlank()
                ? node.getTitle().trim() : fallback;
    }

    private static String entityType(GraphNode node) {
        if (node == null || node.getMetadata() == null) {
            return null;
        }
        return ai.kompile.core.graphrag.typing.GraphNodeTypes.resolveDeclaredType(node.getMetadata());
    }

    private static boolean isDerived(GraphEdge e) {
        if (e.getProvenanceType() == EdgeProvenance.INFERRED) {
            return true;
        }
        String relationType = e.getRelationType();
        return relationType != null
                && (relationType.equalsIgnoreCase(ai.kompile.process.discovery.mining.entail.PrecedenceMaterializer.DIRECTLY_FOLLOWS)
                        || relationType.equalsIgnoreCase(ai.kompile.process.discovery.mining.entail.PrecedenceMaterializer.PRECEDES)
                        // the miner's own materialized performers must never feed the next tally
                        || relationType.equalsIgnoreCase(ai.kompile.process.discovery.mining.entail.PrecedenceMaterializer.PERFORMED_BY));
    }
}
