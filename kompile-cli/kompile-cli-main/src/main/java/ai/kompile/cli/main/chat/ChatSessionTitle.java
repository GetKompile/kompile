/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package ai.kompile.cli.main.chat;

/** Mutable title shared by the standard and managed-passthrough chat sessions. */
final class ChatSessionTitle {

    static final int MAX_LENGTH = 80;

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

    /** Normalize a prompt and retain only its short leading portion. */
    static String fromPrompt(String prompt) {
        if (prompt == null) return null;
        String normalized = prompt.replace('\033', ' ').replace('\007', ' ')
                .strip().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) return null;
        return normalized.length() <= MAX_LENGTH
                ? normalized
                : normalized.substring(0, MAX_LENGTH - 3) + "...";
    }
}
