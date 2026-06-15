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

package ai.kompile.app.facts.service;

import lombok.Builder;
import lombok.Getter;

/**
 * Parameter object for {@link FactSheetService#updateSheet(FactSheetUpdateRequest)}.
 * Replaces the previous 19-parameter method signature.
 */
@Getter
@Builder
public class FactSheetUpdateRequest {

    // Identity
    private final Long id;

    // Basic metadata
    private final String name;
    private final String description;
    private final String color;
    private final String icon;

    // Index storage paths
    private final String vectorStorePath;
    private final String keywordIndexPath;
    /** When true, the {@code vectorStorePath} and {@code keywordIndexPath} fields are applied. */
    @Builder.Default
    private final boolean updateIndexPaths = false;

    // Retrieval / embedding configuration
    private final String embeddingModel;
    private final String embeddingModelSource;
    private final String embeddingArchiveId;

    // Reranking configuration
    private final Boolean rerankingEnabled;
    private final String rerankerType;
    private final String crossEncoderModel;
    private final String crossEncoderModelSource;
    private final String crossEncoderArchiveId;
    private final Integer rerankTopK;
    private final Double mmrLambda;

    /** When true, the model-configuration fields are applied. */
    @Builder.Default
    private final boolean updateModelConfig = false;
}
