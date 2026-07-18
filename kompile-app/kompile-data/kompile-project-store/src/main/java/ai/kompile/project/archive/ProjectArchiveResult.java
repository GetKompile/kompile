package ai.kompile.project.archive;

import java.nio.file.Path;

public record ProjectArchiveResult(Path path, ProjectArchiveManifest manifest, long totalBytes) {}
