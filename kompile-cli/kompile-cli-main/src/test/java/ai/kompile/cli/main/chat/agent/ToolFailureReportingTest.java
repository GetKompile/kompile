/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tool failures that are not ToolExecutionException — most notably
 * NoClassDefFoundError / LinkageError storms caused by staging a rebuilt
 * kompile-cli.jar IN PLACE while live sessions still have it open — must
 * (1) render a human-readable diagnostic instead of the raw slashed class
 * name, and (2) never unwind a turn thread silently. Both behaviors are
 * pinned here via AgenticChatLoop.describeThrowable, the single formatter
 * the tool loop, subagent runner, and turn dispatch boundary share.
 */
class ToolFailureReportingTest {

    @Test
    void linkageErrorsRenderAsReadableDiagnostics() {
        NoClassDefFoundError ncdef =
                new NoClassDefFoundError("ai/kompile/cli/main/chat/tools/GrepTool$LineEntry");
        String text = AgenticChatLoop.describeThrowable(ncdef);

        assertTrue(text.startsWith("internal NoClassDefFoundError"),
                "must name the error class, got: " + text);
        assertTrue(text.contains("class initialization/lookup failed"),
                "must explain the failure kind, got: " + text);
        assertTrue(text.contains("GrepTool$LineEntry".replace('$', '$')),
                "must keep the dotted class reference, got: " + text);
        assertFalse(text.contains("ai/kompile/"),
                "slashed internal names must be converted to dotted form, got: " + text);
    }

    @Test
    void binaryMismatchesAreLabeledStaleBuild() {
        NoSuchMethodError nsme = new NoSuchMethodError(
                "ai.kompile.cli.main.chat.config.DirectLlmClient.streamChat(Ljava/lang/String;)Ljava/lang/String;");
        String text = AgenticChatLoop.describeThrowable(nsme);

        assertTrue(text.contains("binary mismatch (stale build)"),
                "NoSuchMethodError must read as a stale-build problem, got: " + text);
    }

    @Test
    void ordinaryExceptionsKeepTheirMessage() {
        String text = AgenticChatLoop.describeThrowable(
                new IllegalStateException("connection refused"));

        assertEquals("connection refused", text,
                "non-linkage failures must pass through unchanged");
    }

    @Test
    void nullThrowableHasSafeFallback() {
        assertEquals("unknown tool failure", AgenticChatLoop.describeThrowable(null));
    }

    @Test
    void messageLessExceptionsFallBackToClassName() {
        String text = AgenticChatLoop.describeThrowable(new RuntimeException());

        assertEquals("java.lang.RuntimeException", text,
                "message-less throwables must not render as the string 'null'");
    }
}
