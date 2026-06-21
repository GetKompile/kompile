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
package ai.kompile.app.staging;

import ai.kompile.app.project.ProjectBackendService;
import ai.kompile.app.project.ProjectResponse;
import ai.kompile.knowledgegraph.staging.ModelTrainedEvent;
import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.ModelStatus;
import ai.kompile.modelmanager.registry.ModelType;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectStatus;
import ai.kompile.staging.staging.GraphScopedDeployService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ModelDeploymentHook}.
 *
 * <p>Verifies that a {@link ModelTrainedEvent} results in
 * {@link GraphScopedDeployService#deploy} being called with the correct
 * {@code (projectId, graphId, type)} arguments, and that failures in
 * {@code deploy} are swallowed (do not propagate).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelDeploymentHookTest {

    @Mock
    private GraphScopedDeployService deployService;

    @Mock
    private ProjectBackendService projectBackendService;

    @TempDir
    Path tempDir;

    private ModelDeploymentHook hook;

    @BeforeEach
    void setUp() {
        hook = new ModelDeploymentHook(deployService, projectBackendService);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Path createArtifact(String name) throws Exception {
        Path p = tempDir.resolve(name);
        Files.writeString(p, "{\"test\":true}");
        return p;
    }

    private ModelEntry stubbedEntry(ModelType type) {
        return ModelEntry.builder()
                .modelId("test-id")
                .type(type)
                .status(ModelStatus.ACTIVE)
                .promotedAt(Instant.now().toString())
                .metadata(ModelMetadata.builder().build())
                .build();
    }

    private void stubProject(String projectId) {
        KompileProjectManifest manifest = new KompileProjectManifest();
        manifest.setProjectId(projectId);
        KompileProjectStatus status = new KompileProjectStatus();
        when(projectBackendService.current())
                .thenReturn(new ProjectResponse(manifest, status));
    }

    // ── PSL event → deploy(PSL, projectId, graphId) ───────────────────────────

    @Test
    void pslEvent_callsDeployWithCorrectArgs() throws Exception {
        Path artifact = createArtifact("42_cascade.v1.json");
        stubProject("proj-abc");
        when(deployService.deploy(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(stubbedEntry(ModelType.PSL));

        ModelTrainedEvent event = new ModelTrainedEvent(this, "psl", 42L, artifact, "psl-cascade");
        hook.onModelTrained(event);

        ArgumentCaptor<String>     baseModelIdCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String>     projectIdCap   = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String>     graphIdCap     = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ModelType>  typeCap        = ArgumentCaptor.forClass(ModelType.class);
        ArgumentCaptor<Path>       pathCap        = ArgumentCaptor.forClass(Path.class);
        ArgumentCaptor<ModelMetadata> metaCap     = ArgumentCaptor.forClass(ModelMetadata.class);

        verify(deployService, times(1)).deploy(
                baseModelIdCap.capture(),
                projectIdCap.capture(),
                graphIdCap.capture(),
                typeCap.capture(),
                pathCap.capture(),
                metaCap.capture());

        assertEquals("psl-cascade",  baseModelIdCap.getValue(),  "baseModelId should be psl-cascade");
        assertEquals("proj-abc",     projectIdCap.getValue(),    "projectId must come from the open project manifest");
        assertEquals("42",           graphIdCap.getValue(),      "graphId must be String.valueOf(factSheetId)");
        assertEquals(ModelType.PSL,  typeCap.getValue(),         "ModelType must be PSL");
        assertEquals(artifact,       pathCap.getValue(),         "artifact path must be passed through");
    }

    // ── MEBN event → deploy(MEBN, projectId, graphId) ─────────────────────────

    @Test
    void mebnEvent_callsDeployWithCorrectArgs() throws Exception {
        Path artifact = createArtifact("mebn-weights.json");
        stubProject("proj-xyz");
        when(deployService.deploy(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(stubbedEntry(ModelType.MEBN));

        ModelTrainedEvent event = new ModelTrainedEvent(this, "mebn", 7L, artifact, "mebn-grounding");
        hook.onModelTrained(event);

        ArgumentCaptor<ModelType> typeCap    = ArgumentCaptor.forClass(ModelType.class);
        ArgumentCaptor<String>    graphIdCap = ArgumentCaptor.forClass(String.class);
        verify(deployService).deploy(eq("mebn-grounding"), eq("proj-xyz"), graphIdCap.capture(),
                typeCap.capture(), eq(artifact), any());

        assertEquals(ModelType.MEBN, typeCap.getValue());
        assertEquals("7",            graphIdCap.getValue());
    }

    // ── KGE event → deploy(KGE, projectId, graphId) ───────────────────────────

    @Test
    void kgeEvent_callsDeployWithCorrectArgs() throws Exception {
        Path artifact = createArtifact("99-v1234567890.json");
        stubProject("proj-kge");
        when(deployService.deploy(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(stubbedEntry(ModelType.KGE));

        ModelTrainedEvent event = new ModelTrainedEvent(this, "kge", 99L, artifact, "kge-embedding");
        hook.onModelTrained(event);

        verify(deployService).deploy(eq("kge-embedding"), eq("proj-kge"), eq("99"),
                eq(ModelType.KGE), eq(artifact), any());
    }

    // ── Project fallback: no open project → "default" ─────────────────────────

    @Test
    void noOpenProject_usesDefaultProjectId() throws Exception {
        Path artifact = createArtifact("artifact.json");
        when(projectBackendService.current())
                .thenReturn(new ProjectResponse(null, new KompileProjectStatus()));
        when(deployService.deploy(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(stubbedEntry(ModelType.PSL));

        hook.onModelTrained(new ModelTrainedEvent(this, "psl", 1L, artifact, "psl-cascade"));

        ArgumentCaptor<String> projectIdCap = ArgumentCaptor.forClass(String.class);
        verify(deployService).deploy(anyString(), projectIdCap.capture(), anyString(), any(), any(), any());
        assertEquals("default", projectIdCap.getValue(), "should fall back to 'default' when no project open");
    }

    // ── ProjectBackendService throws → "default" fallback ─────────────────────

    @Test
    void projectServiceThrows_usesDefaultProjectId() throws Exception {
        Path artifact = createArtifact("artifact2.json");
        when(projectBackendService.current()).thenThrow(new RuntimeException("db error"));
        when(deployService.deploy(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(stubbedEntry(ModelType.PSL));

        hook.onModelTrained(new ModelTrainedEvent(this, "psl", 2L, artifact, "psl-cascade"));

        ArgumentCaptor<String> projectIdCap = ArgumentCaptor.forClass(String.class);
        verify(deployService).deploy(anyString(), projectIdCap.capture(), anyString(), any(), any(), any());
        assertEquals("default", projectIdCap.getValue());
    }

    // ── Deploy failure is swallowed (cascade must not fail) ───────────────────

    @Test
    void deployFailure_isSwallowed_noExceptionPropagated() throws Exception {
        Path artifact = createArtifact("artifact3.json");
        stubProject("proj-fail");
        when(deployService.deploy(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenThrow(new java.io.IOException("disk full"));

        // Must NOT throw
        assertDoesNotThrow(() ->
                hook.onModelTrained(new ModelTrainedEvent(this, "psl", 3L, artifact, "psl-cascade")));
        verify(deployService, times(1)).deploy(any(), any(), any(), any(), any(), any());
    }

    // ── Missing artifact → deploy is NOT called ────────────────────────────────

    @Test
    void missingArtifact_deployNotCalled() throws Exception {
        Path nonExistent = tempDir.resolve("does-not-exist.json");
        stubProject("proj-miss");

        hook.onModelTrained(new ModelTrainedEvent(this, "psl", 5L, nonExistent, "psl-cascade"));

        verify(deployService, never()).deploy(any(), any(), any(), any(), any(), any());
    }

    // ── Unknown model type → deploy is NOT called ─────────────────────────────

    @Test
    void unknownModelType_deployNotCalled() throws Exception {
        Path artifact = createArtifact("unknown.json");
        stubProject("proj-unk");

        hook.onModelTrained(new ModelTrainedEvent(this, "samediff", 6L, artifact, "samediff-ft"));

        verify(deployService, never()).deploy(any(), any(), any(), any(), any(), any());
    }

    // ── Null projectBackendService → "default" fallback ───────────────────────

    @Test
    void nullProjectBackendService_usesDefault() throws Exception {
        // Create hook without project service
        ModelDeploymentHook hookNoProject = new ModelDeploymentHook(deployService, null);
        Path artifact = createArtifact("artifact4.json");
        when(deployService.deploy(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(stubbedEntry(ModelType.MEBN));

        hookNoProject.onModelTrained(new ModelTrainedEvent(this, "mebn", 10L, artifact, "mebn-grounding"));

        ArgumentCaptor<String> projectIdCap = ArgumentCaptor.forClass(String.class);
        verify(deployService).deploy(anyString(), projectIdCap.capture(), anyString(), any(), any(), any());
        assertEquals("default", projectIdCap.getValue());
    }
}
