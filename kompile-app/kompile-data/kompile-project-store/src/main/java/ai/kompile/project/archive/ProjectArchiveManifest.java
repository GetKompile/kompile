package ai.kompile.project.archive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectArchiveManifest(
        String format,
        int formatVersion,
        String projectId,
        String name,
        Instant createdAt,
        String generator,
        String defaultGraph,
        ProjectArchiveSemanticMetadata semantic,
        List<Entry> entries) {

    public ProjectArchiveManifest {
        semantic = semantic == null ? ProjectArchiveSemanticMetadata.empty() : semantic;
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /**
     * Compatibility constructor for version-one callers that predate semantic inventory metadata.
     */
    public ProjectArchiveManifest(
            String format,
            int formatVersion,
            String projectId,
            String name,
            Instant createdAt,
            String generator,
            String defaultGraph,
            List<Entry> entries) {
        this(format, formatVersion, projectId, name, createdAt, generator, defaultGraph,
                ProjectArchiveSemanticMetadata.empty(), entries);
    }

    public record Entry(String path, long size, String sha256, boolean executable) {}
}
