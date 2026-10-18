package ai.kompile.cli.main.chat.exec;

import ai.kompile.cli.main.chat.agent.AgenticChatLoop;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import ai.kompile.cli.main.chat.tools.BackgroundProcessManager;
import ai.kompile.cli.main.chat.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@TemporaryUserHome
@Timeout(10)
class WebUsageCommandTest {
    @TempDir Path directory;
    private final String session = "usage-web-" + java.util.UUID.randomUUID();

    private WebCommandResolver.Resolution resolve(String raw, ChatConfig config) {
        return WebCommandResolver.resolve(new WebChatInput(WebChatInput.VERSION, raw, "", session),
                directory, new ChatSessionStateStore(), config);
    }

    @Test void usageIsCommandOnlyAndPinnedAccountConfigWinsOverFolderConfig() throws Exception {
        ChatConfig folder = new ChatConfig("kompile-local", null, "local-model", null);
        ChatConfig pinned = new ChatConfig("gemini", null, "model", null);
        pinned.setAuthenticationMethod("none");
        pinned.bindSession(session);
        String original = Files.readString(ChatConfig.sessionConfigPath(session));
        var result = resolve("/usage", folder);
        assertEquals(WebCommandResolver.Status.COMPLETED, result.status(), result.text());
        assertEquals("/usage", result.command());
        assertTrue(result.text().contains("Google AI Studio"), result.text());
        assertNull(result.modelPrompt());
        assertEquals(original, Files.readString(ChatConfig.sessionConfigPath(session)));
        assertEquals(WebCommandResolver.Status.INVALID, resolve("/usage anything", folder).status());
        assertTrue(resolve("/help", folder).text().contains("/usage"));
    }

    @Test void storedVendorSelectionAlsoAppliesToUsageAndDoesNotLeakIntoModelContext() {
        var store = new ChatSessionStateStore();
        assertTrue(store.updateModel(session, directory, "gemini", "selected-model").applied());
        var result = resolve("/usage", new ChatConfig("kompile-local", null, "local-model", null));
        assertEquals(WebCommandResolver.Status.COMPLETED, result.status(), result.text());
        assertTrue(result.text().contains("Google AI Studio"), result.text());
        assertNull(result.modelPrompt());
    }

    @Test void liveUsageAnswersImmediatelyInsteadOfDeferringOrJoiningThePromptQueue() throws Exception {
        var frames = "{\"version\":1,\"requestId\":\"usage\",\"action\":\"command\",\"text\":\"/usage\"}\n";
        var replyReceived = new CountDownLatch(1);
        var events = new ArrayList<HeadlessRunEvent>();
        var prompts = new ArrayList<String>();
        try (var processes = new BackgroundProcessManager(session, directory);
             var controls = new WebHarnessControls(new ByteArrayInputStream(frames.getBytes(StandardCharsets.UTF_8)))) {
            controls.setCommandResolver(raw -> resolve(raw, new ChatConfig("kompile-local", null, "model", null)));
            controls.run(mock(AgenticChatLoop.class), processes, session, "original prompt", 4000,
                    new AtomicBoolean(), prompt -> {
                        prompts.add(prompt);
                        assertTrue(replyReceived.await(3, TimeUnit.SECONDS));
                        return "model answer";
                    }, (action, id) -> ToolResult.error("Unexpected process action"), event -> {
                        events.add(event);
                        if (event.type() == HeadlessRunEvent.Type.CONTROL && event.data().path("requestId").asText().equals("usage")) replyReceived.countDown();
                    });
        }
        assertEquals(java.util.List.of("original prompt"), prompts);
        var reply = events.stream().filter(e -> e.type() == HeadlessRunEvent.Type.CONTROL
                && e.data().path("requestId").asText().equals("usage")).findFirst().orElseThrow().data();
        assertTrue(reply.path("ok").asBoolean(), reply.toString());
        assertFalse(reply.path("deferred").asBoolean());
        assertFalse(reply.path("queued").asBoolean());
        assertTrue(reply.path("message").asText().contains("no remote vendor allowance"), reply.toString());
    }
}
