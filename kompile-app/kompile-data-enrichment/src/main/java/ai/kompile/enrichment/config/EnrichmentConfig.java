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
package ai.kompile.enrichment.config;

import ai.kompile.enrichment.api.EnrichmentPhase;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class EnrichmentConfig {

    @Builder.Default
    private boolean autoTriggerAfterCrawl = false;

    @Builder.Default
    private List<EnrichmentPhase> enabledPhases = List.of(
            EnrichmentPhase.CLEAN,
            EnrichmentPhase.ORGANIZE,
            EnrichmentPhase.TAXONOMY,
            EnrichmentPhase.SEARCH_INDEX
    );

    @Builder.Default
    private double deduplicationJaccardThreshold = 0.85;

    @Builder.Default
    private int deduplicationShingleSize = 5;

    @Builder.Default
    private double pruneConfidenceThreshold = 0.3;

    @Builder.Default
    private double pruneEdgeWeightThreshold = 0.25;

    @Builder.Default
    private int taxonomyMaxDepth = 3;

    @Builder.Default
    private int taxonomyMinEntityTypeCount = 2;

    @Builder.Default
    private String llmProvider = "default";

    @Builder.Default
    private boolean exportSchemaPreset = true;

    @Builder.Default
    private boolean generateProcessDefinitions = true;
}
