/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Model capture must be serialized with a live Claude selection, not just its send. */
@TemporaryUserHome
class DirectLlmClientClaudeModelSelectionTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aTurnSubmittedDuringSelectionUsesOnlyTheAcknowledgedModel(boolean accepted) throws Exception {
        FakeClaudeCode fake = new FakeClaudeCode(directory.resolve("claude"), """
                control() {
                  if [ "$2" = set_model ]; then
                    while [ ! -f "$DIR/ack" ]; do sleep 0.02; done
                    emit '{"type":"control_response","response":{"subtype":"@SUBTYPE@","request_id":"'"$1"'","error":"model unavailable","response":{}}}'
                  else
                    emit '{"type":"control_response","response":{"subtype":"success","request_id":"'"$1"'","response":{}}}'
                  fi
                }
                """.replace("@SUBTYPE@", accepted ? "success" : "error"));
        ChatConfig config = new ChatConfig("anthropic", null, "sonnet", null);
        config.setAuthenticationMethod("oauth");
        LinkedBlockingQueue<String> selection = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<DirectLlmClient.StreamResult> responses = new LinkedBlockingQueue<>();
        try (DirectLlmClient client = new DirectLlmClient(config, JsonUtils.standardMapper(), directory)) {
            client.setOutputConsumer(ignored -> {});
            fake.install(client, directory, null);
            assertEquals("ok", client.streamChat("first", "", null, null).text);
            ChatConfig candidate = config.copy();
            candidate.setModel("opus");
            assertTrue(client.selectClaudeModel(candidate, () -> {
                config.applyLlmSettingsFrom(candidate);
                selection.add("accepted");
            }, selection::add));
            fake.awaitControl("set_model");
            assertEquals("sonnet", config.getModel(), "unconfirmed settings must not be published");

            Thread next = new Thread(() -> responses.add(
                    client.streamChat("next question", "", null, null)), "turn-during-model-selection");
            next.setDaemon(true);
            next.start();
            try {
                FakeClaudeCode.await("the next turn waiting for selection",
                        () -> next.getState() == Thread.State.BLOCKED);
                assertEquals(1, fake.messages().size(), "do not submit the next turn before acknowledgement");
                Files.createFile(fake.path("ack"));
                assertEquals(accepted ? "accepted" : "model unavailable", selection.poll(5, TimeUnit.SECONDS));
                DirectLlmClient.StreamResult response = responses.poll(5, TimeUnit.SECONDS);
                assertNotNull(response, "the pending turn must continue after selection");
                assertFalse(response.failed, response.failureMessage);
                assertEquals("ok", response.text);
                assertEquals(accepted ? "opus" : "sonnet", config.getModel());
                assertEquals(List.of("opus"), fake.controls("set_model").stream()
                        .map(request -> request.path("model").asText()).toList(),
                        "a pending turn must not switch the process back to the old captured model");
                assertEquals(1, fake.argv().size(), "the model switch must preserve the native session");
                assertEquals(2, fake.messages().size(), "keep conversation without replaying previous turns");
                assertFalse(fake.messages().get(1).contains("[Earlier conversation restored"));
            } finally {
                if (!Files.exists(fake.path("ack"))) Files.createFile(fake.path("ack"));
                next.join(5_000);
            }
        }
    }
}
