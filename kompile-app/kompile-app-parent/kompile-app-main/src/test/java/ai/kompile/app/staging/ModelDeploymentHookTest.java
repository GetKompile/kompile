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
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ModelDeploymentHook}.
 *
 * <p>The hook reaches the staging server over HTTP — {@code kompile-model-staging} is a standalone
 * subprocess and is NOT a dependency of app-main. These tests inject a mock {@link RestTemplate}
 * (and the {@code @Value} staging URL) via {@link ReflectionTestUtils}, then verify that a
 * {@link ModelTrainedEvent} POSTs a deploy request to
 * {@code {stagingUrl}/api/staging/graph/{projectId}/{graphId}/deploy} with the right body, that the
 * guard conditions skip the POST, and that any POST failure is swallowed (a cascade/training job must
 * never fail because staging is down).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ModelDeploymentHookTest {

    private static final String STAGING_URL = "http://localhost:8090";

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private ProjectBackendService projectBackendService;

    @TempDir
    Path tempDir;

    private ModelDeploymentHook hook;

    @BeforeEach
    void setUp() {
        hook = new ModelDeploymentHook(projectBackendService);
        // @Value injection and the inline `new RestTemplate()` are not wired in a plain unit test,
        // so set both fields directly — no Spring context, no real network call.
        ReflectionTestUtils.setField(hook, "stagingUrl", STAGING_URL);
        ReflectionTestUtils.setField(hook, "restTemplate", restTemplate);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Path createArtifact(String name) throws Exception {
        Path p = tempDir.resolve(name);
        Files.writeString(p, "{\"test\":true}");
        return p;
    }

    private void stubProject(String projectId) {
        KompileProjectManifest manifest = new KompileProjectManifest();
        manifest.setProjectId(projectId);
        when(projectBackendService.current())
                .thenReturn(new ProjectResponse(manifest, new KompileProjectStatus()));
    }

    /** Verify exactly one POST to {@code expectedUrl} and return the posted body map. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> capturePostedBody(String expectedUrl) {
        ArgumentCaptor<Object> bodyCap = ArgumentCaptor.forClass(Object.class);
        verify(restTemplate, times(1)).postForObject(eq(expectedUrl), bodyCap.capture(), eq(Map.class));
        return (Map<String, Object>) bodyCap.getValue();
    }

    // ── PSL event → POST deploy with correct URL + body ───────────────────────

    @Test
    void pslEvent_postsDeployRequest() throws Exception {
        Path artifact = createArtifact("42_cascade.v1.json");
        stubProject("proj-abc");

        hook.onModelTrained(new ModelTrainedEvent(this, "psl", 42L, artifact, "psl-cascade"));

        Map<String, Object> body =
                capturePostedBody("http://localhost:8090/api/staging/graph/proj-abc/42/deploy");
        assertEquals("psl-cascade", body.get("modelId"), "modelId should be the baseModelId");
        assertEquals("psl", body.get("type"), "type should be the event model type");
        assertEquals(artifact.toAbsolutePath().toString(), body.get("artifactPath"),
                "artifactPath should be the absolute artifact path");
    }

    // ── MEBN event → correct URL ──────────────────────────────────────────────

    @Test
    void mebnEvent_postsCorrectUrl() throws Exception {
        Path artifact = createArtifact("mebn-weights.json");
        stubProject("proj-xyz");

        hook.onModelTrained(new ModelTrainedEvent(this, "mebn", 7L, artifact, "mebn-grounding"));

        Map<String, Object> body =
                capturePostedBody("http://localhost:8090/api/staging/graph/proj-xyz/7/deploy");
        assertEquals("mebn", body.get("type"));
        assertEquals("mebn-grounding", body.get("modelId"));
    }

    // ── KGE event → correct URL ───────────────────────────────────────────────

    @Test
    void kgeEvent_postsCorrectUrl() throws Exception {
        Path artifact = createArtifact("99-v1234567890.json");
        stubProject("proj-kge");

        hook.onModelTrained(new ModelTrainedEvent(this, "kge", 99L, artifact, "kge-embedding"));

        capturePostedBody("http://localhost:8090/api/staging/graph/proj-kge/99/deploy");
    }

    // ── No open project → "default" in the URL ────────────────────────────────

    @Test
    void noOpenProject_usesDefaultProjectId() throws Exception {
        Path artifact = createArtifact("artifact.json");
        when(projectBackendService.current())
                .thenReturn(new ProjectResponse(null, new KompileProjectStatus()));

        hook.onModelTrained(new ModelTrainedEvent(this, "psl", 1L, artifact, "psl-cascade"));

        capturePostedBody("http://localhost:8090/api/staging/graph/default/1/deploy");
    }

    // ── ProjectBackendService throws → "default" ──────────────────────────────

    @Test
    void projectServiceThrows_usesDefaultProjectId() throws Exception {
        Path artifact = createArtifact("artifact2.json");
        when(projectBackendService.current()).thenThrow(new RuntimeException("db error"));

        hook.onModelTrained(new ModelTrainedEvent(this, "psl", 2L, artifact, "psl-cascade"));

        capturePostedBody("http://localhost:8090/api/staging/graph/default/2/deploy");
    }

    // ── POST failure is swallowed (cascade must not fail) ─────────────────────

    @Test
    void postFailure_isSwallowed_noExceptionPropagated() throws Exception {
        Path artifact = createArtifact("artifact3.json");
        stubProject("proj-fail");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                .thenThrow(new RuntimeException("connection refused"));

        assertDoesNotThrow(() ->
                hook.onModelTrained(new ModelTrainedEvent(this, "psl", 3L, artifact, "psl-cascade")));
        verify(restTemplate, times(1)).postForObject(anyString(), any(), eq(Map.class));
    }

    // ── Missing artifact → no POST ─────────────────────────────────────────────

    @Test
    void missingArtifact_noPost() throws Exception {
        Path nonExistent = tempDir.resolve("does-not-exist.json");
        stubProject("proj-miss");

        hook.onModelTrained(new ModelTrainedEvent(this, "psl", 5L, nonExistent, "psl-cascade"));

        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }

    // ── Unknown model type → no POST ──────────────────────────────────────────

    @Test
    void unknownModelType_noPost() throws Exception {
        Path artifact = createArtifact("unknown.json");
        stubProject("proj-unk");

        hook.onModelTrained(new ModelTrainedEvent(this, "samediff", 6L, artifact, "samediff-ft"));

        verify(restTemplate, never()).postForObject(anyString(), any(), any());
    }

    // ── Null projectBackendService → "default" ────────────────────────────────

    @Test
    void nullProjectBackendService_usesDefault() throws Exception {
        ModelDeploymentHook hookNoProject = new ModelDeploymentHook(null);
        ReflectionTestUtils.setField(hookNoProject, "stagingUrl", STAGING_URL);
        ReflectionTestUtils.setField(hookNoProject, "restTemplate", restTemplate);
        Path artifact = createArtifact("artifact4.json");

        hookNoProject.onModelTrained(new ModelTrainedEvent(this, "mebn", 10L, artifact, "mebn-grounding"));

        capturePostedBody("http://localhost:8090/api/staging/graph/default/10/deploy");
    }
}
