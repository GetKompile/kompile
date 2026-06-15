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

package ai.kompile.core.graphrag;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.model.schema.SchemaEnforcementMode;
import ai.kompile.core.retrievers.RetrievedDoc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Delegate interface for running graph extraction in an isolated subprocess.
 *
 * <p>When the main orchestrator process has this delegate available, graph
 * extraction operations (LLM entity/relationship extraction, matrix graph
 * algorithms) are run in a separate process to prevent CUDA/ND4J native
 * crashes from affecting the orchestrator.</p>
 *
 * <p>Implementations are provided by {@code GraphSubprocessLauncher} in
 * kompile-app-main. The interface lives in kompile-app-core so that
 * modules like kompile-crawl-graph can reference it without depending
 * on kompile-app-main.</p>
 */
public interface GraphSubprocessDelegate {

    /**
     * Run graph extraction in an isolated subprocess.
     *
     * @param docs               Documents to extract from
     * @param entityTypes        Entity types to extract (empty = all)
     * @param relationshipTypes  Relationship types to extract (empty = all)
     * @param llmProvider        LLM provider name
     * @param llmModelName       LLM model name (null = default)
     * @param llmTemperature     LLM temperature
     * @param llmMaxTokens       LLM max tokens
     * @param customPrompt       Custom extraction prompt (null = default)
     * @param schemaEnforcementMode Schema enforcement mode
     * @param minConfidence      Minimum confidence threshold
     * @param batchSize          Batch size for processing
     * @param skipEmbedding      Whether to skip embedding generation
     * @param runMatrixAlgorithms Whether to run PageRank/HITS/etc.
     * @param factSheetId        Fact sheet ID for scoping (nullable)
     * @param collectionName     Collection name (nullable)
     * @return Future with the extraction result graph
     */
    CompletableFuture<SubprocessGraphResult> extractInSubprocess(
            List<RetrievedDoc> docs,
            List<String> entityTypes,
            List<String> relationshipTypes,
            String llmProvider,
            String llmModelName,
            double llmTemperature,
            int llmMaxTokens,
            String customPrompt,
            SchemaEnforcementMode schemaEnforcementMode,
            double minConfidence,
            int batchSize,
            boolean skipEmbedding,
            boolean runMatrixAlgorithms,
            Long factSheetId,
            String collectionName);

    /**
     * Check if the subprocess delegate is available and functional.
     */
    boolean isAvailable();

    /**
     * Result of subprocess graph extraction.
     */
    record SubprocessGraphResult(
            Graph graph,
            int entitiesExtracted,
            int relationshipsExtracted,
            int documentsProcessed,
            long extractionDurationMs,
            long matrixAlgorithmDurationMs
    ) {}
}
