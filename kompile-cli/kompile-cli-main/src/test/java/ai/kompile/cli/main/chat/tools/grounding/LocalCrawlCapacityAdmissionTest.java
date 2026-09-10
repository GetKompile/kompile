/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.project.LocalSubprocessWatchdog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end admission checks at the project-local crawl_documents boundary. */
class LocalCrawlCapacityAdmissionTest {

    @TempDir
    Path projectRoot;

    private ObjectMapper mapper;
    private ToolContext context;
    private LocalProjectCrawlBackend backend;

    @BeforeEach
    void setUp() throws Exception {
        clearAdmissionProperties();
        mapper = new ObjectMapper();
        AgentConfig agent = AgentConfig.builder("capacity-admission-test")
                .enabledTools(Set.of("*"))
                .build();
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("crawl_documents", PermissionService.PermissionLevel.ALLOW);
        permissions.setUserOverride("crawl_control", PermissionService.PermissionLevel.ALLOW);
        context = new ToolContext("capacity-admission-test", agent, permissions, projectRoot,
                new ToolRegistry(mapper));
        backend = new LocalProjectCrawlBackend(mapper);
        Files.writeString(projectRoot.resolve("capacity-note.md"),
                "A tiny document used to verify local crawl capacity admission.\n",
                StandardCharsets.UTF_8);
    }

    @AfterEach
    void tearDown() {
        clearAdmissionProperties();
    }

    @Test
    void failModeRejectsBeforeTheCrawlWritesArtifacts() {
        configureImpossibleCapacity("fail", 5_000L, 10L);

        ToolResult result = backend.crawlDocuments(request(false, false, "capacity-fail"), context);

        assertTrue(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("was rejected immediately"), result.getOutput());
        assertTrue(result.getOutput().contains("available RAM"), result.getOutput());
        assertFalse(Files.exists(projectRoot.resolve("data/crawls/capacity-fail/documents.jsonl")));
    }

    @Test
    void waitModeTimesOutBeforeTheCrawlWritesArtifacts() {
        configureImpossibleCapacity("wait", 10L, 1L);

        ToolResult result = backend.crawlDocuments(request(false, false, "capacity-timeout"), context);

        assertTrue(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("timed out after"), result.getOutput());
        assertTrue(result.getOutput().contains("available RAM"), result.getOutput());
        assertFalse(Files.exists(projectRoot.resolve("data/crawls/capacity-timeout/documents.jsonl")));
    }

    @Test
    void asynchronousWaitIsVisibleAndCanBeCancelled() throws Exception {
        configureImpossibleCapacity("wait", 5_000L, 25L);
        ToolResult started = backend.crawlDocuments(
                request(true, false, "capacity-cancel"), context);
        String jobId = String.valueOf(started.getMetadata().get("jobId"));

        assertFalse(started.isError(), started.getOutput());
        try {
            ToolResult waiting = awaitStage(jobId, "WAITING_FOR_CAPACITY", 3_000L);
            assertEquals("WAITING_FOR_CAPACITY", waiting.getMetadata().get("stage"));
            assertTrue(waiting.getOutput().contains("Waiting for local hardware capacity"),
                    waiting.getOutput());
            Map<?, ?> admissionStatus = (Map<?, ?>) LocalSubprocessWatchdog.get()
                    .statusMap().get("capacityAdmission");
            assertEquals(1, ((Number) admissionStatus.get("waiting")).intValue());

            ToolResult cancelling = cancel(jobId);
            assertFalse(cancelling.isError(), cancelling.getOutput());
            ToolResult terminal = awaitTerminal(jobId, 3_000L);
            assertEquals("CANCELLED", terminal.getMetadata().get("status"));
            assertEquals(true, terminal.getMetadata().get("terminal"));
            admissionStatus = (Map<?, ?>) LocalSubprocessWatchdog.get()
                    .statusMap().get("capacityAdmission");
            assertEquals(0, ((Number) admissionStatus.get("waiting")).intValue());
            assertFalse(Files.exists(projectRoot.resolve(
                    "data/crawls/capacity-cancel/documents.jsonl")));
        } finally {
            cancelIfActive(jobId);
        }
    }

    @Test
    void dryRunBypassesHardwareAdmission() {
        configureImpossibleCapacity("fail", 5_000L, 10L);

        ToolResult result = backend.crawlDocuments(request(false, true, "capacity-preview"), context);

        assertFalse(result.isError(), result.getOutput());
        assertEquals("DRY_RUN", result.getMetadata().get("status"));
        assertFalse(Files.exists(projectRoot.resolve("data/crawls/capacity-preview/documents.jsonl")));
    }

    @Test
    void runtimeDiscoveryAdvertisesTheAdmissionContract() {
        ToolResult result = backend.discover("runtime", projectRoot);

        assertFalse(result.isError(), result.getOutput());
        assertTrue(result.getOutput().contains("hardwareAdmission"), result.getOutput());
        assertTrue(result.getOutput().contains("admissionMode"), result.getOutput());
        assertTrue(result.getOutput().contains("subprocess_watchdog"), result.getOutput());
    }

