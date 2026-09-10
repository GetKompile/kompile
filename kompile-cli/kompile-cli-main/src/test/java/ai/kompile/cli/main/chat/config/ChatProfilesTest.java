package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatProfilesTest {
    @TempDir Path project;

    @Test
    void roundTripsOnlyNonSecretProviderSettings() throws Exception {
        ChatConfig original = new ChatConfig("openai-codex", "NEVER_STORE_THIS", "gpt-5.6-sol", "https://example.test");
        original.setAuthenticationMethod("oauth");
        original.setThinking("max");
        original.setFastMode(true);
        ChatProfiles.Profile profile = ChatProfiles.capture("work", original);
        assertEquals("openai", profile.vendor());
        assertTrue(ChatProfiles.save(project, profile, false));
        String json = Files.readString(ChatProfiles.path(project));
        assertFalse(json.contains("NEVER_STORE_THIS"));
        assertFalse(json.contains("apiKey"));
        assertFalse(json.contains("accessToken"));
        assertTrue(JsonUtils.standardMapper().readTree(json).path("vendors").path("openai")
                .path("standard").has("work"));
        assertEquals(profile, ChatProfiles.list(project, "standard").get(0));
        ChatConfig restored = ChatProfiles.list(project, "standard").get(0).toConfig();
        assertEquals("openai-codex", restored.getProvider());
        assertEquals("oauth", restored.getAuthenticationMethod());
        assertEquals("gpt-5.6-sol", restored.getModel());
        assertEquals("max", restored.getThinking());
        assertEquals("https://example.test", restored.getBaseUrl());
        assertTrue(restored.isFastMode());
        assertFalse(Files.exists(ChatConfig.projectConfigPath(project)));
    }

    @Test
    void namesAreIsolatedByProjectVendorAndMode() throws Exception {
        ChatProfiles.save(project, ChatProfiles.capture("work", new ChatConfig("openai", null, "gpt", null)), false);
        ChatProfiles.save(project, ChatProfiles.capture("work", new ChatConfig("anthropic", null, "claude", null)), false);
        ChatConfig nativeConfig = nativeConfig(true);
        ChatProfiles.save(project, ChatProfiles.capture("work", nativeConfig), false);
        nativeConfig.setPassthroughManaged(false);
        ChatProfiles.save(project, ChatProfiles.capture("work", nativeConfig), false);
        assertEquals(2, ChatProfiles.list(project, "standard").size());
        assertEquals(2, ChatProfiles.list(project, "passthrough").size());
        assertEquals(1, ChatProfiles.list(project, "passthrough-direct").size());
        assertTrue(ChatProfiles.list(project.resolve("other-project"), "standard").isEmpty());
        assertTrue(ChatProfiles.list(project, "resume").isEmpty());
        assertTrue(ChatProfiles.list(project, "resume-all").isEmpty());
    }

    @Test
    void replacingNeedsConsentAndPreservesOtherProfiles() throws Exception {
        ChatConfig config = nativeConfig(true);
        ChatProfiles.Profile first = ChatProfiles.capture("work", config);
        ChatProfiles.save(project, first, false);
        ChatProfiles.save(project, ChatProfiles.capture("other", config), false);
        config.setThinking("low");
        ChatProfiles.Profile updated = ChatProfiles.capture("work", config);
        assertFalse(ChatProfiles.save(project, updated, false));
        assertEquals(first, ChatProfiles.list(project, "passthrough").get(0));
        assertTrue(ChatProfiles.save(project, updated, true));
        List<ChatProfiles.Profile> profiles = ChatProfiles.list(project, "passthrough");
        assertEquals(2, profiles.size());
        assertEquals(updated, profiles.get(0));
        assertEquals("high", profiles.get(1).thinking());
    }

    @Test
    void managedAndDirectRoundTripTheirOwnLaunchSettings() throws Exception {
        for (boolean managed : List.of(true, false)) {
            ChatConfig original = nativeConfig(managed);
            ChatProfiles.save(project, ChatProfiles.capture("work", original), false);
            ChatConfig restored = ChatProfiles.list(project, ChatProfiles.mode(original)).get(0).toConfig();
            assertEquals("passthrough", restored.getChatMode());
            assertEquals(managed, restored.isPassthroughManaged());
            assertEquals("codex", restored.getPassthroughAgent());
            assertEquals("native-model", restored.getModel());
            assertEquals("high", restored.getThinking());
            assertNull(restored.getProvider());
            assertNull(restored.getBaseUrl());
        }
    }

    @Test
    void malformedFilesAreNotOverwritten() throws Exception {
        Files.createDirectories(ChatProfiles.path(project).getParent());
        for (String invalid : List.of("not json", "[]", "{\"vendors\":[]}",
                "{\"vendors\":{\"openai\":{\"standard\":{\"bad\":{}}}}}")) {
            Files.writeString(ChatProfiles.path(project), invalid);
            assertThrows(IOException.class, () -> ChatProfiles.save(project,
                    ChatProfiles.capture("work", nativeConfig(true)), true));
            assertEquals(invalid, Files.readString(ChatProfiles.path(project)));
        }
    }

    @Test
    void namesNeverBecomeFilesystemPaths() throws Exception {
        ChatProfiles.save(project, ChatProfiles.capture("../../elsewhere", nativeConfig(true)), false);
        assertEquals("../../elsewhere", ChatProfiles.list(project, "passthrough").get(0).name());
        assertThrows(IllegalArgumentException.class, () -> ChatProfiles.capture("\n", nativeConfig(true)));
    }

    @Test void judgeProfilesShareStoreButNotChatModeOrOtherProjects() throws Exception {
        var chat = ChatProfiles.capture("work", nativeConfig(true));
        var judge = ChatProfiles.captureJudge("work", "codex-cli", "judge-model", "minimal");
        ChatProfiles.save(project, chat, false);
        ChatProfiles.save(project, judge, false);
        ChatProfiles.save(project, ChatProfiles.captureJudge("work", "claude", "claude-judge", null), false);
        ChatProfiles.activateJudge(project, "openai", "work");
        ChatProfiles.activateJudge(project, "anthropic", "work");
        assertEquals(List.of(chat), ChatProfiles.list(project, "passthrough"));
        assertTrue(ChatProfiles.list(project, "standard").isEmpty());
        assertEquals(2, ChatProfiles.list(project, "judge").size());
        assertEquals(judge, ChatProfiles.activeJudge(project, "openai-codex"));
        assertEquals("openai-codex", judge.provider());
        assertEquals("claude-judge", ChatProfiles.activeJudge(project, "claude").model());
        assertNull(ChatProfiles.activeJudge(project.resolve("other"), "openai"));
        assertThrows(IllegalStateException.class, judge::toConfig);
        assertNull(judge.baseUrl());
        assertNull(judge.authenticationMethod());
        assertNull(judge.agent());
        String json = Files.readString(ChatProfiles.path(project));
        assertFalse(json.contains("apiKey"));
        assertFalse(json.contains("accessToken"));
    }

    @Test void judgeReplacementAndDeletionPreserveOtherSelections() throws Exception {
        var first = ChatProfiles.captureJudge("quick", "openai", "first", null);
        var replacement = ChatProfiles.captureJudge("quick", "openai", "second", "low");
        ChatProfiles.save(project, first, false);
        ChatProfiles.save(project, ChatProfiles.captureJudge("other", "openai", "other", null), false);
        ChatProfiles.save(project, ChatProfiles.captureJudge("quick", "anthropic", "claude", null), false);
        ChatProfiles.activateJudge(project, "openai", "quick");
        ChatProfiles.activateJudge(project, "anthropic", "quick");
        assertFalse(ChatProfiles.save(project, replacement, false));
        assertEquals(first, ChatProfiles.activeJudge(project, "openai"));
        assertTrue(ChatProfiles.save(project, replacement, true));
        assertEquals(replacement, ChatProfiles.activeJudge(project, "openai"));
        assertThrows(IllegalArgumentException.class, () -> ChatProfiles.activateJudge(project, "openai", "missing"));
        assertTrue(ChatProfiles.deleteJudge(project, "openai", "other"));
        assertEquals(replacement, ChatProfiles.activeJudge(project, "openai"));
        assertFalse(ChatProfiles.deleteJudge(project, "openai", "missing"));
        assertTrue(ChatProfiles.deleteJudge(project, "codex", "quick"));
        assertNull(ChatProfiles.activeJudge(project, "openai"));
        assertNotNull(ChatProfiles.activeJudge(project, "anthropic"));
        ChatProfiles.activateJudge(project, "anthropic", null);
        assertNull(ChatProfiles.activeJudge(project, "anthropic"));
        assertEquals(1, ChatProfiles.list(project, "judge").size());
    }

    @Test void invalidJudgeProfilesAndDanglingSelectionsCannotBeSavedOver() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> ChatProfiles.captureJudge("judge", "openai", "", null));
        assertThrows(IllegalArgumentException.class, () -> new ChatProfiles.Profile("judge", "anthropic", "judge",
                "openai", "model", null, null, null, false, null));
        assertThrows(IllegalArgumentException.class, () -> new ChatProfiles.Profile("judge", "openai", "judge",
                "openai", "model", null, "https://example.test", null, false, null));
        Files.createDirectories(ChatProfiles.path(project).getParent());
        String invalid = "{\"activeJudges\":{\"openai\":\"missing\"}}";
        Files.writeString(ChatProfiles.path(project), invalid);
        assertThrows(IOException.class, () -> ChatProfiles.activateJudge(project, "openai", null));
        assertThrows(IOException.class, () -> ChatProfiles.save(project,
                ChatProfiles.captureJudge("new", "openai", "model", null), false));
        assertEquals(invalid, Files.readString(ChatProfiles.path(project)));
    }

    static ChatConfig nativeConfig(boolean managed) {
        ChatConfig config = new ChatConfig(null, null, "native-model", null);
        config.setChatMode("passthrough");
        config.setPassthroughAgent("codex");
        config.setPassthroughManaged(managed);
        config.setThinking("high");
        return config;
    }
}
