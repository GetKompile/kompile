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

import ai.kompile.cli.component.output.OutputFormatter;
import ai.kompile.cli.component.output.OutputFormatter.Format;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Command to list all available components
 */
@Command(name = "list", 
        description = "List all available components with their details",
        mixinStandardHelpOptions = true)
public class ComponentListCommand implements Callable<Integer> {

    @Option(names = {"--format", "-f"}, 
            description = "Output format: text, json, yaml, csv, table",
            defaultValue = "text")
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
        List<Map<String, Object>> components = new ArrayList<>();

        // kompile-app-main
        components.add(createComponentEntry(
                "kompile-app-main",
                "Kompile App Main",
                "Spring Boot RAG application with web UI",
                "app",
                8080,
                "ai.kompile.app.MainApplication"
        ));

        // kompile-model-staging
        components.add(createComponentEntry(
                "kompile-model-staging",
                "Kompile Model Staging",
                "Model lifecycle management service",
                "staging",
                8081,
                "ai.kompile.staging.ModelStagingApplication"
        ));

        // kompile-cli
        components.add(createComponentEntry(
                "kompile-cli",
                "Kompile CLI",
                "Main command-line interface",
                "cli",
                null,
                "ai.kompile.cli.main.MainCommand"
        ));

        // kompile-app
        components.add(createComponentEntry(
                "kompile-app",
                "Kompile App CLI",
                "Application management CLI",
                "cli",
                null,
                "ai.kompile.cli.app.AppCliMain"
        ));

        // kompile-model
        components.add(createComponentEntry(
                "kompile-model",
                "Kompile Model CLI",
                "Model management CLI",
                "cli",
                null,
                "ai.kompile.cli.model.ModelCliMain"
        ));

        // kompile-agent
        components.add(createComponentEntry(
                "kompile-agent",
                "Kompile Agent CLI",
                "Agent management CLI",
                "cli",
                null,
                "ai.kompile.cli.agent.AgentCliMain"
        ));

        return components;
    }

    private Map<String, Object> createComponentEntry(String id, String name, String description,
                                                      String type, Integer defaultPort, String mainClass) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("name", name);
        entry.put("description", description);
        entry.put("type", type);
        entry.put("defaultPort", defaultPort != null ? defaultPort : "N/A");
        entry.put("mainClass", mainClass);
        entry.put("installed", checkIfInstalled(id));
        entry.put("status", getComponentStatus(id));
        return entry;
    }

    private boolean checkIfInstalled(String componentId) {
        // Check if component JAR exists in ~/.kompile/components/
        String homeDir = System.getProperty("user.home");
        java.io.File componentsDir = new java.io.File(homeDir, ".kompile/components/" + componentId);
        
        if (!componentsDir.exists()) {
            return false;
        }

        // Check if any version directory exists
        java.io.File[] versions = componentsDir.listFiles(java.io.File::isDirectory);
        return versions != null && versions.length > 0;
    }

    private String getComponentStatus(String componentId) {
        // Check if running via InstanceRegistry
        try {
            String homeDir = System.getProperty("user.home");
            java.io.File instancesDir = new java.io.File(homeDir, ".kompile/instances");
            
            if (!instancesDir.exists()) {
                return "not_running";
            }

            java.io.File[] instanceFiles = instancesDir.listFiles((dir, name) -> 
                    name.equals(componentId + ".json"));

            if (instanceFiles == null || instanceFiles.length == 0) {
                return "not_running";
            }

            // Check if process is alive
            java.io.File instanceFile = instanceFiles[0];
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> instance = mapper.readValue(instanceFile, Map.class);
            
            Long pid = ((Number) instance.get("pid")).longValue();
            boolean isAlive = ProcessHandle.of(pid)
                    .map(ProcessHandle::isAlive)
                    .orElse(false);

            return isAlive ? "running" : "dead";

        } catch (Exception e) {
            return "unknown";
        }
    }
}
