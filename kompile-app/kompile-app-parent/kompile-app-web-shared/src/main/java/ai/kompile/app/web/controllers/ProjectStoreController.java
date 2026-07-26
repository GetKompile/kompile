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
package ai.kompile.app.web.controllers;

import ai.kompile.app.project.ProjectBackendService;
import ai.kompile.app.projectstore.ProjectStoreConfig;
import ai.kompile.app.projectstore.ProjectStoreConfigService;
import ai.kompile.app.projectstore.RemoteProjectDto;
import ai.kompile.app.projectstore.RemoteProjectStoreClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Meta multi-project integration with an external Kompile project store. Reads the
 * Kompile-managed store pointer ({@link ProjectStoreConfigService}), lists projects the store
 * advertises, and clones a chosen project into the local workspace.
 */
@RestController
@RequestMapping("/api/project-store")
public class ProjectStoreController {

    private final ProjectStoreConfigService configService;
    private final RemoteProjectStoreClient client;
    private final ProjectBackendService projectService;

    public ProjectStoreController(ProjectStoreConfigService configService,
                                  RemoteProjectStoreClient client,
                                  ProjectBackendService projectService) {
        this.configService = configService;
        this.client = client;
        this.projectService = projectService;
    }

    /** Current store pointer ({@code url}, {@code gitXet}, {@code configured}). */
    @GetMapping("/config")
    public ProjectStoreConfig getConfig() {
        return configService.getConfig();
    }

    /** Set or update the store pointer (persisted to the Kompile config directory). */
    @PutMapping("/config")
    public ProjectStoreConfig setConfig(@RequestBody ProjectStoreConfig config) {
        if (config == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "config body is required");
        }
        return configService.update(config);
    }

    /** List every project the configured store advertises. */
    @GetMapping("/projects")
    public List<RemoteProjectDto> listProjects() {
        String base = requireStoreUrl();
        try {
            return client.listProjects(base);
        } catch (RestClientException | IllegalArgumentException e) {
            throw badGateway(base, e);
        }
    }

    /** Fetch one project's details (including its manifest) from the store. */
    @GetMapping("/projects/{namespace}/{slug}")
    public RemoteProjectDto getProject(@PathVariable String namespace, @PathVariable String slug) {
        String base = requireStoreUrl();
        RemoteProjectDto project;
        try {
            project = client.getProject(base, namespace, slug);
        } catch (RestClientException | IllegalArgumentException e) {
            throw badGateway(base, e);
        }
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Project not found in store: " + namespace + "/" + slug);
        }
        return project;
    }

    /** Pull in (clone) a store project into the local workspace. */
    @PostMapping("/clone")
    public Map<String, Object> clone(@RequestBody CloneRequest request) {
        if (request == null || isBlank(request.namespace) || isBlank(request.slug)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "namespace and slug are required");
        }
        String base = requireStoreUrl();
        RemoteProjectDto project;
        try {
            project = client.getProject(base, request.namespace, request.slug);
        } catch (RestClientException | IllegalArgumentException e) {
            throw badGateway(base, e);
        }
        if (project == null || isBlank(project.cloneUrl)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Project not found or has no clone URL: " + request.namespace + "/" + request.slug);
        }
        boolean gitXet = configService.getConfig().isGitXet();
        try {
            Path cloned = projectService.cloneFromStore(
                    project.cloneUrl, project.slug, project.defaultBranch, gitXet, request.targetPath);
            return Map.of(
                    "cloned", true,
                    "fullName", project.fullName != null ? project.fullName : project.slug,
                    "path", cloned.toString(),
                    "cloneUrl", project.cloneUrl);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Clone failed: " + e.getMessage(), e);
        }
    }

    private String requireStoreUrl() {
        return configService.storeUrl().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "No project store URL configured. Set one via PUT /api/project-store/config."));
    }

    private static ResponseStatusException badGateway(String base, Exception e) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Failed to reach project store at " + base + ": " + e.getMessage(), e);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Request body for {@code POST /api/project-store/clone}. */
    public static class CloneRequest {
        public String namespace;
        public String slug;
        /** Optional explicit destination path; defaults to a sibling of the current project. */
        public String targetPath;
    }
}
