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

package ai.kompile.core.graphrag.maintenance.model;

import java.util.List;

public record Contradiction(
    String entityIdA,
    String entityIdB,
    String existingFact,
    String newFact,
    String sourceDocExisting,
    String sourceDocNew,
    ContradictionType type,
    Resolution resolution,
    String contradictionId,
    List<String> conflictingEdgeIds,
    List<String> candidateStaleEdgeIds,
    String predicate,
    Double severity,
    Double recencyScore,
    String recommendedEdgeId,
    String recommendedAction,
    String llmRecommendation,
    String details,
    Double probabilityA,
    Double probabilityB,
    Double priorA,
    Double priorB,
    Double posteriorEntropy,
    Double incompatibilityScore,
    String uncertaintyKind
) {
    public Contradiction(
        String entityIdA,
        String entityIdB,
        String existingFact,
        String newFact,
        String sourceDocExisting,
        String sourceDocNew,
        ContradictionType type,
        Resolution resolution,
        String contradictionId,
        List<String> conflictingEdgeIds,
        List<String> candidateStaleEdgeIds,
        String predicate,
        Double severity,
        Double recencyScore,
        String recommendedEdgeId,
        String recommendedAction,
        String llmRecommendation,
        String details
    ) {
        this(
            entityIdA,
            entityIdB,
            existingFact,
            newFact,
            sourceDocExisting,
            sourceDocNew,
            type,
            resolution,
            contradictionId,
            conflictingEdgeIds,
            candidateStaleEdgeIds,
            predicate,
            severity,
            recencyScore,
            recommendedEdgeId,
            recommendedAction,
            llmRecommendation,
            details,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public Contradiction(
        String entityIdA,
        String entityIdB,
        String existingFact,
        String newFact,
        String sourceDocExisting,
        String sourceDocNew,
        ContradictionType type,
        Resolution resolution
    ) {
        this(
            entityIdA,
            entityIdB,
            existingFact,
            newFact,
            sourceDocExisting,
            sourceDocNew,
            type,
            resolution,
            null,
            List.of(),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    public Contradiction {
        conflictingEdgeIds = conflictingEdgeIds == null ? List.of() : List.copyOf(conflictingEdgeIds);
        candidateStaleEdgeIds = candidateStaleEdgeIds == null ? List.of() : List.copyOf(candidateStaleEdgeIds);
    }

    public enum ContradictionType {
        CONFLICTING_RELATIONSHIP,
        CONFLICTING_TYPE,
        CONFLICTING_PROPERTY,
        TEMPORAL_SUPERSESSION,
        PROBABILISTIC_TENSION
    }

    public enum Resolution {
        AUTO_NEWER_WINS,
        AUTO_HIGHER_CONFIDENCE,
        AUTO_LLM_JUDGE,
        USER_RESOLVED,
        NEEDS_REVIEW
    }
}
