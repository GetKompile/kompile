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

package ai.kompile.core.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Central registry of known CLI agent definitions.
 * Loaded from cli-agents.json on the classpath — single source of truth
 * for both kompile-cli and kompile-app-main.
 */
public final class CliAgentRegistry {

    private static final String RESOURCE = "cli-agents.json";
    private static volatile List<AgentProvider> cached;

    private CliAgentRegistry() {}

    /**
     * Load all CLI agent definitions from the classpath resource.
     */
    public static List<AgentProvider> loadAll() {
        List<AgentProvider> c = cached;
        if (c != null) return c;
        synchronized (CliAgentRegistry.class) {
            c = cached;
            if (c != null) return c;
            try (InputStream is = CliAgentRegistry.class.getResourceAsStream("/" + RESOURCE)) {
                if (is == null) {
                    // Don't cache — resource may become available later (native image runtime vs build time)
                    return Collections.emptyList();
                }
                ObjectMapper mapper = new ObjectMapper();
                List<CliAgentDef> defs = mapper.readValue(is, new TypeReference<>() {});
                List<AgentProvider> providers = new ArrayList<>();
                for (CliAgentDef def : defs) {
                    AgentProvider.Builder b = AgentProvider.builder()
                            .name(def.name)
                            .displayName(def.displayName)
                            .command(def.command)
                            .skipPermissionsFlag(def.skipPermissionsFlag)
                            .skipPermissions(def.skipPermissions)
                            .available(false)
                            .isDefault(def.isDefault)
                            .description(def.description)
                            .interactivePromptPattern(def.interactivePromptPattern);
                    if (def.args != null) {
                        for (String arg : def.args) b.addArg(arg);
                    }
                    providers.add(b.build());
                }
                cached = Collections.unmodifiableList(providers);
                return cached;
            } catch (Exception e) {
                // Don't cache — may succeed on retry at runtime
                return Collections.emptyList();
            }
        }
    }

    /**
     * Get just the command names (e.g. "claude", "opencode", "codex") for PATH scanning.
     */
    public static List<String> commandNames() {
        List<String> names = new ArrayList<>();
        for (AgentProvider p : loadAll()) {
            names.add(p.getCommand());
        }
        return names;
    }

    /**
     * Find the first available CLI agent on PATH.
     */
    public static String detectFirstAvailable() {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (AgentProvider agent : loadAll()) {
            for (String dir : path.split(File.pathSeparator)) {
                File candidate = new File(dir, agent.getCommand());
                if (candidate.canExecute()) return agent.getCommand();
            }
        }
        return null;
    }

    // JSON deserialization DTO — must be public for GraalVM native image reflection
    public static class CliAgentDef {
        public String name;
        public String displayName;
        public String command;
        public String skipPermissionsFlag;
        public boolean skipPermissions;
        public List<String> args;
        public boolean isDefault;
        public String description;
        public String interactivePromptPattern;
    }
}
