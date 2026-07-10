package ai.kompile.process.release;

import java.time.Instant;

public record ArtifactDeployment(
        String artifactId, String version, String contentHash, String storageUri,
        long sizeBytes, Instant materializedAt, boolean alreadyPresent) {
}
