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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A named, enforceable team configuration for a chat session: who leads, which
 * roles perform which work on which configured model, who may delegate to whom,
 * and which gates must be satisfied before work proceeds or completes.
 *
 * <p>This is the team-configuration workflow concept (distinct from the
 * enforcer's turn-discipline {@link WorkflowPolicy}). It reuses roles for
 * responsibility and configured chat models for execution; workflow-local
 * participant names own their restrictions. Definitions stay deliberately
 * small: participants, their model bindings, permitted delegation edges, and
 * optional gates — no general purpose workflow language.</p>
 *
 * <p>Example {@code chat-workflows.json} entry:</p>
 * <pre>{@code
 * {
 *   "workflows": {
 *     "designer-workers": {
 *       "version": 1,
 *       "lead": "designer",
 *       "participants": {
 *         "designer": { "id": "designer", "role": "architect",
 *                       "capabilities": ["read", "plan", "delegate"],
 *                       "delegatesTo": ["worker"],
 *                       "model": { "provider": "anthropic", "model": "claude-opus-4-5" } },
 *         "worker":   { "id": "worker", "role": "implementer",
 *                       "capabilities": ["read", "edit-assigned-files", "validate"],
 *                       "model": { "provider": "openai", "model": "gpt-5", "thinking": "medium" } }
 *       },
 *       "routing": { "implement": "worker" },
 *       "limits": { "maxConcurrentWorkers": 3 },
 *       "gates": { "implementationRequires": "user-approved-design" }
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>A participant without a {@code model} runs on the lead chat's own model.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class WorkflowTeam {

    /** Valid participant capability names; enforcement maps them to permission keys. */
    public static final Set<String> VALID_CAPABILITIES = Set.of(
            "read", "plan", "delegate", "edit-assigned-files", "validate", "chat-only");

    private final String name;
    private final int version;
    private final String lead;
    private final Map<String, Participant> participants;
    private final Map<String, String> routing;
    private final int maxConcurrentWorkers;
    private final Gates gates;

    @JsonCreator
    public WorkflowTeam(
            @JsonProperty("name") String name,
            @JsonProperty("version") Integer version,
            @JsonProperty("lead") String lead,
            @JsonProperty("participants") Map<String, Participant> participants,
            @JsonProperty("routing") Map<String, String> routing,
            @JsonProperty("limits") Limits limits,
            @JsonProperty("gates") Gates gates) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Workflow name is required");
        }
        this.name = name.trim();
        int resolvedVersion = version == null ? 1 : version;
        if (resolvedVersion < 1) {
            throw new IllegalArgumentException("Workflow '" + this.name + "' version must be >= 1");
        }
        this.version = resolvedVersion;
        if (participants == null || participants.isEmpty()) {
            throw new IllegalArgumentException("Workflow '" + this.name + "' needs at least one participant");
        }
        // Declaration order is presentation order (wizard tables, summaries, prompts).
        Map<String, Participant> normalized = new LinkedHashMap<>();
        participants.forEach((id, participant) -> {
            if (participant == null) {
                throw new IllegalArgumentException("Workflow '" + this.name + "' has a null participant");
            }
            if (!WorkflowTeam.key(id).equals(participant.id())) {
                throw new IllegalArgumentException("Workflow '" + this.name + "' participant key '" + id
                        + "' does not match participant id '" + participant.id() + "'");
            }
            if (normalized.put(participant.id(), participant) != null) {
                throw new IllegalArgumentException("Workflow '" + this.name + "' declares participant '"
                        + participant.id() + "' twice");
            }
        });
        this.participants = Collections.unmodifiableMap(normalized);
        if (lead == null || lead.isBlank()) {
            throw new IllegalArgumentException("Workflow '" + this.name + "' needs a lead participant");
        }
        String leadKey = key(lead);
        if (!this.participants.containsKey(leadKey)) {
            throw new IllegalArgumentException("Workflow '" + this.name + "' lead '" + lead
                    + "' is not a participant");
        }
        this.lead = leadKey;
        // Purposes and targets are case-insensitive; store them normalized so
        // route() lookups match what validate() checked.
        Map<String, String> routes = new LinkedHashMap<>();
        if (routing != null) {
            routing.forEach((purpose, target) -> {
                String purposeKey = key(purpose);
                if (purposeKey.isEmpty()) {
                    throw new IllegalArgumentException("Workflow '" + this.name + "' has a blank routing purpose");
                }
                if (routes.put(purposeKey, key(target)) != null) {
                    throw new IllegalArgumentException("Workflow '" + this.name + "' routes purpose '"
                            + purposeKey + "' twice");
                }
            });
        }
        this.routing = Collections.unmodifiableMap(routes);
        this.maxConcurrentWorkers = limits == null ? 1 : Math.max(1, limits.maxConcurrentWorkers());
        this.gates = gates == null ? Gates.NONE : gates;
        validate();
    }

    /** Participant ids and purposes are case-insensitive. */
    public static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validate() {
        // Routing purposes must name known participants.
        for (Map.Entry<String, String> entry : routing.entrySet()) {
            String target = entry.getValue();
            if (target.isEmpty() || !participants.containsKey(target)) {
                throw new IllegalArgumentException("Workflow '" + name + "' routes purpose '"
                        + entry.getKey() + "' to unknown participant '" + target + "'");
            }
        }
        // Delegation edges must connect known participants and stay acyclic.
        for (Participant participant : participants.values()) {
            for (String rawTarget : participant.delegatesTo()) {
                if (!participants.containsKey(rawTarget)) {
                    throw new IllegalArgumentException("Workflow '" + name + "' participant '"
                            + participant.id() + "' delegates to unknown participant '" + rawTarget + "'");
                }
            }
            if (reaches(participant.id(), participant.id())) {
                throw new IllegalArgumentException("Workflow '" + name
                        + "' delegation edges form a cycle through '" + participant.id() + "'");
            }
        }
    }

    /** Depth-first cycle check over delegation edges. */
    private boolean reaches(String from, String target) {
        Participant participant = participants.get(from);
        if (participant == null) return false;
        for (String next : participant.delegatesTo()) {
            if (next.equals(target)) return true;
            if (reaches(next, target)) return true;
        }
        return false;
    }

    @JsonProperty("name") public String name() { return name; }
    @JsonProperty("version") public int version() { return version; }
    @JsonProperty("lead") public String lead() { return lead; }
    @JsonProperty("participants") public Map<String, Participant> participants() { return participants; }
    @JsonProperty("routing") public Map<String, String> routing() { return routing; }
    @JsonProperty("maxConcurrentWorkers") public int maxConcurrentWorkers() { return maxConcurrentWorkers; }
    @JsonProperty("gates") public Gates gates() { return gates; }

    @JsonProperty("limits") public Limits limits() { return new Limits(maxConcurrentWorkers); }

    /**
     * A copy with one participant replaced by id. The version is unchanged:
     * {@link WorkflowTeamStore#save} bumps it when the stored definition changes.
     */
    public WorkflowTeam withParticipant(Participant replacement) {
        Objects.requireNonNull(replacement, "participant");
        if (!participants.containsKey(replacement.id())) {
            throw new IllegalArgumentException("Workflow '" + name + "' has no participant '"
                    + replacement.id() + "'");
        }
        Map<String, Participant> updated = new LinkedHashMap<>(participants);
        updated.put(replacement.id(), replacement);
        return new WorkflowTeam(name, version, lead, updated, routing, limits(), gates);
    }

    public WorkflowTeam withVersion(int newVersion) {
        return new WorkflowTeam(name, newVersion, lead, participants, routing, limits(), gates);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WorkflowTeam other
                && name.equals(other.name)
                && version == other.version
                && lead.equals(other.lead)
                && participants.equals(other.participants)
                && routing.equals(other.routing)
                && maxConcurrentWorkers == other.maxConcurrentWorkers
                && gates.equals(other.gates);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, version, lead, participants, routing, maxConcurrentWorkers, gates);
    }

    public Participant participant(String id) {
        return participants.get(key(id));
    }

    /** Returns the participant bound to a routing purpose, or null when unrouted. */
    public Participant route(String purpose) {
        if (purpose == null || purpose.isBlank()) return null;
        String target = routing.get(key(purpose));
        return target == null ? null : participants.get(target);
    }

    public boolean mayDelegate(String fromId, String toId) {
        Participant from = participant(fromId);
        return from != null && toId != null && from.delegatesTo().contains(key(toId));
    }

    /**
     * One team member. {@code model} binds the member to a configured model;
     * a null binding keeps the lead chat's own model.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Participant(
            @JsonProperty("id") String id,
            @JsonProperty("role") String role,
            @JsonProperty("executor") String executor,
            @JsonProperty("capabilities") List<String> capabilities,
            @JsonProperty("delegatesTo") List<String> delegatesTo,
            @JsonProperty("model") ModelBinding model) {

        @JsonCreator
        public Participant {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Participant id is required");
            }
            id = key(id);
            if (!id.matches("[a-z0-9][a-z0-9_-]*")) {
                throw new IllegalArgumentException("Participant id '" + id
                        + "' must be lowercase letters, digits, '-' or '_'");
            }
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("Participant '" + id + "' must reference a role");
            }
            role = role.trim();
            executor = executor == null || executor.isBlank() ? "cli" : key(executor);
            capabilities = capabilities == null ? List.of()
                    : List.copyOf(capabilities.stream().map(WorkflowTeam::key).filter(s -> !s.isEmpty()).toList());
            delegatesTo = delegatesTo == null ? List.of()
                    : List.copyOf(delegatesTo.stream().map(WorkflowTeam::key).filter(s -> !s.isEmpty()).toList());
            for (String capability : capabilities) {
                if (!VALID_CAPABILITIES.contains(capability)) {
                    throw new IllegalArgumentException("Participant '" + id + "' has unknown capability '"
                            + capability + "'. Valid capabilities: " + VALID_CAPABILITIES);
                }
            }
            if (capabilities.contains("chat-only") && capabilities.size() > 1) {
                throw new IllegalArgumentException("Participant '" + id
                        + "': 'chat-only' excludes all other capabilities");
            }
            if (delegatesTo.contains(id)) {
                throw new IllegalArgumentException("Participant '" + id + "' cannot delegate to itself");
            }
        }

        public Participant(String id, String role, String executor,
                           List<String> capabilities, List<String> delegatesTo) {
            this(id, role, executor, capabilities, delegatesTo, null);
        }

        public Participant withModel(ModelBinding binding) {
            return new Participant(id, role, executor, capabilities, delegatesTo, binding);
        }

        public Participant withDelegatesTo(List<String> targets) {
            return new Participant(id, role, executor, capabilities, targets, model);
        }

        public boolean canRead() { return capabilities.contains("read"); }
        public boolean canPlan() { return capabilities.contains("plan"); }
        public boolean canDelegate() { return capabilities.contains("delegate"); }
        public boolean canEdit() { return capabilities.contains("edit-assigned-files"); }
        public boolean canValidate() { return capabilities.contains("validate"); }
        public boolean isChatOnly() { return capabilities.contains("chat-only"); }
        public boolean isCliExecutor() { return executor.equals("cli"); }
    }

    /**
     * The configured model a participant runs on: a chat provider route plus a
     * model id and optional thinking value. {@code agent} optionally names the
     * CLI agent used when delegation goes through managed CLI agents; when
     * absent it is derived from the provider's vendor. Credentials are never
     * stored here — they resolve from the credential store at request time.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ModelBinding(
            @JsonProperty("provider") String provider,
            @JsonProperty("model") String model,
            @JsonProperty("thinking") String thinking,
            @JsonProperty("authenticationMethod") String authenticationMethod,
            @JsonProperty("baseUrl") String baseUrl,
            @JsonProperty("agent") String agent) {

        @JsonCreator
        public ModelBinding {
            provider = clean(provider);
            model = clean(model);
            if (provider == null) {
                throw new IllegalArgumentException("A model binding needs a provider");
            }
            if (model == null) {
                throw new IllegalArgumentException("A model binding for '" + provider + "' needs a model");
            }
            provider = provider.toLowerCase(Locale.ROOT);
            thinking = clean(thinking);
            authenticationMethod = clean(authenticationMethod);
            baseUrl = clean(baseUrl);
            agent = clean(agent) == null ? null : key(agent);
            // Values reach CLI arguments and system prompts; keep them single-line.
            for (String value : new String[] {provider, model, thinking, authenticationMethod, baseUrl, agent}) {
                if (value != null && value.chars().anyMatch(Character::isISOControl)) {
                    throw new IllegalArgumentException("Model binding values cannot contain control characters");
                }
            }
        }

        public ModelBinding(String provider, String model, String thinking) {
            this(provider, model, thinking, null, null, null);
        }

        public ModelBinding withThinking(String value) {
            return new ModelBinding(provider, model, value, authenticationMethod, baseUrl, agent);
        }

        /** Display label: {@code provider/model (thinking: x) via agent}. */
        public String label() {
            return provider + "/" + model
                    + (thinking == null ? "" : " (thinking: " + thinking + ")")
                    + (agent == null ? "" : " via " + agent);
        }
    }

    /** @param maxConcurrentWorkers minimum 1; enforced across the whole workflow run. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Limits(@JsonProperty("maxConcurrentWorkers") Integer maxConcurrentWorkers) {
        public Limits {
            if (maxConcurrentWorkers == null) maxConcurrentWorkers = 1;
        }
        public Limits(int maxConcurrentWorkers) {
            this((Integer) maxConcurrentWorkers);
        }
    }

    /**
     * Declared gates. {@code implementationRequires} blocks delegations to
     * edit-capable participants until satisfied; {@code completionRequires}
     * marks what the workflow still owes before it is complete.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Gates(
            @JsonProperty("implementationRequires") String implementationRequires,
            @JsonProperty("completionRequires") String completionRequires) {

        public static final Gates NONE = new Gates(null, null);

        public Gates {
            implementationRequires = normalized(implementationRequires);
            completionRequires = normalized(completionRequires);
        }

        private static String normalized(String value) {
            return value == null || value.isBlank() ? null : key(value);
        }

        public boolean hasImplementationGate() { return implementationRequires != null; }
        public boolean hasCompletionGate() { return completionRequires != null; }
    }
}
