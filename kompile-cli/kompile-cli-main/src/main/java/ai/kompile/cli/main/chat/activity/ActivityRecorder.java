package ai.kompile.cli.main.chat.activity;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.OverlappingFileLockException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Best-effort lifecycle recorder; failures are retained as health warnings, never hidden. */
public final class ActivityRecorder {
    private final ActivityStorage storage;
    private final ObjectMapper mapper;
    private final Clock clock;
    private static final long LOCK_TIMEOUT_MILLIS = 500L;
    private static final int MAX_WARNINGS = 32;
    private final AtomicLong sequence;
    private final List<String> warnings = new ArrayList<>();

    public ActivityRecorder(ActivityStorage storage) {
        this(storage, JsonUtils.standardMapper(), Clock.systemUTC());
    }

    ActivityRecorder(ActivityStorage storage, ObjectMapper mapper, Clock clock) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sequence = new AtomicLong(0L);
    }

    public synchronized ActivityEvent record(ActivityIdentity identity, String eventType,
                                              String actorId, String operationId,
                                              ActivitySourceRef source,
                                              java.util.Map<String, String> attributes) {
        if (identity == null) {
            warn("Activity recording skipped because identity is missing");
            return null;
        }
        ActivityEvent event = new ActivityEvent(ActivityEvent.CURRENT_SCHEMA_VERSION,
                UUID.randomUUID().toString(), identity, identity.runId(), actorId,
                identity.parentActorId(), 0L, clock.instant(),
                eventType, operationId, List.of(), source, attributes);
        append(event);
        return event;
    }

    public synchronized boolean append(ActivityEvent event) {
        if (event == null || event.identity() == null) return false;
        Path file = storage.eventsPath(event.identity());
        Path lockPath = file.resolveSibling(file.getFileName() + ".lock");
        try {
            storage.ensureSafe(file);
            Files.createDirectories(file.getParent());
            storage.ensureSafe(file);
            if (Files.isSymbolicLink(file) || Files.isSymbolicLink(lockPath)) {
                throw new IOException("Activity journal path must not be a symbolic link");
            }
            try (FileChannel channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                FileLock lock = acquireLock(channel);
                if (lock == null) return false;
                try (lock) {
                    long next = nextSequence(file, event.sequence());
                    String line = mapper.writeValueAsString(event.withSequence(next));
                    String separator = needsSeparator(file) ? System.lineSeparator() : "";
                    Files.writeString(file, separator + line + System.lineSeparator(), StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                            LinkOption.NOFOLLOW_LINKS);
                }
            }
            return true;
        } catch (Exception failure) {
            warn("Activity recording unavailable: " + oneLine(failure.getMessage()));
            return false;
        }
    }

    public synchronized List<String> warnings() {
        return List.copyOf(warnings);
    }

    synchronized void warn(String warning) {
        if (warning == null || warning.isBlank() || warnings.size() >= MAX_WARNINGS) return;
        warnings.add(oneLine(warning));
    }

    private FileLock acquireLock(FileChannel channel) throws IOException {
        long deadline = System.nanoTime() + LOCK_TIMEOUT_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                FileLock lock = channel.tryLock();
                if (lock != null) return lock;
            } catch (OverlappingFileLockException ignored) {
                // Another recorder in this JVM owns the short append lock.
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                warn("Activity recording interrupted while waiting for journal lock");
                return null;
            }
        }
        warn("Activity recording skipped after bounded journal-lock wait");
        return null;
    }

    private long nextSequence(Path file, long requested) throws IOException {
        long persisted = 0L;
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    try {
                        ActivityEvent existing = mapper.readValue(line, ActivityEvent.class);
                        persisted = Math.max(persisted, existing.sequence());
                    } catch (Exception ignored) {
                        // Preserve valid history while ignoring a partial final record.
                    }
                }
            }
        }
        long base = Math.max(Math.max(persisted, sequence.get()), Math.max(0L, requested));
        long next = base == Long.MAX_VALUE ? Long.MAX_VALUE : base + 1L;
        sequence.set(next);
        return next;
    }

    private static boolean needsSeparator(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) == 0L) return false;
        try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
            input.seek(input.length() - 1L);
            return input.read() != '\n';
        }
    }

    private static String oneLine(String value) {
        if (value == null || value.isBlank()) return "unknown error";
        String normalized = value.replace('\n', ' ').replace('\r', ' ').strip();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 239) + "…";
    }
}
