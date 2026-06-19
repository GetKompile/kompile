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
 *   distributed under the License is distributed on an "AS IS" BASIS,
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent terminal emulator configuration stored at {@code ~/.kompile/config/terminal.json}.
 * <p>
 * Users can configure which terminal emulator to use for spawning sessions.
 * If not configured, a sane system default is auto-detected.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TerminalConfig {

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final Path CONFIG_FILE =
            KompileHome.configDirectory().toPath().resolve("terminal.json");

    private String terminalCommand;
    private String terminalArgs;

    /**
     * Load terminal config from disk. Returns default (empty) config if file doesn't exist.
     */
    public static TerminalConfig load() {
        TerminalConfig config = new TerminalConfig();
        if (!Files.exists(CONFIG_FILE)) {
            return config;
        }
        try {
            String content = Files.readString(CONFIG_FILE, StandardCharsets.UTF_8);
            ObjectNode root = (ObjectNode) MAPPER.readTree(content);
            config.terminalCommand = root.path("terminalCommand").asText(null);
            String args = root.path("terminalArgs").asText(null);
            config.terminalArgs = (args != null && !args.isEmpty()) ? args : null;
        } catch (Exception e) {
            System.err.println("Warning: Could not load terminal config: " + e.getMessage());
        }
        return config;
    }

    /**
     * Save terminal config to disk.
     */
    public void save() {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            ObjectNode root = MAPPER.createObjectNode();
            if (terminalCommand != null) root.put("terminalCommand", terminalCommand);
            if (terminalArgs != null) root.put("terminalArgs", terminalArgs);
            Files.writeString(CONFIG_FILE, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Warning: Could not save terminal config: " + e.getMessage());
        }
    }

    /**
     * Returns true if the user has explicitly configured a terminal.
     */
    public boolean isConfigured() {
        return terminalCommand != null && !terminalCommand.isBlank();
    }
}
