/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package ai.kompile.kclaw.gateway;

import ai.kompile.gateway.core.service.AgentRegistry;
import ai.kompile.gateway.core.service.PermissionService;
import ai.kompile.gateway.core.service.SessionService;
import ai.kompile.kclaw.agent.KClawAgentService;
import ai.kompile.kclaw.agent.ToolkitRegistry;
import ai.kompile.kclaw.config.KClawConfig;
import ai.kompile.react.model.ReActMessage;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KClawControllerSessionTest {

    @Test
    void agentAwareHistoryAndClearUseCanonicalAgentService() {
        KClawAgentService agents = mock(KClawAgentService.class);
        SessionService legacy = mock(SessionService.class);
        String agentId = UUID.randomUUID().toString();
        when(agents.getSessionHistory(agentId, "web:shared"))
                .thenReturn(List.of(ReActMessage.user("canonical")));
        KClawController controller = controller(agents, legacy);

        var history = controller.getSessionHistory("web:shared", agentId);
        var cleared = controller.clearSession("web:shared", agentId);

        assertEquals(200, history.getStatusCode().value());
        assertEquals(204, cleared.getStatusCode().value());
        verify(agents).getSessionHistory(agentId, "web:shared");
        verify(agents).clearSession(agentId, "web:shared");
        verify(legacy, never()).loadSession("web:shared");
        verify(legacy, never()).clearSession("web:shared");
    }

    @Test
    void noAgentSelectorPreservesLegacySessionServiceBehavior() {
        KClawAgentService agents = mock(KClawAgentService.class);
        SessionService legacy = mock(SessionService.class);
        when(legacy.loadSession("legacy")).thenReturn(List.of(ReActMessage.user("old")));
        when(legacy.estimateTokenCount("legacy")).thenReturn(1);
        KClawController controller = controller(agents, legacy);

        controller.getSessionHistory("legacy", null);
        controller.clearSession("legacy", null);

        verify(legacy).loadSession("legacy");
        verify(legacy).estimateTokenCount("legacy");
        verify(legacy).clearSession("legacy");
        verify(agents, never()).getSessionHistory("legacy", "legacy");
    }

    private static KClawController controller(
            KClawAgentService agentService,
            SessionService sessionService) {
        return new KClawController(
                agentService,
                mock(AgentRegistry.class),
                mock(ToolkitRegistry.class),
                null,
                sessionService,
                mock(PermissionService.class),
                KClawConfig.defaults());
    }
}
