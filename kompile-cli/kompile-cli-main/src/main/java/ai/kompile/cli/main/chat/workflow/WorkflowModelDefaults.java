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
import ai.kompile.cli.main.chat.config.SetupWizard;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Automatic model assignment for workflow participants, drawn only from models
 * the project already has configured: the chat's own model and its saved chat
 * profiles. The lead keeps the chat's model; workers are spread across the
 * other configured models; a reviewer prefers a model, then a vendor, that no
 * worker uses. Explicit bindings are never replaced.
 *
 * <p>A participant left unbound runs on the lead's model. That holds in-process,
 * where children copy the lead's chat configuration, but not for a CLI-agent
 * lead, whose unbound delegations run on whichever agent it requests — so
 * assignments for a CLI-agent lead are always explicit.</p>
 */
public final class WorkflowModelDefaults {

    /** A configured model a participant can run on, and where it was configured. */
    public record Candidate(WorkflowTeam.ModelBinding binding, String source) {
        public Candidate {
            Objects.requireNonNull(binding, "binding");
        }

        public String label() {
            return binding.label() + " [" + source + "]";
        }
    }

    /** A team with models assigned, and what the user should know about the result. */
    public record Proposal(WorkflowTeam team, List<String> notes) {
        public Proposal {
            Objects.requireNonNull(team, "team");
            notes = List.copyOf(notes);
        }
    }

    private WorkflowModelDefaults() {}

    /** Whether this chat delegates through managed CLI agents rather than in-process. */
    public static boolean delegatesToCliAgents(ChatConfig config) {
        return config != null && "passthrough".equals(config.getChatMode());
    }

    /**
     * The chat's own model as a binding: its in-process route, or for a CLI-agent
     * chat the agent and its selected model. Null when the model is not known.
     */
    public static WorkflowTeam.ModelBinding bindingOf(ChatConfig config) {
        if (config == null) return null;
        try {
            if (delegatesToCliAgents(config)) {
                return agentBindingOf(ChatProfiles.capture("this chat", config));
            }
            if (config.isKompileServer() || blank(config.getProvider()) || blank(config.getModel())) {
                return null;
            }
            return new WorkflowTeam.ModelBinding(config.getProvider(), config.getModel(), config.getThinking(),
                    config.getAuthenticationMethod(), config.getBaseUrl(), null);
        } catch (IllegalArgumentException unusable) {
            return null;
        }
    }

    static WorkflowTeam.ModelBinding bindingOf(ChatProfiles.Profile profile) {
        if (!"standard".equals(profile.mode()) || "kompile".equalsIgnoreCase(profile.provider())
                || blank(profile.model())) {
            return null;
        }
        return new WorkflowTeam.ModelBinding(profile.provider(), profile.model(), profile.thinking(),
                profile.authenticationMethod(), profile.baseUrl(), null);
    }

    static WorkflowTeam.ModelBinding agentBindingOf(ChatProfiles.Profile profile) {
        if (!profile.mode().startsWith("passthrough-") || blank(profile.model())) return null;
        return new WorkflowTeam.ModelBinding(profile.vendor(), profile.model(), profile.thinking(),
                null, null, profile.agent());
    }

    /** A CLI agent running a model, named the way chat profiles name it. */
    public static WorkflowTeam.ModelBinding agentBinding(String agent, String model, String thinking) {
        ChatConfig config = new ChatConfig(null, null, model, null);
        config.setChatMode("passthrough");
        config.setPassthroughAgent(agent);
        config.setThinking(thinking);
        return agentBindingOf(ChatProfiles.capture(agent, config));
    }

    /**
     * The lead's binding when the chat applies it: only an in-process chat runs
     * its lead on another model, and only on an in-process route. A CLI-agent
     * chat leads as its own agent.
     */
    public static WorkflowTeam.ModelBinding leadModel(WorkflowTeam team, ChatConfig current) {
        WorkflowTeam.ModelBinding lead = team.participant(team.lead()).model();
        return lead == null || lead.agent() != null || delegatesToCliAgents(current) ? null : lead;
    }

