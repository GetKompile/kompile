package ai.kompile.process.release;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proves every immutable release artifact is locally resolvable and hash-valid.
 */
@Component
public class ArtifactReleaseDeploymentVerifier implements ReleaseDeploymentVerifier {

    private final ExecutableArtifactResolver artifactResolver;

    public ArtifactReleaseDeploymentVerifier(ExecutableArtifactResolver artifactResolver) {
        this.artifactResolver = artifactResolver;
    }

    @Override
    public DeploymentReadiness verify(ProcessRelease release) {
        if (release == null) {
            return new DeploymentReadiness(null, false, Instant.now(), Map.of(),
                    List.of("Process release is required"));
        }

        Map<String, String> verified = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        List<ArtifactManifest> artifacts = release.getArtifacts() == null
                ? List.of() : release.getArtifacts();

        for (ArtifactManifest manifest : artifacts) {
            if (manifest == null) {
                errors.add("Release contains a null artifact manifest");
                continue;
            }
            String key = manifest.getArtifactId() + ":" + manifest.getVersion();
            try {
                ExecutableRef reference = ExecutableRef.builder()
                        .artifactId(manifest.getArtifactId())
                        .version(manifest.getVersion())
                        .kind(manifest.getKind())
                        .contentHash(manifest.getContentHash())
                        .runtimeProfileId(manifest.getRuntimeProfileId())
                        .build();
                ExecutableArtifactResolver.ResolvedExecutable resolved =
                        artifactResolver.resolve(release, reference);
                verified.put(key, resolved.manifest().getContentHash());
            } catch (RuntimeException exception) {
                errors.add("Artifact " + key + " is not deployment-ready: " + exception.getMessage());
            }
        }

        return new DeploymentReadiness(release.getId(), errors.isEmpty(), Instant.now(),
                verified, errors);
    }
}
