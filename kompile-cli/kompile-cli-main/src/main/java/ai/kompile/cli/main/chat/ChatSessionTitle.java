/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package ai.kompile.cli.main.chat;

/** Mutable title shared by the standard and managed-passthrough chat sessions. */
final class ChatSessionTitle {

    private String title;

    /** Set the title from the first non-blank prompt only. */
    synchronized boolean initializeFromPrompt(String prompt) {
        if (title != null) return false;
        String derived = fromPrompt(prompt);
        if (derived == null) return false;
        title = derived;
        return true;
    }

    /** Explicitly replace the title, as requested by {@code /title}. */
    synchronized String replace(String requestedTitle) {
        String replacement = fromPrompt(requestedTitle);
        if (replacement == null) {
            throw new IllegalArgumentException("Session title must not be blank");
        }
        title = replacement;
        return title;
    }

    synchronized String get() {
        return title;
    }

    /** Normalize without discarding title text. */
    static String fromPrompt(String prompt) {
        return ai.kompile.cli.common.chat.sources.KompileTranscriptFormat.normalizeTitle(prompt);
    }
}