    /**
     * The configured models a participant can run on: this chat's model first,
     * then the project's saved chat profiles. A CLI-agent chat also offers its
     * saved CLI-agent profiles and drops models no supported CLI agent can run.
     *
     * @throws IOException when the project's chat profiles cannot be read
     */
    public static List<Candidate> candidates(Path projectRoot, ChatConfig current) throws IOException {
        boolean cliAgents = delegatesToCliAgents(current);
        List<Candidate> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        add(result, seen, cliAgents, bindingOf(current), "this chat");
        if (projectRoot != null) {
            for (ChatProfiles.Profile profile : ChatProfiles.list(projectRoot, "standard")) {
                add(result, seen, cliAgents, bindingOf(profile), "profile '" + profile.name() + "'");
            }
            if (cliAgents) {
                for (ChatProfiles.Profile profile : ChatProfiles.list(projectRoot, "passthrough")) {
                    add(result, seen, cliAgents, agentBindingOf(profile), "profile '" + profile.name() + "'");
                }
            }
        }
        return List.copyOf(result);
    }

    private static void add(List<Candidate> result, Set<String> seen, boolean cliAgents,
                            WorkflowTeam.ModelBinding binding, String source) {
        if (binding == null) return;
        String identity;
        if (cliAgents) {
            // What runs is the agent with its model and thinking.
            String agent = WorkflowLaunch.agentFor(binding);
            if (agent == null) return;
            identity = String.join("|", agent, binding.model(), lower(binding.thinking()));
        } else {
            identity = String.join("|", binding.provider(), String.valueOf(binding.baseUrl()),
                    lower(binding.authenticationMethod()), binding.model(), lower(binding.thinking()));
        }
        if (seen.add(identity)) result.add(new Candidate(binding, source));
    }

    /**
     * Assigns models to participants that have none, from {@code candidates}.
     * Workers (every participant except the lead and reviewers) are spread across
     * the candidates whose model differs from the lead's; with none they run on
     * the lead's model. A reviewer (can validate, cannot edit) takes the candidate
     * least shared with the workers, preferring one whose tools the workflow can
     * restrict.
     */
    public static Proposal assign(WorkflowTeam team, ChatConfig current, List<Candidate> candidates) {
        boolean cliAgents = delegatesToCliAgents(current);
        WorkflowTeam.ModelBinding base = baseModel(team, current);
        List<WorkflowTeam.ModelBinding> others = candidates.stream().map(Candidate::binding)
                .filter(binding -> base == null || !sameModel(binding, base))
                .toList();
        WorkflowTeam result = team;
        boolean assigned = false;
        List<WorkflowTeam.ModelBinding> workerModels = new ArrayList<>();
        int next = 0;
        for (WorkflowTeam.Participant participant : team.participants().values()) {
            if (participant.id().equals(team.lead()) || isReviewer(participant)) continue;
            WorkflowTeam.ModelBinding model = participant.model();
            if (model == null) {
                assigned = true;
                model = others.isEmpty() ? base : others.get(next++ % others.size());
                result = result.withParticipant(participant.withModel(stored(model, base, cliAgents)));
            }
            if (model != null) workerModels.add(model);
        }
        for (WorkflowTeam.Participant participant : team.participants().values()) {
            if (participant.id().equals(team.lead()) || !isReviewer(participant)
                    || participant.model() != null) continue;
            assigned = true;
            WorkflowTeam.ModelBinding model = reviewerModel(candidates, base, workerModels, cliAgents);
            result = result.withParticipant(participant.withModel(stored(model, base, cliAgents)));
        }
        List<String> notes = new ArrayList<>();
        if (assigned && others.isEmpty()) {
            if (base != null) {
                notes.add("Only one model is configured (" + base.label() + "), so every participant runs"
                        + " on it. Pick another per participant from the provider's live catalog, or save"
                        + " more models as project profiles with /setup.");
            } else if (cliAgents) {
                notes.add("No configured model can run as a CLI agent, so delegated participants run on"
                        + " whichever agent the lead requests, with that agent's default model.");
            }
        }
        notes.addAll(notes(result, current));
        return new Proposal(result, notes);
    }

    /**
     * Every participant on the chat's own model. In-process that clears every
     * binding, so the team follows the chat; a CLI-agent lead binds the others
     * to its agent and model explicitly, since unbound delegations run on
     * whichever agent the lead requests.
     */
    public static WorkflowTeam onChatModel(WorkflowTeam team, ChatConfig current) {
        boolean cliAgents = delegatesToCliAgents(current);
        WorkflowTeam.ModelBinding chat = cliAgents ? bindingOf(current) : null;
        WorkflowTeam result = team;
        for (WorkflowTeam.Participant participant : team.participants().values()) {
            boolean lead = participant.id().equals(team.lead());
            result = result.withParticipant(participant.withModel(cliAgents && !lead ? chat : null));
        }
        return result;
    }

