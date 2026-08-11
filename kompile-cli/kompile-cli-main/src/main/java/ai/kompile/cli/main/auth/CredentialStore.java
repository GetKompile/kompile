/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.auth.ManagedCredential;
import ai.kompile.cli.common.auth.OAuthCredentialLifecycle;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Cross-process credential storage backed by {@code ~/.kompile/auth.json}.
 *
 * <p>All writes are serialized with a stable sidecar lock, use an atomic
 * replacement, and keep the directory/file private to the current user where
 * the platform supports POSIX permissions. The store never logs or exposes
 * secret values through list/status APIs.</p>
 */
public final class CredentialStore {
    public static final String AUTH_FILE = "auth.json";

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final Set<String> OAUTH_RESERVED_FIELDS = Set.of(
            "type", "access", "refresh", "expires");
    private static final Map<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path authPath;
    private final Path lockPath;

    public CredentialStore(Path authPath) {
        if (authPath == null) {
            throw new IllegalArgumentException("authPath must not be null");
        }
        this.authPath = authPath.toAbsolutePath().normalize();
        this.lockPath = this.authPath.resolveSibling(this.authPath.getFileName() + ".lock");
    }

    public static CredentialStore create() {
        return new CredentialStore(KompileHome.homeDirectory().toPath().resolve(AUTH_FILE));
    }

    public Path getAuthPath() {
        return authPath;
    }

    public ManagedCredential read(String providerId) throws IOException {
        String normalized = normalizeProviderId(providerId);
        return withLock(credentials -> credentials.get(normalized));
    }

    public List<CredentialInfo> list() throws IOException {
        return withLock(credentials -> {
            List<CredentialInfo> result = new ArrayList<>(credentials.size());
            credentials.forEach((providerId, credential) ->
                    result.add(new CredentialInfo(providerId, credential.getType())));
            return List.copyOf(result);
        });
    }

    public ManagedCredential putApiKey(String providerId, String key) throws IOException {
        return put(providerId, ManagedCredential.apiKey(key));
    }

    public ManagedCredential putOAuth(String providerId, String access, String refresh, long expires) throws IOException {
        return put(providerId, ManagedCredential.oauth(access, refresh, expires));
    }

    public ManagedCredential put(String providerId, ManagedCredential credential) throws IOException {
        String normalized = normalizeProviderId(providerId);
        if (credential == null) {
            throw new IllegalArgumentException("credential must not be null");
        }
        return withMutation(credentials -> {
            credentials.put(normalized, credential);
            return credential;
        });
    }

    /**
     * Serialized read-modify-write for provider auth operations.
     *
     * <p>Returning {@code null} leaves the existing value unchanged. Deletion is
     * deliberately explicit through {@link #delete(String)}.</p>
     */
    public ManagedCredential modify(String providerId, CredentialUpdater updater) throws IOException {
        String normalized = normalizeProviderId(providerId);
        if (updater == null) {
            throw new IllegalArgumentException("updater must not be null");
        }
        return withMutation(credentials -> {
            ManagedCredential current = credentials.get(normalized);
            ManagedCredential next = updater.update(current);
            if (next != null) {
                credentials.put(normalized, next);
                return next;
            }
            return current;
        });
    }

    /**
     * Resolve an OAuth credential, refreshing once under the provider lock when
     * it is inside the requested validity window.
     */
    public ManagedCredential resolveOAuth(
            String providerId,
            long minimumValidityMillis,
            OAuthRefresher refresher) throws IOException {
        String normalized = normalizeProviderId(providerId);
        if (refresher == null) {
            throw new IllegalArgumentException("refresher must not be null");
        }
        return withMutation(credentials -> {
            ManagedCredential current = credentials.get(normalized);
            if (current == null || !current.isOAuth()) {
                return current;
            }
            ManagedCredential resolved = OAuthCredentialLifecycle.resolve(
                    current,
                    minimumValidityMillis,
                    System.currentTimeMillis(),
                    refresher::refresh);
            if (resolved != current) {
                credentials.put(normalized, resolved);
            }
            return resolved;
        });
    }

