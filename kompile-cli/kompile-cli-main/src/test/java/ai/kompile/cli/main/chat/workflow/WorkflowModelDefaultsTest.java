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
import ai.kompile.cli.main.chat.config.ChatProfiles;
import ai.kompile.cli.main.chat.testing.TemporaryUserHome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Automatic model assignment draws only on models the project configured (the
 * chat's own and its saved profiles), spreads workers across them, gives a
 * reviewer a model no worker uses, and never replaces an explicit binding. The
 * class runs in a temporary user home so nothing it resolves can reach the
 * real one.
 */
@TemporaryUserHome
class WorkflowModelDefaultsTest {

    /** The chat's own model, as an in-process chat runs it. */
    private static final WorkflowTeam.ModelBinding CHAT_MODEL =
            new WorkflowTeam.ModelBinding("anthropic", "claude-opus-5-5", null);
    /** The same model as a CLI-agent chat runs it, through Claude Code. */
    private static final WorkflowTeam.ModelBinding CLI_CHAT_MODEL =
            new WorkflowTeam.ModelBinding("anthropic", "claude-opus-5-5", null, null, null, "claude");
    private static final WorkflowTeam.ModelBinding FAST = new WorkflowTeam.ModelBinding("openai", "gpt-5-mini", null);
    private static final WorkflowTeam.ModelBinding DEEP = new WorkflowTeam.ModelBinding("openai", "gpt-5", null);
    /** The Claude Code route, which runs its own tools. */
    private static final WorkflowTeam.ModelBinding NATIVE =
            new WorkflowTeam.ModelBinding("anthropic", "claude-sonnet-5", null, "oauth", null, null);
    /** A provider no supported CLI agent runs. */
    private static final WorkflowTeam.ModelBinding ACME = new WorkflowTeam.ModelBinding("acme", "acme-large", null);

    private static final String CLI_AGENT_NOTE = "Delegated participants run as CLI agents with their own tools:"
            + " the workflow sets their models and enforces delegation and gates, not individual tool calls.";

    @TempDir
    Path project;

    // ── Candidates ──────────────────────────────────────────────────────────

    @Test
    void candidatesAreThisChatThenSavedProfilesWithoutDuplicates() throws IOException {
        save(standard("fast", "openai", "gpt-5-mini"));
        save(standard("same", "anthropic", "claude-opus-5-5"));   // the chat's own model again
        save(standard("acme", "acme", "acme-large"));
        save(codexHigh());                                        // offered only to a CLI-agent chat

        assertEquals(List.of(
                        "anthropic/claude-opus-5-5 [this chat]",
                        "openai/gpt-5-mini [profile 'fast']",
                        "acme/acme-large [profile 'acme']"),
                labels(WorkflowModelDefaults.candidates(project, chat())));
    }

    @Test
    void aCliAgentChatIsOfferedOnlyModelsASupportedAgentRuns() throws IOException {
        save(standard("fast", "openai", "gpt-5-mini"));           // Codex runs it
        save(standard("same", "anthropic", "claude-opus-5-5"));   // Claude Code already runs it for this chat
        save(standard("acme", "acme", "acme-large"));             // no agent runs it
        save(codexHigh());

        assertEquals(List.of(
                        "anthropic/claude-opus-5-5 via claude [this chat]",
                        "openai/gpt-5-mini [profile 'fast']",
                        "openai/gpt-5.1-codex (thinking: high) via codex [profile 'codex-high']"),
                labels(WorkflowModelDefaults.candidates(project, cliChat())));
    }

    @Test
    void withoutAProjectOnlyThisChatIsACandidate() throws IOException {
        assertEquals(List.of("anthropic/claude-opus-5-5 [this chat]"),
                labels(WorkflowModelDefaults.candidates(null, chat())));
        assertEquals(List.of(), WorkflowModelDefaults.candidates(project, null));
    }

    // ── Assignment ──────────────────────────────────────────────────────────

    @Test
    void workersSpreadAcrossTheModelsTheLeadDoesNotUse() {
        WorkflowTeam team = team(lead("w1", "w2", "w3"), worker("w1"), worker("w2"), worker("w3"));

        WorkflowModelDefaults.Proposal proposal =
                WorkflowModelDefaults.assign(team, chat(), candidates(CHAT_MODEL, FAST, DEEP));

        assertNull(modelOf(proposal.team(), "lead"), "the lead keeps the chat's model");
        assertEquals(FAST, modelOf(proposal.team(), "w1"));
        assertEquals(DEEP, modelOf(proposal.team(), "w2"));
        assertEquals(FAST, modelOf(proposal.team(), "w3"));
        assertEquals(List.of(), proposal.notes());
    }

    @Test
    void withOneConfiguredModelEveryoneFollowsTheChat() {
        WorkflowTeam team = team(lead("w1", "reviewer"), worker("w1"), reviewer());

        WorkflowModelDefaults.Proposal proposal =
                WorkflowModelDefaults.assign(team, chat(), candidates(CHAT_MODEL));

        for (WorkflowTeam.Participant participant : proposal.team().participants().values()) {
            assertNull(participant.model(), participant.id() + " should follow the chat");
        }
        assertEquals(List.of(
                        "Only one model is configured (anthropic/claude-opus-5-5), so every participant runs on"
                                + " it. Pick another per participant from the provider's live catalog, or save"
                                + " more models as project profiles with /setup.",
                        "'reviewer' reviews on the workers' model (anthropic/claude-opus-5-5); a different"
                                + " model gives it an independent review."),
                proposal.notes());
    }

    @Test
    void theReviewerKeepsTheLeadsModelWhenWorkersMoveOffIt() {
        WorkflowTeam team = team(lead("w1", "reviewer"), worker("w1"), reviewer());

        WorkflowModelDefaults.Proposal proposal =
                WorkflowModelDefaults.assign(team, chat(), candidates(CHAT_MODEL, FAST));

        assertEquals(FAST, modelOf(proposal.team(), "w1"));
        assertNull(modelOf(proposal.team(), "reviewer"), "no worker uses the chat's model, so the reviewer keeps it");
        assertEquals(List.of(), proposal.notes());
    }

    @Test
    void theReviewerAvoidsAModelAWorkerIsBoundTo() {
        WorkflowTeam team = team(lead("w1", "reviewer"), worker("w1").withModel(CHAT_MODEL), reviewer());

        WorkflowModelDefaults.Proposal proposal =
                WorkflowModelDefaults.assign(team, chat(), candidates(CHAT_MODEL, FAST));

        assertEquals(CHAT_MODEL, modelOf(proposal.team(), "w1"), "an explicit binding is never replaced");
        assertEquals(FAST, modelOf(proposal.team(), "reviewer"));
        assertEquals(List.of(), proposal.notes());
    }

    @Test
    void theReviewerPrefersAModelWhoseToolsTheWorkflowRestricts() {
        ChatConfig chat = new ChatConfig("openai", null, "gpt-5-mini", null);
        chat.setChatMode("standard");
        WorkflowTeam team = team(lead("w1", "reviewer"), worker("w1").withModel(FAST), reviewer());

        // Both Anthropic models are unused and of another vendor; the Claude Code
        // route runs its own tools, so the reviewer takes the API route.
        WorkflowModelDefaults.Proposal proposal =
                WorkflowModelDefaults.assign(team, chat, candidates(FAST, NATIVE, CHAT_MODEL));

        assertEquals(CHAT_MODEL, modelOf(proposal.team(), "reviewer"));
        assertEquals(List.of(), proposal.notes());
    }

    @Test
    void aCliAgentLeadBindsEveryDelegateExplicitly() {
        WorkflowTeam team = team(lead("w1", "reviewer"), worker("w1"), reviewer());

        WorkflowModelDefaults.Proposal proposal =
                WorkflowModelDefaults.assign(team, cliChat(), candidates(CLI_CHAT_MODEL, FAST));

        assertNull(modelOf(proposal.team(), "lead"), "a CLI-agent chat leads as its own agent");
        assertEquals(FAST, modelOf(proposal.team(), "w1"));
        // An unbound delegation runs on whichever agent the lead requests, so even
        // the chat's own agent is written down.
        assertEquals(CLI_CHAT_MODEL, modelOf(proposal.team(), "reviewer"));
        assertEquals(List.of(CLI_AGENT_NOTE), proposal.notes());
    }

    @Test
    void aCliAgentChatWithoutAKnownModelLeavesDelegatesToTheLeadsRequest() {
        ChatConfig chat = cliChat();
        chat.setModel(null);
        WorkflowTeam team = team(lead("w1"), worker("w1"));

        WorkflowModelDefaults.Proposal proposal = WorkflowModelDefaults.assign(team, chat, List.of());

        assertNull(modelOf(proposal.team(), "w1"));
        assertEquals(List.of(
                        "No configured model can run as a CLI agent, so delegated participants run on"
                                + " whichever agent the lead requests, with that agent's default model.",
                        CLI_AGENT_NOTE),
                proposal.notes());
    }

    // ── Following the chat and explicit bindings ────────────────────────────

    @Test
    void onTheChatModelClearsBindingsInProcessAndBindsDelegatesForACliAgentLead() {
        WorkflowTeam team = team(lead("w1", "reviewer").withModel(FAST),
                worker("w1").withModel(DEEP), reviewer().withModel(ACME));

        WorkflowTeam inProcess = WorkflowModelDefaults.onChatModel(team, chat());
        for (WorkflowTeam.Participant participant : inProcess.participants().values()) {
            assertNull(participant.model(), participant.id() + " should follow the chat");
        }

        WorkflowTeam cli = WorkflowModelDefaults.onChatModel(team, cliChat());
        assertNull(modelOf(cli, "lead"));
        assertEquals(CLI_CHAT_MODEL, modelOf(cli, "w1"));
        assertEquals(CLI_CHAT_MODEL, modelOf(cli, "reviewer"));
    }

    @Test
    void aModelTheParticipantRunsOnAnywayStaysImplicitInProcess() {
        WorkflowTeam team = team(lead("w1"), worker("w1"));

        assertNull(modelOf(WorkflowModelDefaults.withModel(team, "lead", CHAT_MODEL, chat()), "lead"),
                "the lead on the chat's model follows the chat");
        assertEquals(FAST, modelOf(WorkflowModelDefaults.withModel(team, "lead", FAST, chat()), "lead"));
        assertNull(modelOf(WorkflowModelDefaults.withModel(team, "w1", CHAT_MODEL, chat()), "w1"),
                "a worker on the lead's model follows the lead");

        WorkflowTeam fastLead = WorkflowModelDefaults.withModel(team, "lead", FAST, chat());
        assertNull(modelOf(WorkflowModelDefaults.withModel(fastLead, "w1", FAST, chat()), "w1"));
        assertEquals(CHAT_MODEL, modelOf(WorkflowModelDefaults.withModel(fastLead, "w1", CHAT_MODEL, chat()), "w1"),
                "once the lead moves, the chat's model is a real choice");
    }

    @Test
    void aCliAgentLeadKeepsEveryBindingExplicit() {
        WorkflowTeam team = team(lead("w1"), worker("w1"));

        assertEquals(CLI_CHAT_MODEL,
                modelOf(WorkflowModelDefaults.withModel(team, "w1", CLI_CHAT_MODEL, cliChat()), "w1"));
        assertEquals(FAST, modelOf(WorkflowModelDefaults.withModel(team, "lead", FAST, cliChat()), "lead"));
    }

    @Test
    void bindingAnUnknownParticipantIsRefused() {
        WorkflowTeam team = team(lead("w1"), worker("w1"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> WorkflowModelDefaults.withModel(team, "ghost", FAST, chat()));
        assertEquals("Workflow 'team' has no participant 'ghost'", refused.getMessage());
    }

    // ── Notes ───────────────────────────────────────────────────────────────

    @Test
    void inProcessNotesNameWhatTheWorkflowCannotEnforce() {
        WorkflowTeam team = team(lead("reviewer", "coder", "planner"),
                reviewer().withModel(NATIVE),
                worker("coder").withModel(WorkflowModelDefaults.agentBinding("codex", "gpt-5.1-codex", null)),
                new WorkflowTeam.Participant("planner", "designer", "cli",
                        List.of("read", "plan", "delegate"), List.of("coder")));

        assertEquals(List.of(
                        "'coder' runs as the codex CLI agent, which an in-process lead cannot launch;"
                                + " delegations to it are refused.",
                        "'reviewer' runs on anthropic/claude-sonnet-5, which uses its own tools; the workflow"
                                + " cannot stop it from editing.",
                        "'planner' may delegate, but an in-process delegate cannot delegate further; only the"
                                + " lead delegates in this chat."),
                WorkflowModelDefaults.notes(team, chat()));
    }

    @Test
    void cliAgentNotesNameBindingsTheChatCannotRun() {
        WorkflowTeam team = team(lead("w1").withModel(FAST), worker("w1").withModel(ACME));

        assertEquals(List.of(
                        "'lead' leads as this chat's CLI agent; its binding to openai/gpt-5-mini applies when"
                                + " an in-process chat leads.",
                        "'w1' is bound to acme/acme-large, which no supported CLI agent runs; delegations to"
                                + " it are refused.",
                        CLI_AGENT_NOTE),
                WorkflowModelDefaults.notes(team, cliChat()));
    }

    @Test
    void anInProcessLeadBoundToACliAgentLeadsOnTheChatModel() {
        WorkflowTeam team = team(
                lead("w1").withModel(WorkflowModelDefaults.agentBinding("codex", "gpt-5.1-codex", null)),
                worker("w1"));

        assertNull(WorkflowModelDefaults.leadModel(team, chat()));
        assertEquals(List.of("'lead' is bound to the codex CLI agent, which only a CLI-agent chat runs;"
                        + " it leads on the chat's model."),
                WorkflowModelDefaults.notes(team, chat()));
        assertEquals("the chat's model (anthropic/claude-opus-5-5)",
                WorkflowModelDefaults.describe(team, team.participant("lead"), chat()));
    }

    // ── Display ─────────────────────────────────────────────────────────────

    @Test
    void describeSaysWhatEachParticipantRunsOn() {
        WorkflowTeam team = team(lead("w1", "w2"), worker("w1"), worker("w2").withModel(FAST));
        WorkflowTeam.Participant lead = team.participant("lead");
        WorkflowTeam.Participant unbound = team.participant("w1");
        WorkflowTeam.Participant bound = team.participant("w2");

        // Without a chat, only the bindings themselves are known.
        assertEquals("the chat's model", WorkflowModelDefaults.describe(team, lead, null));
        assertEquals("the lead's model", WorkflowModelDefaults.describe(team, unbound, null));
        assertEquals("openai/gpt-5-mini", WorkflowModelDefaults.describe(team, bound, null));

        assertEquals("the chat's model (anthropic/claude-opus-5-5)",
                WorkflowModelDefaults.describe(team, lead, chat()));
        assertEquals("the lead's model (anthropic/claude-opus-5-5)",
                WorkflowModelDefaults.describe(team, unbound, chat()));
        assertEquals("openai/gpt-5-mini", WorkflowModelDefaults.describe(team, bound, chat()));

        WorkflowTeam deepLead = WorkflowModelDefaults.withModel(team, "lead", DEEP, chat());
        assertEquals("openai/gpt-5", WorkflowModelDefaults.describe(deepLead, deepLead.participant("lead"), chat()));
        assertEquals("the lead's model (openai/gpt-5)",
                WorkflowModelDefaults.describe(deepLead, deepLead.participant("w1"), chat()));

        assertEquals("this chat's CLI agent (anthropic/claude-opus-5-5 via claude)",
                WorkflowModelDefaults.describe(team, lead, cliChat()));
        assertEquals("the CLI agent the lead requests", WorkflowModelDefaults.describe(team, unbound, cliChat()));
        assertEquals("openai/gpt-5-mini", WorkflowModelDefaults.describe(team, bound, cliChat()));

        ChatConfig unknownModel = cliChat();
        unknownModel.setModel(null);
        assertEquals("this chat's CLI agent (claude)", WorkflowModelDefaults.describe(team, lead, unknownModel));
    }

    // ── Bindings ────────────────────────────────────────────────────────────

    @Test
    void agentBindingsAreNamedTheWayChatProfilesNameThem() {
        WorkflowTeam.ModelBinding codex = WorkflowModelDefaults.agentBinding("codex", "gpt-5.1-codex", "high");

        assertEquals(new WorkflowTeam.ModelBinding("openai", "gpt-5.1-codex", "high", null, null, "codex"), codex);
        assertEquals("openai/gpt-5.1-codex (thinking: high) via codex", codex.label());
        assertEquals(CLI_CHAT_MODEL, WorkflowModelDefaults.agentBinding("claude", "claude-opus-5-5", null));
        assertEquals(CLI_CHAT_MODEL, WorkflowModelDefaults.bindingOf(cliChat()));
    }

    @Test
    void theChatsOwnBindingKeepsItsRouteAndIsNullWhenUnknown() {
        ChatConfig proxied = new ChatConfig("openai", null, "gpt-5", "https://proxy.example/v1");
        proxied.setChatMode("standard");
        proxied.setThinking("high");
        proxied.setAuthenticationMethod("api-key");

        assertEquals(new WorkflowTeam.ModelBinding("openai", "gpt-5", "high", "api-key",
                        "https://proxy.example/v1", null),
                WorkflowModelDefaults.bindingOf(proxied));
        assertNull(WorkflowModelDefaults.bindingOf((ChatConfig) null));
        assertNull(WorkflowModelDefaults.bindingOf(new ChatConfig("kompile", null, "default", null)),
                "a kompile server chooses its own model");
        assertNull(WorkflowModelDefaults.bindingOf(new ChatConfig("openai", null, " ", null)));
    }

    @Test
    void onlyStandardNonServerProfilesBindInProcess() {
        assertEquals(FAST, WorkflowModelDefaults.bindingOf(standard("fast", "openai", "gpt-5-mini")));
        assertNull(WorkflowModelDefaults.bindingOf(standard("server", "kompile", "default")));
        assertNull(WorkflowModelDefaults.bindingOf(codexHigh()), "a CLI-agent profile is not an in-process route");

        assertEquals(WorkflowModelDefaults.agentBinding("codex", "gpt-5.1-codex", "high"),
                WorkflowModelDefaults.agentBindingOf(codexHigh()));
        assertNull(WorkflowModelDefaults.agentBindingOf(standard("fast", "openai", "gpt-5-mini")));
    }

    @Test
    void theLeadsBindingAppliesOnlyToAnInProcessChat() {
        WorkflowTeam team = team(lead("w1").withModel(FAST), worker("w1"));

        assertEquals(FAST, WorkflowModelDefaults.leadModel(team, chat()));
        assertEquals(FAST, WorkflowModelDefaults.baseModel(team, chat()));
        assertNull(WorkflowModelDefaults.leadModel(team, cliChat()), "a CLI-agent chat leads as its own agent");
        assertEquals(CLI_CHAT_MODEL, WorkflowModelDefaults.baseModel(team, cliChat()));
    }

    @Test
    void sameModelIgnoresRouteAndThinkingButNotTheEndpoint() {
        assertTrue(WorkflowModelDefaults.sameModel(CHAT_MODEL,
                new WorkflowTeam.ModelBinding("anthropic", "Claude-Opus-5-5", "high", "oauth", null, null)));
        assertTrue(WorkflowModelDefaults.sameModel(CHAT_MODEL, CLI_CHAT_MODEL));
        assertFalse(WorkflowModelDefaults.sameModel(CHAT_MODEL,
                new WorkflowTeam.ModelBinding("anthropic", "claude-opus-5-5", null, null,
                        "https://proxy.example/v1", null)));
        assertFalse(WorkflowModelDefaults.sameModel(FAST, DEEP));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static ChatConfig chat() {
        ChatConfig config = new ChatConfig("anthropic", null, "claude-opus-5-5", null);
        config.setChatMode("standard");
        return config;
    }

    /** A managed CLI-agent chat running Claude Code on the chat's model. */
    private static ChatConfig cliChat() {
        ChatConfig config = new ChatConfig(null, null, "claude-opus-5-5", null);
        config.setChatMode("passthrough");
        config.setPassthroughAgent("claude");
        return config;
    }

    private static WorkflowTeam.Participant lead(String... delegatesTo) {
        return new WorkflowTeam.Participant("lead", "designer", "cli",
                List.of("read", "plan", "delegate"), List.of(delegatesTo));
    }

    private static WorkflowTeam.Participant worker(String id) {
        return new WorkflowTeam.Participant(id, "implementer", "cli",
                List.of("read", "edit-assigned-files"), List.of());
    }

    private static WorkflowTeam.Participant reviewer() {
        return new WorkflowTeam.Participant("reviewer", "reviewer", "cli", List.of("read", "validate"), List.of());
    }

    private static WorkflowTeam team(WorkflowTeam.Participant lead, WorkflowTeam.Participant... others) {
        Map<String, WorkflowTeam.Participant> participants = new LinkedHashMap<>();
        participants.put(lead.id(), lead);
        for (WorkflowTeam.Participant other : others) {
            participants.put(other.id(), other);
        }
        return new WorkflowTeam("team", 1, lead.id(), participants, Map.of(), null, null);
    }

    private static List<WorkflowModelDefaults.Candidate> candidates(WorkflowTeam.ModelBinding... bindings) {
        return Arrays.stream(bindings).map(binding -> new WorkflowModelDefaults.Candidate(binding, "configured")).toList();
    }

    private static WorkflowTeam.ModelBinding modelOf(WorkflowTeam team, String id) {
        return team.participant(id).model();
    }

    private static ChatProfiles.Profile standard(String name, String provider, String model) {
        return new ChatProfiles.Profile(name, provider, "standard", provider, model,
                null, null, null, false, false, null);
    }

    private static ChatProfiles.Profile codexHigh() {
        return new ChatProfiles.Profile("codex-high", "openai", "passthrough-managed", null,
                "gpt-5.1-codex", "high", null, null, false, false, "codex");
    }

    private void save(ChatProfiles.Profile profile) throws IOException {
        assertTrue(ChatProfiles.save(project, profile, false), "profile '" + profile.name() + "' should save");
    }

    private static List<String> labels(List<WorkflowModelDefaults.Candidate> candidates) {
        return candidates.stream().map(WorkflowModelDefaults.Candidate::label).toList();
    }
}
