/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main.project;

import ai.kompile.cli.main.MainCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@code kompile project stop}, {@code kompile project status}, and
 * {@code kompile project logs} are registered directly under the {@code project}
 * command group (no {@code service} qualifier needed), and that the {@code Logs}
 * command correctly discovers and tails log files.
 */
class ProjectServiceSubcommandRegistrationTest {

    // ── GAP 1: subcommand routing registration ────────────────────────────────

    @Test
    void projectCommand_registersStop_directlyUnderProject() {
        CommandLine cli = new CommandLine(new ProjectCommand());
        Map<String, CommandLine> subs = cli.getSubcommands();
        assertTrue(subs.containsKey("stop"),
                "'stop' must be a direct subcommand of 'project' — got: " + subs.keySet());
    }

    @Test
    void projectCommand_registersStatus_directlyUnderProject() {
        CommandLine cli = new CommandLine(new ProjectCommand());
        Map<String, CommandLine> subs = cli.getSubcommands();
        assertTrue(subs.containsKey("status"),
                "'status' must be a direct subcommand of 'project' — got: " + subs.keySet());
    }

    @Test
    void projectCommand_registersLogs_directlyUnderProject() {
        CommandLine cli = new CommandLine(new ProjectCommand());
        Map<String, CommandLine> subs = cli.getSubcommands();
        assertTrue(subs.containsKey("logs"),
                "'logs' must be a direct subcommand of 'project' — got: " + subs.keySet());
    }

    @Test
    void projectCommand_stopIsInstanceOfExpectedClass() {
        CommandLine cli = new CommandLine(new ProjectCommand());
        Object cmd = cli.getSubcommands().get("stop").getCommand();
        assertInstanceOf(ProjectServiceCommand.Stop.class, cmd,
                "'stop' subcommand must be ProjectServiceCommand.Stop");
    }

    @Test
    void projectCommand_statusIsInstanceOfExpectedClass() {
        CommandLine cli = new CommandLine(new ProjectCommand());
        Object cmd = cli.getSubcommands().get("status").getCommand();
        assertInstanceOf(ProjectServiceCommand.Status.class, cmd,
                "'status' subcommand must be ProjectServiceCommand.Status");
    }

    @Test
    void projectCommand_logsIsInstanceOfExpectedClass() {
        CommandLine cli = new CommandLine(new ProjectCommand());
        Object cmd = cli.getSubcommands().get("logs").getCommand();
        assertInstanceOf(ProjectServiceCommand.Logs.class, cmd,
                "'logs' subcommand must be ProjectServiceCommand.Logs");
    }

    // ── GAP 2: Logs command — log discovery and tail ──────────────────────────

    /**
     * When a temp subprocess logs dir has sample files, {@code discoverLogFiles} returns
     * exactly those files (sorted newest-first) and {@code tailFile} reads their content.
     */
    @Test
    void logsCommand_discoversSubprocessLogs_andTailsContent(@TempDir Path tmp) throws IOException {
        // Write two fake subprocess log files
        Path spDir = tmp.resolve("subprocesses");
        Files.createDirectories(spDir);

        Path embeddingLog = spDir.resolve("embedding.log");
        Path graphLog = spDir.resolve("graph-matrix.log");

        Files.write(embeddingLog, List.of("line1", "line2", "line3"));
        // Make graph-matrix slightly older by writing it first (mtime order not guaranteed
        // at ms granularity, so we use content to assert the tail, not the order)
        Files.write(graphLog, List.of("gm-line1", "gm-line2"));

        // Wire a Logs instance with injected base dir
        ProjectServiceCommand.Logs cmd = new ProjectServiceCommand.Logs();
        cmd.subprocessLogsBaseDir = spDir;

        // Use a dummy projectRoot that has no data/logs — only subprocess logs should appear
        Map<String, Path> discovered = cmd.discoverLogFiles(tmp);
        assertFalse(discovered.isEmpty(), "Should discover at least the two fake log files");
        assertTrue(discovered.containsValue(embeddingLog),
                "embedding.log must appear in discovered map; got: " + discovered);
        assertTrue(discovered.containsValue(graphLog),
                "graph-matrix.log must appear in discovered map; got: " + discovered);
    }

