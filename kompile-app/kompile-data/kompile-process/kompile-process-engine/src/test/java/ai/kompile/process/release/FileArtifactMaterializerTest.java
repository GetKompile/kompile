package ai.kompile.process.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileArtifactMaterializerTest {

    @TempDir
    Path root;

    @Test
    void atomicallyMaterializesAndIdempotentlyReusesVerifiedContent() throws Exception {
        FileArtifactMaterializer materializer = new FileArtifactMaterializer(root, 1024);
        ArtifactManifest manifest = manifest("scripts/hello.py");

        ArtifactDeployment first = materializer.materialize(manifest, bytes("hello"));
        ArtifactDeployment second = materializer.materialize(manifest, bytes("hello"));

        assertFalse(first.alreadyPresent());
        assertTrue(second.alreadyPresent());
        assertArrayEquals(bytes("hello"), Files.readAllBytes(root.resolve("scripts/hello.py")));
    }

    @Test
    void rejectsHashMismatchWithoutCreatingTarget() {
        FileArtifactMaterializer materializer = new FileArtifactMaterializer(root, 1024);

        assertThrows(IllegalArgumentException.class,
                () -> materializer.materialize(manifest("scripts/hello.py"), bytes("tampered")));
        assertFalse(Files.exists(root.resolve("scripts/hello.py")));
    }

    @Test
    void rejectsTargetsOutsideArtifactRoot() {
        FileArtifactMaterializer materializer = new FileArtifactMaterializer(root, 1024);

        assertThrows(IllegalArgumentException.class,
                () -> materializer.materialize(manifest("../escape.py"), bytes("hello")));
    }

    private ArtifactManifest manifest(String uri) {
        return ArtifactManifest.builder()
                .artifactId("hello")
                .version("1")
                .kind(ExecutableKind.SCRIPT)
                .contentHash("sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
                .storageUri(uri)
                .build();
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