    /**
     * Binds one participant to {@code model}. A model the participant would run
     * on anyway stays implicit in-process: the lead on the chat's model, anyone
     * else on the lead's.
     */
    public static WorkflowTeam withModel(WorkflowTeam team, String participantId,
                                         WorkflowTeam.ModelBinding model, ChatConfig current) {
        WorkflowTeam.Participant participant = team.participant(participantId);
        if (participant == null) {
            throw new IllegalArgumentException("Workflow '" + team.name() + "' has no participant '"
                    + participantId + "'");
        }
        boolean cliAgents = delegatesToCliAgents(current);
        WorkflowTeam.ModelBinding binding;
        if (participant.id().equals(team.lead())) {
            binding = !cliAgents && model != null && model.equals(bindingOf(current)) ? null : model;
        } else {
            binding = stored(model, baseModel(team, current), cliAgents);
        }
        return team.withParticipant(participant.withModel(binding));
    }

    /**
     * What the user should know about a team's current models: reviewers sharing
     * the workers' model, participants whose tools the workflow cannot
     * restrict, and delegation edges an in-process chat cannot run.
     */
    public static List<String> notes(WorkflowTeam team, ChatConfig current) {
        boolean cliAgents = delegatesToCliAgents(current);
        WorkflowTeam.ModelBinding base = baseModel(team, current);
        List<WorkflowTeam.ModelBinding> workerModels = new ArrayList<>();
        for (WorkflowTeam.Participant participant : team.participants().values()) {
            if (participant.id().equals(team.lead()) || isReviewer(participant)) continue;
            WorkflowTeam.ModelBinding model = effective(participant, base);
            if (model != null) workerModels.add(model);
        }
        List<String> notes = new ArrayList<>();
        for (WorkflowTeam.Participant participant : team.participants().values()) {
            WorkflowTeam.ModelBinding model = participant.model();
            if (model == null) continue;
            String id = "'" + participant.id() + "'";
            if (participant.id().equals(team.lead())) {
                if (cliAgents) {
                    notes.add(id + " leads as this chat's CLI agent; its binding to " + model.label()
                            + " applies when an in-process chat leads.");
                } else if (model.agent() != null) {
                    notes.add(id + " is bound to the " + model.agent() + " CLI agent, which only a CLI-agent"
                            + " chat runs; it leads on the chat's model.");
                }
            } else if (cliAgents && WorkflowLaunch.agentFor(model) == null) {
                notes.add(id + " is bound to " + model.label()
                        + ", which no supported CLI agent runs; delegations to it are refused.");
            } else if (!cliAgents && model.agent() != null) {
                notes.add(id + " runs as the " + model.agent()
                        + " CLI agent, which an in-process lead cannot launch; delegations to it are refused.");
            }
        }
        for (WorkflowTeam.Participant participant : team.participants().values()) {
            if (participant.id().equals(team.lead()) || !isReviewer(participant)) continue;
            WorkflowTeam.ModelBinding model = effective(participant, base);
            if (model != null && workerModels.stream().anyMatch(worker -> sameModel(worker, model))) {
                notes.add("'" + participant.id() + "' reviews on the workers' model (" + model.label()
                        + "); a different model gives it an independent review.");
            }
        }
        if (cliAgents) {
            notes.add("Delegated participants run as CLI agents with their own tools: the workflow sets"
                    + " their models and enforces delegation and gates, not individual tool calls.");
        } else {
            for (WorkflowTeam.Participant participant : team.participants().values()) {
                WorkflowTeam.ModelBinding model = participant.id().equals(team.lead())
                        ? base : effective(participant, base);
                // CLI-agent bindings are refused in-process; noted above.
                if (model == null || model.agent() != null) continue;
                if (!participant.canEdit() && WorkflowLaunch.runsOwnTools(model)) {
                    notes.add("'" + participant.id() + "' runs on " + model.label()
                            + ", which uses its own tools; the workflow cannot stop it from editing.");
                }
            }
            for (WorkflowTeam.Participant participant : team.participants().values()) {
                if (participant.id().equals(team.lead()) || !participant.canDelegate()
                        || participant.delegatesTo().isEmpty()) continue;
                notes.add("'" + participant.id() + "' may delegate, but an in-process delegate cannot"
                        + " delegate further; only the lead delegates in this chat.");
            }
        }
        return notes;
    }

