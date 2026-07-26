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

import ai.kompile.core.graphrag.passes.ExtractionCandidates.RelationCandidate;

import java.util.List;

/**
 * Engine-side retrieval of admissible relation types for a resolved endpoint pair.
 *
 * <p>Type admissibility is a schema question, so it is answered by the schema — the pass picks
 * among types the ontology already permits between these endpoints, or reports a schema gap.</p>
 */
@FunctionalInterface
public interface RelationCandidateProvider {

    /**
     * Returns at most {@code limit} relation types admissible from {@code sourceType} to
     * {@code targetType}, best first.
     *
     * @param sourceType resolved source entity type, or {@code null} when unknown
     * @param targetType resolved target entity type, or {@code null} when unknown
     * @param context    version-pinned pass context
     * @param limit      hard upper bound on returned candidates
     */
    List<RelationCandidate> candidatesFor(String sourceType, String targetType, PassContext context,
                                          int limit);

    /** Provider backed by a fixed type list, ignoring endpoint types. */
    static RelationCandidateProvider ofTypes(List<String> types) {
        List<RelationCandidate> fixed = types == null ? List.of()
                : types.stream().filter(t -> t != null && !t.isBlank())
                        .map(t -> RelationCandidate.of(t, null)).toList();
        return (sourceType, targetType, context, limit) ->
                fixed.size() <= limit ? fixed : fixed.subList(0, Math.max(0, limit));
    }

    /** Provider that offers nothing, so relation selection always reports a schema gap. */
    static RelationCandidateProvider none() {
        return (sourceType, targetType, context, limit) -> List.of();
    }
}
