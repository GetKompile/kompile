/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.agent.graph;

import ai.kompile.graph.reasoning.unified.Dtype;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Filesystem store for stable agent manifests and one full-fidelity private graph per agent.
 *
 * <p>The configured root is the {@code agents/} directory. Data is always addressed as
 * {@code <root>/<owner-uuid>/<agent-uuid>/agent.json} and
 * {@code <root>/<owner-uuid>/<agent-uuid>/graph/private.kgraph}. Only UUIDs from a trusted
 * {@link AgentPrincipal} participate in path construction. On POSIX stores, every directory is
 * forced to {@code 0700} and every private file to {@code 0600}. The root is pinned to its real
 * path, every access rejects symbolic links with {@link LinkOption#NOFOLLOW_LINKS}, and paths are
 * revalidated before publication.</p>
 *
 * <p>These defenses isolate untrusted request/model input and other OS accounts. Java's portable
 * path APIs cannot provide descriptor-relative operations for this whole workflow, so a malicious
 * process already running as the same OS user (or a privileged attacker) remains outside this
 * storage boundary.</p>
 */
public final class AgentInstanceStore {

    static final String MANIFEST_FILE_NAME = "agent.json";
    static final String GRAPH_DIRECTORY_NAME = "graph";
    static final String PRIVATE_GRAPH_FILE_NAME = "private.kgraph";
    static final String LOCK_FILE_NAME = ".private-graph.lock";
    static final String STATE_DIRECTORY_NAME = "state";
    static final String CONVERSATIONS_DIRECTORY_NAME = "conversations";

    /** Default maximum for one personalized graph archive: 256 MiB. */
    public static final long DEFAULT_MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L;

    /** Default maximum expanded payload accepted while validating one archive: 1 GiB. */
    public static final long DEFAULT_MAX_EXPANDED_ARCHIVE_BYTES = 1024L * 1024L * 1024L;

    private static final int PROVISION_ATTEMPTS = 8;
    private static final int JVM_LOCK_STRIPE_COUNT = 256;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final ReentrantLock[] JVM_LOCKS = createLockStripes();
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final Set<String> MANIFEST_FIELDS = Set.of(
            "manifestVersion", "principal", "displayName", "createdAt", "updatedAt", "revision");
    private static final Set<String> PRINCIPAL_FIELDS = Set.of("ownerId", "agentId");

    private final Path root;
    private final ObjectMapper mapper;
    private final int maxArchiveBytes;
    private final int maxExpandedArchiveBytes;
    private final DirectoryFsync directoryFsync;

    public AgentInstanceStore(Path configuredRoot) throws IOException {
        this(configuredRoot, DEFAULT_MAX_ARCHIVE_BYTES, DEFAULT_MAX_EXPANDED_ARCHIVE_BYTES);
    }

    /**
     * Create a store with an explicit per-archive byte limit. The limit applies before imports are
     * copied or hydrated and while current archives are read or hashed.
     */
    public AgentInstanceStore(Path configuredRoot, long maxArchiveBytes) throws IOException {
        this(configuredRoot, maxArchiveBytes, DEFAULT_MAX_EXPANDED_ARCHIVE_BYTES);
    }

    /**
     * Create a store with independent compressed-archive and expanded-ZIP payload limits.
     * Both limits are enforced before the semantic graph reader can allocate from archive entries.
     */
    public AgentInstanceStore(
            Path configuredRoot,
            long maxArchiveBytes,
            long maxExpandedArchiveBytes) throws IOException {
        this(configuredRoot, maxArchiveBytes, maxExpandedArchiveBytes,
                AgentInstanceStore::forceDirectory);
    }

    AgentInstanceStore(
            Path configuredRoot,
            long maxArchiveBytes,
            DirectoryFsync directoryFsync) throws IOException {
        this(configuredRoot, maxArchiveBytes, DEFAULT_MAX_EXPANDED_ARCHIVE_BYTES, directoryFsync);
    }

    AgentInstanceStore(
            Path configuredRoot,
            long maxArchiveBytes,
            long maxExpandedArchiveBytes,
            DirectoryFsync directoryFsync) throws IOException {
        if (maxArchiveBytes < 1 || maxArchiveBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "maxArchiveBytes must be between 1 and " + Integer.MAX_VALUE);
        }
        if (maxExpandedArchiveBytes < 1 || maxExpandedArchiveBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "maxExpandedArchiveBytes must be between 1 and " + Integer.MAX_VALUE);
        }
        this.root = prepareRoot(configuredRoot);
        this.mapper = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.maxArchiveBytes = (int) maxArchiveBytes;
        this.maxExpandedArchiveBytes = (int) maxExpandedArchiveBytes;
        this.directoryFsync = Objects.requireNonNull(directoryFsync, "directoryFsync");
    }

    /**
     * Provision a new agent for the trusted owner. The agent UUID is always generated here; display
     * text is manifest data only and can never influence the storage location.
     */
    public AgentInstance provision(UUID trustedOwnerId, String displayName) throws IOException {
        Objects.requireNonNull(trustedOwnerId, "trustedOwnerId");
        validateDisplayName(displayName);
        for (int attempt = 0; attempt < PROVISION_ATTEMPTS; attempt++) {
            AgentPrincipal principal = new AgentPrincipal(trustedOwnerId, UUID.randomUUID());
            AgentPaths paths = paths(principal);
            if (Files.exists(paths.agentDirectory(), LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            createDirectoriesSecure(paths.graphDirectory());
            try {
                return withAgentLock(paths, () -> {
                    if (Files.exists(paths.manifest(), LinkOption.NOFOLLOW_LINKS)) {
                        throw new FileAlreadyExistsException(paths.manifest().toString());
                    }
                    byte[] initialArchive = serializeGraph(new UnifiedGraph());
                    AgentGraphRevision initialRevision = AgentGraphRevision.fromArchive(initialArchive);
                    publishAtomically(paths.privateGraph(), initialArchive, initialRevision);

                    Instant now = Instant.now();
                    AgentInstance instance = new AgentInstance(
                            AgentInstance.CURRENT_MANIFEST_VERSION,
                            principal,
                            displayName,
                            now,
                            now,
                            1L);
                    publishAtomically(paths.manifest(), writeManifest(instance), null);
                    return instance;
                });
            } catch (FileAlreadyExistsException collision) {
                // UUID collision or a concurrent process claimed the same generated location.
            }
        }
        throw new IOException("Unable to allocate a unique server-generated agent UUID");
    }

    /** Discover all manifests owned by one trusted owner, including after a store restart. */
    public List<AgentInstance> list(UUID trustedOwnerId) throws IOException {
        Objects.requireNonNull(trustedOwnerId, "trustedOwnerId");
        Path ownerDirectory = ownerDirectory(trustedOwnerId);
        if (!Files.exists(ownerDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        requireSafeDirectory(ownerDirectory);

        List<AgentInstance> instances = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(ownerDirectory)) {
            for (Path child : children) {
                requireSafeDirectory(child);
                UUID agentId = parseCanonicalUuid(child.getFileName().toString());
                AgentPrincipal principal = new AgentPrincipal(trustedOwnerId, agentId);
                Path manifest = paths(principal).manifest();
                if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                    continue; // An interrupted provision is intentionally not discoverable.
                }
                instances.add(readManifest(principal, manifest));
            }
        }
        instances.sort(Comparator.comparing(AgentInstance::createdAt)
                .thenComparing(instance -> instance.principal().agentId()));
        return List.copyOf(instances);
    }

    public Optional<AgentInstance> find(AgentPrincipal trustedPrincipal) throws IOException {
        Objects.requireNonNull(trustedPrincipal, "trustedPrincipal");
        AgentPaths paths = paths(trustedPrincipal);
        if (!Files.exists(paths.manifest(), LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(readManifest(trustedPrincipal, paths.manifest()));
    }

    /** Bind a selector-free graph capability to an already trusted principal. */
    public AgentPrivateGraphSession open(AgentPrincipal trustedPrincipal) throws IOException {
        AgentInstance instance = find(trustedPrincipal)
                .orElseThrow(() -> new NoSuchElementException("No agent exists for the trusted principal"));
        return new AgentPrivateGraphSession(this, instance);
    }

    /**
     * Bind a selector-free canonical conversation capability to an already trusted principal and
     * external conversation key. Owner and filesystem scope are always derived inside this store.
     */
    public AgentConversationSession openConversation(
            AgentPrincipal trustedPrincipal,
            String externalConversationKey) throws IOException {
        return openConversation(
                trustedPrincipal,
                externalConversationKey,
                AgentConversationSession.DEFAULT_MAX_JOURNAL_BYTES,
                AgentConversationSession.DEFAULT_MAX_EVENTS);
    }

    AgentConversationSession openConversation(
            AgentPrincipal trustedPrincipal,
            String externalConversationKey,
            long maxJournalBytes,
            long maxEvents) throws IOException {
        Objects.requireNonNull(trustedPrincipal, "trustedPrincipal");
        AgentPaths agentPaths = paths(trustedPrincipal);
        readManifest(trustedPrincipal, agentPaths.manifest());
        Path stateDirectory = requireInsideRoot(
                agentPaths.agentDirectory().resolve(STATE_DIRECTORY_NAME));
        Path conversationsDirectory = requireInsideRoot(
                stateDirectory.resolve(CONVERSATIONS_DIRECTORY_NAME));
        createDirectoriesSecure(conversationsDirectory);
        return new AgentConversationSession(
                trustedPrincipal,
                conversationsDirectory,
                externalConversationKey,
                maxJournalBytes,
                maxEvents);
    }

    AgentGraphSnapshot readGraph(AgentPrincipal principal) throws IOException {
        byte[] archive = readCurrentArchive(paths(principal));
        AgentGraphRevision revision = AgentGraphRevision.fromArchive(archive);
        UnifiedGraph graph = loadValidatedArchive(archive);
        return new AgentGraphSnapshot(graph, revision);
    }

    AgentGraphArchive readArchive(AgentPrincipal principal) throws IOException {
        byte[] archive = readCurrentArchive(paths(principal));
        validateArchive(archive);
        return new AgentGraphArchive(archive, AgentGraphRevision.fromArchive(archive));
    }

    AgentGraphRevision mutate(
            AgentPrincipal principal,
            AgentGraphRevision expectedRevision,
            Consumer<UnifiedGraph> mutation) throws IOException {
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        Objects.requireNonNull(mutation, "mutation");
        AgentPaths paths = paths(principal);

        // The callback is application code. Build the detached candidate without holding either
        // the JVM stripe or the cross-process file lock.
        byte[] current = readCurrentArchive(paths);
        requireExpectedRevision(expectedRevision, AgentGraphRevision.fromArchive(current));
        UnifiedGraph detached = loadValidatedArchive(current);
        mutation.accept(detached);
        byte[] replacement = serializeGraph(detached);
        validateArchive(replacement);
        AgentGraphRevision candidateRevision = AgentGraphRevision.fromArchive(replacement);

        return withAgentLock(paths, () -> {
            requireExpectedRevision(expectedRevision, currentRevision(paths));
            publishAtomically(paths.privateGraph(), replacement, candidateRevision);
            return candidateRevision;
        });
    }

    AgentGraphRevision replaceArchive(
            AgentPrincipal principal,
            AgentGraphRevision expectedRevision,
            byte[] archive) throws IOException {
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        Objects.requireNonNull(archive, "archive");
        AgentPaths paths = paths(principal);
        requireExpectedRevision(expectedRevision, currentRevision(paths));
        requireArchiveSize(archive.length, "imported archive");
        byte[] exactReplacement = archive.clone();
        validateArchive(exactReplacement);
        AgentGraphRevision candidateRevision = AgentGraphRevision.fromArchive(exactReplacement);

        return withAgentLock(paths, () -> {
            requireExpectedRevision(expectedRevision, currentRevision(paths));
            publishAtomically(paths.privateGraph(), exactReplacement, candidateRevision);
            return candidateRevision;
        });
    }

    AgentGraphRevision currentRevision(AgentPrincipal principal) throws IOException {
        return currentRevision(paths(principal));
    }

    private AgentInstance readManifest(AgentPrincipal expected, Path manifest) throws IOException {
        JsonNode document = mapper.readTree(readNoFollow(
                manifest, MAX_MANIFEST_BYTES, "agent manifest"));
        if (document == null || !document.isObject()) {
            throw new IOException("Agent manifest must be a JSON object");
        }
        rejectUnknownFields(document, MANIFEST_FIELDS, "agent manifest");
        JsonNode principalNode = requiredField(document, "principal");
        if (!principalNode.isObject()) {
            throw new IOException("Agent manifest principal must be a JSON object");
        }
        rejectUnknownFields(principalNode, PRINCIPAL_FIELDS, "agent principal");

        AgentInstance instance;
        try {
            instance = new AgentInstance(
                    requiredInt(document, "manifestVersion"),
                    new AgentPrincipal(
                            parseCanonicalUuid(
                                    requiredText(principalNode, "ownerId"),
                                    "agent manifest ownerId"),
                            parseCanonicalUuid(
                                    requiredText(principalNode, "agentId"),
                                    "agent manifest agentId")),
                    requiredText(document, "displayName"),
                    Instant.parse(requiredText(document, "createdAt")),
                    Instant.parse(requiredText(document, "updatedAt")),
                    requiredLong(document, "revision"));
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid agent manifest", invalid);
        }
        if (!expected.equals(instance.principal())) {
            throw new IOException("Agent manifest identity does not match its trusted storage scope");
        }
        return instance;
    }

    private byte[] writeManifest(AgentInstance instance) throws IOException {
        ObjectNode document = mapper.createObjectNode();
        document.put("manifestVersion", instance.manifestVersion());
        ObjectNode principal = document.putObject("principal");
        principal.put("ownerId", instance.principal().ownerId().toString());
        principal.put("agentId", instance.principal().agentId().toString());
        document.put("displayName", instance.displayName());
        document.put("createdAt", instance.createdAt().toString());
        document.put("updatedAt", instance.updatedAt().toString());
        document.put("revision", instance.revision());
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document);
    }

    private static JsonNode requiredField(JsonNode object, String field) throws IOException {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) {
            throw new IOException("Agent manifest is missing required field: " + field);
        }
        return value;
    }

    private static String requiredText(JsonNode object, String field) throws IOException {
        JsonNode value = requiredField(object, field);
        if (!value.isTextual()) {
            throw new IOException("Agent manifest field must be text: " + field);
        }
        return value.textValue();
    }

    private static int requiredInt(JsonNode object, String field) throws IOException {
        JsonNode value = requiredField(object, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IOException("Agent manifest field must be an integer: " + field);
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode object, String field) throws IOException {
        JsonNode value = requiredField(object, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IOException("Agent manifest field must be a long integer: " + field);
        }
        return value.longValue();
    }

    private static void rejectUnknownFields(JsonNode object, Set<String> allowed, String section)
            throws IOException {
        var fields = object.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                throw new IOException("Unknown field in " + section + ": " + field);
            }
        }
    }

    private byte[] readCurrentArchive(AgentPaths paths) throws IOException {
        // A valid manifest is the publication marker for a provisioned agent.
        readManifest(paths.principal(), paths.manifest());
        return readNoFollow(paths.privateGraph(), maxArchiveBytes, "agent graph archive");
    }

    private AgentGraphRevision currentRevision(AgentPaths paths) throws IOException {
        // A valid manifest is the publication marker for a provisioned agent.
        readManifest(paths.principal(), paths.manifest());
        return hashNoFollow(paths.privateGraph());
    }

    private static void requireExpectedRevision(
            AgentGraphRevision expected,
            AgentGraphRevision actual) {
        if (!expected.equals(actual)) {
            throw new StaleAgentGraphRevisionException(expected, actual);
        }
    }

    private byte[] serializeGraph(UnifiedGraph graph) throws IOException {
        BoundedArchiveOutputStream out = new BoundedArchiveOutputStream(maxArchiveBytes);
        graph.save(out, Dtype.F64);
        return out.toByteArray();
    }

    private void validateArchive(byte[] archive) throws IOException {
        loadValidatedArchive(archive);
    }

    private UnifiedGraph loadValidatedArchive(byte[] archive) throws IOException {
        requireArchiveSize(archive.length, "agent graph archive");
        if (archive.length == 0) {
            throw new IOException("A non-empty .kgraph archive is required");
        }
        validateExpandedArchiveSize(archive);
        return UnifiedGraph.load(new ByteArrayInputStream(archive));
    }

    private void validateExpandedArchiveSize(byte[] archive) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long expandedBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                long declaredSize = entry.getSize();
                if (declaredSize > maxExpandedArchiveBytes - expandedBytes) {
                    throw expandedArchiveLimitExceeded();
                }
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expandedBytes += read;
                    if (expandedBytes > maxExpandedArchiveBytes) {
                        throw expandedArchiveLimitExceeded();
                    }
                }
            }
        }
    }

    private IOException expandedArchiveLimitExceeded() {
        return new IOException("Expanded agent graph archive exceeds the configured maximum of "
                + maxExpandedArchiveBytes + " bytes");
    }

    private void requireArchiveSize(long size, String description) throws IOException {
        if (size > maxArchiveBytes) {
            throw new IOException(description + " exceeds the configured maximum of "
                    + maxArchiveBytes + " bytes");
        }
    }

    private <T> T withAgentLock(AgentPaths paths, IoSupplier<T> action) throws IOException {
        createDirectoriesSecure(paths.graphDirectory());
        Path lockPath = paths.lockFile();
        rejectSymlinkChain(lockPath);
        ReentrantLock jvmLock = lockStripe(lockPath);
        jvmLock.lock();
        try (FileChannel channel = openPrivateLockFile(lockPath);
             FileLock fileLock = channel.lock()) {
            if (!fileLock.isValid()) {
                throw new IOException("Unable to acquire the agent graph file lock");
            }
            rejectSymlinkChain(paths.agentDirectory());
            return action.get();
        } finally {
            jvmLock.unlock();
        }
    }

    private void publishAtomically(
            Path target,
            byte[] bytes,
            AgentGraphRevision candidateRevision) throws IOException {
        target = requireInsideRoot(target);
        Path parent = target.getParent();
        requireSafeDirectory(parent);
        rejectSymlinkChain(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            requireSafeRegularFile(target);
        }

        Path staging = requireInsideRoot(parent.resolve(
                "." + target.getFileName() + "." + UUID.randomUUID() + ".staging"));
        boolean published = false;
        try {
            try (FileChannel output = createPrivateFileChannel(staging)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                output.force(true);
            }

            // Fsyncing before the move durably records the staging file. Unsupported directory
            // fsync fails closed before publication.
            directoryFsync.force(parent);
            rejectSymlinkChain(target);
            try {
                Files.move(staging, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Atomic replacement is required for agent storage: " + target,
                        unsupported);
            }
            published = true;
            try {
                requireSafeRegularFile(target);
                directoryFsync.force(parent);
            } catch (IOException postMoveFailure) {
                if (candidateRevision != null) {
                    throw new IndeterminateAgentGraphCommitException(
                            candidateRevision, postMoveFailure);
                }
                throw new IOException(
                        "Agent manifest may have been published but post-move durability verification "
                                + "failed; reread before retrying",
                        postMoveFailure);
            }
        } finally {
            if (!published) {
                Files.deleteIfExists(staging);
            }
        }
    }

    private byte[] readNoFollow(Path file, int maximumBytes, String description) throws IOException {
        file = requireInsideRoot(file);
        rejectSymlinkChain(file);
        requireSafeRegularFile(file);
        try (FileChannel input = FileChannel.open(file,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            long size = input.size();
            if (size > maximumBytes) {
                throw new IOException(description + " exceeds the configured maximum of "
                        + maximumBytes + " bytes: " + file);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) size);
            ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                buffer.flip();
                if (read > maximumBytes - out.size()) {
                    throw new IOException(description + " grew beyond the configured maximum of "
                            + maximumBytes + " bytes while being read: " + file);
                }
                out.write(buffer.array(), 0, read);
                buffer.clear();
            }
            return out.toByteArray();
        }
    }

    private void createDirectoriesSecure(Path directory) throws IOException {
        directory = requireInsideRoot(directory);
        Path cursor = root;
        for (Path segment : root.relativize(directory)) {
            cursor = cursor.resolve(segment);
            boolean durabilitySyncRequired = false;
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                durabilitySyncRequired = true;
                try {
                    createPrivateDirectory(cursor);
                } catch (FileAlreadyExistsException concurrentCreate) {
                    // Re-check below; an attacker-created symlink or file is rejected.
                }
            }
            requireSafeDirectory(cursor);
            if (durabilitySyncRequired) {
                // Also sync after a concurrent create: this operation must not return relying on
                // the other creator to make the directory entry durable.
                forceDirectory(cursor);
                forceDirectory(cursor.getParent());
            }
        }
    }

    private void requireSafeDirectory(Path directory) throws IOException {
        directory = requireInsideRoot(directory);
        rejectSymlinkChain(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Agent storage path is not a safe directory: " + directory);
        }
        enforcePrivatePermissions(directory, PRIVATE_DIRECTORY_PERMISSIONS);
    }

    private void requireSafeRegularFile(Path file) throws IOException {
        file = requireInsideRoot(file);
        rejectSymlinkChain(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Required agent storage file is missing or unsafe: " + file);
        }
        enforcePrivatePermissions(file, PRIVATE_FILE_PERMISSIONS);
    }

    private void rejectSymlinkChain(Path candidate) throws IOException {
        candidate = requireInsideRoot(candidate);
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Agent storage root was replaced or became unsafe: " + root);
        }
        enforcePrivatePermissions(root, PRIVATE_DIRECTORY_PERMISSIONS);
        Path cursor = root;
        for (Path segment : root.relativize(candidate)) {
            cursor = cursor.resolve(segment);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cursor)) {
                throw new IOException("Symbolic links are forbidden in agent storage: " + cursor);
            }
        }
    }

    private AgentPaths paths(AgentPrincipal principal) throws IOException {
        Objects.requireNonNull(principal, "principal");
        Path owner = ownerDirectory(principal.ownerId());
        Path agent = requireInsideRoot(owner.resolve(principal.agentId().toString()));
        Path graph = requireInsideRoot(agent.resolve(GRAPH_DIRECTORY_NAME));
        return new AgentPaths(
                principal,
                agent,
                requireInsideRoot(agent.resolve(MANIFEST_FILE_NAME)),
                graph,
                requireInsideRoot(graph.resolve(PRIVATE_GRAPH_FILE_NAME)),
                requireInsideRoot(agent.resolve(LOCK_FILE_NAME)));
    }

    private Path ownerDirectory(UUID ownerId) throws IOException {
        return requireInsideRoot(root.resolve(ownerId.toString()));
    }

    private Path requireInsideRoot(Path candidate) throws IOException {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IOException("Agent storage path escapes the configured root");
        }
        return normalized;
    }

    private static Path prepareRoot(Path configuredRoot) throws IOException {
        Objects.requireNonNull(configuredRoot, "configuredRoot");
        Path requested = configuredRoot.toAbsolutePath().normalize();
        List<Path> missing = new ArrayList<>();
        Path existing = requested;
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            missing.add(existing);
            existing = existing.getParent();
            if (existing == null) {
                throw new IOException("Agent storage root has no existing filesystem ancestor");
            }
        }
        if (Files.isSymbolicLink(existing)
                || !Files.isDirectory(existing, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Agent storage root ancestor is not a safe directory: " + existing);
        }

        Path cursor = existing.toRealPath();
        Collections.reverse(missing);
        for (Path missingPath : missing) {
            cursor = cursor.resolve(missingPath.getFileName().toString());
            try {
                createPrivateDirectory(cursor);
            } catch (FileAlreadyExistsException concurrentCreate) {
                // Revalidate and fsync below instead of relying on the competing creator.
            }
            requirePrivateDirectory(cursor);
            forceDirectory(cursor);
            forceDirectory(cursor.getParent());
        }
        requirePrivateDirectory(cursor);
        return cursor.toRealPath();
    }

    private static UUID parseCanonicalUuid(String value) throws IOException {
        return parseCanonicalUuid(value, "agent owner directory entry");
    }

    private static UUID parseCanonicalUuid(String value, String context) throws IOException {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equals(value)) {
                throw new IOException("Non-canonical UUID in " + context + ": " + value);
            }
            return uuid;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid UUID in " + context + ": " + value, invalid);
        }
    }

    private static void validateDisplayName(String displayName) {
        Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (displayName.length() > AgentInstance.MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "displayName exceeds " + AgentInstance.MAX_DISPLAY_NAME_LENGTH + " characters");
        }
    }

    private AgentGraphRevision hashNoFollow(Path file) throws IOException {
        file = requireInsideRoot(file);
        rejectSymlinkChain(file);
        requireSafeRegularFile(file);
        MessageDigest digest = newSha256();
        try (FileChannel input = FileChannel.open(
                file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = input.size();
            requireArchiveSize(size, "current agent graph archive");
            ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                total += read;
                requireArchiveSize(total, "current agent graph archive");
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }
        return new AgentGraphRevision(HexFormat.of().formatHex(digest.digest()));
    }

    private FileChannel openPrivateLockFile(Path lockPath) throws IOException {
        try {
            FileChannel created = createPrivateFileChannel(lockPath);
            try {
                created.force(true);
                forceDirectory(lockPath.getParent());
                return created;
            } catch (IOException failure) {
                created.close();
                throw failure;
            }
        } catch (FileAlreadyExistsException existing) {
            requireSafeRegularFile(lockPath);
            return FileChannel.open(
                    lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        }
    }

    private static FileChannel createPrivateFileChannel(Path file) throws IOException {
        FileAttribute<?>[] attributes = privateFileAttributes(file.getParent());
        FileChannel channel = FileChannel.open(
                file,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                attributes);
        try {
            enforcePrivatePermissions(file, PRIVATE_FILE_PERMISSIONS);
            return channel;
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    private static void createPrivateDirectory(Path directory) throws IOException {
        FileAttribute<?>[] attributes = privateDirectoryAttributes(directory.getParent());
        Files.createDirectory(directory, attributes);
        requirePrivateDirectory(directory);
    }

    private static void requirePrivateDirectory(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Agent storage path is not a safe directory: " + directory);
        }
        enforcePrivatePermissions(directory, PRIVATE_DIRECTORY_PERMISSIONS);
    }

    private static FileAttribute<?>[] privateDirectoryAttributes(Path parent) throws IOException {
        if (!supportsPosix(parent)) {
            return new FileAttribute<?>[0];
        }
        return new FileAttribute<?>[] {
                PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS)
        };
    }

    private static FileAttribute<?>[] privateFileAttributes(Path parent) throws IOException {
        if (!supportsPosix(parent)) {
            return new FileAttribute<?>[0];
        }
        return new FileAttribute<?>[] {
                PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS)
        };
    }

    private static boolean supportsPosix(Path existingPath) throws IOException {
        return Files.getFileStore(existingPath).supportsFileAttributeView(PosixFileAttributeView.class);
    }

    private static void enforcePrivatePermissions(
            Path path,
            Set<PosixFilePermission> required) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(
                path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return;
        }
        view.setPermissions(required);
        Set<PosixFilePermission> actual = view.readAttributes().permissions();
        if (!actual.equals(required)) {
            throw new IOException("Unable to enforce private POSIX permissions on " + path
                    + ": expected " + PosixFilePermissions.toString(required)
                    + " but found " + PosixFilePermissions.toString(actual));
        }
    }

    static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(
                directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("The Java runtime does not provide SHA-256", impossible);
        }
    }

    private static ReentrantLock[] createLockStripes() {
        ReentrantLock[] locks = new ReentrantLock[JVM_LOCK_STRIPE_COUNT];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private static ReentrantLock lockStripe(Path lockPath) {
        return JVM_LOCKS[Math.floorMod(lockPath.hashCode(), JVM_LOCKS.length)];
    }

    @FunctionalInterface
    interface DirectoryFsync {
        void force(Path directory) throws IOException;
    }

    private static final class BoundedArchiveOutputStream extends OutputStream {

        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final int maximumBytes;
        private int written;

        private BoundedArchiveOutputStream(int maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            delegate.write(value);
            written++;
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, values.length);
            requireCapacity(length);
            delegate.write(values, offset, length);
            written += length;
        }

        private void requireCapacity(int additionalBytes) throws IOException {
            if (additionalBytes > maximumBytes - written) {
                throw new IOException("Serialized agent graph archive exceeds the configured maximum of "
                        + maximumBytes + " bytes");
            }
        }

        private byte[] toByteArray() {
            return delegate.toByteArray();
        }
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    private record AgentPaths(
            AgentPrincipal principal,
            Path agentDirectory,
            Path manifest,
            Path graphDirectory,
            Path privateGraph,
            Path lockFile) {
    }
}
