package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.agent.AgentRegistry;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.hooks.HookManager;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.permission.PermissionService.PermissionLevel;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static ai.kompile.cli.main.chat.exec.WebCommandResolver.Status.*;
import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class WebSafetySettingsTest {
    @TempDir Path project;
    private final String session = "safety-" + UUID.randomUUID();
    private ToolContext principal() {
        var permissions = new PermissionService();
        permissions.setPromptListener(ignored -> permissions.submitPromptResponse("deny"));
        return new ToolContext(session, new AgentRegistry().get("coder"), permissions, project, null);
    }
    private WebCommandResolver.Resolution save(String overrides) {
        return WebPermissionSettings.resolve("save {\"confirmed\":true,\"overrides\":" + overrides + "}", principal(), true);
    }
    @Test void multiCommandWebEditsAreConfirmedOrderedAtomicAndNeverExecuteCommands() throws Exception {
        Path marker = project.resolve("never-executed");
        var request = JsonUtils.standardMapper().createObjectNode().put("action", "add").put("id", "multi")
                .put("event", "PreToolUse").put("scope", "project");
        var commands = request.putArray("hooks");
        commands.addObject().put("type", "command").put("command", "touch '" + marker + "'").put("timeout", 12);
        commands.addObject().put("command", "exit 0").put("timeout", 34);
        assertEquals(INVALID, WebHooksSettings.resolve("apply " + request, principal(), true).status());
        request.put("confirmed", true);
        assertEquals(COMPLETED, WebHooksSettings.resolve("apply " + request, principal(), true).status());
        var manager = new HookManager(project);
        assertEquals(2, manager.load().get(0).commands().size()); assertFalse(Files.exists(marker));
        request.put("action", "edit");
        var reordered = request.putArray("hooks");
        reordered.add(commands.get(1)); reordered.add(commands.get(0));
        var saved = WebHooksSettings.resolve("apply " + request, principal(), true);
        assertEquals(COMPLETED, saved.status());
        assertEquals("exit 0", saved.data().path("hooks").get(0).path("hooks").get(0).path("command").asText());
        assertEquals(12, saved.data().path("hooks").get(0).path("hooks").get(1).path("timeout").asInt());
        String original = Files.readString(manager.path("project"));
        assertEquals(INVALID, WebHooksSettings.resolve("apply " + request, principal(), false).status());
        assertEquals(original, Files.readString(manager.path("project")));
        ((com.fasterxml.jackson.databind.node.ObjectNode) reordered.get(1)).put("timeout", 0);
        assertEquals(INVALID, WebHooksSettings.resolve("apply " + request, principal(), true).status());
        assertEquals(original, Files.readString(manager.path("project")));
        assertEquals(INVALID, WebHooksSettings.resolve("apply " + request, principal(), false).status());
        assertFalse(Files.exists(marker));
    }

    @Test void readingSettingsDoesNotPersistPermissionOrHookDefaults() throws Exception {
        assertEquals(INTERACTION_REQUIRED, WebPermissionSettings.resolve("", principal(), true).status());
        assertFalse(Files.exists(WebPermissionSettings.path(session)));
        assertEquals(INTERACTION_REQUIRED, WebHooksSettings.resolve("", principal(), true).status());
        assertFalse(Files.exists(new HookManager(project).path("project")));
    }
    @Test void savedLevelsReachFreshWorkersAndDoNotOverrideRoleCeilings() {
        assertEquals(COMPLETED, save("{\"bash\":\"deny\",\"read\":\"ask\"}").status());
        var fresh = new PermissionService(); WebPermissionSettings.apply(session, project, fresh);
        var coder = new AgentRegistry().get("coder");
        assertEquals(PermissionLevel.DENY, fresh.getEffectiveLevel(coder, "bash"));
        assertEquals(PermissionLevel.ASK, fresh.getEffectiveLevel(coder, "read"));
        var role = coder.toBuilder().permissionOverrides(Map.of("read", PermissionLevel.DENY)).build();
        assertEquals(PermissionLevel.DENY, fresh.getEffectiveLevel(role, "read"));
        assertEquals(COMPLETED, save("{}").status());
        var reset = new PermissionService(); reset.setAutoApproveAll(true);
        WebPermissionSettings.apply(session, project, reset);
        assertEquals(new PermissionService().getEffectiveLevel(coder, "bash"), reset.getEffectiveLevel(coder, "bash"));
        assertEquals(PermissionService.PermissionResult.DENIED, reset.check(role, "read", "role boundary"),
                "restoring defaults must not re-arm launcher auto-approval over role rules");
    }
    @Test void selfImposedReadDenialCanBeInspectedAndRestoredByTheOperator() throws Exception {
        assertEquals(COMPLETED, save("{\"read\":\"deny\"}").status());
        var context = principal(); WebPermissionSettings.apply(session, project, context.getPermissionService());
        assertTrue(WebPermissionSettings.snapshot(context, true).path("canManage").asBoolean());
        assertEquals(COMPLETED, WebPermissionSettings.resolve("save {\"confirmed\":true,\"overrides\":{}}", context, true).status());
    }
    @Test void invalidOrUnconfirmedPermissionSavesAreAtomic() throws Exception {
        save("{\"bash\":\"deny\"}");
        String original = Files.readString(WebPermissionSettings.path(session));
        for (String payload : new String[]{"{\"overrides\":{}}", "{\"confirmed\":false,\"overrides\":{}}",
                "{\"confirmed\":true,\"overrides\":{\"bash\":\"sometimes\"}}", "{\"confirmed\":true,\"overrides\":{\"unknown\":\"allow\"}}",
                "{\"confirmed\":true,\"overrides\":{},\"secret-test-key\":true}", "{\"confirmed\":true,\"overrides\":{}} {}"}) {
            var result = WebPermissionSettings.resolve("save " + payload, principal(), true);
            assertEquals(INVALID, result.status(), payload);
            assertFalse(result.text().contains("secret-test-key"));
            assertEquals(original, Files.readString(WebPermissionSettings.path(session)));
        }
    }
    @Test void restrictedOrBlockedPrincipalsCannotChangeSafetyConfiguration() {
        var context = principal();
        var readOnly = context.getAgent().toBuilder().enabledTools(Set.of("read")).build();
        var restricted = new ToolContext(session, readOnly, context.getPermissionService(), project, null);
        assertEquals(INVALID, WebPermissionSettings.resolve("save {\"confirmed\":true,\"overrides\":{}}", restricted, true).status());
        assertEquals(INVALID, WebPermissionSettings.resolve("save {\"confirmed\":true,\"overrides\":{}}", context, false).status());
        assertEquals(INVALID, WebHooksSettings.resolve(hookRequest("add", null, true), restricted, true).status());
        assertEquals(INVALID, WebHooksSettings.resolve(hookRequest("add", null, true), context, false).status());
    }
    @Test void explicitRoleDenialsCannotBeBypassedByAutoApproveFlags() {
        var context = principal();
        var role = context.getAgent().toBuilder().permissionOverrides(Map.of("hooks", PermissionLevel.DENY, "permissions", PermissionLevel.DENY)).build();
        var denied = new ToolContext(session, role, context.getPermissionService(), project, null);
        denied.setAutoApproveAll(true);
        assertEquals(INVALID, WebHooksSettings.resolve(hookRequest("add", null, true), denied, true).status());
        assertEquals(INVALID, WebPermissionSettings.resolve("save {\"confirmed\":true,\"overrides\":{}}", denied, true).status());
    }

    @Test void workspaceMismatchAndSymlinkedPermissionStoresFailClosed() throws Exception {
        save("{\"bash\":\"deny\"}");
        Path other = Files.createTempDirectory(project, "other");
        assertThrows(IllegalStateException.class, () -> WebPermissionSettings.apply(session, other, new PermissionService()));
        Path target = WebPermissionSettings.path(session);
        Files.delete(target);
        Path foreign = Files.writeString(project.resolve("foreign.json"), "not-a-permission-store");
        Files.createSymbolicLink(target, foreign);
        assertThrows(IllegalStateException.class, () -> WebPermissionSettings.apply(session, project, new PermissionService()));
        assertEquals(INVALID, save("{}").status());
        assertEquals("not-a-permission-store", Files.readString(foreign));
    }
    private String hookRequest(String action, String id, boolean confirmed) {
        var json = JsonUtils.standardMapper().createObjectNode().put("action", action).put("scope", "project").put("confirmed", confirmed);
        if (id != null) json.put("id", id);
        if (action.equals("add") || action.equals("edit")) json.put("event", "PreToolUse").put("matcher", "^read$")
                .put("command", "touch " + project.resolve("must-not-execute")).put("timeout", 10);
        return "apply " + json;
    }
    @Test void confirmedHookLifecyclePersistsWithoutRunningAnyCommand() throws Exception {
        assertEquals(INVALID, WebHooksSettings.resolve(hookRequest("add", null, false), principal(), true).status());
        var added = WebHooksSettings.resolve(hookRequest("add", null, true), principal(), true);
        assertEquals(COMPLETED, added.status(), added.text());
        String id = added.data().path("hooks").get(0).path("id").asText();
        assertEquals(COMPLETED, WebHooksSettings.resolve(hookRequest("disable", id, true), principal(), true).status());
        assertFalse(WebHooksSettings.snapshot(principal(), true).path("hooks").get(0).path("enabled").asBoolean());
        assertEquals(COMPLETED, WebHooksSettings.resolve(hookRequest("enable", id, true), principal(), true).status());
        assertEquals(COMPLETED, WebHooksSettings.resolve(hookRequest("edit", id, true), principal(), true).status());
        assertFalse(Files.exists(project.resolve("must-not-execute")));
        assertEquals(COMPLETED, WebHooksSettings.resolve(hookRequest("delete", id, true), principal(), true).status());
        assertEquals(0, WebHooksSettings.snapshot(principal(), true).path("hooks").size());
    }
    @Test void globalHooksNeedConfirmationAndDoNotGrantTheCallerFutureAutoApproval() throws Exception {
        Path file = new HookManager(project).path("global");
        byte[] original = Files.exists(file) ? Files.readAllBytes(file) : null;
        ToolContext context = principal();
        try {
            assertEquals(INVALID, WebHooksSettings.resolve(hookRequest("add", null, false).replace("\"project\"", "\"global\""), context, true).status());
            var saved = WebHooksSettings.resolve(hookRequest("add", null, true).replace("\"project\"", "\"global\""), context, true);
            assertEquals(COMPLETED, saved.status(), saved.text());
            assertTrue(Files.exists(file));
            assertEquals("global", saved.data().path("hooks").get(0).path("scope").asText());
            assertFalse(context.isAutoApproveAll(), "confirmation applies only to the forked management call");
            assertFalse(Files.exists(project.resolve("must-not-execute")));
        } finally {
            if (original == null) Files.deleteIfExists(file); else Files.write(file, original);
        }
    }

    @Test void hookValidationAndStoreContainmentPreserveExistingFiles() throws Exception {
        var context = principal(); WebHooksSettings.resolve(hookRequest("add", null, true), context, true);
        Path file = new HookManager(project).path("project");
        String original = Files.readString(file);
        assertEquals(INVALID, WebHooksSettings.resolve("apply {\"action\":\"delete\",\"id\":\"missing\",\"confirmed\":true}", context, true).status());
        assertEquals(INVALID, WebHooksSettings.resolve("apply {\"action\":\"add\",\"confirmed\":true,\"apiKey\":\"do-not-echo\"}", context, true).status());
        assertEquals(original, Files.readString(file));
        Files.delete(file); Path foreign = Files.writeString(project.resolve("foreign-hooks.json"), original);
        Files.createSymbolicLink(file, foreign);
        assertEquals(INVALID, WebHooksSettings.resolve(hookRequest("add", null, true), context, true).status());
        assertEquals(original, Files.readString(foreign));
    }
    @Test void resolverMenusStayModelFreeAndSavedPermissionsAffectOtherCommandPrincipals() {
        var input = new WebChatInput(1, "/permissions", "", session);
        var result = WebCommandResolver.resolve(input, project);
        assertEquals(INTERACTION_REQUIRED, result.status()); assertNull(result.modelPrompt());
        save("{\"read\":\"deny\"}");
        assertEquals(INVALID, WebCommandResolver.resolve(new WebChatInput(1, "/hooks", "", session), project).status());
        var blocked = new WebCommandResolver.RunPermissions("coder", null, true, true);
        var mutation = new WebChatInput(1, "/permissions save {\"confirmed\":true,\"overrides\":{}}", "", session);
        assertEquals(INVALID, WebCommandResolver.resolve(mutation, project, blocked).status());
    }
}
