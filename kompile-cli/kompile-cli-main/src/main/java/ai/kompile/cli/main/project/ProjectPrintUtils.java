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
package ai.kompile.cli.main.project;

import ai.kompile.project.KompileCodingProject;
import ai.kompile.project.KompileProjectComponent;
import ai.kompile.project.KompileProjectCrawlProfile;
import ai.kompile.project.KompileProjectLifecycleState;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectModel;
import ai.kompile.project.KompileProjectPipeline;
import ai.kompile.project.KompileProjectScript;
import ai.kompile.project.KompileProjectStatus;
import ai.kompile.project.KompileProjectStore;
import ai.kompile.project.KompileProjectWorkflow;
import ai.kompile.project.KompileProjectWorkflowStep;

import java.nio.file.Files;
import java.nio.file.Path;

import static ai.kompile.cli.main.project.ProjectCommandUtils.firstNonBlank;
import static ai.kompile.cli.main.project.ProjectCommandUtils.normalizeEnum;

/**
 * Package-private static display/print helpers shared across the project command classes.
 */
final class ProjectPrintUtils {

    private ProjectPrintUtils() {
        // utility class
    }

    static void printManifest(KompileProjectManifest manifest, KompileProjectStatus status) {
        System.out.println("Project: " + manifest.getName());
        System.out.println("  ID: " + manifest.getProjectId());
        System.out.println("  Lifecycle: " + manifest.getLifecycle());
        System.out.println("  Tags: " + (manifest.getTags().isEmpty() ? "-" : String.join(", ", manifest.getTags())));
        System.out.println("  Root: " + status.getRoot());
        System.out.println("  Metadata: " + status.getMetadataPath()
                + (status.isMetadataPresent() ? "" : " (missing)"));
        System.out.println("  Open: " + status.isOpen()
                + (status.getOpenedAt() == null ? "" : " openedAt=" + status.getOpenedAt()));
        System.out.println("  Backend: " + manifest.getRepository().getBackend()
                + (manifest.getRepository().isGitXetEnabled() ? " (Git Xet models)" : ""));
        System.out.println("  Branch: " + firstNonBlank(status.getBranch(), manifest.getRepository().getBranch(), "-"));
        System.out.println("  Remote: " + firstNonBlank(status.getRemoteUrl(), manifest.getRepository().getRemoteUrl(), "-"));
        System.out.println("  Git dirty: " + status.isGitDirty());
        System.out.println("  Git Xet available: " + status.isGitXetAvailable());
        printComponents(manifest);
        printCodingProjects(manifest);
        printModels(manifest);
        printPipelines(manifest);
        printScripts(manifest);
        printCrawlProfiles(manifest);
        printWorkflows(manifest);
    }

    static void printComponents(KompileProjectManifest manifest) {
        System.out.println("  Components (" + manifest.getComponents().size() + "):");
        for (KompileProjectComponent component : manifest.getComponents()) {
            System.out.println("    " + component.getId()
                    + " [" + component.getType() + ", " + component.getStorageBackend() + "] "
                    + component.getPath()
                    + (component.getTags().isEmpty() ? "" : " tags=" + String.join(",", component.getTags())));
        }
    }

    static void printCodingProjects(KompileProjectManifest manifest) {
        System.out.println("  Coding projects (" + manifest.getCodingProjects().size() + "):");
        for (KompileCodingProject project : manifest.getCodingProjects()) {
            System.out.println("    " + project.getId()
                    + " [index=" + project.getCodeProjectId() + "] "
                    + project.getRootPath()
                    + " context=" + project.getContextPath()
                    + " metadata=" + project.getMetadataPath()
                    + " indexes=" + project.getIndexPath()
                    + (project.isAutoIndex() ? " autoIndex" : "")
                    + (project.getTags().isEmpty() ? "" : " tags=" + String.join(",", project.getTags())));
        }
    }

    static void printModels(KompileProjectManifest manifest) {
        System.out.println("  Models (" + manifest.getModels().size() + "):");
        for (KompileProjectModel model : manifest.getModels()) {
            System.out.println("    " + model.getId()
                    + " [" + model.getRole() + "] "
                    + model.getModelId()
                    + (model.getVersion() == null ? "" : " version=" + model.getVersion())
                    + (model.getRegistryModelId() == null ? "" : " registry=" + model.getRegistryModelId())
                    + (model.isRequired() ? " required" : " optional")
                    + (model.getTags().isEmpty() ? "" : " tags=" + String.join(",", model.getTags())));
        }
    }

    static void printPipelines(KompileProjectManifest manifest) {
        System.out.println("  Pipelines (" + manifest.getPipelines().size() + "):");
        for (KompileProjectPipeline pipeline : manifest.getPipelines()) {
            System.out.println("    " + pipeline.getId()
                    + " [" + pipeline.getRole() + "] "
                    + pipeline.getPipelineId()
                    + (pipeline.getVersion() == null ? "" : " version=" + pipeline.getVersion())
                    + (pipeline.isActive() ? " active" : " inactive")
                    + (pipeline.getModelRefs().isEmpty() ? "" : " models=" + String.join(",", pipeline.getModelRefs()))
                    + (pipeline.getTags().isEmpty() ? "" : " tags=" + String.join(",", pipeline.getTags())));
        }
    }

