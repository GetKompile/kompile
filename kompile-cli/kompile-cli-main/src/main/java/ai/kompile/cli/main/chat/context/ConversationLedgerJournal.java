package ai.kompile.cli.main.chat.context;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Objects;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Session-owned, forced JSONL journal. Only startup replays history; a commit
 * serializes its own payload and compares the last committed record and length.
 * Atomic installation of journal.jsonl and a forced committed marker make
 * migration resumable without ever falling back to an obsolete legacy snapshot.
 * Original schema-1 files are never changed or removed.
 *
 * <p>Each LF-terminated envelope has schemaVersion=2, revision, version, kind,
 * payload (a JSON string), and sha256. The checksum covers UTF-8 of schema,
 * revision, version, kind, and the decoded payload string, separated by LF.
 * Revision starts at zero (seed), then one (ready), and advances for every row.
 * Only event/checkpoint rows advance conversation version. Keeping payload as
 * text avoids re-canonicalizing opaque JSON while validating its checksum.</p>
 */
final class ConversationLedgerJournal {
    static final int SCHEMA = 2;
    // Serialize this class's shared-root leases inside the JVM: Java does not
    // permit overlapping shared FileLocks, even through different channels.
    private static final ReentrantLock JVM_LOCK = new ReentrantLock();
    private final ObjectMapper mapper;
    private final Path root;
    private final String id;
    private final Path directory;
    private final Path file;
    private static final byte[] MARKER = "conversation-ledger-journal-v2\n".getBytes(StandardCharsets.UTF_8);
    private final CommitHook commitHook;
    @FunctionalInterface
    interface CommitHook { void beforeForce() throws IOException; }
    private long offset;
    private long lastStart;
    private byte[] lastRecord;
    private long revision;
    private long version;
    private long lastSequence;
    private long coveredThrough;
    private boolean initialized;
    private boolean poisoned;
    private FileTime modifiedTime;
    private Object fileKey;

    ConversationLedgerJournal(ObjectMapper mapper, Path root, String id) {
        this(mapper, root, id, () -> {});
    }

    // Package-private failure seam: deterministic rollback tests, no global state.
    ConversationLedgerJournal(ObjectMapper mapper, Path root, String id, CommitHook commitHook) {
        this.commitHook = commitHook;
        this.mapper = mapper.copy().disable(SerializationFeature.INDENT_OUTPUT)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.root = root;
        this.id = id;
        this.directory = root.resolve(id + ".context");
        this.file = directory.resolve("journal.jsonl");
    }

    Path attachmentDirectory() { return directory.resolve("attachments"); }

    record Restored(ConversationLedger.State state, ConversationLedger.NativeSession nativeSession) {}
    private record Row(int schemaVersion, long revision, long version, String kind,
                       String payload, String sha256) {}
    @FunctionalInterface
    private interface Operation<T> { T run() throws IOException; }

