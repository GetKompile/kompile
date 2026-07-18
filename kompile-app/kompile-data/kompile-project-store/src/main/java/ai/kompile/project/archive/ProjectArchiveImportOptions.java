package ai.kompile.project.archive;

public record ProjectArchiveImportOptions(
        int maxEntries,
        long maxEntrySize,
        long maxTotalSize,
        long maxManifestSize) {
    public ProjectArchiveImportOptions {
        if (maxEntries < 1 || maxEntrySize < 1 || maxTotalSize < 1 || maxManifestSize < 1) {
            throw new IllegalArgumentException("Archive limits must be positive");
        }
    }

    public static ProjectArchiveImportOptions defaults() {
        return new ProjectArchiveImportOptions(100_000, 20L * 1024 * 1024 * 1024,
                100L * 1024 * 1024 * 1024, 16L * 1024 * 1024);
    }
}
