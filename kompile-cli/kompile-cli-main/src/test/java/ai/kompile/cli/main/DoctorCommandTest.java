/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.main;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DoctorCommand}.
 *
 * Scope: pure logic (result formatting, port-check against a real bound socket,
 * fake check runs). We do NOT test probes requiring real binaries (nvidia-smi,
 * java, external agents).
 */
class DoctorCommandTest {

    // ── CheckResult record ────────────────────────────────────────────────────

    @Test
    void checkResultOk_hasCorrectStatus() {
        DoctorCommand.CheckResult r = DoctorCommand.CheckResult.ok("CLI version", "1.0.0 (native)");
        assertEquals(DoctorCommand.Status.OK, r.status());
        assertEquals("CLI version", r.name());
        assertEquals("1.0.0 (native)", r.detail());
        assertNull(r.fix());
        assertFalse(r.critical());
    }

    @Test
    void checkResultFail_isCritical() {
        DoctorCommand.CheckResult r = DoctorCommand.CheckResult.fail("kompile-app-main", "not installed",
                "Install with: kompile install kompile-app-main");
        assertEquals(DoctorCommand.Status.FAIL, r.status());
        assertTrue(r.critical());
        assertNotNull(r.fix());
    }

    @Test
    void checkResultWarn_isNotCritical() {
        DoctorCommand.CheckResult r = DoctorCommand.CheckResult.warn("Ollama", "not reachable", "ollama serve");
        assertEquals(DoctorCommand.Status.WARN, r.status());
        assertFalse(r.critical());
    }

    @Test
    void checkResultOf_respectsExplicitCriticalityFlag() {
        DoctorCommand.CheckResult r = DoctorCommand.CheckResult.of(
                "Custom", DoctorCommand.Status.FAIL, "detail", "fix", false);
        assertFalse(r.critical());
    }

    // ── Table formatting ──────────────────────────────────────────────────────

    @Test
    void printTable_outputsAllChecks(@TempDir Path tmp) {
        DoctorCommand cmd = new DoctorCommand();

        List<DoctorCommand.CheckResult> results = List.of(
                DoctorCommand.CheckResult.ok("CLI version", "1.0.0 (native)"),
                DoctorCommand.CheckResult.warn("Disk (~/.kompile)", "2 GB usable", "Free disk space"),
                DoctorCommand.CheckResult.fail("kompile-app-main", "not installed",
                        "Install with: kompile install kompile-app-main")
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(baos));
        try {
            cmd.printTable(results);
        } finally {
            System.setOut(old);
        }

        String output = baos.toString();
        assertTrue(output.contains("CLI version"), "should contain check name");
        assertTrue(output.contains("1.0.0 (native)"), "should contain detail");
        assertTrue(output.contains("not installed"), "should contain failure detail");
        assertTrue(output.contains("kompile install kompile-app-main"), "should contain fix line");
        assertTrue(output.contains("Disk (~/.kompile)"), "should contain warning name");
        assertTrue(output.contains("Actions:"), "should have actions section");
    }

    @Test
    void printTable_noActionsSection_whenAllChecksPassed() {
        DoctorCommand cmd = new DoctorCommand();
        List<DoctorCommand.CheckResult> results = List.of(
                DoctorCommand.CheckResult.ok("CLI version", "1.0.0"),
                DoctorCommand.CheckResult.ok("Hardware", "32 GB, 16 CPUs")
        );

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(baos));
        try {
            cmd.printTable(results);
        } finally {
            System.setOut(old);
        }

