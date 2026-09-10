package ai.kompile.staging.execution.text;

import ai.kompile.modelmanager.registry.ModelEntry;
import ai.kompile.modelmanager.registry.ModelMetadata;
import ai.kompile.modelmanager.registry.RegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegisteredLlmModelResolverTest {

    private static final String MODEL_ID = "verified-llm";

    @TempDir
    Path temporary;

    private Path modelsRoot;
    private Path modelFile;
    private RegistryService registryService;
    private RegisteredLlmModelResolver resolver;

    @BeforeEach
    void setUp() throws Exception {
        modelsRoot = temporary.resolve("models");
        modelFile = modelsRoot.resolve("llms/verified/model.sdnb");
        Files.createDirectories(modelFile.getParent());
        Files.writeString(modelFile, "registered model bytes", StandardCharsets.UTF_8);

        registryService = mock(RegistryService.class);
        when(registryService.getModelsDir()).thenReturn(modelsRoot);
        when(registryService.getModel(MODEL_ID)).thenReturn(Optional.of(entry(
                "llms/verified", "model.sdnb", "v4", checksum(modelFile))));
        resolver = new RegisteredLlmModelResolver(registryService);
    }

    @Test
    void resolvesRegistryFileAndAcceptsOnlyItsExactExplicitPath() throws Exception {
        RegisteredLlmModelResolver.VerifiedModel implicit = resolver.resolve(MODEL_ID, null);
        RegisteredLlmModelResolver.VerifiedModel explicit =
                resolver.resolve(MODEL_ID, modelFile.toString());

        assertEquals(MODEL_ID, implicit.modelId());
        assertEquals("v4", implicit.modelVersion());
        assertEquals(checksum(modelFile), implicit.registeredChecksum());
        assertEquals(modelFile.toRealPath(), implicit.modelFile());
        assertEquals(implicit, explicit);
    }

    @Test
    void rejectsMissingIncompleteOrMismatchedRegistryIntegrity() throws Exception {
        when(registryService.getModel(MODEL_ID)).thenReturn(Optional.of(entry(
                "llms/verified", "model.sdnb", " ", checksum(modelFile))));
        assertThrows(RegisteredLlmModelResolver.ModelVerificationException.class,
                () -> resolver.resolve(MODEL_ID, null));

        when(registryService.getModel(MODEL_ID)).thenReturn(Optional.of(entry(
                "llms/verified", "model.sdnb", "v4", "sha256:abcd")));
        assertThrows(RegisteredLlmModelResolver.ModelVerificationException.class,
                () -> resolver.resolve(MODEL_ID, null));

        when(registryService.getModel(MODEL_ID)).thenReturn(Optional.of(entry(
                "llms/verified", "model.sdnb", "v4", "sha256:" + "0".repeat(64))));
        assertThrows(RegisteredLlmModelResolver.ModelVerificationException.class,
                () -> resolver.resolve(MODEL_ID, null));

        Files.delete(modelFile);
        when(registryService.getModel(MODEL_ID)).thenReturn(Optional.of(entry(
                "llms/verified", "model.sdnb", "v4", "sha256:" + "0".repeat(64))));
        assertThrows(RegisteredLlmModelResolver.ModelVerificationException.class,
                () -> resolver.resolve(MODEL_ID, null));
    }

    @Test
    void rejectsRegisteredPathEscapeAndSymlinkComponents() throws Exception {
        Path escaped = temporary.resolve("outside/model.sdnb");
        Files.createDirectories(escaped.getParent());
        Files.writeString(escaped, "outside", StandardCharsets.UTF_8);
        when(registryService.getModel(MODEL_ID)).thenReturn(Optional.of(entry(
                "../outside", "model.sdnb", "v4", checksum(escaped))));
        assertThrows(RegisteredLlmModelResolver.ModelVerificationException.class,
                () -> resolver.resolve(MODEL_ID, null));

        Path linkedTarget = temporary.resolve("linked-target");
        Files.createDirectories(linkedTarget);
        Path linkedModel = linkedTarget.resolve("model.sdnb");
        Files.writeString(linkedModel, "linked", StandardCharsets.UTF_8);
        Path link = modelsRoot.resolve("llms/link");
        try {
            Files.createSymbolicLink(link, linkedTarget);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            return; // The path-escape assertion above still applies on filesystems without symlinks.
        }
        when(registryService.getModel(MODEL_ID)).thenReturn(Optional.of(entry(
                "llms/link", "model.sdnb", "v4", checksum(linkedModel))));
        assertThrows(RegisteredLlmModelResolver.ModelVerificationException.class,
                () -> resolver.resolve(MODEL_ID, null));
    }

    @Test
    void rejectsExplicitPathThatDoesNotResolveToRegisteredFile() throws Exception {
        Path arbitrary = modelsRoot.resolve("llms/verified/other.sdnb");
        Files.writeString(arbitrary, "registered model bytes", StandardCharsets.UTF_8);

        assertThrows(RegisteredLlmModelResolver.ModelVerificationException.class,
                () -> resolver.resolve(MODEL_ID, arbitrary.toString()));
        assertThrows(RegisteredLlmModelResolver.ModelVerificationException.class,
                () -> resolver.resolve(MODEL_ID, temporary.resolve("elsewhere.sdnb").toString()));
    }

    private static ModelEntry entry(String path, String modelFile,
                                    String version, String checksum) {
        return ModelEntry.builder()
                .modelId(MODEL_ID)
                .path(path)
                .modelFile(modelFile)
                .checksum(checksum)
                .metadata(ModelMetadata.builder().version(version).build())
                .build();
    }

    private static String checksum(Path path) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
