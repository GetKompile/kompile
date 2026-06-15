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

package ai.kompile.knowledgegraph.resolution;

import ai.kompile.core.graphrag.format.GraphExtractionSchema.ExtractedEntity;

import java.util.List;
import java.util.Map;

/**
 * Result of entity merge operation.
 *
 * @param mergedEntities the deduplicated list of entities
 * @param idMapping      mapping from original entity IDs to their canonical (merged) entity IDs
 */
public record EntityMergeResult(
        List<ExtractedEntity> mergedEntities,
        Map<String, String> idMapping
) {
}
