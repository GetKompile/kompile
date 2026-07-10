package ai.kompile.process.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileExecutableArtifactResolverTest {

    @TempDir
    Path artifactRoot;

    @Test
    void resolvesVerifiedArtifactWithinConfiguredRoot() throws Exception {
        String content = "_output = {total: amount * 2};";
        Files.writeString(artifactRoot.resolve("billing.js"), content);
        String hash = "sha256:" + sha256(content);

        ExecutableRef reference = ExecutableRef.builder()
                .kind(ExecutableKind.SCRIPT).artifactId("billing").version("1")
                .contentHash(hash).configuration(Map.of("language", "javascript")).build();
        ProcessRelease release = ProcessRelease.builder().artifacts(List.of(
                ArtifactManifest.builder().artifactId("billing").version("1")
                        .kind(ExecutableKind.SCRIPT).contentHash(hash)
                        .storageUri("billing.js").build())).build();

        ExecutableArtifactResolver.ResolvedExecutable resolved =
                new FileExecutableArtifactResolver(artifactRoot, 1024).resolve(release, reference);

        assertEquals(content, resolved.content());
        assertEquals("javascript", resolved.language());
    }

    @Test
    void rejectsTamperedContent() throws Exception {
        Files.writeString(artifactRoot.resolve("script.js"), "tampered");
        ExecutableRef reference = ExecutableRef.builder()
                .kind(ExecutableKind.SCRIPT).artifactId("script").version("1")
                .contentHash("sha256:" + sha256("expected")).build();
        ProcessRelease release = ProcessRelease.builder().artifacts(List.of(
                ArtifactManifest.builder().artifactId("script").version("1")
                        .kind(ExecutableKind.SCRIPT).contentHash(reference.getContentHash())
                        .storageUri("script.js").build())).build();

        assertThrows(IllegalStateException.class,
                () -> new FileExecutableArtifactResolver(artifactRoot, 1024)
                        .resolve(release, reference));
    }

    @Test
    void rejectsPathOutsideArtifactRoot() throws Exception {
        Path outside = Files.createTempFile("outside-artifact", ".js");
        String content = "1 + 1";
        Files.writeString(outside, content);
        String hash = "sha256:" + sha256(content);
        ExecutableRef reference = ExecutableRef.builder()
                .kind(ExecutableKind.SCRIPT).artifactId("outside").version("1")
                .contentHash(hash).build();
        ProcessRelease release = ProcessRelease.builder().artifacts(List.of(
                ArtifactManifest.builder().artifactId("outside").version("1")
                        .kind(ExecutableKind.SCRIPT).contentHash(hash)
                        .storageUri(outside.toUri().toString()).build())).build();

        assertThrows(IllegalStateException.class,
                () -> new FileExecutableArtifactResolver(artifactRoot, 1024)
                        .resolve(release, reference));
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
