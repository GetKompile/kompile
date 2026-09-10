/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.agent.graph;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32C;

/**
 * Selector-free capability bound by {@link AgentInstanceStore} to one trusted agent and external
 * conversation key.
 *
 * <p>Each record is framed as {@code magic:int, payloadLength:int, crc32c:long, payload:JSON}. A
 * partial final header or payload is a recoverable torn tail and is truncated while holding both
 * the bounded JVM stripe and the cross-process file lock. Invalid framing, checksum, schema, or
 * sequence before that incomplete tail fails closed.</p>
 */
public final class AgentConversationSession {

    public static final long DEFAULT_MAX_JOURNAL_BYTES = 64L * 1024L * 1024L;
    public static final long DEFAULT_MAX_EVENTS = 100_000L;
    public static final int MAX_CONVERSATION_KEY_BYTES = 4096;
    public static final long MAX_CONFIGURED_JOURNAL_BYTES = 512L * 1024L * 1024L;

    static final String JOURNAL_SUFFIX = ".events";
    static final String LOCK_SUFFIX = ".lock";
    static final int FRAME_MAGIC = 0x4b434531; // KCE1
    static final int FRAME_HEADER_BYTES = Integer.BYTES + Integer.BYTES + Long.BYTES;
    private static final int MAX_FRAME_PAYLOAD_BYTES = ConversationEventDraft.MAX_CONTENT_BYTES
            + (ConversationEventDraft.MAX_METADATA_ENTRIES
            * (ConversationEventDraft.MAX_METADATA_KEY_BYTES
            + ConversationEventDraft.MAX_METADATA_VALUE_BYTES))
            + 64 * 1024;
    private static final int JVM_LOCK_STRIPE_COUNT = 256;
    private static final ReentrantLock[] JVM_LOCKS = createLockStripes();
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final Set<String> EVENT_FIELDS = Set.of(
            "schemaVersion", "eventId", "sequence", "timestamp", "type", "role", "content",
            "metadata", "idempotencyKey");

    private final AgentPrincipal principal;
    private final Path conversationDirectory;
    private final Path journalPath;
    private final Path lockPath;
    private final long maxJournalBytes;
    private final long maxEvents;
    private final ObjectMapper mapper;

    AgentConversationSession(
            AgentPrincipal principal,
            Path conversationDirectory,
            String externalConversationKey,
            long maxJournalBytes,
            long maxEvents) throws IOException {
        this.principal = Objects.requireNonNull(principal, "principal");
        this.conversationDirectory = Objects.requireNonNull(
                conversationDirectory, "conversationDirectory").toAbsolutePath().normalize();
        validateConversationKey(externalConversationKey);
        if (maxJournalBytes < FRAME_HEADER_BYTES + 1
                || maxJournalBytes > MAX_CONFIGURED_JOURNAL_BYTES) {
            throw new IllegalArgumentException(
                    "maxJournalBytes must be between " + (FRAME_HEADER_BYTES + 1) + " and "
                            + MAX_CONFIGURED_JOURNAL_BYTES);
        }
        if (maxEvents < 1) {
            throw new IllegalArgumentException("maxEvents must be positive");
        }
        String hash = hashConversationKey(externalConversationKey);
        this.journalPath = conversationDirectory.resolve(hash + JOURNAL_SUFFIX);
        this.lockPath = conversationDirectory.resolve(hash + LOCK_SUFFIX);
        this.maxJournalBytes = maxJournalBytes;
        this.maxEvents = maxEvents;
        this.mapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        requireSafeDirectory();
    }

    /** The trusted identity is evidence only; no selector can be changed after binding. */
    public AgentPrincipal principal() {
        return principal;
    }

    /** Load the newest events under explicit limits and report whenever older events were omitted. */
    public ConversationTail loadTail(ConversationLoadLimits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        return withLock(() -> {
            if (!Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
                return new ConversationTail(List.of(), 0, false);
            }
            try (JournalChannel journal = openJournal(false)) {
                ScanResult scan = scanAndRecover(journal.channel());
                List<ConversationEvent> all = scan.events();
                int start = all.size();
                int selectedBytes = 0;
                while (start > 0 && all.size() - start < limits.maxEvents()) {
                    ConversationEvent candidate = all.get(start - 1);
                    int candidateBytes = ConversationEventDraft.utf8Length(candidate.content());
                    if (candidateBytes > limits.maxContentBytes()) {
                        if (start == all.size()) {
                            throw new IOException(
                                    "Newest conversation event exceeds the requested content limit");
                        }
                        break;
                    }
                    if (candidateBytes > limits.maxContentBytes() - selectedBytes) {
                        break;
                    }
                    selectedBytes += candidateBytes;
                    start--;
                }
                List<ConversationEvent> tail = List.copyOf(all.subList(start, all.size()));
                return new ConversationTail(tail, all.size(), start > 0);
            }
        });
    }

