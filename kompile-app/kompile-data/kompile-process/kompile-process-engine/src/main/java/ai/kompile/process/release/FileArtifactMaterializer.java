package ai.kompile.process.release;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class FileArtifactMaterializer implements ArtifactMaterializer {

    private final Path artifactRoot;
    private final long maxBytes;

    public FileArtifactMaterializer(
            @Value("${kompile.process.artifact-dir:${user.home}/.kompile/processes/artifacts}") String root,
            @Value("${kompile.process.max-executable-artifact-bytes:1048576}") long maxBytes) {
        this(Paths.get(root), maxBytes);
    }

    FileArtifactMaterializer(Path root, long maxBytes) {
        this.artifactRoot = root.toAbsolutePath().normalize();
        this.maxBytes = maxBytes;
    }

    @Override
    public ArtifactDeployment materialize(ArtifactManifest manifest, byte[] content) {
        if (manifest == null || content == null) {
            throw new IllegalArgumentException("Artifact manifest and content are required");
        }
        if (content.length > maxBytes) {
            throw new IllegalArgumentException("Artifact exceeds maximum size of " + maxBytes + " bytes");
        }
        String actual = sha256(content);
        if (!actual.equals(normalizeHash(manifest.getContentHash()))) {
            throw new IllegalArgumentException("Artifact upload content hash does not match manifest");
        }
        Path target = resolveTarget(manifest.getStorageUri());
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) {
                if (!sha256(Files.readAllBytes(target)).equals(actual)) {
                    throw new IllegalStateException("Artifact target already contains different content");
                }
                return result(manifest, target, content.length, true);
            }
            Path temporary = Files.createTempFile(target.getParent(), ".artifact-", ".tmp");
            try {
                Files.write(temporary, content);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, target);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return result(manifest, target, content.length, false);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to materialize artifact " + target, exception);
        }
    }

    private Path resolveTarget(String storageUri) {
        if (storageUri == null || storageUri.isBlank()) {
            throw new IllegalArgumentException("Artifact storageUri is required");
        }
        URI uri = URI.create(storageUri);
        if (uri.getScheme() != null && !"file".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Unsupported artifact URI scheme: " + uri.getScheme());
        }
        Path path = uri.getScheme() == null ? artifactRoot.resolve(storageUri) : Paths.get(uri);
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(artifactRoot)) {
            throw new IllegalArgumentException("Artifact target escapes configured artifact root");
        }
        return normalized;
    }

    private ArtifactDeployment result(ArtifactManifest manifest, Path target, long size, boolean present) {
        return new ArtifactDeployment(manifest.getArtifactId(), manifest.getVersion(),
                manifest.getContentHash(), target.toUri().toString(), size, Instant.now(), present);
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String normalizeHash(String hash) {
        if (hash == null || hash.isBlank()) {
            throw new IllegalArgumentException("Artifact content hash is required");
        }
        return hash.regionMatches(true, 0, "sha256:", 0, 7)
                ? hash.substring(7).toLowerCase() : hash.toLowerCase();
    }
}
