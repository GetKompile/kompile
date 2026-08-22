package ai.kompile.cli.main.codeindex;

import ai.kompile.cli.main.chat.tools.ProgressPrintStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeIndexDiagnosticsTest {

    @Test
    void warningUsesAlertSinkAndIsNotDuplicatedIntoToolProgress() {
        List<String> alerts = new ArrayList<>();
        List<String> progress = new ArrayList<>();
        Runnable cleanup = CodeIndexDiagnostics.installAlertSink(alerts::add);
        try {
            ProgressPrintStream stream = new ProgressPrintStream(progress::add);
            stream.println("Warning: connectivity pass failed: unavailable");
            stream.println("Indexed 10 files");

            assertEquals(List.of("Warning: connectivity pass failed: unavailable"), alerts);
            assertEquals(List.of("Indexed 10 files"), progress);
            assertTrue(CodeIndexDiagnostics.hasAlertSink());
        } finally {
            cleanup.run();
        }
    }

    @Test
    void nestedRegistrationRestoresThePreviousSessionSink() {
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        Runnable firstCleanup = CodeIndexDiagnostics.installAlertSink(first::add);
        Runnable secondCleanup = CodeIndexDiagnostics.installAlertSink(second::add);
        try {
            CodeIndexDiagnostics.alert("newest");
            assertEquals(List.of("newest"), second);
            assertTrue(first.isEmpty());

            secondCleanup.run();
            CodeIndexDiagnostics.alert("restored");
            assertEquals(List.of("restored"), first);
        } finally {
            secondCleanup.run();
            firstCleanup.run();
        }
        assertFalse(CodeIndexDiagnostics.hasAlertSink());
    }

    @Test
    void throwingSinkFallsBackToStderrAndPartialProgressIsNotDuplicated() {
        PrintStream previousErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        List<String> progress = new ArrayList<>();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        Runnable cleanup = CodeIndexDiagnostics.installAlertSink(message -> {
            throw new IllegalStateException("closing");
        });
        try {
            CodeIndexDiagnostics.alert("[code-index] fallback warning");
        } finally {
            cleanup.run();
        }

        ProgressPrintStream stream = new ProgressPrintStream(progress::add);
        stream.print("tick");
        stream.flush();
        System.setErr(previousErr);

        String stderr = err.toString(StandardCharsets.UTF_8);
        assertTrue(stderr.contains("fallback warning"));
        assertEquals(1, countOccurrences(stderr, "tick"));
        assertEquals(List.of("tick"), progress);
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        for (int at = 0; (at = value.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
    }
}
