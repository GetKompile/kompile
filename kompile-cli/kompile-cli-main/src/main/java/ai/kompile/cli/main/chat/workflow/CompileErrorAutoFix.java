/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects compilation failures in build-tool output captured by shell and
 * background-process tools and renders the harness's automated recovery
 * directive.
 *
 * <p>Agents that hit a compiler error regularly stop and ask the user what to
 * do, or "fix" the error by deleting a file or reverting a change. The directive
 * this class renders does two things: it names the failed files with their first
 * error line so the next model step starts from evidence instead of re-running
 * the build blind, and it states the contract the harness holds the agent to —
 * the error was introduced by the work in this session, so repair the code in
 * context, in keeping with the domain; never delete the file, never undo the
 * change. When other agents hold edit locks on the failing files, the directive
 * says to coordinate through {@code edit_coordinator} instead of editing blind;
 * when none do, the harness is expected to continue the turn itself
 * (auto-fix mode) rather than asking the user.</p>
 *
 * <p>Detection is deliberately conservative: only failure-family exit codes,
 * only output that also matches a known compiler diagnostic, and never on
 * output that merely mentions the word "error" in passing.</p>
 */
public final class CompileErrorAutoFix {

    /** A compilation failure observed in one tool execution. */
    public record Finding(List<String> files, List<String> diagnostics, String toolchain) {
        public boolean isEmpty() { return files.isEmpty() && diagnostics.isEmpty(); }
    }

    private static final int MAX_FILES = 8;
    private static final int MAX_DIAGNOSTICS = 10;
    private static final int MAX_LINE = 400;

    /** Command prefixes whose nonzero exit means a compile attempt failed. */
    private static final Pattern BUILD_LAUNCH = Pattern.compile(
            "(?:^|[;&|(]|\\b(?:then|do|else)\\s+)\\s*(?:sudo\\s+|env\\s+\\S+=\\S+\\s+|npx\\s+|yarn\\s+)?"
                    + "(mvn|mvnw|gradle|gradlew|javac|kotlinc|kotlinc-jvm|scala|scalac|sbt|"
                    + "gcc|g\\+\\+|cc|clang|clang\\+\\+|nvcc|make|cmake|ninja|"
                    + "cargo|rustc|go|tsc|babel|esbuild|rollup|webpack)"
                    + "(?:\\.cmd|\\.exe|\\.bat)?\\b");

    /** Per-toolchain compile diagnostics that name a file. */
    private static final Pattern[] FILE_DIAGNOSTICS = {
            // path:line:col: severity: message  (javac/kotlinc/gcc/clang/tsc/go/rustc)
            Pattern.compile("^\\s*([^:\\s][^:]*\\.[A-Za-z][A-Za-z0-9_]{0,11}):(\\d+)(?::\\d+)?:"
                    + "\\s*(?:error|fatal error|warning)\\s*:\\s*(.{1,300})"),
            // [ERROR] /path/File.java:[12,34] message   (Maven)
            Pattern.compile("^\\s*\\[(?:ERROR|error)]\\s*(\\S+\\.\\w{1,12}):\\[(\\d+),(\\d+)]\\s*(.{1,300})"),
            // e: file.kt:12:34 message   (Kotlin)
            Pattern.compile("^\\s*[ew]:\\s+file:(\\S+):\\s*(\\d+):(\\d+)\\s*(.{1,300})"),
            // error[E0308]: message / --> src/main.rs:12:34   (Rust)
            Pattern.compile("^\\s*-->\\s+(\\S+):(\\d+):(\\d+)\\s*$"),
            // File "x.py", line 12  / SyntaxError style fallbacks stay out: not compilation.
    };

    private static final Pattern FILE_COLON_LINE = Pattern.compile(
            "^(.*\\.[A-Za-z][A-Za-z0-9_]{0,11}):(\\d+):.*\\b(?:error|expected|cannot find symbol|"
                    + "illegal|incompatible|unresolved|no such|does not exist|not a member|"
                    + "unreachable|already defined|abstract|missing)\\b.*",
            Pattern.CASE_INSENSITIVE);

