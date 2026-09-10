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
    public static final String DEFAULT_CREDENTIAL_NAME = "default";

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper();
    private static final int CURRENT_FORMAT_VERSION = 2;
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
        return withLock(store -> {
            ProviderCredentials provider = store.providers.get(normalized);
            return provider == null ? null : provider.activeCredential();
        });
    }

    public ManagedCredential read(String providerId, String credentialName) throws IOException {
        String normalizedProvider = normalizeProviderId(providerId);
        String normalizedName = normalizeCredentialName(credentialName);
        return withLock(store -> {
            ProviderCredentials provider = store.providers.get(normalizedProvider);
            return provider == null ? null : provider.credentials.get(normalizedName);
        });
    }

    public String activeCredentialName(String providerId) throws IOException {
        String normalized = normalizeProviderId(providerId);
        return withLock(store -> {
            ProviderCredentials provider = store.providers.get(normalized);
            return provider == null ? null : provider.activeName;
        });
    }

    public List<CredentialInfo> list() throws IOException {
        return withLock(store -> listCredentials(store, null));
    }

    public List<CredentialInfo> list(String providerId) throws IOException {
        String normalized = normalizeProviderId(providerId);
        return withLock(store -> listCredentials(store, normalized));
    }

    private static List<CredentialInfo> listCredentials(StoreState store, String providerFilter) {
        List<CredentialInfo> result = new ArrayList<>();
        store.providers.forEach((providerId, provider) -> {
            if (providerFilter != null && !providerFilter.equals(providerId)) {
                return;
            }
            provider.credentials.forEach((credentialName, credential) ->
                    result.add(new CredentialInfo(
                            providerId,
                            credentialName,
                            credential.getType(),
                            credentialName.equals(provider.activeName),
                            OAuthCredentialIdentity.label(credential),
                            credential.getExpires(), credential.hasRefreshToken())));
        });
        return List.copyOf(result);
    }

    public ManagedCredential putApiKey(String providerId, String key) throws IOException {
        return put(providerId, ManagedCredential.apiKey(key));
    }

    public ManagedCredential putApiKey(
            String providerId,
            String credentialName,
            String key,
            boolean activate) throws IOException {
        return put(providerId, credentialName, ManagedCredential.apiKey(key), activate);
    }

    public ManagedCredential putOAuth(String providerId, String access, String refresh, long expires) throws IOException {
        return put(providerId, ManagedCredential.oauth(access, refresh, expires));
    }

    public ManagedCredential putOAuth(
            String providerId,
            String credentialName,
            String access,
            String refresh,
            long expires,
            boolean activate) throws IOException {
        return put(providerId, credentialName, ManagedCredential.oauth(access, refresh, expires), activate);
    }

    public ManagedCredential put(String providerId, ManagedCredential credential) throws IOException {
        String normalized = normalizeProviderId(providerId);
        if (credential == null) {
            throw new IllegalArgumentException("credential must not be null");
        }
        if (credential.isOAuth()) return putOAuthIdentity(normalized, null, credential, true);
        return withMutation(store -> {
            ProviderCredentials provider = store.providers.computeIfAbsent(
                    normalized, ignored -> new ProviderCredentials());
            String targetName = provider.activeName == null
                    ? DEFAULT_CREDENTIAL_NAME
                    : provider.activeName;
            provider.credentials.put(targetName, credential);
            provider.activeName = targetName;
            return credential;
        });
    }

    public ManagedCredential put(
            String providerId,
            String credentialName,
            ManagedCredential credential,
            boolean activate) throws IOException {
        String normalizedProvider = normalizeProviderId(providerId);
        String normalizedName = normalizeCredentialName(credentialName);
        if (credential == null) {
            throw new IllegalArgumentException("credential must not be null");
        }
        if (credential.isOAuth()) {
            return putOAuthIdentity(normalizedProvider, normalizedName, credential, activate);
        }
        return withMutation(store -> {
            ProviderCredentials provider = store.providers.computeIfAbsent(
                    normalizedProvider, ignored -> new ProviderCredentials());
            provider.credentials.put(normalizedName, credential);
            if (provider.activeName == null || activate) {
                provider.activeName = normalizedName;
            }
            return credential;
        });
    }

    /** Store logins by identity, retaining the existing name on repeated sign-in. */
    private ManagedCredential putOAuthIdentity(
            String providerId, String requestedName, ManagedCredential credential, boolean activate)
            throws IOException {
        ManagedCredential normalized = OAuthCredentialIdentity.normalize(providerId, credential);
        return withMutation(store -> {
            ProviderCredentials provider = store.providers.computeIfAbsent(
                    providerId, ignored -> new ProviderCredentials());
            String targetName = matchingName(providerId, provider, normalized);
            if (targetName == null) {
                targetName = requestedName;
                if (targetName == null) {
                    targetName = DEFAULT_CREDENTIAL_NAME;
                    int suffix = 2;
                    while (provider.credentials.containsKey(targetName)) targetName = "account-" + suffix++;
                }
            }
            removeAliases(providerId, provider, targetName, normalized);
            provider.credentials.put(targetName, normalized);
            if (activate || provider.activeName == null) provider.activeName = targetName;
            return normalized;
        });
    }

    /** Actual stored name, including an existing identity reused by a named login. */
    public String credentialName(String providerId, ManagedCredential credential) throws IOException {
        String normalized = normalizeProviderId(providerId);
        return withLock(store -> {
            ProviderCredentials provider = store.providers.get(normalized);
            return provider == null ? null : matchingName(
                    normalized, provider, OAuthCredentialIdentity.normalize(normalized, credential));
        });
    }

    private static String matchingName(String providerId, ProviderCredentials provider, ManagedCredential credential) {
        if (OAuthCredentialIdentity.sameAccount(providerId, provider.activeCredential(), credential)) return provider.activeName;
        return provider.credentials.entrySet().stream()
                .filter(entry -> OAuthCredentialIdentity.sameAccount(providerId, entry.getValue(), credential))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private static void removeAliases(String providerId, ProviderCredentials provider,
                                      String retainedName, ManagedCredential credential) {
        provider.credentials.entrySet().removeIf(entry -> {
            if (entry.getKey().equals(retainedName)
                    || !OAuthCredentialIdentity.sameAccount(providerId, entry.getValue(), credential)) return false;
            if (entry.getKey().equals(provider.activeName)) provider.activeName = retainedName;
            return true;
        });
    }

    /**
     * Serialized read-modify-write for provider auth operations.
     *
     * <p>Returning {@code null} leaves the existing value unchanged. Deletion is
     * deliberately explicit through {@link #delete(String)}.</p>
     */
    public ManagedCredential modify(String providerId, CredentialUpdater updater) throws IOException {
        return modify(providerId, null, updater);
    }

    /** Reject a changed selection under the lock instead of refreshing a different account. */
    public ManagedCredential modify(String providerId, String expectedName, CredentialUpdater updater) throws IOException {
        return modifySelected(providerId, expectedName, false, updater);
    }

    public ManagedCredential modifyNamed(String providerId, String name, CredentialUpdater updater) throws IOException {
        return modifySelected(providerId, normalizeCredentialName(name), true, updater);
    }

    private ManagedCredential modifySelected(String providerId, String expectedName, boolean named,
                                             CredentialUpdater updater) throws IOException {
        String normalized = normalizeProviderId(providerId);
        if (updater == null) {
            throw new IllegalArgumentException("updater must not be null");
        }
        return withMutation(store -> {
            ProviderCredentials provider = store.providers.get(normalized);
            if (!named && expectedName != null && (provider == null || !expectedName.equals(provider.activeName))) return null;
            String targetName = named ? expectedName : provider == null ? DEFAULT_CREDENTIAL_NAME : provider.activeName;
            ManagedCredential current = provider == null ? null : provider.credentials.get(targetName);
            if (named && current == null) throw new IOException("Selected credential no longer exists");
            ManagedCredential next = updater.update(current);
            if (next != null) {
                if (provider == null) {
                    provider = new ProviderCredentials();
                    provider.activeName = DEFAULT_CREDENTIAL_NAME;
                    store.providers.put(normalized, provider);
                }
                ManagedCredential normalizedNext = OAuthCredentialIdentity.normalize(normalized, next);
                if (next != current) {
                    removeAliases(normalized, provider, targetName, current);
                    removeAliases(normalized, provider, targetName, normalizedNext);
                }
                provider.credentials.put(targetName, normalizedNext);
                return normalizedNext;
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
        SelectedCredential selected = resolveOAuthSelection(providerId, minimumValidityMillis, refresher);
        return selected == null ? null : selected.credential();
    }

    public record SelectedCredential(String name, ManagedCredential credential) { }

    /** Captures the credential and its name in the same critical section. */
    public SelectedCredential resolveOAuthSelection(
            String providerId, long minimumValidityMillis, OAuthRefresher refresher) throws IOException {
        return resolveOAuthSelection(providerId, null, minimumValidityMillis, refresher);
    }

    public SelectedCredential resolveOAuthSelection(String providerId, String name,
            long minimumValidityMillis, OAuthRefresher refresher) throws IOException {
        String normalized = normalizeProviderId(providerId);
        String selectedName = name == null ? null : normalizeCredentialName(name);
        if (refresher == null) {
            throw new IllegalArgumentException("refresher must not be null");
        }
        return withMutation(store -> {
            ProviderCredentials provider = store.providers.get(normalized);
            String targetName = selectedName != null ? selectedName : provider == null ? null : provider.activeName;
            ManagedCredential current = provider == null ? null : provider.credentials.get(targetName);
            if (selectedName != null && current == null) throw new IOException("Selected credential no longer exists");
            if (current == null || !current.isOAuth()) {
                return current == null ? null : new SelectedCredential(targetName, current);
            }
            ManagedCredential resolved = OAuthCredentialLifecycle.resolve(
                    current,
                    minimumValidityMillis,
                    System.currentTimeMillis(),
                    value -> OAuthCredentialIdentity.normalize(normalized, refresher.refresh(value)));
            if (resolved != current) {
                removeAliases(normalized, provider, targetName, current);
                removeAliases(normalized, provider, targetName, resolved);
                provider.credentials.put(targetName, resolved);
            }
            return new SelectedCredential(targetName, resolved);
        });
    }

    public boolean delete(String providerId) throws IOException {
        String normalized = normalizeProviderId(providerId);
        return withMutation(store -> store.providers.remove(normalized) != null);
    }

    public boolean deleteCredential(String providerId, String credentialName) throws IOException {
        String normalizedProvider = normalizeProviderId(providerId);
        String normalizedName = normalizeCredentialName(credentialName);
        return withMutation(store -> {
            ProviderCredentials provider = store.providers.get(normalizedProvider);
            if (provider == null || provider.credentials.remove(normalizedName) == null) {
                return false;
            }
            if (provider.credentials.isEmpty()) {
                store.providers.remove(normalizedProvider);
            } else if (normalizedName.equals(provider.activeName)) {
                provider.activeName = provider.credentials.keySet().iterator().next();
            }
            return true;
        });
    }

    public boolean switchCredential(String providerId, String credentialName) throws IOException {
        String normalizedProvider = normalizeProviderId(providerId);
        String normalizedName = normalizeCredentialName(credentialName);
        return withMutation(store -> {
            ProviderCredentials provider = store.providers.get(normalizedProvider);
            if (provider == null || !provider.credentials.containsKey(normalizedName)) {
                return false;
            }
            provider.activeName = normalizedName;
            return true;
        });
    }

    public int deleteAll() throws IOException {
        return withMutation(store -> {
            int removed = store.providers.values().stream()
                    .mapToInt(provider -> provider.credentials.size())
                    .sum();
            store.providers.clear();
            return removed;
        });
    }

    /**
     * Resolve a stored API-key value. A full-value {@code $NAME} or
     * {@code ${NAME}} reference is read from the supplied environment lookup;
     * shell-command execution is intentionally not supported.
     */
    public String resolveApiKey(String providerId, Function<String, String> environment) throws IOException {
        return resolveApiKey(providerId, null, environment);
    }

    public String resolveApiKey(String providerId, String name, Function<String, String> environment) throws IOException {
        ManagedCredential credential = name == null ? read(providerId) : read(providerId, name);
        if (name != null && credential == null) throw new IOException("Selected credential no longer exists");
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
            StoreState store = readUnlocked();
            T result = operation.apply(store);
            if (writeBack || store.normalized) {
                writeUnlocked(store);
            }
            return result;
        } finally {
            jvmLock.unlock();
        }
    }

    private StoreState readUnlocked() throws IOException {
        StoreState store = new StoreState();
        if (!Files.exists(authPath)) {
            return store;
        }

        JsonNode root = MAPPER.readTree(authPath.toFile());
        if (root == null || !root.isObject()) {
            throw new IOException("Invalid auth.json: expected an object");
        }

        if (root.has("version") || root.has("providers")) {
            readVersioned(root, store);
        } else {
            readLegacy(root, store);
        }
        // Repair exact-token aliases only. Expiry is not issuance order: two grants
        // for the same identity are reconciled only after a successful login/refresh.
        store.providers.forEach((providerId, provider) -> {
            ProviderCredentials unique = new ProviderCredentials();
            for (var entry : provider.credentials.entrySet()) {
                ManagedCredential value = OAuthCredentialIdentity.normalize(providerId, entry.getValue());
                store.normalized |= !value.equals(entry.getValue());
                String match = matchingName(providerId, unique, value);
                if (match != null) {
                    ManagedCredential old = unique.credentials.get(match);
                    if (!old.getAccess().equals(value.getAccess()) || !old.getRefresh().equals(value.getRefresh())) {
                        match = null;
                    }
                }
                if (match == null) {
                    unique.credentials.put(entry.getKey(), value);
                } else {
                    String keep = entry.getKey().equals(provider.activeName) ? entry.getKey() : match;
                    unique.credentials.remove(match);
                    unique.credentials.put(keep, value);
                    store.normalized = true;
                }
            }
            provider.credentials.clear();
            provider.credentials.putAll(unique.credentials);
        });
        return store;
    }

    private void readVersioned(JsonNode root, StoreState store) throws IOException {
        JsonNode versionNode = root.get("version");
        if (versionNode == null || !versionNode.canConvertToInt()
                || versionNode.intValue() != CURRENT_FORMAT_VERSION) {
            throw new IOException("Unsupported auth.json version");
        }
        JsonNode providersNode = root.get("providers");
        if (providersNode == null || !providersNode.isObject()) {
            throw new IOException("Invalid auth.json: expected a providers object");
        }

        var providers = providersNode.fields();
        while (providers.hasNext()) {
            Map.Entry<String, JsonNode> providerEntry = providers.next();
            String providerId = normalizeProviderId(providerEntry.getKey());
            JsonNode providerNode = providerEntry.getValue();
            if (providerNode == null || !providerNode.isObject()) {
                throw new IOException("Invalid credentials for provider " + providerId + ": expected an object");
            }
            String activeName = normalizeCredentialName(requiredText(providerNode, "active", providerId));
            JsonNode credentialsNode = providerNode.get("credentials");
            if (credentialsNode == null || !credentialsNode.isObject() || credentialsNode.isEmpty()) {
                throw new IOException("Invalid credentials for provider " + providerId
                        + ": expected a non-empty credentials object");
            }

            ProviderCredentials provider = new ProviderCredentials();
            var credentials = credentialsNode.fields();
            while (credentials.hasNext()) {
                Map.Entry<String, JsonNode> credentialEntry = credentials.next();
                String credentialName = normalizeCredentialName(credentialEntry.getKey());
                provider.credentials.put(
                        credentialName,
                        readCredential(credentialEntry.getValue(), providerId + "/" + credentialName));
            }
            if (!provider.credentials.containsKey(activeName)) {
                throw new IOException("Active credential '" + activeName
                        + "' does not exist for provider " + providerId);
            }
            provider.activeName = activeName;
            store.providers.put(providerId, provider);
        }
    }

    private void readLegacy(JsonNode root, StoreState store) throws IOException {
        var fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String providerId = normalizeProviderId(entry.getKey());
            ProviderCredentials provider = new ProviderCredentials();
            provider.activeName = DEFAULT_CREDENTIAL_NAME;
            provider.credentials.put(
                    DEFAULT_CREDENTIAL_NAME,
                    readCredential(entry.getValue(), providerId));
            store.providers.put(providerId, provider);
        }
    }

    private ManagedCredential readCredential(JsonNode node, String context) throws IOException {
        if (node == null || !node.isObject()) {
            throw new IOException("Invalid credential for provider " + context + ": expected an object");
        }
        String type = requiredText(node, "type", context);
        try {
            if (ManagedCredential.API_KEY.equals(type)) {
                return ManagedCredential.apiKey(requiredText(node, "key", context));
            }
            if (ManagedCredential.OAUTH.equals(type)) {
                JsonNode expiresNode = node.get("expires");
                if (expiresNode == null || !expiresNode.canConvertToLong()) {
                    throw new IOException("Invalid OAuth expiry for provider " + context);
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
                return ManagedCredential.oauth(
                        requiredText(node, "access", context),
                        requiredString(node, "refresh", context),
                        expiresNode.longValue(),
                        metadata);
            }
            throw new IOException("Unsupported credential type for provider " + context + ": " + type);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid credential for provider " + context + ": " + e.getMessage(), e);
        }
    }

    private void writeUnlocked(StoreState store) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", CURRENT_FORMAT_VERSION);
        ObjectNode providersNode = root.putObject("providers");
        store.providers.forEach((providerId, provider) -> {
            ObjectNode providerNode = providersNode.putObject(providerId);
            providerNode.put("active", provider.activeName);
            ObjectNode credentialsNode = providerNode.putObject("credentials");
            provider.credentials.forEach((credentialName, credential) ->
                    writeCredential(credentialsNode.putObject(credentialName), credential));
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

    private static void writeCredential(ObjectNode node, ManagedCredential credential) {
        node.put("type", credential.getType());
        if (credential.isApiKey()) {
            node.put("key", credential.getKey());
            return;
        }
        node.put("access", credential.getAccess());
        node.put("refresh", credential.getRefresh());
        node.put("expires", credential.getExpires());
        credential.getMetadata().forEach((key, value) -> {
            if (!OAUTH_RESERVED_FIELDS.contains(key)) {
                node.put(key, value);
            }
        });
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

    private static String normalizeCredentialName(String credentialName) {
        if (credentialName == null || credentialName.isBlank()) {
            throw new IllegalArgumentException("credentialName must not be blank");
        }
        String normalized = credentialName.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isISOControl(normalized.charAt(i))) {
                throw new IllegalArgumentException(
                        "credentialName must not contain control characters");
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

    public record CredentialInfo(
            String providerId,
            String credentialName,
            String type,
            boolean active,
            String identity,
            long expiresAt,
            boolean refreshable) {
        public CredentialInfo(String providerId, String credentialName, String type, boolean active) {
            this(providerId, credentialName, type, active, null, 0L, false);
        }

        public CredentialInfo(String providerId, String type) {
            this(providerId, DEFAULT_CREDENTIAL_NAME, type, true);
        }

        public String status() {
            if (!ManagedCredential.OAUTH.equals(type)) return active ? "active" : "";
            String expiry = expiresAt == Long.MAX_VALUE ? "non-expiring"
                    : expiresAt <= System.currentTimeMillis()
                    ? (refreshable ? "expired; refresh needed" : "expired; sign in again")
                    : "expires " + java.time.Instant.ofEpochMilli(expiresAt);
            return (active ? "active; " : "") + expiry;
        }

        public String displayLabel() {
            return credentialName + " — " + type + (identity == null ? "" : " — " + identity)
                    + (status().isBlank() ? "" : " (" + status() + ")");
        }
    }

    private static final class StoreState {
        private final LinkedHashMap<String, ProviderCredentials> providers = new LinkedHashMap<>();
        private boolean normalized;
    }

    private static final class ProviderCredentials {
        private String activeName;
        private final LinkedHashMap<String, ManagedCredential> credentials = new LinkedHashMap<>();

        private ManagedCredential activeCredential() {
            return activeName == null ? null : credentials.get(activeName);
        }
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
        T apply(StoreState store) throws IOException;
    }
}
