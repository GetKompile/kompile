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

package ai.kompile.cli.main.build.config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Immutable POJO holding all resolved configuration for a build.
 */
public final class BuildConfiguration {

    private final String configName;
    private final File outputDir;
    private final ModuleSelection modules;
    private final boolean buildNative;
    private final boolean skipTests;
    private final boolean cleanBuild;

    // Maven/Java settings
    private final String javacppPlatform;
    private final String javacppExtension;
    private final String device;
    private final String cudaVersion;
    private final String backend;
    private final File mavenHome;
    private final File graalVmHome;

    // App metadata
    private final String appTitle;
    private final String instanceGroupId;
    private final String instanceVersion;
    private final String ragMcpVersion;

    // Database (for pgvector)
    private final String databaseUrl;
    private final String databaseUsername;
    private final String databasePassword;
    private final boolean enableSchemaInit;

    // OpenNLP / Anserini
    private final List<String> supportedLanguages;
    private final List<String> anseriniIndexIds;
    private final List<String> anseriniEncoderModelIds;

    // Archive / model staging
    private final File archivePath;
    private final String modelSourceType;
    private final List<String> registryUrls;
    private final boolean archiveOnly;

    // Container deployment (Jib)
    private final boolean buildContainer;
    private final String containerImageName;
    private final String containerBaseImage;
    private final String containerRegistry;
    private final List<String> containerPorts;
    private final List<String> containerJvmFlags;

    // Pipeline-specific
    private final String appType;
    private final File pipelineFile;
    private final List<String> pipelineComponents;

    private BuildConfiguration(Builder b) {
        this.configName = b.configName;
        this.outputDir = b.outputDir;
        this.modules = b.modules;
        this.buildNative = b.buildNative;
        this.skipTests = b.skipTests;
        this.cleanBuild = b.cleanBuild;
        this.javacppPlatform = b.javacppPlatform;
        this.javacppExtension = b.javacppExtension;
        this.device = b.device;
        this.cudaVersion = b.cudaVersion;
        this.backend = b.backend;
        this.mavenHome = b.mavenHome;
        this.graalVmHome = b.graalVmHome;
        this.appTitle = b.appTitle;
        this.instanceGroupId = b.instanceGroupId;
        this.instanceVersion = b.instanceVersion;
        this.ragMcpVersion = b.ragMcpVersion;
        this.databaseUrl = b.databaseUrl;
        this.databaseUsername = b.databaseUsername;
        this.databasePassword = b.databasePassword;
        this.enableSchemaInit = b.enableSchemaInit;
        this.supportedLanguages = b.supportedLanguages;
        this.anseriniIndexIds = b.anseriniIndexIds;
        this.anseriniEncoderModelIds = b.anseriniEncoderModelIds;
        this.archivePath = b.archivePath;
        this.modelSourceType = b.modelSourceType;
        this.registryUrls = b.registryUrls;
        this.archiveOnly = b.archiveOnly;
        this.buildContainer = b.buildContainer;
        this.containerImageName = b.containerImageName;
        this.containerBaseImage = b.containerBaseImage;
        this.containerRegistry = b.containerRegistry;
        this.containerPorts = b.containerPorts;
        this.containerJvmFlags = b.containerJvmFlags;
        this.appType = b.appType;
        this.pipelineFile = b.pipelineFile;
        this.pipelineComponents = b.pipelineComponents;
    }