    /** Messages that prove a failure is a compilation failure without naming a line. */
    private static final Pattern COMPILE_SUMMARY = Pattern.compile(
            "\\b(?:COMPILATION ERROR|BUILD FAILURE|compilation failed|compile error|"
                    + "[0-9]+ errors?\\b.*\\b(?:compil|build)|error: could not compile|"
                    + "error TS[0-9]+|Unresolved reference|cannot find symbol)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ANSI = Pattern.compile(
            "\\x1B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\x07]*(?:\\x07|\\x1B\\\\))");

    private CompileErrorAutoFix() { }

    /**
     * Detects a compilation failure in one tool execution. A finding requires
     * BOTH a build-tool launch in the command and compile diagnostics in the
     * output; a red exit code alone never triggers it, and neither does output
     * from a non-build command that happens to contain error text.
     *
     * @param command  the shell command that ran (used to confirm build intent)
     * @param exitCode the process exit code
     * @param output   the captured combined output; ANSI escapes are stripped here
     */
    public static Finding detect(String command, int exitCode, String output) {
        if (command == null || command.isBlank()
                || output == null || output.isBlank()
                || exitCode == 0 || exitCode > 127 || exitCode < 0) {
            return EMPTY;
        }
        String clean = ANSI.matcher(output).replaceAll("");
        // Exit codes in the 1..127 band are the failure families every listed
        // toolchain uses for compile errors; 128+ are signals (OOM, KILL).
        if (!BUILD_LAUNCH.matcher(command).find()) return EMPTY;
        if (!COMPILE_SUMMARY.matcher(clean).find()) {
            boolean sawDiagnostic = false;
            for (String line : lines(clean)) {
                if (matchesDiagnostic(line) != null) { sawDiagnostic = true; break; }
            }
            if (!sawDiagnostic) return EMPTY;
        }
        return collect(clean);
    }

    private static final Finding EMPTY = new Finding(List.of(), List.of(), null);

    /** Renders the automated recovery directive sent to the agent. */
    public static String message(Finding finding, List<String> foreignLocks, boolean autoFix) {
        StringBuilder out = new StringBuilder(1024);
        out.append("[harness auto-detection] COMPILATION ERROR — do not stop and ask the user what to do.\n\n");
        out.append("A build you ran failed to compile");
        if (finding != null && finding.toolchain() != null) {
            out.append(" (").append(finding.toolchain()).append(")");
        }
        out.append(". This is YOUR task to complete: the error came from the changes made ");
        out.append("in this session, and fixing it is part of the request you were given.\n\n");

        out.append("HOW TO FIX — the fix must be a CORRECTION IN CONTEXT:\n");
        out.append("1. Read the exact failing locations listed below, then repair the code so it ");
        out.append("compiles AND keeps the domain's intent: resolve types, signatures, imports ");
        out.append("and API usage as the surrounding code intends. When the change you made ");
        out.append("conflicts with a caller, update the caller to the new contract rather than ");
        out.append("deleting it.\n");
        out.append("2. NEVER remove a file, comment it out, delete a test, or revert/undo the ");
        out.append("change to make the error disappear. An empty compile is not a fix. If you ");
        out.append("genuinely believe the removal is the domain-correct fix, finish it and ");
        out.append("state the domain reason in your summary — removal without that reasoning is ");
        out.append("treated as a failure.\n");
        out.append("3. Re-run the same build command after repairing, and continue your task. ");
        out.append("Do not end your turn while the build is red.\n\n");

        if (finding != null && !finding.isEmpty()) {
            out.append("Detected failures:\n");
            int shown = 0;
            for (String diagnostic : finding.diagnostics()) {
                if (shown >= MAX_DIAGNOSTICS) { out.append("  … more diagnostics omitted\n"); break; }
                out.append("  - ").append(diagnostic).append('\n');
                shown++;
            }
            for (String file : finding.files()) {
                if (shown >= MAX_DIAGNOSTICS) { out.append("  … more files omitted\n"); break; }
                out.append("  - ").append(file).append('\n');
                shown++;
            }
            out.append('\n');
        }

        if (foreignLocks != null && !foreignLocks.isEmpty()) {
            out.append("COORDINATION REQUIRED: other agents hold edit locks right now:\n");
            for (String lock : foreignLocks) out.append("  - ").append(lock).append('\n');
            out.append("Do NOT edit those files blind. Before touching a locked file, call ");
            out.append("edit_coordinator with action='query_edits' and either wait, message the ");
            out.append("holder with action='send_message', or register_edit only once the lock is ");
            out.append("released. Editing without coordination duplicates work and can revert ");
            out.append("another agent's in-flight fix.\n");
        } else if (autoFix) {
            out.append("No other agent is editing right now, so the harness is keeping this turn ");
            out.append("open: repair the failing code now, in this turn. You are not blocked and ");
            out.append("no user input is needed.\n");
        }
        return out.toString();
    }

