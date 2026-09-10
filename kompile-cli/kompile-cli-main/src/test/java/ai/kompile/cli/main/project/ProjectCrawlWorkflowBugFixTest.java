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
import ai.kompile.project.KompileProjectCrawlProfile;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectStore;
import ai.kompile.project.KompileProjectWorkflow;
import ai.kompile.project.KompileProjectWorkflowStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for two bugs in the auto-ingest crawl workflow:
 *
 * <ol>
 *   <li>HEALTH_CHECK step hitting /actuator/health (404 on generated apps) instead of using
 *       KompileHttpClient.isHealthy() which also probes /api/setup/status.</li>
 *   <li>CRAWL step with null ref causing "Crawl null: missing profile" / IllegalArgumentException
 *       instead of falling back to the first available crawl profile.</li>
 * </ol>
 */
class ProjectCrawlWorkflowBugFixTest {

    // ── Bug 1: HEALTH_CHECK probe selection ───────────────────────────────────

    /**
     * When the HEALTH_CHECK step has no explicit URL, the runner should use the robust
     * kompile readiness probe (not a direct /actuator/health hit). In dry-run mode the
     * step exits 0 and prints the probe target.
     */
    @Test
    void healthCheckStep_nullUrl_useKompileReadinessProbe_dryRunExitsZero(@TempDir Path tmp) throws Exception {
        KompileProjectManifest manifest = manifestWithAutoIngestProfile(tmp);
        KompileProjectWorkflow workflow = workflowWithSteps(
                healthCheckStep(null),          // null URL → must use isHealthy()
                crawlStep("auto-ingest")
        );
        manifest.getWorkflows().add(workflow);

        PrintStream saved = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            int exit = ProjectCrawlCommand.runWorkflow(
                    new KompileProjectStore(), manifest, workflow, tmp,
                    "http://localhost:8080", null, /*dryRun=*/true);
            assertEquals(0, exit, "Dry-run of workflow with null-URL HEALTH_CHECK step should exit 0");
        } finally {
            System.setOut(saved);
        }
        String out = buf.toString();
        assertTrue(out.contains("kompile readiness probe"),
                "Dry-run output should identify this as a kompile readiness probe, not a raw /actuator/health GET. Got: " + out);
        assertFalse(out.contains("/actuator/health"),
                "Dry-run output should NOT contain a direct /actuator/health URL (would 404 on generated apps). Got: " + out);
    }

    /**
     * When the HEALTH_CHECK step has the legacy hard-coded URL "${appUrl}/actuator/health"
     * (from old generated manifests), the runner must detect this template and switch to
     * the kompile readiness probe rather than doing a single GET to /actuator/health.
     */
    @Test
    void healthCheckStep_legacyActuatorUrl_useKompileReadinessProbe_dryRunExitsZero(@TempDir Path tmp) throws Exception {
        KompileProjectManifest manifest = manifestWithAutoIngestProfile(tmp);
        KompileProjectWorkflow workflow = workflowWithSteps(
                healthCheckStep("${appUrl}/actuator/health"),  // legacy default from old generator
                crawlStep("auto-ingest")
        );
        manifest.getWorkflows().add(workflow);

        PrintStream saved = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            int exit = ProjectCrawlCommand.runWorkflow(
                    new KompileProjectStore(), manifest, workflow, tmp,
                    "http://localhost:8080", null, /*dryRun=*/true);
            assertEquals(0, exit, "Dry-run of workflow with legacy actuator URL should exit 0");
        } finally {
            System.setOut(saved);
        }
        String out = buf.toString();
        assertTrue(out.contains("kompile readiness probe"),
                "Legacy '${appUrl}/actuator/health' URL must be upgraded to kompile readiness probe. Got: " + out);
    }

    /**
     * An explicit, non-default URL (e.g. a separate staging server) must NOT be replaced
     * by the kompile probe — it should be passed through as-is.
     */
    @Test
    void healthCheckStep_explicitNonDefaultUrl_passesThrough_dryRunExitsZero(@TempDir Path tmp) throws Exception {
        KompileProjectManifest manifest = manifestWithAutoIngestProfile(tmp);
        KompileProjectWorkflow workflow = workflowWithSteps(
                healthCheckStep("http://localhost:8090/actuator/health"),  // staging server
                crawlStep("auto-ingest")
        );
        manifest.getWorkflows().add(workflow);

        PrintStream saved = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            int exit = ProjectCrawlCommand.runWorkflow(
                    new KompileProjectStore(), manifest, workflow, tmp,
                    "http://localhost:8080", null, /*dryRun=*/true);
            assertEquals(0, exit, "Dry-run of workflow with explicit non-default URL should exit 0");
        } finally {
            System.setOut(saved);
        }
        String out = buf.toString();
        assertTrue(out.contains("http://localhost:8090/actuator/health"),
                "Explicit non-default URL should be preserved in output. Got: " + out);
    }

    // ── Bug 2: CRAWL step null-ref fallback ───────────────────────────────────

    /**
     * When a CRAWL workflow step has ref=null (generated before the fix), the runner
     * must fall back to the first available crawl profile instead of throwing an exception.
     */
    @Test
    void crawlStep_nullRef_fallsBackToFirstProfile_dryRunExitsZero(@TempDir Path tmp) throws Exception {
        KompileProjectManifest manifest = manifestWithAutoIngestProfile(tmp);
        // Simulate the pre-fix generator: CRAWL step with null ref
        KompileProjectWorkflow workflow = workflowWithSteps(crawlStep(null));
        manifest.getWorkflows().add(workflow);

        PrintStream saved = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            int exit = ProjectCrawlCommand.runWorkflow(
                    new KompileProjectStore(), manifest, workflow, tmp,
                    "http://localhost:8080", null, /*dryRun=*/true);
            assertEquals(0, exit,
                    "Dry-run of CRAWL step with null ref should exit 0 when a profile exists (fallback)");
        } finally {
            System.setOut(saved);
        }
    }

    /**
     * When a CRAWL step has ref=null AND the manifest has no crawl profiles at all,
     * the runner must throw rather than silently continuing with a null profile.
     */
    @Test
    void crawlStep_nullRef_noProfilesInManifest_throws(@TempDir Path tmp) throws Exception {
        KompileProjectManifest manifest = new KompileProjectManifest();
        manifest.setProjectId("empty-project");
        // No crawl profiles — fallback has nothing to pick.
        KompileProjectWorkflow workflow = workflowWithSteps(crawlStep(null));
        manifest.getWorkflows().add(workflow);

        assertThrows(IllegalArgumentException.class, () ->
                ProjectCrawlCommand.runWorkflow(
                        new KompileProjectStore(), manifest, workflow, tmp,
                        "http://localhost:8080", null, /*dryRun=*/true),
                "Should throw when ref is null and no profiles exist");
    }

    // ── Generator correctness: auto-ingest waits for the crawl-manager persona ──

    @Test
    void artifactLaunchCommand_executesNativeDistributionComponentWithSideLoadedLibs(
            @TempDir Path tmp) throws Exception {
        Path distribution = tmp.resolve("kompile-dist");
        Path nativeComponent = distribution.resolve("bin/kompile-chat");
        Files.createDirectories(nativeComponent.getParent());
        Files.createDirectories(distribution.resolve("lib"));
        Files.writeString(nativeComponent, "#!/usr/bin/env bash\nexit 0\n");
        nativeComponent.toFile().setExecutable(true);

        String command = ProjectCrawlCommand.artifactLaunchCommand(nativeComponent,
                List.of("--server.port=18081"));

        assertTrue(command.contains("KOMPILE_DIST_HOME='" + distribution + "'"), command);
        assertTrue(command.contains("KOMPILE_NATIVE_LIB_DIR='" + distribution.resolve("lib") + "'"), command);
        assertTrue(command.contains("LD_LIBRARY_PATH='" + distribution.resolve("bin") + ":"
                + distribution.resolve("lib") + "'"), command);
        assertTrue(command.contains("exec '" + nativeComponent + "' '-Dkompile.dist.home="
                + distribution + "' '--server.port=18081'"), command);
        assertFalse(command.contains("java -jar"), command);
        assertFalse(command.contains("LD_PRELOAD"), command);
        assertEquals(0, new ProcessBuilder("bash", "-lc", command).start().waitFor(), command);
    }

    @Test
    void artifactLaunchCommand_usesBundledRuntimeForDistributionJar(@TempDir Path tmp) throws Exception {
        Path distribution = tmp.resolve("kompile-dist");
        Path jar = distribution.resolve("lib/kompile-chat.jar");
        Path java = distribution.resolve("runtime/bin/java");
        Files.createDirectories(distribution.resolve("bin"));
        Files.createDirectories(jar.getParent());
        Files.createDirectories(java.getParent());
        Files.writeString(jar, "not-a-real-jar");
        Files.writeString(java, "#!/usr/bin/env bash\nexit 0\n");
        java.toFile().setExecutable(true);

        String command = ProjectCrawlCommand.artifactLaunchCommand(jar,
                List.of("--server.port=18081"));

        assertTrue(command.contains("exec '" + java + "' '-Dkompile.dist.home=" + distribution
                + "' -jar '" + jar + "' '--server.port=18081'"), command);
        assertFalse(command.contains("LD_PRELOAD"), command);
        assertEquals(0, new ProcessBuilder("bash", "-lc", command).start().waitFor(), command);
    }

    /**
     * After `kompile project init`, the generated auto-ingest workflow's HEALTH_CHECK step
     * must target an endpoint owned by the crawl manager, and the CRAWL step must have
     * ref="auto-ingest".
     */
    @Test
    void projectInit_generatesAutoIngestWorkflow_withCrawlManagerHealthUrlAndAutoIngestCrawlRef(
            @TempDir Path tmp) {
        Path docs = tmp.resolve("data/input_documents");
        assertDoesNotThrow(() -> {
            Files.createDirectories(docs);
            Files.writeString(docs.resolve("sample.txt"), "test document");
        });

        int exit = execute("project", "init",
                "--root", tmp.toString(),
                "--name", "workflow-fix-test",
                "--backend", "local");
        assertEquals(0, exit, "project init should succeed");

        KompileProjectStore store = new KompileProjectStore();
        KompileProjectManifest manifest = store.load(tmp);

        KompileProjectWorkflow autoIngest = manifest.getWorkflows().stream()
                .filter(w -> "auto-ingest".equals(w.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(autoIngest, "auto-ingest workflow should be generated by project init");

        KompileProjectWorkflowStep healthStep = autoIngest.getSteps().stream()
                .filter(s -> "HEALTH_CHECK".equals(s.getType()))
                .findFirst()
                .orElse(null);
        assertNotNull(healthStep, "auto-ingest workflow should have a HEALTH_CHECK step");
        assertEquals("${appUrl}/api/unified-crawl/jobs/active", healthStep.getUrl(),
                "HEALTH_CHECK must wait for the persona that serves the next crawl step");
        assertEquals(200, healthStep.getExpectedStatus());

        KompileProjectWorkflowStep crawlStep = autoIngest.getSteps().stream()
                .filter(s -> "CRAWL".equals(s.getType()))
                .findFirst()
                .orElse(null);
        assertNotNull(crawlStep, "auto-ingest workflow should have a CRAWL step");
        assertNotNull(crawlStep.getRef(),
                "CRAWL step must have a non-null ref; null causes 'Crawl null: missing profile'");
        assertEquals("auto-ingest", crawlStep.getRef(),
                "CRAWL step ref should be 'auto-ingest' to match the generated crawl profile");
    }

    @Test
    void projectInit_vlmPresetAutoIngestWorkflowReferencesVlmCrawlProfile(@TempDir Path tmp) {
        int exit = execute("project", "init",
                "--root", tmp.toString(),
                "--name", "workflow-vlm-ref-test",
                "--backend", "local",
                "--preset", "vlm-ocr",
                "--source", "data/input_documents/uploads",
                "--schema-preset", "example-schema-v1");
        assertEquals(0, exit, "project init should succeed");

        KompileProjectManifest manifest = new KompileProjectStore().load(tmp);
        KompileProjectWorkflow autoIngest = manifest.getWorkflows().stream()
                .filter(w -> "auto-ingest".equals(w.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(autoIngest, "auto-ingest workflow should be generated by project init");

        KompileProjectWorkflowStep crawlStep = autoIngest.getSteps().stream()
                .filter(s -> "CRAWL".equals(s.getType()))
                .findFirst()
                .orElse(null);
        assertNotNull(crawlStep, "auto-ingest workflow should have a CRAWL step");
        assertEquals("vlm-ocr-docs", crawlStep.getRef(),
                "auto-ingest CRAWL step should reference the generated VLM crawl profile");
    }

    @Test
    void projectServe_chatOnlyRunsGeneratedLifecycleScript(@TempDir Path tmp) {
        int initExit = execute("project", "init",
                "--root", tmp.toString(),
                "--name", "serve-chat-test",
                "--backend", "local");
        assertEquals(0, initExit);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(captured));
            assertEquals(0, execute("project", "serve", "--root", tmp.toString(),
                    "--chat-only", "--dry-run"));
        } finally {
            System.setOut(originalOut);
        }
        assertTrue(captured.toString().contains("./scripts/start-chat.sh"), captured.toString());
    }

    @Test
    void projectCrawlServe_startsServicesBeforeAutoIngestWorkflow(@TempDir Path tmp) {
        int initExit = execute("project", "init",
                "--root", tmp.toString(),
                "--name", "workflow-serve-test",
                "--backend", "local",
                "--preset", "vlm-ocr");
        assertEquals(0, initExit, "project init should succeed");

        PrintStream originalOut = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured));
            int crawlExit = execute("project", "crawl",
                    "--root", tmp.toString(),
                    "--serve",
                    "--dry-run");
            assertEquals(0, crawlExit, "crawl --serve dry-run should succeed");
        } finally {
            System.setOut(originalOut);
        }

        String output = captured.toString();
        int startWorkflow = output.indexOf("Workflow: Start services (start-services)");
        int crawlWorkflow = output.lastIndexOf("Workflow: Auto ingest (auto-ingest)");
        assertTrue(startWorkflow >= 0, "--serve should select the start-services workflow. Output: " + output);
        assertTrue(crawlWorkflow > startWorkflow,
                "start-services must run before auto-ingest. Output: " + output);
        assertTrue(output.contains("Pipeline runtimes are MCP-managed and start on demand — no standalone service started."),
                "VLM/OCR projects should explicitly skip standalone serving without creating a no-op PID. Output: " + output);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Manifest with a single auto-ingest crawl profile — minimal viable manifest. */
    private static KompileProjectManifest manifestWithAutoIngestProfile(Path tmp) {
        KompileProjectManifest manifest = new KompileProjectManifest();
        manifest.setProjectId("test-project");
        KompileProjectCrawlProfile profile = new KompileProjectCrawlProfile();
        profile.setId("auto-ingest");
        profile.setName("Auto ingest");
        profile.setSources(List.of("data/input_documents"));
        profile.setSourceType("DIRECTORY");
        profile.setGraphExtraction(true);
        manifest.getCrawlProfiles().add(profile);
        return manifest;
    }

    private static KompileProjectWorkflow workflowWithSteps(KompileProjectWorkflowStep... steps) {
        KompileProjectWorkflow wf = new KompileProjectWorkflow();
        wf.setId("test-workflow");
        wf.setName("Test workflow");
        wf.setSteps(List.of(steps));
        return wf;
    }

    private static KompileProjectWorkflowStep healthCheckStep(String url) {
        KompileProjectWorkflowStep step = new KompileProjectWorkflowStep();
        step.setId("wait-for-app");
        step.setName("Wait for app");
        step.setType("HEALTH_CHECK");
        step.setUrl(url);
        step.setTimeoutSeconds(5);   // short; dry-run never actually probes
        return step;
    }

    private static KompileProjectWorkflowStep crawlStep(String ref) {
        KompileProjectWorkflowStep step = new KompileProjectWorkflowStep();
        step.setId("run-crawl");
        step.setName("Run crawl");
        step.setType("CRAWL");
        step.setRef(ref);
        return step;
    }

    private static int execute(String... args) {
        CommandLine commandLine = new CommandLine(new MainCommand());
        commandLine.setOut(new PrintWriter(new StringWriter()));
        commandLine.setErr(new PrintWriter(new StringWriter()));
        return commandLine.execute(args);
    }
}
