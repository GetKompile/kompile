package ai.kompile.process.release;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Resolves local content-addressed artifacts from a restricted storage root.
 */
@Component
public class FileExecutableArtifactResolver implements ExecutableArtifactResolver {

    private final Path artifactRoot;
    private final long maxArtifactBytes;

    public FileExecutableArtifactResolver(
            @Value("${kompile.process.artifact-dir:${user.home}/.kompile/processes/artifacts}")
            String artifactRoot,
            @Value("${kompile.process.max-executable-artifact-bytes:1048576}")
            long maxArtifactBytes) {
        this(Paths.get(artifactRoot), maxArtifactBytes);
    }

    FileExecutableArtifactResolver(Path artifactRoot, long maxArtifactBytes) {
        this.artifactRoot = artifactRoot.toAbsolutePath().normalize();
        this.maxArtifactBytes = maxArtifactBytes;
    }

    @Override
    public ResolvedExecutable resolve(ProcessRelease release, ExecutableRef reference) {
        if (release == null || reference == null) {
            throw new IllegalArgumentException("Release and executable reference are required");
        }
        ArtifactManifest manifest = release.getArtifacts() == null ? null
                : release.getArtifacts().stream()
                .filter(candidate -> reference.getArtifactId().equals(candidate.getArtifactId()))
                .filter(candidate -> reference.getVersion().equals(candidate.getVersion()))
                .findFirst()
                .orElse(null);
        if (manifest == null) {
            throw new IllegalStateException("Executable artifact is absent from pinned release");
        }
        if (manifest.getKind() != reference.getKind()) {
            throw new IllegalStateException("Executable artifact kind does not match pinned reference");
        }
        if (!normalizeHash(manifest.getContentHash()).equals(normalizeHash(reference.getContentHash()))) {
            throw new IllegalStateException("Executable artifact manifest hash does not match pinned reference");
        }

        Path artifact = resolvePath(manifest.getStorageUri());
        try {
            long size = Files.size(artifact);
            if (size > maxArtifactBytes) {
                throw new IllegalStateException("Executable artifact exceeds maximum size of "
                        + maxArtifactBytes + " bytes");
            }
            byte[] content = Files.readAllBytes(artifact);
            String actualHash = sha256(content);
            if (!actualHash.equals(normalizeHash(reference.getContentHash()))) {
                throw new IllegalStateException("Executable artifact content hash verification failed");
            }
            return new ResolvedExecutable(manifest, new String(content, StandardCharsets.UTF_8),
                    resolveLanguage(reference, manifest));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read executable artifact " + artifact, e);
        }
    }

    private Path resolvePath(String storageUri) {
        if (storageUri == null || storageUri.isBlank()) {
            throw new IllegalStateException("Executable artifact storageUri is required");
        }
        URI uri = URI.create(storageUri);
        if (uri.getScheme() != null && !"file".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException("Unsupported executable artifact URI scheme: " + uri.getScheme());
        }
        Path path = uri.getScheme() == null ? artifactRoot.resolve(storageUri) : Paths.get(uri);
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(artifactRoot)) {
            throw new IllegalStateException("Executable artifact escapes configured artifact root");
        }
        if (!Files.isRegularFile(normalized)) {
            throw new IllegalStateException("Executable artifact does not exist: " + normalized);
        }
        return normalized;
    }

    private String resolveLanguage(ExecutableRef reference, ArtifactManifest manifest) {
        Map<String, Object> configuration = reference.getConfiguration();
        if (configuration != null && configuration.get("language") != null) {
            return configuration.get("language").toString();
        }
        if (manifest.getMetadata() != null && manifest.getMetadata().get("language") != null) {
            return manifest.getMetadata().get("language").toString();
        }
        String mediaType = manifest.getMediaType();
        return mediaType != null && mediaType.toLowerCase().contains("python")
                ? "python" : "javascript";
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String normalizeHash(String hash) {
        if (hash == null || hash.isBlank()) {
            throw new IllegalStateException("Executable artifact content hash is required");
        }
        return hash.regionMatches(true, 0, "sha256:", 0, 7)
                ? hash.substring(7).toLowerCase() : hash.toLowerCase();
    }
}
