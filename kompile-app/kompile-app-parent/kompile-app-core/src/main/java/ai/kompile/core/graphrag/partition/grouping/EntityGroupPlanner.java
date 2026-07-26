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

package ai.kompile.core.graphrag.partition.grouping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Decides which subjects are read together.
 *
 * <p>The shape is hybrid by default, because both pure alternatives are wrong at scale. One
 * partition per subject re-reads every shared chunk once per subject and produces coverage claims
 * that each know nothing about the others. One partition per corpus never closes and cannot be
 * re-run incrementally. What works is grouping subjects whose evidence genuinely overlaps, capping
 * the group so it stays a unit of work that can finish, and refusing to let hub subjects glue the
 * groups back together — see {@link BridgePolicy} for why that last part is not optional.</p>
 *
 * <p>Pure by construction: the caller measures how strongly subjects pull together and hands the
 * result in as {@link EntityLink}s. This class never asks what a link means, where it came from, or
 * what store it lives in, which is what keeps grouping testable and keeps graph access out of the
 * core.</p>
 *
 * <p>Every step is deterministic — sorted inputs, sorted tie-breaks, sorted output. The group ids
 * become partition ids, partition ids are durable, and a plan that produced different ids on a
 * re-run over unchanged input would orphan every claim made by the run before it.</p>
 */
public final class EntityGroupPlanner {

    private final GroupingPolicy policy;

    public EntityGroupPlanner(GroupingPolicy policy) {
        this.policy = policy == null ? GroupingPolicy.defaults() : policy;
    }

    /** The default hybrid grouping. */
    public static EntityGroupPlanner hybrid() {
        return new EntityGroupPlanner(GroupingPolicy.defaults());
    }

    /** One partition per subject, under its own policy version. */
    public static EntityGroupPlanner perEntity() {
        return new EntityGroupPlanner(GroupingPolicy.perEntity());
    }

    public GroupingPolicy policy() {
        return policy;
    }

    /** Groups {@code subjects} with nothing pulling them together — one partition each. */
    public GroupingPlan plan(Collection<String> subjects) {
        return plan(subjects, List.of());
    }

    /**
     * Groups {@code subjects}, using {@code links} to decide which belong together.
     *
     * <p>Links naming a subject that was not asked for are ignored rather than silently adding it:
     * the plan covers what the caller asked to cover, and quietly widening that would produce
     * partitions nobody asked for and coverage nobody claimed.</p>
     */
    public GroupingPlan plan(Collection<String> subjects, Collection<EntityLink> links) {
        List<String> cleaned = cleanedSubjects(subjects);
        if (cleaned.isEmpty()) {
            return GroupingPlan.empty(policy);
        }
        List<String> notes = new ArrayList<>();
        Map<String, Map<String, Double>> adjacency = adjacency(cleaned, links, notes);
        List<String> bridges = List.copyOf(bridges(cleaned, adjacency));
        if (!bridges.isEmpty()) {
            notes.add(bridges.size() + " subject(s) at or above degree " + policy.bridgeDegree()
                    + " were treated as bridges (" + policy.bridges() + "): "
                    + String.join(", ", bridges));
        }

        List<List<String>> components = components(cleaned, adjacency, new TreeSet<>(bridges));
        List<EntityGroup> groups = new ArrayList<>(components.size());
        for (List<String> component : components) {
            for (List<String> part : split(component, adjacency, notes)) {
                groups.add(EntityGroup.of(part));
            }
        }
        groups = applyBridges(groups, bridges, adjacency, notes);

        groups.sort(Comparator.comparing(EntityGroup::id));
        return new GroupingPlan(policy, List.copyOf(groups), bridges, List.copyOf(notes));
    }

    /** Trimmed, de-duplicated and sorted; sorting is what makes every later tie-break stable. */
    private static List<String> cleanedSubjects(Collection<String> subjects) {
        if (subjects == null || subjects.isEmpty()) {
            return List.of();
        }
        Set<String> cleaned = new TreeSet<>();
        for (String subject : subjects) {
            if (subject != null && !subject.isBlank()) {
                cleaned.add(subject.trim());
            }
        }
        return List.copyOf(cleaned);
    }

