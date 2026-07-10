package ai.kompile.process.release;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactReleaseDeploymentVerifierTest {

    @Test
    void verifiesEveryArtifactAndRecordsItsHash() {
        ExecutableArtifactResolver resolver = mock(ExecutableArtifactResolver.class);
        ArtifactManifest manifest = manifest("script", "sha256:abc");
        ProcessRelease release = ProcessRelease.builder()
                .id("release")
                .artifacts(List.of(manifest))
                .build();
        when(resolver.resolve(same(release), any())).thenReturn(
                new ExecutableArtifactResolver.ResolvedExecutable(manifest, "return 1", "python"));

        DeploymentReadiness readiness =
                new ArtifactReleaseDeploymentVerifier(resolver).verify(release);

        assertTrue(readiness.ready());
        assertEquals("sha256:abc", readiness.verifiedArtifacts().get("script:1"));
        assertTrue(readiness.errors().isEmpty());
    }

    @Test
    void reportsResolverFailuresWithoutPartiallyDeclaringReadiness() {
        ExecutableArtifactResolver resolver = mock(ExecutableArtifactResolver.class);
        ProcessRelease release = ProcessRelease.builder()
                .id("release")
                .artifacts(List.of(manifest("script", "sha256:abc")))
                .build();
        when(resolver.resolve(same(release), any()))
                .thenThrow(new IllegalStateException("content hash mismatch"));

        DeploymentReadiness readiness =
                new ArtifactReleaseDeploymentVerifier(resolver).verify(release);

        assertFalse(readiness.ready());
        assertTrue(readiness.verifiedArtifacts().isEmpty());
        assertEquals(1, readiness.errors().size());
        assertTrue(readiness.errors().get(0).contains("content hash mismatch"));
    }

    private ArtifactManifest manifest(String id, String hash) {
        return ArtifactManifest.builder()
                .artifactId(id)
                .version("1")
                .kind(ExecutableKind.SCRIPT)
                .contentHash(hash)
                .build();
    }
}
