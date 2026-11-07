/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

/** Result of a provider token-count request. */
public record TokenCountResult(
        boolean supported,
        boolean exact,
        long inputTokens,
        String source,
        String diagnostic) {

    public static TokenCountResult unsupported() {
        return new TokenCountResult(false, false, 0L, "none", null);
    }

    static TokenCountResult unavailable(String source, int status) {
        return new TokenCountResult(false, false, 0L, source,
                "HTTP " + status);
    }

    static TokenCountResult exact(long inputTokens, String source) {
        return new TokenCountResult(true, true, Math.max(0L, inputTokens), source, null);
    }
}
