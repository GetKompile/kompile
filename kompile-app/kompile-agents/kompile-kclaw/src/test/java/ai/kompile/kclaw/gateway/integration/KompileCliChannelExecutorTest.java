/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.kclaw.gateway.integration;

import ai.kompile.gateway.core.model.AgentRequest;
import ai.kompile.gateway.core.service.SessionService;
import ai.kompile.kclaw.task.KompileCliRunner;
import ai.kompile.react.model.ReActMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KompileCliChannelExecutorTest {

    @Test
    void rendersPersistedHistoryAndPassesModelOverride() {
        KompileCliRunner runner = mock(KompileCliRunner.class);
        SessionService sessions = mock(SessionService.class);
        when(sessions.loadSession("slack:U1")).thenReturn(List.of(
                ReActMessage.user("first question"),
                ReActMessage.assistant("first answer")));
        when(runner.run(any(), eq("claude-sonnet")))
                .thenReturn(new KompileCliRunner.Result(true, "second answer", null));
        KompileCliChannelExecutor executor = new KompileCliChannelExecutor(runner, sessions);

        var response = executor.execute(AgentRequest.builder()
                .agentId("jarvis")
                .sessionKey("slack:U1")
                .message("second question")
                .metadata(Map.of("model", "claude-sonnet"))
                .build());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(runner).run(prompt.capture(), eq("claude-sonnet"));
        assertTrue(prompt.getValue().contains("User: first question"));
        assertTrue(prompt.getValue().contains("Assistant: first answer"));
        assertTrue(prompt.getValue().endsWith("User: second question"));
        assertTrue(response.isSuccess());
        verify(sessions, times(2)).appendMessage(eq("slack:U1"), any(ReActMessage.class));
    }
}
