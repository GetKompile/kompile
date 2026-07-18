package ai.kompile.project.archive;

public record ProjectArchiveExportOptions(boolean allowRunning, boolean includeSensitiveFiles) {
    public ProjectArchiveExportOptions(boolean allowRunning) {
        this(allowRunning, false);
    }

    public static ProjectArchiveExportOptions defaults() {
        return new ProjectArchiveExportOptions(false, false);
    }
}
