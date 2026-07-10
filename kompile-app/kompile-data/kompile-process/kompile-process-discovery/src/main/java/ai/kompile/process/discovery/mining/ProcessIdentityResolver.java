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

import ai.kompile.process.discovery.ProcessSuggestion;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedPhase;
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves that a freshly mined suggestion and an earlier one describe the SAME business process
 * at different times — the identity link that turns disconnected mining snapshots into one
 * lineage, so drift ("what changed since last month?") becomes computable.
 *
 * <p>Identity = activity-set Jaccard between the fresh suggestion and each candidate head, with
 * BOTH sides remapped through the current alias merges first (a label the embedder unified this
 * mine must not break identity with a predecessor mined before the merge). Matching is greedy
 * one-to-one, best overlap first — a cluster that split keeps the key on its closest half and
 * mints fresh identity for the rest. Deliberately stricter than trace clustering
 * ({@code miningIdentityJaccardThreshold} &gt; {@code miningClusterJaccardThreshold}): identity is
 * a stronger claim than co-clustering.
 */
public final class ProcessIdentityResolver {

    private ProcessIdentityResolver() {
    }

    /** One resolved identity: the fresh suggestion's matched predecessor and the overlap score. */
    public record Match(ProcessSuggestion predecessor, double jaccard) {
    }

    /**
     * Match fresh suggestions to predecessor heads, greedily one-to-one by Jaccard.
     *
     * @param fresh       this mine's suggestions (ids already assigned)
     * @param candidates  predecessor suggestions still heading their lineage (pending heads and
     *                    accepted suggestions)
     * @param aliasMerges the alias merges THIS mine applied — predecessor labels remap through them
     * @param threshold   min activity-set Jaccard for an identity claim
     * @return fresh suggestion id → its match (unmatched suggestions absent)
     */
    public static Map<String, Match> resolve(List<ProcessSuggestion> fresh,
                                             List<ProcessSuggestion> candidates,
                                             List<ActivityAliasUnifier.Merge> aliasMerges,
                                             double threshold) {
        Map<String, Match> out = new LinkedHashMap<>();
        if (fresh == null || fresh.isEmpty() || candidates == null || candidates.isEmpty()) {
            return out;
        }
        record Pair(ProcessSuggestion fresh, ProcessSuggestion candidate, double jaccard) {
        }
        List<Pair> pairs = new ArrayList<>();
        for (ProcessSuggestion f : fresh) {
            Set<String> freshActivities = activitySet(f, aliasMerges);
            if (freshActivities.isEmpty()) {
                continue;
            }
            for (ProcessSuggestion c : candidates) {
                double jaccard = jaccard(freshActivities, activitySet(c, aliasMerges));
                if (jaccard >= threshold) {
                    pairs.add(new Pair(f, c, jaccard));
                }
            }
        }
        pairs.sort((a, b) -> Double.compare(b.jaccard(), a.jaccard()));
        Set<String> takenFresh = new LinkedHashSet<>();
        Set<String> takenCandidates = new LinkedHashSet<>();
        for (Pair pair : pairs) {
            if (takenFresh.contains(pair.fresh().getId())
                    || takenCandidates.contains(pair.candidate().getId())) {
                continue;
            }
            takenFresh.add(pair.fresh().getId());
            takenCandidates.add(pair.candidate().getId());
            out.put(pair.fresh().getId(), new Match(pair.candidate(), pair.jaccard()));
        }
        return out;
    }

    /** The suggestion's step names, remapped through the current alias merges. */
    static Set<String> activitySet(ProcessSuggestion suggestion,
                                   List<ActivityAliasUnifier.Merge> aliasMerges) {
        Set<String> activities = new LinkedHashSet<>();
        if (suggestion == null || suggestion.getPhases() == null) {
            return activities;
        }
        Map<String, String> canon = new LinkedHashMap<>();
        if (aliasMerges != null) {
            for (ActivityAliasUnifier.Merge merge : aliasMerges) {
                canon.put(merge.alias(), merge.canonical());
            }
        }
        for (SuggestedPhase phase : suggestion.getPhases()) {
            if (phase.getSteps() == null) {
                continue;
            }
            for (SuggestedStep step : phase.getSteps()) {
                if (step.getName() != null) {
                    activities.add(canon.getOrDefault(step.getName(), step.getName()));
                }
            }
        }
        return activities;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        long intersection = a.stream().filter(b::contains).count();
        return (double) intersection / (a.size() + b.size() - intersection);
    }
}
