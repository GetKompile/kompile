package ai.kompile.cli.common.metrics;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Explicit maintenance: remove global records only after their session payload was purged. */
public final class ToolJournalCompactor {
    private static final int MAX_RECORD_BYTES = 8 * 1024 * 1024;
    private final Path root;
    public ToolJournalCompactor(Path conversations) { root = conversations.toAbsolutePath().normalize(); }

    public record FileResult(String file, long originalBytes, long retainedBytes, long removedRecords) {
        public long reclaimableBytes() { return originalBytes - retainedBytes; }
    }
    public record Result(boolean dryRun, List<FileResult> files) {
        public long reclaimableBytes() { return files.stream().mapToLong(FileResult::reclaimableBytes).sum(); }
        public long removedRecords() { return files.stream().mapToLong(FileResult::removedRecords).sum(); }
    }

    public Result compact(boolean dryRun) throws IOException {
        ToolJournalFiles.safe(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return new Result(dryRun, List.of());
        // A dry run must not create lock files. With existing locks it still gets a consistent snapshot.
        Path rootLock = root.resolve(".retention.lock"); ToolJournalFiles.safe(rootLock);
        if (dryRun && !Files.exists(rootLock, LinkOption.NOFOLLOW_LINKS)) return run(true);
        try (FileChannel channel = FileChannel.open(rootLock,
                dryRun ? new java.nio.file.OpenOption[]{StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS}
                        : new java.nio.file.OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS});
             var lock = channel.tryLock()) {
            if (lock == null) throw new IOException("Retention/restore is running");
            return run(dryRun);
        } catch (java.nio.channels.OverlappingFileLockException busy) {
            throw new IOException("Retention/restore is running", busy);
        }
    }

    private Result run(boolean dryRun) throws IOException {
        Set<String> purged = purgedSessions();
        if (purged.isEmpty()) return new Result(dryRun, List.of());
        List<FileResult> results = new ArrayList<>();
        for (String name : List.of("all-tool-calls.jsonl", "tool-usage.jsonl")) {
            Path file = root.resolve("tool-calls").resolve(name); ToolJournalFiles.safe(file);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
            if (dryRun) results.add(rewrite(file, purged, true));
            else results.add(ToolJournalFiles.locked(file, () -> rewrite(file, purged, false)));
        }
        return new Result(dryRun, List.copyOf(results));
    }

    private Set<String> purgedSessions() throws IOException {
        Set<String> ids = new HashSet<>();
        Path markers = root.resolve(".purged"); ToolJournalFiles.safe(markers);
        if (!Files.isDirectory(markers, LinkOption.NOFOLLOW_LINKS)) return ids;
        try (var paths = Files.list(markers)) {
            for (Path marker : paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String id = marker.getFileName().toString();
                if (!id.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]*") || id.contains("..")) continue;
                // Reappearing session data or a pin is never erased from a shared journal.
                boolean present = false;
                for (String suffix : List.of("", ".txt", ".context.json", ".context.jsonl", ".context", ".pinned")) {
                    Path path = root.resolve(id + suffix); ToolJournalFiles.safe(path);
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { present = true; break; }
                }
                Path perSession = root.resolve("tool-calls").resolve(id + ".jsonl"); ToolJournalFiles.safe(perSession);
                Path details = root.resolve("tool-calls/details").resolve(id); ToolJournalFiles.safe(details);
                if (!present && !Files.exists(perSession, LinkOption.NOFOLLOW_LINKS)
                        && !Files.exists(details, LinkOption.NOFOLLOW_LINKS)) ids.add(id);
            }
        }
        return ids;
    }

    private FileResult rewrite(Path file, Set<String> purged, boolean dryRun) throws IOException {
        Path temporary = dryRun ? null : Files.createTempFile(file.getParent(), ".compact-", ".tmp");
        long original = Files.size(file), retained = 0, removed = 0, consumed = 0;
        try {
            try (var input = new BufferedInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS));
                 OutputStream output = dryRun ? OutputStream.nullOutputStream()
                         : new BufferedOutputStream(Files.newOutputStream(temporary, StandardOpenOption.WRITE))) {
                ByteArrayOutputStream record = new ByteArrayOutputStream();
                int next;
                while (consumed < original && (next = input.read()) != -1) {
                    consumed++;
                    record.write(next);
                    if (record.size() > MAX_RECORD_BYTES) throw new IOException("Journal record exceeds 8 MiB; no compaction committed");
                    if (next != '\n') continue;
                    byte[] bytes = record.toByteArray();
                    if (belongsToPurged(bytes, purged)) removed++;
                    else { output.write(bytes); retained += bytes.length; }
                    record.reset();
                }
                // Never discard/repair an unterminated record during compaction.
                byte[] tail = record.toByteArray(); output.write(tail); retained += tail.length;
                if (consumed != original || Files.size(file) != original)
                    throw new IOException("Journal changed during compaction; no replacement committed");
            }
            if (!dryRun && removed > 0) {
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
                // Atomic replacement is required; no truncate/in-place rewrite fallback.
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                try (FileChannel parent = FileChannel.open(file.getParent(), StandardOpenOption.READ)) { parent.force(true); }
            }
            return new FileResult(file.getFileName().toString(), original, retained, removed);
        } finally { if (temporary != null) Files.deleteIfExists(temporary); }
    }

    private boolean belongsToPurged(byte[] bytes, Set<String> purged) {
        try {
            JsonNode record = JsonUtils.standardMapper().readerFor(JsonNode.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(bytes);
            // Keep malformed/unknown/anonymous records byte-for-byte, and every revision of live calls.
            return record != null && record.isObject() && record.path("sessionId").isTextual()
                    && purged.contains(record.path("sessionId").asText());
        } catch (IOException malformed) { return false; }
    }
}
