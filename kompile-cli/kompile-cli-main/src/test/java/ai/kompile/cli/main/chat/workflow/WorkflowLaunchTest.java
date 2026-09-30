/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ai.kompile.cli.main.chat.workflow;

import ai.kompile.cli.main.chat.config.ChatConfig;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A bound participant runs exactly its binding: in-process on an isolated copy
 * of the lead's configuration that keeps the lead's credentials only on the
 * lead's own route, or as the CLI agent that runs its model. The class runs in
 * a temporary user home so nothing it resolves can reach the real one.
 */
@TemporaryUserHome
class WorkflowLaunchTest {

    // ── In-process configuration ────────────────────────────────────────────

    @Test
    void withoutABindingAParticipantRunsOnACopyOfTheLeadsConfiguration() {
        ChatConfig parent = parent();

        ChatConfig child = WorkflowLaunch.configFor(parent, null);

        assertNotSame(parent, child);
        assertEquals("anthropic", child.getProvider());
        assertEquals("claude-opus-5-5", child.getModel());
        assertTrue(child.isFastMode());
        assertTrue(child.isUltracode());
        assertEquals(200_000, child.getContextWindowTokens());
    }

    @Test
    void aBindingOnTheLeadsRouteChangesOnlyTheModelAndThinking() {
        ChatConfig parent = parent();

        ChatConfig child = WorkflowLaunch.configFor(parent,
                new WorkflowTeam.ModelBinding("anthropic", "claude-sonnet-5", "high"));

        assertEquals("anthropic", child.getProvider());
        assertEquals("claude-sonnet-5", child.getModel());
        assertEquals("high", child.getThinking());
        // The lead's fast mode, ultracode and token limits describe another model.
        assertFalse(child.isFastMode());
        assertFalse(child.isUltracode());
        assertEquals(0, child.getContextWindowTokens());
        assertEquals(0, child.getMaxOutputTokens());

        assertEquals("claude-opus-5-5", parent.getModel(), "the lead's configuration is untouched");
        assertTrue(parent.isFastMode());
        assertEquals(200_000, parent.getContextWindowTokens());
    }

    @Test
    void theLeadsModelWithAnotherThinkingValueKeepsItsTokenLimits() {
        ChatConfig child = WorkflowLaunch.configFor(parent(),
                new WorkflowTeam.ModelBinding("anthropic", "claude-opus-5-5", "low"));

        assertEquals("low", child.getThinking());
        assertEquals(200_000, child.getContextWindowTokens());
        assertEquals(32_000, child.getMaxOutputTokens());
        assertFalse(child.isFastMode(), "a bound participant runs exactly its binding");
    }

    @Test
    void aBindingOnAnotherRouteResolvesItsOwnRoute() {
        ChatConfig parent = parent();
        parent.setAuthenticationMethod("oauth");

        ChatConfig child = WorkflowLaunch.configFor(parent, new WorkflowTeam.ModelBinding(
                "openai", "gpt-5-mini", "medium", null, "https://proxy.example/v1", null));

        assertEquals("openai", child.getProvider());
        assertEquals("gpt-5-mini", child.getModel());
        assertEquals("medium", child.getThinking());
        assertEquals("https://proxy.example/v1", child.getBaseUrl());
        assertNull(child.getAuthenticationMethod(), "the lead's sign-in never reaches another provider");
        assertFalse(child.isFastMode());
        assertEquals(0, child.getContextWindowTokens());

        assertEquals("anthropic", parent.getProvider(), "the lead's configuration is untouched");
        assertEquals("oauth", parent.getAuthenticationMethod());
    }

    @Test
    void aSignInMethodOtherThanTheLeadsIsAnotherRoute() {
        ChatConfig child = WorkflowLaunch.configFor(parent(), new WorkflowTeam.ModelBinding(
                "anthropic", "claude-sonnet-5", null, "oauth", null, null));

        assertEquals("anthropic", child.getProvider());
        assertEquals("claude-sonnet-5", child.getModel());
        assertEquals("oauth", child.getAuthenticationMethod());
    }

    @Test
    void aCliAgentBindingIsNotAnInProcessRoute() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> WorkflowLaunch.configFor(parent(),
                        WorkflowModelDefaults.agentBinding("codex", "gpt-5.1-codex", null)));

        assertEquals("openai/gpt-5.1-codex via codex runs through a CLI agent, not an in-process chat",
                refused.getMessage());
    }

    // ── CLI agents ──────────────────────────────────────────────────────────

    @Test
    void aBindingRunsOnItsOwnAgentElseItsVendorsAgent() {
        assertEquals("claude", WorkflowLaunch.agentFor(
                new WorkflowTeam.ModelBinding("anthropic", "claude-opus-5-5", null)));
        assertEquals("codex", WorkflowLaunch.agentFor(new WorkflowTeam.ModelBinding("openai", "gpt-5-mini", null)));
        assertEquals("gemini", WorkflowLaunch.agentFor(new WorkflowTeam.ModelBinding("gemini", "gemini-3-pro", null)));
        assertEquals("opencode", WorkflowLaunch.agentFor(
                new WorkflowTeam.ModelBinding("openai", "gpt-5", null, null, null, "opencode")));
        assertEquals("codex", WorkflowLaunch.agentFor(
                new WorkflowTeam.ModelBinding("openai", "gpt-5.1-codex", null, null, null, "Codex")));
    }

    @Test
    void aBindingNoSupportedAgentRunsHasNoAgent() {
        // Callers refuse these rather than fall back to another agent.
        assertNull(WorkflowLaunch.agentFor(new WorkflowTeam.ModelBinding("acme", "acme-large", null)));
        assertNull(WorkflowLaunch.agentFor(new WorkflowTeam.ModelBinding("openai", "gpt-5", null, null, null, "aider")));
        assertNull(WorkflowLaunch.agentFor(null));
    }

    @Test
    void cliAgentsAndNativeRoutesRunTheirOwnTools() {
        assertTrue(WorkflowLaunch.runsOwnTools(WorkflowModelDefaults.agentBinding("codex", "gpt-5.1-codex", null)));
        assertTrue(WorkflowLaunch.runsOwnTools(
                new WorkflowTeam.ModelBinding("anthropic", "claude-sonnet-5", null, "oauth", null, null)));
        assertTrue(WorkflowLaunch.runsOwnTools(
                new WorkflowTeam.ModelBinding("anthropic", "claude-sonnet-5", null, "native", null, null)));
        assertTrue(WorkflowLaunch.runsOwnTools(new WorkflowTeam.ModelBinding("opencode", "big-pickle", null)));

        assertFalse(WorkflowLaunch.runsOwnTools(
                new WorkflowTeam.ModelBinding("anthropic", "claude-sonnet-5", null, "api-key", null, null)));
        assertFalse(WorkflowLaunch.runsOwnTools(new WorkflowTeam.ModelBinding("anthropic", "claude-sonnet-5", null)));
        assertFalse(WorkflowLaunch.runsOwnTools(new WorkflowTeam.ModelBinding("openai", "gpt-5-mini", null)));
        assertFalse(WorkflowLaunch.runsOwnTools(null));
    }

    /** An in-process lead on Anthropic's API with per-model settings a child must not inherit blindly. */
    private static ChatConfig parent() {
        ChatConfig parent = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        parent.setChatMode("standard");
        parent.setFastMode(true);
        parent.setUltracode(true);
        parent.setContextWindowTokens(200_000);
        parent.setMaxOutputTokens(32_000);
        return parent;
    }
}
