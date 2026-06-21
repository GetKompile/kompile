package ai.kompile.staging.staging;

import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.ModelStatus;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.modelmanager.registry.RegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lifecycle tests for per-graph model staging and activation.
 *
 * Covers:
 * <ol>
 *   <li>Stage + activate a model for a (project, graph) → ACTIVE, versioned id correct.</li>
 *   <li>Two graphs have independent active models — activating for one does not affect the other.</li>
 *   <li>Rollback: deploy v2 (demotes v1 → STAGED), then roll back to v1 (demotes v2 → STAGED).</li>
 *   <li>{@link RegistryService#findActiveByProjectAndGraph} type filter works correctly.</li>
 *   <li>Attempting to activate a non-existent model throws {@link IllegalArgumentException}.</li>
 *   <li>Attempting to activate a global (non-graph-scoped) model via the graph path throws.</li>
 * </ol>
 */
class GraphScopedModelLifecycleTest {

    @TempDir
    Path tempDir;

    private RegistryService registryService;
    private GraphScopedDeployService deployService;

    @BeforeEach
    void setUp() {
        registryService = new RegistryService(tempDir);
        deployService = new GraphScopedDeployService(registryService);
    }

    // ==================== 1. Stage + activate a model for a (project, graph) ====================

    @Test
    @DisplayName("deploy() stages and activates a model for a given (project, graph)")
    void deploy_stagesAndActivatesModel() throws IOException {
        Path artifact = createArtifact("rotate-e.fb");

        ModelEntry entry = deployService.deploy(
                "rotate-e", "proj-alpha", "graph-1",
                ModelType.KGE, artifact, null);

        // Registry entry is ACTIVE
        assertEquals(ModelStatus.ACTIVE, entry.getStatus());
        assertEquals("proj-alpha", entry.getProjectId());
        assertEquals("graph-1", entry.getGraphId());
        assertEquals(ModelType.KGE, entry.getType());

        // Versioned ID contains base name
        assertTrue(entry.getModelId().startsWith("rotate-e__"),
                "Model ID should start with base name prefix: " + entry.getModelId());

        // findActiveByProjectAndGraph resolves it
        Optional<ModelEntry> active = registryService.findActiveByProjectAndGraph("proj-alpha", "graph-1", ModelType.KGE);
        assertTrue(active.isPresent());
        assertEquals(entry.getModelId(), active.get().getModelId());

        // Artifact was physically copied
        Path destDir = tempDir.resolve("graph-scoped")
                .resolve(ModelType.KGE.getValue())
                .resolve(entry.getModelId());
        assertTrue(Files.exists(destDir.resolve("rotate-e.fb")),
                "Artifact should have been copied to " + destDir);
    }

    // ==================== 2. Two graphs have independent active models ====================

    @Test
    @DisplayName("Each graph has an independent active model — activating in one graph does not affect the other")
    void twoGraphs_haveIndependentActives() throws IOException {
        Path artifact1 = createArtifact("model-g1.fb");
        Path artifact2 = createArtifact("model-g2.fb");

        ModelEntry entryG1 = deployService.deploy(
                "psl-weights", "proj-alpha", "graph-1",
                ModelType.PSL, artifact1, null);

        ModelEntry entryG2 = deployService.deploy(
                "psl-weights", "proj-alpha", "graph-2",
                ModelType.PSL, artifact2, null);

        // Both are active in their own scope
        Optional<ModelEntry> activeG1 = registryService.findActiveByProjectAndGraph("proj-alpha", "graph-1", ModelType.PSL);
        Optional<ModelEntry> activeG2 = registryService.findActiveByProjectAndGraph("proj-alpha", "graph-2", ModelType.PSL);

        assertTrue(activeG1.isPresent(), "graph-1 should have an active model");
        assertTrue(activeG2.isPresent(), "graph-2 should have an active model");

        // They are different models
        assertNotEquals(activeG1.get().getModelId(), activeG2.get().getModelId());

        assertEquals("graph-1", activeG1.get().getGraphId());
        assertEquals("graph-2", activeG2.get().getGraphId());

        // Activating a new model for graph-1 does NOT demote graph-2
        Path artifact1b = createArtifact("model-g1b.fb");
        ModelEntry entryG1b = deployService.deploy(
                "psl-weights-v2", "proj-alpha", "graph-1",
                ModelType.PSL, artifact1b, null);

        // graph-1 now points to the new model
        Optional<ModelEntry> activeG1after = registryService.findActiveByProjectAndGraph("proj-alpha", "graph-1", ModelType.PSL);
        assertTrue(activeG1after.isPresent());
        assertEquals(entryG1b.getModelId(), activeG1after.get().getModelId());

        // graph-2 is unchanged
        Optional<ModelEntry> activeG2after = registryService.findActiveByProjectAndGraph("proj-alpha", "graph-2", ModelType.PSL);
        assertTrue(activeG2after.isPresent());
        assertEquals(entryG2.getModelId(), activeG2after.get().getModelId());

        // The original graph-1 entry was demoted
        ModelEntry demotedG1 = registryService.getModel(entryG1.getModelId()).orElseThrow();
        assertEquals(ModelStatus.STAGED, demotedG1.getStatus(), "Previous graph-1 model should be STAGED");
    }

    // ==================== 3. Rollback to a prior version ====================

    @Test
    @DisplayName("rollback() re-activates a prior version and demotes the current active")
    void rollback_restoresPriorVersion() throws IOException {
        Path artifactV1 = createArtifact("mebn-v1.fb");
        Path artifactV2 = createArtifact("mebn-v2.fb");

        // Deploy v1
        ModelEntry v1 = deployService.deploy(
                "mebn-params", "proj-beta", "graph-A",
                ModelType.MEBN, artifactV1, null);
        assertEquals(ModelStatus.ACTIVE, v1.getStatus());

        // Deploy v2 — v1 should be demoted to STAGED
        ModelEntry v2 = deployService.deploy(
                "mebn-params", "proj-beta", "graph-A",
                ModelType.MEBN, artifactV2, null);
        assertEquals(ModelStatus.ACTIVE, v2.getStatus());

        ModelEntry v1AfterV2 = registryService.getModel(v1.getModelId()).orElseThrow();
        assertEquals(ModelStatus.STAGED, v1AfterV2.getStatus(), "v1 should have been demoted after v2 deploy");

        // Roll back to v1
        ModelEntry restored = deployService.rollback(v1.getModelId());
        assertEquals(ModelStatus.ACTIVE, restored.getStatus(), "After rollback, v1 should be ACTIVE again");

        // v2 should now be STAGED
        ModelEntry v2AfterRollback = registryService.getModel(v2.getModelId()).orElseThrow();
        assertEquals(ModelStatus.STAGED, v2AfterRollback.getStatus(), "v2 should be demoted after rollback");

        // findActiveByProjectAndGraph returns v1
        Optional<ModelEntry> activeNow = registryService.findActiveByProjectAndGraph("proj-beta", "graph-A", ModelType.MEBN);
        assertTrue(activeNow.isPresent());
        assertEquals(v1.getModelId(), activeNow.get().getModelId());
    }

    // ==================== 4. Type filter in findActiveByProjectAndGraph ====================

    @Test
    @DisplayName("findActiveByProjectAndGraph type filter distinguishes KGE from PSL for the same (project,graph)")
    void typeFilter_distinguishesModelTypes() throws IOException {
        Path kgeArtifact = createArtifact("rotate-e.fb");
        Path pslArtifact = createArtifact("psl.json");

        deployService.deploy("rotate-e", "proj-gamma", "graph-X", ModelType.KGE, kgeArtifact, null);
        deployService.deploy("psl-weights", "proj-gamma", "graph-X", ModelType.PSL, pslArtifact, null);

        // Filtered search for KGE
        Optional<ModelEntry> activeKge = registryService.findActiveByProjectAndGraph("proj-gamma", "graph-X", ModelType.KGE);
        assertTrue(activeKge.isPresent());
        assertEquals(ModelType.KGE, activeKge.get().getType());

        // Filtered search for PSL
        Optional<ModelEntry> activePsl = registryService.findActiveByProjectAndGraph("proj-gamma", "graph-X", ModelType.PSL);
        assertTrue(activePsl.isPresent());
        assertEquals(ModelType.PSL, activePsl.get().getType());

        // No type filter — returns one of them (both are active for the scope)
        Optional<ModelEntry> activeAny = registryService.findActiveByProjectAndGraph("proj-gamma", "graph-X", null);
        assertTrue(activeAny.isPresent());
    }

    // ==================== 5. listVersions ====================

    @Test
    @DisplayName("listVersions returns all versions (STAGED and ACTIVE) scoped to the (project, graph)")
    void listVersions_returnsAllVersions() throws IOException {
        Path a1 = createArtifact("kge-v1.fb");
        Path a2 = createArtifact("kge-v2.fb");
        Path a3 = createArtifact("kge-v3.fb");

        deployService.deploy("kge", "proj-delta", "graph-Z", ModelType.KGE, a1, null);
        deployService.deploy("kge", "proj-delta", "graph-Z", ModelType.KGE, a2, null);
        deployService.deploy("kge", "proj-delta", "graph-Z", ModelType.KGE, a3, null);

        List<ModelEntry> versions = deployService.listVersions("proj-delta", "graph-Z");
        assertEquals(3, versions.size(), "All three deployed versions should be listed");

        long active = versions.stream().filter(ModelEntry::isActive).count();
        long staged = versions.stream().filter(e -> e.getStatus() == ModelStatus.STAGED).count();
        assertEquals(1, active, "Exactly one version should be ACTIVE");
        assertEquals(2, staged, "The two prior versions should be STAGED");
    }

    // ==================== 6. Error cases ====================

    @Test
    @DisplayName("activate() throws IllegalArgumentException for an unknown modelId")
    void activate_unknownModel_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> deployService.activate("nonexistent-model-id"));
    }

    @Test
    @DisplayName("activateForGraph() returns false for a model without project/graph scoping")
    void activateForGraph_globalModel_returnsFalse() {
        // Add a plain global model entry (no projectId/graphId)
        ModelEntry globalEntry = ModelEntry.encoder("global-encoder", 768);
        globalEntry.setStatus(ModelStatus.STAGED);
        registryService.addModel(globalEntry);

        boolean result = registryService.activateForGraph("global-encoder");
        assertFalse(result, "activateForGraph should return false for a non-graph-scoped model");
    }

    @Test
    @DisplayName("stage() throws IllegalArgumentException when artifact file does not exist")
    void stage_missingArtifact_throws() {
        Path missing = tempDir.resolve("does-not-exist.fb");
        assertThrows(IllegalArgumentException.class,
                () -> deployService.stage("kge", "p", "g", ModelType.KGE, missing, null));
    }

    // ==================== 7. ModelEntry graph-scope helpers ====================

    @Test
    @DisplayName("ModelEntry.isGraphScoped() returns true only when both projectId and graphId are set")
    void modelEntry_isGraphScoped() {
        ModelEntry scoped = ModelEntry.graphScoped("kge-v1", ModelType.KGE, "proj-1", "graph-1", "kge/kge-v1");
        assertTrue(scoped.isGraphScoped());
        assertEquals("proj-1", scoped.getProjectId());
        assertEquals("graph-1", scoped.getGraphId());
        assertEquals(ModelStatus.STAGED, scoped.getStatus());

        ModelEntry global = ModelEntry.encoder("enc-1", 768);
        assertFalse(global.isGraphScoped());
    }

    @Test
    @DisplayName("GraphScopedDeployService.buildVersionedId embeds all three components")
    void buildVersionedId_format() {
        String id = GraphScopedDeployService.buildVersionedId("rotate-e", "my-project", "fact-sheet-42");
        assertTrue(id.startsWith("rotate-e__my-project__fact-sheet-42__"),
                "Versioned ID should start with base__project__graph__: " + id);
    }

    // ==================== Helpers ====================

    private Path createArtifact(String filename) throws IOException {
        Path artifact = tempDir.resolve(filename);
        Files.writeString(artifact, "dummy model data for " + filename);
        return artifact;
    }
}
