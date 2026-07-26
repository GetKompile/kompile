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
    public static final String KOMPILE_APP_CHAT = "kompile-app-chat";
    public static final String KOMPILE_APP_CRAWL_MANAGER = "kompile-app-crawl-manager";
    public static final String KOMPILE_MODEL_STAGING = "kompile-model-staging";
    public static final String KOMPILE_GRAPH_SERVICE = "kompile-graph-service";
    public static final String KOMPILE_CLI = "kompile-cli";
    public static final String KOMPILE_AGENT = "kompile-agent";
    public static final String KOMPILE_LITE = "kompile-lite";

    // Component metadata map
    private static final Map<String, ComponentDescriptor> COMPONENTS = new HashMap<>();

    /**
     * Alternate artifact/binary base names per component. Distributions ship the
     * app as {@code kompile-server} (bin/kompile-server, lib/kompile-server.jar)
     * while the component id stays {@code kompile-app-main}.
     */
    private static final Map<String, List<String>> BINARY_ALIASES = Map.of(
            KOMPILE_APP_MAIN, List.of("kompile-app-main", "kompile-server"),
            KOMPILE_APP_CHAT, List.of("kompile-app-chat", "kompile-chat"),
            KOMPILE_APP_CRAWL_MANAGER, List.of("kompile-app-crawl-manager", "kompile-crawl-manager"),
            KOMPILE_MODEL_STAGING, List.of("kompile-model-staging"),
            KOMPILE_GRAPH_SERVICE, List.of("kompile-graph-service"),
            KOMPILE_CLI, List.of("kompile-cli", "kompile"));

    private static List<String> aliasesFor(String componentId) {
        return BINARY_ALIASES.getOrDefault(componentId, List.of(componentId));
    }

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

        // Register kompile-app-chat — the end-user chat persona (chat, agents, RAG,
        // project browsing). Ships an exec jar, unlike app-main's thin library artifact.
        COMPONENTS.put(KOMPILE_APP_CHAT, ComponentDescriptor.builder()
                .id(KOMPILE_APP_CHAT)
                .name("Kompile Chat")
                .description("End-user chat application with project browsing and RAG")
                .type("app")
                .defaultPort(8081)
                .mainClass("ai.kompile.app.chat.ChatApplication")
                .artifactId("kompile-app-chat")
                .artifactClassifier("exec")
                .groupId("ai.kompile")
                .build());

        // Register kompile-app-crawl-manager — the end-user ingest persona (crawls,
        // indexing, fact sheets, documents, note sync).
        COMPONENTS.put(KOMPILE_APP_CRAWL_MANAGER, ComponentDescriptor.builder()
                .id(KOMPILE_APP_CRAWL_MANAGER)
                .name("Kompile Crawl Manager")
                .description("End-user crawl, ingest, and index management application")
                .type("app")
                .defaultPort(8082)
                .mainClass("ai.kompile.app.crawlmanager.CrawlManagerApplication")
                .artifactId("kompile-app-crawl-manager")
                .artifactClassifier("exec")
                .groupId("ai.kompile")
                .build());

        // Register kompile-model-staging
        COMPONENTS.put(KOMPILE_MODEL_STAGING, ComponentDescriptor.builder()
                .id(KOMPILE_MODEL_STAGING)
                .name("Kompile Model Staging")
                .description("Model lifecycle management service")
                .type("staging")
                .defaultPort(8090)
                .mainClass("ai.kompile.modelstaging.MainApplication")
                .artifactId("kompile-model-staging")
                .groupId("ai.kompile")
                .build());

        // Register the standalone graph persistence and reasoning service
        COMPONENTS.put(KOMPILE_GRAPH_SERVICE, ComponentDescriptor.builder()
                .id(KOMPILE_GRAPH_SERVICE)
                .name("Kompile Graph Service")
                .description("Knowledge-graph persistence, interchange, and reasoning service")
                .type("graph")
                .defaultPort(8095)
                .mainClass("ai.kompile.graph.service.GraphServiceApplication")
                .artifactId("kompile-graph-service")
                .artifactClassifier("exec")
                .groupId("ai.kompile")
                .build());

        // Register kompile-cli
        COMPONENTS.put(KOMPILE_CLI, ComponentDescriptor.builder()
                .id(KOMPILE_CLI)
                .name("Kompile CLI")
                .description("Command-line interface")
                .type("cli")
                .artifactId("kompile-cli-main")
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
        this.installBaseDir = resolveInstallBaseDir();
    }

    /**
     * Installation base directory holding {@code bin/} native binaries and
     * {@code lib/} distribution jars. Custom install locations
     * ({@code install.sh --dir}, {@code KOMPILE_INSTALL_DIR}) must be honored
     * here — otherwise every component lookup silently falls back to
     * {@code ~/.kompile} and {@code project start} cannot find the installed
     * binaries. User/project STATE remains under {@link Info#homeDirectory()}
     * regardless of where the binaries are installed.
     * Resolution: {@code -Dkompile.install.dir} &gt; {@code $KOMPILE_INSTALL_DIR}
     * &gt; {@code ~/.kompile}.
     */
    static File resolveInstallBaseDir() {
        String prop = System.getProperty("kompile.install.dir");
        if (prop != null && !prop.isBlank()) {
            return new File(prop);
        }
        String env = System.getenv("KOMPILE_INSTALL_DIR");
        if (env != null && !env.isBlank()) {
            return new File(env);
        }
        return Info.homeDirectory();
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
        return String.format(GITHUB_RELEASES_BASE + "%s/%s", githubRepo, tagName, fileName);
    }

    /**
     * Build Maven repository URL for a component
     */
    private String buildMavenUrl(ComponentDescriptor descriptor) {
        String classifier = descriptor.getArtifactClassifier()
                .map(value -> "-" + value)
                .orElse("");
        String artifactPath = descriptor.getGroupId().replace('.', '/') + "/" +
                descriptor.getArtifactId() + "/" +
                version + "/" +
                descriptor.getArtifactId() + "-" + version + classifier + ".jar";
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
     * Get the JAR path from a distribution-style install (lib/ directory).
     * Returns null if no matching JAR is found.
     */
    public File getDistributionJarPath(String componentId) {
        File libDir = new File(installBaseDir, "lib");
        if (!libDir.isDirectory()) return null;
        // Exact <alias>.jar wins over the prefix scan: with sibling components sharing a
        // prefix (kompile-chat.jar vs kompile-chat-local.jar) a prefix match is a coin flip.
        for (String alias : aliasesFor(componentId)) {
            File exact = new File(libDir, alias + ".jar");
            if (exact.isFile()) {
                return exact;
            }
        }
        for (String alias : aliasesFor(componentId)) {
            File[] jars = libDir.listFiles((dir, name) ->
                    name.startsWith(alias) && name.endsWith(".jar"));
            if (jars != null && jars.length > 0) {
                return jars[0];
            }
        }
        return null;
    }

    /**
     * Get the native executable from a distribution-style install (bin/ directory),
     * e.g. {@code ~/.kompile/bin/kompile-server}. Returns null if none is present.
     */
    public File getDistributionBinaryPath(String componentId) {
        File binDir = new File(installBaseDir, "bin");
        if (!binDir.isDirectory()) return null;
        for (String alias : aliasesFor(componentId)) {
            File exe = new File(binDir, alias);
            if (exe.isFile() && exe.canExecute() && !exe.getName().endsWith(".jar")) {
                return exe;
            }
            File exeWindows = new File(binDir, alias + ".exe");
            if (exeWindows.isFile() && exeWindows.canExecute()) {
                return exeWindows;
            }
        }
        return null;
    }

    /**
     * Check if a component is installed
     */
    public boolean isInstalled(String componentId) {
        return findInstalledJar(componentId) != null;
    }

    /**
     * Find the installed JAR or native executable for a component.
     * Searches multiple locations and naming conventions:
     *   0. Distribution native binary at ~/.kompile/bin/ (AOT-first; aliases apply,
     *      e.g. kompile-app-main resolves bin/kompile-server)
     *   1. Distribution install at ~/.kompile/lib/ (aliases apply)
     *   2. Canonical name at ~/.kompile/components/<id>/<version>/<id>-<version>.jar
     *   3. Exec JAR at ~/.kompile/components/<id>/<version>/<id>-<version>-exec.jar
     *   4. Any matching JAR in the latest version directory
     *   5. Native executable at ~/.kompile/components/<id>/<id>
     */
    public File findInstalledJar(String componentId) {
        // 0. Distribution native binary
        File distBinary = getDistributionBinaryPath(componentId);
        if (distBinary != null) return distBinary;

        // 1. Distribution install
        File distJar = getDistributionJarPath(componentId);
        if (distJar != null && distJar.isFile()) return distJar;

        // 2. Canonical name
        File canonicalJar = getJarPath(componentId);
        if (canonicalJar.isFile()) return canonicalJar;

        // 3. Exec JAR in canonical version dir
        File installDir = getInstallDirectory(componentId);
        File execJar = new File(installDir, componentId + "-" + version + "-exec.jar");
        if (execJar.isFile()) return execJar;

        // 4. Any matching JAR in any version dir (newest first)
        File componentDir = new File(installBaseDir, "components/" + componentId);
        if (componentDir.isDirectory()) {
            File[] versionDirs = componentDir.listFiles(File::isDirectory);
            if (versionDirs != null && versionDirs.length > 0) {
                java.util.Arrays.sort(versionDirs, (a, b) -> b.getName().compareTo(a.getName()));
                for (File versionDir : versionDirs) {
                    File[] jars = versionDir.listFiles(
                            (dir, name) -> name.startsWith(componentId) && name.endsWith(".jar"));
                    if (jars != null && jars.length > 0) return jars[0];
                }
            }

            // 5. Native executable at component root
            File nativeExe = new File(componentDir, componentId);
            if (nativeExe.isFile() && nativeExe.canExecute()) return nativeExe;
        }

        return null;
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
        private String artifactClassifier;
        private String groupId;

        private ComponentDescriptor(Builder builder) {
            this.id = builder.id;
            this.name = builder.name;
            this.description = builder.description;
            this.type = builder.type;
            this.defaultPort = builder.defaultPort;
            this.mainClass = builder.mainClass;
            this.artifactId = builder.artifactId;
            this.artifactClassifier = builder.artifactClassifier;
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
        public Optional<String> getArtifactClassifier() { return Optional.ofNullable(artifactClassifier); }
        public String getGroupId() { return groupId; }

        public static class Builder {
            private String id;
            private String name;
            private String description;
            private String type;
            private Integer defaultPort;
            private String mainClass;
            private String artifactId;
            private String artifactClassifier;
            private String groupId;

            public Builder id(String id) { this.id = id; return this; }
            public Builder name(String name) { this.name = name; return this; }
            public Builder description(String description) { this.description = description; return this; }
            public Builder type(String type) { this.type = type; return this; }
            public Builder defaultPort(Integer defaultPort) { this.defaultPort = defaultPort; return this; }
            public Builder mainClass(String mainClass) { this.mainClass = mainClass; return this; }
            public Builder artifactId(String artifactId) { this.artifactId = artifactId; return this; }
            public Builder artifactClassifier(String artifactClassifier) { this.artifactClassifier = artifactClassifier; return this; }
            public Builder groupId(String groupId) { this.groupId = groupId; return this; }

            public ComponentDescriptor build() {
                return new ComponentDescriptor(this);
            }
        }
    }
}
