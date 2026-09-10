/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.services;

import ai.kompile.agent.graph.AgentInstance;
import ai.kompile.agent.graph.AgentInstanceStore;
import ai.kompile.agent.graph.AgentPrincipal;
import ai.kompile.agent.graph.LocalOwnerIdentity;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Thin owner-bound application service for durable private-graph agent provisioning. */
public final class AgentProvisioningService {

    private final AgentInstanceStore store;
    private final LocalOwnerIdentity localOwner;

    public AgentProvisioningService(
            AgentInstanceStore store,
            LocalOwnerIdentity localOwner) {
        this.store = Objects.requireNonNull(store, "store");
        this.localOwner = Objects.requireNonNull(localOwner, "localOwner");
    }

    public ProvisionedAgent create(String displayName) throws IOException {
        if (displayName == null) {
            throw new IllegalArgumentException("displayName is required");
        }
        return describe(store.provision(localOwner.ownerId(), displayName));
    }

    public List<ProvisionedAgent> list() throws IOException {
        List<ProvisionedAgent> result = new ArrayList<>();
        for (AgentInstance instance : store.list(localOwner.ownerId())) {
            result.add(describe(instance));
        }
        return List.copyOf(result);
    }

    public ProvisionedAgent get(String canonicalAgentId) throws IOException {
        UUID agentId = parseCanonicalAgentId(canonicalAgentId);
        AgentPrincipal principal = new AgentPrincipal(localOwner.ownerId(), agentId);
        AgentInstance instance = store.find(principal)
                .orElseThrow(() -> new AgentNotFoundException(agentId));
        return describe(instance);
    }

    private ProvisionedAgent describe(AgentInstance instance) throws IOException {
        return new ProvisionedAgent(
                instance.principal().agentId(),
                instance.displayName(),
                instance.manifestVersion(),
                instance.revision(),
                instance.createdAt(),
                instance.updatedAt(),
                store.open(instance.principal()).currentRevision().sha256());
    }

    static UUID parseCanonicalAgentId(String value) {
        if (value == null) {
            throw new InvalidAgentIdException("Agent UUID is required");
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new InvalidAgentIdException("Agent UUID must use canonical lowercase form");
            }
            return parsed;
        } catch (IllegalArgumentException invalid) {
            if (invalid instanceof InvalidAgentIdException invalidAgentId) {
                throw invalidAgentId;
            }
            throw new InvalidAgentIdException("Agent UUID is invalid");
        }
    }

    public record ProvisionedAgent(
            UUID agentId,
            String displayName,
            int manifestVersion,
            long manifestRevision,
            Instant createdAt,
            Instant updatedAt,
            String graphRevision) {
    }

    public static final class InvalidAgentIdException extends IllegalArgumentException {
        public InvalidAgentIdException(String message) {
            super(message);
        }
    }

    public static final class AgentNotFoundException extends RuntimeException {
        public AgentNotFoundException(UUID agentId) {
            super("No provisioned agent exists for UUID " + agentId);
        }
    }

}