    public boolean delete(String providerId) throws IOException {
        String normalized = normalizeProviderId(providerId);
        return withMutation(credentials -> credentials.remove(normalized) != null);
    }

    /**
     * Resolve a stored API-key value. A full-value {@code $NAME} or
     * {@code ${NAME}} reference is read from the supplied environment lookup;
     * shell-command execution is intentionally not supported.
     */
    public String resolveApiKey(String providerId, Function<String, String> environment) throws IOException {
        ManagedCredential credential = read(providerId);
        if (credential == null || !credential.isApiKey()) {
            return null;
        }
        String value = credential.getKey();
        String envName = referencedEnvironmentName(value);
        if (envName == null) {
            return value;
        }
        String resolved = environment != null ? environment.apply(envName) : null;
        return resolved == null || resolved.isBlank() ? null : resolved;
    }

    public String resolveApiKey(String providerId) throws IOException {
        return resolveApiKey(providerId, System::getenv);
    }

    private <T> T withLock(StoreOperation<T> operation) throws IOException {
        return withStoreLock(operation, false);
    }

    private <T> T withMutation(StoreOperation<T> operation) throws IOException {
        return withStoreLock(operation, true);
    }

    private <T> T withStoreLock(StoreOperation<T> operation, boolean writeBack) throws IOException {
        ensurePrivateParentDirectory();
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        try {
            jvmLock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for credential-store lock " + lockPath, e);
        }
        try (FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            applyOwnerOnlyPermissions(lockPath, false);
            LinkedHashMap<String, ManagedCredential> credentials = readUnlocked();
            T result = operation.apply(credentials);
            if (writeBack) {
                writeUnlocked(credentials);
            }
            return result;
        } finally {
            jvmLock.unlock();
        }
    }

