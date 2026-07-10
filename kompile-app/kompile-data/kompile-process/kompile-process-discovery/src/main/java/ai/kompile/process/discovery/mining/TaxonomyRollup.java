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

package ai.kompile.process.discovery.mining;

import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.process.discovery.mining.extract.ActivityClassifier;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OWL is-a resolution AT THE MINING SEAM — by consuming what the reasoning stack already
 * materialized, not by re-deriving it. {@code OwlReasoningService} writes each entity node's
 * cax-sco closure onto its metadata ({@code owlInferredTypes} — a CHIANTI node carries
 * {@code [Chianti, RedWine, Wine]}), and retrieval already reads it
 * ({@code MatrixGraphRagService.matchesEntityType}); this class makes process mining read the
 * SAME keys, so traces over sibling subtypes mine as one process about their shared concept
 * instead of N thin variants.
 *
 * <p>Roll-up policy, applied to fixpoint: an ancestor qualifies when at least
 * {@code minSiblings} current activities map to it (an activity already LABELED as the ancestor
 * counts — it IS the concept); each activity rolls to its MOST SPECIFIC qualifying ancestor
 * (fewest members, ties lexicographic — deterministic re-mines). Repeated passes let
 * {Chianti, Malbec} → RedWine → Wine converge with {Riesling} → Wine onto ONE Wine activity.
 * Every rewritten event keeps its ORIGINAL leaf label as a {@code category} attribute, so the
 * existing decision-stump guard mining can rediscover subtype routing ({@code #category ==
 * 'Chianti'}) exactly like any other attribute.
 *
 * <p>Inert without closure metadata (no bound ontology / OWL classification not run) — the honest
 * no-artifact degradation.
 */
public final class TaxonomyRollup {

    /** Event attribute carrying the pre-rollup leaf label (guard mining routes on it). */
    public static final String CATEGORY_ATTRIBUTE = "category";

    /** Fixpoint guard — deeper real-world taxonomies than this are a modeling smell. */
    static final int MAX_PASSES = 5;

    private TaxonomyRollup() {
    }

    /**
     * @param log      the (possibly rewritten) log; the ORIGINAL instance when nothing rolled up
     * @param rollups  original activity label → FINAL ancestor label (transitively resolved)
     * @param groups   human-readable group descriptions ("'Chianti', 'Malbec', 'Riesling' → 'Wine'")
     */
    public record Result(EventLog log, Map<String, String> rollups, List<String> groups) {
        public boolean isEmpty() {
            return rollups.isEmpty();
        }
    }

    /**
     * Roll sibling activities up to their shared ontology concept.
     *
     * @param log         the extracted (and alias-unified) event log
     * @param nodes       the fact sheet's nodes — the closure metadata lives on THEM
     * @param classifier  the same classifier extraction used (labels must line up)
     * @param minSiblings min activities per qualifying ancestor ({@code < 2} disables — a lone
     *                    subtype is already its concept's best name)
     */
    public static Result apply(EventLog log, List<GraphNode> nodes, ActivityClassifier classifier,
                               int minSiblings) {
        if (log == null || log.isEmpty() || nodes == null || nodes.isEmpty() || minSiblings < 2) {
            return new Result(log, Map.of(), List.of());
        }

        // Per activity label: the ancestors (display-label space, self excluded), gathered across
        // the nodes that produce the activity — from BOTH taxonomy sources the graph already
        // carries: the materialized OWL closure AND the crawl-native type hierarchy
        // (entity_subtype → entity_type → entity_category), so an ungoverned crawl that stamps
        // entity_category=Wine rolls up without OWL ever having run.
        Map<String, Set<String>> ancestorsByActivity = new LinkedHashMap<>();
        for (GraphNode node : nodes) {
            String activity = classifier != null ? classifier.activityOf(node) : null;
            if (activity == null) {
                continue;
            }
            for (String ancestor : ancestorsOf(node)) {
                String label = ActivityClassifier.displayLabel(ancestor);
                if (!label.equals(activity)) {
                    ancestorsByActivity.computeIfAbsent(activity, k -> new LinkedHashSet<>()).add(label);
                }
            }
        }
        if (ancestorsByActivity.isEmpty()) {
            return new Result(log, Map.of(), List.of());
        }

        // Iterate to fixpoint: each pass maps every activity to its most specific qualifying
        // ancestor; ancestors inherit their members' remaining closures so multi-level
        // hierarchies converge ({Chianti,Malbec}→RedWine, then {RedWine,Wine}→Wine).
        Map<String, String> resolved = new LinkedHashMap<>();   // original → current label
        Set<String> current = new LinkedHashSet<>(activityNames(log));
        current.forEach(a -> resolved.put(a, a));
        Map<String, Set<String>> closures = new LinkedHashMap<>(ancestorsByActivity);
        List<String> groups = new ArrayList<>();
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            Map<String, String> mapping = onePass(current, closures, minSiblings, groups);
            if (mapping.isEmpty()) {
                break;
            }
            resolved.replaceAll((original, label) -> mapping.getOrDefault(label, label));
            Map<String, Set<String>> nextClosures = new LinkedHashMap<>();
            Set<String> next = new LinkedHashSet<>();
            for (String label : current) {
                String mapped = mapping.getOrDefault(label, label);
                next.add(mapped);
                Set<String> remaining = nextClosures.computeIfAbsent(mapped, k -> new LinkedHashSet<>());
                for (String ancestor : closures.getOrDefault(label, Set.of())) {
                    if (!ancestor.equals(mapped)) {
                        remaining.add(ancestor);
                    }
                }
            }
            current = next;
            closures = nextClosures;
        }

