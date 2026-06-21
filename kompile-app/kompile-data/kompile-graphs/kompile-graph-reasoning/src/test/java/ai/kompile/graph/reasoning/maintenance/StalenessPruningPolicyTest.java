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
package ai.kompile.graph.reasoning.maintenance;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StalenessPruningPolicyTest {

    private static final Instant CUTOFF = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    void emptyGraphProducesEmptyResult() {
        PruneResult result = new StalenessPruningPolicy(CUTOFF).evaluate(new MutableReasoningGraph());
        assertTrue(result.isEmpty());
    }

    @Test
    void entityWithNoTimestampIsNeverSelected() {
        MutableReasoningGraph graph = new MutableReasoningGraph();
        // SimpleGraphEntity.of(id) leaves timestamp null
        graph.addEntity(SimpleGraphEntity.of("timeless"));

        PruneResult result = new StalenessPruningPolicy(CUTOFF).evaluate(graph);

        assertFalse(result.entityIds().contains("timeless"),
                "entity with null timestamp must never be selected");
    }

    @Test
    void entityBeforeCutoffIsSelected() {
        Instant beforeCutoff = CUTOFF.minus(1, ChronoUnit.DAYS);
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entityWithTimestamp("old", beforeCutoff));

        PruneResult result = new StalenessPruningPolicy(CUTOFF).evaluate(graph);

        assertTrue(result.entityIds().contains("old"),
                "entity before cutoff must be selected");
    }

    @Test
    void entityAtExactCutoffIsKept() {
        // isBefore is strict: exactly at cutoff → NOT selected
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entityWithTimestamp("exact", CUTOFF));

        PruneResult result = new StalenessPruningPolicy(CUTOFF).evaluate(graph);

        assertFalse(result.entityIds().contains("exact"),
                "entity at exactly the cutoff is NOT stale (strict before)");
    }

    @Test
    void entityAfterCutoffIsKept() {
        Instant afterCutoff = CUTOFF.plus(1, ChronoUnit.DAYS);
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entityWithTimestamp("fresh", afterCutoff));

        PruneResult result = new StalenessPruningPolicy(CUTOFF).evaluate(graph);

        assertFalse(result.entityIds().contains("fresh"),
                "entity after cutoff must not be selected");
    }

    @Test
    void mixedGraphSelectsOnlyStaleEntities() {
        Instant beforeCutoff = CUTOFF.minus(10, ChronoUnit.DAYS);
        Instant afterCutoff = CUTOFF.plus(10, ChronoUnit.DAYS);

        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entityWithTimestamp("stale", beforeCutoff));
        graph.addEntity(entityWithTimestamp("fresh", afterCutoff));
        graph.addEntity(SimpleGraphEntity.of("timeless")); // no timestamp

        PruneResult result = new StalenessPruningPolicy(CUTOFF).evaluate(graph);

        assertTrue(result.entityIds().contains("stale"));
        assertFalse(result.entityIds().contains("fresh"));
        assertFalse(result.entityIds().contains("timeless"));
        assertEquals(1, result.entityIds().size());
    }

    @Test
    void reasonStringMentionsStaleAndTimestamp() {
        Instant ts = CUTOFF.minus(5, ChronoUnit.DAYS);
        MutableReasoningGraph graph = new MutableReasoningGraph();
        graph.addEntity(entityWithTimestamp("e1", ts));

        PruneResult result = new StalenessPruningPolicy(CUTOFF).evaluate(graph);

        String reason = result.entityReason("e1");
        assertNotNull(reason);
        assertTrue(reason.contains("stale"), "reason should mention 'stale'");
    }

    @Test
    void nullCutoffThrows() {
        assertThrows(NullPointerException.class, () -> new StalenessPruningPolicy(null));
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private static GraphEntity entityWithTimestamp(String id, Instant ts) {
        return new SimpleGraphEntity(id, "X", id, 1.0, 1.0, Set.of(), null, ts, Map.of());
    }
}
