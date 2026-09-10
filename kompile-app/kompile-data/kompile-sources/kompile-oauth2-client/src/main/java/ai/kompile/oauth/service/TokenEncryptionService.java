/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.oauth.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.nio.file.StandardOpenOption;

/**
 * Service for encrypting and decrypting OAuth tokens using AES-256-GCM.
 * <p>
 * The encryption key is either provided via configuration property or
 * auto-generated and stored in the Kompile config directory.
 */
@Service
public class TokenEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(TokenEncryptionService.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int AES_KEY_SIZE = 256;
    private static final Object KEY_INITIALIZATION_MONITOR = new Object();

    @Value("${kompile.oauth.encryption-key:${KOMPILE_OAUTH_KEY:}}")
    private String configuredKey;

    @Value("${kompile.data.dir:${user.home}/.kompile}")
    private String kompileDataDir;

    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        try {
            this.secretKey = loadOrGenerateKey();
            log.info("OAuth token encryption service initialized");
        } catch (Exception e) {
            log.error("Failed to initialize encryption service", e);
            throw new RuntimeException("Failed to initialize token encryption", e);
        }
    }

    /**
     * Encrypt a plaintext string.
     *
     * @param plainText the text to encrypt
     * @return Base64-encoded encrypted data (IV + ciphertext)
     */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return null;
        }

        try {
            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            // Encrypt
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // Combine IV and ciphertext
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherText);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new RuntimeException("Failed to encrypt token", e);
        }
    }

    /**
     * Decrypt an encrypted string.
     *
     * @param encryptedText Base64-encoded encrypted data (IV + ciphertext)
     * @return the decrypted plaintext
     */
    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isEmpty()) {
            return null;
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(encryptedText);

            // Extract IV and ciphertext
            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);
            byte[] cipherText = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherText);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            // Decrypt
            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed", e);
            throw new RuntimeException("Failed to decrypt token", e);
        }
    }

    /**
     * Load encryption key from config or generate a new one.
     */
    private SecretKey loadOrGenerateKey() throws Exception {
        // First, check if key is provided via configuration
        if (configuredKey != null && !configuredKey.isBlank()) {
            log.info("Using configured OAuth encryption key");
            return key(Base64.getDecoder().decode(configuredKey.trim()), "configured key");
        }

        // Otherwise, serialize key discovery/publication both inside this JVM and across all
        // Kompile personas sharing the same data directory. Readers also take the lock, so none
        // can observe a partially published key.
        Path keyFilePath = Paths.get(kompileDataDir, "config", "oauth-encryption.key");
        Path lockFilePath = keyFilePath.resolveSibling(keyFilePath.getFileName() + ".lock");
        synchronized (KEY_INITIALIZATION_MONITOR) {
            Files.createDirectories(keyFilePath.getParent());
            try (FileChannel lockChannel = FileChannel.open(lockFilePath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = lockChannel.lock()) {
                restrictPermissions(lockFilePath);
                if (Files.isRegularFile(keyFilePath)) {
                    restrictPermissions(keyFilePath);
                    return loadKey(keyFilePath);
                }

                log.info("Generating new OAuth encryption key");
                SecretKey newKey = generateKey();
                try {
                    saveKey(newKey, keyFilePath);
                    return newKey;
                } catch (FileAlreadyExistsException unexpectedExternalPublisher) {
                    restrictPermissions(keyFilePath);
                    return loadKey(keyFilePath);
                }
            }
        }
    }

    /**
     * Generate a new AES-256 key.
     */
    private SecretKey generateKey() throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(AES_KEY_SIZE, new SecureRandom());
        return keyGen.generateKey();
    }

    /**
     * Save the encryption key to a file.
     */
    private void saveKey(SecretKey key, Path keyFilePath) throws IOException {
        // Create parent directories
        Files.createDirectories(keyFilePath.getParent());

        // Write key as Base64
        String keyBase64 = Base64.getEncoder().encodeToString(key.getEncoded());
        Path temporary = Files.createTempFile(keyFilePath.getParent(), ".oauth-key-", ".tmp");
        try {
            Files.writeString(
                    temporary,
                    keyBase64,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            restrictPermissions(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            publishCompletedFile(temporary, keyFilePath);
            restrictPermissions(keyFilePath);
        } finally {
            Files.deleteIfExists(temporary);
        }

        log.info("Saved OAuth encryption key to: {}", keyFilePath);
    }

    private static void publishCompletedFile(Path completed, Path target) throws IOException {
        if (Files.exists(target)) throw new FileAlreadyExistsException(target.toString());
        try {
            Files.move(completed, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(completed, target);
        }
    }

    private static void restrictPermissions(Path path) throws IOException {
        try {
            Set<PosixFilePermission> ownerOnly = Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, ownerOnly);
            if (!Files.getPosixFilePermissions(path).equals(ownerOnly)) {
                throw new IOException("Could not verify owner-only permissions for " + path);
            }
            return;
        } catch (UnsupportedOperationException unsupportedPosix) {
            // Windows/non-POSIX fallback below.
        }
        File file = path.toFile();
        boolean secured = file.setReadable(false, false)
                && file.setWritable(false, false)
                && file.setExecutable(false, false)
                && file.setReadable(true, true)
                && file.setWritable(true, true);
        if (!secured) {
            throw new IOException("Could not establish owner-only permissions for " + path);
        }
    }

    private SecretKey loadKey(Path keyFilePath) throws IOException {
        log.info("Loading OAuth encryption key from: {}", keyFilePath);
        String keyBase64 = Files.readString(keyFilePath, StandardCharsets.UTF_8).trim();
        return key(Base64.getDecoder().decode(keyBase64), "key file " + keyFilePath);
    }

    private SecretKey key(byte[] keyBytes, String source) {
        if (keyBytes.length != AES_KEY_SIZE / 8) {
            throw new IllegalStateException(source + " must contain a 256-bit AES key");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Check if the encryption service is properly initialized.
     */
    public boolean isInitialized() {
        return secretKey != null;
    }
}