    /**
     * {@code tailFile} with n >= file length returns all lines; with n < file length
     * returns the last n.
     */
    @Test
    void logsCommand_tailFile_returnsLastNLines(@TempDir Path tmp) throws IOException {
        Path log = tmp.resolve("test.log");
        Files.write(log, List.of("a", "b", "c", "d", "e"));

        List<String> all = ProjectServiceCommand.Logs.tailFile(log, 10);
        assertEquals(List.of("a", "b", "c", "d", "e"), all,
                "When n >= file size, all lines must be returned");

        List<String> last2 = ProjectServiceCommand.Logs.tailFile(log, 2);
        assertEquals(List.of("d", "e"), last2,
                "Last 2 lines must be returned when n=2");

        List<String> last1 = ProjectServiceCommand.Logs.tailFile(log, 1);
        assertEquals(List.of("e"), last1, "Last 1 line must be returned when n=1");

        List<String> none = ProjectServiceCommand.Logs.tailFile(log, 0);
        assertTrue(none.isEmpty(), "n=0 must return no lines");
    }

    /**
     * When the subprocess log dir exists but is empty, and no project-local logs exist,
     * discoveredLogFiles should return an empty map (triggering the helpful "no logs" message).
     */
    @Test
    void logsCommand_emptySubprocessDir_returnsEmptyMap(@TempDir Path tmp) throws IOException {
        Path spDir = tmp.resolve("subprocesses");
        Files.createDirectories(spDir);

        ProjectServiceCommand.Logs cmd = new ProjectServiceCommand.Logs();
        cmd.subprocessLogsBaseDir = spDir;

        Map<String, Path> discovered = cmd.discoverLogFiles(tmp);
        assertTrue(discovered.isEmpty(),
                "With empty subprocess dir and no project logs, map must be empty");
    }

