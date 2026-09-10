/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/** Domain-separated HMAC wire contract for admin-to-chat channel execution. */
public final class ChannelInternalAuthentication {

    public static final String TIMESTAMP_HEADER = "X-Kompile-Channel-Timestamp";
    public static final String NONCE_HEADER = "X-Kompile-Channel-Nonce";
    public static final String SIGNATURE_HEADER = "X-Kompile-Channel-Signature";

    private ChannelInternalAuthentication() {
    }

    public static String sign(String key, long timestamp, String nonce, byte[] body) {
        return encode(hmac(key, requestPayload(timestamp, nonce, body)));
    }

    public static boolean verify(
            String key, long timestamp, String nonce, byte[] body, String suppliedSignature) {
        if (suppliedSignature == null) return false;
        byte[] expected = hmac(key, requestPayload(timestamp, nonce, body));
        byte[] supplied;
        try {
            supplied = Base64.getUrlDecoder().decode(suppliedSignature);
        } catch (IllegalArgumentException invalidBase64) {
            return false;
        }
        return MessageDigest.isEqual(expected, supplied);
    }

    public static String opaqueConversationId(String key, String sessionKey) {
        return "channel-" + encode(hmac(key,
                ("conversation\n" + sessionKey).getBytes(StandardCharsets.UTF_8)));
    }

    /** Verify the stateless browser credential minted by the admin control plane. */
    public static boolean verifyBrowserSession(
            String key, String credential, String suppliedCsrfToken, boolean mutation) {
        if (credential == null || credential.isBlank()) return false;
        try {
            String[] parts = credential.split("\\.", 5);
            if (parts.length != 5 || !"v1".equals(parts[0])) return false;
            long expiresEpoch = Long.parseLong(parts[1]);
            Instant expiresAt = Instant.ofEpochSecond(expiresEpoch);
            Instant now = Instant.now();
            if (!expiresAt.isAfter(now)
                    || expiresAt.isAfter(now.plus(Duration.ofHours(12)).plusSeconds(60))) {
                return false;
            }
            byte[] csrfHash = Base64.getUrlDecoder().decode(parts[3]);
            if (!verify(key, expiresEpoch, parts[2], csrfHash, parts[4])) return false;
            return !mutation || suppliedCsrfToken != null
                    && MessageDigest.isEqual(csrfHash,
                            MessageDigest.getInstance("SHA-256").digest(
                                    suppliedCsrfToken.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException | java.security.GeneralSecurityException invalid) {
            return false;
        }
    }

    private static byte[] requestPayload(long timestamp, String nonce, byte[] body) {
        byte[] prefix = ("request\n" + timestamp + "\n" + nonce + "\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, payload, 0, prefix.length);
        System.arraycopy(body, 0, payload, prefix.length, body.length);
        return payload;
    }

    private static byte[] hmac(String key, byte[] payload) {
        if (key == null || key.getBytes(StandardCharsets.UTF_8).length
                < ChannelControlHeaders.MINIMUM_TOKEN_BYTES) {
            throw new IllegalArgumentException("Channel internal authentication key is unavailable");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
