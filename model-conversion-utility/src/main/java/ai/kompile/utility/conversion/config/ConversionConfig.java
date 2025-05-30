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

package ai.kompile.utility.conversion.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Main configuration for the model conversion process.
 */
public class ConversionConfig {
    
    @JsonProperty("github_config")
    private GitHubConfig githubConfig;
    
    @JsonProperty("conversion_settings")
    private ConversionSettings conversionSettings;
    
    @JsonProperty("models")
    private List<ModelDefinition> models;

    // Constructors
    public ConversionConfig() {}

    // Getters and Setters
    public GitHubConfig getGithubConfig() { return githubConfig; }
    public void setGithubConfig(GitHubConfig githubConfig) { this.githubConfig = githubConfig; }

    public ConversionSettings getConversionSettings() { return conversionSettings; }
    public void setConversionSettings(ConversionSettings conversionSettings) { this.conversionSettings = conversionSettings; }

    public List<ModelDefinition> getModels() { return models; }
    public void setModels(List<ModelDefinition> models) { this.models = models; }

    /**
     * GitHub-specific configuration for uploads.
     */
    public static class GitHubConfig {
        @JsonProperty("repository_owner")
        private String repositoryOwner;
        
        @JsonProperty("repository_name")
        private String repositoryName;
        
        @JsonProperty("release_tag")
        private String releaseTag;
        
        @JsonProperty("base_download_url")
        private String baseDownloadUrl;
        
        @JsonProperty("access_token_env_var")
        private String accessTokenEnvVar = "GITHUB_TOKEN";

        public String getRepositoryOwner() { return repositoryOwner; }
        public void setRepositoryOwner(String repositoryOwner) { this.repositoryOwner = repositoryOwner; }

        public String getRepositoryName() { return repositoryName; }
        public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }

        public String getReleaseTag() { return releaseTag; }
        public void setReleaseTag(String releaseTag) { this.releaseTag = releaseTag; }

        public String getBaseDownloadUrl() { return baseDownloadUrl; }
        public void setBaseDownloadUrl(String baseDownloadUrl) { this.baseDownloadUrl = baseDownloadUrl; }

        public String getAccessTokenEnvVar() { return accessTokenEnvVar; }
        public void setAccessTokenEnvVar(String accessTokenEnvVar) { this.accessTokenEnvVar = accessTokenEnvVar; }
    }

    /**
     * General conversion settings.
     */
    public static class ConversionSettings {
        @JsonProperty("working_directory")
        private String workingDirectory = "./conversion-workspace";
        
        @JsonProperty("cleanup_after_conversion")
        private boolean cleanupAfterConversion = true;
        
        @JsonProperty("parallel_conversions")
        private int parallelConversions = 1;
        
        @JsonProperty("verify_conversions")
        private boolean verifyConversions = true;
        
        @JsonProperty("generate_checksums")
        private boolean generateChecksums = true;

        public String getWorkingDirectory() { return workingDirectory; }
        public void setWorkingDirectory(String workingDirectory) { this.workingDirectory = workingDirectory; }

        public boolean isCleanupAfterConversion() { return cleanupAfterConversion; }
        public void setCleanupAfterConversion(boolean cleanupAfterConversion) { this.cleanupAfterConversion = cleanupAfterConversion; }

        public int getParallelConversions() { return parallelConversions; }
        public void setParallelConversions(int parallelConversions) { this.parallelConversions = parallelConversions; }

        public boolean isVerifyConversions() { return verifyConversions; }
        public void setVerifyConversions(boolean verifyConversions) { this.verifyConversions = verifyConversions; }

        public boolean isGenerateChecksums() { return generateChecksums; }
        public void setGenerateChecksums(boolean generateChecksums) { this.generateChecksums = generateChecksums; }
    }
}
