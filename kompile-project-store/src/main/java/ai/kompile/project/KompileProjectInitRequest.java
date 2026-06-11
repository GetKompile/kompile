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
package ai.kompile.project;

import java.util.ArrayList;
import java.util.List;

public class KompileProjectInitRequest {
    private String name;
    private String description;
    private KompileProjectStorageBackend backend = KompileProjectStorageBackend.LOCAL;
    private String remoteUrl;
    private String branch = "main";
    private boolean includeStandardComponents = true;
    private boolean initializeGit;
    private boolean installGitXet;
    private List<String> tags = new ArrayList<>();
    private List<String> modules = new ArrayList<>();
    private List<KompileProjectComponent> components = new ArrayList<>();
    private List<KompileProjectModel> models = new ArrayList<>();
    private List<KompileProjectPipeline> pipelines = new ArrayList<>();
    private List<KompileProjectScript> scripts = new ArrayList<>();
    private List<KompileProjectCrawlProfile> crawlProfiles = new ArrayList<>();
    private List<KompileProjectWorkflow> workflows = new ArrayList<>();
    private List<KompileCodingProject> codingProjects = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public KompileProjectStorageBackend getBackend() {
        return backend;
    }

    public void setBackend(KompileProjectStorageBackend backend) {
        this.backend = backend == null ? KompileProjectStorageBackend.LOCAL : backend;
    }

    public String getRemoteUrl() {
        return remoteUrl;
    }

    public void setRemoteUrl(String remoteUrl) {
        this.remoteUrl = remoteUrl;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch == null || branch.isBlank() ? "main" : branch;
    }

    public boolean isIncludeStandardComponents() {
        return includeStandardComponents;
    }

    public void setIncludeStandardComponents(boolean includeStandardComponents) {
        this.includeStandardComponents = includeStandardComponents;
    }

    public boolean isInitializeGit() {
        return initializeGit;
    }

    public void setInitializeGit(boolean initializeGit) {
        this.initializeGit = initializeGit;
    }

    public boolean isInstallGitXet() {
        return installGitXet;
    }

    public void setInstallGitXet(boolean installGitXet) {
        this.installGitXet = installGitXet;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
    }

    public List<String> getModules() {
        return modules;
    }

    public void setModules(List<String> modules) {
        this.modules = modules == null ? new ArrayList<>() : new ArrayList<>(modules);
    }

    public List<KompileProjectComponent> getComponents() {
        return components;
    }

    public void setComponents(List<KompileProjectComponent> components) {
        this.components = components == null ? new ArrayList<>() : new ArrayList<>(components);
    }

    public List<KompileProjectModel> getModels() {
        return models;
    }

    public void setModels(List<KompileProjectModel> models) {
        this.models = models == null ? new ArrayList<>() : new ArrayList<>(models);
    }

    public List<KompileProjectPipeline> getPipelines() {
        return pipelines;
    }

    public void setPipelines(List<KompileProjectPipeline> pipelines) {
        this.pipelines = pipelines == null ? new ArrayList<>() : new ArrayList<>(pipelines);
    }

    public List<KompileProjectScript> getScripts() {
        return scripts;
    }

    public void setScripts(List<KompileProjectScript> scripts) {
        this.scripts = scripts == null ? new ArrayList<>() : new ArrayList<>(scripts);
    }

    public List<KompileProjectCrawlProfile> getCrawlProfiles() {
        return crawlProfiles;
    }

    public void setCrawlProfiles(List<KompileProjectCrawlProfile> crawlProfiles) {
        this.crawlProfiles = crawlProfiles == null ? new ArrayList<>() : new ArrayList<>(crawlProfiles);
    }

    public List<KompileProjectWorkflow> getWorkflows() {
        return workflows;
    }

    public void setWorkflows(List<KompileProjectWorkflow> workflows) {
        this.workflows = workflows == null ? new ArrayList<>() : new ArrayList<>(workflows);
    }

    public List<KompileCodingProject> getCodingProjects() {
        return codingProjects;
    }

    public void setCodingProjects(List<KompileCodingProject> codingProjects) {
        this.codingProjects = codingProjects == null ? new ArrayList<>() : new ArrayList<>(codingProjects);
    }
}
