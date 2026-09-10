/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.gateway.core.gateway.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelMessageChunkerTest {

    @Test
    void preservesContentAndNeverSplitsSurrogatePair() {
        String content = "alpha 😀 beta gamma delta";
        var chunks = ChannelMessageChunker.split(content, 9);

        assertEquals(content, String.join("", chunks));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.length() <= 9));
        assertFalse(chunks.stream().anyMatch(chunk -> !chunk.isEmpty()
                && Character.isHighSurrogate(chunk.charAt(chunk.length() - 1))));
        assertFalse(chunks.stream().anyMatch(chunk -> !chunk.isEmpty()
                && Character.isLowSurrogate(chunk.charAt(0))));
    }
}
