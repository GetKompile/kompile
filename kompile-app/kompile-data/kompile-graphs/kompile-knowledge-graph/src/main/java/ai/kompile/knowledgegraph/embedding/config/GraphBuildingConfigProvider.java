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
package ai.kompile.knowledgegraph.embedding.config;

/**
 * SPI for reading and writing per-FactSheet graph-building configuration.
 *
 * <p>The concrete implementation lives in {@code kompile-app-facts}
 * ({@code GraphBuildingConfigProviderImpl}) so that JPA access is kept out
 * of the graphs subtree ("Graphs NEVER touch JPA" rule). Consumers inside
 * {@code kompile-knowledge-graph} that need per-fact-sheet config must
 * depend on this interface only.</p>
 */
public interface GraphBuildingConfigProvider {

    /**
     * Returns the graph-building configuration for the given fact sheet.
     * Falls back to {@link KGEmbeddingConfigService.GraphBuildingConfig#defaults()}
     * when the fact sheet is not found or its config is absent.
     */
    KGEmbeddingConfigService.GraphBuildingConfig getGraphBuildingConfig(Long factSheetId);

    /**
     * Persists an updated graph-building configuration for the given fact sheet
     * and returns the stored value.
     */
    KGEmbeddingConfigService.GraphBuildingConfig updateGraphBuildingConfig(
            Long factSheetId, KGEmbeddingConfigService.GraphBuildingConfig config);
}
