package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.auth.CredentialStore;
import ai.kompile.cli.main.chat.exec.ChatSessionStateStore;
import ai.kompile.cli.main.chat.exec.WebCommandResolver;
import ai.kompile.cli.main.chat.exec.WebModelCatalog;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

@TemporaryUserHome
class WebChatSetupTest {
    @TempDir Path directory;
    @AfterEach void restoreLiveDiscovery() { WebModelCatalog.useDiscovery(null); }
    /** The route's live discovery answers with {@code ids} instead of reaching the provider. */
    private static void liveModels(String... ids) {
        WebModelCatalog.useDiscovery(config -> ModelDiscovery.Result.success(java.util.Arrays.stream(ids)
                .map(id -> new LiveModelDiscovery.Model(id, List.of())).toList(), List.of("https://provider.example/v1/models")));
    }
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
        // A day-one id absent from an authoritative live list is accepted, as the terminal /model does.
        liveModels("gpt-5.6");
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
    @Test void createRefusesAModelTheRoutesLiveDiscoveryCannotVouchFor() {
        String id = UUID.randomUUID().toString();
        var request = JsonUtils.standardMapper().createObjectNode().put("action", "create").put("sessionId", id);
        request.set("selection", apiSelection());
        WebModelCatalog.useDiscovery(config -> ModelDiscovery.Result.failure(
                ModelDiscovery.Status.AUTH_REQUIRED, "bad key", List.of()));
        var refused = assertThrows(IllegalArgumentException.class, () -> WebChatSetup.handle(directory, request));
        assertTrue(refused.getMessage().contains("live model list"), refused.getMessage());
        assertFalse(Files.exists(ChatConfig.sessionConfigPath(id)));
        request.set("selection", apiSelection().put("model", "3"));
        liveModels("gpt-5.6");
        assertThrows(IllegalArgumentException.class, () -> WebChatSetup.handle(directory, request),
                "a menu index is never a model id");
        assertFalse(Files.exists(ChatConfig.sessionConfigPath(id)));
    }
    @Test void catalogShowsTheLastKnownGoodListOnlyWhileTheProviderIsUnreachable() throws Exception {
        var selection = JsonUtils.standardMapper().createObjectNode().put("mode", "standard").put("runtime", "direct")
                .put("vendor", "openai").put("authMethod", "api-key");
        liveModels("gpt-5.6", "gpt-5.6-mini");
        var live = catalog(selection);
        assertEquals(List.of("gpt-5.6", "gpt-5.6-mini"), values(live.path("models"), "id"));
        assertEquals(0, live.path("errors").size(), live.toString());
        WebModelCatalog.useDiscovery(config -> ModelDiscovery.Result.failure(
                ModelDiscovery.Status.TIMEOUT, "timed out", List.of()));
        var offline = catalog(selection);
        assertEquals(List.of("gpt-5.6", "gpt-5.6-mini"), values(offline.path("models"), "id"));
        assertTrue(offline.path("errors").get(0).asText().contains("last known good"), offline.toString());
    }
    @Test void rejectsUnknownWorkflowAndInvalidUrlBeforeSessionCreation() {
        liveModels("gpt-5.6");
        String id = UUID.randomUUID().toString();
        var request = JsonUtils.standardMapper().createObjectNode().put("action", "create").put("sessionId", id);
        request.set("selection", apiSelection().put("mode", "workflow").put("workflow", "missing-team"));
        assertThrows(IllegalArgumentException.class, () -> WebChatSetup.handle(directory, request));
        assertFalse(Files.exists(ChatConfig.sessionConfigPath(id)));
        request.set("selection", apiSelection().put("baseUrl", "https://user:password@example.test/v1"));
        assertThrows(IllegalArgumentException.class, () -> WebChatSetup.handle(directory, request));
        assertFalse(Files.exists(ChatConfig.sessionConfigPath(id)));
    }
    /** Loopback OpenAI-compatible catalog: records each Authorization header and answers with {@code status}/{@code body}. */
    private static HttpServer catalogServer(int status, String body, List<String> authorizations) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(auth == null ? "" : auth);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }
    private JsonNode catalog(ObjectNode selection) throws Exception {
        var request = JsonUtils.standardMapper().createObjectNode().put("action", "catalog");
        request.set("selection", selection);
        return WebChatSetup.handle(directory, request);
    }
    private static List<String> values(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.path(field).asText()));
        return out;
    }
    private static ObjectNode endpoint(HttpServer server) {
        return JsonUtils.standardMapper().createObjectNode().put("runtime", "external-local").put("vendor", "custom")
                .put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }
    @Test void catalogDiscoversLiveModelsWithTheTypedKeyAndKeepsTheRuntime() throws Exception {
        List<String> authorizations = new CopyOnWriteArrayList<>();
        HttpServer server = catalogServer(200, "{\"object\":\"list\",\"data\":[{\"id\":\"loop-model-a\"},{\"id\":\"loop-model-b\"}]}", authorizations);
        try {
            var result = catalog(endpoint(server).put("authMethod", "api-key").put("apiKey", "typed-key"));
            assertTrue(result.path("available").asBoolean(), result.toString());
            assertEquals(List.of("loop-model-a", "loop-model-b"), values(result.path("models"), "id"));
            assertEquals(0, result.path("errors").size(), result.toString());
            assertTrue(authorizations.contains("Bearer typed-key"), authorizations.toString());
            // An external endpoint must not snap the runtime select back to the direct-vendor list.
            assertEquals("external-local", result.path("defaults").path("runtime").asText());
            assertFalse(result.toString().contains("typed-key"));
        } finally { server.stop(0); }
    }
    @Test void catalogReportsTheProvidersOwnDiscoveryFailure() throws Exception {
        HttpServer server = catalogServer(401, "{\"error\":{\"message\":\"bad key\"}}", new CopyOnWriteArrayList<>());
        try {
            var result = catalog(endpoint(server).put("authMethod", "api-key").put("apiKey", "rejected-key"));
            assertEquals(0, result.path("models").size());
            assertEquals(1, result.path("errors").size(), result.toString());
            assertFalse(result.path("errors").get(0).asText().startsWith("Model catalog unavailable"), result.toString());
        } finally { server.stop(0); }
    }
    @Test void catalogMenusAreTheTerminalWizardsMenus() throws Exception {
        HttpServer server = catalogServer(200, "{\"data\":[]}", new CopyOnWriteArrayList<>());
        try {
            var result = catalog(endpoint(server).put("authMethod", "none"));
            assertEquals(SetupWizard.chatModeValues(), values(result.path("modes"), "id"));
            assertEquals(SetupWizard.chatModeOptions(), values(result.path("modes"), "label"));
            assertEquals(SetupWizard.standardRuntimeOptions(), values(result.path("runtimes"), "label"));
            assertEquals(List.of("kompile-local", "external-local", "direct", "kompile"), values(result.path("runtimes"), "id"));
            assertEquals(ChatConfig.getPassthroughAgentOrder(), values(result.path("frameworks"), "id"));
            var custom = result.path("vendors").get(0);
            assertEquals(List.of(SetupWizard.authMethodLabel("custom", SetupWizard.AuthMethod.NONE),
                    SetupWizard.authMethodLabel("custom", SetupWizard.AuthMethod.API_KEY)), values(custom.path("authMethods"), "label"));
            assertTrue(result.path("webSupported").asBoolean());
        } finally { server.stop(0); }
    }
    @Test void savedNativeAgentIsAValidFrameworkChoice() throws Exception {
        // The wizard persists registry command keys; the browser must offer and accept the same key.
        var config = new ChatConfig(); config.setChatMode("passthrough"); config.setPassthroughAgent("dsh"); config.saveProject(directory);
        var result = catalog(JsonUtils.standardMapper().createObjectNode().put("mode", "passthrough"));
        assertTrue(result.path("available").asBoolean(), result.toString());
        assertEquals("dsh", result.path("defaults").path("passthroughAgent").asText());
        assertTrue(values(result.path("frameworks"), "id").contains("dsh"));
        assertFalse(result.path("webSupported").asBoolean());
    }
    /** A chat created by the browser wizard on the day-one route. */
    private String createdChat() throws Exception {
        String id = UUID.randomUUID().toString();
        var request = JsonUtils.standardMapper().createObjectNode().put("action", "create").put("sessionId", id);
        request.set("selection", apiSelection());
        assertTrue(WebChatSetup.handle(directory, request).path("ok").asBoolean());
        return id;
    }
    private JsonNode session(String action, String id, ObjectNode selection) throws Exception {
        var request = JsonUtils.standardMapper().createObjectNode().put("action", action).put("sessionId", id);
        request.set("selection", selection);
        return WebChatSetup.handle(directory, request);
    }
    @Test void sessionCatalogIsSeededFromTheChatsEffectiveRoute() throws Exception {
        liveModels("gpt-5.6", "gpt-5.7");
        String id = createdChat();
        // A web /model pick overrides the pinned route; the wizard must show what the next turn will use.
        assertTrue(new ChatSessionStateStore().updateModel(id, directory, "openai", "gpt-5.7").applied());
        var result = session("catalog", id, JsonUtils.standardMapper().createObjectNode());
        assertTrue(result.path("session").asBoolean(), result.toString());
        assertEquals("gpt-5.7", result.path("defaults").path("model").asText());
        assertEquals("openai", result.path("defaults").path("vendor").asText());
        assertEquals("api-key", result.path("defaults").path("authMethod").asText());
        assertEquals(List.of("gpt-5.6", "gpt-5.7"), values(result.path("models"), "id"));
        assertFalse(result.toString().contains("test-secret"));
    }
    @Test void updateRepinsTheRouteAndDropsEarlierWebOverrides() throws Exception {
        liveModels("gpt-5.6", "gpt-5.7");
        String id = createdChat();
        var store = new ChatSessionStateStore();
        assertTrue(store.updateModel(id, directory, "openai", "gpt-5.7").applied());
        assertTrue(store.updateRole(id, directory, "reviewer").applied());
        var result = session("update", id, apiSelection().<ObjectNode>without("apiKey").put("model", "gpt-5.6"));
        assertTrue(result.path("ok").asBoolean(), result.toString());
        assertEquals("gpt-5.6", result.path("model").asText());
        var pinned = ChatConfig.loadSession(id);
        assertEquals("gpt-5.6", pinned.getModel());
        assertEquals("test-secret", pinned.getApiKey(), "the chat keeps its key when none is typed");
        assertNull(store.loadModel(id, directory));
        assertNull(store.loadProvider(id, directory));
        assertEquals("reviewer", store.loadRole(id, directory), "the role is not part of the route");
        assertEquals("gpt-5.6", WebCommandResolver.effectiveSessionConfig(id, directory).getModel());
        assertFalse(Files.readString(ChatConfig.sessionConfigPath(id)).contains("test-secret"));
    }
    @Test void updateKeepsTheChatsWorkflowTeam() throws Exception {
        liveModels("gpt-5.6");
        String id = createdChat();
        assertThrows(IllegalArgumentException.class, () -> session("update", id, apiSelection().put("leadMode", "standard")));
        assertThrows(IllegalArgumentException.class, () -> session("update", id, apiSelection().put("workflow", "team")));
        assertThrows(IllegalArgumentException.class, () -> session("update", id, apiSelection().put("mode", "workflow")));
        assertEquals("day-one-model", ChatConfig.loadSession(id).getModel());
    }
    /** A native framework is a vendor: a chat switches to it, between frameworks, and back to a standard vendor. */
    @Test void updateSwitchesModeAndNativeFrameworkLikeAVendor() throws Exception {
        liveModels("gpt-5.6", "zai/glm-5", "gpt-5-codex");
        String id = createdChat();
        var mapper = JsonUtils.standardMapper();
        var toNative = session("update", id, mapper.createObjectNode().put("mode", "passthrough")
                .put("passthroughAgent", "opencode").put("passthroughManaged", true).put("model", "zai/glm-5"));
        assertTrue(toNative.path("ok").asBoolean(), toNative.toString());
        assertEquals("opencode", toNative.path("framework").asText());
        var pinned = ChatConfig.loadSession(id);
        assertEquals("passthrough", pinned.getChatMode());
        assertEquals("opencode", pinned.getPassthroughAgent());
        assertEquals("zai/glm-5", pinned.getModel());

        // Between frameworks the old framework's model does not carry over.
        session("update", id, mapper.createObjectNode().put("mode", "passthrough").put("passthroughAgent", "codex"));
        pinned = ChatConfig.loadSession(id);
        assertEquals("codex", pinned.getPassthroughAgent());
        assertNull(pinned.getModel(), "the framework's default model, not opencode's");

        var toStandard = session("update", id, apiSelection().<ObjectNode>without("apiKey").put("model", "gpt-5.6"));
        assertTrue(toStandard.path("ok").asBoolean(), toStandard.toString());
        pinned = ChatConfig.loadSession(id);
        assertEquals("standard", pinned.getChatMode());
        assertEquals("gpt-5.6", pinned.getModel());
        assertEquals("standard", toStandard.path("framework").asText());
    }
    @Test void updateRefusesAModelTheRoutesLiveDiscoveryCannotVouchFor() throws Exception {
        liveModels("gpt-5.6");
        String id = createdChat();
        WebModelCatalog.useDiscovery(config -> ModelDiscovery.Result.failure(
                ModelDiscovery.Status.AUTH_REQUIRED, "bad key", List.of()));
        var refused = assertThrows(IllegalArgumentException.class,
                () -> session("update", id, apiSelection().<ObjectNode>without("apiKey").put("model", "gpt-5.7")));
        assertTrue(refused.getMessage().contains("live model list"), refused.getMessage());
        assertEquals("day-one-model", ChatConfig.loadSession(id).getModel());
    }
    @Test void updateRequiresAChat() {
        assertThrows(IllegalArgumentException.class, () -> session("update", null, apiSelection()));
    }
    @Test void unknownJudgeProfileDoesNotPartiallyBindTheChat() {
        liveModels("gpt-5.6");
        String id = UUID.randomUUID().toString();
        var selection = apiSelection(); selection.putArray("judges").addObject().put("provider", "openai").put("profile", "missing");
        var request = JsonUtils.standardMapper().createObjectNode().put("action", "create").put("sessionId", id);
        request.set("selection", selection);
        assertThrows(IllegalArgumentException.class, () -> WebChatSetup.handle(directory, request));
        assertFalse(Files.exists(ChatConfig.sessionConfigPath(id)));
    }
}
