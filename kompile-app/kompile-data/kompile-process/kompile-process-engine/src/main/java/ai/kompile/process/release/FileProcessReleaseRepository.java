package ai.kompile.process.release;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * JSON-backed release repository using atomic replacement so a process crash cannot leave a
 * partially written release manifest.
 */
@Repository
public class FileProcessReleaseRepository implements ProcessReleaseRepository {

    private final Path releaseDirectory;
    private final ObjectMapper objectMapper;

    public FileProcessReleaseRepository(
            @Value("${kompile.process.release-dir:${user.home}/.kompile/processes/releases}")
            String releaseDirectory) {
        this(Paths.get(releaseDirectory), JsonUtils.newStandardMapper()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL));
    }

    FileProcessReleaseRepository(Path releaseDirectory, ObjectMapper objectMapper) {
        this.releaseDirectory = releaseDirectory;
        this.objectMapper = objectMapper;
        initialize();
    }

    @PostConstruct
    public final void initialize() {
        try {
            Files.createDirectories(releaseDirectory);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create process release directory " + releaseDirectory, e);
        }
    }

    @Override
    public synchronized ProcessRelease save(ProcessRelease release) {
        if (release == null || release.getId() == null || release.getId().isBlank()) {
            throw new IllegalArgumentException("A release with a non-empty ID is required");
        }
        Path target = pathFor(release.getId());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            objectMapper.writeValue(temporary.toFile(), release);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return release;
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the original persistence failure.
            }
            throw new IllegalStateException("Unable to persist process release " + release.getId(), e);
        }
    }

    @Override
    public Optional<ProcessRelease> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        Path path = pathFor(id);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(read(path));
    }

    @Override
    public List<ProcessRelease> findAll() {
        if (!Files.isDirectory(releaseDirectory)) {
            return List.of();
        }
        List<ProcessRelease> releases = new ArrayList<>();
        try (Stream<Path> paths = Files.list(releaseDirectory)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .map(this::read)
                    .forEach(releases::add);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to list process releases", e);
        }
        return List.copyOf(releases);
    }

    private ProcessRelease read(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), ProcessRelease.class);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read process release " + path, e);
        }
    }

    private Path pathFor(String releaseId) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(releaseId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return releaseDirectory.resolve(encoded + ".json");
    }
}