    @Test
    void admittedAsynchronousCrawlCompletesAndPersistsItsCorpus() throws Exception {
        configureHealthyCapacity();
        ToolResult started = backend.crawlDocuments(
                request(true, false, "capacity-admitted"), context);

        assertFalse(started.isError(), started.getOutput());
        String jobId = String.valueOf(started.getMetadata().get("jobId"));
        try {
            ToolResult terminal = awaitTerminal(jobId, 5_000L);

            assertEquals("COMPLETED", terminal.getMetadata().get("status"), terminal.getOutput());
            assertEquals(true, terminal.getMetadata().get("terminal"));
            assertTrue(Files.isRegularFile(projectRoot.resolve(
                    "data/crawls/capacity-admitted/documents.jsonl")));
            assertTrue(Files.isRegularFile(projectRoot.resolve(
                    "data/crawls/capacity-admitted/chunks.jsonl")));
        } finally {
            cancelIfActive(jobId);
        }
    }

    private ObjectNode request(boolean async, boolean dryRun, String knowledgeBase) {
        ObjectNode request = mapper.createObjectNode();
        request.put("async", async);
        request.put("dryRun", dryRun);
        request.putObject("knowledgeBase").put("name", knowledgeBase);
        request.putArray("documents").addObject()
                .put("path", "capacity-note.md")
                .put("sourceType", "FILE");
        request.putArray("steps")
                .add("LOADING").add("MARKDOWN_EXTRACTION").add("CHUNKING");
        request.put("strictSteps", true);
        request.put("deriveOntology", false);
        request.putObject("embeddingTraining").put("enabled", false);
        request.putObject("reasoningLearning").put("enabled", false);
        return request;
    }

    private ToolResult awaitStage(String jobId, String expected, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        ToolResult status = null;
        while (System.nanoTime() < deadline) {
            status = status(jobId);
            if (expected.equals(status.getMetadata().get("stage"))) return status;
            if (Boolean.TRUE.equals(status.getMetadata().get("terminal"))) break;
            Thread.sleep(10L);
        }
        throw new AssertionError("Did not observe stage " + expected + "; last status="
                + (status == null ? "none" : status.getOutput()));
    }

    private ToolResult awaitTerminal(String jobId, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        ToolResult status = null;
        while (System.nanoTime() < deadline) {
            status = status(jobId);
            if (Boolean.TRUE.equals(status.getMetadata().get("terminal"))) return status;
            Thread.sleep(10L);
        }
        throw new AssertionError("Crawl did not become terminal; last status="
                + (status == null ? "none" : status.getOutput()));
    }

    private ToolResult status(String jobId) {
        return backend.control(mapper.createObjectNode()
                .put("operation", "status").put("jobId", jobId), context);
    }

    private ToolResult cancel(String jobId) {
        return backend.control(mapper.createObjectNode()
                .put("operation", "cancel").put("jobId", jobId), context);
    }

    private void cancelIfActive(String jobId) {
        ToolResult current = status(jobId);
        if (!Boolean.TRUE.equals(current.getMetadata().get("terminal"))) {
            cancel(jobId);
        }
    }

    private static void configureImpossibleCapacity(String mode, long timeoutMs, long pollMs) {
        System.setProperty(LocalSubprocessWatchdog.ENABLED_PROPERTY, "true");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, mode);
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_TIMEOUT_MS_PROPERTY,
                Long.toString(timeoutMs));
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_POLL_INTERVAL_MS_PROPERTY,
                Long.toString(pollMs));
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MIN_AVAILABLE_RAM_MB_PROPERTY,
                Long.toString(Long.MAX_VALUE));
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_RAM_USED_FRACTION_PROPERTY, "0");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MIN_AVAILABLE_GPU_MB_PROPERTY, "0");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_GPU_USED_FRACTION_PROPERTY, "0");
    }

    private static void configureHealthyCapacity() {
        System.setProperty(LocalSubprocessWatchdog.ENABLED_PROPERTY, "true");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY, "fail");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MIN_AVAILABLE_RAM_MB_PROPERTY, "0");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_RAM_USED_FRACTION_PROPERTY, "1");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MIN_AVAILABLE_GPU_MB_PROPERTY, "0");
        System.setProperty(LocalSubprocessWatchdog.ADMISSION_MAX_GPU_USED_FRACTION_PROPERTY, "0");
    }

    private static void clearAdmissionProperties() {
        System.clearProperty(LocalSubprocessWatchdog.ENABLED_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MODE_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_TIMEOUT_MS_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_POLL_INTERVAL_MS_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MIN_AVAILABLE_RAM_MB_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MAX_RAM_USED_FRACTION_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MIN_AVAILABLE_GPU_MB_PROPERTY);
        System.clearProperty(LocalSubprocessWatchdog.ADMISSION_MAX_GPU_USED_FRACTION_PROPERTY);
    }
}
