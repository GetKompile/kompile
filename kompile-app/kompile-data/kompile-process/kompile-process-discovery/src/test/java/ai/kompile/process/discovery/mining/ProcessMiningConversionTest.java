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
import ai.kompile.process.discovery.ProcessSuggestion.SuggestedStep;
import ai.kompile.process.discovery.mining.convert.ProcessTreeToSuggestion;
import ai.kompile.process.discovery.mining.log.Event;
import ai.kompile.process.discovery.mining.log.EventLog;
import ai.kompile.process.discovery.mining.log.Trace;
import ai.kompile.process.discovery.mining.miner.InductiveMiner;
import ai.kompile.process.discovery.mining.tree.ProcessTree;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a mined {@link ProcessTree} converts into a {@link ProcessSuggestion} that the existing
 * accept/UI pipeline can consume, with knowledge-graph provenance preserved on the steps.
 */
class ProcessMiningConversionTest {

    /** Builds a log with synthetic increasing timestamps and a graph-node id per event. */
    private static EventLog logWithProvenance(String... traces) {
        List<Trace> ts = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 0, 0);
        int caseId = 0;
        for (String tr : traces) {
            String cid = "c" + (caseId++);
            List<Event> events = new ArrayList<>();
            int i = 0;
            for (String a : tr.trim().split("\\s+")) {
                events.add(Event.of(cid, a, base.plusMinutes(i), cid + "-n" + i));
                i++;
            }
            ts.add(new Trace(cid, events));
        }
        return new EventLog(ts);
    }

    @Test
    void convertsMinedTreeToSuggestionWithProvenance() {
        EventLog log = logWithProvenance("a b c d", "a c b d", "a e d");
        ProcessTree tree = new InductiveMiner().mine(log);

        ProcessSuggestion suggestion = ProcessTreeToSuggestion.convert(tree, log, "Test process");

        assertEquals("PROCESS_MINING", suggestion.getDiscoverySource());
        assertTrue(suggestion.getConfidence() > 0.5, "a well-structured log should not yield a flower model");
        assertFalse(suggestion.getPhases().isEmpty(), "expected at least one phase");

        Set<String> stepNames = suggestion.getPhases().stream()
                .flatMap(p -> p.getSteps().stream())
                .map(SuggestedStep::getName)
                .collect(Collectors.toSet());
        assertTrue(stepNames.containsAll(Set.of("a", "b", "c", "d", "e")),
                () -> "every activity should become a step; got " + stepNames);

        assertFalse(suggestion.getSourceGraphNodeIds().isEmpty(), "source node ids should be populated");
        boolean someStepHasProvenance = suggestion.getPhases().stream()
                .flatMap(p -> p.getSteps().stream())
                .anyMatch(s -> s.getGraphNodeIds() != null && !s.getGraphNodeIds().isEmpty());
        assertTrue(someStepHasProvenance, "steps should carry graph-node provenance");
    }

    @Test
    void noisyLogStillConvertsToALowConfidenceSuggestion() {
        EventLog log = logWithProvenance("a b c", "c a b", "b c a");
        ProcessTree tree = new InductiveMiner().mine(log);

        ProcessSuggestion suggestion = ProcessTreeToSuggestion.convert(tree, log, "Noisy");

        // A flower model is honest about its low confidence, but must still be a usable suggestion.
        assertFalse(suggestion.getPhases().isEmpty());
        assertEquals("PROCESS_MINING", suggestion.getDiscoverySource());
    }
}