    /**
     * The --subprocess filter returns only the named log (when it exists) and an
     * empty map when the named log does not exist.
     */
    @Test
    void logsCommand_subprocessFilter_returnsOnlyNamedLog(@TempDir Path tmp) throws IOException {
        Path spDir = tmp.resolve("subprocesses");
        Files.createDirectories(spDir);
        Files.write(spDir.resolve("embedding.log"), List.of("embed-line"));
        Files.write(spDir.resolve("graph-matrix.log"), List.of("gm-line"));

        // Filter to 'embedding' — should return only embedding.log
        ProjectServiceCommand.Logs cmd = new ProjectServiceCommand.Logs();
        cmd.subprocessLogsBaseDir = spDir;
        cmd.subprocess = "embedding";

        Map<String, Path> discovered = cmd.discoverLogFiles(tmp);
        assertEquals(1, discovered.size(), "Filter to 'embedding' should return 1 file");
        assertTrue(discovered.containsKey("embedding.log"),
                "Key must be 'embedding.log'; got: " + discovered.keySet());

        // Filter to a non-existent subprocess — empty, not an exception
        PrintStream savedErr = System.err;
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBuf));
        try {
            ProjectServiceCommand.Logs cmd2 = new ProjectServiceCommand.Logs();
            cmd2.subprocessLogsBaseDir = spDir;
            cmd2.subprocess = "nonexistent";
            Map<String, Path> none = cmd2.discoverLogFiles(tmp);
            assertTrue(none.isEmpty(),
                    "Filter to nonexistent subprocess must return empty map");
        } finally {
            System.setErr(savedErr);
        }
    }

    @Test
    void logsCommand_discoversProjectServiceLogsByDefault(@TempDir Path tmp) throws IOException {
        Path spDir = tmp.resolve("subprocesses");
        Files.createDirectories(spDir);
        Path serviceDir = tmp.resolve("data/logs");
        Files.createDirectories(serviceDir);
        Path stagingErr = serviceDir.resolve("service-staging.err.log");
        Path appOut = serviceDir.resolve("planning-app.out.log");
        Files.write(stagingErr, List.of("staging line"));
        Files.write(appOut, List.of("app line"));

        ProjectServiceCommand.Logs cmd = new ProjectServiceCommand.Logs();
        cmd.subprocessLogsBaseDir = spDir;

        Map<String, Path> discovered = cmd.discoverLogFiles(tmp);
        assertTrue(discovered.containsKey("data/logs/service-staging.err.log"),
                "project staging stderr log must be discovered by default; got: " + discovered.keySet());
        assertTrue(discovered.containsKey("data/logs/planning-app.out.log"),
                "project app stdout log must be discovered by default; got: " + discovered.keySet());
    }

    @Test
    void logsCommand_scanDetectsStagingModelFailures(@TempDir Path tmp) throws IOException {
        Path log = tmp.resolve("staging.err.log");
        Files.write(log, List.of(
                "INFO model staging started",
                "Conversion failed: Conversion failed: Another variable with the name input_ids already exists.",
                "Download failed: No downloader available for source: local"));

        List<ProjectServiceCommand.Logs.LogSignal> findings =
                ProjectServiceCommand.Logs.scanLogFile("staging.err.log", log, 100);

        assertTrue(findings.stream().anyMatch(f -> f.id().equals("conversion-failed")),
                "conversion failure must be surfaced; got: " + findings);
        assertTrue(findings.stream().anyMatch(f -> f.id().equals("missing-downloader")),
                "missing downloader must be surfaced; got: " + findings);
    }

    @Test
    void logsCommand_scanDoesNotTreatFixedNanNarrationAsNonFiniteFailure(@TempDir Path tmp) throws IOException {
        Path log = tmp.resolve("embedding.log");
        Files.write(log, List.of(
                "INFO DSP_PHASE_SYNC: native plan phase=1 -- syncing Java shapesFrozen=true (preserves cast cache across plan swaps, fixes FP16 NaN after swap)",
                "INFO Model loaded successfully: bge-base-en-v1.5"));

        List<ProjectServiceCommand.Logs.LogSignal> findings =
                ProjectServiceCommand.Logs.scanLogFile("embedding.log", log, 100);

        assertTrue(findings.stream().noneMatch(f -> f.id().equals("non-finite")),
                "fixed-issue narration must not be reported as a live non-finite failure; got: " + findings);
    }

    @Test
    void logsCommand_monitorSelectionKeepsLatestSubprocessLogAndServiceLogs(@TempDir Path tmp) throws IOException {
        Path oldEmbedding = tmp.resolve("embedding-old.log");
        Path newEmbedding = tmp.resolve("embedding-new.log");
        Path serviceErr = tmp.resolve("service-staging.err.log");
        Path oldStagingOut = tmp.resolve("staging-server.out.log");
        Path newStagingOut = tmp.resolve("kompile-project-alpha-staging.out.log");
        Files.write(oldEmbedding, List.of("old"));
        Files.write(newEmbedding, List.of("new"));
        Files.write(serviceErr, List.of("service"));
        Files.write(oldStagingOut, List.of("old staging"));
        Files.write(newStagingOut, List.of("new staging"));
        Files.setLastModifiedTime(oldEmbedding, FileTime.fromMillis(1000));
        Files.setLastModifiedTime(newEmbedding, FileTime.fromMillis(2000));
        Files.setLastModifiedTime(serviceErr, FileTime.fromMillis(1500));
        Files.setLastModifiedTime(oldStagingOut, FileTime.fromMillis(1200));
        Files.setLastModifiedTime(newStagingOut, FileTime.fromMillis(2500));

        Map<String, Path> selected = ProjectServiceCommand.Logs.selectMonitorLogFiles(Map.of(
                "embedding/old.log", oldEmbedding,
                "embedding/new.log", newEmbedding,
                "data/logs/service-staging.err.log", serviceErr,
                "data/logs/staging-server.out.log", oldStagingOut,
                "data/logs/kompile-project-alpha-staging.out.log", newStagingOut));

        assertFalse(selected.containsKey("embedding/old.log"),
                "default monitor scan must not include stale historical subprocess logs");
        assertEquals(newEmbedding, selected.get("embedding/new.log"),
                "default monitor scan should keep the newest log for each subprocess type");
        assertEquals(serviceErr, selected.get("data/logs/service-staging.err.log"),
                "service stderr logs should remain selected independently");
        assertFalse(selected.containsKey("data/logs/staging-server.out.log"),
                "default monitor scan should prefer the latest staging stdout log over stale generic logs");
        assertEquals(newStagingOut, selected.get("data/logs/kompile-project-alpha-staging.out.log"),
                "default monitor scan should keep the newest staging stdout log");
    }

    @Test
    void logsCommand_scanIgnoresOptionalArtifactHttp404(@TempDir Path tmp) throws IOException {
        Path log = tmp.resolve("staging.out.log");
        Files.write(log, List.of("WARN Optional vocab file 'tokenizer.json' was not available for lfm2.5-1.2b-instruct: HTTP 404"));

        List<ProjectServiceCommand.Logs.LogSignal> findings =
                ProjectServiceCommand.Logs.scanLogFile("staging.out.log", log, 100);

        assertTrue(findings.stream().noneMatch(f -> f.id().equals("http-error")),
                "optional artifact HTTP 404 should not be reported as a critical HTTP failure; got: " + findings);
    }

    @Test
    void logsCommand_scanReturnsNonZeroForCriticalSignals(@TempDir Path tmp) throws IOException {
        Path log = tmp.resolve("staging.err.log");
        Files.write(log, List.of("ERROR Failed to load model bge-base-en-v1.5"));

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        System.setOut(new PrintStream(buf));
        int exit;
        try {
            exit = ProjectServiceCommand.Logs.scanLogs(Map.of("staging.err.log", log), 100);
        } finally {
            System.setOut(saved);
        }

        String out = buf.toString();
        assertEquals(2, exit, "critical log signals must return exit code 2; got output:\n" + out);
        assertTrue(out.contains("model-load-failed"), "scan output should name the model-load failure; got:\n" + out);
    }

    @Test
    void logsCommand_scanSurfacesZeroYieldAsWarning(@TempDir Path tmp) throws IOException {
        Path log = tmp.resolve("app.out.log");
        Files.write(log, List.of("yield 0 ent / 44020 chars (output-ceiling guard)"));

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        System.setOut(new PrintStream(buf));
        int exit;
        try {
            exit = ProjectServiceCommand.Logs.scanLogs(Map.of("app.out.log", log), 100);
        } finally {
            System.setOut(saved);
        }

        String out = buf.toString();
        assertEquals(1, exit, "warning-only log signals must return exit code 1; got output:\n" + out);
        assertTrue(out.contains("zero-yield"), "scan output should name zero-yield warnings; got:\n" + out);
    }

    // ── GAP 3: status prints something useful on an initialized project ───────

    /**
     * Running {@code kompile project status} via the top-level CLI on an initialized
     * project exits 0 and includes key fields in the output.
     */
    @Test
    void projectStatus_onInitializedProject_exitsZeroAndPrintsManifestInfo(@TempDir Path tmp)
            throws Exception {
        // Init a minimal project first
        int initExit = execute("project", "init",
                "--root", tmp.toString(), "--name", "status-test", "--backend", "local");
        assertEquals(0, initExit, "project init should succeed");

        // Run project status and capture output
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream saved = System.out;
        System.setOut(new PrintStream(buf));
        int statusExit;
        try {
            statusExit = execute("project", "status", "--root", tmp.toString());
        } finally {
            System.setOut(saved);
        }
        String out = buf.toString();

        assertEquals(0, statusExit, "project status on an initialized project should exit 0; got output:\n" + out);
        assertTrue(out.contains("status-test"),
                "Output should contain project name 'status-test'; got:\n" + out);
        assertTrue(out.contains("Services:"),
                "Output should contain 'Services:' section from enhanced status; got:\n" + out);
        assertTrue(out.contains("Subprocess logs:"),
                "Output should contain 'Subprocess logs:' section; got:\n" + out);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int execute(String... args) {
        CommandLine commandLine = new CommandLine(new MainCommand());
        commandLine.setOut(new PrintWriter(new StringWriter()));
        commandLine.setErr(new PrintWriter(new StringWriter()));
        return commandLine.execute(args);
    }
}
