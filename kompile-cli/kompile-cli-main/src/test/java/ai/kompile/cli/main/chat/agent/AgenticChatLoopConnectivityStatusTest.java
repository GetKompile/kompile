package ai.kompile.cli.main.chat.agent;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.ChatCompleter;
import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.config.DirectLlmClient;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class AgenticChatLoopConnectivityStatusTest {
    @TempDir Path directory;

    @Test
    void retryNotificationsAreTransientForTextAndSilentTerminalPaths() throws Exception {
        String home = System.getProperty("user.home");
        System.setProperty("user.home", directory.toString());
        try {
            for (String outcome : List.of("text", "tool", "empty", "failed", "cancelled", "exception")) {
                verifyOutcome(outcome);
            }
        } finally {
            if (home == null) System.clearProperty("user.home");
            else System.setProperty("user.home", home);
            ChatCompleter.setContentOutput(null);
            ChatCompleter.setActivity(null);
        }
    }

    private void verifyOutcome(String outcome) throws Exception {
        var mapper = JsonUtils.standardMapper();
        List<String> output = new ArrayList<>();
        ChatCompleter.setContentOutput(output::add);
        DirectLlmClient client = new DirectLlmClient(
                new ChatConfig("openai", null, "gpt-4o", "https://api.openai.com/v1"), mapper) {
            @Override
            public StreamResult streamChat(String message, String prompt, ArrayNode tools,
                    List<ToolCallResultInput> results, String model, List<AttachmentInput> attachments) {
                for (int attempt = 2; attempt <= 3; attempt++) {
                    getConnectivityEventConsumer().accept(new ConnectivityEvent(
                            "openai", attempt, 3, Duration.ofMillis(1), "test disconnect"));
                    assertTrue(ChatCompleter.getActivity().contains(attempt + "/3"));
                }
                if (outcome.equals("exception")) throw new IllegalStateException("test failure");
                StreamResult result = new StreamResult();
                if (outcome.equals("text")) {
                    getOutputConsumer().accept("recovered\n");
                    assertEquals("Responding", ChatCompleter.getActivity());
                    result.text = "recovered";
                }
                if (outcome.equals("tool")) {
                    getProviderActivityListener().onToolStart("call-1", "read", "{}");
                    assertEquals("Thinking", ChatCompleter.getActivity());
                }
                result.failed = outcome.equals("failed");
                result.cancelled = outcome.equals("cancelled");
                return result;
            }
        };
        Consumer<DirectLlmClient.ConnectivityEvent> previous = event -> {};
        client.setConnectivityEventConsumer(previous);
        AgenticChatLoop loop = new AgenticChatLoop(null, mapper, new ToolRegistry(mapper),
                new PermissionService(), new AgentRegistry(), directory, client, null);
        var method = AgenticChatLoop.class.getDeclaredMethod("streamDirectTurn", String.class,
                String.class, ArrayNode.class, List.class, String.class, List.class);
        method.setAccessible(true);
        if (outcome.equals("exception")) {
            InvocationTargetException error = assertThrows(InvocationTargetException.class,
                    () -> method.invoke(loop, "hello", "", mapper.createArrayNode(), null, null, null));
            assertInstanceOf(IllegalStateException.class, error.getCause());
        } else {
            method.invoke(loop, "hello", "", mapper.createArrayNode(), null, null, null);
        }
        assertFalse(String.valueOf(ChatCompleter.getActivity()).startsWith("Reconnecting"), outcome);
        assertTrue(output.stream().noneMatch(line -> line.contains("test disconnect")),
                "retry warnings must not accumulate in permanent chat output");
        assertSame(previous, client.getConnectivityEventConsumer());
    }
}
