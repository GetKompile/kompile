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

package ai.kompile.staging.staging;

import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.ModelStatus;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Deploy trained model artifacts (PSL weights, MEBN parameters, KGE checkpoints) through
 * the staging lifecycle and bind them to a specific (projectId, graphId) scope.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li><b>stage</b> — copy the artifact into the staging models directory and register
 *       it with {@link ModelStatus#STAGED}.  Versioning is handled by appending a
 *       timestamp suffix to the modelId.</li>
 *   <li><b>activate</b> — promote the staged entry to {@link ModelStatus#ACTIVE} for its
 *       (projectId, graphId, type) scope; the previously active model (if any) is
 *       demoted to STAGED so it can serve as a rollback target.</li>
 *   <li><b>deploy</b> — convenience combination of stage + activate in one call.</li>
 *   <li><b>rollback</b> — re-activate a prior (STAGED) version.</li>
 * </ol>
 *
 * <h3>Cascade note (follow-up)</h3>
 * Wiring the knowledge-graph training pipeline to call
 * {@link #deploy(String, String, String, ModelType, Path, ModelMetadata)} automatically
 * upon training completion is intentionally deferred.  See design doc
 * {@code docs/architecture/learning-loop-deployment-rules-process-integration-design.md}
 * section D1-A for the planned {@code TrainingService} hook.
 */
@Service
public class GraphScopedDeployService {

    private static final Logger log = LoggerFactory.getLogger(GraphScopedDeployService.class);

    private final RegistryService registryService;

    @Autowired
    public GraphScopedDeployService(RegistryService registryService) {
        this.registryService = registryService;
    }

    // ==================== Public API ====================

    /**
     * Stage + activate a model artifact for a (project, graph) in one step.
     * <p>
     * The artifact file at {@code artifactPath} is copied into the models directory
     * under a versioned sub-path, registered, and immediately activated.
     *
     * @param baseModelId   logical name of the model family (e.g. "rotate-e")
     * @param projectId     owning project
     * @param graphId       target named-graph / fact-sheet id
     * @param type          model type (e.g. {@link ModelType#KGE})
     * @param artifactPath  path to the trained artifact on disk
     * @param metadata      optional model metadata (may be {@code null})
     * @return the newly created and activated {@link ModelEntry}
     * @throws IOException  if the artifact cannot be copied into the staging area
     */
    public ModelEntry deploy(String baseModelId, String projectId, String graphId,
                             ModelType type, Path artifactPath, ModelMetadata metadata)
            throws IOException {

        ModelEntry staged = stage(baseModelId, projectId, graphId, type, artifactPath, metadata);
        boolean activated = registryService.activateForGraph(staged.getModelId());
        if (!activated) {
            throw new IllegalStateException(
                    "Failed to activate model " + staged.getModelId() +
                    " for project=" + projectId + " graph=" + graphId);
        }
        log.info("Deployed and activated {} for project={} graph={}", staged.getModelId(), projectId, graphId);
        return registryService.getModel(staged.getModelId())
                .orElse(staged);
    }

    /**
     * Stage an artifact as a versioned STAGED entry without activating it.
     * Useful when you want to inspect/validate before making it active.
     *
     * @param baseModelId   logical name of the model family
     * @param projectId     owning project
     * @param graphId       target named-graph / fact-sheet id
     * @param type          model type
     * @param artifactPath  path to the trained artifact on disk
     * @param metadata      optional model metadata
     * @return the newly created {@link ModelEntry} with status STAGED
     * @throws IOException  if the artifact cannot be copied
     */
    public ModelEntry stage(String baseModelId, String projectId, String graphId,
                            ModelType type, Path artifactPath, ModelMetadata metadata)
            throws IOException {

        if (!Files.exists(artifactPath)) {
            throw new IllegalArgumentException("Artifact not found: " + artifactPath);
        }

        // Build a timestamped versioned ID to avoid collisions
        String versionedId = buildVersionedId(baseModelId, projectId, graphId);

        // Copy the artifact into the models directory
        Path dest = resolveArtifactDest(versionedId, type, artifactPath);
        Files.createDirectories(dest.getParent());
        Files.copy(artifactPath, dest, StandardCopyOption.REPLACE_EXISTING);
        log.info("Copied artifact {} -> {}", artifactPath, dest);
        copySidecars(artifactPath, dest.getParent());

        // Build relative path for the registry
        Path modelsRoot = registryService.getModelDir();
        String relPath = modelsRoot.relativize(dest.getParent()).toString();

        ModelEntry entry = ModelEntry.builder()
                .modelId(versionedId)
                .type(type)
                .path(relPath)
                .modelFile(dest.getFileName().toString())
                .projectId(projectId)
                .graphId(graphId)
                .status(ModelStatus.STAGED)
                .promotedAt(Instant.now().toString())
                .metadata(metadata != null ? metadata : ModelMetadata.builder().build())
                .build();

        registryService.addModel(entry);
        log.info("Staged model {} (project={} graph={} type={})", versionedId, projectId, graphId, type);
        return entry;
    }

    /**
     * Activate a previously staged model entry for its (project, graph) scope.
     * The current active model (if any) is demoted to STAGED and can be used for rollback.
     *
     * @param modelId  the versioned model ID to activate
     * @return the activated {@link ModelEntry}
     * @throws IllegalArgumentException if the model is not found or not graph-scoped
     */
    public ModelEntry activate(String modelId) {
        ModelEntry entry = registryService.getModel(modelId)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + modelId));
        if (!entry.isGraphScoped()) {
            throw new IllegalArgumentException(
                    "Model " + modelId + " is not graph-scoped; use StagingController#activateModel instead");
        }
        boolean ok = registryService.activateForGraph(modelId);
        if (!ok) {
            throw new IllegalStateException("activateForGraph returned false for " + modelId);
        }
        return registryService.getModel(modelId).orElse(entry);
    }

    /**
     * Roll back the active model for the same (project, graph, type) scope to a prior
     * version identified by {@code targetModelId}.
     *
     * @param targetModelId  the STAGED model to restore as ACTIVE
     * @return the newly re-activated {@link ModelEntry}
     */
    public ModelEntry rollback(String targetModelId) {
        return activate(targetModelId);
    }

    /**
     * Find the currently ACTIVE model for a (project, graph) + optional type.
     *
     * @param projectId  project identifier
     * @param graphId    named-graph identifier
     * @param type       model type filter, or {@code null} for any type
     * @return the active entry, if present
     */
    public Optional<ModelEntry> findActive(String projectId, String graphId, ModelType type) {
        return registryService.findActiveByProjectAndGraph(projectId, graphId, type);
    }

    /**
     * List all model versions (any status) scoped to the given (project, graph).
     */
    public List<ModelEntry> listVersions(String projectId, String graphId) {
        return registryService.getModelsByProjectAndGraph(projectId, graphId);
    }

    // ==================== Helpers ====================

    /**
     * Build a unique versioned model ID from the base name, project, graph, and timestamp.
     * Format: {@code <baseModelId>__<projectId>__<graphId>__<epochMillis>}
     */
    static String buildVersionedId(String baseModelId, String projectId, String graphId) {
        String safePid = sanitize(projectId);
        String safeGid = sanitize(graphId);
        return baseModelId + "__" + safePid + "__" + safeGid + "__" + System.currentTimeMillis();
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    /**
     * Copy sidecar files that belong to the staged artifact: regular files in the same
     * source directory whose name is {@code <primaryFileName>.<suffix>} (e.g.
     * {@code model.sdz} → {@code model.sdz.vocab.json}, which
     * {@code SameDiffKgeModel.loadEmbeddings} requires next to the archive).
     *
     * <p>Only the strict filename-prefix rule is applied — never the whole source
     * directory — because artifacts may be staged out of shared directories (e.g. the
     * PSL weight store keeps every version side by side) where unrelated siblings must
     * not be swept into a single registry entry.</p>
     */
    private static void copySidecars(Path artifactPath, Path destDir) throws IOException {
        Path srcDir = artifactPath.getParent();
        if (srcDir == null || !Files.isDirectory(srcDir)) {
            return;
        }
        String prefix = artifactPath.getFileName().toString() + ".";
        try (var siblings = Files.list(srcDir)) {
            for (Path sibling : (Iterable<Path>) siblings::iterator) {
                String name = sibling.getFileName().toString();
                if (Files.isRegularFile(sibling) && name.startsWith(prefix)) {
                    Files.copy(sibling, destDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    log.info("Copied sidecar {} -> {}", sibling, destDir.resolve(name));
                }
            }
        }
    }

    /**
     * Resolve the destination path for the artifact file inside the models root.
     * Path structure: {@code <modelsRoot>/graph-scoped/<type>/<versionedId>/<originalFilename>}
     */
    private Path resolveArtifactDest(String versionedId, ModelType type, Path artifactPath) {
        Path modelsRoot = registryService.getModelDir();
        String typeName = type != null ? type.getValue() : "unknown";
        return modelsRoot
                .resolve("graph-scoped")
                .resolve(typeName)
                .resolve(versionedId)
                .resolve(artifactPath.getFileName().toString());
    }
}
