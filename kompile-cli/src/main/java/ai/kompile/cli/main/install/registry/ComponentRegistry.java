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

package ai.kompile.cli.main.install.registry;

import ai.kompile.cli.main.Info;
import ai.kompile.cli.main.util.OSResolver;

import java.io.File;
import java.util.*;

/**
 * Centralized registry for managing downloadable Kompile components.
 * Handles URL resolution, version management, and install path configuration.
 * 
 * Supported release sources:
 * - GitHub Releases (default: KonduitAI/kompile)
 * - Maven Central/Repository
 * - Custom mirror URLs
 */
public class ComponentRegistry {

    // Default repository configuration
    private static final String DEFAULT_GITHUB_REPO = "KonduitAI/kompile";
    private static final String GITHUB_RELEASES_BASE = "https://github.com/%s/releases/download/";
    private static final String MAVEN_CENTRAL_BASE = "https://repo1.maven.org/maven2/";
    
    // Component identifiers
    public static final String KOMPILE_APP_MAIN = "kompile-app-main";
    public static final String KOMPILE_MODEL_STAGING = "kompile-model-staging";
    public static final String KOMPILE_CLI = "kompile-cli";
    public static final String KOMPILE_AGENT = "kompile-agent";
    public static final String KOMPILE_LITE = "kompile-lite";

    // Component metadata map
    private static final Map<String, ComponentDescriptor> COMPONENTS = new HashMap<>();

    static {
        // Register kompile-app-main
        COMPONENTS.put(KOMPILE_APP_MAIN, ComponentDescriptor.builder()
                .id(KOMPILE_APP_MAIN)
                .name("Kompile App Main")
                .description("Spring Boot RAG application with web UI")
                .type("app")
                .defaultPort(8080)
                .mainClass("ai.kompile.app.MainApplication")
                .artifactId("kompile-app-main")
                .groupId("ai.kompile")
                .build());

        // Register kompile-model-staging
        COMPONENTS.put(KOMPILE_MODEL_STAGING, ComponentDescriptor.builder()
                .id(KOMPILE_MODEL_STAGING)
                .name("Kompile Model Staging")
                .description("Model lifecycle management service")
                .type("staging")
                .defaultPort(8081)
                .mainClass("ai.kompile.modelstaging.MainApplication")
                .artifactId("kompile-model-staging")
                .groupId("ai.kompile")
                .build());

        // Register kompile-cli
        COMPONENTS.put(KOMPILE_CLI, ComponentDescriptor.builder()
                .id(KOMPILE_CLI)
                .name("Kompile CLI")
                .description("Command-line interface")
                .type("cli")
                .artifactId("kompile-cli")
                .groupId("ai.kompile")
                .build());
    }

    private String githubRepo;
    private String mavenRepoUrl;
    private String version;
    private Map<String, String> customUrls;
    private File installBaseDir;

    public ComponentRegistry() {
        this.githubRepo = DEFAULT_GITHUB_REPO;
        this.mavenRepoUrl = MAVEN_CENTRAL_BASE;
        this.version = Info.getVersion();
        this.customUrls = new HashMap<>();
        this.installBaseDir = Info.homeDirectory();
    }

    /**
     * Get component descriptor by ID
     */
    public Optional<ComponentDescriptor> getComponent(String componentId) {
        return Optional.ofNullable(COMPONENTS.get(componentId));
    }

    /**
     * List all registered components
     */
    public List<ComponentDescriptor> listAllComponents() {
        return new ArrayList<>(COMPONENTS.values());
    }

    /**
     * Resolve download URL for a component
     */
    public String resolveDownloadUrl(String componentId, ReleaseSource source) {
        // Check for custom URL override
        if (customUrls.containsKey(componentId)) {
            return customUrls.get(componentId);
        }

        ComponentDescriptor descriptor = COMPONENTS.get(componentId);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown component: " + componentId);
        }

        String os = OSResolver.os();
        String arch = OSResolver.arch();
        String platform = os + "-" + arch;

