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

import ai.kompile.core.graphrag.passes.ExtractionCandidates.EntityCandidate;

import java.util.List;

/**
 * Engine-side retrieval of entity resolution candidates for a mention.
 *
 * <p>Implementations run deterministic matching (lexical normalisation, alias tables, embeddings,
 * identifier joins) and return a ranked, bounded list. The pass then only <em>chooses</em>, which
 * is what keeps identity recall out of the model's hands.</p>
 *
 * <p>Declared here in {@code kompile-app-core} so the pass pipeline stays dependency-free; the
 * graph-backed implementation lives in {@code kompile-knowledge-graph}, which has both the core
 * schema and the reasoning engines.</p>
 */
@FunctionalInterface
public interface EntityCandidateProvider {

    /**
     * Returns at most {@code limit} candidates for {@code mentionText}, best first.
     *
     * @param mentionText the surface form as it appears in the source
     * @param typeHint    expected entity type, or {@code null} when unknown
     * @param context     version-pinned pass context the candidates must be read at
     * @param limit       hard upper bound on returned candidates
     */
    List<EntityCandidate> candidatesFor(String mentionText, String typeHint, PassContext context,
                                        int limit);

    /** Provider that offers nothing, so every mention becomes a provisional entity. */
    static EntityCandidateProvider none() {
        return (mentionText, typeHint, context, limit) -> List.of();
    }
}
