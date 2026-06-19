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
package ai.kompile.core.graphrag.maintenance.model;

import java.util.List;

/**
 * Result of comparing two fact sheets' knowledge graphs: the ENTITY-name set overlap (shared /
 * only-in-A / only-in-B) plus each side's {@link GraphHealthSnapshot} so the caller can read
 * metric deltas. Entity identity is by normalized (trimmed, lower-cased) title.
 *
 * <p>The sample lists are capped (the counts are exact); they exist to make the comparison
 * inspectable without returning the full sets on large graphs.
 *
 * @param factSheetIdA   the "A" fact sheet
 * @param factSheetIdB   the "B" fact sheet
 * @param sharedEntityCount  entities present in both (by normalized name)
 * @param onlyInACount   entities only in A
 * @param onlyInBCount   entities only in B
 * @param sampleShared   capped sample of shared entity names
 * @param sampleOnlyInA  capped sample of A-only entity names
 * @param sampleOnlyInB  capped sample of B-only entity names
 * @param healthA        A's health snapshot (for metric deltas)
 * @param healthB        B's health snapshot (for metric deltas)
 */
public record GraphComparison(
        Long factSheetIdA,
        Long factSheetIdB,
        int sharedEntityCount,
        int onlyInACount,
        int onlyInBCount,
        List<String> sampleShared,
        List<String> sampleOnlyInA,
        List<String> sampleOnlyInB,
        GraphHealthSnapshot healthA,
        GraphHealthSnapshot healthB
) {}
