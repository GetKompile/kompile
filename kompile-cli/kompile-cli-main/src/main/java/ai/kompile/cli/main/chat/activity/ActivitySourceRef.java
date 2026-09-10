package ai.kompile.cli.main.chat.activity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Safe, non-executable reference to retained evidence. */
public record ActivitySourceRef(
        String namespace,
        String identifier,
        String kind,
        String path,
        boolean available) {

    public ActivitySourceRef {
        namespace = clean(namespace, "unknown", 80);
        identifier = clean(identifier, "unknown", 240);
        kind = clean(kind, "evidence", 80);
        path = clean(path, "", 1_024);
    }

    public static ActivitySourceRef logical(String namespace, String identifier, String kind) {
        return new ActivitySourceRef(namespace, identifier, kind, "", true);
    }

    public static ActivitySourceRef file(String namespace, String identifier, String kind,
                                         Path file, Path permittedRoot) {
        if (file == null || permittedRoot == null) {
            return new ActivitySourceRef(namespace, identifier, kind, "", false);
        }
        try {
            Path safe = resolve(file.toString(), permittedRoot);
            return new ActivitySourceRef(namespace, identifier, kind, safe.toString(),
                    Files.isRegularFile(safe, LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | RuntimeException ignored) {
            return new ActivitySourceRef(namespace, identifier, kind, "", false);
        }
    }

    /** Resolve this reference only beneath the caller-approved root. */
    public Optional<Path> resolve(Path permittedRoot) throws IOException {
        if (path.isBlank() || permittedRoot == null) return Optional.empty();
        return Optional.of(resolve(path, permittedRoot));
    }

    public static Path resolve(String requested, Path permittedRoot) throws IOException {
        if (requested == null || requested.isBlank() || permittedRoot == null) {
            throw new IOException("Evidence path is missing");
        }
        Path root = permittedRoot.toAbsolutePath().normalize();
        rejectSymlinkParents(root);
        Path raw = Path.of(requested);
        Path candidate = (raw.isAbsolute() ? raw : root.resolve(raw)).normalize();
        if (!candidate.startsWith(root)) throw new IOException("Evidence path escapes permitted root");
        rejectSymlinkComponents(root, candidate);
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(root)) throw new IOException("Evidence path resolves outside permitted root");
        }
        return candidate;
    }

    private static void rejectSymlinkParents(Path root) throws IOException {
        for (Path current = root; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Permitted evidence root contains a symbolic link");
            }
        }
    }

    private static void rejectSymlinkComponents(Path root, Path candidate) throws IOException {
        Path relative = root.relativize(candidate);
        Path current = root;
        for (Path component : relative) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Evidence path contains a symbolic link");
            }
        }
    }

    private static String clean(String value, String fallback, int max) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.replace('\n', ' ').replace('\r', ' ').strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max - 1) + "…";
    }
}
