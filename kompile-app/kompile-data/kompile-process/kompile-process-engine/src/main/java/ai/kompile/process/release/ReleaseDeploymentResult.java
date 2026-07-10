package ai.kompile.process.release;

import java.util.List;

public record ReleaseDeploymentResult(
        ProcessRelease release, List<ArtifactDeployment> artifacts, DeploymentReadiness readiness) {
    public ReleaseDeploymentResult {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }
}