    /**
     * Who pulls towards whom, and how hard.
     *
     * <p>Accumulated per pair before the floor is applied: several observations of the same pair are
     * one link that pulls harder, not several links that each get judged separately and each fall
     * short.</p>
     */
    private Map<String, Map<String, Double>> adjacency(List<String> subjects,
                                                       Collection<EntityLink> links,
                                                       List<String> notes) {
        Map<String, Map<String, Double>> adjacency = new TreeMap<>();
        subjects.forEach(subject -> adjacency.put(subject, new TreeMap<>()));
        if (links == null || links.isEmpty()) {
            return adjacency;
        }
        int unknown = 0;
        Map<String, Map<String, Double>> observed = new TreeMap<>();
        for (EntityLink link : links) {
            if (link == null) {
                continue;
            }
            if (!adjacency.containsKey(link.from()) || !adjacency.containsKey(link.to())) {
                unknown++;
                continue;
            }
            observed.computeIfAbsent(link.from(), from -> new TreeMap<>())
                    .merge(link.to(), link.strength(), Double::sum);
            observed.computeIfAbsent(link.to(), to -> new TreeMap<>())
                    .merge(link.from(), link.strength(), Double::sum);
        }
        int weak = 0;
        for (Map.Entry<String, Map<String, Double>> from : observed.entrySet()) {
            for (Map.Entry<String, Double> to : from.getValue().entrySet()) {
                if (policy.admits(to.getValue())) {
                    adjacency.get(from.getKey()).put(to.getKey(), to.getValue());
                } else if (from.getKey().compareTo(to.getKey()) < 0) {
                    weak++;
                }
            }
        }
        if (unknown > 0) {
            notes.add(unknown + " link(s) named a subject that was not being grouped");
        }
        if (weak > 0) {
            notes.add(weak + " link(s) below strength " + policy.minLinkStrength()
                    + " did not pull their subjects together");
        }
        return adjacency;
    }

    private List<String> bridges(List<String> subjects,
                                 Map<String, Map<String, Double>> adjacency) {
        List<String> bridges = new ArrayList<>();
        for (String subject : subjects) {
            if (policy.isBridge(adjacency.get(subject).size())) {
                bridges.add(subject);
            }
        }
        return bridges;
    }

    /**
     * Connected components over the links, with bridges left out.
     *
     * <p>Leaving them out is the whole trick: a bridge in the union would merge every component it
     * touches, and one merged component is exactly the un-partitioned corpus this exists to
     * avoid.</p>
     */
    private static List<List<String>> components(List<String> subjects,
                                                 Map<String, Map<String, Double>> adjacency,
                                                 Set<String> bridges) {
        Map<String, String> parent = new LinkedHashMap<>();
        for (String subject : subjects) {
            if (!bridges.contains(subject)) {
                parent.put(subject, subject);
            }
        }
        for (Map.Entry<String, Map<String, Double>> entry : adjacency.entrySet()) {
            String from = entry.getKey();
            if (!parent.containsKey(from)) {
                continue;
            }
            for (String to : entry.getValue().keySet()) {
                if (parent.containsKey(to)) {
                    union(parent, from, to);
                }
            }
        }
        Map<String, List<String>> byRoot = new TreeMap<>();
        for (String subject : parent.keySet()) {
            byRoot.computeIfAbsent(find(parent, subject), root -> new ArrayList<>()).add(subject);
        }
        return List.copyOf(byRoot.values());
    }

    private static String find(Map<String, String> parent, String node) {
        String root = node;
        while (!root.equals(parent.get(root))) {
            root = parent.get(root);
        }
        String walk = node;
        while (!walk.equals(root)) {
            String next = parent.get(walk);
            parent.put(walk, root);
            walk = next;
        }
        return root;
    }

    private static void union(Map<String, String> parent, String left, String right) {
        String leftRoot = find(parent, left);
        String rightRoot = find(parent, right);
        if (leftRoot.equals(rightRoot)) {
            return;
        }
        // Smaller id wins, so the component's root does not depend on iteration order.
        if (leftRoot.compareTo(rightRoot) < 0) {
            parent.put(rightRoot, leftRoot);
        } else {
            parent.put(leftRoot, rightRoot);
        }
    }

    /**
     * Cuts a component down to the group cap.
     *
     * <p>The most connected subjects are kept together in the first part, so the split falls where
     * the component is thinnest rather than through the middle of its densest neighbourhood. It is
     * still a real loss — links crossing the cut are not seen by either half — so it is noted.</p>
     */
    private List<List<String>> split(List<String> component,
                                     Map<String, Map<String, Double>> adjacency,
                                     List<String> notes) {
        int max = policy.maxGroupSize();
        if (max <= 0 || component.size() <= max) {
            return List.of(component);
        }
        List<String> ordered = new ArrayList<>(component);
        ordered.sort(Comparator.comparingInt((String subject) -> adjacency.get(subject).size())
                .reversed()
                .thenComparing(Comparator.naturalOrder()));

        int parts = (ordered.size() + max - 1) / max;
        int base = ordered.size() / parts;
        int remainder = ordered.size() % parts;
        List<List<String>> split = new ArrayList<>(parts);
        int from = 0;
        for (int part = 0; part < parts; part++) {
            int size = base + (part < remainder ? 1 : 0);
            split.add(List.copyOf(ordered.subList(from, from + size)));
            from += size;
        }
        notes.add("a component of " + component.size() + " was split into " + parts
                + " groups to fit the cap of " + max
                + "; links across the split are not read together");
        return List.copyOf(split);
    }

