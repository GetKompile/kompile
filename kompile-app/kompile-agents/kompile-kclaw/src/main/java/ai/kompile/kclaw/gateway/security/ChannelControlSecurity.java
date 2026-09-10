/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.security;

import ai.kompile.channel.api.ChannelControlHeaders;
import ai.kompile.channel.api.ChannelInternalAuthentication;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/** Loads or atomically creates the bearer credential protecting channel administration. */
@Component
public final class ChannelControlSecurity {

    private final String configuredToken;
    private final Path tokenPath;
    private volatile String token;

    public ChannelControlSecurity(
            @Value("${KOMPILE_CHANNEL_ADMIN_TOKEN:}") String configuredToken,
            @Value("${kompile.data.dir:${user.home}/.kompile}") String kompileDataDir) {
        this.configuredToken = configuredToken == null ? "" : configuredToken.trim();
        this.tokenPath = Path.of(kompileDataDir, "config", ChannelControlHeaders.TOKEN_FILE_NAME);
    }

    @PostConstruct
    public void initialize() {
        try {
            if (!configuredToken.isBlank()) {
                requireStrong(configuredToken);
                token = configuredToken;
            } else {
                token = loadOrCreateToken();
            }
            ensureTokenIgnored();
        } catch (IOException e) {
            throw new IllegalStateException("Could not initialize channel administration credential", e);
        }
    }

    public boolean matches(String supplied) {
        if (token == null || supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }

    public String signInternalChat(byte[] body, long timestamp, String nonce) {
        return ChannelInternalAuthentication.sign(token, timestamp, nonce, body);
    }

    public String opaqueChannelConversationId(String sessionKey) {
        return ChannelInternalAuthentication.opaqueConversationId(token, sessionKey);
    }

    Path tokenPath() {
        return tokenPath;
    }

    private String loadOrCreateToken() throws IOException {
        if (Files.isRegularFile(tokenPath)) {
            return readToken();
        }
        Files.createDirectories(tokenPath.getParent());
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Path temporary = Files.createTempFile(tokenPath.getParent(), ".channel-admin-", ".tmp");
        try {
            Files.writeString(
                    temporary,
                    generated,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            restrictPermissions(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            publishCompletedFile(temporary, tokenPath);
            return generated;
        } catch (FileAlreadyExistsException racedWithAnotherProcess) {
            return readToken();
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void publishCompletedFile(Path completed, Path target) throws IOException {
        try {
            Files.createLink(target, completed);
            return;
        } catch (FileAlreadyExistsException e) {
            throw e;
        } catch (UnsupportedOperationException | IOException hardLinkUnavailable) {
            // Fall back to a non-replacing same-directory move on filesystems without hard links.
        }
        try {
            Files.move(completed, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(completed, target);
        }
    }

    private String readToken() throws IOException {
        String loaded = Files.readString(tokenPath, StandardCharsets.UTF_8).trim();
        requireStrong(loaded);
        restrictPermissions(tokenPath);
        return loaded;
    }

    private void ensureTokenIgnored() throws IOException {
        Path dataRoot = tokenPath.getParent().getParent();
        Files.createDirectories(dataRoot);
        Path gitignore = dataRoot.resolve(".gitignore");
        String rule = "config/" + ChannelControlHeaders.TOKEN_FILE_NAME;
        String existing = Files.isRegularFile(gitignore)
                ? Files.readString(gitignore, StandardCharsets.UTF_8)
                : "";
        if (existing.lines().map(String::trim).anyMatch(rule::equals)) {
            return;
        }
        String prefix = existing.isEmpty() || existing.endsWith("\n") ? "" : System.lineSeparator();
        Files.writeString(
                gitignore,
                prefix + "# Kompile runtime credentials\n" + rule + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static void requireStrong(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length < ChannelControlHeaders.MINIMUM_TOKEN_BYTES) {
            throw new IllegalStateException(
                    ChannelControlHeaders.TOKEN_ENVIRONMENT + " must contain at least "
                            + ChannelControlHeaders.MINIMUM_TOKEN_BYTES + " bytes");
        }
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            path.toFile().setReadable(false, false);
            path.toFile().setWritable(false, false);
            path.toFile().setReadable(true, true);
            path.toFile().setWritable(true, true);
        }
    }
}