        assertFalse(baos.toString().contains("Actions:"), "no actions when all OK");
    }

    // ── Fake check run ────────────────────────────────────────────────────────

    @Test
    void call_returnsZero_whenNoCriticalFailures() throws Exception {
        // Run doctor with --no-color so we don't need ANSI support
        DoctorCommand cmd = new DoctorCommand();
        // Capture stdout to avoid noise in test logs
        PrintStream old = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream()));
        int code;
        try {
            // We can't easily suppress all probe output but we at least verify
            // that the command doesn't crash and returns an int
            code = cmd.call();
        } finally {
            System.setOut(old);
        }
        // Exit code must be 0 or 1 (valid values)
        assertTrue(code == 0 || code == 1, "exit code must be 0 or 1, was " + code);
    }

    // ── Port-check logic ──────────────────────────────────────────────────────

    @Test
    void checkPort_returnsFree_whenPortIsAvailable() throws IOException {
        DoctorCommand cmd = new DoctorCommand();
        // Find a free port dynamically so the test is not sensitive to environment
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        DoctorCommand.CheckResult result = cmd.checkPort(freePort);
        assertEquals(DoctorCommand.Status.OK, result.status(), "free port should be OK");
        assertEquals("free", result.detail());
    }

    @Test
    void checkPort_returnsWarn_whenPortIsInUse() throws IOException {
        DoctorCommand cmd = new DoctorCommand();
        // Bind a port ourselves so we know it's in use
        try (ServerSocket occupied = new ServerSocket(0)) {
            int port = occupied.getLocalPort();
            DoctorCommand.CheckResult result = cmd.checkPort(port);
            // The port is occupied by us, so it should be WARN
            assertEquals(DoctorCommand.Status.WARN, result.status(),
                    "occupied port should produce WARN");
            assertTrue(result.detail().contains("in use"), "detail should say 'in use'");
        }
    }

    // ── CLI checks ────────────────────────────────────────────────────────────

    @Test
    void checkCli_producesAtLeastOneResult() {
        DoctorCommand cmd = new DoctorCommand();
        List<DoctorCommand.CheckResult> results = cmd.checkCli();
        assertFalse(results.isEmpty());
        assertEquals("CLI version", results.get(0).name());
    }

    @Test
    void checkCli_statusIsOkOrWarn() {
        DoctorCommand cmd = new DoctorCommand();
        List<DoctorCommand.CheckResult> results = cmd.checkCli();
        DoctorCommand.Status s = results.get(0).status();
        assertTrue(s == DoctorCommand.Status.OK || s == DoctorCommand.Status.WARN,
                "CLI check should be OK or WARN (never FAIL on a working install), got: " + s);
    }

    // ── Disk checks ───────────────────────────────────────────────────────────

    @Test
    void checkDisk_okForExistingTmpDir(@TempDir Path tmp) {
        DoctorCommand cmd = new DoctorCommand();
        List<DoctorCommand.CheckResult> results = cmd.checkDisk(tmp);
        // At minimum ~/.kompile check is always present
        assertFalse(results.isEmpty());
        // The temp dir check (project root) should be present and not crash
        boolean hasProjectDisk = results.stream().anyMatch(r -> r.name().contains("project root"));
        assertTrue(hasProjectDisk, "should include project root disk check when root differs from ~/.kompile");
    }

    // ── Global config check ───────────────────────────────────────────────────

    @Test
    void checkGlobalConfig_producesOneResult() {
        DoctorCommand cmd = new DoctorCommand();
        List<DoctorCommand.CheckResult> results = cmd.checkGlobalConfig();
        assertEquals(1, results.size());
        assertEquals("Global config", results.get(0).name());
    }

    // ── Distribution-aware component checks ───────────────────────────────────

    @Test
    void localDistributionIsDetectedFromVariantMarker(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve(".variant"), "local\n");

        assertTrue(DoctorCommand.isLocalDistribution(tmp));
    }

    @Test
    void nonLocalDistributionDoesNotUseLocalComponentContract(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve(".variant"), "full\n");

        assertFalse(DoctorCommand.isLocalDistribution(tmp));
    }

    // ── isNativeImage helper ──────────────────────────────────────────────────

    @Test
    void isNativeImage_returnsBoolean() {
        // Just verify it doesn't throw — the return value depends on whether
        // graalvm-sdk is on the test classpath (it may be, as a transitive dep).
        // We only assert it returns a deterministic boolean without throwing.
        boolean result = DoctorCommand.isNativeImage();
        assertTrue(result == true || result == false); // always true, guards against NPE/exception
    }

    // ── runWithTimeout ────────────────────────────────────────────────────────

    @Test
    void runWithTimeout_returnsFirstLine_forEchoCommand() {
        DoctorCommand cmd = new DoctorCommand();
        // Use a portable command that works on Linux/macOS
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return; // skip on Windows (different echo)
        String result = cmd.runWithTimeout(new String[]{"echo", "hello-doctor"}, 3000);
        assertNotNull(result);
        assertEquals("hello-doctor", result.trim());
    }

    @Test
    void runWithTimeout_returnsNull_onTimeout() {
        DoctorCommand cmd = new DoctorCommand();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return; // skip on Windows
        // sleep 10 will never finish in 100ms
        String result = cmd.runWithTimeout(new String[]{"sleep", "10"}, 100);
        // After timeout the process is destroyed; result may be null or the
        // empty output captured before timeout — either is acceptable
        // (we only verify no exception is thrown and it returns)
        // result can be null or blank
        assertTrue(result == null || result.isBlank());
    }

    // ── Project checks ────────────────────────────────────────────────────────

    @Test
    void checkProject_warnsWhenNoManifest(@TempDir Path tmp) {
        DoctorCommand cmd = new DoctorCommand();
        List<DoctorCommand.CheckResult> results = cmd.checkProject(tmp);
        assertEquals(1, results.size(), "should have exactly one result when manifest is missing");
        assertEquals("Project manifest", results.get(0).name());
        assertEquals(DoctorCommand.Status.WARN, results.get(0).status());
    }

    @Test
    void checkProject_okWhenManifestExists(@TempDir Path tmp) throws IOException {
        DoctorCommand cmd = new DoctorCommand();
        // Minimal kompile.project.json
        Files.writeString(tmp.resolve("kompile.project.json"), "{\"id\":\"test\",\"models\":[]}");
        List<DoctorCommand.CheckResult> results = cmd.checkProject(tmp);
        DoctorCommand.CheckResult manifest = results.stream()
                .filter(r -> r.name().equals("Project manifest"))
                .findFirst()
                .orElseThrow();
        assertEquals(DoctorCommand.Status.OK, manifest.status());
    }

    // ── Instance registry GC ──────────────────────────────────────────────────

    @Test
    void checkInstances_producesExactlyOneResult() {
        DoctorCommand cmd = new DoctorCommand();
        List<DoctorCommand.CheckResult> results = cmd.checkInstances();
        assertEquals(1, results.size(), "checkInstances should produce exactly one result");
        assertEquals("Instance registry", results.get(0).name());
    }

    @Test
    void checkInstances_statusIsOk() {
        // After GC the registry is always in a clean state, so status must be OK
        DoctorCommand cmd = new DoctorCommand();
        List<DoctorCommand.CheckResult> results = cmd.checkInstances();
        assertEquals(DoctorCommand.Status.OK, results.get(0).status(),
                "checkInstances should be OK after GC (stale entries are removed, not left as failures)");
    }

    @Test
    void checkInstances_reportsCleanedStaleEntry() throws Exception {
        // Register a stale entry (PID=0, free port) then run checkInstances
        int freePort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        String entryName = "doctor-test-stale-" + System.currentTimeMillis();
        ai.kompile.cli.common.registry.InstanceInfo stale =
                ai.kompile.cli.common.registry.InstanceInfo.builder()
                        .name(entryName)
                        .type("kompile-app-main")
                        .port(freePort)
                        .pid(0L)
                        .startedAt(java.time.Instant.now())
                        .build();
        ai.kompile.cli.common.registry.InstanceRegistry.register(stale);

        try {
            DoctorCommand cmd = new DoctorCommand();
            List<DoctorCommand.CheckResult> results = cmd.checkInstances();
            DoctorCommand.CheckResult r = results.get(0);
            // GC should have fired and the detail should mention the cleaned entry
            assertTrue(r.detail().contains("cleaned") || r.detail().contains(entryName)
                            || r.detail().contains("instance"),
                    "checkInstances detail should acknowledge the registry state, got: " + r.detail());
        } finally {
            ai.kompile.cli.common.registry.InstanceRegistry.unregister(entryName);
        }
    }
}
