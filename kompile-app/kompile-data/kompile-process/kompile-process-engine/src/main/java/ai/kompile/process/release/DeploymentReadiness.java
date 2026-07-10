package ai.kompile.process.release;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Side-effect-free deployment preflight result suitable for APIs and automation.
 */
public record DeploymentReadiness(
        String releaseId,
        boolean ready,
        Instant checkedAt,
        Map<String, String> verifiedArtifacts,
        List<String> errors) {

    public DeploymentReadiness {
        verifiedArtifacts = verifiedArtifacts == null ? Map.of() : Map.copyOf(verifiedArtifacts);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
