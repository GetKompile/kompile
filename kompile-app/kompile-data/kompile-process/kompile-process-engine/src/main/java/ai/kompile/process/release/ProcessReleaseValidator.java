package ai.kompile.process.release;

import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessPhase;
import ai.kompile.process.workflow.ProcessStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Preflight validation for a process release. Validation is deliberately side-effect free so the
 * same checks can run in the REST API, CLI, UI, and deployment pipeline.
 */
@Component
public class ProcessReleaseValidator {

    public ValidationResult validate(ProcessDefinition definition, ProcessRelease release) {
        List<String> errors = new ArrayList<>();
        if (definition == null) {
            errors.add("Process definition is required");
            return new ValidationResult(errors);
        }
        if (release == null) {
            errors.add("Process release is required");
            return new ValidationResult(errors);
        }

        requireText(release.getId(), "Release ID is required", errors);
        requireText(release.getEnvironment(), "Release environment is required", errors);
        requireText(definition.getId(), "Process definition ID is required", errors);
        if (!java.util.Objects.equals(definition.getId(), release.getProcessDefinitionId())) {
            errors.add("Release processDefinitionId does not match the process definition");
        }
        if (definition.getVersion() != release.getProcessDefinitionVersion()) {
            errors.add("Release processDefinitionVersion does not match the process definition");
        }

        Map<String, ArtifactManifest> artifacts = indexArtifacts(release.getArtifacts(), errors);
        Set<String> runtimeProfiles = indexRuntimeProfiles(release.getRuntimeProfiles(), errors);
        validateSteps(definition.getPhases(), artifacts, runtimeProfiles, errors);
        return new ValidationResult(errors);
    }

    private Map<String, ArtifactManifest> indexArtifacts(List<ArtifactManifest> manifests,
                                                          List<String> errors) {
        Map<String, ArtifactManifest> indexed = new HashMap<>();
        if (manifests == null) {
            return indexed;
        }
        for (ArtifactManifest manifest : manifests) {
            if (manifest == null || isBlank(manifest.getArtifactId())) {
                errors.add("Every artifact manifest must have an artifactId");
                continue;
            }
            String key = artifactKey(manifest.getArtifactId(), manifest.getVersion());
            if (indexed.put(key, manifest) != null) {
                errors.add("Duplicate artifact manifest: " + key);
            }
            requireText(manifest.getVersion(), "Artifact " + manifest.getArtifactId() + " must have a version", errors);
            requireText(manifest.getContentHash(), "Artifact " + manifest.getArtifactId() + " must have a contentHash", errors);
            if (manifest.getKind() == null) {
                errors.add("Artifact " + manifest.getArtifactId() + " must have a kind");
            }
        }
        return indexed;
    }

    private Set<String> indexRuntimeProfiles(List<RuntimeProfile> profiles, List<String> errors) {
        Set<String> indexed = new HashSet<>();
        if (profiles == null) {
            return indexed;
        }
        for (RuntimeProfile profile : profiles) {
            if (profile == null || isBlank(profile.getId())) {
                errors.add("Every runtime profile must have an ID");
                continue;
            }
            if (!indexed.add(profile.getId())) {
                errors.add("Duplicate runtime profile: " + profile.getId());
            }
            if (profile.isHostAccessAllowed()) {
                errors.add("Runtime profile " + profile.getId() + " cannot allow unrestricted host access");
            }
            if (profile.getMaxWallTimeMillis() <= 0) {
                errors.add("Runtime profile " + profile.getId() + " must enforce a wall-time limit");
            }
        }
        return indexed;
    }

    private void validateSteps(List<ProcessPhase> phases,
                               Map<String, ArtifactManifest> artifacts,
                               Set<String> runtimeProfiles,
                               List<String> errors) {
        if (phases == null) {
            return;
        }
        for (ProcessPhase phase : phases) {
            if (phase == null || phase.getSteps() == null) {
                continue;
            }
            for (ProcessStep step : phase.getSteps()) {
                if (step == null || step.getExecutableRef() == null) {
                    continue; // Legacy inline definitions remain valid until materialized.
                }
                ExecutableRef ref = step.getExecutableRef();
                String label = isBlank(step.getId()) ? "<unknown>" : step.getId();
                requireText(ref.getArtifactId(), "Step " + label + " executable artifactId is required", errors);
                requireText(ref.getVersion(), "Step " + label + " executable version is required", errors);
                requireText(ref.getContentHash(), "Step " + label + " executable contentHash is required", errors);
                if (ref.getKind() == null) {
                    errors.add("Step " + label + " executable kind is required");
                    continue;
                }

                ArtifactManifest manifest = artifacts.get(artifactKey(ref.getArtifactId(), ref.getVersion()));
                if (manifest == null) {
                    errors.add("Step " + label + " references an artifact absent from the release");
                    continue;
                }
                if (manifest.getKind() != ref.getKind()) {
                    errors.add("Step " + label + " executable kind does not match its artifact manifest");
                }
                if (!ref.getContentHash().equals(manifest.getContentHash())) {
                    errors.add("Step " + label + " executable contentHash does not match its artifact manifest");
                }
                String runtimeProfileId = ref.getRuntimeProfileId() != null
                        ? ref.getRuntimeProfileId() : manifest.getRuntimeProfileId();
                if (!isBlank(runtimeProfileId) && !runtimeProfiles.contains(runtimeProfileId)) {
                    errors.add("Step " + label + " references missing runtime profile " + runtimeProfileId);
                }
            }
        }
    }

    private String artifactKey(String artifactId, String version) {
        return String.valueOf(artifactId) + ":" + String.valueOf(version);
    }

    private void requireText(String value, String message, List<String> errors) {
        if (isBlank(value)) {
            errors.add(message);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static final class ValidationResult {
        private final List<String> errors;

        private ValidationResult(List<String> errors) {
            this.errors = List.copyOf(errors);
        }

        public boolean isValid() {
            return errors.isEmpty();
        }

        public List<String> getErrors() {
            return errors;
        }
    }
}
