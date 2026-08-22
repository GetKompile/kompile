/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.chat.render;

import java.util.Locale;

/**
 * Stable foreground states shared by the status bar and terminal tab title.
 */
public enum ChatActivityPhase {
    READY("Ready"),
    THINKING("Thinking"),
    WORKING("Working"),
    RESPONDING("Responding"),
    AWAITING_INPUT("Awaiting input"),
    INTERRUPTED("Interrupted"),
    FAILED("Failed"),
    BLOCKED("Blocked");

    private final String label;

    ChatActivityPhase(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Phases that represent active compute/tool work rather than a terminal state. */
    public boolean isBusy() {
        return this == THINKING || this == WORKING || this == RESPONDING;
    }

    public static ChatActivityPhase fromLabel(String raw) {
        if (raw == null || raw.isBlank()) return READY;
        String label = raw.toLowerCase(Locale.ROOT);
        if (label.contains("interrupt")) return INTERRUPTED;
        if (label.contains("await") || label.contains("question") || label.contains("approval")) {
            return AWAITING_INPUT;
        }
        if (label.contains("fail") || label.contains("error")) return FAILED;
        if (label.contains("block")) return BLOCKED;
        if (label.contains("respond")) return RESPONDING;
        if (label.contains("work") || label.contains("tool") || label.contains("compil")) return WORKING;
        if (label.contains("think") || label.contains("generat")) return THINKING;
        if (label.contains("ready") || label.contains("idle") || label.contains("complete")) return READY;
        return WORKING;
    }

    public static String detailFromLabel(String raw) {
        if (raw == null || raw.isBlank()) return "";
        int separator = raw.indexOf(':');
        if (separator >= 0 && separator + 1 < raw.length()) {
            return raw.substring(separator + 1).trim();
        }
        return "";
    }
}
