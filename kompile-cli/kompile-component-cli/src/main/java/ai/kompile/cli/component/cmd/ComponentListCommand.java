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

package ai.kompile.cli.component.cmd;

import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;
import ai.kompile.cli.component.output.OutputFormatter;
import ai.kompile.cli.component.output.OutputFormatter.Format;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Command to list all available components
 */
@Command(name = "list", 
        description = "List all available components with their details",
        mixinStandardHelpOptions = true)
public class ComponentListCommand implements Callable<Integer> {

    private static final Map<String, ComponentMetadata> KNOWN_COMPONENTS = knownComponents();

    @Option(names = {"--format", "-f"}, 
            description = "Output format: text, json, yaml, csv, table",
            defaultValue = "text",
            converter = FormatConverter.class)
    private Format format = Format.TEXT;

    @Option(names = {"--installed-only"}, 
            description = "Show only installed components")
    private boolean installedOnly = false;

    @Option(names = {"--running-only"}, 
            description = "Show only running components")
    private boolean runningOnly = false;

    @Override
    public Integer call() throws Exception {
        List<Map<String, Object>> components = getComponentList();

        // Apply filters
        if (installedOnly) {
            components = components.stream()
                    .filter(c -> Boolean.TRUE.equals(c.get("installed")))
                    .toList();
        }

        if (runningOnly) {
            components = components.stream()
                    .filter(c -> "running".equals(c.get("status")))
                    .toList();
        }

        // Output
        String output = OutputFormatter.formatList(components, format);
        System.out.println(output);

        return 0;
    }

    private List<Map<String, Object>> getComponentList() {
        Map<String, ComponentMetadata> components = new LinkedHashMap<>(KNOWN_COMPONENTS);
        for (String componentId : discoverInstalledComponentIds()) {
            components.putIfAbsent(componentId, ComponentMetadata.discovered(componentId));
        }

        return components.values().stream()
                .map(this::createComponentEntry)
                .toList();
    }

    private Map<String, Object> createComponentEntry(ComponentMetadata metadata) {
        ComponentInstallPaths.InstallInfo install = ComponentInstallPaths.findInstallInfo(metadata.id());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", metadata.id());
        entry.put("name", metadata.name());
        entry.put("description", metadata.description());
        entry.put("type", metadata.type());
        entry.put("defaultPort", metadata.defaultPort() != null ? metadata.defaultPort() : "N/A");
        entry.put("mainClass", metadata.mainClass() != null ? metadata.mainClass() : "N/A");
        entry.put("installed", install.installed());
        entry.put("version", install.version() != null ? install.version() : "N/A");
        entry.put("artifactPath", install.path() != null ? install.path().getAbsolutePath() : "N/A");
        entry.put("status", getComponentStatus(metadata.id()));
        return entry;
    }

    private String getComponentStatus(String componentId) {
        try {
            boolean matched = false;
            boolean dead = false;
            for (InstanceInfo instance : InstanceRegistry.listAll()) {
                if (!matchesInstance(componentId, instance)) {
                    continue;
                }
                matched = true;
                boolean alive = ProcessHandle.of(instance.getPid())
                        .map(ProcessHandle::isAlive)
                        .orElse(false);
                if (alive) {
                    return "running";
                }
                dead = true;
            }
            if (dead) {
                return "dead";
            }
            return matched ? "unknown" : "not_running";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private boolean matchesInstance(String componentId, InstanceInfo instance) {
        String name = safe(instance.getName());
        String type = safe(instance.getType());
        if (componentId.equals(name) || componentId.equals(type)) {
            return true;
        }
        return switch (componentId) {
            case "kompile-app-main" -> "app".equals(type);
            case "kompile-model-staging" -> "staging".equals(type);
            default -> false;
        };
    }

    private Set<String> discoverInstalledComponentIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (File root : ComponentInstallPaths.componentRoots()) {
            File[] dirs = root.listFiles(File::isDirectory);
            if (dirs == null) {
                continue;
            }
            Arrays.sort(dirs, Comparator.comparing(File::getName));
            for (File dir : dirs) {
                if (!dir.getName().startsWith(".")) {
                    ids.add(dir.getName());
                }
            }
        }
        return ids;
    }

    private static Map<String, ComponentMetadata> knownComponents() {
        Map<String, ComponentMetadata> components = new LinkedHashMap<>();
        addKnown(components, "kompile-app-main", "Kompile App Main",
                "Spring Boot RAG application with web UI", "app", 8080,
                "ai.kompile.app.MainApplication");
        addKnown(components, "kompile-model-staging", "Kompile Model Staging",
                "Model lifecycle management service", "staging", 8090,
                "ai.kompile.modelstaging.MainApplication");
        addKnown(components, "kompile-cli", "Kompile CLI",
                "Main command-line interface", "cli", null,
                "ai.kompile.cli.main.MainCommand");
        addKnown(components, "kompile-app", "Kompile App CLI",
                "Application management CLI", "cli", null,
                "ai.kompile.cli.app.AppCliMain");
        addKnown(components, "kompile-model", "Kompile Model CLI",
                "Model management CLI", "cli", null,
                "ai.kompile.cli.model.ModelCliMain");
        addKnown(components, "kompile-agent", "Kompile Agent CLI",
                "Agent management CLI", "cli", null,
                "ai.kompile.cli.agent.AgentCliMain");
        addKnown(components, "kompile-lite", "Kompile Lite",
                "Self-contained chat, RAG, and Graph RAG application", "app", null,
                "ai.kompile.lite.MainApplication");
        return components;
    }

    private static void addKnown(Map<String, ComponentMetadata> components,
                                 String id,
                                 String name,
                                 String description,
                                 String type,
                                 Integer defaultPort,
                                 String mainClass) {
        components.put(id, new ComponentMetadata(id, name, description, type, defaultPort, mainClass));
    }

    private static String displayName(String componentId) {
        String[] parts = componentId.replace('-', ' ').replace('_', ' ').split(" ");
        StringBuilder builder = new StringBuilder(componentId.length());
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            String lower = part.toLowerCase(Locale.ROOT);
            builder.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
        }
        return builder.isEmpty() ? componentId : builder.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    static class FormatConverter implements CommandLine.ITypeConverter<Format> {
        @Override
        public Format convert(String value) {
            return Format.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    private record ComponentMetadata(String id, String name, String description,
                                     String type, Integer defaultPort, String mainClass) {
        static ComponentMetadata discovered(String id) {
            return new ComponentMetadata(id, displayName(id),
                    "Installed component discovered under the Kompile components directory",
                    "component", null, null);
        }
    }

}
