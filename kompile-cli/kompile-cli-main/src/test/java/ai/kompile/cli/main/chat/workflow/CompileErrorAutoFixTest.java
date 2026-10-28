/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.workflow;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the compile-error auto-fix detector and the wording contract of its
 * automated directive: the message must demand an in-context, domain-correct
 * repair and must forbid deletion/revert-style "fixes".
 */
class CompileErrorAutoFixTest {

    private static final String MAVEN_FAIL = """
            [INFO] Scanning for projects...
            [INFO] BUILD FAILURE
            [INFO] Total time:  2.113 s
            [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:compile (default-compile) on project demo: Compilation failure: Compilation failure:
            [ERROR] /home/dev/kompile/demo/src/main/java/ai/kompile/demo/Printer.java:[42,18] cannot find symbol
              symbol:   variable out
              location: class ai.kompile.demo.Printer
            [ERROR] /home/dev/kompile/demo/src/main/java/ai/kompile/demo/Printer.java:[42,26] incompatible types: int cannot be converted to String
            [INFO] 2 errors
            """;

    private static final String JAVAC_FAIL = """
            Printer.java:42: error: cannot find symbol
              symbol: variable out
              location: class Printer
            1 error
            error: compilation failed
            """;

    private static final String RUST_FAIL = """
            error[E0308]: mismatched types
             --> src/main.rs:12:34
              |
            12 |     let x: String = 42;
              |                     ^^ expected `String`, found integer
            error: could not compile `demo` (bin "demo") due to 1 previous error
            """;

    private static Object detect(String command, int exitCode, String output) throws Exception {
        Method detect = CompileErrorAutoFix.class.getDeclaredMethod(
                "detect", String.class, int.class, String.class);
        detect.setAccessible(true);
        return detect.invoke(null, command, exitCode, output);
    }

    private static boolean isEmpty(Object finding) throws Exception {
        return (boolean) finding.getClass().getMethod("isEmpty").invoke(finding);
    }

    @SuppressWarnings("unchecked")
    private static List<String> files(Object finding) throws Exception {
        return (List<String>) finding.getClass().getMethod("files").invoke(finding);
    }

    @SuppressWarnings("unchecked")
    private static List<String> diagnostics(Object finding) throws Exception {
        return (List<String>) finding.getClass().getMethod("diagnostics").invoke(finding);
    }

    private static String message(Object finding, List<String> locks, boolean autoFix) throws Exception {
        Method render = CompileErrorAutoFix.class.getDeclaredMethod(
                "message", finding.getClass(), List.class, boolean.class);
        render.setAccessible(true);
        return (String) render.invoke(null, finding, locks, autoFix);
    }

    @Test
    void detectsMavenCompilationFailure() throws Exception {
        Object finding = detect("mvn -pl :demo compile", 1, MAVEN_FAIL);
        assertFalse(isEmpty(finding));
        assertTrue(files(finding).stream().anyMatch(f -> f.endsWith("Printer.java")));
        assertEquals(2, diagnostics(finding).size());
    }

    @Test
    void detectsPlainJavacFailure() throws Exception {
        Object finding = detect("javac Printer.java", 1, JAVAC_FAIL);
        assertFalse(isEmpty(finding));
        assertEquals(1, diagnostics(finding).size());
        assertTrue(diagnostics(finding).get(0).contains("cannot find symbol"));
    }

    @Test
    void detectsRustArrowDiagnostics() throws Exception {
        Object finding = detect("cargo build --release", 101, RUST_FAIL);
        assertFalse(isEmpty(finding));
        assertTrue(files(finding).stream().anyMatch(f -> f.endsWith("main.rs")));
    }

    @Test
    void ignoresSuccessfulBuildEvenWithMatchingText() throws Exception {
        Object finding = detect("mvn -q compile", 0,
                "[ERROR] cannot find symbol\n");
        assertTrue(isEmpty(finding));
    }

    @Test
    void ignoresNonBuildCommandWithRedExit() throws Exception {
        Object finding = detect("grep -n 'cannot find symbol' notes.md", 1,
                "notes.md:3: cannot find symbol");
        assertTrue(isEmpty(finding));
    }

    @Test
    void ignoresBuildCommandWithNonCompileFailure() throws Exception {
        // A failing test run is a test failure, not a compilation failure:
        // the compile phase passed, so the auto-fix gate must stay out.
        Object finding = detect("mvn test", 1, """
                [INFO] Running ai.kompile.demo.DemoTest
                [ERROR] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
                [INFO] BUILD FAILURE
                [ERROR] There are test failures.
                """);
        assertTrue(isEmpty(finding));
    }

    @Test
    void ignoresSignalExits() throws Exception {
        Object finding = detect("mvn compile", 137, JAVAC_FAIL);
        assertTrue(isEmpty(finding));
    }

    @Test
    void stripsAnsiEscapesBeforeMatching() throws Exception {
        String ansi = "\u001B[1;31m[ERROR]\u001B[0m /p/A.java:[9,1] cannot find symbol";
        Object finding = detect("mvn compile", 1, ansi);
        assertFalse(isEmpty(finding));
        assertEquals(1, diagnostics(finding).size());
    }

    @Test
    void messageDemandsInContextDomainRepair() throws Exception {
        String text = message(detect("mvn compile", 1, MAVEN_FAIL), List.of(), true);
        assertTrue(text.contains("CORRECTION IN CONTEXT"));
        assertTrue(text.contains("intent"));
        assertTrue(text.toLowerCase().contains("domain"));
        assertTrue(text.contains("update the caller to the new contract"));
    }

    @Test
    void messageForbidsDeletionRevertAndUndo() throws Exception {
        String text = message(detect("mvn compile", 1, MAVEN_FAIL), List.of(), true);
        assertTrue(text.contains("NEVER remove a file"));
        assertTrue(text.contains("delete a test"));
        assertTrue(text.contains("revert/undo"));
        assertTrue(text.contains("An empty compile is not a fix"));
    }

    @Test
    void messageTellsAgentNotToStopAndAsk() throws Exception {
        String text = message(detect("mvn compile", 1, MAVEN_FAIL), List.of(), true);
        assertTrue(text.contains("do not stop and ask the user"));
        assertTrue(text.contains("no user input is needed"));
    }

    @Test
    void autoFixVariantKeepsTurnOpen() throws Exception {
        String text = message(detect("mvn compile", 1, MAVEN_FAIL), List.of(), true);
        assertTrue(text.contains("keeping this turn open") || text.contains("repair the failing code now"));
    }

    @Test
    void coordinationVariantNamesLocksAndForbidsBlindEdits() throws Exception {
        Object finding = detect("mvn compile", 1, MAVEN_FAIL);
        String text = message(finding, List.of("refactor-agent → /p/A.java"), false);
        assertTrue(text.contains("COORDINATION REQUIRED"));
        assertTrue(text.contains("refactor-agent → /p/A.java"));
        assertTrue(text.contains("edit_coordinator"));
        assertTrue(text.contains("Do NOT edit those files blind"));
    }

    @Test
    void messageCarriesDetectedFileAndDiagnostics() throws Exception {
        String text = message(detect("mvn compile", 1, MAVEN_FAIL), List.of(), true);
        // Maven's [42,18] positional syntax is normalized to file:line:col.
        assertTrue(text.contains("Printer.java:42:18"));
        assertTrue(text.contains("cannot find symbol"));
        assertTrue(text.contains("Detected failures:"));
    }
}
