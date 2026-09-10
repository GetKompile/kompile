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

package ai.kompile.cli.main.chat;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent resume/recovery preferences stored at {@code ~/.kompile/config/resume.json}.
 * <p>
 * Currently holds the default number of recent sessions surfaced by
 * {@code kompile resume-all} and the {@code resume} tool's {@code recent}/{@code resume_all}
 * actions. Defaults to {@value #DEFAULT_RECENT_SESSIONS} when unset.
 * <p>
 * Paths are resolved at call time (not class-load time) so tests can isolate
 * {@code user.home} the same way the other chat tests do.
 */
public class ResumeConfig {

    public static final int DEFAULT_RECENT_SESSIONS = 10;

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private int recentSessions = DEFAULT_RECENT_SESSIONS;

    /**
     * Resolve the config file location at call time for test isolation.
     */
    static Path configFile() {
        return KompileHome.configDirectory().toPath().resolve("resume.json");
    }

    /**
     * Load resume config from disk. Returns the default config if the file doesn't exist.
     */
    public static ResumeConfig load() {
        ResumeConfig config = new ResumeConfig();
        Path file = configFile();
        if (!Files.exists(file)) {
            return config;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            ObjectNode root = (ObjectNode) MAPPER.readTree(content);
            int recent = root.path("recentSessions").asInt(DEFAULT_RECENT_SESSIONS);
            config.recentSessions = recent > 0 ? recent : DEFAULT_RECENT_SESSIONS;
        } catch (Exception e) {
            System.err.println("Warning: Could not load resume config: " + e.getMessage());
        }
        return config;
    }

    /**
     * Save resume config to disk.
     */
    public boolean save() {
        try {
            Path file = configFile();
            Files.createDirectories(file.getParent());
            ObjectNode root = MAPPER.createObjectNode();
            root.put("recentSessions", recentSessions);
            Files.writeString(file, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            System.err.println("Warning: Could not save resume config: " + e.getMessage());
            return false;
        }
    }

    public int getRecentSessions() {
        return recentSessions;
    }

    public void setRecentSessions(int recentSessions) {
        this.recentSessions = recentSessions > 0 ? recentSessions : DEFAULT_RECENT_SESSIONS;
    }
}
