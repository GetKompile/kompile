package ai.kompile.process.release;

import java.util.Map;

public record ReleaseDeploymentRequest(Map<String, String> artifacts, boolean activate) {
    public ReleaseDeploymentRequest {
        artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
    }
}