    /**
     * Places the bridges according to the policy, once the neighbourhoods they might join exist.
     *
     * <p>A bridge is only ever placed against a neighbourhood — a group grown from non-bridge
     * subjects. A bridge that has already been placed is never a target for the next one: letting
     * bridges attach to each other would chain them back into the single mega-group that keeping
     * them out of the union just avoided, and would make the result depend on which bridge happened
     * to be placed first.</p>
     */
    private List<EntityGroup> applyBridges(List<EntityGroup> groups, List<String> bridges,
                                           Map<String, Map<String, Double>> adjacency,
                                           List<String> notes) {
        List<EntityGroup> placed = new ArrayList<>(groups);
        // Everything from here on is a bridge standing alone, and off-limits as a target.
        int neighbourhoods = groups.size();
        Set<String> bridged = new TreeSet<>(bridges);
        int stranded = 0;
        int full = 0;
        for (String bridge : bridges) {
            if (policy.bridges() == BridgePolicy.SEPARATE) {
                placed.add(EntityGroup.ofSingle(bridge));
                continue;
            }
            Map<Integer, Double> pull = pullTowardsNeighbourhoods(placed, neighbourhoods,
                    adjacency.get(bridge), bridged);
            if (pull.isEmpty()) {
                stranded++;
                placed.add(EntityGroup.ofSingle(bridge));
                continue;
            }
            if (policy.bridges() == BridgePolicy.SHARE) {
                for (Integer neighbourhood : pull.keySet()) {
                    placed.set(neighbourhood, placed.get(neighbourhood).sharing(bridge));
                }
                continue;
            }
            int strongest = strongest(pull);
            EntityGroup target = placed.get(strongest);
            if (policy.hasRoom(target.core().size())) {
                List<String> core = new ArrayList<>(target.core());
                core.add(bridge);
                // The core changed, so the id changes with it: a group of different subjects is a
                // different claim, and reusing the old id would let it inherit coverage it never had.
                placed.set(strongest, EntityGroup.of(core));
            } else {
                full++;
                placed.add(EntityGroup.ofSingle(bridge));
            }
        }
        if (stranded > 0) {
            notes.add(stranded + " bridge(s) had no group to join and were run alone");
        }
        if (full > 0) {
            notes.add(full + " bridge(s) could not join a full group and were run alone");
        }
        return placed;
    }

    /**
     * How hard a bridge pulls towards each neighbourhood.
     *
     * <p>Only non-bridge neighbours count. A pull through another bridge is a pull towards the
     * connective tissue rather than towards a neighbourhood, and following it is what turns two
     * groups into one.</p>
     */
    private static Map<Integer, Double> pullTowardsNeighbourhoods(List<EntityGroup> groups,
                                                                  int neighbourhoods,
                                                                  Map<String, Double> neighbours,
                                                                  Set<String> bridges) {
        Map<Integer, Double> pull = new TreeMap<>();
        if (neighbours == null || neighbours.isEmpty()) {
            return pull;
        }
        for (int index = 0; index < neighbourhoods; index++) {
            EntityGroup group = groups.get(index);
            double total = 0.0;
            for (Map.Entry<String, Double> neighbour : neighbours.entrySet()) {
                if (!bridges.contains(neighbour.getKey()) && group.owns(neighbour.getKey())) {
                    total += neighbour.getValue();
                }
            }
            if (total > 0.0) {
                pull.put(index, total);
            }
        }
        return pull;
    }

    /**
     * The neighbourhood pulled towards hardest.
     *
     * <p>Ties go to the first, and the neighbourhoods are in component order, so the choice is the
     * same on every run over the same corpus.</p>
     */
    private static int strongest(Map<Integer, Double> pull) {
        int best = -1;
        double bestPull = Double.NEGATIVE_INFINITY;
        for (Map.Entry<Integer, Double> candidate : pull.entrySet()) {
            if (candidate.getValue() > bestPull) {
                best = candidate.getKey();
                bestPull = candidate.getValue();
            }
        }
        return best;
    }
}
