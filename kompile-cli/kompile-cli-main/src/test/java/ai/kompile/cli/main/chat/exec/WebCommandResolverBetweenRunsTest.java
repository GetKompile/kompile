/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.exec.WebCommandResolver.Resolution;
import ai.kompile.cli.main.chat.exec.WebCommandResolver.RunPermissions;
import ai.kompile.cli.main.chat.exec.WebCommandResolver.Status;
import ai.kompile.cli.main.chat.skill.SkillRegistry;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.coordination.CoordinationStateManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Process commands the web chat sends between runs. No harness is running then, so the CLI
 * answers them from the processes the session's runs recorded and those other sessions
 * share, permitted as the session's run would permit them.
 */
@TemporaryUserHome
@DisabledOnOs(OS.WINDOWS)
class WebCommandResolverBetweenRunsTest {

    private static final String SESSION = "between-runs-session";
    private static final RunPermissions CODER = new RunPermissions("coder", null, false);
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path temp;
    private Path project;

    @BeforeEach
    void setUp() throws Exception {
        // The project's .kompile holds the coordination state and the session's logs and history.
        project = temp.resolve("project");
        Path roles = Files.createDirectories(project.resolve(".kompile").resolve("roles"));
        Files.writeString(roles.resolve("no-process.md"), role("no-process", "deny_tools: process\n"));
        Files.writeString(roles.resolve("may-process.md"), role("may-process", ""));
    }

    @Test
    void aLaterMessageListsTailsAndInspectsWhatAnEarlierRunLaunched() throws Exception {
        String id = launchAndFinish("echo between-runs-output", "say it");
        String recorded = Files.readString(historyFile());

        Resolution list = resolve("/processes", CODER);
        assertEquals(Status.COMPLETED, list.status(), list.text());
        assertEquals("/processes", list.command());
        assertTrue(list.text().contains("[" + id + "] say it"), list.text());

        Resolution output = resolve("/process-output " + id, CODER);
        assertEquals(Status.COMPLETED, output.status(), output.text());
        assertTrue(output.text().contains("between-runs-output"), output.text());

        Resolution status = resolve("/process-status " + id, CODER);
        assertEquals(Status.COMPLETED, status.status(), status.text());
        assertTrue(status.text().contains("State:       COMPLETED"), status.text());
        assertTrue(status.text().contains("Exit Code:   0"), status.text());

        Resolution kill = resolve("/process-kill " + id, CODER);
        assertEquals(Status.COMPLETED, kill.status(), kill.text());
        assertTrue(kill.text().contains("Process " + id + " is already COMPLETED (exit code: 0)"), kill.text());

        // Reading the history, and killing what already ended, rewrite nothing.
        assertEquals(recorded, Files.readString(historyFile()));
    }

    @Test
    void aProcessThatOutlivedItsRunIsStoppedByALaterMessage() throws Exception {
        // The harness that launched it was killed hard, so its record still says RUNNING.
        Process orphan = new ProcessBuilder("sleep", "30").start();
        try {
            ObjectNode root = JSON.createObjectNode().put("version", 1);
            root.putArray("processes").addObject()
                    .put("id", "proc-001").put("command", "sleep 30").put("description", "long job")
                    .put("state", "RUNNING").put("startTime", Instant.now().toString())
                    .put("pid", orphan.pid())
                    .put("osStart", orphan.info().startInstant().orElseThrow().toString());
            Files.createDirectories(historyFile().getParent());
            Files.writeString(historyFile(), JSON.writeValueAsString(root));

            Resolution running = resolve("/process-status proc-001", CODER);
            assertEquals(Status.COMPLETED, running.status(), running.text());
            assertTrue(running.text().contains("State:       RUNNING"), running.text());
            assertTrue(orphan.isAlive(), "reading the history signals nothing");

            Resolution kill = resolve("/process-kill proc-001", CODER);
            assertEquals(Status.COMPLETED, kill.status(), kill.text());
            assertTrue(kill.text().contains("Process proc-001 (PID " + orphan.pid() + ") killed."), kill.text());
            assertTrue(orphan.waitFor(5, TimeUnit.SECONDS), "the recorded process is stopped");

            JsonNode record = JSON.readTree(Files.readString(historyFile())).path("processes").get(0);
            assertEquals("KILLED", record.path("state").asText());
            assertEquals(-1, record.path("exitCode").asInt());
            Resolution after = resolve("/process-status proc-001", CODER);
            assertTrue(after.text().contains("State:       KILLED"), after.text());
        } finally {
            orphan.destroyForcibly();
        }
    }