    static void printScripts(KompileProjectManifest manifest) {
        System.out.println("  Scripts (" + manifest.getScripts().size() + "):");
        for (KompileProjectScript script : manifest.getScripts()) {
            System.out.println("    " + script.getId()
                    + " [" + script.getPhase() + ", " + script.getPlatform() + "] "
                    + firstNonBlank(script.getPath(), script.getCommand(), "-")
                    + (script.getTags().isEmpty() ? "" : " tags=" + String.join(",", script.getTags())));
        }
    }

    static void printCrawlProfiles(KompileProjectManifest manifest) {
        System.out.println("  Crawl profiles (" + manifest.getCrawlProfiles().size() + "):");
        for (KompileProjectCrawlProfile profile : manifest.getCrawlProfiles()) {
            System.out.println("    " + profile.getId()
                    + " [" + (profile.isGraphExtraction() ? "graph" : "vector") + "] "
                    + String.join(", ", profile.getSources())
                    + (profile.getSchemaPresetId() == null ? "" : " schema=" + profile.getSchemaPresetId())
                    + (profile.isWatch() ? " watch=true" : "")
                    + (profile.getTags().isEmpty() ? "" : " tags=" + String.join(",", profile.getTags())));
        }
    }

    static void printWorkflows(KompileProjectManifest manifest) {
        System.out.println("  Workflows (" + manifest.getWorkflows().size() + "):");
        for (KompileProjectWorkflow workflow : manifest.getWorkflows()) {
            System.out.println("    " + workflow.getId()
                    + " [" + workflow.getPhase() + "] "
                    + workflow.getSteps().size() + " step(s)"
                    + (workflow.getTags().isEmpty() ? "" : " tags=" + String.join(",", workflow.getTags())));
            for (KompileProjectWorkflowStep step : workflow.getSteps()) {
                System.out.println("      - " + step.getId()
                        + " " + step.getType()
                        + (step.getRef() == null ? "" : " ref=" + step.getRef())
                        + (step.getCommand() == null ? "" : " command=" + step.getCommand())
                        + (step.getUrl() == null ? "" : " url=" + step.getUrl()));
            }
        }
    }

    static void printServePlan(KompileProjectManifest manifest, Path projectRoot) {
        Path stagingRegistry = projectRoot.resolve("data/models/registry.json");
        Path projectModels = projectRoot.resolve("data/models/project-models.json");
        Path projectPipelines = projectRoot.resolve("data/pipelines/project-pipelines.json");
        System.out.println("Project serve plan:");
        System.out.println("  Model staging registry: " + stagingRegistry + (Files.isRegularFile(stagingRegistry) ? " (present)" : " (not created yet)"));
        System.out.println("  Project model snapshot: " + projectModels);
        System.out.println("  Project pipeline snapshot: " + projectPipelines);
        if (manifest.getModels().isEmpty()) {
            System.out.println("  Models: none registered; services will use app defaults and runtime config.");
        } else {
            System.out.println("  Models:");
            for (KompileProjectModel model : manifest.getModels()) {
                System.out.println("    " + model.getRole() + " -> " + model.getModelId()
                        + (model.getVersion() == null ? "" : "@" + model.getVersion())
                        + (model.getSourceRepository() == null ? "" : " source=" + model.getSourceRepository()));
            }
        }
        if (!manifest.getPipelines().isEmpty()) {
            System.out.println("  Pipelines:");
            for (KompileProjectPipeline pipeline : manifest.getPipelines()) {
                System.out.println("    " + pipeline.getRole() + " -> " + pipeline.getPipelineId()
                        + (pipeline.getVersion() == null ? "" : "@" + pipeline.getVersion()));
            }
        }
    }

    static void printCrawlPlan(KompileProjectStore store, KompileProjectManifest manifest,
                               KompileProjectWorkflow workflow, Path projectRoot) {
        System.out.println("Project crawl plan:");
        System.out.println("  Workflow: " + workflow.getName() + " (" + workflow.getId() + ")");
        for (KompileProjectWorkflowStep step : workflow.getSteps()) {
            if (!"CRAWL".equals(normalizeEnum(step.getType()))) {
                System.out.println("  Step " + step.getId() + ": " + step.getType());
                continue;
            }
            KompileProjectCrawlProfile profile = store.findCrawlProfile(manifest, step.getRef()).orElse(null);
            if (profile == null) {
                System.out.println("  Crawl " + step.getRef() + ": missing profile");
                continue;
            }
            Path outputDir = projectRoot.resolve("data/crawls").resolve(localArtifactId(profile)).normalize();
            System.out.println("  Crawl " + profile.getId() + ": "
                    + (canRunLocalCrawl(profile) ? "local" : "kompile-app")
                    + " output=" + outputDir);
        }
    }

    // Helpers used by printCrawlPlan — duplicated here to avoid circular deps with ProjectCrawlCommand
    private static String localArtifactId(KompileProjectCrawlProfile profile) {
        return localArtifactId(firstNonBlank(profile.getId(), profile.getName(), "crawl"));
    }

    private static String localArtifactId(String value) {
        String id = firstNonBlank(value, "crawl")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return id.isBlank() ? "crawl" : id;
    }

    private static boolean canRunLocalCrawl(KompileProjectCrawlProfile profile) {
        if (profile == null || profile.getSources().isEmpty()) {
            return false;
        }
        if (profile.isMultimodal() || profile.isGraphExtraction()) {
            return false;
        }
        String sourceType = normalizeEnum(profile.getSourceType());
        if ("WEB".equals(sourceType) || "URL".equals(sourceType)) {
            return false;
        }
        for (String source : profile.getSources()) {
            String lower = firstNonBlank(source, "").toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return false;
            }
        }
        return true;
    }
}