    private LinkedHashMap<String, ManagedCredential> readUnlocked() throws IOException {
        LinkedHashMap<String, ManagedCredential> credentials = new LinkedHashMap<>();
        if (!Files.exists(authPath)) {
            return credentials;
        }

        JsonNode root = MAPPER.readTree(authPath.toFile());
        if (root == null || !root.isObject()) {
            throw new IOException("Invalid auth.json: expected an object");
        }

        var fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String providerId = normalizeProviderId(entry.getKey());
            JsonNode node = entry.getValue();
            if (node == null || !node.isObject()) {
                throw new IOException("Invalid credential for provider " + providerId + ": expected an object");
            }
            String type = requiredText(node, "type", providerId);
            try {
                if (ManagedCredential.API_KEY.equals(type)) {
                    credentials.put(providerId, ManagedCredential.apiKey(requiredText(node, "key", providerId)));
                } else if (ManagedCredential.OAUTH.equals(type)) {
                    JsonNode expiresNode = node.get("expires");
                    if (expiresNode == null || !expiresNode.canConvertToLong()) {
                        throw new IOException("Invalid OAuth expiry for provider " + providerId);
                    }
                    Map<String, String> metadata = new LinkedHashMap<>();
                    var metadataFields = node.fields();
                    while (metadataFields.hasNext()) {
                        Map.Entry<String, JsonNode> metadataEntry = metadataFields.next();
                        if (!OAUTH_RESERVED_FIELDS.contains(metadataEntry.getKey())
                                && metadataEntry.getValue() != null
                                && metadataEntry.getValue().isValueNode()
                                && !metadataEntry.getValue().isNull()) {
                            metadata.put(metadataEntry.getKey(), metadataEntry.getValue().asText());
                        }
                    }
                    credentials.put(providerId, ManagedCredential.oauth(
                            requiredText(node, "access", providerId),
                            requiredString(node, "refresh", providerId),
                            expiresNode.longValue(),
                            metadata));
                } else {
                    throw new IOException("Unsupported credential type for provider " + providerId + ": " + type);
                }
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid credential for provider " + providerId + ": " + e.getMessage(), e);
            }
        }
        return credentials;
    }

    private void writeUnlocked(LinkedHashMap<String, ManagedCredential> credentials) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        credentials.forEach((providerId, credential) -> {
            ObjectNode node = root.putObject(providerId);
            node.put("type", credential.getType());
            if (credential.isApiKey()) {
                node.put("key", credential.getKey());
            } else {
                node.put("access", credential.getAccess());
                node.put("refresh", credential.getRefresh());
                node.put("expires", credential.getExpires());
                credential.getMetadata().forEach((key, value) -> {
                    if (!OAUTH_RESERVED_FIELDS.contains(key)) {
                        node.put(key, value);
                    }
                });
            }
        });

        byte[] serialized = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        Path tempPath = authPath.resolveSibling(authPath.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel output = FileChannel.open(
                    tempPath,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(serialized);
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
                output.force(true);
            }
            applyOwnerOnlyPermissions(tempPath, false);
            try {
                Files.move(tempPath, authPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempPath, authPath, StandardCopyOption.REPLACE_EXISTING);
            }
            applyOwnerOnlyPermissions(authPath, false);
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }

    private void ensurePrivateParentDirectory() throws IOException {
        Path parent = authPath.getParent();
        if (parent == null) {
            throw new IOException("Credential path has no parent directory: " + authPath);
        }
        Files.createDirectories(parent);
        applyOwnerOnlyPermissions(parent, true);
    }

    private static void applyOwnerOnlyPermissions(Path path, boolean directory) throws IOException {
        try {
            Files.setPosixFilePermissions(path,
                    directory ? OWNER_DIRECTORY_PERMISSIONS : OWNER_FILE_PERMISSIONS);
        } catch (UnsupportedOperationException ignored) {
            // Windows and other non-POSIX providers: narrow the java.io.File ACL where possible.
            var file = path.toFile();
            if (!file.setReadable(false, false)
                    || !file.setWritable(false, false)
                    || !file.setReadable(true, true)
                    || !file.setWritable(true, true)) {
                // Some providers do not expose mutable ACLs. The OS remains authoritative.
            }
        }
    }

    private static String requiredText(JsonNode node, String field, String providerId) throws IOException {
        String value = requiredString(node, field, providerId);
        if (value.isBlank()) {
            throw new IOException("Missing or invalid '" + field + "' for provider " + providerId);
        }
        return value;
    }

    private static String requiredString(JsonNode node, String field, String providerId) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IOException("Missing or invalid '" + field + "' for provider " + providerId);
        }
        return value.textValue();
    }

    private static String normalizeProviderId(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        String normalized = providerId.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isISOControl(c)) {
                throw new IllegalArgumentException("providerId must not contain control characters");
            }
        }
        return normalized;
    }

    private static String referencedEnvironmentName(String value) {
        if (value == null) {
            return null;
        }
        String candidate = null;
        if (value.startsWith("${") && value.endsWith("}") && value.length() > 3) {
            candidate = value.substring(2, value.length() - 1);
        } else if (value.startsWith("$") && value.length() > 1) {
            candidate = value.substring(1);
        }
        if (candidate == null || !candidate.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return null;
        }
        return candidate;
    }

    public record CredentialInfo(String providerId, String type) {
    }

    @FunctionalInterface
    public interface CredentialUpdater {
        ManagedCredential update(ManagedCredential current) throws IOException;
    }

    @FunctionalInterface
    public interface OAuthRefresher {
        ManagedCredential refresh(ManagedCredential current) throws IOException;
    }

    @FunctionalInterface
    private interface StoreOperation<T> {
        T apply(LinkedHashMap<String, ManagedCredential> credentials) throws IOException;
    }
}
