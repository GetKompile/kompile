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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class KompileProjectManifest {
    private int schemaVersion = 1;
    private String projectId;
    private String name;
    private String description;
    private KompileProjectLifecycleState lifecycle = KompileProjectLifecycleState.ACTIVE;
    private List<String> tags = new ArrayList<>();
    private KompileProjectRepository repository = new KompileProjectRepository();
    private List<String> modules = new ArrayList<>();
    private List<KompileProjectComponent> components = new ArrayList<>();
    private List<KompileCodingProject> codingProjects = new ArrayList<>();
    private List<KompileProjectModel> models = new ArrayList<>();
    private List<KompileProjectPipeline> pipelines = new ArrayList<>();
    private List<KompileProjectScript> scripts = new ArrayList<>();
    private List<KompileProjectCrawlProfile> crawlProfiles = new ArrayList<>();
    private List<KompileProjectWorkflow> workflows = new ArrayList<>();
    private Map<String, String> metadata = new LinkedHashMap<>();
    private Instant createdAt;
    private Instant updatedAt;

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

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

    public KompileProjectLifecycleState getLifecycle() {
        return lifecycle;
    }

    public void setLifecycle(KompileProjectLifecycleState lifecycle) {
        this.lifecycle = lifecycle == null ? KompileProjectLifecycleState.ACTIVE : lifecycle;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
    }

    public KompileProjectRepository getRepository() {
        return repository;
    }

    public void setRepository(KompileProjectRepository repository) {
        this.repository = repository == null ? new KompileProjectRepository() : repository;
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

    public List<KompileCodingProject> getCodingProjects() {
        return codingProjects;
    }

    public void setCodingProjects(List<KompileCodingProject> codingProjects) {
        this.codingProjects = codingProjects == null ? new ArrayList<>() : new ArrayList<>(codingProjects);
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

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
