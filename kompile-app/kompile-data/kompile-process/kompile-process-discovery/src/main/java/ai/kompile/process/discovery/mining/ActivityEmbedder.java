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

package ai.kompile.process.discovery.mining;

import java.util.List;
import java.util.Map;

/**
 * SPI for embedding activity labels — the hook that turns {@code ProcessHybridActivation}'s
 * semantic blending on. The mining module stays infra-free: an application module provides an
 * implementation backed by its embedding model (app-main wraps the {@code EmbeddingModel} bean),
 * and {@code MiningProcessDiscoveryService} picks it up when wired.
 *
 * <p>Batch-shaped on purpose (one call per mine, all labels at once) so implementations ride the
 * shared inference batch planner instead of embedding label-by-label.
 */
@FunctionalInterface
public interface ActivityEmbedder {

    /** One activity label plus graph-derived occurrence context from its source events. */
    record ActivityContext(String label, List<String> contexts) {
        public ActivityContext {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("activity label must be non-blank");
            }
            contexts = contexts == null ? List.of() : List.copyOf(contexts);
        }
    }

    /**
     * Embed activity display labels.
     *
     * @param activityLabels distinct labels to embed
     * @return label → dense vector; labels the implementation could not embed are ABSENT (never
     *         empty vectors). An empty map disables semantic blending for the mine — the honest
     *         "no artifact yet" degradation.
     */
    Map<String, double[]> embed(List<String> activityLabels);

    /**
     * Embed labels with graph-derived occurrence context. Existing implementations remain valid:
     * the default delegates to the label-only batch method. Model-backed implementations can use
     * the context to disambiguate short labels shared by unrelated documents or workflows.
     */
    default Map<String, double[]> embedContexts(List<ActivityContext> activities) {
        if (activities == null || activities.isEmpty()) {
            return Map.of();
        }
        return embed(activities.stream().map(ActivityContext::label).distinct().toList());
    }

    /** Stable provenance label carried into process reasoning evidence. */
    default String embeddingSource() {
        return getClass().getSimpleName();
    }

    /** Optional model identifier for diagnostics and reproducibility. */
    default String embeddingModel() {
        return null;
    }
}
