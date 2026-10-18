/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.render;

import java.util.ArrayList;
import java.util.List;

/**
 * A replaceable presentation snapshot, not a tool result or persistence event.
 * The key identifies one card for its whole lifetime. Sections retain plain payloads
 * plus a safe renderer-generated presentation; classic is the existing transcript output.
 * A custom block with empty summary, classic and sections removes/hides that card.
 */
public record ChatDisplayBlock(String key, String kind, String toolName, String summary,
                               String classic, List<Section> sections,
                               boolean error, boolean running) {
    /** UI retention ceiling, independent of the small terminal/web inline preview. */
    public static final int MAX_SECTION_CHARS = 128_000;

    public ChatDisplayBlock {
        key = key == null ? "" : key;
        kind = kind == null ? "custom" : kind;
        toolName = toolName == null ? "" : toolName;
        summary = boundedStyledText(summary, 1_000);
        classic = TerminalRenderer.safeStyledDisplayText(classic);
        List<Section> retained = new ArrayList<>();
        int remaining = MAX_SECTION_CHARS;
        if (sections != null) {
            for (Section section : sections) {
                if (section == null) continue;
                if (remaining <= 0) {
                    retained.add(new Section("omitted", "metadata", "Omitted",
                            "… (additional sections omitted by UI retention limit)"));
                    break;
                }
                String content = boundedPlainText(section.content(), remaining);
                retained.add(new Section(section.id(), section.kind(), section.title(), content,
                        boundedStyledText(section.formattedContent(), remaining)));
                remaining -= content.length();
            }
        }
        sections = List.copyOf(retained);
    }

    public record Section(String id, String kind, String title, String content, String formattedContent) {
        /** Raw callers remain plain; only renderers supply the optional styled representation. */
        public Section(String id, String kind, String title, String content) {
            this(id, kind, title, content, null);
        }
        public Section {
            id = id == null ? "" : id;
            kind = kind == null ? "text" : kind;
            title = boundedPlainText(title, 1_000);
            content = boundedPlainText(content, MAX_SECTION_CHARS);
            formattedContent = formattedContent == null ? content : boundedStyledText(formattedContent, MAX_SECTION_CHARS);
        }
    }

    public static ChatDisplayBlock text(String key, String kind, String content) {
        String safe = boundedPlainText(content, MAX_SECTION_CHARS);
        return new ChatDisplayBlock(key, kind, "", "", safe,
                List.of(new Section("body", kind, "", safe)), false, false);
    }

    /** Bound printable characters before serializing SGR, never slice an escape sequence. */
    public static String boundedStyledText(String content, int maxChars) {
        if (maxChars < 0) throw new IllegalArgumentException("maxChars must be nonnegative");
        var styled = org.jline.utils.AttributedString.fromAnsi(TerminalRenderer.safeStyledDisplayText(content));
        if (styled.length() <= maxChars) return styled.toAnsi();
        String note = "\n… (remaining chars omitted by UI retention limit)";
        if (maxChars < note.length()) return "…".substring(0, Math.min(1, maxChars));
        int cut = Math.max(0, maxChars - note.length());
        if (cut > 0 && Character.isHighSurrogate(styled.charAt(cut - 1))) cut--;
        return styled.subSequence(0, cut).toAnsi() + note;
    }

    /** Preserve multiline text, never cursor controls, and explicitly disclose a cut. */
    public static String boundedPlainText(String content, int maxChars) {
        if (maxChars < 0) throw new IllegalArgumentException("maxChars must be nonnegative");
        String safe = TerminalRenderer.printableToolOutput(content);
        if (safe.length() <= maxChars) return safe;
        String note = "\n… (remaining chars omitted by UI retention limit)";
        int cut = Math.max(0, maxChars - note.length());
        if (cut > 0 && Character.isHighSurrogate(safe.charAt(cut - 1))) cut--;
        if (maxChars < note.length()) return "…".substring(0, Math.min(1, maxChars));
        return safe.substring(0, cut) + note;
    }
}
