/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.graph.reasoning.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalGraphLearningResolutionPipelineTest {

    @Test
    void ordersLearningResolutionAndCanonicalLearning() throws Exception {
        List<String> events = new ArrayList<>();

        FinalGraphLearningResolutionPipeline.Result<Scope, String, String> result =
                FinalGraphLearningResolutionPipeline.run(
                        new Scope(0),
                        true,
                        (scope, phase) -> {
                            events.add("learn:" + phase + ":" + scope.version());
                            return new FinalGraphLearningResolutionPipeline.LearningOutcome<>(
                                    new Scope(scope.version() + 1), phase.name());
                        },
                        scope -> {
                            events.add("resolve:" + scope.version());
                            return new FinalGraphLearningResolutionPipeline.ResolutionOutcome<>(
                                    new Scope(scope.version() + 1), 2, "resolved");
                        });

        assertEquals(List.of(
                "learn:PRE_RESOLUTION:0",
                "resolve:1",
                "learn:POST_CANONICALIZATION:2"), events);
        assertEquals(3, result.scope().version());
        assertEquals(2, result.entitiesMerged());
        assertTrue(result.graphChanged());
        assertTrue(result.canonicalLearningRan());
    }

    @Test
    void phasePolicyCanReuseFreshPreResolutionModelsButStillRefreshCanonicalGraph()
            throws Exception {
        List<FinalGraphLearningResolutionPipeline.LearningPhase> learned = new ArrayList<>();

        FinalGraphLearningResolutionPipeline.Result<Scope, String, String> result =
                FinalGraphLearningResolutionPipeline.run(
                        new Scope(0),
                        phase -> phase
                                == FinalGraphLearningResolutionPipeline.LearningPhase.POST_CANONICALIZATION,
                        (scope, phase) -> {
                            learned.add(phase);
                            return new FinalGraphLearningResolutionPipeline.LearningOutcome<>(
                                    scope, phase.name());
                        },
                        scope -> new FinalGraphLearningResolutionPipeline.ResolutionOutcome<>(
                                scope, 0, true, "type-corrected"));

        assertEquals(List.of(
                FinalGraphLearningResolutionPipeline.LearningPhase.POST_CANONICALIZATION),
                learned);
        assertTrue(result.canonicalLearningRan());
        assertEquals("type-corrected", result.resolution());
    }

    private record Scope(int version) {
    }
}
