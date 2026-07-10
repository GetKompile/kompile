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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies process identity across mining generations: Jaccard matching over alias-unified
 * activity sets, greedy one-to-one assignment, and the below-threshold no-claim.
 */
class ProcessIdentityResolverTest {

    private static ProcessSuggestion suggestion(String id, String... stepNames) {
        List<SuggestedStep> steps = new ArrayList<>();
        for (String name : stepNames) {
            steps.add(SuggestedStep.builder().name(name).stepType("AUTO").build());
        }
        return ProcessSuggestion.builder()
                .id(id)
                .phases(List.of(SuggestedPhase.builder().name("p").steps(steps).build()))
                .build();
    }

    @Test
    void overlapAboveThreshold_matches_greedyOneToOne() {
        ProcessSuggestion oldProcurement = suggestion("old-proc", "Request", "Approval", "Invoice");
        ProcessSuggestion oldRecruiting = suggestion("old-rec", "Application", "Interview", "Offer");
        // Fresh procurement gained a step (3/4 overlap = 0.75); fresh recruiting unchanged.
        ProcessSuggestion newProcurement = suggestion("new-proc", "Request", "Approval", "Manager Review", "Invoice");
        ProcessSuggestion newRecruiting = suggestion("new-rec", "Application", "Interview", "Offer");

        Map<String, ProcessIdentityResolver.Match> matches = ProcessIdentityResolver.resolve(
                List.of(newProcurement, newRecruiting), List.of(oldProcurement, oldRecruiting),
                List.of(), 0.5);

        assertEquals("old-proc", matches.get("new-proc").predecessor().getId());
        assertEquals("old-rec", matches.get("new-rec").predecessor().getId());
        assertEquals(0.75, matches.get("new-proc").jaccard(), 1e-9);
        assertEquals(1.0, matches.get("new-rec").jaccard(), 1e-9);
    }

    @Test
    void aliasMerges_bridgeRenamedActivities() {
        // The predecessor was mined before the embedder unified 'Bill' → 'Invoice'.
        ProcessSuggestion old = suggestion("old", "Request", "Approval", "Bill");
        ProcessSuggestion fresh = suggestion("new", "Request", "Approval", "Invoice");
        List<ActivityAliasUnifier.Merge> merges =
                List.of(new ActivityAliasUnifier.Merge("Bill", "Invoice", 0.97));

        Map<String, ProcessIdentityResolver.Match> without = ProcessIdentityResolver.resolve(
                List.of(fresh), List.of(old), List.of(), 0.75);
        Map<String, ProcessIdentityResolver.Match> with = ProcessIdentityResolver.resolve(
                List.of(fresh), List.of(old), merges, 0.75);

        assertFalse(without.containsKey("new"), "2/4 overlap misses without the merge");
        assertTrue(with.containsKey("new"), "the alias merge must bridge the rename");
        assertEquals(1.0, with.get("new").jaccard(), 1e-9);
    }

    @Test
    void belowThreshold_makesNoIdentityClaim_andSplitKeepsBestHalf() {
        ProcessSuggestion old = suggestion("old", "A", "B", "C", "D");
        // The cluster split: one half keeps 3 of the activities, the other only 1.
        ProcessSuggestion big = suggestion("big", "A", "B", "C");
        ProcessSuggestion small = suggestion("small", "D", "X", "Y");

        Map<String, ProcessIdentityResolver.Match> matches = ProcessIdentityResolver.resolve(
                List.of(small, big), List.of(old), List.of(), 0.5);

        assertTrue(matches.containsKey("big"), "the closest half inherits the identity");
        assertFalse(matches.containsKey("small"),
                "the far half (1/6 overlap) mints fresh identity instead");
    }
}
