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
package ai.kompile.knowledgegraph.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * JSON-serializable pointer to a trained Knowledge Graph Embedding (KGE) model.
 *
 * <p>This is a lightweight manifest stored alongside the graph; the actual model weights
 * are either in the staging registry (see {@link StagingModelArtifactBackend}) or in a
 * SameDiff checkpoint accessible via {@link ModelArtifactRouter}.</p>
 *
 * <p>Written by {@link EmbeddingModelPersistenceService#persistKgePointer} and read back
 * by {@link EmbeddingModelPersistenceService#loadKgePointer}.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KgeModelRef {

    /** Unique identifier for this model (e.g. a UUID or content hash). */
    private String modelId;

    /** Base URL of the staging registry hosting the checkpoint, if applicable. */
    private String stagingUrl;

    /** Algorithm used to train the model, e.g. {@code "RotatE"} or {@code "SGNS"}. */
    private String algorithm;

    /** Embedding dimension. */
    private int dim;

    /** Snapshot / checkpoint identifier within the staging registry. */
    private String snapshotId;

    /** ISO-8601 timestamp of when the model was trained / persisted. */
    private String trainedAt;

    /**
     * Provenance of the model: {@code "LOCAL"} for models trained in this session,
     * or {@code "HUMAN_CORRECTION"} for manually curated embeddings.
     */
    private String origin;

    /** Version string of the ND4J library used during training (e.g. {@code "1.0.0-M2.1"}). */
    private String nd4jVersion;
}
