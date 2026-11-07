/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

/** Provider-neutral terminal failure categories used by the chat coordinator. */
public enum FailureKind {
    NONE,
    CONTEXT_OVERFLOW,
    AUTHENTICATION,
    CREDENTIAL_UNAVAILABLE,
    RATE_LIMITED,
    TEMPORARY,
    PERMISSION_DENIED,
    QUOTA_EXHAUSTED,
    REFUSAL,
    TRUNCATED,
    PROVIDER_ERROR
}
