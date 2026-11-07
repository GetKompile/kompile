package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.ChatCommandCatalog;
import ai.kompile.cli.main.chat.SessionModelLadder;
import ai.kompile.cli.main.chat.config.SubagentConfig;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static ai.kompile.cli.main.chat.exec.WebCommandResolver.Status.*;
import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class WebHarnessSettingsTest {
    @TempDir Path project;
    @org.junit.jupiter.api.BeforeEach void resetSettings() throws Exception {
        // TemporaryUserHome is class-scoped; individual cases must not share saved policy.
        Files.deleteIfExists(SubagentConfig.path());
        Files.deleteIfExists(SessionModelLadder.sessionPath(SESSION));
    }

    private static final String SESSION = "web-harness-settings";
    private static final String ROUTE = """
            {"provider":"anthropic","model":"fallback-model","credentialName":"saved-account",
             "authenticationMethod":"native","thinking":"high"}
            """;

    private WebCommandResolver.Resolution resolve(String command) {
        return WebCommandResolver.resolve(new WebChatInput(WebChatInput.VERSION, command, "", SESSION), project);
    }

    @Test void subagentMenuIsAvailableWithoutTerminalOrModel() throws Exception {
        assertEquals(ChatCommandCatalog.WebSupport.SUPPORTED, ChatCommandCatalog.webSupport("subagent-config"));
        var result = resolve("/subagent-config");
        assertEquals(INTERACTION_REQUIRED, result.status());
        assertNull(result.modelPrompt());
        assertEquals(-1, result.data().path("maxConcurrent").asInt());
        assertFalse(Files.exists(SubagentConfig.path()), "Opening the menu must not save defaults");
    }

    @Test void browserEditsTheSamePolicyThatCliAndChildrenRead() throws Exception {
        assertEquals(COMPLETED, resolve("/subagent-config max 2").status());
        assertEquals(COMPLETED, resolve("/subagent-config ban vendor/blocked-*").status());
        assertEquals(2, SubagentConfig.load().maxConcurrent());
        assertEquals(List.of("vendor/blocked-*"), SubagentConfig.load().bannedModels());
        var saved = resolve("/subagent-config save {\"maxConcurrent\":0,\"bannedModels\":[\"other-*\"],"
                + "\"failoverEnabled\":false,\"failoverRungs\":[" + ROUTE + "]}");
        assertEquals(COMPLETED, saved.status(), saved.text());
        var policy = SubagentConfig.load();
        assertEquals(0, policy.maxConcurrent());
        assertFalse(policy.failoverEnabled());
        assertEquals("saved-account", policy.failoverRungs().get(0).credentialName());
        assertEquals("high", policy.failoverRungs().get(0).thinking());
    }

    @Test void invalidPolicyIsAtomicAndDoesNotEchoSecrets() throws Exception {
        resolve("/subagent-config max 3");
        String original = Files.readString(SubagentConfig.path());
        for (String json : List.of(
                "{\"maxConcurrent\":-2,\"bannedModels\":[],\"failoverEnabled\":true,\"failoverRungs\":[]}",
                "{\"maxConcurrent\":1,\"bannedModels\":[false],\"failoverEnabled\":true,\"failoverRungs\":[]}",
                "{\"maxConcurrent\":1,\"bannedModels\":[],\"failoverEnabled\":true,\"failoverRungs\":[{\"model\":\"m\",\"apiKey\":\"secret-test-key\"}]}",
                "{\"maxConcurrent\":1,\"bannedModels\":[],\"failoverEnabled\":true,\"failoverRungs\":[]} {}")) {
            var result = resolve("/subagent-config save " + json);
            assertEquals(INVALID, result.status(), json);
            assertFalse(result.text().contains("secret-test-key"));
            assertEquals(original, Files.readString(SubagentConfig.path()));
        }
    }

    @Test void leadAndChildFailoverAreIndependentAndSurviveReload() throws Exception {
        assertEquals(COMPLETED, resolve("/model failover save {\"enabled\":true,\"rungs\":[" + ROUTE + "]}").status());
        var lead = SessionModelLadder.load(SESSION, project);
        assertEquals("fallback-model", lead.rungs().get(0).model());
        assertEquals("native", lead.rungs().get(0).authenticationMethod());
        assertTrue(SubagentConfig.load().failoverRungs().isEmpty());
        assertEquals(COMPLETED, resolve("/subagent-config failover save {\"enabled\":false,\"rungs\":[{\"model\":\"child-only\"}]}").status());
        assertFalse(SubagentConfig.load().failoverEnabled());
        assertEquals("child-only", SubagentConfig.load().failoverRungs().get(0).model());
        assertEquals("fallback-model", SessionModelLadder.load(SESSION, project).rungs().get(0).model());
        assertEquals(INTERACTION_REQUIRED, resolve("/model ladder").status());
        assertEquals(COMPLETED, resolve("/model ladder off").status());
        assertFalse(SessionModelLadder.load(SESSION, project).enabled());
        assertEquals(COMPLETED, resolve("/model failover clear").status());
        assertTrue(SessionModelLadder.load(SESSION, project).isEmpty());
    }

    @Test void routesCanBeReorderedRemovedAndInvalidChangesLeaveStoreAlone() throws Exception {
        resolve("/model failover save {\"enabled\":true,\"rungs\":[{\"model\":\"first\"},{\"model\":\"second\"}]}");
        assertEquals(COMPLETED, resolve("/model failover order 1 2").status());
        assertEquals("second", SessionModelLadder.load(SESSION, project).rungs().get(0).model());
        assertEquals(COMPLETED, resolve("/model failover remove 2").status());
        String saved = Files.readString(SessionModelLadder.sessionPath(SESSION));
        for (String argument : List.of("remove 0", "order 1 9", "on unexpected",
                "save {\"enabled\":true,\"rungs\":[{\"model\":\"duplicate\"},{\"model\":\"duplicate\"}]}",
                "save {\"enabled\":true,\"rungs\":[{\"model\":\"m\",\"credentialName\":false}]}")) {
            assertEquals(INVALID, resolve("/model failover " + argument).status());
            assertEquals(saved, Files.readString(SessionModelLadder.sessionPath(SESSION)));
        }
    }

    @Test void settingsSnapshotIncludesPersistedPolicyAndBothLaddersWithoutASecret() {
        resolve("/subagent-config max 4");
        resolve("/model failover save {\"enabled\":true,\"rungs\":[" + ROUTE + "]}");
        WebModelCatalog.useDiscovery(config -> ai.kompile.cli.main.chat.config.ModelDiscovery.Result.success(List.of(), List.of()));
        try {
            var config = new ai.kompile.cli.main.chat.config.ChatConfig("openai", "not-for-the-browser", "primary", null);
            var snapshot = WebCommandResolver.resolve(new WebChatInput(WebChatInput.VERSION, "", "", SESSION, true),
                    project, new ChatSessionStateStore(), config);
            assertEquals(INTERACTION_REQUIRED, snapshot.status());
            assertEquals(4, snapshot.data().path("subagents").path("maxConcurrent").asInt());
            assertTrue(snapshot.data().path("subagents").path("failover").path("rungs").isEmpty());
            assertEquals("saved-account", snapshot.data().path("failover").path("rungs").get(0).path("credentialName").asText());
            assertFalse(snapshot.data().toString().contains("not-for-the-browser"));
        } finally {
            WebModelCatalog.useDiscovery(null);
        }
    }

    @Test void writesRequireSessionButReadingDoesNotCreateOne() {
        assertEquals(INTERACTION_REQUIRED, WebHarnessSettings.ladder("", null, project, false).status());
        assertEquals(INVALID, WebHarnessSettings.ladder("off", null, project, false).status());
    }
}
