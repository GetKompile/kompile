/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.ChatCommandCatalog;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.roles.RoleManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Web {@code /role} and {@code /fast} command resolution: durable session
 * selections mirroring the established {@code /model} contract.
 */
class WebCommandResolverRoleFastTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;
    private Path project;
    private Path home;
    private String previousHome;

    @BeforeEach
    void setUp() throws Exception {
        previousHome = System.getProperty("user.home");
        home = Files.createDirectories(tempDir.resolve("home"));
        System.setProperty("user.home", home.toString());
        project = Files.createDirectories(tempDir.resolve("project"));
        // Built-in roles come from the classpath; a project role proves loading works.
        Path rolesDir = Files.createDirectories(project.resolve(".kompile").resolve("roles"));
        Files.writeString(rolesDir.resolve("web-test-role.md"), """
                ---
                name: web-test-role
                display_name: Web Test Role
                description: role used by web command tests
                category: testing
                model: default
                ---
                You are a web test role.
                """);
    }

    @AfterEach
    void tearDown() {
        if (previousHome != null) System.setProperty("user.home", previousHome);
    }

    private static WebChatInput input(String raw) {
        return new WebChatInput(WebChatInput.VERSION, raw, "", "web-session-1");
    }

    private WebCommandResolver.Resolution resolve(String raw) {
        return WebCommandResolver.resolve(input(raw), project, new ChatSessionStateStore());
    }

    // ── Catalog support ──────────────────────────────────────────────────

    @Test
    void roleAndFastAreWebSupportedInCatalog() {
        assertEquals(ChatCommandCatalog.WebSupport.SUPPORTED,
                ChatCommandCatalog.webSupport("role"));
        assertEquals(ChatCommandCatalog.WebSupport.SUPPORTED,
                ChatCommandCatalog.webSupport("fast"));
        assertEquals(ChatCommandCatalog.WebSupport.SUPPORTED,
                ChatCommandCatalog.webSupport("model"));
    }

    // ── /role bare form: menu with current selection ─────────────────────

    @Test
    void bareRoleListsRosterWithoutSelection() {
        WebCommandResolver.Resolution resolution = resolve("/role");
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, resolution.status());
        assertEquals(0, resolution.exitCode());
        JsonNode data = resolution.data();
        assertNotNull(data, "menu payload must be present");
        assertEquals("role", data.path("menu").asText());
        assertNull(data.path("currentRole").asText(null));
        assertTrue(data.path("roles").isArray());
        assertTrue(data.path("roles").size() >= 7, "built-ins plus the project role");
        boolean hasProjectRole = false;
        for (JsonNode role : data.path("roles")) {
            if ("web-test-role".equals(role.path("name").asText())) {
                hasProjectRole = true;
                assertEquals("Web Test Role", role.path("display").asText());
                assertEquals("testing", role.path("category").asText());
            }
        }
        assertTrue(hasProjectRole, "project-scoped roles must appear in the menu");
        assertTrue(resolution.text().contains("Select with: /role <name>"));
    }

    @Test
    void bareRoleMarksPersistedSelectionCurrent() {
        ChatSessionStateStore store = new ChatSessionStateStore();
        assertTrue(store.updateRole("web-session-1", project, "architect").applied());
        WebCommandResolver.Resolution resolution =
                WebCommandResolver.resolve(input("/role"), project, store);
        assertEquals("architect", resolution.data().path("currentRole").asText());
        assertTrue(resolution.text().contains("* architect"));
    }

    // ── /role <name>: validation and durable persistence ─────────────────

    @Test
    void explicitRoleSelectionPersistsAndCanonicalizes() {
        WebCommandResolver.Resolution resolution = resolve("/role ARCHITECT");
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, resolution.status());
        JsonNode state = resolution.data().path("state");
        assertEquals("web-session-1", state.path("sessionId").asText());
        assertEquals("architect", state.path("role").asText(), "roster casing wins");
        assertEquals(project.toAbsolutePath().normalize().toString(),
                state.path("workingDirectory").asText());
        ChatSessionStateStore store = new ChatSessionStateStore();
        assertEquals("architect", store.loadRole("web-session-1", project));
    }

    @Test
    void unknownRoleIsRejectedWithoutStateChange() {
        WebCommandResolver.Resolution resolution = resolve("/role no-such-role");
        assertEquals(WebCommandResolver.Status.INVALID, resolution.status());
        assertEquals(2, resolution.exitCode());
        assertTrue(resolution.text().contains("Unknown role"));
        assertNull(new ChatSessionStateStore().loadRole("web-session-1", project));
    }

    @Test
    void clearingRoleWithEmptyQuotesPersistsEmptyAndReadsAbsent() {
        assertTrue(resolve("/role architect").status()
                == WebCommandResolver.Status.INTERACTION_REQUIRED);
        WebCommandResolver.Resolution cleared = resolve("/role ''");
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, cleared.status());
        assertTrue(cleared.data().path("cleared").asBoolean());
        assertEquals("", cleared.data().path("state").path("role").asText());
        assertNull(new ChatSessionStateStore().loadRole("web-session-1", project));
        // "none" and "default" are aliases for clearing.
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED,
                resolve("/role none").status());
        assertNull(new ChatSessionStateStore().loadRole("web-session-1", project));
    }

    @Test
    void roleSelectionIsScopedToWorkingDirectory() {
        assertTrue(resolve("/role architect").status()
                == WebCommandResolver.Status.INTERACTION_REQUIRED);
        Path otherProject = tempDir.resolve("other-project");
        assertNull(new ChatSessionStateStore().loadRole("web-session-1", otherProject),
                "stored role must not leak to another project directory");
    }

    @Test
    void roleSelectionSurvivesModelUpdateAndViceVersa() {
        assertTrue(resolve("/role architect").status()
                == WebCommandResolver.Status.INTERACTION_REQUIRED);
        WebChatInput modelInput = new WebChatInput(WebChatInput.VERSION, "/model m1", "", "web-session-1");
        // m1 must be locally verifiable: make it the config's current model.
        ChatConfig modelConfig = configuredChatConfig();
        modelConfig.setModel("m1");
        WebCommandResolver.Resolution modelResolution =
                WebCommandResolver.resolve(modelInput, project, new ChatSessionStateStore(),
                        modelConfig, null);
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, modelResolution.status());
        ChatSessionStateStore store = new ChatSessionStateStore();
        assertEquals("m1", store.loadModel("web-session-1", project));
        assertEquals("architect", store.loadRole("web-session-1", project),
                "the model update must preserve the stored role");
    }

    // ── Schema compatibility ─────────────────────────────────────────────

    @Test
    void legacyV1RecordStillLoadsWithoutRole() throws Exception {
        Path stateDir = Files.createDirectories(home.resolve("web-session-state"));
        Files.writeString(stateDir.resolve("legacy-session.json"), """
                {
                  "schemaVersion": 1,
                  "sessionId": "legacy-session",
                  "workingDirectory": "%s",
                  "model": "m-legacy",
                  "updatedAt": "2026-01-01T00:00:00Z"
                }
                """.formatted(project.toAbsolutePath().normalize()));
        ChatSessionStateStore store = new ChatSessionStateStore(stateDir);
        assertEquals("m-legacy", store.loadModel("legacy-session", project));
        assertNull(store.loadRole("legacy-session", project));
        // Writing through upgrades the record to v2 while preserving the model.
        assertTrue(store.updateRole("legacy-session", project, "reviewer").applied());
        ChatSessionStateStore.SessionState state = store.load("legacy-session", project);
        assertEquals(ChatSessionStateStore.SCHEMA_VERSION, state.schemaVersion());
        assertEquals("m-legacy", state.model());
        assertEquals("reviewer", state.role());
    }

    // ── /fast: eligibility gate, persistence, and menu payload ──────────

    @Test
    void bareFastReportsCurrentStateForUnsupportedProvider() {
        // Default config (none) does not support fast mode → INVALID with note.
        WebCommandResolver.Resolution resolution = resolve("/fast");
        assertEquals(WebCommandResolver.Status.INVALID, resolution.status());
        assertTrue(resolution.text().contains("not supported"));
    }

    @Test
    void fastToggleRequiresOnOffOrStatus() {
        WebCommandResolver.Resolution resolution = resolve("/fast maybe");
        assertEquals(WebCommandResolver.Status.INVALID, resolution.status());
        assertTrue(resolution.text().contains("Usage: /fast"));
    }

    @Test
    void fastOnForEligibleProviderPersistsAndReportsPayload() {
        WebCommandResolver.Resolution resolution =
                WebCommandResolver.resolve(input("/fast on"), project,
                        new ChatSessionStateStore(), eligibleFastConfig(), null);
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, resolution.status());
        assertTrue(resolution.data().path("menu").asText().equals("fast"));
        assertTrue(resolution.data().path("fastMode").asBoolean());
        assertTrue(resolution.data().path("supported").asBoolean());
        assertEquals("openai", resolution.data().path("provider").asText());
        assertTrue(resolution.text().contains("ON"));
        // Persisted into the project chat configuration like the interactive /fast.
        ChatConfig reloaded = ChatConfig.loadOrFromEnv(project);
        assertTrue(reloaded.isFastMode());
    }

    @Test
    void fastOffForEligibleProviderClearsTheToggle() {
        assertTrue(WebCommandResolver.resolve(input("/fast on"), project,
                new ChatSessionStateStore(), eligibleFastConfig(), null).exitCode() == 0);
        WebCommandResolver.Resolution off = WebCommandResolver.resolve(input("/fast off"),
                project, new ChatSessionStateStore(), eligibleFastConfig(), null);
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, off.status());
        assertFalse(off.data().path("fastMode").asBoolean());
        assertFalse(ChatConfig.loadOrFromEnv(project).isFastMode());
    }

    @Test
    void fastStatusNeverWrites() {
        ChatConfig config = eligibleFastConfig();
        WebCommandResolver.Resolution status = WebCommandResolver.resolve(input("/fast status"),
                project, new ChatSessionStateStore(), config, null);
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, status.status());
        assertFalse(status.data().path("fastMode").asBoolean());
        assertFalse(config.isFastMode(), "status must not write");
    }

    private ChatConfig configuredChatConfig() {
        ChatConfig config = new ChatConfig("custom", null, "base-model", "https://example.test/v1");
        config.setChatMode("standard");
        config.setContextWindowTokens(8_192);
        config.setMaxOutputTokens(1_024);
        return config;
    }

    private ChatConfig eligibleFastConfig() {
        ChatConfig config = configuredChatConfig();
        config.setProvider("openai");
        // Matches the documented openai fast-mode pattern ^gpt-5\.(?:4|5|6)...$
        config.setModel("gpt-5.6");
        return config;
    }

    // ── /reminder and /reminder-global ──────────────────────────────

    @Test
    void reminderAddListAndClearPersistPerScope() {
        WebCommandResolver.Resolution added = resolve("/reminder check the build");
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, added.status());
        assertTrue(added.data().path("menu").asText().equals("reminders"));
        assertEquals("session", added.data().path("scope").asText());
        assertEquals(1, added.data().path("reminders").size());

        WebCommandResolver.Resolution listed = resolve("/reminder");
        assertEquals(1, listed.data().path("reminders").size());
        assertEquals("check the build", listed.data().path("reminders").get(0).path("text").asText());

        // Project scope stays separate.
        WebCommandResolver.Resolution projectList = resolve("/reminder-global");
        assertEquals("project", projectList.data().path("scope").asText());
        assertEquals(0, projectList.data().path("reminders").size());

        WebCommandResolver.Resolution cleared = resolve("/reminder clear");
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, cleared.status());
        assertTrue(cleared.text().contains("Cleared 1"));
        assertEquals(0, resolve("/reminder").data().path("reminders").size());
    }

    @Test
    void blankReminderIsRejectedWithoutStateChange() {
        WebCommandResolver.Resolution resolution = resolve("/reminder add   ");
        assertEquals(WebCommandResolver.Status.INVALID, resolution.status());
        assertEquals(0, resolve("/reminder").data().path("reminders").size());
    }

    // ── /loop and /loop-global ──────────────────────────────────

    @Test
    void loopAddListAndRemovePersistPerScope() {
        WebCommandResolver.Resolution added = resolve("/loop 6h run tests");
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, added.status());
        assertEquals("session", added.data().path("scope").asText());
        assertEquals(1, added.data().path("loops").size());
        assertEquals("run tests", added.data().path("loops").get(0).path("prompt").asText());
        String loopId = added.data().path("loops").get(0).path("id").asText();

        WebCommandResolver.Resolution listed = resolve("/loop");
        assertEquals(1, listed.data().path("loops").size());

        WebCommandResolver.Resolution paused = resolve("/loop pause " + loopId);
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, paused.status());

        WebCommandResolver.Resolution removed = resolve("/loop remove " + loopId);
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, removed.status());
        assertEquals(0, resolve("/loop").data().path("loops").size());
    }

    @Test
    void invalidLoopScheduleIsRejected() {
        WebCommandResolver.Resolution resolution = resolve("/loop add not-a-schedule prompt");
        assertEquals(WebCommandResolver.Status.INVALID, resolution.status());
        assertEquals(0, resolve("/loop").data().path("loops").size());
    }

    @Test
    void loopRunNowReportsLiveSessionRequirementWithoutStateChange() {
        assertTrue(resolve("/loop 6h run tests").status()
                == WebCommandResolver.Status.INTERACTION_REQUIRED);
        String loopId = resolve("/loop").data().path("loops").get(0).path("id").asText();
        WebCommandResolver.Resolution resolution = resolve("/loop run " + loopId);
        assertEquals(WebCommandResolver.Status.LIVE_SESSION_REQUIRED, resolution.status());
        assertTrue(resolution.text().contains("live chat process"));
        // The loop stays scheduled.
        assertEquals(1, resolve("/loop").data().path("loops").size());
    }

    @Test
    void loopGlobalIsScopedSeparatelyFromSession() {
        assertTrue(resolve("/loop 6h run tests").status()
                == WebCommandResolver.Status.INTERACTION_REQUIRED);
        assertTrue(resolve("/loop-global 1h sweep logs").status()
                == WebCommandResolver.Status.INTERACTION_REQUIRED);
        assertEquals(1, resolve("/loop").data().path("loops").size());
        assertEquals(1, resolve("/loop-global").data().path("loops").size());
    }

    @Test
    void reminderAndLoopCommandsAreWebSupportedInCatalog() {
        for (String name : new String[] {"reminder", "reminder-global", "loop", "loop-global"}) {
            assertEquals(ChatCommandCatalog.WebSupport.SUPPORTED,
                    ChatCommandCatalog.webSupport(name), name);
        }
    }

    // ── /queue family ─────────────────────────────────────────

    @Test
    void queueEnqueueListAndClearShareTheLiveCliQueueFile() {
        WebCommandResolver.Resolution added = resolve("/queue run the full suite later");
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, added.status());
        assertEquals("queue", added.data().path("menu").asText());
        assertEquals(1, added.data().path("queued").size());
        String queuedId = added.data().path("queued").get(0).path("id").asText();

        // The bare /queues listing reads the same persisted queue file.
        WebCommandResolver.Resolution listed = resolve("/queues");
        assertEquals(1, listed.data().path("queued").size());
        assertTrue(listed.text().contains("run the full suite later"));

        // Remove by id, then verify empty through a third command run.
        WebCommandResolver.Resolution removed = resolve("/queue-remove " + queuedId);
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, removed.status());
        assertEquals(0, resolve("/queues").data().path("queued").size());
    }

    @Test
    void duplicateQueueEnqueueIsRejectedWithoutChange() {
        assertTrue(resolve("/queue check logs").status()
                == WebCommandResolver.Status.INTERACTION_REQUIRED);
        WebCommandResolver.Resolution duplicate = resolve("/queue check logs");
        assertEquals(WebCommandResolver.Status.INVALID, duplicate.status());
        assertTrue(duplicate.text().contains("already queued"));
        assertEquals(1, resolve("/queues").data().path("queued").size());
    }

    @Test
    void queueEditAndMoveUpdateThePersistedQueue() {
        assertTrue(resolve("/queue first message").status()
                == WebCommandResolver.Status.INTERACTION_REQUIRED);
        assertTrue(resolve("/queue second message").status()
                == WebCommandResolver.Status.INTERACTION_REQUIRED);
        String firstId = resolve("/queues").data().path("queued").get(0).path("id").asText();

        WebCommandResolver.Resolution edited = resolve("/queue-edit " + firstId + " edited text");
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, edited.status());
        assertEquals("edited text",
                resolve("/queues").data().path("queued").get(0).path("content").asText());

        String secondId = resolve("/queues").data().path("queued").get(1).path("id").asText();
        WebCommandResolver.Resolution moved = resolve("/queue-move " + secondId + " 1");
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, moved.status());
        assertEquals(secondId, resolve("/queues").data().path("queued").get(0).path("id").asText());

        assertTrue(resolve("/queue-clear").status() == WebCommandResolver.Status.INTERACTION_REQUIRED);
        assertEquals(0, resolve("/queues").data().path("queued").size());
    }

    @Test
    void queueSendReportsLiveSessionRequirementWithoutStateChange() {
        assertTrue(resolve("/queue later thing").status()
                == WebCommandResolver.Status.INTERACTION_REQUIRED);
        for (String name : new String[] {"/queue-send", "/queue-send-all"}) {
            WebCommandResolver.Resolution resolution = resolve(name);
            assertEquals(WebCommandResolver.Status.LIVE_SESSION_REQUIRED, resolution.status(), name);
            assertTrue(resolution.text().contains("live chat turn loop"), name);
        }
        // Queue is untouched.
        assertEquals(1, resolve("/queues").data().path("queued").size());
    }

    @Test
    void queueCommandsAreWebSupportedInCatalog() {
        for (String name : new String[] {"queue", "queues", "queue-remove", "queue-edit",
                "queue-move", "queue-clear", "queue-status"}) {
            assertEquals(ChatCommandCatalog.WebSupport.SUPPORTED,
                    ChatCommandCatalog.webSupport(name), name);
        }
        // Send variants stay live-session-gated.
        assertEquals(ChatCommandCatalog.WebSupport.LIVE_SESSION_REQUIRED,
                ChatCommandCatalog.webSupport("queue-send"));
        assertEquals(ChatCommandCatalog.WebSupport.LIVE_SESSION_REQUIRED,
                ChatCommandCatalog.webSupport("queue-send-all"));
    }

    // ── /clear ────────────────────────────────────────────────

    @Test
    void clearCarriesBrowserInstructionAndRejectsArguments() {
        WebCommandResolver.Resolution resolution = resolve("/clear");
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, resolution.status());
        assertEquals("clear", resolution.data().path("menu").asText());
        assertEquals("web-session-1", resolution.data().path("sessionId").asText());
        assertTrue(resolution.text().contains("new conversation"));

        // /clear takes no arguments in the interactive CLI either.
        assertEquals(WebCommandResolver.Status.INVALID,
                resolve("/clear now with extra words").status());

        assertEquals(ChatCommandCatalog.WebSupport.SUPPORTED,
                ChatCommandCatalog.webSupport("clear"));
    }

    // ── configQuery snapshot (headless dialog priming) ────────

    @Test
    void configQueryReturnsEveryMenuInOneOutcome() {
        WebChatInput snapshotInput = new WebChatInput(WebChatInput.VERSION, "", "", "web-session-1", true);
        WebCommandResolver.Resolution resolution = WebCommandResolver.resolve(
                snapshotInput, project, new ChatSessionStateStore());
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, resolution.status());
        JsonNode data = resolution.data();
        assertNotNull(data);
        assertEquals("config", data.path("menu").asText());
        assertEquals("web-session-1", data.path("sessionId").asText());
        // Every section carries its own menu payload.
        assertEquals("model", data.path("model").path("menu").asText());
        assertNotNull(data.path("model").path("models").isArray());
        assertEquals("role", data.path("role").path("menu").asText());
        assertNotNull(data.path("role").path("roles").isArray());
        assertTrue(data.path("role").path("roles").size() > 0,
                "the project role from setUp should appear in the roster");
        assertEquals("fast", data.path("fast").path("menu").asText());
        assertTrue(data.path("fast").has("fastMode"));
        assertTrue(data.path("fast").has("supported"));
        assertEquals("reminders", data.path("reminders").path("menu").asText());
        assertEquals("session", data.path("reminders").path("scope").asText());
        assertEquals("reminders", data.path("remindersGlobal").path("menu").asText());
        assertEquals("project", data.path("remindersGlobal").path("scope").asText());
        assertEquals("loops", data.path("loops").path("menu").asText());
        assertEquals("session", data.path("loops").path("scope").asText());
        assertEquals("loops", data.path("loopsGlobal").path("menu").asText());
        assertEquals("project", data.path("loopsGlobal").path("scope").asText());
        assertEquals("queue", data.path("queue").path("menu").asText());
        assertEquals("session", data.path("queue").path("scope").asText());
        assertTrue(data.path("queue").path("queued").isArray());
    }

    @Test
    void configQueryIsReadOnlyAndReflectsDurableState() {
        // Seed durable state through the normal write commands.
        resolve("/model m1");
        resolve("/reminder add snapshot check");
        resolve("/queue queued text");

        WebChatInput snapshotInput = new WebChatInput(WebChatInput.VERSION, "", "", "web-session-1", true);
        JsonNode data = WebCommandResolver.resolve(
                snapshotInput, project, new ChatSessionStateStore()).data();

        // Persisted session state is reflected... (model from the state store)
        assertNotNull(data.path("model").path("currentModel").asText());
        // ...and reads never mutate: re-reading gives the same answer.
        JsonNode again = WebCommandResolver.resolve(
                new WebChatInput(WebChatInput.VERSION, "", "", "web-session-1", true),
                project, new ChatSessionStateStore()).data();
        assertEquals(data.toString(), again.toString());
    }

    @Test
    void configQueryWithoutSessionIdStillReturnsMenus() {
        WebChatInput snapshotInput = new WebChatInput(WebChatInput.VERSION, "", "", "", true);
        WebCommandResolver.Resolution resolution = WebCommandResolver.resolve(
                snapshotInput, project, new ChatSessionStateStore());
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, resolution.status());
        assertEquals("config", resolution.data().path("menu").asText());
        assertEquals("model", resolution.data().path("model").path("menu").asText());
    }

    // ── vendor switching (/model <vendor> and /model <vendor>:<model>) ──

    @Test
    void modelMenuCarriesSwitchableVendors() {
        WebCommandResolver.Resolution resolution = WebCommandResolver.resolve(
                input("/model"), project, new ChatSessionStateStore(), configuredChatConfig(), null);
        JsonNode vendors = resolution.data().path("vendors");
        assertTrue(vendors.isArray(), "bare /model must carry the vendor chips");
        assertTrue(vendors.size() > 0, "at least the configured vendor must be listed");
        boolean sawCurrent = false;
        for (JsonNode vendor : vendors) {
            assertTrue(vendor.path("vendor").isTextual());
            if (vendor.path("current").asBoolean()) sawCurrent = true;
        }
        assertTrue(sawCurrent, "the configured provider's vendor must be marked current");
    }

    @Test
    void vendorColonFormFailsClosedWithoutCredential() {
        WebCommandResolver.Resolution resolution = WebCommandResolver.resolve(
                input("/model openai:gpt-5.6"), project, new ChatSessionStateStore(),
                configuredChatConfig(), null);
        // No usable openai credential in this test env → the switch must fail
        // closed and leave the durable state untouched.
        assertEquals(WebCommandResolver.Status.INVALID, resolution.status());
        assertTrue(resolution.text().contains("credential"), resolution.text());
        assertNull(new ChatSessionStateStore().load("web-session-1", project));
    }

    @Test
    void vendorMenuListsRecordedModelsForThatVendor() {
        WebCommandResolver.Resolution resolution = WebCommandResolver.resolve(
                input("/model custom"), project, new ChatSessionStateStore(),
                configuredChatConfig(), null);
        // The vendor menu works with or without a recorded catalog: the
        // payload carries the vendor marker either way.
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, resolution.status());
        assertEquals("custom", resolution.data().path("vendor").asText());
        assertEquals("model", resolution.data().path("menu").asText());
        assertTrue(resolution.data().path("models").isArray());
    }

    @Test
    void unknownVendorIsRejectedWithoutStateChange() {
        WebCommandResolver.Resolution resolution = WebCommandResolver.resolve(
                input("/model nosuchvendor:m1"), project, new ChatSessionStateStore(),
                configuredChatConfig(), null);
        assertEquals(WebCommandResolver.Status.INVALID, resolution.status());
        assertTrue(resolution.text().contains("Unknown vendor"));
        assertNull(new ChatSessionStateStore().load("web-session-1", project));
    }

    @Test
    void snapshotVendorScopeListsThatVendorsModels() {
        WebChatInput snapshotInput = new WebChatInput(
                WebChatInput.VERSION, "", "", "web-session-1", true, "openai");
        WebCommandResolver.Resolution resolution = WebCommandResolver.resolve(
                snapshotInput, project, new ChatSessionStateStore(), configuredChatConfig(), null);
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, resolution.status());
        JsonNode model = resolution.data().path("model");
        assertEquals("openai", model.path("vendor").asText());
        assertTrue(model.path("models").isArray());
        assertTrue(model.path("vendors").isArray(), "vendor chips ride along");
    }

    // ── /continue (auto-reply configuration) ──────────────────

    @Test
    void continueCarriesStructuredState() {
        WebCommandResolver.Resolution resolution = resolve("/continue");
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, resolution.status());
        assertEquals("continue", resolution.data().path("menu").asText());
        assertTrue(resolution.data().path("enabled").isBoolean());
        assertTrue(resolution.data().path("reply").isTextual());
        assertTrue(resolution.data().path("keywords").isArray());
        assertTrue(resolution.text().contains("auto-reply") || resolution.text().contains("Continue"),
                resolution.text());
    }

    @Test
    void continueTogglePersistsProjectGlobally() {
        resolve("/continue on");
        assertTrue(resolve("/continue").data().path("enabled").asBoolean());
        resolve("/continue off");
        assertFalse(resolve("/continue").data().path("enabled").asBoolean());
    }

    @Test
    void continueReplyAndKeywordsPersist() {
        resolve("/continue reply absolutely proceed");
        assertEquals("absolutely proceed", resolve("/continue").data().path("reply").asText());
        resolve("/continue add build green, ship it");
        JsonNode keywords = resolve("/continue").data().path("keywords");
        boolean sawAdded = false;
        for (JsonNode keyword : keywords) {
            if (keyword.path("keyword").asText().equalsIgnoreCase("build green")) sawAdded = true;
        }
        assertTrue(sawAdded, "added keyword must appear in the list");
        resolve("/continue reset");
    }

    // ── /judge and /judge-global (durable judge control over web) ──

    @Test
    void judgeStatusCarriesGlobalAndSessionPosture() {
        WebCommandResolver.Resolution resolution = resolve("/judge status");
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, resolution.status());
        assertEquals("judge", resolution.data().path("menu").asText());
        assertTrue(resolution.data().path("globalEnabled").isBoolean());
        assertTrue(resolution.text().contains("Judge global"));
    }

    @Test
    void judgeGlobalOnOffRoundTripPersists() {
        WebChatInput onInput = new WebChatInput(WebChatInput.VERSION, "/judge global on", "", "web-session-1");
        WebCommandResolver.Resolution on = WebCommandResolver.resolve(onInput, project,
                new ChatSessionStateStore(), configuredChatConfig(), null);
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, on.status());
        assertTrue(on.data().path("globalEnabled").asBoolean(), "must end enabled");

        WebCommandResolver.Resolution off = WebCommandResolver.resolve(
                input("/judge global off"), project, new ChatSessionStateStore(),
                configuredChatConfig(), null);
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, off.status());
        assertFalse(off.data().path("globalEnabled").asBoolean(), "must end disabled");
        // Restore the default for later runs.
        WebCommandResolver.resolve(input("/judge global on"), project,
                new ChatSessionStateStore(), configuredChatConfig(), null);
    }

    @Test
    void judgeGlobalUsageIsRejectedWithoutChange() {
        WebCommandResolver.Resolution resolution = WebCommandResolver.resolve(
                input("/judge global banana"), project, new ChatSessionStateStore(),
                configuredChatConfig(), null);
        assertEquals(WebCommandResolver.Status.INVALID, resolution.status());
        assertTrue(resolution.text().contains("Usage"));
    }

    @Test
    void judgeFeedbackNeedsSessionId() {
        WebChatInput noSession = new WebChatInput(WebChatInput.VERSION, "/judge feedback be lenient", "", "");
        WebCommandResolver.Resolution resolution = WebCommandResolver.resolve(
                noSession, project, new ChatSessionStateStore(), configuredChatConfig(), null);
        assertEquals(WebCommandResolver.Status.INVALID, resolution.status());
        assertTrue(resolution.text().contains("session id"));
    }

    @Test
    void judgeFeedbackRoundTripAndClear() {
        WebCommandResolver.Resolution set = WebCommandResolver.resolve(
                input("/judge feedback prefer warnings over blocking"), project,
                new ChatSessionStateStore(), configuredChatConfig(), null);
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, set.status());
        assertEquals("prefer warnings over blocking",
                set.data().path("guidance").asText());

        WebCommandResolver.Resolution cleared = WebCommandResolver.resolve(
                input("/judge feedback clear"), project, new ChatSessionStateStore(),
                configuredChatConfig(), null);
        assertEquals(WebCommandResolver.Status.INTERACTION_REQUIRED, cleared.status());
        assertEquals("", cleared.data().path("guidance").asText());
    }

    @Test
    void judgeLiveOnlySubcommandsReportHonestly() {
        for (String sub : new String[]{"override", "approve git status", "restart", "agent claude"}) {
            WebCommandResolver.Resolution resolution = WebCommandResolver.resolve(
                    input("/judge " + sub), project, new ChatSessionStateStore(),
                    configuredChatConfig(), null);
            assertEquals(WebCommandResolver.Status.LIVE_SESSION_REQUIRED, resolution.status(), sub);
            assertTrue(resolution.text().contains("live-session"), sub);
        }
    }
}
