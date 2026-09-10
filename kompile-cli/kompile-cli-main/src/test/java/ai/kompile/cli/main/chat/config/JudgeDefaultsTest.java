package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JudgeDefaultsTest {
    @TempDir Path temp;
    private String home;
    private Path project;

    @BeforeEach void isolate() {
        home = System.getProperty("user.home");
        System.setProperty("user.home", temp.resolve("home").toString());
        project = temp.resolve("project");
    }

    @AfterEach void restore() { System.setProperty("user.home", home); }

    @Test void vendorsRoundTripIndependentlyAcrossRoutesAndScopes() throws Exception {
        JudgeDefaults.save(ChatConfig.Scope.GLOBAL, project, "openai-codex",
                new JudgeDefaults.Selection("global-openai", "low"));
        JudgeDefaults.save(ChatConfig.Scope.GLOBAL, project, "claude",
                new JudgeDefaults.Selection("global-anthropic", "medium"));
        JudgeDefaults.save(ChatConfig.Scope.PROJECT, project, " OPENAI ",
                new JudgeDefaults.Selection("project-openai", "none"));

        assertEquals("project-openai", JudgeDefaults.configured("codex-cli", project).model());
        assertEquals("global-anthropic", JudgeDefaults.configured("anthropic", project).model());
        assertEquals("global-openai", JudgeDefaults.configured("openai", temp.resolve("other-project")).model());
        assertNull(JudgeDefaults.configured("gemini", project));
        String json = Files.readString(JudgeDefaults.configPath(ChatConfig.Scope.PROJECT, project));
        assertFalse(json.contains("apiKey"));
        assertFalse(Files.exists(ChatConfig.projectConfigPath(project)));
    }

    @Test void savedThinkingIsModelSpecificAndExplicitModelWins() throws Exception {
        JudgeDefaults.save(ChatConfig.Scope.PROJECT, project, "openai",
                new JudgeDefaults.Selection("gpt-5.6-sol", "high"));
        assertEquals(new JudgeDefaults.Selection("gpt-5.6-sol", "high"),
                JudgeDefaults.resolve("openai-codex", project, null, "main-model"));
        assertEquals(new JudgeDefaults.Selection("o3", "low"),
                JudgeDefaults.resolve("openai", project, "o3", "main-model"));
        assertEquals(new JudgeDefaults.Selection("unknown", null),
                JudgeDefaults.resolve("openai", project, "unknown", "main-model"));
    }

    @Test void automaticThinkingUsesLowestAdvertisedValueRatherThanAlwaysLow() {
        assertEquals("none", JudgeDefaults.lowestThinking("openai", "gpt-5.6", null));
        assertEquals("low", JudgeDefaults.lowestThinking("openai", "o3", null));
        assertNull(JudgeDefaults.lowestThinking("custom", "unknown", null));
        var live = ModelDiscovery.Result.success(List.of(new LiveModelDiscovery.Model(
                "live-model", List.of("high", "low", "minimal"))), List.of());
        assertEquals("minimal", JudgeDefaults.lowestThinking("openai", "live-model", live));
    }

    @Test void unspecifiedThinkingGetsLowestAndUnknownVariantsDoNotInventAnOrdering() throws Exception {
        JudgeDefaults.save(ChatConfig.Scope.PROJECT, project, "openai",
                new JudgeDefaults.Selection("gpt-5.6", null));
        assertEquals("none", JudgeDefaults.resolve("openai", project, null, null).thinking());
        assertNull(JudgeDefaults.lowestThinking(List.of(
                new SetupWizard.ThinkingOption("turbo", "Turbo"), new SetupWizard.ThinkingOption("", "Default"))));
    }

    @Test void sharedVendorDefaultRespectsDifferentRouteCapabilities() throws Exception {
        JudgeDefaults.save(ChatConfig.Scope.PROJECT, project, "openai",
                new JudgeDefaults.Selection("gpt-5.6-sol", "none"));
        assertEquals("none", JudgeDefaults.resolve("openai", project, null, null).thinking());
        assertEquals("low", JudgeDefaults.resolve("codex", project, null, null).thinking());
        assertEquals("low", JudgeDefaults.lowestThinking("openai-codex", "gpt-5.6-sol", null));
    }

    @Test void liveSelectionOnSameRouteOutranksOlderDocumentedFallback() throws Exception {
        JudgeDefaults.save(ChatConfig.Scope.PROJECT, project, "openai-codex",
                new JudgeDefaults.Selection("gpt-5.6-sol", "minimal"));
        assertEquals("minimal", JudgeDefaults.resolve("codex", project, null, null).thinking());
        assertEquals("none", JudgeDefaults.resolve("openai", project, null, null).thinking());
    }

    @Test void projectProfilesOverrideVendorDefaultsWithoutChangingOtherProjects() throws Exception {
        JudgeDefaults.save(ChatConfig.Scope.GLOBAL, project, "openai", new JudgeDefaults.Selection("global", null));
        JudgeDefaults.save(ChatConfig.Scope.PROJECT, project, "openai", new JudgeDefaults.Selection("project", null));
        ChatProfiles.save(project, ChatProfiles.captureJudge("quick", "openai", "o3", "high"), false);
        assertEquals("project", JudgeDefaults.resolve("openai", project, null, "fallback").model());
        ChatProfiles.activateJudge(project, "openai", "quick");
        assertEquals(new JudgeDefaults.Selection("o3", "high"),
                JudgeDefaults.resolve("openai", project, null, "fallback"));
        assertEquals(new JudgeDefaults.Selection("gpt-5.6", "none"),
                JudgeDefaults.resolve("openai", project, "gpt-5.6", "fallback"));
        assertEquals("global", JudgeDefaults.resolve("openai", temp.resolve("other"), null, "fallback").model());
        assertEquals("project", JudgeDefaults.vendorDefault("openai", project).model());
        ChatProfiles.deleteJudge(project, "openai", "quick");
        assertEquals("project", JudgeDefaults.resolve("openai", project, null, "fallback").model());
    }

    @Test void profilePreservesLiveThinkingProvenanceAndDefaultsToLowestWhenUnset() throws Exception {
        ChatProfiles.save(project, ChatProfiles.captureJudge("quick", "codex", "gpt-5.6-sol", "minimal"), false);
        ChatProfiles.activateJudge(project, "openai", "quick");
        assertEquals("minimal", JudgeDefaults.resolve("codex", project, null, null).thinking());
        assertEquals("none", JudgeDefaults.resolve("openai", project, null, null).thinking());
        ChatProfiles.save(project, ChatProfiles.captureJudge("quick", "openai", "gpt-5.6", null), true);
        assertEquals("none", JudgeDefaults.resolve("openai", project, null, null).thinking());
    }

    @Test void malformedProfileSelectionWarnsAndUsesExistingVendorDefault() throws Exception {
        JudgeDefaults.save(ChatConfig.Scope.PROJECT, project, "openai", new JudgeDefaults.Selection("o3", "low"));
        Files.writeString(ChatProfiles.path(project), "{\"activeJudges\":{\"openai\":\"missing\"}}");
        assertEquals(new JudgeDefaults.Selection("o3", "low"), JudgeDefaults.resolve("openai", project, null, null));
    }

    @Test void malformedExistingDefaultsAreNotDestroyedBySaving() throws Exception {
        Path path = JudgeDefaults.configPath(ChatConfig.Scope.PROJECT, project);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "broken json");
        assertThrows(java.io.IOException.class, () -> JudgeDefaults.save(ChatConfig.Scope.PROJECT, project,
                "openai", new JudgeDefaults.Selection("gpt-5.6", "none")));
        assertEquals("broken json", Files.readString(path));
    }
}