    @Test
    void aMissingOrUnknownIdIsInvalid() {
        for (String command : List.of("/process-output", "/process-status", "/process-kill")) {
            Resolution usage = resolve(command, CODER);
            assertEquals(Status.INVALID, usage.status(), command);
            assertEquals("Usage: " + command + " <id>", usage.text());

            Resolution unknown = resolve(command + " proc-404", CODER);
            assertEquals(Status.INVALID, unknown.status(), command);
            assertEquals("Process not found: proc-404", unknown.text());
            assertEquals(2, unknown.exitCode());
        }
    }

    @Test
    void jobCommandsFindNoTasksBetweenRunsAndActivityOnlyLists() {
        Resolution jobs = resolve("/jobs", CODER);
        assertEquals(Status.COMPLETED, jobs.status(), jobs.text());
        assertTrue(jobs.text().contains("No background tasks"), jobs.text());

        Resolution clear = resolve("/jobs-clear", CODER);
        assertEquals(Status.COMPLETED, clear.status());
        assertEquals("Cleared completed tasks", clear.text());

        Resolution usage = resolve("/jobs-remove", CODER);
        assertEquals(Status.INVALID, usage.status());
        assertEquals("Usage: /jobs-remove <id>", usage.text());
        Resolution remove = resolve("/jobs-remove task-1", CODER);
        assertEquals(Status.INVALID, remove.status());
        assertEquals("Task not found or still running: task-1", remove.text());

        assertEquals(Status.COMPLETED, resolve("/activity", CODER).status());
        Resolution watch = resolve("/activity watch", CODER);
        assertEquals(Status.INVALID, watch.status());
        assertEquals("/activity watch requires the interactive terminal; no action was performed.", watch.text());
    }

    @Test
    void theRunsRoleAndApprovalsDecideWhetherACommandRuns() throws Exception {
        String id = launchAndFinish("echo guarded-output", "guarded");
        RunPermissions denied = new RunPermissions("coder", "no-process", false);
        for (String command : List.of("/processes", "/process-output " + id,
                "/process-status " + id, "/process-kill " + id)) {
            Resolution resolution = resolve(command, denied);
            assertEquals(Status.INVALID, resolution.status(), command);
            assertTrue(resolution.text().startsWith("Permission denied: process - Web process "),
                    command + ": " + resolution.text());
        }

        // Skipping permission prompts approves every tool call, as it does in the run.
        Resolution skipped = resolve("/process-output " + id, new RunPermissions("coder", "no-process", true));
        assertEquals(Status.COMPLETED, skipped.status(), skipped.text());
        assertTrue(skipped.text().contains("guarded-output"), skipped.text());

        Resolution missing = resolve("/processes", new RunPermissions("coder", "nope", false));
        assertEquals(Status.INVALID, missing.status());
        assertEquals("Role not found: nope", missing.text());
    }

    @Test
    void theSessionsDurableRoleAppliesUnlessTheRunNamesOne() {
        ChatSessionStateStore store = new ChatSessionStateStore(temp.resolve("state"));
        assertTrue(store.updateRole(SESSION, project, "no-process").applied());
        Resolution durable = resolve(store, "/processes", CODER);
        assertEquals(Status.INVALID, durable.status(), durable.text());
        assertTrue(durable.text().startsWith("Permission denied: process"), durable.text());

        Resolution named = resolve(store, "/processes", new RunPermissions("coder", "may-process", false));
        assertEquals(Status.COMPLETED, named.status(), named.text());

        // A durable role that no longer exists is ignored, as the run ignores it.
        assertTrue(store.updateRole(SESSION, project, "retired").applied());
        Resolution retired = resolve(store, "/processes", CODER);
        assertEquals(Status.COMPLETED, retired.status(), retired.text());
    }

