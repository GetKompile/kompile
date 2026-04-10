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
import picocli.CommandLine.Parameters;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Command to show component configuration
 */
@Command(name = "config", 
        description = "Show component configuration details",
        mixinStandardHelpOptions = true)
public class ComponentConfigCommand implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", description = "Component ID (use --all for all components)")
    private String componentId;

    @Option(names = {"--format", "-f"}, 
            description = "Output format: text, json, yaml, csv, table",
            defaultValue = "text")
    private Format format = Format.TEXT;

    @Option(names = {"--all"}, 
            description = "Show configuration for all components")
    private boolean showAll = false;

    @Option(names = {"--include-paths"}, 
            description = "Include file system paths in output")
    private boolean includePaths = false;

    @Override
    public Integer call() throws Exception {
        Map<String, Object> config;

        if (showAll) {
            config = getAllComponentsConfig();
        } else if (componentId != null) {
            config = getComponentConfig(componentId);
        } else {
            System.err.println("Error: Specify a component ID or use --all flag");
            return 1;
        }

        String output = OutputFormatter.formatMap(config, format);
        System.out.println(output);

        return 0;
    }

    private Map<String, Object> getComponentConfig(String id) {
        Map<String, Object> config = new LinkedHashMap<>();
        
        // Basic component info
        config.put("id", id);
        config.put("name", getComponentName(id));
        config.put("description", getComponentDescription(id));
        config.put("type", getComponentType(id));

        // Installation config
        String installPath = getInstallPath(id);
        if (installPath != null) {
            config.put("installed", true);
            config.put("installPath", installPath);
            
            File installDir = new File(installPath);
            config.put("installedAt", installDir.lastModified());
            config.put("installSize", getFileSize(installDir) + " bytes");
            
            // Find JAR file
            File jarFile = findJarFile(installDir);
            if (jarFile != null) {
                config.put("jarFile", jarFile.getAbsolutePath());
                config.put("jarSize", jarFile.length() + " bytes");
                config.put("jarLastModified", jarFile.lastModified());
            }
        } else {
            config.put("installed", false);
        }

        // Runtime config
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("defaultPort", getDefaultPort(id));
        runtime.put("mainClass", getMainClass(id));
        runtime.put("jvmArgs", getDefaultJvmArgs(id));
        runtime.put("healthEndpoint", "/actuator/health");
        
        if (includePaths && installPath != null) {
            runtime.put("workDirectory", System.getProperty("user.home") + "/.kompile/work/" + id);
            runtime.put("logDirectory", System.getProperty("user.home") + "/.kompile/logs/" + id);
            runtime.put("configDirectory", System.getProperty("user.home") + "/.kompile/config/" + id);
        }
        
        config.put("runtime", runtime);

        // Repository config
        Map<String, Object> repo = new LinkedHashMap<>();
        repo.put("githubRepo", "KonduitAI/kompile");
        repo.put("mavenGroupId", "ai.kompile");
        repo.put("mavenArtifactId", id);
        repo.put("releaseSource", "github");
        config.put("repository", repo);

        return config;
    }

    private Map<String, Object> getAllComponentsConfig() {
        Map<String, Object> allConfig = new LinkedHashMap<>();
        
        List<String> components = List.of(
                "kompile-app-main",
                "kompile-model-staging",
                "kompile-cli",
                "kompile-app",
                "kompile-model",
                "kompile-agent"
        );

        Map<String, Object> componentsMap = new LinkedHashMap<>();
        for (String id : components) {
            componentsMap.put(id, getComponentConfig(id));
        }
        
        allConfig.put("components", componentsMap);
        allConfig.put("globalConfig", getGlobalConfig());
        
        return allConfig;
    }

    private Map<String, Object> getGlobalConfig() {
        Map<String, Object> global = new LinkedHashMap<>();
        global.put("kompileHome", System.getProperty("user.home") + "/.kompile");
        global.put("componentsDirectory", System.getProperty("user.home") + "/.kompile/components");
        global.put("instancesDirectory", System.getProperty("user.home") + "/.kompile/instances");
        global.put("defaultReleaseSource", "github");
        global.put("defaultGithubRepo", "KonduitAI/kompile");
        global.put("defaultMavenRepo", "https://repo1.maven.org/maven2/");
        
        if (includePaths) {
            global.put("workDirectory", System.getProperty("user.home") + "/.kompile/work");
            global.put("logDirectory", System.getProperty("user.home") + "/.kompile/logs");
            global.put("configDirectory", System.getProperty("user.home") + "/.kompile/config");
            global.put("modelsDirectory", System.getProperty("user.home") + "/.kompile/models");
        }
        
        return global;
    }

    // Helper methods

    private String getComponentName(String id) {
        return switch (id) {
            case "kompile-app-main" -> "Kompile App Main";
            case "kompile-model-staging" -> "Kompile Model Staging";
            case "kompile-cli" -> "Kompile CLI";
            case "kompile-app" -> "Kompile App CLI";
            case "kompile-model" -> "Kompile Model CLI";
            case "kompile-agent" -> "Kompile Agent CLI";
            default -> id;
        };
    }

    private String getComponentDescription(String id) {
        return switch (id) {
            case "kompile-app-main" -> "Spring Boot RAG application with web UI";
            case "kompile-model-staging" -> "Model lifecycle management service";
            case "kompile-cli" -> "Main command-line interface";
            case "kompile-app" -> "Application management CLI";
            case "kompile-model" -> "Model management CLI";
            case "kompile-agent" -> "Agent management CLI";
            default -> "Unknown component";
        };
    }

    private String getComponentType(String id) {
        if (id.contains("app-main") || id.contains("model-staging")) {
            return "spring-boot-app";
        }
        return "cli";
    }

    private String getInstallPath(String componentId) {
        String homeDir = System.getProperty("user.home");
        File componentsDir = new File(homeDir, ".kompile/components/" + componentId);
        
        if (!componentsDir.exists()) {
            return null;
        }

        File[] versions = componentsDir.listFiles(File::isDirectory);
        if (versions == null || versions.length == 0) {
            return null;
        }

        return versions[0].getAbsolutePath();
    }

    private long getFileSize(File dir) {
        long size = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    size += file.length();
                } else if (file.isDirectory()) {
                    size += getFileSize(file);
                }
            }
        }
        return size;
    }

    private File findJarFile(File directory) {
        File[] jars = directory.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars != null && jars.length > 0) {
            return jars[0];
        }

        // Search recursively
        File[] dirs = directory.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File dir : dirs) {
                File jar = findJarFile(dir);
                if (jar != null) {
                    return jar;
                }
            }
        }

        return null;
    }

    private Integer getDefaultPort(String id) {
        return switch (id) {
            case "kompile-app-main" -> 8080;
            case "kompile-model-staging" -> 8081;
            default -> null;
        };
    }

    private String getMainClass(String id) {
        return switch (id) {
            case "kompile-app-main" -> "ai.kompile.app.MainApplication";
            case "kompile-model-staging" -> "ai.kompile.staging.ModelStagingApplication";
            case "kompile-cli" -> "ai.kompile.cli.main.MainCommand";
            case "kompile-app" -> "ai.kompile.cli.app.AppCliMain";
            case "kompile-model" -> "ai.kompile.cli.model.ModelCliMain";
            case "kompile-agent" -> "ai.kompile.cli.agent.AgentCliMain";
            default -> "unknown";
        };
    }

    private List<String> getDefaultJvmArgs(String id) {
        if (id.contains("app-main") || id.contains("model-staging")) {
            return List.of("-Xmx4g", "-Xms2g");
        }
        return List.of("-Xmx512m");
    }
}
