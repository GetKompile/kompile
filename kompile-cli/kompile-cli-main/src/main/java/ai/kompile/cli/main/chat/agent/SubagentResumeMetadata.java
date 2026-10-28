/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Sidecar metadata for one resumable subagent run:
 * {@code ~/.kompile/conversations/<sessionId>.subagent.json}.
 *
 * <p>Presence of the sidecar <em>is</em> the resumable marker — non-resumable
 * runs write nothing and stay byte-for-byte today's behavior. The sidecar (not
 * the prose transcript header) carries resume metadata, mirroring the existing
 * {@code .title} override sidecar: a running writer is never rewritten, and the
 * shared transcript format is untouched.</p>
 *
 * <p>Identity boundary: sessions described here always carry the
 * {@code subagent-} id prefix. The main resume flow never reads sidecars.</p>
 */
public record SubagentResumeMetadata(
        String sessionId,
        String name,
        String agentType,
        String parentSession,
        String workflowParticipant,
        String model,
        long createdAt,
        long updatedAt,
        String provider,
        String authenticationMethod,
        String credentialName,
        String thinking) {

    public SubagentResumeMetadata(String sessionId, String name, String agentType, String parentSession,
                                  String workflowParticipant, String model, long createdAt, long updatedAt,
                                  String provider, String authenticationMethod) {
        this(sessionId, name, agentType, parentSession, workflowParticipant, model,
                createdAt, updatedAt, provider, authenticationMethod, null, null);
    }

    /** Source compatibility for callers and old sidecars without route metadata. */
    public SubagentResumeMetadata(String sessionId, String name, String agentType, String parentSession,
                                  String workflowParticipant, String model, long createdAt, long updatedAt) {
        this(sessionId, name, agentType, parentSession, workflowParticipant, model,
                createdAt, updatedAt, null, null);
    }

    public SubagentResumeMetadata withRoute(String provider, String authenticationMethod) {
        return new SubagentResumeMetadata(sessionId, name, agentType, parentSession, workflowParticipant,
                model, createdAt, updatedAt, provider, authenticationMethod, credentialName, thinking);
    }

    public SubagentResumeMetadata withRoute(String provider, String authenticationMethod, String model,
                                           String credentialName, String thinking) {
        return new SubagentResumeMetadata(sessionId, name, agentType, parentSession, workflowParticipant,
                model, createdAt, System.currentTimeMillis(), provider, authenticationMethod, credentialName, thinking);
    }

    private static final String VERSION = "1";
    private static final String NAME_PATTERN = "[a-z0-9][a-z0-9-_]{0,63}";

    public static Path sidecarFor(Path conversationsDir, String sessionId) {
        return conversationsDir.resolve(sessionId + ".subagent.json");
    }

    /** Validate and normalize a user-chosen name slug, or return the error. */
    public static String nameError(String name) {
        if (name == null || name.isBlank()) return "Name must not be blank";
        if (!name.matches(NAME_PATTERN)) {
            return "Name must match " + NAME_PATTERN + " (lowercase letters, digits, '-' and '_')";
        }
        return null;
    }

    public SubagentResumeMetadata {
        if (sessionId == null || !sessionId.startsWith("subagent-")) {
            throw new IllegalArgumentException(
                    "Resumable metadata requires a subagent- prefixed session id: " + sessionId);
        }
    }

    /** First-run metadata. */
    public static SubagentResumeMetadata create(String sessionId, String name, String agentType,
                                                String parentSession, String workflowParticipant,
                                                String model) {
        long now = System.currentTimeMillis();
        return new SubagentResumeMetadata(sessionId, name, agentType, parentSession,
                workflowParticipant, model, now, now);
    }

    public SubagentResumeMetadata withName(String newName) {
        return new SubagentResumeMetadata(sessionId, newName, agentType, parentSession,
                workflowParticipant, model, createdAt, System.currentTimeMillis(), provider, authenticationMethod, credentialName, thinking);
    }

    public SubagentResumeMetadata withParent(String newParentSession) {
        return new SubagentResumeMetadata(sessionId, name, agentType, newParentSession,
                workflowParticipant, model, createdAt, System.currentTimeMillis(), provider, authenticationMethod, credentialName, thinking);
    }

    public void write(Path sidecar, ObjectMapper mapper) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("v", VERSION);
        node.put("sessionId", sessionId);
        if (name != null) node.put("name", name);
        node.put("agentType", agentType);
        if (parentSession != null) node.put("parentSession", parentSession);
        if (workflowParticipant != null) node.put("workflowParticipant", workflowParticipant);
        if (model != null) node.put("model", model);
        if (provider != null) node.put("provider", provider);
        if (authenticationMethod != null) node.put("authenticationMethod", authenticationMethod);
        if (credentialName != null) node.put("credentialName", credentialName);
        if (thinking != null) node.put("thinking", thinking);
        node.put("createdAt", createdAt);
        node.put("updatedAt", updatedAt);
        Files.createDirectories(sidecar.getParent());
        Files.writeString(sidecar, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node),
                StandardCharsets.UTF_8);
    }

    /** Parse a sidecar, or {@code null} when absent or unreadable (never a wrong run). */
    public static SubagentResumeMetadata read(Path sidecar, ObjectMapper mapper) {
        if (sidecar == null || !Files.isRegularFile(sidecar)) return null;
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(sidecar.toFile());
            String sessionId = node.path("sessionId").asText(null);
            if (sessionId == null || !sessionId.startsWith("subagent-")) return null;
            return new SubagentResumeMetadata(
                    sessionId,
                    node.hasNonNull("name") ? node.path("name").asText() : null,
                    node.path("agentType").asText(null),
                    node.hasNonNull("parentSession") ? node.path("parentSession").asText() : null,
                    node.hasNonNull("workflowParticipant") ? node.path("workflowParticipant").asText() : null,
                    node.hasNonNull("model") ? node.path("model").asText() : null,
                    node.path("createdAt").asLong(0),
                    node.path("updatedAt").asLong(0),
                    node.path("provider").asText(null),
                    node.path("authenticationMethod").asText(null),
                    node.path("credentialName").asText(null),
                    node.path("thinking").asText(null));
        } catch (IOException | RuntimeException malformed) {
            return null;
        }
    }

    /** Human-friendly age for listings. */
    public String lastActivityLabel() {
        long ageMs = System.currentTimeMillis() - updatedAt;
        if (ageMs < 60_000) return "just now";
        if (ageMs < 3_600_000) return (ageMs / 60_000) + "m ago";
        if (ageMs < 86_400_000) return (ageMs / 3_600_000) + "h ago";
        return (ageMs / 86_400_000) + "d ago";
    }

    /** ISO timestamp for listings; falls back to epoch millis on a corrupt clock. */
    public String updatedAtIso() {
        try {
            return Instant.ofEpochMilli(updatedAt).toString();
        } catch (RuntimeException e) {
            return String.valueOf(updatedAt);
        }
    }
}