    // Getters
    public String getConfigName() { return configName; }
    public File getOutputDir() { return outputDir; }
    public ModuleSelection getModules() { return modules; }
    public boolean isBuildNative() { return buildNative; }
    public boolean isSkipTests() { return skipTests; }
    public boolean isCleanBuild() { return cleanBuild; }
    public String getJavacppPlatform() { return javacppPlatform; }
    public String getJavacppExtension() { return javacppExtension; }
    public String getDevice() { return device; }
    public String getCudaVersion() { return cudaVersion; }
    public String getBackend() { return backend; }
    public File getMavenHome() { return mavenHome; }
    public File getGraalVmHome() { return graalVmHome; }
    public String getAppTitle() { return appTitle; }
    public String getInstanceGroupId() { return instanceGroupId; }
    public String getInstanceVersion() { return instanceVersion; }
    public String getRagMcpVersion() { return ragMcpVersion; }
    public String getDatabaseUrl() { return databaseUrl; }
    public String getDatabaseUsername() { return databaseUsername; }
    public String getDatabasePassword() { return databasePassword; }
    public boolean isEnableSchemaInit() { return enableSchemaInit; }
    public List<String> getSupportedLanguages() { return supportedLanguages; }
    public List<String> getAnseriniIndexIds() { return anseriniIndexIds; }
    public List<String> getAnseriniEncoderModelIds() { return anseriniEncoderModelIds; }
    public File getArchivePath() { return archivePath; }
    public String getModelSourceType() { return modelSourceType; }
    public List<String> getRegistryUrls() { return registryUrls; }
    public boolean isArchiveOnly() { return archiveOnly; }
    public boolean isBuildContainer() { return buildContainer; }
    public String getContainerImageName() { return containerImageName; }
    public String getContainerBaseImage() { return containerBaseImage; }
    public String getContainerRegistry() { return containerRegistry; }
    public List<String> getContainerPorts() { return containerPorts; }
    public List<String> getContainerJvmFlags() { return containerJvmFlags; }
    public String getAppType() { return appType; }
    public File getPipelineFile() { return pipelineFile; }
    public List<String> getPipelineComponents() { return pipelineComponents; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String configName;
        private File outputDir = new File("kompile-rag-builds");
        private ModuleSelection modules;
        private boolean buildNative = true;
        private boolean skipTests = true;
        private boolean cleanBuild = true;
        private String javacppPlatform = "linux-x86_64";
        private String javacppExtension;
        private String device = "CPU";
        private String cudaVersion;
        private String backend = "nd4j-cuda-12.9";
        private File mavenHome;
        private File graalVmHome;
        private String appTitle = "Kompile RAG Console";
        private String instanceGroupId = "ai.kompile.rag.instance";
        private String instanceVersion = "0.1.0-SNAPSHOT";
        private String ragMcpVersion = "0.1.0-SNAPSHOT";
        private String databaseUrl = "jdbc:postgresql://localhost:5432/kompile_db";
        private String databaseUsername = "postgres";
        private String databasePassword = "postgres";
        private boolean enableSchemaInit = true;
        private List<String> supportedLanguages = new ArrayList<>(List.of("en"));
        private List<String> anseriniIndexIds = new ArrayList<>();
        private List<String> anseriniEncoderModelIds = new ArrayList<>();
        private File archivePath;
        private String modelSourceType = "HYBRID";
        private List<String> registryUrls;
        private boolean archiveOnly;
        private boolean buildContainer;
        private String containerImageName;
        private String containerBaseImage = "eclipse-temurin:17-jre";
        private String containerRegistry;
        private List<String> containerPorts = new ArrayList<>(List.of("8080"));
        private List<String> containerJvmFlags = new ArrayList<>();
        private String appType = "rag";
        private File pipelineFile;
        private List<String> pipelineComponents;

        public Builder configName(String v) { this.configName = v; return this; }
        public Builder outputDir(File v) { this.outputDir = v; return this; }
        public Builder modules(ModuleSelection v) { this.modules = v; return this; }
        public Builder buildNative(boolean v) { this.buildNative = v; return this; }
        public Builder skipTests(boolean v) { this.skipTests = v; return this; }
        public Builder cleanBuild(boolean v) { this.cleanBuild = v; return this; }
        public Builder javacppPlatform(String v) { this.javacppPlatform = v; return this; }
        public Builder javacppExtension(String v) { this.javacppExtension = v; return this; }
        public Builder device(String v) { this.device = v; return this; }
        public Builder cudaVersion(String v) { this.cudaVersion = v; return this; }
        public Builder backend(String v) { if (v != null && !v.isBlank()) this.backend = v; return this; }
        public Builder mavenHome(File v) { this.mavenHome = v; return this; }
        public Builder graalVmHome(File v) { this.graalVmHome = v; return this; }
        public Builder appTitle(String v) { this.appTitle = v; return this; }
        public Builder instanceGroupId(String v) { this.instanceGroupId = v; return this; }
        public Builder instanceVersion(String v) { this.instanceVersion = v; return this; }
        public Builder ragMcpVersion(String v) { this.ragMcpVersion = v; return this; }
        public Builder databaseUrl(String v) { this.databaseUrl = v; return this; }
        public Builder databaseUsername(String v) { this.databaseUsername = v; return this; }
        public Builder databasePassword(String v) { this.databasePassword = v; return this; }
        public Builder enableSchemaInit(boolean v) { this.enableSchemaInit = v; return this; }
        public Builder supportedLanguages(List<String> v) { this.supportedLanguages = v; return this; }
        public Builder anseriniIndexIds(List<String> v) { this.anseriniIndexIds = v; return this; }
        public Builder anseriniEncoderModelIds(List<String> v) { this.anseriniEncoderModelIds = v; return this; }
        public Builder archivePath(File v) { this.archivePath = v; return this; }
        public Builder modelSourceType(String v) { this.modelSourceType = v; return this; }
        public Builder registryUrls(List<String> v) { this.registryUrls = v; return this; }
        public Builder archiveOnly(boolean v) { this.archiveOnly = v; return this; }
        public Builder buildContainer(boolean v) { this.buildContainer = v; return this; }
        public Builder containerImageName(String v) { this.containerImageName = v; return this; }
        public Builder containerBaseImage(String v) { if (v != null && !v.isBlank()) this.containerBaseImage = v; return this; }
        public Builder containerRegistry(String v) { this.containerRegistry = v; return this; }
        public Builder containerPorts(List<String> v) { if (v != null) this.containerPorts = v; return this; }
        public Builder containerJvmFlags(List<String> v) { if (v != null) this.containerJvmFlags = v; return this; }
        public Builder appType(String v) { this.appType = v; return this; }
        public Builder pipelineFile(File v) { this.pipelineFile = v; return this; }
        public Builder pipelineComponents(List<String> v) { this.pipelineComponents = v; return this; }

        public BuildConfiguration build() {
            if (configName == null || configName.isEmpty()) {
                throw new IllegalArgumentException("configName is required");
            }
            if (modules == null) {
                throw new IllegalArgumentException("modules (ModuleSelection) is required");
            }
            return new BuildConfiguration(this);
        }
    }
}
