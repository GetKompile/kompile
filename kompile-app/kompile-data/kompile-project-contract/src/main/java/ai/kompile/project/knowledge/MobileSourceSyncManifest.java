package ai.kompile.project.knowledge;

import java.util.List;
import java.util.Locale;

/** Manifest stored as {@code mobile-sync.json} in a phone-authored {@code .ksync} archive. */
public record MobileSourceSyncManifest(
        String format,
        int formatVersion,
        String projectId,
        String baseRevision,
        String deviceId,
        String createdAt,
        List<Source> sources) {

    public static final String FORMAT = "kompile-mobile-source-sync";

    public MobileSourceSyncManifest {
        if (!FORMAT.equals(format)) {
            throw new IllegalArgumentException("Unsupported mobile source sync format: " + format);
        }
        if (formatVersion != PortableKnowledge.FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported mobile source sync version: " + formatVersion);
        }
        projectId = PortableKnowledge.requireIdentity(projectId, "projectId");
        baseRevision = PortableKnowledge.requireRevision(baseRevision, "baseRevision");
        deviceId = PortableKnowledge.requireIdentity(deviceId, "deviceId");
        createdAt = PortableKnowledge.requireIdentity(createdAt, "createdAt");
        sources = sources == null ? List.of() : List.copyOf(sources);
        PortableKnowledge.normalizeInventory(sources.stream()
                .map(source -> new PortableKnowledge.Entry(source.path(), source.size(), source.sha256()))
                .toList());
    }

    public record Source(
            String path,
            long size,
            String sha256,
            String title,
            String mediaType) {
        public Source {
            path = PortableKnowledge.requireMarkdownPath(path);
            if (size < 0 || size > PortableKnowledge.MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException("Invalid mobile source size: " + size);
            }
            sha256 = PortableKnowledge.requireRevision(sha256, "sha256");
            title = PortableKnowledge.requireIdentity(title, "title");
            mediaType = mediaType == null || mediaType.isBlank()
                    ? "text/markdown"
                    : mediaType.trim().toLowerCase(Locale.ROOT);
            if (!"text/markdown".equals(mediaType) && !"text/plain".equals(mediaType)) {
                throw new IllegalArgumentException("Unsupported mobile source media type: " + mediaType);
            }
        }
    }
}
