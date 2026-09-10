/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.agent.spin;

import ai.kompile.utils.HashUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/** Detached Ed25519 publisher signatures for complete {@code .kspin} archives. */
public final class SpinSignature {
    public static final String ALGORITHM = "Ed25519";
    public static final String SIGNATURE_SUFFIX = ".sig";

    private static final String SCHEMA_VERSION = "1";
    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final long MAX_KEY_BYTES = 64L * 1024L;
    private static final long MAX_SIGNATURE_FILE_BYTES = 64L * 1024L;
    private static final byte[] DOMAIN = "KOMPILE-KSPIN-SHA256-SIGNATURE-V1\0"
            .getBytes(StandardCharsets.US_ASCII);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SpinSignature() { }

    /** Generate a PEM-encoded Ed25519 publisher key pair. */
    public static KeyFiles generateKeyPair(
            Path privateKeyFile, Path publicKeyFile, boolean replaceExisting) throws IOException {
        Path privateTarget = outputPath(privateKeyFile, "private key");
        Path publicTarget = outputPath(publicKeyFile, "public key");
        if (privateTarget.equals(publicTarget)) {
            throw new IOException("Private and public key paths must be different");
        }
        if (!replaceExisting && (Files.exists(privateTarget, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(publicTarget, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Publisher key output already exists; use --force to replace it");
        }

        KeyPair keyPair;
        try {
            keyPair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IOException("Ed25519 is not available in this Java runtime", e);
        }

        Path privateTemporary = temporarySibling(privateTarget);
        Path publicTemporary = temporarySibling(publicTarget);
        try {
            writePem(privateTemporary, "PRIVATE KEY", keyPair.getPrivate().getEncoded(), true);
            writePem(publicTemporary, "PUBLIC KEY", keyPair.getPublic().getEncoded(), false);
            SpinArchive.moveAtomic(publicTemporary, publicTarget);
            SpinArchive.moveAtomic(privateTemporary, privateTarget);
        } finally {
            Files.deleteIfExists(privateTemporary);
            Files.deleteIfExists(publicTemporary);
        }
        return new KeyFiles(privateTarget, publicTarget,
                HashUtils.sha256Hex(keyPair.getPublic().getEncoded()));
    }

    /** Parse and validate an Ed25519 private key before starting a potentially large build. */
    public static void validatePrivateKey(Path privateKeyFile) throws IOException {
        readPrivateKey(privateKeyFile);
    }

    /** Sign the complete archive and atomically write a detached JSON signature envelope. */
    public static Signed sign(Path archive, Path privateKeyFile, Path signatureFile)
            throws IOException {
        Path source = regularFile(archive, "spin archive");
        Path output = outputPath(signatureFile, "signature output");
        if (source.equals(output)) {
            throw new IOException("Signature output must be different from the spin archive");
        }
        PrivateKey privateKey = readPrivateKey(privateKeyFile);
        Signature signer = signature();
        try {
            byte[] archiveDigest = digest(source, null);
            signer.initSign(privateKey);
            signer.update(DOMAIN);
            signer.update(archiveDigest);
            byte[] signature = signer.sign();
            String archiveSha256 = HashUtils.toHex(archiveDigest);

            ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put("schemaVersion", SCHEMA_VERSION);
            envelope.put("algorithm", ALGORITHM);
            envelope.put("digestAlgorithm", "SHA-256");
            envelope.put("archiveSha256", archiveSha256);
            envelope.put("signature", Base64.getEncoder().encodeToString(signature));
            writeAtomic(output, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(envelope));
            return new Signed(output, archiveSha256);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not sign spin archive with Ed25519", e);
        }
    }

    /** Verify a detached signature against an explicitly trusted Ed25519 public key. */
    public static Verification verify(Path archive, Path signatureFile, Path trustedPublicKeyFile)
            throws IOException {
        Path source = regularFile(archive, "spin archive");
        SignatureEnvelope envelope = readEnvelope(signatureFile);
        PublicKey publicKey = readPublicKey(trustedPublicKeyFile);
        return verifyContent(source, envelope, publicKey, null, envelopePath(signatureFile));
    }

    /**
     * Copy and verify in one streaming pass, returning the exact private copy that was
     * authenticated. Consumers must parse or extract this path rather than reopening
     * the caller-controlled source path.
     */
    public static VerifiedArchive stageVerified(
            Path archive, Path signatureFile, Path trustedPublicKeyFile) throws IOException {
        Path source = regularFile(archive, "spin archive");
        SignatureEnvelope envelope = readEnvelope(signatureFile);
        PublicKey publicKey = readPublicKey(trustedPublicKeyFile);
        Path staged = Files.createTempFile("kompile-spin-verified-", ".kspin")
                .toAbsolutePath().normalize();
        try {
            try {
                Files.setPosixFilePermissions(staged,
                        PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows and non-POSIX file systems do not expose POSIX modes.
            }
            Verification verification;
            try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(staged,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))) {
                verification = verifyContent(source, envelope, publicKey, output,
                        envelopePath(signatureFile));
            }
            return new VerifiedArchive(staged, verification);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(staged);
            throw failure;
        }
    }

    private static Verification verifyContent(
            Path source, SignatureEnvelope envelope, PublicKey publicKey,
            OutputStream authenticatedCopy, Path signatureFile) throws IOException {
        Signature verifier = signature();
        try {
            byte[] archiveDigest = digest(source, authenticatedCopy);
            String actualSha256 = HashUtils.toHex(archiveDigest);
            if (!actualSha256.equals(envelope.archiveSha256())) {
                throw new IOException("Spin signature archive SHA-256 does not match " + source);
            }
            verifier.initVerify(publicKey);
            verifier.update(DOMAIN);
            verifier.update(archiveDigest);
            if (!verifier.verify(envelope.signature())) {
                throw new IOException("Spin signature is not valid for the trusted publisher key");
            }
            return new Verification(actualSha256,
                    HashUtils.sha256Hex(publicKey.getEncoded()), signatureFile);
        } catch (GeneralSecurityException e) {
            throw new IOException("Could not verify spin Ed25519 signature", e);
        }
    }

    public static Path defaultSignaturePath(Path archive) {
        Path source = archive.toAbsolutePath().normalize();
        return source.resolveSibling(source.getFileName() + SIGNATURE_SUFFIX);
    }

    private static byte[] digest(Path source, OutputStream copy) throws IOException {
        MessageDigest digest = HashUtils.newSha256Digest();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(source))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                digest.update(buffer, 0, read);
                if (copy != null) copy.write(buffer, 0, read);
            }
        }
        return digest.digest();
    }

    private static SignatureEnvelope readEnvelope(Path signatureFile) throws IOException {
        Path source = regularFile(signatureFile, "spin signature");
        if (Files.size(source) > MAX_SIGNATURE_FILE_BYTES) {
            throw new IOException("Spin signature exceeds " + MAX_SIGNATURE_FILE_BYTES + " bytes");
        }
        JsonNode root = MAPPER.readTree(source.toFile());
        if (root == null || !root.isObject()) {
            throw new IOException("Spin signature must contain a JSON object");
        }
        if (!SCHEMA_VERSION.equals(root.path("schemaVersion").asText())) {
            throw new IOException("Unsupported spin signature schemaVersion: "
                    + root.path("schemaVersion").asText());
        }
        if (!ALGORITHM.equals(root.path("algorithm").asText())) {
            throw new IOException("Unsupported spin signature algorithm: "
                    + root.path("algorithm").asText());
        }
        if (!"SHA-256".equals(root.path("digestAlgorithm").asText())) {
            throw new IOException("Unsupported spin signature digest algorithm: "
                    + root.path("digestAlgorithm").asText());
        }
        String archiveSha256 = root.path("archiveSha256").asText("")
                .toLowerCase(Locale.ROOT);
        if (!archiveSha256.matches("[0-9a-f]{64}")) {
            throw new IOException("Spin signature has an invalid archiveSha256");
        }
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(root.path("signature").asText(""));
        } catch (IllegalArgumentException e) {
            throw new IOException("Spin signature is not valid Base64", e);
        }
        if (signature.length != 64) {
            throw new IOException("Spin Ed25519 signature must be 64 bytes");
        }
        return new SignatureEnvelope(archiveSha256, signature);
    }

    private static PrivateKey readPrivateKey(Path path) throws IOException {
        byte[] encoded = readPem(path, "PRIVATE KEY");
        try {
            return KeyFactory.getInstance(ALGORITHM)
                    .generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            throw new IOException("Invalid Ed25519 private key: " + path, e);
        }
    }

    private static PublicKey readPublicKey(Path path) throws IOException {
        byte[] encoded = readPem(path, "PUBLIC KEY");
        try {
            return KeyFactory.getInstance(ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            throw new IOException("Invalid Ed25519 public key: " + path, e);
        }
    }

    private static byte[] readPem(Path path, String type) throws IOException {
        Path source = regularFile(path, type.toLowerCase(Locale.ROOT));
        if (Files.size(source) > MAX_KEY_BYTES) {
            throw new IOException(type + " exceeds " + MAX_KEY_BYTES + " bytes");
        }
        String text = Files.readString(source, StandardCharsets.US_ASCII)
                .replace("\r", "").trim();
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        if (!text.startsWith(begin) || !text.endsWith(end)) {
            throw new IOException("Expected PEM " + type + " in " + source);
        }
        String body = text.substring(begin.length(), text.length() - end.length())
                .replaceAll("[\\r\\n\\t ]", "");
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid Base64 in PEM " + type + ": " + source, e);
        }
    }

    private static void writePem(Path target, String type, byte[] encoded, boolean privateKey)
            throws IOException {
        Files.createDirectories(target.getParent());
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        Files.writeString(target, "-----BEGIN " + type + "-----\n" + body
                        + "\n-----END " + type + "-----\n",
                StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(
                    privateKey ? "rw-------" : "rw-r--r--"));
        } catch (UnsupportedOperationException ignored) {
            // Windows and non-POSIX file systems do not expose POSIX modes.
        }
    }

    private static void writeAtomic(Path target, byte[] content) throws IOException {
        rejectSymlinkComponents(target, "spin signature output");
        Files.createDirectories(target.getParent());
        Path temporary = temporarySibling(target);
        try {
            Files.write(temporary, content, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
            SpinArchive.moveAtomic(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Signature signature() throws IOException {
        try {
            return Signature.getInstance(ALGORITHM);
        } catch (GeneralSecurityException e) {
            throw new IOException("Ed25519 is not available in this Java runtime", e);
        }
    }

    private static Path regularFile(Path value, String label) throws IOException {
        if (value == null) throw new IOException(label + " is required");
        Path normalized = value.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a regular file: " + normalized);
        }
        return normalized;
    }

    private static Path outputPath(Path value, String label) throws IOException {
        if (value == null) throw new IOException(label + " is required");
        Path normalized = value.toAbsolutePath().normalize();
        rejectSymlinkComponents(normalized, label);
        return normalized;
    }

    private static Path envelopePath(Path value) {
        return value.toAbsolutePath().normalize();
    }

    private static Path temporarySibling(Path target) {
        return target.resolveSibling("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
    }

    private static void rejectSymlinkComponents(Path path, String label) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current == null ? part : current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(current)) {
                throw new IOException(label + " contains a symbolic link: " + current);
            }
        }
    }

    public record KeyFiles(Path privateKey, Path publicKey, String publicKeySha256) { }
    public record Signed(Path signatureFile, String archiveSha256) { }
    public record Verification(String archiveSha256, String publicKeySha256,
                               Path signatureFile) { }
    public record VerifiedArchive(Path archive, Verification verification)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            Files.deleteIfExists(archive);
        }
    }
    private record SignatureEnvelope(String archiveSha256, byte[] signature) { }
}