    /** A reviewer validates work without editing it. */
    public static boolean isReviewer(WorkflowTeam.Participant participant) {
        return participant.canValidate() && !participant.canEdit();
    }

    /** What an unbound participant runs on: the model the lead runs on. */
    public static WorkflowTeam.ModelBinding baseModel(WorkflowTeam team, ChatConfig current) {
        WorkflowTeam.ModelBinding lead = leadModel(team, current);
        return lead != null ? lead : bindingOf(current);
    }

    /**
     * What a participant runs on under the chat {@code current} configures (null
     * when unknown), for display: its binding as the chat applies it, or what an
     * unbound participant follows — the chat's model for the lead, the lead's
     * for anyone else. A CLI-agent chat leads as its own agent, and its unbound
     * delegations run on whichever agent the lead requests.
     */
    public static String describe(WorkflowTeam team, WorkflowTeam.Participant participant, ChatConfig current) {
        boolean lead = participant.id().equals(team.lead());
        WorkflowTeam.ModelBinding model = participant.model();
        if (current == null) {
            if (model != null) return model.label();
            return lead ? "the chat's model" : "the lead's model";
        }
        if (delegatesToCliAgents(current)) {
            if (lead) {
                WorkflowTeam.ModelBinding own = bindingOf(current);
                String agent = own != null ? own.label() : current.getPassthroughAgent();
                return "this chat's CLI agent" + (blank(agent) ? "" : " (" + agent + ")");
            }
            return model != null ? model.label() : "the CLI agent the lead requests";
        }
        if (lead) {
            WorkflowTeam.ModelBinding applied = leadModel(team, current);
            if (applied != null) return applied.label();
            WorkflowTeam.ModelBinding own = bindingOf(current);
            return "the chat's model" + (own == null ? "" : " (" + own.label() + ")");
        }
        if (model != null) return model.label();
        WorkflowTeam.ModelBinding base = baseModel(team, current);
        return "the lead's model" + (base == null ? "" : " (" + base.label() + ")");
    }

    private static WorkflowTeam.ModelBinding effective(WorkflowTeam.Participant participant,
                                                       WorkflowTeam.ModelBinding base) {
        return participant.model() != null ? participant.model() : base;
    }

    /** In-process, the lead's own model stays implicit so the participant follows the chat. */
    private static WorkflowTeam.ModelBinding stored(WorkflowTeam.ModelBinding model,
                                                    WorkflowTeam.ModelBinding base, boolean cliAgents) {
        return !cliAgents && Objects.equals(model, base) ? null : model;
    }

    private static WorkflowTeam.ModelBinding reviewerModel(List<Candidate> candidates,
                                                           WorkflowTeam.ModelBinding base,
                                                           List<WorkflowTeam.ModelBinding> workerModels,
                                                           boolean cliAgents) {
        WorkflowTeam.ModelBinding best = null;
        int bestScore = -1;
        for (Candidate candidate : candidates) {
            WorkflowTeam.ModelBinding model = candidate.binding();
            int score = 0;
            if (workerModels.stream().noneMatch(worker -> sameModel(worker, model))) score += 4;
            if (workerModels.stream().noneMatch(worker -> vendor(worker).equals(vendor(model)))) score += 2;
            if (!cliAgents && !WorkflowLaunch.runsOwnTools(model)) score += 1;
            if (score > bestScore) {
                best = model;
                bestScore = score;
            }
        }
        return best != null ? best : base;
    }

    /** Same model on the same endpoint, whichever route or thinking value reaches it. */
    static boolean sameModel(WorkflowTeam.ModelBinding a, WorkflowTeam.ModelBinding b) {
        return a.model().equalsIgnoreCase(b.model())
                && vendor(a).equals(vendor(b))
                && Objects.equals(a.baseUrl(), b.baseUrl());
    }

    private static String vendor(WorkflowTeam.ModelBinding binding) {
        String vendor = binding.agent() != null ? binding.provider() : SetupWizard.vendorForProvider(binding.provider());
        return lower(vendor);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