    /** Append one event after assigning its durable identity, sequence, and timestamp. */
    public ConversationEvent append(ConversationEventDraft draft) throws IOException {
        return appendAll(List.of(Objects.requireNonNull(draft, "draft"))).get(0);
    }

    /**
     * Append one keyed event under the journal lock, or acknowledge the already durable event with
     * the same key. The scan and append share both JVM and cross-process locks, so concurrent callers
     * and callers after a process restart can never publish a second frame for the key.
     */
    public ConversationEvent appendIdempotent(ConversationEventDraft draft) throws IOException {
        ConversationEventDraft safeDraft = Objects.requireNonNull(draft, "draft");
        String key = ConversationEventDraft.requiredIdempotencyKey(safeDraft.idempotencyKey());
        return withLock(() -> {
            try (JournalChannel journal = openJournal(true)) {
                ScanResult scan = scanAndRecover(journal.channel());
                for (ConversationEvent event : scan.events()) {
                    if (key.equals(event.idempotencyKey())) {
                        return event;
                    }
                }
                return appendLocked(journal, scan, List.of(safeDraft)).get(0);
            }
        });
    }

    /** Append a batch atomically with respect to all writers. No member is silently omitted. */
    public List<ConversationEvent> appendAll(List<ConversationEventDraft> drafts) throws IOException {
        List<ConversationEventDraft> safeDrafts = immutableDrafts(drafts);
        if (safeDrafts.isEmpty()) {
            return List.of();
        }
        return withLock(() -> {
            try (JournalChannel journal = openJournal(true)) {
                ScanResult scan = scanAndRecover(journal.channel());
                return appendLocked(journal, scan, safeDrafts);
            }
        });
    }

    /**
     * Atomically import legacy events only when the canonical journal has no events, then append a
     * non-replayable migration marker. This is the one-time migration seam used by KClaw.
     *
     * @return true only for the process that performed the import/marker append
     */
    public boolean appendMigrationIfEmpty(
            String migrationSource,
            List<ConversationEventDraft> importedEvents) throws IOException {
        Objects.requireNonNull(migrationSource, "migrationSource");
        if (migrationSource.isBlank()
                || ConversationEventDraft.utf8Length(migrationSource)
                > ConversationEventDraft.MAX_METADATA_VALUE_BYTES) {
            throw new IllegalArgumentException("migrationSource is blank or too large");
        }
        List<ConversationEventDraft> safeImports = immutableDrafts(importedEvents);
        return withLock(() -> {
            ScanResult scan;
            try (JournalChannel journal = openJournal(true)) {
                scan = scanAndRecover(journal.channel());
                if (!scan.events().isEmpty()) {
                    return false;
                }
            }
            List<ConversationEventDraft> batch = new ArrayList<>(safeImports.size() + 1);
            batch.addAll(safeImports);
            batch.add(new ConversationEventDraft(
                    ConversationEventType.MIGRATION,
                    ConversationRole.SYSTEM,
                    "",
                    Map.of("source", migrationSource)));
            PreparedBatch prepared = prepareBatch(scan, batch);
            publishJournalAtomically(prepared.frames());
            return true;
        });
    }

    /** Delete the canonical journal durably. The bounded lock file is deliberately retained. */
    public void clear() throws IOException {
        withLock(() -> {
            if (Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
                requireSafeRegularFile(journalPath);
                Files.delete(journalPath);
                forceDirectory(conversationDirectory);
            }
            return null;
        });
    }

    Path journalPath() {
        return journalPath;
    }

    Path lockPath() {
        return lockPath;
    }

    private List<ConversationEvent> appendLocked(
            JournalChannel journal,
            ScanResult scan,
            List<ConversationEventDraft> drafts) throws IOException {
        PreparedBatch prepared = prepareBatch(scan, drafts);

        FileChannel channel = journal.channel();
        channel.position(scan.validBytes());
        writeFully(channel, ByteBuffer.wrap(prepared.frames()));
        channel.force(true);
        if (journal.created()) {
            forceDirectory(conversationDirectory);
        }
        return prepared.events();
    }

