package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class WebChatSetupTest {
    @TempDir Path directory;
    private ObjectNode apiSelection() {
        return JsonUtils.standardMapper().createObjectNode().put("mode", "standard").put("runtime", "direct")
                .put("vendor", "openai").put("authMethod", "api-key").put("model", "day-one-model")
                .put("apiKey", "test-secret").put("saveScope", "session");
    }
    @Test void scopedDefaultsAndNamedCredentialsSurviveFreshSetup() throws Exception {
        CredentialStore.create().putApiKey("openai", "work", "saved-secret", false);
        var config = new ChatConfig("openai", null, "project-model", "https://example.test/v1");
        config.setAuthenticationMethod("api-key"); config.setCredentialName("work"); config.saveProject(directory);
        var selected = WebChatSetup.selection(directory, JsonUtils.standardMapper().createObjectNode(), false);
        assertEquals("project-model", selected.getModel());
        assertEquals("work", selected.getCredentialName());
        assertEquals("https://example.test/v1", selected.getBaseUrl());
        assertEquals("saved-secret", selected.getApiKey());
    }
    @Test void changingVendorDoesNotCarryProjectModelOrCredential() throws Exception {
        var config = new ChatConfig("openai", null, "project-model", "https://example.test/v1");
        config.setAuthenticationMethod("api-key"); config.setCredentialName("work"); config.saveProject(directory);
        var input = JsonUtils.standardMapper().createObjectNode().put("runtime", "external-local").put("vendor", "custom").put("authMethod", "api-key");
        var selected = WebChatSetup.selection(directory, input, false);
        assertEquals("custom", selected.getProvider()); assertNull(selected.getModel()); assertNull(selected.getCredentialName());
        assertNotEquals("https://example.test/v1", selected.getBaseUrl());
    }
    @Test void createPinsSessionWithoutCreatingTranscriptOrChangingProjectDefaults() throws Exception {
        var defaults = new ChatConfig("openai", null, "original-model", null); defaults.saveProject(directory);
        String id = UUID.randomUUID().toString();
        var request = JsonUtils.standardMapper().createObjectNode().put("action", "create").put("sessionId", id);
        request.set("selection", apiSelection());
        var result = WebChatSetup.handle(directory, request);
        assertTrue(result.path("ok").asBoolean()); assertFalse(result.toString().contains("test-secret"));
        var pinned = ChatConfig.loadSession(id);
        assertEquals("day-one-model", pinned.getModel()); assertEquals("test-secret", pinned.getApiKey());
        assertFalse(Files.readString(ChatConfig.sessionConfigPath(id)).contains("test-secret"));
        assertFalse(Files.exists(ChatConfig.sessionConfigPath(id).resolveSibling(id + ".jsonl")));
        assertEquals("original-model", ChatConfig.loadProject(directory).getModel());
    }
    @Test void rejectsUnsupportedFieldsBeforeAnyPersistence() {
        var request = JsonUtils.standardMapper().createObjectNode().put("action", "create").put("sessionId", UUID.randomUUID().toString());
        request.set("selection", apiSelection().put("unwiredOption", "no-op"));
        assertThrows(IllegalArgumentException.class, () -> WebChatSetup.handle(directory, request));
    }
    @Test void rejectsUnknownWorkflowAndInvalidUrlBeforeSessionCreation() {
        String id = UUID.randomUUID().toString();
        var request = JsonUtils.standardMapper().createObjectNode().put("action", "create").put("sessionId", id);
        request.set("selection", apiSelection().put("mode", "workflow").put("workflow", "missing-team"));
        assertThrows(IllegalArgumentException.class, () -> WebChatSetup.handle(directory, request));
        assertFalse(Files.exists(ChatConfig.sessionConfigPath(id)));
        request.set("selection", apiSelection().put("baseUrl", "https://user:password@example.test/v1"));
        assertThrows(IllegalArgumentException.class, () -> WebChatSetup.handle(directory, request));
        assertFalse(Files.exists(ChatConfig.sessionConfigPath(id)));
    }
    @Test void unknownJudgeProfileDoesNotPartiallyBindTheChat() {
        String id = UUID.randomUUID().toString();
        var selection = apiSelection(); selection.putArray("judges").addObject().put("provider", "openai").put("profile", "missing");
        var request = JsonUtils.standardMapper().createObjectNode().put("action", "create").put("sessionId", id);
        request.set("selection", selection);
        assertThrows(IllegalArgumentException.class, () -> WebChatSetup.handle(directory, request));
        assertFalse(Files.exists(ChatConfig.sessionConfigPath(id)));
    }
}
