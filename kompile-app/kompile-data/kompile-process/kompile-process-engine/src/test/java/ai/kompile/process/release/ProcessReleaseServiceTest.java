package ai.kompile.process.release;

import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.workflow.ProcessDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProcessReleaseServiceTest {

    private MemoryRepository repository;
    private ReleaseDeploymentVerifier deploymentVerifier;
    private ArtifactMaterializer artifactMaterializer;
    private ProcessReleaseService service;

    @BeforeEach
    void setUp() {
        repository = new MemoryRepository();
        ProcessEngineService engine = mock(ProcessEngineService.class);
        when(engine.getProcess("proc", 1)).thenReturn(ProcessDefinition.builder()
                .id("proc")
                .version(1)
                .phases(List.of())
                .build());
        deploymentVerifier = mock(ReleaseDeploymentVerifier.class);
        when(deploymentVerifier.verify(any())).thenAnswer(invocation -> {
            ProcessRelease release = invocation.getArgument(0);
            return new DeploymentReadiness(release.getId(), true, Instant.now(), Map.of(), List.of());
        });
        artifactMaterializer = mock(ArtifactMaterializer.class);
        service = new ProcessReleaseService(repository, engine, new ProcessReleaseValidator(),
                deploymentVerifier, artifactMaterializer);
    }

    @Test
    void drivesReleaseThroughPromotionLifecycle() {
        ProcessRelease draft = service.createDraft(release("first"));

        assertEquals(ProcessReleaseStatus.VALIDATED, service.validate(draft.getId()).getStatus());
        assertEquals(ProcessReleaseStatus.PUBLISHED, service.publish(draft.getId()).getStatus());
        ProcessRelease deployed = service.confirmDeployed(draft.getId());
        assertEquals(ProcessReleaseStatus.DEPLOYED, deployed.getStatus());
        assertNotNull(deployed.getDeploymentVerifiedAt());
        assertEquals(Map.of(), deployed.getDeployedArtifactHashes());
        assertEquals(ProcessReleaseStatus.ACTIVE, service.activate(draft.getId()).getStatus());
    }

    @Test
    void activatingNewReleaseDrainsPreviousReleaseAndSupportsRollback() {
        promoteAndActivate(service.createDraft(release("old")));
        ProcessRelease next = service.createDraft(release("new"));
        promoteAndActivate(next);

        assertEquals(ProcessReleaseStatus.DRAINING, service.get("old").getStatus());
        assertEquals(ProcessReleaseStatus.ACTIVE, service.get("new").getStatus());

        ProcessRelease restored = service.rollback("new", "old");

        assertEquals(ProcessReleaseStatus.ACTIVE, restored.getStatus());
        assertEquals(ProcessReleaseStatus.DRAINING, service.get("new").getStatus());
        assertEquals("old", service.get("new").getRollbackReleaseId());
    }

    @Test
    void rejectsSkippedLifecycleStates() {
        ProcessRelease draft = service.createDraft(release("draft"));

        assertThrows(IllegalStateException.class, () -> service.publish(draft.getId()));
        assertEquals(ProcessReleaseStatus.DRAFT, service.get("draft").getStatus());
    }

    @Test
    void stagesOnlyManifestArtifactsFromPublishedReleases() {
        ArtifactManifest manifest = ArtifactManifest.builder()
                .artifactId("script")
                .version("1")
                .kind(ExecutableKind.SCRIPT)
                .contentHash("sha256:abc")
                .storageUri("scripts/script.py")
                .build();
        ProcessRelease draft = release("staging");
        draft.setArtifacts(List.of(manifest));
        service.createDraft(draft);
        service.validate(draft.getId());
        service.publish(draft.getId());
        ArtifactDeployment expected = new ArtifactDeployment(
                "script", "1", "sha256:abc", "file:///script.py", 4, Instant.now(), false);
        when(artifactMaterializer.materialize(eq(manifest), aryEq(new byte[]{1, 2, 3, 4})))
                .thenReturn(expected);

        ArtifactDeployment actual = service.stageArtifact("staging", "script", "1",
                new ArtifactUploadRequest(Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4})));

        assertEquals(expected, actual);
        verify(artifactMaterializer).materialize(eq(manifest), aryEq(new byte[]{1, 2, 3, 4}));
        assertThrows(NoSuchElementException.class, () -> service.stageArtifact(
                "staging", "missing", "1", new ArtifactUploadRequest("AQ==")));
    }

    @Test
    void deploysAndActivatesCompleteArtifactBundleInOneCall() {
        ArtifactManifest manifest = ArtifactManifest.builder()
                .artifactId("script").version("1").kind(ExecutableKind.SCRIPT)
                .contentHash("sha256:abc").storageUri("scripts/script.py").build();
        ProcessRelease draft = release("bundle");
        draft.setArtifacts(List.of(manifest));
        service.createDraft(draft);
        service.validate(draft.getId());
        service.publish(draft.getId());
        when(artifactMaterializer.materialize(eq(manifest), aryEq(new byte[]{1, 2, 3, 4})))
                .thenReturn(new ArtifactDeployment("script", "1", "sha256:abc",
                        "file:///script.py", 4, Instant.now(), false));

        ReleaseDeploymentResult result = service.deploy("bundle", new ReleaseDeploymentRequest(
                Map.of("script:1", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4})), true));

        assertEquals(ProcessReleaseStatus.ACTIVE, result.release().getStatus());
        assertEquals(1, result.artifacts().size());
        assertEquals(true, result.readiness().ready());
    }

    @Test
    void rejectsIncompleteBundleBeforeStagingOrChangingReleaseState() {
        ArtifactManifest manifest = ArtifactManifest.builder()
                .artifactId("script").version("1").kind(ExecutableKind.SCRIPT)
                .contentHash("sha256:abc").storageUri("scripts/script.py").build();
        ProcessRelease draft = release("incomplete");
        draft.setArtifacts(List.of(manifest));
        service.createDraft(draft);
        service.validate(draft.getId());
        service.publish(draft.getId());

        assertThrows(IllegalArgumentException.class, () -> service.deploy(
                "incomplete", new ReleaseDeploymentRequest(Map.of(), true)));

        assertEquals(ProcessReleaseStatus.PUBLISHED, service.get("incomplete").getStatus());
        verifyNoInteractions(artifactMaterializer);
    }

    @Test
    void refusesDeploymentConfirmationWhenArtifactsAreUnavailable() {
        ProcessRelease draft = service.createDraft(release("unready"));
        service.validate(draft.getId());
        service.publish(draft.getId());
        doReturn(new DeploymentReadiness(
                "unready", false, Instant.now(), Map.of(), List.of("artifact missing")))
                .when(deploymentVerifier).verify(any());

        ReleaseDeploymentException exception = assertThrows(ReleaseDeploymentException.class,
                () -> service.confirmDeployed(draft.getId()));

        assertEquals(List.of("artifact missing"), exception.getErrors());
        assertEquals(ProcessReleaseStatus.PUBLISHED, service.get("unready").getStatus());
    }

    @Test
    void rechecksReadinessBeforeActivation() {
        ProcessRelease draft = service.createDraft(release("stale"));
        service.validate(draft.getId());
        service.publish(draft.getId());
        service.confirmDeployed(draft.getId());
        doReturn(new DeploymentReadiness(
                "stale", false, Instant.now(), Map.of(), List.of("artifact removed")))
                .when(deploymentVerifier).verify(any());

        assertThrows(ReleaseDeploymentException.class, () -> service.activate(draft.getId()));
        assertEquals(ProcessReleaseStatus.DEPLOYED, service.get("stale").getStatus());
    }

    private void promoteAndActivate(ProcessRelease release) {
        service.validate(release.getId());
        service.publish(release.getId());
        service.confirmDeployed(release.getId());
        service.activate(release.getId());
    }

    private ProcessRelease release(String id) {
        return ProcessRelease.builder()
                .id(id)
                .processDefinitionId("proc")
                .processDefinitionVersion(1)
                .environment("production")
                .artifacts(List.of())
                .runtimeProfiles(List.of())
                .build();
    }

    private static class MemoryRepository implements ProcessReleaseRepository {
        private final Map<String, ProcessRelease> releases = new LinkedHashMap<>();

        @Override
        public ProcessRelease save(ProcessRelease release) {
            releases.put(release.getId(), release);
            return release;
        }

        @Override
        public Optional<ProcessRelease> findById(String id) {
            return Optional.ofNullable(releases.get(id));
        }

        @Override
        public List<ProcessRelease> findAll() {
            return List.copyOf(releases.values());
        }
    }
}