    private PreparedBatch prepareBatch(
            ScanResult scan,
            List<ConversationEventDraft> drafts) throws IOException {
        if (drafts.size() > maxEvents - scan.events().size()) {
            throw new IOException("Conversation event quota exceeded: maximum is " + maxEvents);
        }
        long sequence = scan.events().isEmpty()
                ? 1L
                : Math.addExact(scan.events().get(scan.events().size() - 1).sequence(), 1L);
        List<ConversationEvent> events = new ArrayList<>(drafts.size());
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        for (ConversationEventDraft draft : drafts) {
            ConversationEvent event = new ConversationEvent(
                    ConversationEvent.CURRENT_SCHEMA_VERSION,
                    UUID.randomUUID(),
                    sequence++,
                    Instant.now(),
                    draft.type(),
                    draft.role(),
                    draft.content(),
                    draft.metadata(),
                    draft.idempotencyKey());
            byte[] frame = encodeFrame(event);
            if (frame.length > maxJournalBytes - scan.validBytes() - frames.size()) {
                throw new IOException(
                        "Conversation journal quota exceeded: maximum is " + maxJournalBytes
                                + " bytes");
            }
            frames.write(frame);
            events.add(event);
        }
        return new PreparedBatch(List.copyOf(events), frames.toByteArray());
    }