        Map<String, String> rollups = new LinkedHashMap<>();
        resolved.forEach((original, label) -> {
            if (!original.equals(label)) {
                rollups.put(original, label);
            }
        });
        if (rollups.isEmpty()) {
            return new Result(log, Map.of(), List.of());
        }

        // Rewrite the log; the pre-rollup leaf label rides along as the category attribute.
        List<Trace> rewritten = new ArrayList<>(log.traces().size());
        for (Trace trace : log.traces()) {
            List<Event> events = new ArrayList<>(trace.events().size());
            for (Event event : trace.events()) {
                String target = rollups.get(event.activity());
                if (target == null) {
                    events.add(event);
                    continue;
                }
                Map<String, Object> attributes = new LinkedHashMap<>(event.attributes());
                attributes.putIfAbsent(CATEGORY_ATTRIBUTE, event.activity());
                events.add(new Event(event.caseId(), target, event.timestamp(),
                        event.graphNodeId(), attributes));
            }
            rewritten.add(new Trace(trace.caseId(), events));
        }
        return new Result(new EventLog(rewritten), rollups, List.copyOf(groups));
    }

    /** One roll-up pass: activity → most specific qualifying ancestor (or absent). */
    private static Map<String, String> onePass(Set<String> activities,
                                               Map<String, Set<String>> closures,
                                               int minSiblings, List<String> groups) {
        // members(ancestor) = activities carrying it in their closure, plus the ancestor itself
        // when it is already an activity (it IS the concept).
        Map<String, Set<String>> members = new LinkedHashMap<>();
        for (String activity : activities) {
            for (String ancestor : closures.getOrDefault(activity, Set.of())) {
                members.computeIfAbsent(ancestor, k -> new LinkedHashSet<>()).add(activity);
            }
        }
        members.forEach((ancestor, set) -> {
            if (activities.contains(ancestor)) {
                set.add(ancestor);
            }
        });

        Map<String, String> mapping = new LinkedHashMap<>();
        for (String activity : activities) {
            String best = null;
            int bestSize = Integer.MAX_VALUE;
            for (String ancestor : closures.getOrDefault(activity, Set.of())) {
                Set<String> group = members.getOrDefault(ancestor, Set.of());
                if (group.size() < minSiblings) {
                    continue;
                }
                if (group.size() < bestSize
                        || (group.size() == bestSize && ancestor.compareTo(best) < 0)) {
                    best = ancestor;
                    bestSize = group.size();
                }
            }
            if (best != null) {
                mapping.put(activity, best);
            }
        }
        // Describe each applied group once, at the pass where it fires.
        Map<String, Set<String>> applied = new LinkedHashMap<>();
        mapping.forEach((activity, ancestor) ->
                applied.computeIfAbsent(ancestor, k -> new LinkedHashSet<>()).add(activity));
        applied.forEach((ancestor, group) -> groups.add(
                "'" + String.join("', '", group) + "' → '" + ancestor + "' (OWL is-a closure)"));
        return mapping;
    }

    /** Remap any activity-keyed map through the applied rollups. */
    public static <V> Map<String, V> remapKeys(Map<String, V> byActivity, Map<String, String> rollups,
                                               java.util.function.BinaryOperator<V> onCollision) {
        if (byActivity == null || byActivity.isEmpty() || rollups == null || rollups.isEmpty()) {
            return byActivity;
        }
        Map<String, V> out = new LinkedHashMap<>();
        for (Map.Entry<String, V> entry : byActivity.entrySet()) {
            out.merge(rollups.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue(), onCollision);
        }
        return out;
    }

    /**
     * The node's is-a ancestors, raw type space, from both graph-borne taxonomy sources:
     * the OWL closure ({@code GraphNodeTypes.resolveTypeClosure} — the SAME single key
     * definition retrieval's {@code matchesEntityType} reads) plus the crawl-native hierarchy
     * ({@code resolveTypeHierarchy}: entity_subtype → entity_type → entity_category), walked
     * upward from the node's declared type. Empty when the node carries no taxonomy at all.
     */
    static List<String> ancestorsOf(GraphNode node) {
        Map<String, Object> metadata = node.getMetadata();
        if (metadata == null || metadata.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> ancestors = new LinkedHashSet<>(
                ai.kompile.core.graphrag.typing.GraphNodeTypes.resolveTypeClosure(metadata));
        // Crawl-native hierarchy: walk parent links upward from the DECLARED type only —
        // subtype entries are narrower than the activity, never ancestors of it.
        Map<String, String> parentByType = new LinkedHashMap<>();
        for (ai.kompile.core.graphrag.typing.GraphNodeTypes.TypeHierarchyEdge edge :
                ai.kompile.core.graphrag.typing.GraphNodeTypes.resolveTypeHierarchy(metadata)) {
            if (edge.type() != null && edge.parentType() != null) {
                parentByType.put(edge.type(), edge.parentType());
            }
        }
        String current = ai.kompile.core.graphrag.typing.GraphNodeTypes.resolveDeclaredType(metadata);
        for (int hop = 0; hop < MAX_PASSES && current != null; hop++) {
            current = parentByType.get(current);
            if (current != null) {
                ancestors.add(current);
            }
        }
        return List.copyOf(ancestors);
    }

    private static Set<String> activityNames(EventLog log) {
        Set<String> names = new LinkedHashSet<>();
        for (Trace trace : log.traces()) {
            for (Event event : trace.events()) {
                names.add(event.activity());
            }
        }
        return names;
    }
}