    private static Finding collect(String output) {
        Map<String, Boolean> files = new LinkedHashMap<>();
        List<String> diagnostics = new ArrayList<>();
        String toolchain = null;
        for (String line : lines(output)) {
            String diagnostic = matchesDiagnostic(line);
            if (diagnostic == null) continue;
            if (diagnostics.size() < MAX_DIAGNOSTICS) diagnostics.add(diagnostic);
            String file = fileOf(diagnostic);
            if (file != null) files.putIfAbsent(file, Boolean.TRUE);
            if (toolchain == null) {
                toolchain = toolchainOf(diagnostic);
            }
        }
        List<String> fileList = new ArrayList<>(files.keySet());
        if (fileList.size() > MAX_FILES) fileList = fileList.subList(0, MAX_FILES);
        return new Finding(List.copyOf(fileList), List.copyOf(diagnostics), toolchain);
    }

    /** Returns a rendered one-line diagnostic when the line is a compile diagnostic, else null. */
    private static String matchesDiagnostic(String line) {
        if (line.length() > MAX_LINE * 2 || line.isEmpty()) return null;
        for (Pattern pattern : FILE_DIAGNOSTICS) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                String file = matcher.group(1);
                String position = matcher.group(2) == null ? ""
                        : ":" + matcher.group(2)
                        + (matcher.group(3) == null ? "" : ":" + matcher.group(3));
                String message = matcher.group(matcher.groupCount());
                return trim(file) + position + " — " + trim(message);
            }
        }
        Matcher generic = FILE_COLON_LINE.matcher(line);
        if (generic.matches()) return trim(generic.group(1)) + ":" + generic.group(2)
                + " — " + trim(line.substring(Math.min(line.length(), MAX_LINE)));
        return null;
    }

    private static String fileOf(String renderedDiagnostic) {
        int end = renderedDiagnostic.indexOf(" — ");
        String candidate = end > 0 ? renderedDiagnostic.substring(0, end) : renderedDiagnostic;
        int colon = candidate.indexOf(':');
        return colon > 0 ? candidate.substring(0, colon) : candidate;
    }

    private static String toolchainOf(String renderedDiagnostic) {
        String lower = renderedDiagnostic.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".java")) return "javac/maven";
        if (lower.endsWith(".kt") || lower.endsWith(".kts")) return "kotlinc";
        if (lower.endsWith(".rs")) return "rustc/cargo";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "tsc";
        if (lower.endsWith(".c") || lower.endsWith(".h")
                || lower.endsWith(".cpp") || lower.endsWith(".cc") || lower.endsWith(".hpp")) {
            return "c/c++ compiler";
        }
        if (lower.endsWith(".go")) return "go build";
        return null;
    }

    private static List<String> lines(String output) {
        List<String> out = new ArrayList<>();
        for (String part : output.split("[\\r\\n]+")) {
            if (!part.isBlank()) out.add(part.strip());
        }
        return out;
    }

    private static String trim(String value) {
        String trimmed = value.strip();
        return trimmed.length() > MAX_LINE ? trimmed.substring(0, MAX_LINE) + "…" : trimmed;
    }
}