    Restored open() throws IOException {
        return locked(() -> {
            Path marker = directory.resolve("committed");
            safe(marker);
            boolean marked = Files.exists(marker, LinkOption.NOFOLLOW_LINKS);
            if (marked && (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(marker) != MARKER.length || !MessageDigest.isEqual(MARKER, Files.readAllBytes(marker))))
                throw corrupt("invalid migration marker");
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (marked) throw corrupt("committed journal disappeared; legacy state is not a fallback");
                migrate();
            }
            Restored restored = replay();
            if (!marked) publishMarker(marker);
            initialized = true;
            return restored;
        });
    }

    /** expectedVersion concerns conversation state; revision also orders native bindings. */
    void append(long expectedVersion, long newVersion, String kind, Object payload) throws IOException {
        locked(() -> {
            requireCurrent(expectedVersion);
            validateCommit(expectedVersion, newVersion, kind, payload);
            byte[] record = encode(revision + 1, newVersion, kind, mapper.valueToTree(payload));
            long start = offset;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.position(start);
                try {
                    writeFully(channel, record);
                    commitHook.beforeForce();
                    channel.force(true); // Nothing is acknowledged before this succeeds.
                    rememberFingerprint();
                } catch (IOException failure) {
                    try {
                        channel.truncate(start);
                        channel.force(true);
                        rememberFingerprint();
                    } catch (IOException rollback) {
                        poisoned = true;
                        failure.addSuppressed(rollback);
                    }
                    throw failure;
                }
            }
            lastStart = start;
            lastRecord = record;
            offset += record.length;
            revision++;
            version = newVersion;
            if (payload instanceof ConversationLedger.Event event) lastSequence = event.sequence();
            if (payload instanceof ConversationLedger.CompactionCheckpoint checkpoint)
                coveredThrough = checkpoint.coveredThroughSequence();
            return null;
        });
    }

    private void validateCommit(long expected, long next, String kind, Object payload) throws IOException {
        switch (kind) {
            case "event" -> {
                if (next != expected + 1 || !(payload instanceof ConversationLedger.Event event))
                    throw corrupt("invalid event commit");
                validateEvent(event, lastSequence + 1);
            }
            case "checkpoint" -> {
                if (next != expected + 1 || !(payload instanceof ConversationLedger.CompactionCheckpoint checkpoint))
                    throw corrupt("invalid checkpoint commit");
                validateCheckpoint(checkpoint, lastSequence, coveredThrough);
            }
            case "native" -> {
                if (next != expected) throw corrupt("native binding changed conversation version");
                if (payload != null && (!(payload instanceof ConversationLedger.NativeSession nativeState)
                        || nativeState.syncedVersion() != expected)) throw corrupt("invalid native commit");
                nativeSession(mapper.valueToTree(payload), expected);
            }
            default -> throw corrupt("invalid commit kind");
        }
    }

    boolean isCurrent(long expectedVersion) {
        try { return locked(() -> { requireCurrent(expectedVersion); return true; }); }
        catch (IOException e) { return false; }
    }

    void storeBlob(long expectedVersion, String digest, byte[] bytes) throws IOException {
        locked(() -> {
            requireCurrent(expectedVersion);
            Path blobs = attachmentDirectory();
            safe(blobs);
            Files.createDirectories(blobs);
            forceDirectory(directory);
            Path target = blobs.resolve(digest);
            safe(target);
            Path temporary = Files.createTempFile(blobs, digest, ".tmp");
            try {
                forceWrite(temporary, bytes);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                forceDirectory(blobs);
            } finally { Files.deleteIfExists(temporary); }
            return null;
        });
    }

    private void requireCurrent(long expectedVersion) throws IOException {
        if (!initialized || poisoned || version != expectedVersion)
            throw new IOException("Conversation journal is not writable at version " + expectedVersion);
        safe(file);
        Path marker = directory.resolve("committed");
        safe(marker);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) != MARKER.length
                || !MessageDigest.isEqual(MARKER, Files.readAllBytes(marker)))
            throw corrupt("migration marker disappeared or changed");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Conversation journal disappeared");
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!Objects.equals(attributes.fileKey(), fileKey) || !attributes.lastModifiedTime().equals(modifiedTime))
            throw new IOException("Stale conversation ledger: journal identity or timestamp changed");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.size() != offset) throw new IOException("Stale conversation ledger: journal length changed");
            ByteBuffer tail = ByteBuffer.allocate(lastRecord.length);
            channel.position(lastStart);
            while (tail.hasRemaining()) {
                if (channel.read(tail) < 0) throw new IOException("Truncated conversation journal");
            }
            if (!MessageDigest.isEqual(tail.array(), lastRecord))
                throw new IOException("Stale conversation ledger: journal commit changed");
        }
    }

    private void publishMarker(Path marker) throws IOException {
        Path temporary = Files.createTempFile(directory, "marker-", ".tmp");
        try {
            forceWrite(temporary, MARKER);
            Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(directory);
        } finally { Files.deleteIfExists(temporary); }
    }

    private void migrate() throws IOException {
        Path legacy = root.resolve(id + ".context.json");
        Path nativeFile = root.resolve(id + ".native-session.json");
        safe(legacy);
        safe(nativeFile);
        JsonNode state = Files.exists(legacy, LinkOption.NOFOLLOW_LINKS)
                ? mapper.readTree(legacy.toFile())
                : mapper.valueToTree(new ConversationLedger.State(1, 0, List.of(), null));
        ConversationLedger.State typed = state(state);
        JsonNode nativeState = Files.exists(nativeFile, LinkOption.NOFOLLOW_LINKS)
                ? mapper.readTree(nativeFile.toFile()) : mapper.nullNode();
        nativeSession(nativeState, typed.version());
        ObjectNode seed = mapper.createObjectNode();
        seed.set("state", state); // Preserve the original tree, including extension fields.
        seed.set("nativeSession", nativeState);
        byte[] first = encode(0, typed.version(), "seed", seed);
        byte[] ready = encode(1, typed.version(), "ready", mapper.nullNode());
        Path temporary = Files.createTempFile(directory, "migration-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                writeFully(channel, first);
                writeFully(channel, ready);
                channel.force(true);
            }
            // No replace or non-atomic fallback: an incomplete migration is never authoritative.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(directory);
        } finally { Files.deleteIfExists(temporary); }
    }

    private Restored replay() throws IOException {
        List<ConversationLedger.Event> events = new ArrayList<>();
        ConversationLedger.CompactionCheckpoint checkpoint = null;
        ConversationLedger.NativeSession nativeState = null;
        long committed = 0;
        long priorRevision = -1;
        long currentVersion = 0;
        byte[] latest = null;
        long latestStart = 0;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) != -1) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    byte b = buffer.get();
                    line.write(b);
                    if (b != '\n') continue;
                    byte[] raw = line.toByteArray();
                    line.reset();
                    Row row = decode(raw);
                    if (row.revision() != priorRevision + 1) throw corrupt("non-contiguous revision");
                    JsonNode payload = mapper.readTree(row.payload());
                    if (priorRevision < 0) {
                        if (!"seed".equals(row.kind())) throw corrupt("missing migration seed");
                        ConversationLedger.State initial = state(payload.get("state"));
                        events.addAll(initial.events() == null ? List.of() : initial.events());
                        checkpoint = initial.checkpoint();
                        currentVersion = initial.version();
                        nativeState = nativeSession(payload.get("nativeSession"), currentVersion);
                        if (row.version() != currentVersion) throw corrupt("seed version mismatch");
                    } else {
                        if (priorRevision == 0 && !"ready".equals(row.kind()))
                            throw corrupt("missing migration marker");
                        long expected = currentVersion;
                        switch (row.kind()) {
                            case "ready" -> {
                                if (priorRevision != 0 || !payload.isNull()) throw corrupt("invalid migration marker");
                            }
                            case "event" -> {
                                ConversationLedger.Event event = mapper.treeToValue(payload, ConversationLedger.Event.class);
                                validateEvent(event, events.size() + 1L);
                                events.add(event);
                                expected++;
                            }
                            case "checkpoint" -> {
                                ConversationLedger.CompactionCheckpoint next = mapper.treeToValue(payload,
                                        ConversationLedger.CompactionCheckpoint.class);
                                validateCheckpoint(next, events.size(), checkpoint == null ? 0 : checkpoint.coveredThroughSequence());
                                checkpoint = next;
                                expected++;
                            }
                            case "native" -> {
                                nativeState = nativeSession(payload, currentVersion);
                                if (nativeState != null && nativeState.syncedVersion() != currentVersion)
                                    throw corrupt("native binding does not match conversation version");
                            }
                            default -> throw corrupt("unknown record kind " + row.kind());
                        }
                        if (row.version() != expected) throw corrupt("non-contiguous conversation version");
                        currentVersion = expected;
                    }
                    priorRevision = row.revision();
                    latestStart = committed;
                    latest = raw;
                    committed += raw.length;
                }
                buffer.clear();
            }
            if (priorRevision < 1) throw corrupt("incomplete migration marker");
            // Only a non-newline-terminated EOF fragment is uncommitted. Every
            // complete record, including the last, must validate; never skip it.
            if (line.size() > 0) {
                channel.truncate(committed);
                channel.force(true);
            }
        } catch (RuntimeException e) { throw corrupt("invalid record", e); }
        offset = committed;
        revision = priorRevision;
        version = currentVersion;
        lastStart = latestStart;
        lastRecord = latest;
        lastSequence = events.size();
        coveredThrough = checkpoint == null ? 0 : checkpoint.coveredThroughSequence();
        rememberFingerprint();
        return new Restored(new ConversationLedger.State(1, version, List.copyOf(events), checkpoint), nativeState);
    }

    private ConversationLedger.State state(JsonNode node) throws IOException {
        if (node == null || !node.isObject() || !node.path("schemaVersion").isIntegralNumber()
                || !node.path("version").isIntegralNumber()) throw corrupt("missing state schema/version");
        // Unknown fields stay in the immutable seed, rather than being discarded
        // by reserializing the typed State at later commits.
        ConversationLedger.State state = mapper.treeToValue(node, ConversationLedger.State.class);
        if (state.schemaVersion() != 1 || state.version() < 0) throw corrupt("unsupported state schema/version");
        List<ConversationLedger.Event> events = state.events() == null ? List.of() : state.events();
        for (int i = 0; i < events.size(); i++) validateEvent(events.get(i), i + 1L);
        if (state.version() < events.size() + (state.checkpoint() == null ? 0L : 1L))
            throw corrupt("state version precedes events/checkpoint");
        if (state.checkpoint() != null) validateCheckpoint(state.checkpoint(), events.size(), 0);
        return state;
    }

    private static void validateEvent(ConversationLedger.Event event, long sequence) throws IOException {
        if (event == null || event.sequence() != sequence || event.type() == null
                || event.createdAt() == null) throw corrupt("invalid event sequence or type");
    }

    private static void validateCheckpoint(ConversationLedger.CompactionCheckpoint checkpoint,
                                           long lastSequence, long previousCoverage) throws IOException {
        if (checkpoint == null || checkpoint.coveredThroughSequence() < previousCoverage
                || checkpoint.coveredThroughSequence() > lastSequence
                || checkpoint.summary() == null || checkpoint.summary().isBlank()
                || checkpoint.tokensBefore() < 0 || checkpoint.tokensAfter() < 0
                || checkpoint.createdAt() == null) throw corrupt("invalid checkpoint");
    }

    private ConversationLedger.NativeSession nativeSession(JsonNode node, long currentVersion) throws IOException {
        if (node == null || node.isNull()) return null;
        ConversationLedger.NativeSession nativeState = mapper.treeToValue(node, ConversationLedger.NativeSession.class);
        if (nativeState.transport() == null || nativeState.sessionId() == null || nativeState.sessionId().isBlank()
                || nativeState.syncedVersion() < 0 || nativeState.syncedVersion() > currentVersion
                || nativeState.updatedAt() == null) throw corrupt("invalid native binding");
        return nativeState;
    }

    private byte[] encode(long revision, long version, String kind, JsonNode payload) throws IOException {
        String data = mapper.writeValueAsString(payload);
        Row row = new Row(SCHEMA, revision, version, kind, data, checksum(revision, version, kind, data));
        return (mapper.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private Row decode(byte[] raw) throws IOException {
        JsonNode envelope = mapper.readTree(raw);
        if (!envelope.isObject() || !envelope.path("schemaVersion").isIntegralNumber()
                || !envelope.path("revision").isIntegralNumber() || !envelope.path("version").isIntegralNumber()
                || !envelope.path("kind").isTextual() || !envelope.path("payload").isTextual()
                || !envelope.path("sha256").isTextual()) throw corrupt("missing envelope fields");
        Row row = mapper.readerFor(Row.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(raw);
        if (row.schemaVersion() != SCHEMA || row.revision() < 0 || row.version() < 0
                || row.kind() == null || row.payload() == null || row.sha256() == null
                || !row.sha256().equals(checksum(row.revision(), row.version(), row.kind(), row.payload())))
            throw corrupt("invalid schema or checksum");
        return row;
    }

    private static String checksum(long revision, long version, String kind, String data) {
        try {
            byte[] bytes = (SCHEMA + "\n" + revision + "\n" + version + "\n" + kind + "\n" + data)
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private <T> T locked(Operation<T> operation) throws IOException {
        JVM_LOCK.lock();
        try {
            safe(root);
            boolean newRoot = !Files.exists(root, LinkOption.NOFOLLOW_LINKS);
            Files.createDirectories(root);
            if (newRoot) forceDirectory(root.getParent());
            Path rootLock = root.resolve(".retention.lock");
            safe(rootLock);
            return ai.kompile.cli.common.metrics.ToolJournalFiles.sharedRetention(root, () -> {
                Path retired = root.resolve(".retired").resolve(id);
                safe(retired);
                if (Files.exists(retired, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Conversation is retired: " + id);
                safe(directory);
                if (initialized && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Conversation directory disappeared");
                Files.createDirectories(directory);
                forceDirectory(root);
                Path lockPath = directory.resolve("writer.lock");
                safe(lockPath);
                try (FileChannel writer = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock lease = writer.tryLock()) {
                    if (lease == null) throw new IOException("Conversation writer is active");
                    safe(file);
                    return operation.run();
                }
            });
        } catch (OverlappingFileLockException busy) {
            throw new IOException("Conversation retention or writer lease already held in this JVM", busy);
        } finally { JVM_LOCK.unlock(); }
    }

    private void rememberFingerprint() throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        modifiedTime = attributes.lastModifiedTime();
        fileKey = attributes.fileKey();
    }

    private static void safe(Path path) throws IOException {
        for (Path current = path.toAbsolutePath(); current != null; current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("Symlinked conversation path: " + current);
    }

    private static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static void forceWrite(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            writeFully(channel, bytes);
            channel.force(true);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    private static IOException corrupt(String message) { return new IOException("Corrupt conversation journal: " + message); }
    private static IOException corrupt(String message, Throwable cause) { return new IOException("Corrupt conversation journal: " + message, cause); }
}
