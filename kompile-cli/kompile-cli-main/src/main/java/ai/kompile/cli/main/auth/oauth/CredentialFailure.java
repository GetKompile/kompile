/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.oauth;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.io.Serializable;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Safe credential-resolution diagnostics. Only fixed categories and HTTP status are retained;
 * exception messages, response bodies, OAuth codes and causes are never copied.
 * Classification does not authorize retries or changes to the selected identity.
 */
public final class CredentialFailure implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Kind {
        REAUTH_REQUIRED, PERMISSION_DENIED, RATE_LIMITED, TEMPORARY, LOCAL_OR_PROTOCOL, INTERRUPTED
    }

    public enum Operation { UNKNOWN, STORE, REFRESH, REQUEST_AUTH }
    public enum Reason { UNKNOWN, HTTP, TIMEOUT, CONNECT_TIMEOUT, CONNECT, CONNECTION, DNS, END_OF_STREAM, TLS, FILE_IO, INTERRUPTION }

    private final Kind kind;
    private final int statusCode;
    private final Operation operation;
    private final Reason reason;

    private CredentialFailure(Kind kind, int statusCode) {
        this(kind, statusCode, Operation.UNKNOWN, Reason.UNKNOWN);
    }

    private CredentialFailure(Kind kind, int statusCode, Operation operation, Reason reason) {
        this.kind = kind;
        this.statusCode = statusCode;
        this.operation = operation;
        this.reason = reason;
    }

    /** Carry only safe diagnostics across layers, never a provider body, path, or cause. */
    public static IOException during(Operation operation, IOException error) {
        CredentialFailure failure = classify(error);
        if (failure.operation == Operation.UNKNOWN) {
            failure = new CredentialFailure(failure.kind, failure.statusCode, operation, failure.reason);
        }
        return new DiagnosticException(failure);
    }

    private static final class DiagnosticException extends IOException {
        private final CredentialFailure failure;

        private DiagnosticException(CredentialFailure failure) {
            super(failure.message() + " " + failure.diagnostic(), null);
            this.failure = failure;
        }
    }

    @JsonProperty("operation")
    public Operation operation() { return operation; }

    @JsonProperty("reason")
    public Reason reason() { return reason; }

    public String diagnostic() {
        return "[operation=" + operation + ", reason=" + reason + ", http=" + statusCode + "]";
    }

    /** Missing credentials, without an HTTP response. */
    public static CredentialFailure reauthRequired() {
        return new CredentialFailure(Kind.REAUTH_REQUIRED, 0);
    }

    /**
     * Inspect typed causes only, never error-message text. The outermost HTTP failure
     * takes precedence over transport causes. Cyclic cause chains terminate safely.
     * Null and unknown failures are LOCAL_OR_PROTOCOL, not a request to sign in.
     */
    public static CredentialFailure classify(IOException failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean temporary = false;
        boolean interrupted = false;
        boolean tls = false;
        Reason reason = Reason.UNKNOWN;
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof DiagnosticException diagnostic) return diagnostic.failure;
            if (cause instanceof ai.kompile.cli.common.auth.OAuthCredentialLifecycle.ReauthenticationRequiredException) {
                return reauthRequired();
            }
            if (cause instanceof OAuthSupport.OAuthHttpException http) {
                return new CredentialFailure(httpKind(http), http.status(), Operation.UNKNOWN, Reason.HTTP);
            }
            if (cause instanceof InterruptedException
                    || cause instanceof java.nio.channels.ClosedByInterruptException
                    || cause instanceof java.nio.channels.FileLockInterruptionException
                    || (cause instanceof java.io.InterruptedIOException
                        && !(cause instanceof SocketTimeoutException))) {
                interrupted = true;
            }
            if (cause instanceof javax.net.ssl.SSLException) tls = true;
            if (cause instanceof java.nio.file.FileSystemException) reason = Reason.FILE_IO;
            if (cause instanceof java.net.http.HttpConnectTimeoutException) {
                temporary = true;
                reason = Reason.CONNECT_TIMEOUT;
            } else if (cause instanceof java.net.ConnectException) {
                temporary = true;
                reason = Reason.CONNECT;
            } else if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                temporary = true;
                reason = Reason.TIMEOUT;
            } else if (cause instanceof SocketException) {
                temporary = true;
                reason = Reason.CONNECTION;
            } else if (cause instanceof UnknownHostException) {
                temporary = true;
                reason = Reason.DNS;
            } else if (cause instanceof java.io.EOFException) {
                temporary = true;
                reason = Reason.END_OF_STREAM;
            }
        }
        // TLS validation/configuration errors must not become retryable via a nested EOF/socket cause.
        return new CredentialFailure(interrupted ? Kind.INTERRUPTED
                : tls ? Kind.LOCAL_OR_PROTOCOL : temporary ? Kind.TEMPORARY : Kind.LOCAL_OR_PROTOCOL,
                0, Operation.UNKNOWN, interrupted ? Reason.INTERRUPTION : tls ? Reason.TLS : reason);
    }

    private static Kind httpKind(OAuthSupport.OAuthHttpException http) {
        int status = http.status();
        if (status == 429) return Kind.RATE_LIMITED;
        if ((status >= 500 && status <= 599) || status == 408) return Kind.TEMPORARY;
        if (status == 403) return Kind.PERMISSION_DENIED;
        String code = http.oauthError();
        // A rejected OAuth client is a configuration problem, even on HTTP 401.
        if ("invalid_client".equals(code) || "unauthorized_client".equals(code)) {
            return Kind.LOCAL_OR_PROTOCOL;
        }
        if (code != null && switch (code) {
            case "invalid_grant", "invalid_token", "expired_token", "token_expired",
                    "refresh_token_expired", "refresh_token_reused", "refresh_token_invalidated",
                    "refresh_token_revoked", "invalid_refresh_token", "refresh_token_invalid" -> true;
            default -> false;
        }) return Kind.REAUTH_REQUIRED;
        return status == 401 ? Kind.REAUTH_REQUIRED : Kind.LOCAL_OR_PROTOCOL;
    }

    @JsonProperty("kind")
    public Kind kind() {
        return kind;
    }

    /** HTTP response status, or 0 when no HTTP response is known. */
    @JsonProperty("statusCode")
    public int statusCode() {
        return statusCode;
    }

    /** Fixed, safe text suitable for logs and user-visible errors. */
    @JsonProperty("message")
    public String message() {
        return switch (kind) {
            case REAUTH_REQUIRED -> "Credentials are missing, expired, or rejected. Sign in again.";
            case PERMISSION_DENIED -> "Credential access was denied. Check account permissions.";
            case RATE_LIMITED -> "Credential service rate limit reached. Retry later.";
            case TEMPORARY -> "Credential service is temporarily unavailable. Retry later.";
            case LOCAL_OR_PROTOCOL -> "Credential storage or provider-protocol error. Check local configuration.";
            case INTERRUPTED -> "Credential operation was interrupted; this does not indicate rejected credentials.";
        };
    }

    @Override
    public String toString() {
        return "CredentialFailure[kind=" + kind + ", statusCode=" + statusCode + ", message=" + message() + "]";
    }
}
