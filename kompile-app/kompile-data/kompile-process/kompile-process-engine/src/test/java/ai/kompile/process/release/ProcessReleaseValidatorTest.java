package ai.kompile.process.release;

import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessPhase;
import ai.kompile.process.workflow.ProcessStep;
import ai.kompile.process.workflow.StepType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessReleaseValidatorTest {

    private final ProcessReleaseValidator validator = new ProcessReleaseValidator();

    @Test
    void acceptsPinnedExecutableWithRestrictedRuntime() {
        ExecutableRef executable = ExecutableRef.builder()
                .kind(ExecutableKind.SCRIPT)
                .artifactId("invoice-normalizer")
                .version("1.0.0")
                .contentHash("sha256:abc")
                .runtimeProfileId("restricted-js")
                .build();
        ProcessDefinition definition = definition(executable);
        ProcessRelease release = release(
                ArtifactManifest.builder()
                        .artifactId("invoice-normalizer")
                        .version("1.0.0")
                        .kind(ExecutableKind.SCRIPT)
                        .contentHash("sha256:abc")
                        .runtimeProfileId("restricted-js")
                        .build(),
                RuntimeProfile.builder()
                        .id("restricted-js")
                        .runtime("graaljs")
                        .runtimeVersion("23")
                        .maxWallTimeMillis(30_000)
                        .build());

        assertTrue(validator.validate(definition, release).isValid());
    }

    @Test
    void rejectsArtifactHashDrift() {
        ExecutableRef executable = ExecutableRef.builder()
                .kind(ExecutableKind.SCRIPT)
                .artifactId("invoice-normalizer")
                .version("1.0.0")
                .contentHash("sha256:expected")
                .build();
        ProcessDefinition definition = definition(executable);
        ProcessRelease release = release(
                ArtifactManifest.builder()
                        .artifactId("invoice-normalizer")
                        .version("1.0.0")
                        .kind(ExecutableKind.SCRIPT)
                        .contentHash("sha256:actual")
                        .build(),
                null);

        ProcessReleaseValidator.ValidationResult result = validator.validate(definition, release);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("contentHash")));
    }

    @Test
    void rejectsUnsafeRuntimeAndMissingArtifact() {
        ProcessDefinition definition = definition(ExecutableRef.builder()
                .kind(ExecutableKind.AGENT_SESSION)
                .artifactId("close-assistant")
                .version("2")
                .contentHash("sha256:agent")
                .runtimeProfileId("unsafe")
                .build());
        ProcessRelease release = release(null, RuntimeProfile.builder()
                .id("unsafe")
                .runtime("agent")
                .hostAccessAllowed(true)
                .maxWallTimeMillis(0)
                .build());

        ProcessReleaseValidator.ValidationResult result = validator.validate(definition, release);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("host access")));
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("wall-time")));
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("absent")));
    }

    @Test
    void keepsLegacyInlineStepsDeployableDuringMigration() {
        ProcessStep inlineStep = ProcessStep.builder()
                .id("1.1")
                .name("Legacy script")
                .stepType(StepType.SCRIPT)
                .scriptLanguage("javascript")
                .scriptBody("1 + 1")
                .build();
        ProcessDefinition definition = ProcessDefinition.builder()
                .id("proc")
                .version(3)
                .phases(List.of(ProcessPhase.builder().steps(List.of(inlineStep)).build()))
                .build();

        assertTrue(validator.validate(definition, release(null, null)).isValid());
    }

    private ProcessDefinition definition(ExecutableRef executable) {
        ProcessStep step = ProcessStep.builder()
                .id("1.1")
                .name("Normalize")
                .stepType(StepType.SCRIPT)
                .executableRef(executable)
                .build();
        return ProcessDefinition.builder()
                .id("proc")
                .version(3)
                .phases(List.of(ProcessPhase.builder().steps(List.of(step)).build()))
                .build();
    }

    private ProcessRelease release(ArtifactManifest artifact, RuntimeProfile runtimeProfile) {
        return ProcessRelease.builder()
                .id("release-1")
                .processDefinitionId("proc")
                .processDefinitionVersion(3)
                .environment("staging")
                .status(ProcessReleaseStatus.DRAFT)
                .artifacts(artifact == null ? List.of() : List.of(artifact))
                .runtimeProfiles(runtimeProfile == null ? List.of() : List.of(runtimeProfile))
                .build();
    }
}
