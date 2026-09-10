/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, 2.0.
 */
package ai.kompile.cli.main.auth.oauth;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * File-backed OAuth application registration store for the managed CLI
 * ({@code ~/.kompile/config/oauth-clients.json}).
 *
 * <p>Holds the per-provider client id / secret (and optional tenant id) that OAuth
 * app registrations require, so interactive logins never need environment
 * variables. The file is created 0600, written atomically via a temp-file move,
 * and never echoed: list output masks secrets. Flow resolution order is
 * injected → this store → environment (legacy).</p>
 */
public final class OAuthClientSettings {

    public static final String SETTINGS_FILE = "oauth-clients.json";

    private static final ObjectMapper MAPPER = JsonUtils.standardMapper();
    private static final Set<PosixFilePermission> OWNER_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    private final Path settingsPath;

    public OAuthClientSettings(Path settingsPath) {
        if (settingsPath == null) {
            throw new IllegalArgumentException("settingsPath must not be null");
        }
        this.settingsPath = settingsPath.toAbsolutePath().normalize();
    }

    public static OAuthClientSettings create() {
        return new OAuthClientSettings(KompileHome.homeDirectory().toPath()
                .resolve("config").resolve(SETTINGS_FILE));
    }

    public Path getSettingsPath() {
        return settingsPath;
    }

    /** Client registration for one provider; any field may be null. */
    public record ClientCredentials(String clientId, String clientSecret, String tenantId) {
    }

    public ClientCredentials read(String providerId) throws IOException {
        JsonNode provider = readStore().path(normalize(providerId));
        if (!provider.isObject()) {
            return null;
        }
        String clientId = textOrNull(provider, "clientId");
        String clientSecret = textOrNull(provider, "clientSecret");
        String tenantId = textOrNull(provider, "tenantId");
        if (clientId == null && clientSecret == null) {
            return null;
        }
        return new ClientCredentials(clientId, clientSecret, tenantId);
    }

    public void put(String providerId, ClientCredentials credentials) throws IOException {
        String normalized = normalize(providerId);
        if (credentials == null
                || ((credentials.clientId() == null || credentials.clientId().isBlank())
                && (credentials.clientSecret() == null || credentials.clientSecret().isBlank()))) {
            throw new IllegalArgumentException(
                    "At least one of clientId or clientSecret must be non-blank");
        }
        ObjectNode root = readStore();
        ObjectNode providerNode = root.with("/" + normalized);
        putText(providerNode, "clientId", credentials.clientId());
        putText(providerNode, "clientSecret", credentials.clientSecret());
        putText(providerNode, "tenantId", credentials.tenantId());
        writeStore(root);
    }

    public boolean remove(String providerId) throws IOException {
        String normalized = normalize(providerId);
        ObjectNode root = readStore();
        JsonNode provider = root.path(normalized);
        if (!provider.isObject()) {
            return false;
        }
        root.remove(normalized);
        writeStore(root);
        return true;
    }

    /** Providers present in the store, sorted for stable wizard/menu output. */
    public List<String> listProviders() throws IOException {
        List<String> providers = new java.util.ArrayList<>();
        readStore().fieldNames().forEachRemaining(providers::add);
        return providers.stream().sorted().toList();
    }

    private ObjectNode readStore() throws IOException {
        if (!Files.isRegularFile(settingsPath)) {
            return MAPPER.createObjectNode();
        }
        String content = Files.readString(settingsPath, StandardCharsets.UTF_8);
        try {
            JsonNode parsed = MAPPER.readTree(content);
            return parsed != null && parsed.isObject()
                    ? (ObjectNode) parsed : MAPPER.createObjectNode();
        } catch (JsonParseException | JsonMappingException corrupt) {
            // A truncated write from a crashed process must not brick logins: treat an
            // unreadable store as empty. The next write replaces it atomically.
            return MAPPER.createObjectNode();
        }
    }

    private void writeStore(ObjectNode root) throws IOException {
        Files.createDirectories(settingsPath.getParent());
        restrictPermissions(settingsPath.getParent(), OWNER_DIRECTORY_PERMISSIONS);
        Path temporary = Files.createTempFile(
                settingsPath.getParent(), ".oauth-clients-", ".tmp");
        try {
            Files.writeString(temporary, MAPPER.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(root),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            restrictPermissions(temporary, OWNER_FILE_PERMISSIONS);
            publishAtomically(temporary, settingsPath);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void publishAtomically(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restrictPermissions(
            Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            path.toFile().setReadable(false, false);
            path.toFile().setWritable(false, false);
            path.toFile().setReadable(true, true);
            path.toFile().setWritable(true, true);
        }
    }

    private static void putText(ObjectNode node, String field, String value) {
        if (value == null || value.isBlank()) {
            node.remove(field);
        } else {
            node.put(field, value.trim());
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.textValue().isBlank()
                ? value.textValue() : null;
    }

    private static String normalize(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        return providerId.trim().toLowerCase(Locale.ROOT);
    }
}
