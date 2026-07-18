package ai.kompile.project.archive;

import java.nio.file.Path;
import java.util.List;

/**
 * Read-only structural preflight result for a {@code .kproject} archive.
 *
 * <p>Inspection validates the ZIP layout, manifest, inventory, paths, and declared sizes. Payload
 * checksums and the embedded project identity are verified again while importing.</p>
 */
public record ProjectArchiveInspection(
        Path archive,
        ProjectArchiveManifest manifest,
        long declaredTotalBytes,
        List<String> warnings) {

    public ProjectArchiveInspection {
        archive = archive == null ? null : archive.toAbsolutePath().normalize();
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
