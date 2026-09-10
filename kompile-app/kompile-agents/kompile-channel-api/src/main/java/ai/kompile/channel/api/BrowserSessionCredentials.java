/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/**
 * Portable one-time browser logins and revocable browser sessions shared by every Kompile persona.
 * Login consumption and revocation are filesystem-backed because the admin, chat, and crawl
 * applications are separate JVMs that share one {@code kompile.data.dir}.
 */
public final class BrowserSessionCredentials {

    private static final Duration CODE_LIFETIME = Duration.ofMinutes(5);
    private static final Duration SESSION_LIFETIME = Duration.ofHours(12);
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final String key;
    private final Path codeDirectory;
    private final Path revocationDirectory;
    private final SecureRandom random = new SecureRandom();

    public BrowserSessionCredentials(String key, Path dataDirectory) {
        if (key == null || key.getBytes(StandardCharsets.UTF_8).length
                < ChannelControlHeaders.MINIMUM_TOKEN_BYTES) {
            throw new IllegalArgumentException("Integration administration credential is unavailable");
        }
        this.key = key;
        Path authDirectory = dataDirectory.resolve("config").resolve("integration-browser-auth");
        this.codeDirectory = authDirectory.resolve("codes");
        this.revocationDirectory = authDirectory.resolve("revocations");
    }

    public BrowserLogin issueLogin() {
        try {
            cleanupExpired(codeDirectory);
            String code = randomToken();
            Instant expiresAt = Instant.now().plus(CODE_LIFETIME);
            Path entry = codeDirectory.resolve(hashKey(code) + ".login");
            writeOwnerOnly(entry, Long.toString(expiresAt.getEpochSecond()), true);
            return new BrowserLogin(code, expiresAt);
        } catch (IOException e) {
            throw new IllegalStateException("Could not issue integration browser login", e);
        }
    }

    public BrowserSession exchangeLogin(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Browser login code is required");
        }
        Path issued = codeDirectory.resolve(hashKey(code.trim()) + ".login");
        Path consuming = codeDirectory.resolve(issued.getFileName() + "." + UUID.randomUUID());
        try {
            consume(issued, consuming);
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(
                    Files.readString(consuming, StandardCharsets.UTF_8).trim()));
            if (!expiresAt.isAfter(Instant.now())) {
                throw new IllegalArgumentException("Browser login code is invalid or expired");
            }
            return mintSession();
        } catch (NoSuchFileException missing) {
            throw new IllegalArgumentException("Browser login code is invalid or expired");
        } catch (IllegalArgumentException invalid) {
            throw invalid;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not exchange integration browser login", e);
        } finally {
            try {
                Files.deleteIfExists(consuming);
            } catch (IOException ignored) {
                // A consumed code remains unusable even if cleanup is deferred.
            }
        }
    }

    public boolean verifySession(String credential, String csrfToken, boolean mutation) {
        if (credential == null || credential.isBlank() || isRevoked(credential)) return false;
        return ChannelInternalAuthentication.verifyBrowserSession(
                key, credential, csrfToken, mutation);
    }

    public void revokeSession(String credential) {
        Instant expiresAt = sessionExpiry(credential);
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) return;
        try {
            cleanupExpired(revocationDirectory);
            writeOwnerOnly(
                    revocationDirectory.resolve(hashKey(credential) + ".revoked"),
                    Long.toString(expiresAt.getEpochSecond()), false);
        } catch (IOException e) {
            throw new IllegalStateException("Could not revoke integration browser session", e);
        }
    }

    private BrowserSession mintSession() {
        String csrfToken = randomToken();
        Instant expiresAt = Instant.now().plus(SESSION_LIFETIME);
        String nonce = randomToken();
        byte[] csrfHash = hashBytes(csrfToken);
        long expiresEpoch = expiresAt.getEpochSecond();
        String signature = ChannelInternalAuthentication.sign(
                key, expiresEpoch, nonce, csrfHash);
        String credential = "v1." + expiresEpoch + "." + nonce + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(csrfHash)
                + "." + signature;
        return new BrowserSession(credential, csrfToken, expiresAt);
    }

    private boolean isRevoked(String credential) {
        Path entry = revocationDirectory.resolve(hashKey(credential) + ".revoked");
        if (!Files.exists(entry)) return false;
        try {
            Instant expiry = Instant.ofEpochSecond(Long.parseLong(
                    Files.readString(entry, StandardCharsets.UTF_8).trim()));
            if (!expiry.isAfter(Instant.now())) {
                Files.deleteIfExists(entry);
                return false;
            }
            return true;
        } catch (IOException | RuntimeException unreadable) {
            return true;
        }
    }

    private static Instant sessionExpiry(String credential) {
        if (credential == null) return null;
        try {
            String[] parts = credential.split("\\.", 5);
            return parts.length == 5 && "v1".equals(parts[0])
                    ? Instant.ofEpochSecond(Long.parseLong(parts[1])) : null;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static void consume(Path issued, Path consuming) throws IOException {
        try {
            Files.move(issued, consuming, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(issued, consuming);
        }
    }

    private static void writeOwnerOnly(Path path, String value, boolean createNew) throws IOException {
        Files.createDirectories(path.getParent());
        if (createNew) {
            Files.writeString(path, value, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } else {
            Files.writeString(path, value, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        }
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (UnsupportedOperationException ignored) {
            // Windows ACLs are inherited from the user-private Kompile data directory.
        }
    }

    private static void cleanupExpired(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return;
        Instant now = Instant.now();
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                try {
                    Instant expiry = Instant.ofEpochSecond(Long.parseLong(
                            Files.readString(entry, StandardCharsets.UTF_8).trim()));
                    if (!expiry.isAfter(now)) Files.deleteIfExists(entry);
                } catch (IOException | RuntimeException invalid) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashKey(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes(value));
    }

    private static byte[] hashBytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record BrowserLogin(String code, Instant expiresAt) {
    }

    public record BrowserSession(String credential, String csrfToken, Instant expiresAt) {
    }
}
