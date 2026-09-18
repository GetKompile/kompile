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
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A named, enforceable team configuration for a chat session: who leads, which
 * roles perform which work, who may delegate to whom, and which gates must be
 * satisfied before work proceeds or completes.
 *
 * <p>This is the team-configuration workflow concept (distinct from the
 * enforcer's turn-discipline {@link WorkflowPolicy}). It reuses roles for
 * responsibility and profiles for launch selection; workflow-local participant
 * names own their restrictions. Definitions stay deliberately small:
 * participants, permitted delegation edges, and optional gates — no general
 * purpose workflow language.</p>
 *
 * <p>Example {@code chat-workflows.json} entry:</p>
 * <pre>{@code
 * {
 *   "workflows": {
 *     "designer-workers": {
 *       "version": 1,
 *       "lead": "designer",
 *       "participants": {
 *         "designer": { "role": "architect",
 *                       "capabilities": ["read", "plan", "delegate"],
 *                       "delegatesTo": ["worker"] },
 *         "worker":   { "role": "implementer",
 *                       "capabilities": ["read", "edit-assigned-files", "validate"] }
 *       },
 *       "routing": { "implement": "worker" },
 *       "limits": { "maxConcurrentWorkers": 3 },
 *       "gates": { "implementationRequires": "user-approved-design" }
 *     }
 *   }
 * }
 * }</pre>
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
            @JsonProperty("gates") Gates gates) {        if (name == null || name.isBlank()) {
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
        Map<String, Participant> normalized = new LinkedHashMap<>();
        participants.forEach((id, participant) -> {
            if (participant == null) {
                throw new IllegalArgumentException("Workflow '" + this.name + "' has a null participant");
            }
            if (!WorkflowTeam.key(id).equals(participant.id())) {
                throw new IllegalArgumentException("Workflow '" + this.name + "' participant key '" + id
                        + "' does not match participant id '" + participant.id() + "'");
            }
            normalized.put(WorkflowTeam.key(id), participant);
        });
        this.participants = Map.copyOf(normalized);
        if (lead == null || lead.isBlank()) {
            throw new IllegalArgumentException("Workflow '" + this.name + "' needs a lead participant");
        }
        String leadKey = key(lead);
        if (!this.participants.containsKey(leadKey)) {
            throw new IllegalArgumentException("Workflow '" + this.name + "' lead '" + lead
                    + "' is not a participant");
        }
        this.lead = leadKey;
        this.routing = routing == null ? Map.of() : Map.copyOf(routing);
        this.maxConcurrentWorkers = limits == null ? 1 : Math.max(1, limits.maxConcurrentWorkers());
        this.gates = gates == null ? Gates.NONE : gates;
        validate();
    }

    /** Participant ids and purposes are case-insensitive. */
    public static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private void validate() {
        // Routing purposes must name known participants.
        for (Map.Entry<String, String> entry : routing.entrySet()) {
            String target = key(entry.getValue());
            if (target.isEmpty() || !participants.containsKey(target)) {
                throw new IllegalArgumentException("Workflow '" + name + "' routes purpose '"
                        + entry.getKey() + "' to unknown participant '" + entry.getValue() + "'");
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
        return java.util.Objects.hash(name, version, lead, participants, routing,
                maxConcurrentWorkers, gates);
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Participant(
            @JsonProperty("id") String id,
            @JsonProperty("role") String role,
            @JsonProperty("executor") String executor,
            @JsonProperty("capabilities") List<String> capabilities,
            @JsonProperty("delegatesTo") List<String> delegatesTo) {

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

        public boolean canRead() { return capabilities.contains("read"); }
        public boolean canPlan() { return capabilities.contains("plan"); }
        public boolean canDelegate() { return capabilities.contains("delegate"); }
        public boolean canEdit() { return capabilities.contains("edit-assigned-files"); }
        public boolean canValidate() { return capabilities.contains("validate"); }
        public boolean isChatOnly() { return capabilities.contains("chat-only"); }
        public boolean isCliExecutor() { return executor.equals("cli"); }
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
