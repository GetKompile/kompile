package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the Kompile Chat ↔ OpenCode provider interaction.
 *
 * <p>Under load, a pre-turn transport failure (server boot, session creation or
 * turn spawn) used to be marked as a provider-side side effect unconditionally, so
 * the connectivity loop refused to replay it and surfaced
 * "Response was not replayed because the provider had already streamed output"
 * even though nothing had streamed. A pre-turn failure touches no provider state,
 * so it must stay retryable AND replay-safe.</p>
 */
@TemporaryUserHome
class DirectLlmClientOpenCodeRetryTest {

    @Test
    void preTurnOpenCodeFailureIsRetryableAndReplaySafe() throws Exception {
        ChatConfig config = new ChatConfig("opencode", null, "opencode-go/model", null);
        DirectLlmClient client = new DirectLlmClient(
                config, new ObjectMapper(), fastPolicy());
        client.setOutputConsumer(ignored -> { });

        DirectLlmClient.StreamResult result = new DirectLlmClient.StreamResult();
        var failure = new OpenCodeServeClient.TurnNotStartedException(
                "OpenCode session creation timed out: request timed out");
        invokeRecordStreamFailure(client, result, failure);

        assertTrue(result.failed, "the turn attempt must be marked failed");
        assertTrue(readRetryable(result),
                "a pre-turn failure must be retryable by the connectivity loop");
        assertTrue(result.isReplaySafe(),
                "a pre-turn failure must be replayable: the provider never saw the prompt");
        assertTrue(readFinalMessage(result).contains("timed out"),
                "the diagnostic must carry the timeout detail");
    }

    @Test
    void genericMidTurnOpenCodeFailureIsNotReplaySafe() throws Exception {
        ChatConfig config = new ChatConfig("opencode", null, "opencode-go/model", null);
        DirectLlmClient client = new DirectLlmClient(
                config, new ObjectMapper(), fastPolicy());
        client.setOutputConsumer(ignored -> { });

        // Mirrors streamOpenCode's mid-turn catch: the turn ran, so the native
        // session may hold partial state and the result must not be replayed.
        DirectLlmClient.StreamResult result = new DirectLlmClient.StreamResult();
        result.providerSideEffectsObserved = true;
        invokeRecordStreamFailure(client, result,
                new IllegalStateException("OpenCode turn failed (exit 1): boom"));

        assertTrue(result.failed);
        assertFalse(readRetryable(result),
                "a mid-turn crash is terminal: the native session may hold state, "
                        + "so the connectivity loop must not re-send the turn");
        assertFalse(result.isReplaySafe(),
                "a turn that ran must not be replayed: provider-side history may exist");
    }

    @Test
    void cancellingTheChatTurnAbortsTheOpenCodeTurn() throws Exception {
        ChatConfig config = new ChatConfig("opencode", null, "opencode-go/model", null);
        DirectLlmClient client = new DirectLlmClient(
                config, new ObjectMapper(), fastPolicy());
        client.setOutputConsumer(ignored -> { });
        AtomicBoolean cancelled = new AtomicBoolean();
        client.setCancelSignal(cancelled);
        ExecutorService turnThread = Executors.newSingleThreadExecutor();
        try (HangingOpenCodeServer server = new HangingOpenCodeServer("session-chat")) {
            var transport = DirectLlmClient.class.getDeclaredField("openCodeServeClient");
            transport.setAccessible(true);
            transport.set(client, server.client(new ObjectMapper()));
            var streamOpenCode = DirectLlmClient.class.getDeclaredMethod("streamOpenCode",
                    String.class, String.class, ArrayNode.class, List.class, String.class);
            streamOpenCode.setAccessible(true);
            Future<Object> turn = turnThread.submit(() -> streamOpenCode.invoke(
                    client, "run a long tool", null, null, List.of(), "opencode-go/model"));
            assertTrue(server.turnStarted.await(10, TimeUnit.SECONDS),
                    "the turn never reached the server");

            cancelled.set(true);

            DirectLlmClient.StreamResult result =
                    (DirectLlmClient.StreamResult) turn.get(10, TimeUnit.SECONDS);
            assertEquals(1, server.abortCalls.get(),
                    "cancelling the chat turn must abort the OpenCode turn and its tools");
            assertTrue(result.cancelled, "the turn must end as cancelled");
            assertFalse(result.failed, "a cancelled turn is not a failure");
            assertFalse(readRetryable(result), "a cancelled turn must not be retried");
        } finally {
            turnThread.shutdownNow();
            client.close();
        }
    }

    private static ProviderConnectivityPolicy fastPolicy() {
        return new ProviderConnectivityPolicy(
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(3),
                java.time.Duration.ofMillis(100), java.time.Duration.ofSeconds(1),
                3, java.time.Duration.ofMillis(5), java.time.Duration.ofMillis(20));
    }

    private static void invokeRecordStreamFailure(
            DirectLlmClient client, DirectLlmClient.StreamResult result,
            Exception failure) throws Exception {
        var method = DirectLlmClient.class.getDeclaredMethod(
                "recordStreamFailure", DirectLlmClient.StreamResult.class,
                Exception.class, String.class);
        method.setAccessible(true);
        method.invoke(client, result, failure, "[Error: ");
    }

    private static boolean readRetryable(DirectLlmClient.StreamResult result)
            throws Exception {
        var field = DirectLlmClient.StreamResult.class
                .getDeclaredField("retryableConnectivityFailure");
        field.setAccessible(true);
        return field.getBoolean(result);
    }

    private static String readFinalMessage(DirectLlmClient.StreamResult result)
            throws Exception {
        var field = DirectLlmClient.StreamResult.class
                .getDeclaredField("connectivityFinalMessage");
        field.setAccessible(true);
        Object value = field.get(result);
        return value == null ? "" : value.toString();
    }
}
