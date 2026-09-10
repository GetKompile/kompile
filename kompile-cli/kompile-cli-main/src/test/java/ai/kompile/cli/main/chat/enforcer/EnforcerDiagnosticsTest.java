package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.main.chat.tui.TopBar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class EnforcerDiagnosticsTest {
    @TempDir Path directory;

    @Test
    void backgroundConfigWarningGoesToTopBarNotStderr() throws Exception {
        Path config = EnforcerConfig.resolveConfigPath(directory);
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{invalid-json");
        TopBar topBar = new TopBar(new Object());
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        Runnable cleanup = EnforcerDiagnostics.installAlertSink(topBar::setAlert);
        try (PrintStream capture = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            assertNull(CompletableFuture.supplyAsync(() -> EnforcerConfig.load(directory)).get());
            assertTrue(topBar.getAlert().contains("[enforcer] warning: could not read"));
            assertEquals("", err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(previous);
            cleanup.run();
        }
    }

    @Test
    void cleanupRestoresPreviousSinkAndIsSafeOutOfOrder() {
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        Runnable firstCleanup = EnforcerDiagnostics.installAlertSink(first::add);
        Runnable secondCleanup = EnforcerDiagnostics.installAlertSink(second::add);
        try {
            EnforcerDiagnostics.alert("newest");
            assertEquals(List.of("newest"), second);
            assertTrue(first.isEmpty());
            secondCleanup.run();
            EnforcerDiagnostics.alert("restored");
            assertEquals(List.of("restored"), first);

            Runnable thirdCleanup = EnforcerDiagnostics.installAlertSink(second::add);
            try {
                firstCleanup.run();
                secondCleanup.run();
                EnforcerDiagnostics.alert("still newest");
                assertEquals(List.of("newest", "still newest"), second);
            } finally {
                thirdCleanup.run();
            }
        } finally {
            secondCleanup.run();
            firstCleanup.run();
        }
    }

    @Test
    void identicalSinkRegistrationsHaveIndependentCleanup() {
        List<String> alerts = new ArrayList<>();
        List<String> middle = new ArrayList<>();
        Consumer<String> shared = alerts::add;
        Runnable first = EnforcerDiagnostics.installAlertSink(shared);
        Runnable second = EnforcerDiagnostics.installAlertSink(middle::add);
        Runnable third = EnforcerDiagnostics.installAlertSink(shared);
        try {
            third.run();
            EnforcerDiagnostics.alert("middle");
            assertEquals(List.of("middle"), middle);
            assertTrue(alerts.isEmpty());
        } finally {
            third.run();
            second.run();
            first.run();
        }
    }

    @Test
    void headlessAndFailedSinksKeepStderrDiagnostics() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (PrintStream capture = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            EnforcerDiagnostics.alert(null);
            EnforcerDiagnostics.alert("  ");
            EnforcerDiagnostics.alert("headless warning");
            Runnable cleanup = EnforcerDiagnostics.installAlertSink(message -> {
                throw new IllegalStateException("closing");
            });
            try {
                assertDoesNotThrow(() -> EnforcerDiagnostics.alert("closing warning"));
            } finally {
                cleanup.run();
            }
            EnforcerDiagnostics.alert("after cleanup");
            assertEquals(String.join(System.lineSeparator(),
                    "headless warning", "closing warning", "after cleanup", ""),
                    err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(previous);
        }
    }
}