    @Test
    void anotherSessionsProcessIsListedAndTailedButOnlyItsOwnerStopsIt() throws Exception {
        Path log = Files.writeString(temp.resolve("owner.log"), "owner output\n");
        CoordinationStateManager owner = new CoordinationStateManager(project, "owner-session",
                JsonUtils.standardMapper(), temp.resolve("system"));
        try {
            owner.publishProcess("proc-001", "make build", "owner build", 0L, "RUNNING",
                    log.toString(), "codex");
            String id = "shared-owner-session-proc-001";

            Resolution list = resolve("/processes", CODER);
            assertEquals(Status.COMPLETED, list.status(), list.text());
            assertTrue(list.text().contains("[" + id + "]"), list.text());

            Resolution output = resolve("/process-output " + id, CODER);
            assertEquals(Status.COMPLETED, output.status(), output.text());
            assertTrue(output.text().contains("owner output"), output.text());

            Resolution kill = resolve("/process-kill " + id, CODER);
            assertEquals(Status.INVALID, kill.status(), kill.text());
            assertEquals("Process " + id + " belongs to codex; only its owning session can stop it", kill.text());
        } finally {
            owner.shutdown();
        }
    }

    @Test
    void withoutTheRunsFlagsOrAUsableSessionNothingIsAnswered() {
        String liveOnly = "/processes works during a live run: send it while the agent is working. "
                + "Between runs no harness is running, so no action was performed.";
        List<Resolution> unanswered = List.of(
                WebCommandResolver.resolve(input("/processes", SESSION), project),
                WebCommandResolver.resolve(input("/processes", "../" + SESSION), project, CODER),
                WebCommandResolver.resolve(input("/processes", ".."), project, CODER),
                WebCommandResolver.resolve(input("/processes", ""), project, CODER),
                WebCommandResolver.resolve(input("/processes", SESSION), null, CODER));
        for (Resolution resolution : unanswered) {
            assertEquals(Status.LIVE_SESSION_REQUIRED, resolution.status(), resolution.text());
            assertEquals(liveOnly, resolution.text());
        }
        assertFalse(Files.exists(project.resolve(".kompile").resolve("coordination")),
                "no coordinator was started");
    }

    // -----------------------------------------------------------------

    private static String role(String name, String extraFrontmatter) {
        return "---\nname: " + name + "\ndisplay_name: " + name + "\ndescription: between-runs test role\n"
                + "category: testing\nmodel: default\n" + extraFrontmatter + "---\nYou are a test role.\n";
    }

    private static WebChatInput input(String raw, String sessionId) {
        return new WebChatInput(WebChatInput.VERSION, raw, "", sessionId);
    }

    private Resolution resolve(String raw, RunPermissions permissions) {
        return WebCommandResolver.resolve(input(raw, SESSION), project, permissions);
    }

    private Resolution resolve(ChatSessionStateStore store, String raw, RunPermissions permissions) {
        return WebCommandResolver.resolveInternal(input(raw, SESSION), SkillRegistry::new, store, project,
                () -> null, null, permissions);
    }

    private Path historyFile() {
        return project.resolve(".kompile").resolve("process-output").resolve(SESSION).resolve("processes.json");
    }

    /** A run of the session launches {@code command}, which exits before the run ends. */
    private String launchAndFinish(String command, String description) throws Exception {
        try (BackgroundProcessManager run = new BackgroundProcessManager(SESSION, project)) {
            run.enableSessionHistory();
            BackgroundProcessManager.ProcessEntry entry = run.launch(command, description, project);
            for (int attempts = 0; entry.isRunning() && attempts < 50; attempts++) {
                Thread.sleep(100);
            }
            assertFalse(entry.isRunning(), entry.getId() + " should have exited");
            return entry.getId();
        }
    }
}
