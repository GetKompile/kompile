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

package ai.kompile.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 hashing helpers. Canonical home for the hex-digest logic previously
 * copy-pasted as private {@code sha256}/{@code sha256Hex} helpers across modules.
 */
public final class HashUtils {

    private HashUtils() {}

    /** Lowercase hex SHA-256 digest of the given bytes. */
    public static String sha256Hex(byte[] data) {
        return toHex(newSha256Digest().digest(data));
    }

    /** Lowercase hex SHA-256 digest of the UTF-8 encoding of the given string. */
    public static String sha256Hex(String data) {
        return sha256Hex(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Lowercase hex SHA-256 digest of a file's contents, streamed so large files
     * are not loaded into memory.
     */
    public static String sha256Hex(Path file) throws IOException {
        MessageDigest digest = newSha256Digest();
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream din = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[8192];
            while (din.read(buffer) != -1) {
                // digest updated by the stream
            }
        }
        return toHex(digest.digest());
    }

    /**
     * Truncated lowercase hex SHA-256 digest of the UTF-8 encoding of the given
     * string — for compact content keys where the full 64 chars are overkill.
     */
    public static String sha256HexShort(String data, int hexChars) {
        String full = sha256Hex(data);
        return hexChars > 0 && hexChars < full.length() ? full.substring(0, hexChars) : full;
    }

    /**
     * Create an independent incremental SHA-256 digest for callers that must hash
     * bytes while streaming them into another sink.
     */
    public static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Lowercase hexadecimal encoding used by all hash helpers. */
    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
