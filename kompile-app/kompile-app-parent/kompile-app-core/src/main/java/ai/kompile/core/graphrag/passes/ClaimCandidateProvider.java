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

package ai.kompile.core.graphrag.passes;

import ai.kompile.core.graphrag.passes.ExtractionCandidates.ClaimCandidate;

import java.util.List;

/**
 * Engine-side retrieval of claims the KB already holds about a proposed relation.
 *
 * <p>Lets pass 5 decide between attaching evidence to an existing claim, opening a new one, or
 * flagging a conflict — instead of blindly re-asserting a duplicate on every chunk.</p>
 */
@FunctionalInterface
public interface ClaimCandidateProvider {

    /**
     * Returns at most {@code limit} claims related to the given triple, best first.
     *
     * @param subjectEntityId resolved subject entity id
     * @param predicate       proposed relation type
     * @param objectEntityId  resolved object entity id
     * @param context         version-pinned pass context
     * @param limit           hard upper bound on returned candidates
     */
    List<ClaimCandidate> candidatesFor(String subjectEntityId, String predicate,
                                       String objectEntityId, PassContext context, int limit);

    /** Provider that offers nothing, so every relation is treated as a fresh claim. */
    static ClaimCandidateProvider none() {
        return (subjectEntityId, predicate, objectEntityId, context, limit) -> List.of();
    }
}