        switch (source) {
            case GITHUB_RELEASES:
                return buildGitHubReleaseUrl(componentId, platform);
            case MAVEN:
                return buildMavenUrl(descriptor);
            default:
                throw new IllegalArgumentException("Unknown release source: " + source);
        }
    }

    /**
     * Build GitHub Releases download URL
     */
    private String buildGitHubReleaseUrl(String componentId, String platform) {
        String tagName = "v" + version;
        String fileName = componentId + "-" + version + "-" + platform + ".tar.gz";
        return String.format(GITHUB_RELEASES_BASE + "%s/%s/%s", githubRepo, tagName, fileName);
    }

    /**
     * Build Maven repository URL for a component
     */
    private String buildMavenUrl(ComponentDescriptor descriptor) {
        String artifactPath = descriptor.getGroupId().replace('.', '/') + "/" +
                descriptor.getArtifactId() + "/" +
                version + "/" +
                descriptor.getArtifactId() + "-" + version + ".jar";
        return mavenRepoUrl + artifactPath;
    }

    /**
     * Get install directory for a component
     */
    public File getInstallDirectory(String componentId) {
        return new File(installBaseDir, "components/" + componentId + "/" + version);
    }

    /**
     * Get the JAR file path for an installed component
     */
    public File getJarPath(String componentId) {
        File installDir = getInstallDirectory(componentId);
        return new File(installDir, componentId + "-" + version + ".jar");
    }

    /**
     * Check if a component is installed
     */
    public boolean isInstalled(String componentId) {
        File jarPath = getJarPath(componentId);
        return jarPath.exists();
    }

    // Getters and setters

    public String getGithubRepo() {
        return githubRepo;
    }

    public void setGithubRepo(String githubRepo) {
        this.githubRepo = githubRepo;
    }

    public String getMavenRepoUrl() {
        return mavenRepoUrl;
    }

    public void setMavenRepoUrl(String mavenRepoUrl) {
        this.mavenRepoUrl = mavenRepoUrl;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Map<String, String> getCustomUrls() {
        return customUrls;
    }

    public void setCustomUrl(String componentId, String url) {
        this.customUrls.put(componentId, url);
    }

    public File getInstallBaseDir() {
        return installBaseDir;
    }

    public void setInstallBaseDir(File installBaseDir) {
        this.installBaseDir = installBaseDir;
    }

    /**
     * Release source enumeration
     */
    public enum ReleaseSource {
        GITHUB_RELEASES,
        MAVEN,
        CUSTOM
    }

    /**
     * Component descriptor POJO
     */
    public static class ComponentDescriptor {
        private String id;
        private String name;
        private String description;
        private String type;
        private Integer defaultPort;
        private String mainClass;
        private String artifactId;
        private String groupId;

        private ComponentDescriptor(Builder builder) {
            this.id = builder.id;
            this.name = builder.name;
            this.description = builder.description;
            this.type = builder.type;
            this.defaultPort = builder.defaultPort;
            this.mainClass = builder.mainClass;
            this.artifactId = builder.artifactId;
            this.groupId = builder.groupId;
        }

        public static Builder builder() {
            return new Builder();
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public String getType() { return type; }
        public Optional<Integer> getDefaultPort() { return Optional.ofNullable(defaultPort); }
        public Optional<String> getMainClass() { return Optional.ofNullable(mainClass); }
        public String getArtifactId() { return artifactId; }
        public String getGroupId() { return groupId; }

        public static class Builder {
            private String id;
            private String name;
            private String description;
            private String type;
            private Integer defaultPort;
            private String mainClass;
            private String artifactId;
            private String groupId;

            public Builder id(String id) { this.id = id; return this; }
            public Builder name(String name) { this.name = name; return this; }
            public Builder description(String description) { this.description = description; return this; }
            public Builder type(String type) { this.type = type; return this; }
            public Builder defaultPort(Integer defaultPort) { this.defaultPort = defaultPort; return this; }
            public Builder mainClass(String mainClass) { this.mainClass = mainClass; return this; }
            public Builder artifactId(String artifactId) { this.artifactId = artifactId; return this; }
            public Builder groupId(String groupId) { this.groupId = groupId; return this; }

            public ComponentDescriptor build() {
                return new ComponentDescriptor(this);
            }
        }
    }
}
