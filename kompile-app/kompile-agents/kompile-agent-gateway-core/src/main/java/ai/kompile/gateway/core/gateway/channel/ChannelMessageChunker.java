/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.gateway.core.gateway.channel;

import java.util.ArrayList;
import java.util.List;

/** Unicode-safe text splitting for provider message-size limits. */
public final class ChannelMessageChunker {

    private ChannelMessageChunker() {
    }

    public static List<String> split(String content, int maxUtf16Units) {
        if (content == null) return List.of("");
        if (maxUtf16Units < 2) throw new IllegalArgumentException("Message chunk limit is too small");
        if (content.length() <= maxUtf16Units) return List.of(content);
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(content.length(), start + maxUtf16Units);
            if (end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))) {
                end--;
            }
            if (end < content.length()) {
                int natural = Math.max(content.lastIndexOf('\n', end - 1),
                        content.lastIndexOf(' ', end - 1));
                if (natural > start + maxUtf16Units / 2) end = natural + 1;
            }
            if (end <= start) end = Math.min(content.length(), start + maxUtf16Units);
            chunks.add(content.substring(start, end));
            start = end;
        }
        return List.copyOf(chunks);
    }
}
