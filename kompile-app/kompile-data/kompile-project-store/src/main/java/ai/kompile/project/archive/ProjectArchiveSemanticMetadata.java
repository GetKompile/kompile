package ai.kompile.project.archive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Describes the portable knowledge-base state carried by a project archive.
 *
 * <p>The values are deliberately declarative and contain no credentials. Consumers can use
 * {@link #externalRequirements()} to explain which bindings must be supplied after import.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectArchiveSemanticMetadata(
        int schemaVersion,
        String description,
        String lifecycle,
        List<String> tags,
        List<String> componentTypes,
        List<String> portableAssets,
        List<String> rebuildableAssets,
        List<String> externalRequirements) {

    public ProjectArchiveSemanticMetadata {
        schemaVersion = schemaVersion < 1 ? 1 : schemaVersion;
        tags = immutable(tags);
        componentTypes = immutable(componentTypes);
        portableAssets = immutable(portableAssets);
        rebuildableAssets = immutable(rebuildableAssets);
        externalRequirements = immutable(externalRequirements);
    }

    public static ProjectArchiveSemanticMetadata empty() {
        return new ProjectArchiveSemanticMetadata(
                1, null, null, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return description == null
                && lifecycle == null
                && tags.isEmpty()
                && componentTypes.isEmpty()
                && portableAssets.isEmpty()
                && rebuildableAssets.isEmpty()
                && externalRequirements.isEmpty();
    }

    private static List<String> immutable(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