    private void publishJournalAtomically(byte[] completeJournal) throws IOException {
        Path staging = conversationDirectory.resolve(
                "." + journalPath.getFileName() + "." + UUID.randomUUID() + ".staging");
        boolean published = false;
        try {
            try (FileChannel output = createPrivateFile(staging, false)) {
                writeFully(output, ByteBuffer.wrap(completeJournal));
                output.force(true);
            }
            forceDirectory(conversationDirectory);
            try {
                Files.move(staging, journalPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException(
                        "Atomic publication is required for conversation migration", unsupported);
            }
            published = true;
            requireSafeRegularFile(journalPath);
            forceDirectory(conversationDirectory);
        } finally {
            if (!published) {
                Files.deleteIfExists(staging);
            }
        }
    }

    private byte[] encodeFrame(ConversationEvent event) throws IOException {
        byte[] payload = encodeEvent(event);
        if (payload.length < 1 || payload.length > MAX_FRAME_PAYLOAD_BYTES) {
            throw new IOException("Encoded conversation event exceeds the frame payload limit");
        }
        CRC32C checksum = new CRC32C();
        checksum.update(payload, 0, payload.length);
        ByteBuffer frame = ByteBuffer.allocate(FRAME_HEADER_BYTES + payload.length);
        frame.putInt(FRAME_MAGIC);
        frame.putInt(payload.length);
        frame.putLong(checksum.getValue());
        frame.put(payload);
        return frame.array();
    }

    private byte[] encodeEvent(ConversationEvent event) throws IOException {
        ObjectNode document = mapper.createObjectNode();
        document.put("schemaVersion", event.schemaVersion());
        document.put("eventId", event.eventId().toString());
        document.put("sequence", event.sequence());
        document.put("timestamp", event.timestamp().toString());
        document.put("type", event.type().name());
        document.put("role", event.role().name());
        document.put("content", event.content());
        if (event.idempotencyKey() != null) {
            document.put("idempotencyKey", event.idempotencyKey());
        }
        ObjectNode metadata = document.putObject("metadata");
        event.metadata().forEach(metadata::put);
        return mapper.writeValueAsBytes(document);
    }

    private ScanResult scanAndRecover(FileChannel channel) throws IOException {
        long size = channel.size();
        long maximumRecoverableBytes = maxJournalBytes
                + FRAME_HEADER_BYTES + (long) MAX_FRAME_PAYLOAD_BYTES;
        if (size > maximumRecoverableBytes) {
            throw new IOException(
                    "Conversation journal exceeds the bounded recovery maximum of "
                            + maximumRecoverableBytes + " bytes");
        }
        ByteBuffer bytes = ByteBuffer.allocate((int) size);
        channel.position(0);
        while (bytes.hasRemaining()) {
            int read = channel.read(bytes);
            if (read < 0) {
                break;
            }
        }
        bytes.flip();
        List<ConversationEvent> events = new ArrayList<>();
        int validBytes = 0;
        long expectedSequence = 1L;
        boolean tornTail = false;
        while (bytes.hasRemaining()) {
            int frameStart = bytes.position();
            if (bytes.remaining() < FRAME_HEADER_BYTES) {
                tornTail = true;
                break;
            }
            int magic = bytes.getInt();
            int payloadLength = bytes.getInt();
            long expectedChecksum = bytes.getLong();
            if (magic != FRAME_MAGIC) {
                throw corruption(frameStart, "invalid frame magic");
            }
            if (payloadLength < 1 || payloadLength > MAX_FRAME_PAYLOAD_BYTES) {
                throw corruption(frameStart, "invalid frame payload length");
            }
            if (bytes.remaining() < payloadLength) {
                tornTail = true;
                break;
            }
            byte[] payload = new byte[payloadLength];
            bytes.get(payload);
            CRC32C checksum = new CRC32C();
            checksum.update(payload, 0, payload.length);
            if (checksum.getValue() != expectedChecksum) {
                throw corruption(frameStart, "frame checksum mismatch");
            }
            ConversationEvent event = decodeEvent(payload, frameStart);
            if (event.sequence() != expectedSequence) {
                throw corruption(frameStart,
                        "non-monotonic sequence: expected " + expectedSequence + " but found "
                                + event.sequence());
            }
            expectedSequence++;
            events.add(event);
            validBytes = bytes.position();
            if (validBytes > maxJournalBytes) {
                throw new IOException(
                        "Conversation journal exceeds the configured maximum of "
                                + maxJournalBytes + " bytes");
            }
            if (events.size() > maxEvents) {
                throw new IOException(
                        "Conversation event quota exceeded: maximum is " + maxEvents);
            }
        }
        if (tornTail) {
            channel.truncate(validBytes);
            channel.force(true);
        }
        return new ScanResult(List.copyOf(events), validBytes);
    }

    private ConversationEvent decodeEvent(byte[] payload, int frameStart) throws IOException {
        try {
            JsonNode document = mapper.readTree(payload);
            if (document == null || !document.isObject()) {
                throw corruption(frameStart, "event payload is not an object");
            }
            rejectUnknownFields(document, EVENT_FIELDS, frameStart);
            JsonNode metadataNode = required(document, "metadata", frameStart);
            if (!metadataNode.isObject()) {
                throw corruption(frameStart, "event metadata is not an object");
            }
            Map<String, String> metadata = new LinkedHashMap<>();
            var fields = metadataNode.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!field.getValue().isTextual()) {
                    throw corruption(frameStart, "event metadata value is not text");
                }
                metadata.put(field.getKey(), field.getValue().textValue());
            }
            return new ConversationEvent(
                    requiredInt(document, "schemaVersion", frameStart),
                    parseUuid(requiredText(document, "eventId", frameStart), frameStart),
                    requiredLong(document, "sequence", frameStart),
                    Instant.parse(requiredText(document, "timestamp", frameStart)),
                    ConversationEventType.valueOf(requiredText(document, "type", frameStart)),
                    ConversationRole.valueOf(requiredText(document, "role", frameStart)),
                    requiredText(document, "content", frameStart),
                    metadata,
                    optionalText(document, "idempotencyKey", frameStart));
        } catch (IOException failure) {
            throw failure;
        } catch (DateTimeParseException | IllegalArgumentException failure) {
            throw corruption(frameStart, "invalid event payload", failure);
        }
    }

    private <T> T withLock(IoSupplier<T> action) throws IOException {
        requireSafeDirectory();
        ReentrantLock jvmLock = JVM_LOCKS[Math.floorMod(lockPath.hashCode(), JVM_LOCKS.length)];
        jvmLock.lock();
        try (FileChannel lockChannel = openLockFile(); FileLock fileLock = lockChannel.lock()) {
            if (!fileLock.isValid()) {
                throw new IOException("Unable to acquire conversation journal file lock");
            }
            requireSafeDirectory();
            return action.get();
        } finally {
            jvmLock.unlock();
        }
    }

    private FileChannel openLockFile() throws IOException {
        boolean created = false;
        FileChannel channel;
        try {
            channel = createPrivateFile(lockPath, false);
            created = true;
        } catch (FileAlreadyExistsException existing) {
            requireSafeRegularFile(lockPath);
            channel = FileChannel.open(
                    lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        }
        if (created) {
            channel.force(true);
            forceDirectory(conversationDirectory);
        }
        return channel;
    }

    private JournalChannel openJournal(boolean create) throws IOException {
        if (!Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) {
                throw new IOException("Conversation journal disappeared during locked read");
            }
            try {
                return new JournalChannel(createPrivateFile(journalPath, true), true);
            } catch (FileAlreadyExistsException concurrent) {
                // The file lock makes this unexpected, but revalidate instead of trusting the race.
            }
        }
        requireSafeRegularFile(journalPath);
        return new JournalChannel(FileChannel.open(
                journalPath,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS), false);
    }

    private FileChannel createPrivateFile(Path path, boolean readable) throws IOException {
        Set<StandardOpenOption> standard = readable
                ? Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE)
                : Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        FileAttribute<?>[] attributes = supportsPosix(conversationDirectory)
                ? new FileAttribute<?>[] {
                    PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS)
                }
                : new FileAttribute<?>[0];
        Set<java.nio.file.OpenOption> options = new java.util.HashSet<>(standard);
        options.add(LinkOption.NOFOLLOW_LINKS);
        FileChannel channel = FileChannel.open(path, options, attributes);
        try {
            requireSafeRegularFile(path);
            return channel;
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    private void requireSafeDirectory() throws IOException {
        if (Files.isSymbolicLink(conversationDirectory)
                || !Files.isDirectory(conversationDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Agent conversation storage is not a safe directory: " + conversationDirectory);
        }
        enforcePrivatePermissions(conversationDirectory, PRIVATE_DIRECTORY_PERMISSIONS);
        rejectSymlink(journalPath);
        rejectSymlink(lockPath);
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new IOException("Symbolic links are forbidden in agent conversation storage: " + path);
        }
    }

    private static void requireSafeRegularFile(Path path) throws IOException {
        rejectSymlink(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Required agent conversation file is missing or unsafe: " + path);
        }
        enforcePrivatePermissions(path, PRIVATE_FILE_PERMISSIONS);
    }

    private static void enforcePrivatePermissions(
            Path path,
            Set<PosixFilePermission> required) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            java.io.File file = path.toFile();
            boolean restricted = file.setReadable(false, false)
                    && file.setWritable(false, false)
                    && (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    || file.setExecutable(false, false));
            restricted &= file.setReadable(true, true) && file.setWritable(true, true);
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                restricted &= file.setExecutable(true, true);
            }
            if (!restricted) {
                throw new IOException("Unable to enforce owner-only permissions on " + path);
            }
            return;
        }
        view.setPermissions(required);
        if (!view.readAttributes().permissions().equals(required)) {
            throw new IOException("Unable to enforce owner-only permissions on " + path);
        }
    }

    private static boolean supportsPosix(Path path) throws IOException {
        return Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class);
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(
                directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    private static void validateConversationKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("externalConversationKey is required");
        }
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_CONVERSATION_KEY_BYTES) {
            throw new IllegalArgumentException(
                    "externalConversationKey exceeds " + MAX_CONVERSATION_KEY_BYTES + " UTF-8 bytes");
        }
    }

    static String hashConversationKey(String key) {
        validateConversationKey(key);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256", impossible);
        }
    }

    private static List<ConversationEventDraft> immutableDrafts(List<ConversationEventDraft> drafts) {
        Objects.requireNonNull(drafts, "drafts");
        drafts.forEach(draft -> Objects.requireNonNull(draft, "conversation event draft"));
        return List.copyOf(drafts);
    }

    private static void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
    }

    private static JsonNode required(JsonNode document, String field, int offset) throws IOException {
        JsonNode value = document.get(field);
        if (value == null || value.isNull()) {
            throw corruption(offset, "missing event field: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode document, String field, int offset) throws IOException {
        JsonNode value = required(document, field, offset);
        if (!value.isTextual()) {
            throw corruption(offset, "event field is not text: " + field);
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode document, String field, int offset) throws IOException {
        JsonNode value = document.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw corruption(offset, "event field is not text: " + field);
        }
        return value.textValue();
    }

    private static int requiredInt(JsonNode document, String field, int offset) throws IOException {
        JsonNode value = required(document, field, offset);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw corruption(offset, "event field is not an integer: " + field);
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode document, String field, int offset) throws IOException {
        JsonNode value = required(document, field, offset);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw corruption(offset, "event field is not a long integer: " + field);
        }
        return value.longValue();
    }

    private static UUID parseUuid(String value, int offset) throws IOException {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw corruption(offset, "event UUID is not canonical");
            }
            return parsed;
        } catch (IllegalArgumentException invalid) {
            throw corruption(offset, "event UUID is invalid", invalid);
        }
    }

    private static void rejectUnknownFields(JsonNode object, Set<String> allowed, int offset)
            throws IOException {
        var names = object.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw corruption(offset, "unknown event field: " + name);
            }
        }
    }

    private static IOException corruption(int offset, String detail) {
        return new IOException(
                "Conversation journal corruption at byte " + offset + ": " + detail);
    }

    private static IOException corruption(int offset, String detail, Throwable cause) {
        return new IOException(
                "Conversation journal corruption at byte " + offset + ": " + detail, cause);
    }

    private static ReentrantLock[] createLockStripes() {
        ReentrantLock[] locks = new ReentrantLock[JVM_LOCK_STRIPE_COUNT];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private record ScanResult(List<ConversationEvent> events, int validBytes) {
    }

    private record PreparedBatch(List<ConversationEvent> events, byte[] frames) {
    }

    private record JournalChannel(FileChannel channel, boolean created) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }
}
