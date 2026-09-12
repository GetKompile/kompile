package ai.kompile.cli.main.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

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
