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

package ai.kompile.cli.common.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Persistent record of a project's registration with a kompile-app endpoint.
 * Stored at {@code <projectDir>/.kompile/registration.json} so that any tool
 * or agent working in the project directory can discover which endpoint this
 * project is registered with.
 *
 * <p>Also stored at {@code ~/.kompile/registrations/<projectId>.json} for
 * global lookup by project ID.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProjectRegistration {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    /** The kompile-app endpoint URL this project is registered with. */
    private String endpointUrl;

    /** The project ID used for registration. */
    private String projectId;

    /** The absolute directory path that was registered. */
    private String directory;

    /** When the registration was last updated. */
    private Instant registeredAt;

    /** Whether the project was activated on the endpoint. */
    private boolean active;

    /** Session ID that triggered the registration (if any). */
    private String sessionId;

    /** Source of the registration (e.g., "claude", "kompile-chat", "mcp-stdio"). */
    private String source;

    public ProjectRegistration() {}

    public ProjectRegistration(String endpointUrl, String projectId, String directory,
                               boolean active, String sessionId, String source) {
        this.endpointUrl = endpointUrl;
        this.projectId = projectId;
        this.directory = directory;
        this.registeredAt = Instant.now();
        this.active = active;
        this.sessionId = sessionId;
        this.source = source;
    }

    // ── Persistence ──────────────────────────────────────────────────

    /**
     * Save this registration to both the project-local and global locations.
     *
     * @param projectDir the project root directory
     */
    public void save(Path projectDir) {
        // Project-local: <projectDir>/.kompile/registration.json
        try {
            Path localDir = projectDir.resolve(".kompile");
            Files.createDirectories(localDir);
            MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(localDir.resolve("registration.json").toFile(), this);
        } catch (IOException e) {
            // Best-effort — don't fail if we can't write to the project dir
        }

        // Global: ~/.kompile/registrations/<projectId>.json
        if (projectId != null) {
            try {
                Path globalDir = Path.of(System.getProperty("user.home"),
                        ".kompile", "registrations");
                Files.createDirectories(globalDir);
                MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValue(globalDir.resolve(projectId + ".json").toFile(), this);
            } catch (IOException e) {
                // Best-effort
            }
        }
    }

    /**
     * Load the registration for a project from its local directory.
     * Returns null if no registration exists.
     */
    public static ProjectRegistration loadFromProject(Path projectDir) {
        Path file = projectDir.resolve(".kompile").resolve("registration.json");
        if (!Files.exists(file)) return null;
        try {
            return MAPPER.readValue(file.toFile(), ProjectRegistration.class);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Load the registration for a project by its ID from the global registry.
     * Returns null if no registration exists.
     */
    public static ProjectRegistration loadByProjectId(String projectId) {
        Path file = Path.of(System.getProperty("user.home"),
                ".kompile", "registrations", projectId + ".json");
        if (!Files.exists(file)) return null;
        try {
            return MAPPER.readValue(file.toFile(), ProjectRegistration.class);
        } catch (IOException e) {
            return null;
        }
    }

    // ── Getters/Setters ──────────────────────────────────────────────

    public String getEndpointUrl() { return endpointUrl; }
    public void setEndpointUrl(String endpointUrl) { this.endpointUrl = endpointUrl; }

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getDirectory() { return directory; }
    public void setDirectory(String directory) { this.directory = directory; }

    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    @Override
    public String toString() {
        return "ProjectRegistration{" +
                "endpoint=" + endpointUrl +
                ", projectId=" + projectId +
                ", active=" + active +
                ", registeredAt=" + registeredAt +
                '}';
    }
}
