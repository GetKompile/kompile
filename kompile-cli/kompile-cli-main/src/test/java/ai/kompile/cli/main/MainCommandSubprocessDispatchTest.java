/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainCommandSubprocessDispatchTest {
    @Test
    void embeddingMarkerMustBeTheFirstInternalArgument() {
        assertTrue(MainCommand.isEmbeddingSubprocessRequest(
                new String[]{"--subprocess=embedding"}));
        assertFalse(MainCommand.isEmbeddingSubprocessRequest(
                new String[]{"chat", "--subprocess=embedding"}));
        assertFalse(MainCommand.isEmbeddingSubprocessRequest(new String[0]));
    }
}
