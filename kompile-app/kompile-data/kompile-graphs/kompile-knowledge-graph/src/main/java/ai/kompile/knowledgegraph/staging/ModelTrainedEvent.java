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
package ai.kompile.knowledgegraph.staging;

import org.springframework.context.ApplicationEvent;

import java.nio.file.Path;

/**
 * Spring event published after a reasoning or embedding model finishes training and
 * has persisted its artifact to disk.
 *
 * <p>The event carries enough context for a downstream listener (e.g.
 * {@code ModelDeploymentHook} in {@code kompile-app-main}) to stage and activate
 * the artifact in the model-staging registry for the right {@code (projectId, graphId)}
 * scope — without the knowledge-graph module needing to depend on model-staging.</p>
 *
 * <h3>Published by</h3>
 * <ul>
 *   <li>{@link ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator}
 *       — STEP 5b (PSL) and STEP 9 (MEBN) after {@code cascadeWeightStore.save} /
 *       {@link ai.kompile.knowledgegraph.persistence.MebnWeightPersistenceAdapter#persist}
 *       succeed.</li>
 *   <li>{@link ai.kompile.knowledgegraph.embedding.service.KGEmbeddingJobService}
 *       — after {@code storeEmbeddings} completes successfully.</li>
 * </ul>
 *
 * <h3>No circular dependency</h3>
 * This class lives in {@code kompile-knowledge-graph}. The listener that calls
 * {@code GraphScopedDeployService} lives in {@code kompile-app-main}, which already
 * depends on both {@code kompile-knowledge-graph} and {@code kompile-model-staging}.
 * {@code kompile-knowledge-graph} therefore does NOT need a compile-time dependency on
 * {@code kompile-model-staging}.
 */
public class ModelTrainedEvent extends ApplicationEvent {

    /**
     * Logical model type string — mirrors {@code ModelType} values in kompile-model-manager
     * but expressed as a plain {@code String} to keep this class free of model-staging
     * compile dependencies.
     * Typical values: {@code "psl"}, {@code "mebn"}, {@code "kge"}.
     */
    private final String modelType;

    /**
     * The fact-sheet (named-graph) ID for which training ran.
     * Used as {@code graphId} in the staging registry.
     */
    private final long factSheetId;

    /**
     * Path to the artifact file that was just written to disk.
     * Must exist and be readable at the time the listener processes the event.
     */
    private final Path artifactPath;

    /**
     * Optional logical name for the model family (e.g. "psl-cascade", "mebn-grounding",
     * "rotate-e"). Used as the {@code baseModelId} prefix in the staging registry.
     * Defaults to {@code modelType} when not set.
     */
    private final String baseModelId;

    /**
     * Create a {@code ModelTrainedEvent}.
     *
     * @param source       the object publishing the event (e.g. the orchestrator/job service)
     * @param modelType    model type string ("psl", "mebn", or "kge")
     * @param factSheetId  fact-sheet / named-graph identifier
     * @param artifactPath absolute path to the artifact file on disk
     * @param baseModelId  logical model family name; {@code null} falls back to {@code modelType}
     */
    public ModelTrainedEvent(Object source, String modelType, long factSheetId,
                             Path artifactPath, String baseModelId) {
        super(source);
        this.modelType    = modelType;
        this.factSheetId  = factSheetId;
        this.artifactPath = artifactPath;
        this.baseModelId  = (baseModelId != null && !baseModelId.isBlank()) ? baseModelId : modelType;
    }

    /**
     * Convenience constructor when {@code baseModelId} == {@code modelType}.
     */
    public ModelTrainedEvent(Object source, String modelType, long factSheetId, Path artifactPath) {
        this(source, modelType, factSheetId, artifactPath, modelType);
    }

    public String getModelType()    { return modelType; }
    public long   getFactSheetId()  { return factSheetId; }
    public Path   getArtifactPath() { return artifactPath; }
    public String getBaseModelId()  { return baseModelId; }

    @Override
    public String toString() {
        return "ModelTrainedEvent{type=" + modelType
                + ", factSheetId=" + factSheetId
                + ", artifact=" + artifactPath
                + ", baseModelId=" + baseModelId + '}';
    }
}
