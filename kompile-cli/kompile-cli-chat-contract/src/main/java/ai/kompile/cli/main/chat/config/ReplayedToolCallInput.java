/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

/** A tool call replayed into a follow-up provider request. */
public record ReplayedToolCallInput(
        String toolName, String callId, String argumentsJson) {
}
