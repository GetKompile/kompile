/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.auth;

import java.io.IOException;

/**
 * Provider-neutral OAuth credential lifecycle shared by Kompile credential managers.
 *
 * <p>Storage backends retain their native locations and security controls. This
 * class centralizes refresh eligibility, refreshed-credential validation, and
 * revocation argument handling so every OAuth consumer follows the same rules.</p>
 */
public final class OAuthCredentialLifecycle {
    private OAuthCredentialLifecycle() {
    }

    /**
     * Refresh an OAuth credential when it falls inside the requested validity
     * window. Credentials without a refresh token remain usable by the caller;
     * this supports non-expiring OAuth-minted provider keys.
     */
    public static ManagedCredential resolve(
            ManagedCredential current,
            long minimumValidityMillis,
            long nowMillis,
            OAuthRefresher refresher) throws IOException {
        requireOAuth(current);
        if (!current.expiresWithin(minimumValidityMillis, nowMillis)
                || !current.hasRefreshToken()) {
            return current;
        }
        return refresh(current, refresher);
    }

    /** Force a refresh and validate the provider result. */
    public static ManagedCredential refresh(
            ManagedCredential current,
            OAuthRefresher refresher) throws IOException {
        requireOAuth(current);
        if (refresher == null) {
            throw new IllegalArgumentException("refresher must not be null");
        }
        if (!current.hasRefreshToken()) {
            throw new IOException("OAuth credential cannot be refreshed without a refresh token");
        }

        ManagedCredential refreshed = refresher.refresh(current);
        if (refreshed == null || !refreshed.isOAuth()) {
            throw new IOException("OAuth refresher must return an OAuth credential");
        }
        return refreshed;
    }

    /**
     * Revoke an OAuth credential. Blank refresh tokens are normalized to null,
     * matching the provider-handler contract used by the application OAuth manager.
     */
    public static boolean revoke(
            ManagedCredential current,
            OAuthRevoker revoker) throws IOException {
        requireOAuth(current);
        if (revoker == null) {
            throw new IllegalArgumentException("revoker must not be null");
        }
        return revoker.revoke(
                current.getAccess(),
                current.hasRefreshToken() ? current.getRefresh() : null);
    }

    private static void requireOAuth(ManagedCredential credential) {
        if (credential == null || !credential.isOAuth()) {
            throw new IllegalArgumentException("OAuth credential is required");
        }
    }

    @FunctionalInterface
    public interface OAuthRefresher {
        ManagedCredential refresh(ManagedCredential current) throws IOException;
    }

    @FunctionalInterface
    public interface OAuthRevoker {
        boolean revoke(String accessToken, String refreshToken) throws IOException;
    }
}
