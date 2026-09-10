package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.common.KompileHome;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** Shared path resolution for the per-conversation activity sidecar. */
public final class ActivityStorage {
    private final Path conversationsRoot;

    public ActivityStorage() {
        this(KompileHome.homeDirectory().toPath().resolve("conversations"));
    }

    public ActivityStorage(Path conversationsRoot) {
        this.conversationsRoot = Objects.requireNonNull(conversationsRoot, "conversationsRoot")
                .toAbsolutePath().normalize();
        validateRootParents(this.conversationsRoot);
    }

    public Path conversationsRoot() {
        return conversationsRoot;
    }

    public Path transcriptPath(ActivityIdentity identity) {
        return conversationFile(identity, ".txt");
    }

    public Path metricsPath(ActivityIdentity identity) {
        return conversationFile(identity, ".metrics.json");
    }

    public Path sidecarDirectory(ActivityIdentity identity) {
        if (identity == null) throw new IllegalArgumentException("identity is required");
        return conversationsRoot.resolve(identity.storageKey() + ".activity");
    }

    public Path summaryPath(ActivityIdentity identity) {
        return sidecarDirectory(identity).resolve("activity-summary.json");
    }

    public Path eventsPath(ActivityIdentity identity) {
        return sidecarDirectory(identity).resolve("activity-events.jsonl");
    }

    Path summaryLockPath(ActivityIdentity identity) {
        return sidecarDirectory(identity).resolve("activity-summary.json.lock");
    }

    /** Reject symlinked parents before a writer creates or follows any sidecar path. */
    void ensureSafe(Path requested) throws IOException {
        ActivitySourceRef.resolve(requested.toString(), conversationsRoot);
    }

    public List<ActivityIdentity> knownIdentities(int limit) {
        if (limit <= 0 || !Files.isDirectory(conversationsRoot, LinkOption.NOFOLLOW_LINKS)) return List.of();
        Map<String, ActivityIdentity> result = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.list(conversationsRoot)) {
            paths.filter(path -> !Files.isSymbolicLink(path)
                            && (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)))
                    .sorted(Comparator.comparingLong(this::modified).reversed())
                    .forEach(path -> {
                        ActivityIdentity identity = identityFromPath(path.getFileName().toString());
                        if (identity != null) result.putIfAbsent(identity.key(), identity);
                    });
        } catch (IOException ignored) {
            return List.of();
        }
        return result.values().stream().limit(limit).toList();
    }

    public Optional<Path> safeExisting(Path requested, Path permittedRoot) throws IOException {
        if (requested == null || permittedRoot == null) return Optional.empty();
        Path resolved = ActivitySourceRef.resolve(requested.toString(), permittedRoot);
        return Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
                ? Optional.of(resolved) : Optional.empty();
    }

    private Path conversationFile(ActivityIdentity identity, String suffix) {
        if (identity == null) throw new IllegalArgumentException("identity is required");
        String prefix = "kompile".equals(identity.source()) ? ""
                : ActivityIdentity.storageSegment(identity.source()) + "__";
        return conversationsRoot.resolve(prefix + ActivityIdentity.storageSegment(identity.conversationId()) + suffix);
    }

    private ActivityIdentity identityFromPath(String name) {
        String source = "kompile";
        String encoded;
        if (name.endsWith(".activity")) {
            String key = name.substring(0, name.length() - ".activity".length());
            int separator = key.indexOf("__");
            if (separator <= 0 || separator + 2 >= key.length()) return null;
            source = ActivityIdentity.restoreSegment(key.substring(0, separator));
            encoded = key.substring(separator + 2);
        } else if (name.endsWith(".metrics.json")) {
            String key = name.substring(0, name.length() - ".metrics.json".length());
            int separator = key.indexOf("__");
            if (separator > 0) {
                source = ActivityIdentity.restoreSegment(key.substring(0, separator));
                encoded = key.substring(separator + 2);
            } else {
                encoded = key;
            }
        } else if (name.endsWith(".txt")) {
            String key = name.substring(0, name.length() - ".txt".length());
            int separator = key.indexOf("__");
            if (separator > 0) {
                source = ActivityIdentity.restoreSegment(key.substring(0, separator));
                encoded = key.substring(separator + 2);
            } else {
                encoded = key;
            }
        } else {
            return null;
        }
        String conversationId = ActivityIdentity.restoreSegment(encoded);
        return conversationId.isBlank() ? null : new ActivityIdentity(source, conversationId,
                "", "", "", "");
    }

    private static void validateRootParents(Path root) {
        for (Path current = root; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("Conversation storage path contains a symbolic link: " + current);
            }
        }
    }

    private long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }
}
