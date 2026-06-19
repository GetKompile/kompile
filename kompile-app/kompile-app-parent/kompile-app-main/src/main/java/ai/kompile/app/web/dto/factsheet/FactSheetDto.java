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
package ai.kompile.app.web.dto.factsheet;

import java.time.Instant;

public record FactSheetDto(
    Long id,
    String name,
    String description,
    Boolean isActive,
    Long derivedFromId,
    String color,
    String icon,
    String vectorStorePath,
    String keywordIndexPath,
    // Retrieval configuration
    String embeddingModel,
    String embeddingModelSource,
    String embeddingArchiveId,
    // Model tracking - what model was actually used for indexing
    String indexedWithModel,
    Instant indexedAt,
    boolean needsReindex,
    // Reranking configuration
    Boolean rerankingEnabled,
    String rerankerType,
    String crossEncoderModel,
    String crossEncoderModelSource,
    String crossEncoderArchiveId,
    Integer rerankTopK,
    Double mmrLambda,
    // Stats
    long factCount,
    long indexedCount,
    long unindexedCount,
    long totalSizeBytes,
    Instant createdAt,
    Instant updatedAt
) {}
