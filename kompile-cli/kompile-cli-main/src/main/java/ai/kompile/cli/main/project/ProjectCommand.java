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

import static ai.kompile.cli.main.project.ProjectCommandUtils.defaultDirectoryName;
import static ai.kompile.cli.main.project.ProjectCommandUtils.firstNonBlank;
import static ai.kompile.cli.main.project.ProjectCommandUtils.jsonArray;
import static ai.kompile.cli.main.project.ProjectCommandUtils.jsonString;
import static ai.kompile.cli.main.project.ProjectCommandUtils.normalizeEnum;
import static ai.kompile.cli.main.project.ProjectCommandUtils.parseBackend;
import static ai.kompile.cli.main.project.ProjectCommandUtils.parseLifecycle;
import static ai.kompile.cli.main.project.ProjectCommandUtils.parseType;
import static ai.kompile.cli.main.project.ProjectCommandUtils.resolveProjectRoot;
import static ai.kompile.cli.main.project.ProjectPrintUtils.printComponents;
import static ai.kompile.cli.main.project.ProjectPrintUtils.printCodingProjects;
import static ai.kompile.cli.main.project.ProjectPrintUtils.printCrawlProfiles;
import static ai.kompile.cli.main.project.ProjectPrintUtils.printManifest;
import static ai.kompile.cli.main.project.ProjectPrintUtils.printScripts;

import ai.kompile.cli.main.Info;
import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import ai.kompile.cli.main.codeindex.LocalCodeIndexer;
import ai.kompile.project.KompileProjectChatSession;
import ai.kompile.project.KompileProjectCrawlProfile;
import ai.kompile.project.KompileProjectCrawlResult;
import ai.kompile.project.KompileProjectComponent;
import ai.kompile.project.KompileProjectIndexedDocument;
import ai.kompile.project.KompileProjectComponentType;
import ai.kompile.project.KompileCodingProject;
import ai.kompile.project.KompileProjectFactSheet;
import ai.kompile.project.KompileProjectGitResult;
import ai.kompile.project.KompileProjectInitRequest;
import ai.kompile.project.KompileProjectLifecycleState;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectMarkdownEntry;
import ai.kompile.project.KompileProjectModel;
import ai.kompile.project.KompileProjectNoteSyncConnection;
import ai.kompile.project.KompileProjectPipeline;
import ai.kompile.project.KompileProjectPromptTemplate;
import ai.kompile.project.KompileProjectScript;
import ai.kompile.project.KompileProjectSourceDocument;
import ai.kompile.project.KompileProjectStorageBackend;
import ai.kompile.project.KompileProjectStore;
import ai.kompile.project.KompileProjectWorkflow;
import ai.kompile.project.KompileProjectWorkflowStep;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Command(name = "project",
        mixinStandardHelpOptions = true,
        description = "Manage a unified Kompile project repository.",
        subcommands = {
                ProjectCommand.Init.class,
                ProjectServiceCommand.class,
                ProjectModelCommand.class,
                ProjectCommand.Clone.class,
                ProjectCommand.ListComponents.class,
                ProjectCommand.AddComponent.class,
                ProjectCommand.ListCodeProjects.class,
                ProjectCommand.AddCodeProject.class,
                ProjectCommand.IndexCodeProject.class,
                ProjectCommand.EnforcerStart.class,
                ProjectCommand.JudgeConfig.class,
                ProjectCommand.ListScripts.class,
                ProjectCommand.AddScript.class,
                ProjectCommand.ListCrawlProfiles.class,
                ProjectCommand.AddCrawlProfile.class,
                ProjectCommand.ListMarkdown.class,
                ProjectCommand.ReadMarkdown.class,
                ProjectCommand.SearchMarkdown.class,
                ProjectCommand.ListCrawlResults.class,
                ProjectCommand.ListSourceDocuments.class,
                ProjectCommand.ListPromptTemplates.class,
                ProjectCommand.ListFactSheets.class,
                ProjectCommand.ListChatSessions.class,
                ProjectCommand.ListNoteSyncConnections.class,
                ProjectCommand.ListIndexedDocuments.class,
                ProjectCrawlCommand.class,
                ProjectCommand.Tag.class,
                ProjectCommand.Lifecycle.class,
                ProjectCommand.Commit.class,
                ProjectCommand.Pull.class,
                ProjectCommand.Push.class
        })
public class ProjectCommand implements Callable<Integer> {

    private final KompileProjectStore store = new KompileProjectStore();

    @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
    private File root;

    @Override
    public Integer call() {
        return new ProjectServiceCommand.Status().run(store, root);
    }

    @Command(name = "init", aliases = "create", mixinStandardHelpOptions = true,
            description = "Create or update kompile.project.json and standard project component directories.")
    public static class Init implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--name", "-n"}, description = "Project name. Defaults to the root directory name.")
        private String name;

        @Option(names = "--description", description = "Project description.")
        private String description;

        @Option(names = "--backend", description = "Repository backend: local, git, git-xet.", defaultValue = "local")
        private String backend;

        @Option(names = "--remote", description = "Git remote URL.")
        private String remoteUrl;

        @Option(names = "--branch", description = "Git branch.", defaultValue = "main")
        private String branch;

        @Option(names = "--tag", split = ",", description = "Project tags. Can be repeated or comma-separated.")
        private List<String> tags = new ArrayList<>();

        @Option(names = "--module", split = ",", description = "Enabled Kompile module IDs. Can be repeated or comma-separated.")
        private List<String> modules = new ArrayList<>();

        @Option(names = "--preset", description = "Project preset to seed: generic, vlm-ocr.")
        private String preset;

        @Option(names = "--source", split = ",",
                description = "Preset source paths. For vlm-ocr, defaults to data/input_documents.")
        private List<String> presetSources = new ArrayList<>();

        @Option(names = "--crawl-id", description = "Preset crawl profile ID. For vlm-ocr, defaults to vlm-ocr-docs.")
        private String presetCrawlId;

        @Option(names = "--collection", description = "Preset vector/knowledge collection. For vlm-ocr, defaults to the crawl ID.")
        private String presetCollection;

        @Option(names = "--vlm-model", description = "Preset VLM model ID. For vlm-ocr, defaults to smoldocling-256m.")
        private String presetVlmModel;

        @Option(names = "--pdf-routing", description = "VLM OCR PDF routing mode: AUTO, FORCE_VLM, FORCE_TEXT, DISABLED.",
                defaultValue = "AUTO")
        private String pdfRoutingMode;

        @Option(names = "--vlm-output-format", description = "VLM output format, such as DOCTAGS or MARKDOWN.",
                defaultValue = "DOCTAGS")
        private String vlmOutputFormat;

        @Option(names = "--ocr-output-format", description = "OCR artifact output format.",
                defaultValue = "MARKDOWN")
        private String ocrOutputFormat;

        @Option(names = "--no-standard-components", description = "Do not seed markdown, models, sources, chats, and prompts.")
        private boolean noStandardComponents;

        @Option(names = "--git", description = "Initialize a Git repository even when backend is local.")
        private boolean initializeGit;

        @Option(names = "--install-git-xet", description = "Run 'git xet install' when backend is git-xet.")
        private boolean installGitXet;

        @Option(names = "--auto-crawl", description = "Generate an auto-ingest workflow that runs crawl + index after services start.")
        private boolean autoCrawl;

        @Option(names = "--detect-sources", description = "Scan the project directory for documents and auto-create a crawl profile.")
        private boolean detectSources;

        @Option(names = "--detect-models", description = "Scan data/models/ for model files and auto-register them in the manifest.")
        private boolean detectModels;

        @Option(names = "--no-auto-detect", description = "Suppress automatic detection of documents, models, and code. Use explicit flags only.")
        private boolean noAutoDetect;

        @Override
        public Integer call() throws IOException {
            KompileProjectStore store = new KompileProjectStore();
            Path rootPath = root.toPath().toAbsolutePath().normalize();

            // Auto-install git-xet if git-xet backend is selected
            KompileProjectStorageBackend parsedBackend = parseBackend(backend);
            if (parsedBackend == KompileProjectStorageBackend.GIT_XET || installGitXet) {
                if (!ai.kompile.cli.common.util.GitRunner.isGitXetAvailable()) {
                    ai.kompile.cli.main.install.InstallGitXet.ensureGitXet();
                }
            }

            KompileProjectInitRequest request = new KompileProjectInitRequest();
            request.setName(name);
            request.setDescription(description);
            request.setBackend(parseBackend(backend));
            request.setRemoteUrl(remoteUrl);
            request.setBranch(branch);
            request.setTags(tags);
            request.setModules(modules);
            request.setIncludeStandardComponents(!noStandardComponents);
            request.setInitializeGit(initializeGit);
            request.setInstallGitXet(installGitXet);

            ProjectScenario scenario = null;
            VlmOcrPresetConfig autoDetectedVlm = null;

            // Auto-detection pipeline (runs by default unless --preset or --no-auto-detect)
            if (preset != null && !preset.isBlank()) {
                // Explicit preset — skip auto-detection, use preset path below
            } else if (noAutoDetect) {
                // Old flag-only behavior for scripted use
                if (detectSources && presetSources.isEmpty()) {
                    List<String> detected = detectDocumentSources(rootPath);
                    if (!detected.isEmpty()) {
                        presetSources = detected;
                        System.out.println("Detected document sources:");
                        detected.forEach(s -> System.out.println("  " + s));
                    }
                }
                if (detectModels) {
                    List<KompileProjectModel> detectedModels = detectModelFiles(rootPath);
                    detectedModels.forEach(m -> request.getModels().add(m));
                    if (!detectedModels.isEmpty()) {
                        System.out.println("Detected model files:");
                        detectedModels.forEach(m -> System.out.println("  " + m.getPath() + " (" + m.getRole() + "/" + m.getMetadata().getOrDefault("registry.framework", "unknown") + ")"));
                    }
                }
            } else {
                // Auto-detection (default)
                DetectedSignals signals = collectSignals(rootPath);
                scenario = classifyScenario(signals);

                if (scenario == ProjectScenario.WIZARD) {
                    scenario = runWizard(rootPath, request, signals);
                    // WIZARD returned means blank or vlm-ocr choice — just proceed
                    // For wizard choices 1/2, applyScenario was already called inside runWizard
                    // Check if VLM was auto-configured (wizard choice 1 with PDFs)
                    if (scenario != ProjectScenario.WIZARD) {
                        // runWizard called applyScenario — check for VLM tags
                        if (request.getTags().contains("vlm")) {
                            // VLM was auto-detected in wizard path; reconstruct the config
                            // to write preset files. The sources came from the wizard user input.
                            List<String> vlmSources = request.getCrawlProfiles().isEmpty()
                                    ? List.of() : request.getCrawlProfiles().get(0).getSources();
                            autoDetectedVlm = new VlmOcrPresetConfig(
                                    "auto-ingest", "auto-ingest", "smoldocling-256m",
                                    "AUTO", "DOCTAGS", "MARKDOWN", vlmSources);
                        }
                    }
                } else {
                    autoDetectedVlm = applyScenario(scenario, signals, request, rootPath);
                    printDetectionSummary(scenario, signals);
                }

                // Explicit flags as additive overrides
                if (detectSources && presetSources.isEmpty()) {
                    List<String> extraDocs = detectDocumentSources(rootPath);
                    if (!extraDocs.isEmpty()) presetSources = extraDocs;
                }
                if (detectModels) {
                    List<KompileProjectModel> extraModels = detectModelFiles(rootPath);
                    extraModels.forEach(m -> request.getModels().add(m));
                }
            }

            VlmOcrPresetConfig vlmOcrPreset = applyInitPreset(request, preset, presetSources, presetCrawlId,
                    presetCollection, presetVlmModel, pdfRoutingMode, vlmOutputFormat, ocrOutputFormat);
            // Merge: auto-detected VLM takes effect if no explicit preset was given
            if (vlmOcrPreset == null) {
                vlmOcrPreset = autoDetectedVlm;
            }

            // If --auto-crawl explicitly set and no crawl profile yet, create one
            if (autoCrawl && request.getCrawlProfiles().isEmpty() && !presetSources.isEmpty()) {
                request.getCrawlProfiles().add(buildAutoIngestProfile(presetSources));
            }

            KompileProjectManifest manifest = store.init(rootPath, request);
            if (vlmOcrPreset != null) {
                writeVlmOcrPresetFiles(rootPath, vlmOcrPreset);
            }
            printManifest(manifest, store.status(rootPath));
            if (vlmOcrPreset != null) {
                printVlmOcrPresetSummary(vlmOcrPreset);
            }

            // Print next-steps guidance
            boolean hasCrawl = !manifest.getCrawlProfiles().isEmpty();
            boolean hasModels = !manifest.getModels().isEmpty();
            if (hasCrawl || hasModels) {
                System.out.println("\nNext step: kompile project open --root " + rootPath);
                if (hasCrawl) {
                    System.out.println("  Services will start and you'll be prompted to crawl your documents.");
                }
            }
            return 0;
        }

        /**
         * Scan the project directory for subdirectories containing documents (PDFs, Office, text).
         * Returns absolute paths of directories that contain at least one supported file.
         */
        private static List<String> detectDocumentSources(Path projectRoot) {
            List<String> sources = new ArrayList<>();
            // Check common document directories first
            String[] candidates = {"data/input_documents", "documents", "docs", "pdfs", "files", "sources"};
            for (String candidate : candidates) {
                Path dir = projectRoot.resolve(candidate);
                if (Files.isDirectory(dir) && containsDocuments(dir)) {
                    sources.add(dir.toString());
                }
            }
            // If no standard dirs found, check immediate subdirectories
            if (sources.isEmpty()) {
                try (Stream<Path> children = Files.list(projectRoot)) {
                    children.filter(Files::isDirectory)
                            .filter(p -> !p.getFileName().toString().startsWith("."))
                            .filter(p -> !p.getFileName().toString().equals("target"))
                            .filter(p -> !p.getFileName().toString().equals("node_modules"))
                            .filter(p -> !p.getFileName().toString().equals("scripts"))
                            .filter(Init::containsDocuments)
                            .forEach(p -> sources.add(p.toString()));
                } catch (IOException ignored) {}
            }
            return sources;
        }

        private static boolean containsDocuments(Path dir) {
            try (Stream<Path> files = Files.walk(dir, 2)) {
                return files.filter(Files::isRegularFile)
                        .anyMatch(p -> isDocumentFile(p.getFileName().toString()));
            } catch (IOException e) {
                return false;
            }
        }

        private static boolean isDocumentFile(String name) {
            String lower = name.toLowerCase();
            return lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")
                    || lower.endsWith(".xls") || lower.endsWith(".xlsx")
                    || lower.endsWith(".ppt") || lower.endsWith(".pptx")
                    || lower.endsWith(".odt") || lower.endsWith(".ods") || lower.endsWith(".odp")
                    || lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".rst")
                    || lower.endsWith(".html") || lower.endsWith(".htm")
                    || lower.endsWith(".csv") || lower.endsWith(".json") || lower.endsWith(".jsonl")
                    || lower.endsWith(".xml") || lower.endsWith(".yaml") || lower.endsWith(".yml")
                    || lower.endsWith(".rtf") || lower.endsWith(".epub")
                    || lower.endsWith(".eml") || lower.endsWith(".msg")
                    || lower.endsWith(".mbox") || lower.endsWith(".mbx")
                    || lower.endsWith(".pst") || lower.endsWith(".ost") || lower.endsWith(".emlx");
        }

        /**
         * Check if a file is a known ML model format.
         */
        private static boolean isModelFile(String name) {
            String lower = name.toLowerCase();
            return lower.endsWith(".onnx") || lower.endsWith(".safetensors")
                    || lower.endsWith(".bin") || lower.endsWith(".pt") || lower.endsWith(".pth")
                    || lower.endsWith(".gguf") || lower.endsWith(".fb")
                    || lower.endsWith(".ggml") || lower.endsWith(".sdz");
        }

        /**
         * Infer model role and framework from file extension.
         * Returns a string array: [role, framework, modelType].
         */
        private static String[] inferModelType(String fileName) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".gguf") || lower.endsWith(".ggml")) {
                return new String[]{"LLM", "ggml", "llm"};
            } else if (lower.endsWith(".onnx")) {
                return new String[]{"ENCODER", "onnx", "dense"};
            } else if (lower.endsWith(".fb") || lower.endsWith(".sdz")) {
                return new String[]{"ENCODER", "samediff", "dense"};
            } else if (lower.endsWith(".safetensors") || lower.endsWith(".bin")
                    || lower.endsWith(".pt") || lower.endsWith(".pth")) {
                // Generic weight files — could be LLM, embedding, or VLM
                // Default to generic MODEL role; user can refine later
                return new String[]{"MODEL", "pytorch", "unknown"};
            }
            return new String[]{"MODEL", "unknown", "unknown"};
        }

        /**
         * Scan the project's data/models/ directory (and optionally the root) for model files.
         * Returns a list of KompileProjectModel entries ready for registration.
         */
        private static List<KompileProjectModel> detectModelFiles(Path projectRoot) {
            List<KompileProjectModel> models = new ArrayList<>();
            // Check data/models/ first, then root-level model dirs
            String[] candidates = {"data/models", "models"};
            for (String candidate : candidates) {
                Path dir = projectRoot.resolve(candidate);
                if (!Files.isDirectory(dir)) continue;
                try (Stream<Path> files = Files.walk(dir, 4)) {
                    files.filter(Files::isRegularFile)
                            .filter(p -> isModelFile(p.getFileName().toString()))
                            .forEach(p -> {
                                String fileName = p.getFileName().toString();
                                String[] typeInfo = inferModelType(fileName);
                                String relativePath = projectRoot.relativize(p.getParent()).toString();

                                // Use parent dir name + file stem as model ID
                                String stem = fileName.contains(".")
                                        ? fileName.substring(0, fileName.lastIndexOf('.'))
                                        : fileName;
                                String parentName = p.getParent().getFileName().toString();
                                String modelId = parentName.equals("models") ? stem : parentName + "-" + stem;

                                KompileProjectModel model = new KompileProjectModel();
                                model.setId(modelId);
                                model.setModelId(modelId);
                                model.setRole(typeInfo[0]);
                                model.setPath(relativePath);
                                model.setSource("LOCAL");
                                model.setRequired(true);
                                model.getMetadata().put("registry.framework", typeInfo[1]);
                                model.getMetadata().put("registry.modelType", typeInfo[2]);
                                model.getMetadata().put("registry.modelFile", fileName);
                                models.add(model);
                            });
                } catch (IOException e) {
                    System.err.println("Warning: could not scan " + dir + ": " + e.getMessage());
                }
                if (!models.isEmpty()) break; // prefer data/models/ over root models/
            }
            return models;
        }

        // ==================== Auto-Detection System ====================

        private enum ProjectScenario {
            DATA_ONLY, MODELS_ONLY, MODELS_AND_DATA,
            CODE_ONLY, CODE_AND_DATA, CODE_AND_MODELS, ALL,
            WIZARD
        }

        private record CodeProjectSignal(Path root, String buildFile, String language) {}

        private record DetectedSignals(
                List<String> docDirs,
                List<KompileProjectModel> models,
                CodeProjectSignal codeProject,
                boolean hasRichDocuments
        ) {}

        private static final String[] BUILD_FILES = {
                "pom.xml", "build.gradle", "build.gradle.kts", "package.json",
                "Cargo.toml", "go.mod", "pyproject.toml", "setup.py",
                "Makefile", "tsconfig.json", "Gemfile"
        };

        private static final Map<String, String> LANGUAGE_MAP = Map.ofEntries(
                Map.entry("pom.xml", "Java"), Map.entry("build.gradle", "Java"),
                Map.entry("build.gradle.kts", "Kotlin"), Map.entry("package.json", "JavaScript"),
                Map.entry("Cargo.toml", "Rust"), Map.entry("go.mod", "Go"),
                Map.entry("pyproject.toml", "Python"), Map.entry("setup.py", "Python"),
                Map.entry("Makefile", "C/C++"), Map.entry("tsconfig.json", "TypeScript"),
                Map.entry("Gemfile", "Ruby")
        );

        private static final Set<String> CODE_DETECT_SKIP_DIRS = Set.of(
                "data", "docs", "documents", "node_modules", "target", "build",
                ".git", ".kompile", ".claude", ".idea", ".vscode", "dist", "out"
        );

        /**
         * Collect all detection signals from the project directory.
         */
        private static DetectedSignals collectSignals(Path rootPath) {
            List<String> docDirs = detectDocumentSources(rootPath);
            List<KompileProjectModel> modelFiles = detectModelFiles(rootPath);
            CodeProjectSignal codeProject = detectCodeProject(rootPath);
            boolean hasRichDocs = docDirs.stream().anyMatch(Init::containsRichDocuments);
            return new DetectedSignals(docDirs, modelFiles, codeProject, hasRichDocs);
        }

        /**
         * Check if a directory contains rich documents that benefit from
         * the multi-route pipeline: PDFs (may be scanned), images, Office,
         * HTML, or email files.  Plain text/markdown/csv alone return false.
         */
        private static boolean containsRichDocuments(String dirPath) {
            try (Stream<Path> files = Files.walk(Path.of(dirPath), 2)) {
                return files.filter(Files::isRegularFile).anyMatch(p -> {
                    String lower = p.getFileName().toString().toLowerCase();
                    // PDFs and images — may need VLM OCR
                    if (lower.endsWith(".pdf") || lower.endsWith(".png")
                            || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                            || lower.endsWith(".tiff") || lower.endsWith(".tif")
                            || lower.endsWith(".bmp")) return true;
                    // Office docs — need Office loader, route through standard-text
                    if (lower.endsWith(".doc") || lower.endsWith(".docx")
                            || lower.endsWith(".xls") || lower.endsWith(".xlsx")
                            || lower.endsWith(".xlsm") || lower.endsWith(".ods")
                            || lower.endsWith(".ppt") || lower.endsWith(".pptx")
                            || lower.endsWith(".odt") || lower.endsWith(".odp")
                            || lower.endsWith(".rtf")) return true;
                    // HTML — needs web/HTML loader
                    if (lower.endsWith(".html") || lower.endsWith(".htm")
                            || lower.endsWith(".xhtml")) return true;
                    // Email files — need mail/inbox loader
                    if (lower.endsWith(".eml") || lower.endsWith(".msg")
                            || lower.endsWith(".mbox") || lower.endsWith(".mbx")
                            || lower.endsWith(".pst") || lower.endsWith(".ost")
                            || lower.endsWith(".emlx")) return true;
                    return false;
                });
            } catch (IOException e) {
                return false;
            }
        }

        /**
         * Detect if the directory contains a code project by checking for build files.
         */
        private static CodeProjectSignal detectCodeProject(Path projectRoot) {
            // Check root for build files
            for (String buildFile : BUILD_FILES) {
                if (Files.isRegularFile(projectRoot.resolve(buildFile))) {
                    return new CodeProjectSignal(projectRoot, buildFile,
                            LANGUAGE_MAP.getOrDefault(buildFile, "Unknown"));
                }
            }
            // Check immediate subdirectories (skip known non-code dirs)
            try (Stream<Path> children = Files.list(projectRoot)) {
                List<Path> subdirs = children
                        .filter(Files::isDirectory)
                        .filter(p -> !CODE_DETECT_SKIP_DIRS.contains(p.getFileName().toString()))
                        .filter(p -> !p.getFileName().toString().startsWith("."))
                        .collect(Collectors.toList());
                for (Path subdir : subdirs) {
                    for (String buildFile : BUILD_FILES) {
                        if (Files.isRegularFile(subdir.resolve(buildFile))) {
                            return new CodeProjectSignal(subdir, buildFile,
                                    LANGUAGE_MAP.getOrDefault(buildFile, "Unknown"));
                        }
                    }
                }
            } catch (IOException ignored) {}
            return null;
        }

        /**
         * Classify the detection scenario from the collected signals.
         */
        private static ProjectScenario classifyScenario(DetectedSignals signals) {
            boolean hasCode = signals.codeProject() != null;
            boolean hasDocs = !signals.docDirs().isEmpty();
            boolean hasModels = !signals.models().isEmpty();

            if (hasCode && hasDocs && hasModels) return ProjectScenario.ALL;
            if (hasCode && hasDocs) return ProjectScenario.CODE_AND_DATA;
            if (hasCode && hasModels) return ProjectScenario.CODE_AND_MODELS;
            if (hasCode) return ProjectScenario.CODE_ONLY;
            if (hasDocs && hasModels) return ProjectScenario.MODELS_AND_DATA;
            if (hasDocs) return ProjectScenario.DATA_ONLY;
            if (hasModels) return ProjectScenario.MODELS_ONLY;
            return ProjectScenario.WIZARD;
        }

        /**
         * Apply scenario-specific configuration to the init request.
         * Returns a VlmOcrPresetConfig if VLM PDF auto-detection triggered, null otherwise.
         */
        private static VlmOcrPresetConfig applyScenario(ProjectScenario scenario, DetectedSignals signals,
                                           KompileProjectInitRequest request, Path rootPath) {
            boolean hasModels = !signals.models().isEmpty();
            boolean hasDocs = !signals.docDirs().isEmpty();
            boolean hasCode = signals.codeProject() != null;

            // Tags
            List<String> tags = new ArrayList<>(request.getTags());
            tags.add("auto-detected");
            if (hasDocs || scenario == ProjectScenario.DATA_ONLY
                    || scenario == ProjectScenario.CODE_AND_DATA) {
                tags.add("rag");
            }
            if (hasModels) tags.add("models");
            if (hasCode) tags.add("code");
            request.setTags(tags);

            // Models: add detected models, or default encoder if data-only
            if (hasModels) {
                for (KompileProjectModel model : signals.models()) {
                    request.getModels().add(model);
                }
            }
            boolean scenarioHasData = scenario == ProjectScenario.DATA_ONLY
                    || scenario == ProjectScenario.MODELS_AND_DATA
                    || scenario == ProjectScenario.CODE_AND_DATA
                    || scenario == ProjectScenario.ALL;

            VlmOcrPresetConfig autoVlmConfig = null;
            if (scenarioHasData && signals.hasRichDocuments()) {
                // PDFs or images detected — apply VLM OCR preset for proper extraction
                tags.add("vlm");
                tags.add("ocr");
                autoVlmConfig = new VlmOcrPresetConfig(
                        "auto-ingest", "auto-ingest", "smoldocling-256m",
                        "AUTO", "DOCTAGS", "MARKDOWN", signals.docDirs());
                request.getModels().add(vlmOcrModel(autoVlmConfig));
                if (!hasModels) {
                    // Also add a text encoder for vector search
                    request.getModels().add(defaultEncoderModel());
                }
                request.getPipelines().add(vlmOcrPipeline(autoVlmConfig));
                request.getCrawlProfiles().add(vlmOcrCrawlProfile(autoVlmConfig));
                request.getScripts().add(vlmOcrScript());
                request.getWorkflows().add(vlmOcrWorkflow(autoVlmConfig));
                addMissing(request.getModules(), "loader-pdf-extended", "ocr-core",
                        "ocr-models", "ocr-integration",
                        "loader-microsoft", "loader-excel", "loader-web", "loader-tika",
                        "loader-mail", "loader-email-inbox");
            } else if (scenarioHasData) {
                if (!hasModels) {
                    // Text-only data without models — add default encoder
                    request.getModels().add(defaultEncoderModel());
                }
                // Standard text crawl profile
                request.getCrawlProfiles().add(buildAutoIngestProfile(signals.docDirs()));
            }

            // Coding project
            if (hasCode) {
                request.getCodingProjects().add(buildCodingProject(signals.codeProject(), rootPath));
            }
            return autoVlmConfig;
        }

        /**
         * Create a default encoder model entry for bge-base-en-v1.5 (from catalog).
         */
        private static KompileProjectModel defaultEncoderModel() {
            KompileProjectModel model = new KompileProjectModel();
            model.setId("bge-base-en-v1.5");
            model.setModelId("bge-base-en-v1.5");
            model.setRole("ENCODER");
            model.setSource("CATALOG");
            model.setRegistryModelId("bge-base-en-v1.5");
            model.setRequired(true);
            model.setTags(List.of("encoder", "rag", "default", "auto-detected"));
            model.getMetadata().put("registry.framework", "onnx");
            model.getMetadata().put("registry.modelType", "dense");
            model.getMetadata().put("staging.requiresDownload", "true");
            model.setCreatedAt(Instant.now());
            model.setUpdatedAt(Instant.now());
            return model;
        }

        /**
         * Build a crawl profile from detected document directories.
         */
        private static KompileProjectCrawlProfile buildAutoIngestProfile(List<String> sources) {
            KompileProjectCrawlProfile profile = new KompileProjectCrawlProfile();
            profile.setId("auto-ingest");
            profile.setName("Auto ingest");
            profile.setSources(sources);
            profile.setSourceType("DIRECTORY");
            profile.setLifecycle(KompileProjectLifecycleState.ACTIVE);
            profile.setCreatedAt(Instant.now());
            profile.setUpdatedAt(Instant.now());
            return profile;
        }

        /**
         * Build a coding project entry from a code detection signal.
         */
        private static KompileCodingProject buildCodingProject(CodeProjectSignal signal, Path projectRoot) {
            String dirName = signal.root().getFileName() != null
                    ? signal.root().getFileName().toString() : "code";
            // If code root == project root, use project dir name; else use subdir name
            String id = signal.root().equals(projectRoot) ? dirName : dirName;

            KompileCodingProject cp = new KompileCodingProject();
            cp.setId(id);
            cp.setCodeProjectId(id);
            cp.setName(signal.language() + " project (" + dirName + ")");
            cp.setRootPath(signal.root().toAbsolutePath().normalize().toString());
            cp.setAutoIndex(true);
            cp.setTags(List.of("code", signal.language().toLowerCase(), "auto-detected"));
            cp.setCreatedAt(Instant.now());
            cp.setUpdatedAt(Instant.now());
            return cp;
        }

        /**
         * Print a summary of what was auto-detected.
         */
        private static void printDetectionSummary(ProjectScenario scenario, DetectedSignals signals) {
            System.out.println("Auto-detected: " + scenario.name().toLowerCase().replace('_', '-'));
            if (!signals.docDirs().isEmpty()) {
                System.out.println("  Documents:");
                signals.docDirs().forEach(d -> System.out.println("    " + d));
                if (signals.hasRichDocuments()) {
                    System.out.println("  Rich documents detected — multi-route pipeline enabled:");
                    System.out.println("    Text PDFs    → PDFBox + Tabula (standard extraction)");
                    System.out.println("    Scanned PDFs → VLM OCR (smoldocling-256m)");
                    System.out.println("    Images       → VLM OCR (smoldocling-256m)");
                    System.out.println("    Office docs  → Office/Excel loader (standard extraction)");
                    System.out.println("    HTML files   → Web loader (standard extraction)");
                    System.out.println("    Email files  → Mail/inbox loader (standard extraction)");
                }
            }
            if (!signals.models().isEmpty()) {
                System.out.println("  Models:");
                signals.models().forEach(m -> System.out.println("    " + m.getPath()
                        + " (" + m.getRole() + "/" + m.getMetadata().getOrDefault("registry.framework", "unknown") + ")"));
            }
            if (signals.codeProject() != null) {
                System.out.println("  Code: " + signals.codeProject().language()
                        + " (" + signals.codeProject().buildFile() + " in " + signals.codeProject().root() + ")");
            }
        }

        /**
         * Interactive wizard for empty/ambiguous directories.
         */
        private static ProjectScenario runWizard(Path rootPath, KompileProjectInitRequest request,
                                                  DetectedSignals signals) {
            Console console = System.console();
            if (console == null) {
                System.out.println("No project files detected (non-interactive mode). Creating blank project.");
                return ProjectScenario.WIZARD;
            }

            System.out.println("\nNo project files detected. What kind of project is this?");
            System.out.println("  [1] RAG / document search (I have documents to index)");
            System.out.println("  [2] Code indexing (I have source code to search)");
            System.out.println("  [3] VLM / OCR pipeline (scanned documents or images)");
            System.out.println("  [4] Blank project (configure later)");
            System.out.print("Choice [1-4]: ");

            String choice = console.readLine();
            if (choice == null) choice = "4";
            choice = choice.trim();

            switch (choice) {
                case "1": {
                    System.out.print("Document path (press Enter for data/input_documents): ");
                    String docPath = console.readLine();
                    if (docPath == null || docPath.isBlank()) docPath = "data/input_documents";
                    Path docDir = rootPath.resolve(docPath);
                    try { Files.createDirectories(docDir); } catch (IOException ignored) {}
                    // Create modified signals with the user-specified doc dir
                    boolean docDirHasPdf = containsRichDocuments(docDir.toString());
                    DetectedSignals wizardSignals = new DetectedSignals(
                            List.of(docDir.toString()), signals.models(), signals.codeProject(), docDirHasPdf);
                    applyScenario(ProjectScenario.DATA_ONLY, wizardSignals, request, rootPath);
                    return ProjectScenario.DATA_ONLY;
                }
                case "2": {
                    System.out.print("Code root (press Enter for current directory): ");
                    String codePath = console.readLine();
                    if (codePath == null || codePath.isBlank()) codePath = ".";
                    Path codeRoot = rootPath.resolve(codePath).toAbsolutePath().normalize();
                    CodeProjectSignal wizardCode = new CodeProjectSignal(codeRoot, "manual", "Unknown");
                    DetectedSignals wizardSignals = new DetectedSignals(
                            signals.docDirs(), signals.models(), wizardCode, false);
                    applyScenario(ProjectScenario.CODE_ONLY, wizardSignals, request, rootPath);
                    return ProjectScenario.CODE_ONLY;
                }
                case "3": {
                    // Delegate to the existing vlm-ocr preset
                    return ProjectScenario.WIZARD; // caller will check and apply preset
                }
                default:
                    return ProjectScenario.WIZARD;
            }
        }
    }

    @Command(name = "clone", mixinStandardHelpOptions = true,
            description = "Clone a Kompile project repository and initialize a manifest if the repository does not already have one.")
    public static class Clone implements Callable<Integer> {
        @Parameters(index = "0", description = "Git remote URL.")
        private String remoteUrl;

        @Parameters(index = "1", arity = "0..1", description = "Target directory. Defaults to the repository name.")
        private File targetDir;

        @Option(names = "--branch", description = "Git branch to clone.", defaultValue = "main")
        private String branch;

        @Option(names = "--git-xet", description = "Mark the cloned project as Git Xet backed and run 'git xet install' if available.")
        private boolean gitXet;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            File target = targetDir != null ? targetDir : new File(defaultDirectoryName(remoteUrl));
            Path cloned = store.cloneRepository(remoteUrl, target.toPath(), branch, gitXet);
            System.out.println("Cloned Kompile project: " + cloned);
            return 0;
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true,
            description = "List project components.")
    public static class ListComponents implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            KompileProjectManifest manifest = store.load(resolveProjectRoot(store, root));
            printComponents(manifest);
            return 0;
        }
    }

    @Command(name = "add-component", mixinStandardHelpOptions = true,
            description = "Register a project component such as markdown, model, source, chat, or artifact storage.")
    public static class AddComponent implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = "--id", description = "Stable component ID. Defaults to a slug of the name.")
        private String id;

        @Option(names = "--type", required = true,
                description = "Component type: markdown, model, source, chat, prompt, pipeline, graph, config, dataset, artifact, module, other.")
        private String type;

        @Option(names = "--name", required = true, description = "Display name.")
        private String name;

        @Option(names = "--path", required = true, description = "Path relative to the project root.")
        private String path;

        @Option(names = "--description", description = "Component description.")
        private String description;

        @Option(names = "--storage", description = "Storage backend: local, git, git-xet, external.", defaultValue = "git")
        private String storage;

        @Option(names = "--tag", split = ",", description = "Component tags. Can be repeated or comma-separated.")
        private List<String> tags = new ArrayList<>();

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectComponent component = new KompileProjectComponent();
            component.setId(id);
            component.setType(parseType(type));
            component.setName(name);
            component.setPath(path);
            component.setDescription(description);
            component.setStorageBackend(parseBackend(storage));
            component.setTags(tags);
            KompileProjectManifest manifest = store.addComponent(projectRoot, component);
            printComponents(manifest);
            return 0;
        }
    }

    @Command(name = "code-list", mixinStandardHelpOptions = true,
            description = "List managed external coding projects and their Kompile context paths.")
    public static class ListCodeProjects implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            KompileProjectManifest manifest = store.load(resolveProjectRoot(store, root));
            printCodingProjects(manifest);
            return 0;
        }
    }

    @Command(name = "code-add", mixinStandardHelpOptions = true,
            description = "Register an external coding project without copying its source into the Kompile project repository.")
    public static class AddCodeProject implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = "--id", description = "Kompile coding project ID. Defaults to a slug of the source directory name.")
        private String id;

        @Option(names = "--codeProjectId", description = "Code indexer project ID. Defaults to --id.")
        private String codeProjectId;

        @Option(names = "--name", description = "Display name. Defaults to the source directory name.")
        private String name;

        @Option(names = "--path", required = true, description = "External source root path. Source files stay at this path.")
        private File sourcePath;

        @Option(names = "--description", description = "Coding project description.")
        private String description;

        @Option(names = "--include", description = "Code index include patterns, comma-separated.")
        private String includePatterns;

        @Option(names = "--exclude", description = "Code index exclude patterns, comma-separated.")
        private String excludePatterns;

        @Option(names = "--auto-index", description = "Mark the project for auto-indexing.")
        private boolean autoIndex;

        @Option(names = "--tag", split = ",", description = "Coding project tags. Can be repeated or comma-separated.")
        private List<String> tags = new ArrayList<>();

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileCodingProject codingProject = new KompileCodingProject();
            codingProject.setId(id);
            codingProject.setCodeProjectId(codeProjectId);
            codingProject.setName(name);
            codingProject.setRootPath(sourcePath.toPath().toAbsolutePath().normalize().toString());
            codingProject.setDescription(description);
            codingProject.setIncludePatterns(includePatterns);
            codingProject.setExcludePatterns(excludePatterns);
            codingProject.setAutoIndex(autoIndex);
            codingProject.setTags(tags);
            KompileProjectManifest manifest = store.registerCodingProject(projectRoot, codingProject);
            printCodingProjects(manifest);
            if (autoIndex) {
                KompileCodingProject registered = findCodingProjectOrThrow(manifest, codingProject.getId());
                return runCodeIndex(projectRoot, registered, false, false);
            }
            return 0;
        }
    }

    @Command(name = "code-index", mixinStandardHelpOptions = true,
            description = "Build or refresh project-owned index metadata for managed external coding projects.")
    public static class IndexCodeProject implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--id", "--project"}, description = "Coding project ID, code index project ID, or name. Defaults to auto-index projects.")
        private String projectId;

        @Option(names = "--all", description = "Index all managed coding projects.")
        private boolean all;

        @Option(names = {"--force", "-f"}, description = "Force full re-index through the local code indexer.")
        private boolean force;

        @Option(names = "--dry-run", description = "Print selected code index operations without writing artifacts.")
        private boolean dryRun;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectManifest manifest = store.load(projectRoot);
            List<KompileCodingProject> selected = selectCodingProjects(manifest, projectId, all);
            if (selected.isEmpty()) {
                System.err.println("No coding projects selected. Add one with: kompile project code-add --path <repo> --auto-index");
                return 1;
            }
            int exitCode = 0;
            for (KompileCodingProject codingProject : selected) {
                int result = runCodeIndex(projectRoot, codingProject, force, dryRun);
                if (result != 0) {
                    exitCode = result;
                }
            }
            return exitCode;
        }
    }

    @Command(name = "enforcer-start", mixinStandardHelpOptions = true,
            description = "Start a judge+enforcer session scoped to a coding project folder.")
    public static class EnforcerStart implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--id", "--project"}, required = true,
                description = "Coding project ID, code index project ID, or name.")
        private String projectId;

        @Option(names = {"--agent", "-a"}, description = "Subordinate agent to enforce.", defaultValue = "claude")
        private String agent;

        @Option(names = "--rules", description = "Inline enforcer rules (overrides config).")
        private String rules;

        @Option(names = "--rule-file", description = "Path to enforcer rules file (overrides config).")
        private String ruleFile;

        @Option(names = "--max-corrections", description = "Maximum correction attempts.", defaultValue = "-1")
        private int maxCorrections;

        @Option(names = "--keyword-mode", description = "Use keyword evaluator instead of LLM judge.")
        private Boolean keywordMode;

        @Option(names = "--skip-permissions", description = "Skip agent permission prompts.", defaultValue = "true")
        private boolean skipPermissions;

        @Option(names = "--inject-tools", description = "Inject kompile MCP tools.", defaultValue = "true")
        private boolean injectTools;

        @Option(names = "--url", description = "Kompile-app base URL for server registration.", defaultValue = "")
        private String kompileUrl;

        @Option(names = "--print", description = "Single-shot prompt to enforce and print.")
        private String printPrompt;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectManifest manifest = store.load(projectRoot);
            KompileCodingProject codingProject = findCodingProjectOrThrow(manifest, projectId);

            Path codeRoot = Path.of(codingProject.getRootPath()).toAbsolutePath().normalize();
            if (!codeRoot.toFile().isDirectory()) {
                System.err.println("Coding project root does not exist: " + codeRoot);
                return 1;
            }

            // Load per-coding-project config, fall back to project-level
            EnforcerConfig config = EnforcerConfig.loadForCodeProject(projectRoot, codingProject.getId());

            // Build the enforcer command args, merging config with CLI overrides
            List<String> args = new ArrayList<>();
            args.add("enforcer");
            args.add("--agent");
            args.add(config != null && agent.equals("claude") && config.getAgent() != null
                    ? config.getAgent() : agent);
            args.add("--working-dir");
            args.add(codeRoot.toString());
            args.add("--skip-permissions");
            args.add(String.valueOf(skipPermissions));
            args.add("--inject-tools");
            args.add(String.valueOf(injectTools));

            // Rules: CLI overrides > config
            if (rules != null && !rules.isBlank()) {
                args.add("--rules");
                args.add(rules);
            } else if (ruleFile != null && !ruleFile.isBlank()) {
                args.add("--rule-file");
                args.add(ruleFile);
            } else if (config != null) {
                try {
                    String configRules = config.buildRulesText(codeRoot);
                    if (configRules != null && !configRules.isBlank()) {
                        args.add("--rules");
                        args.add(configRules);
                    }
                } catch (IOException e) {
                    System.err.println("Warning: could not load rules from config: " + e.getMessage());
                }
            }

            int mc = maxCorrections >= 0 ? maxCorrections
                    : (config != null ? config.getMaxCorrections() : 2);
            args.add("--max-corrections");
            args.add(String.valueOf(mc));

            boolean kwMode = keywordMode != null ? keywordMode
                    : (config != null && config.isKeywordMode());
            if (kwMode) {
                args.add("--keyword-mode");
            }

            if (config != null && config.getJudgeMode() != null) {
                args.add("--judge-mode");
                args.add(config.getJudgeMode());
            }
            if (config != null && config.getJudgeProvider() != null) {
                args.add("--judge-provider");
                args.add(config.getJudgeProvider());
            }
            if (config != null && config.getJudgeModel() != null) {
                args.add("--judge-model");
                args.add(config.getJudgeModel());
            }
            if (config != null && config.getJudgeBaseUrl() != null && !config.getJudgeBaseUrl().isBlank()) {
                args.add("--judge-base-url");
                args.add(config.getJudgeBaseUrl());
            }
            if (config != null && config.getJudgeApiKey() != null && !config.getJudgeApiKey().isBlank()) {
                args.add("--judge-api-key");
                args.add(config.getJudgeApiKey());
            }

            if (kompileUrl != null && !kompileUrl.isBlank()) {
                args.add("--url");
                args.add(kompileUrl);
            } else if (config != null && config.getKompileUrl() != null && !config.getKompileUrl().isBlank()) {
                args.add("--url");
                args.add(config.getKompileUrl());
            }

            if (printPrompt != null && !printPrompt.isBlank()) {
                args.add("--print");
                args.add(printPrompt);
            }

            System.out.println("Starting enforcer session for coding project: " + codingProject.getId());
            System.out.println("  Name:      " + (codingProject.getName() != null ? codingProject.getName() : codingProject.getId()));
            System.out.println("  Root:      " + codeRoot);
            System.out.println("  Agent:     " + (config != null ? config.getAgent() : agent));
            System.out.println("  Config:    " + (EnforcerConfig.existsForCodeProject(projectRoot, codingProject.getId())
                    ? "per-project" : (EnforcerConfig.exists(projectRoot) ? "project-level" : "defaults")));
            System.out.println();

            // Delegate to the enforcer command by invoking it through picocli
            picocli.CommandLine enforcerCmd = new picocli.CommandLine(
                    new ai.kompile.cli.main.chat.EnforcerCommand());
            return enforcerCmd.execute(args.toArray(new String[0]));
        }
    }

    @Command(name = "judge-config", mixinStandardHelpOptions = true,
            description = "View and manage judge/enforcer metadata for a coding project folder.")
    public static class JudgeConfig implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--id", "--project"}, required = true,
                description = "Coding project ID, code index project ID, or name.")
        private String projectId;

        @Option(names = "--action", description = "Action: status, init, set, get, add-keyword, remove-keyword, "
                + "add-tool-ban, remove-tool-ban, delete.", defaultValue = "status")
        private String action;

        @Option(names = "--field", description = "Config field to set (for 'set' action).")
        private String field;

        @Option(names = "--value", description = "Value to set (for 'set' action).")
        private String value;

        @Option(names = "--keyword", description = "Keyword or tool name (for add/remove actions).")
        private String keyword;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectManifest manifest = store.load(projectRoot);
            KompileCodingProject codingProject = findCodingProjectOrThrow(manifest, projectId);
            String cpId = codingProject.getId();

            try {
                return switch (action) {
                    case "status" -> judgeStatus(projectRoot, codingProject);
                    case "init" -> judgeInit(projectRoot, cpId);
                    case "get" -> judgeGet(projectRoot, cpId);
                    case "set" -> judgeSet(projectRoot, cpId);
                    case "add-keyword" -> judgeAddKeyword(projectRoot, cpId);
                    case "remove-keyword" -> judgeRemoveKeyword(projectRoot, cpId);
                    case "add-tool-ban" -> judgeAddToolBan(projectRoot, cpId);
                    case "remove-tool-ban" -> judgeRemoveToolBan(projectRoot, cpId);
                    case "delete" -> judgeDelete(projectRoot, cpId);
                    default -> {
                        System.err.println("Unknown action: " + action);
                        yield 1;
                    }
                };
            } catch (Exception e) {
                System.err.println("Judge config error: " + e.getMessage());
                return 1;
            }
        }

        private int judgeStatus(Path projectRoot, KompileCodingProject cp) {
            EnforcerConfig config = EnforcerConfig.loadForCodeProject(projectRoot, cp.getId());
            boolean hasOwnConfig = EnforcerConfig.existsForCodeProject(projectRoot, cp.getId());
            System.out.println("Judge config for coding project: " + cp.getId());
            System.out.println("  Name:       " + (cp.getName() != null ? cp.getName() : cp.getId()));
            System.out.println("  Root:       " + cp.getRootPath());
            System.out.println("  Config:     " + (hasOwnConfig ? "per-project ("
                    + EnforcerConfig.resolveCodeProjectConfigPath(projectRoot, cp.getId()) + ")"
                    : (EnforcerConfig.exists(projectRoot) ? "inherited from project" : "none")));
            if (config == null) {
                System.out.println("  No enforcer/judge config found.");
                System.out.println("  Use --action=init to create one.");
                return 0;
            }
            System.out.println("  ────────────────────────────────────");
            System.out.println("  Agent:           " + config.getAgent());
            System.out.println("  Mode:            " + (config.isKeywordMode() ? "keyword" : "LLM judge"));
            System.out.println("  Max corrections: " + config.getMaxCorrections());
            System.out.println("  Judge mode:      " + (config.getJudgeMode() != null ? config.getJudgeMode() : "auto"));
            System.out.println("  Judge provider:  " + (config.getJudgeProvider() != null ? config.getJudgeProvider() : "auto"));
            System.out.println("  Judge model:     " + (config.getJudgeModel() != null ? config.getJudgeModel() : "auto"));
            System.out.println("  Semantic mode:   " + config.getSemanticMode());
            if (!"none".equals(config.getSemanticMode())) {
                System.out.println("  Sim. threshold:  " + config.getSemanticThreshold());
            }
            System.out.println("  Archive diffs:   " + (config.isArchiveDiffs() ? "enabled" : "disabled"));
            System.out.println("  Auto-rollback:   " + (config.isAutoRollbackOnViolation() ? "yes" : "no"));
            if (!config.getBannedKeywords().isEmpty()) {
                System.out.println("  Banned keywords: " + String.join(", ", config.getBannedKeywords()));
            }
            if (!config.getBannedTools().isEmpty()) {
                System.out.println("  Banned tools:    " + String.join(", ", config.getBannedTools()));
            }
            if (!config.getBannedCommands().isEmpty()) {
                System.out.println("  Banned commands: " + String.join(", ", config.getBannedCommands()));
            }
            if (config.getInlineRules() != null && !config.getInlineRules().isBlank()) {
                System.out.println("  Inline rules:    " + config.getInlineRules().split("\n").length + " lines");
            }
            return 0;
        }

        private int judgeInit(Path projectRoot, String cpId) throws IOException {
            if (EnforcerConfig.existsForCodeProject(projectRoot, cpId)) {
                System.err.println("Judge config already exists for coding project: " + cpId);
                System.err.println("  Path: " + EnforcerConfig.resolveCodeProjectConfigPath(projectRoot, cpId));
                System.err.println("  Use --action=get to view or --action=set to modify.");
                return 1;
            }
            EnforcerConfig config = new EnforcerConfig();
            config.setKeywordMode(true);
            config.setSemanticMode("wordnet");
            config.saveForCodeProject(projectRoot, cpId);
            System.out.println("Created judge config for coding project: " + cpId);
            System.out.println("  Path: " + EnforcerConfig.resolveCodeProjectConfigPath(projectRoot, cpId));
            System.out.println("  Defaults: keyword_mode=true, semantic_mode=wordnet, max_corrections=2");
            System.out.println("  Use --action=add-keyword --keyword=<word> to add banned keywords.");
            System.out.println("  Use --action=set --field=judge_model --value=<model> to set judge model.");
            return 0;
        }

        private int judgeGet(Path projectRoot, String cpId) throws Exception {
            EnforcerConfig config = EnforcerConfig.loadForCodeProject(projectRoot, cpId);
            if (config == null) {
                System.err.println("No judge config found for coding project: " + cpId);
                return 1;
            }
            ObjectMapper mapper = JsonUtils.newStandardMapper()
                    .enable(SerializationFeature.INDENT_OUTPUT);
            System.out.println(mapper.writeValueAsString(config));
            return 0;
        }

        private int judgeSet(Path projectRoot, String cpId) throws IOException {
            if (field == null || field.isBlank()) {
                System.err.println("--field is required for 'set' action.");
                System.err.println("Valid fields: agent, keyword_mode, max_corrections, judge_mode, "
                        + "judge_provider, judge_model, judge_api_key, judge_base_url, "
                        + "semantic_mode, semantic_threshold, embedding_url, inline_rules, "
                        + "archive_diffs, auto_rollback, rule_file, primary_language");
                return 1;
            }
            if (value == null) {
                System.err.println("--value is required for 'set' action.");
                return 1;
            }
            EnforcerConfig config = EnforcerConfig.existsForCodeProject(projectRoot, cpId)
                    ? EnforcerConfig.loadForCodeProject(projectRoot, cpId) : new EnforcerConfig();
            if (config == null) config = new EnforcerConfig();

            switch (field) {
                case "agent" -> config.setAgent(value);
                case "keyword_mode" -> config.setKeywordMode(Boolean.parseBoolean(value));
                case "max_corrections" -> config.setMaxCorrections(Integer.parseInt(value));
                case "judge_mode" -> config.setJudgeMode(value);
                case "judge_provider" -> config.setJudgeProvider(value);
                case "judge_model" -> config.setJudgeModel(value);
                case "judge_api_key" -> config.setJudgeApiKey(value);
                case "judge_base_url" -> config.setJudgeBaseUrl(value);
                case "semantic_mode" -> config.setSemanticMode(value);
                case "semantic_threshold" -> config.setSemanticThreshold(Double.parseDouble(value));
                case "embedding_url" -> config.setEmbeddingUrl(value);
                case "inline_rules" -> config.setInlineRules(value.replace("\\n", "\n"));
                case "archive_diffs" -> config.setArchiveDiffs(Boolean.parseBoolean(value));
                case "auto_rollback" -> config.setAutoRollbackOnViolation(Boolean.parseBoolean(value));
                case "rule_file" -> config.setRuleFile(value);
                case "primary_language" -> config.setPrimaryLanguage(value);
                default -> {
                    System.err.println("Unknown field: " + field);
                    return 1;
                }
            }
            config.saveForCodeProject(projectRoot, cpId);
            System.out.println("Set " + field + " = " + value + " for coding project: " + cpId);
            return 0;
        }

        private int judgeAddKeyword(Path projectRoot, String cpId) throws IOException {
            if (keyword == null || keyword.isBlank()) {
                System.err.println("--keyword is required.");
                return 1;
            }
            EnforcerConfig config = EnforcerConfig.existsForCodeProject(projectRoot, cpId)
                    ? EnforcerConfig.loadForCodeProject(projectRoot, cpId) : new EnforcerConfig();
            if (config == null) config = new EnforcerConfig();
            List<String> kws = new ArrayList<>(config.getBannedKeywords());
            if (!kws.contains(keyword)) {
                kws.add(keyword);
                config.setBannedKeywords(kws);
                config.saveForCodeProject(projectRoot, cpId);
                System.out.println("Added banned keyword \"" + keyword + "\" for coding project: " + cpId);
            } else {
                System.out.println("Keyword already banned: \"" + keyword + "\"");
            }
            return 0;
        }

        private int judgeRemoveKeyword(Path projectRoot, String cpId) throws IOException {
            if (keyword == null || keyword.isBlank()) {
                System.err.println("--keyword is required.");
                return 1;
            }
            EnforcerConfig config = EnforcerConfig.loadForCodeProject(projectRoot, cpId);
            if (config == null) {
                System.err.println("No judge config found for coding project: " + cpId);
                return 1;
            }
            List<String> kws = new ArrayList<>(config.getBannedKeywords());
            if (kws.remove(keyword)) {
                config.setBannedKeywords(kws);
                config.saveForCodeProject(projectRoot, cpId);
                System.out.println("Removed banned keyword \"" + keyword + "\" from coding project: " + cpId);
            } else {
                System.err.println("Keyword not found: \"" + keyword + "\"");
                return 1;
            }
            return 0;
        }

        private int judgeAddToolBan(Path projectRoot, String cpId) throws IOException {
            if (keyword == null || keyword.isBlank()) {
                System.err.println("--keyword is required (tool name to ban).");
                return 1;
            }
            EnforcerConfig config = EnforcerConfig.existsForCodeProject(projectRoot, cpId)
                    ? EnforcerConfig.loadForCodeProject(projectRoot, cpId) : new EnforcerConfig();
            if (config == null) config = new EnforcerConfig();
            List<String> tools = new ArrayList<>(config.getBannedTools());
            if (!tools.contains(keyword)) {
                tools.add(keyword);
                config.setBannedTools(tools);
                config.saveForCodeProject(projectRoot, cpId);
                System.out.println("Added banned tool \"" + keyword + "\" for coding project: " + cpId);
            } else {
                System.out.println("Tool already banned: \"" + keyword + "\"");
            }
            return 0;
        }

        private int judgeRemoveToolBan(Path projectRoot, String cpId) throws IOException {
            if (keyword == null || keyword.isBlank()) {
                System.err.println("--keyword is required (tool name to unban).");
                return 1;
            }
            EnforcerConfig config = EnforcerConfig.loadForCodeProject(projectRoot, cpId);
            if (config == null) {
                System.err.println("No judge config found for coding project: " + cpId);
                return 1;
            }
            List<String> tools = new ArrayList<>(config.getBannedTools());
            if (tools.remove(keyword)) {
                config.setBannedTools(tools);
                config.saveForCodeProject(projectRoot, cpId);
                System.out.println("Removed banned tool \"" + keyword + "\" from coding project: " + cpId);
            } else {
                System.err.println("Tool not found: \"" + keyword + "\"");
                return 1;
            }
            return 0;
        }

        private int judgeDelete(Path projectRoot, String cpId) throws IOException {
            if (EnforcerConfig.deleteForCodeProject(projectRoot, cpId)) {
                System.out.println("Deleted judge config for coding project: " + cpId);
                return 0;
            }
            System.err.println("No per-project judge config found for: " + cpId);
            return 1;
        }
    }

    @Command(name = "script-list", mixinStandardHelpOptions = true,
            description = "List managed lifecycle scripts and runbook commands.")
    public static class ListScripts implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            KompileProjectManifest manifest = store.load(resolveProjectRoot(store, root));
            printScripts(manifest);
            return 0;
        }
    }

    @Command(name = "script-add", mixinStandardHelpOptions = true,
            description = "Register a project lifecycle script or runbook command.")
    public static class AddScript implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = "--id", description = "Script ID. Defaults to a slug of the name, path, or command.")
        private String id;

        @Option(names = "--name", required = true, description = "Display name.")
        private String name;

        @Option(names = "--path", description = "Project-relative script path, such as scripts/start-all.sh.")
        private String path;

        @Option(names = "--command", description = "Command to run. Defaults to --path when present.")
        private String command;

        @Option(names = "--workdir", description = "Working directory relative to the project root.", defaultValue = ".")
        private String workingDirectory;

        @Option(names = "--phase", description = "Lifecycle phase: init, start, stop, verify, crawl, analyze, run.", defaultValue = "run")
        private String phase;

        @Option(names = "--description", description = "Script description.")
        private String description;

        @Option(names = "--platform", description = "Target platform: unix, windows, any.", defaultValue = "any")
        private String platform;

        @Option(names = "--generated", description = "Mark this script as generated by Kompile.")
        private boolean generated;

        @Option(names = "--tag", split = ",", description = "Script tags. Can be repeated or comma-separated.")
        private List<String> tags = new ArrayList<>();

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectScript script = new KompileProjectScript();
            script.setId(id);
            script.setName(name);
            script.setPath(path);
            script.setCommand(command);
            script.setWorkingDirectory(workingDirectory);
            script.setPhase(phase);
            script.setDescription(description);
            script.setPlatform(platform);
            script.setGenerated(generated);
            script.setTags(tags);
            KompileProjectManifest manifest = store.registerScript(projectRoot, script);
            printScripts(manifest);
            return 0;
        }
    }

    @Command(name = "crawl-list", mixinStandardHelpOptions = true,
            description = "List managed crawl initialization profiles.")
    public static class ListCrawlProfiles implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            KompileProjectManifest manifest = store.load(resolveProjectRoot(store, root));
            printCrawlProfiles(manifest);
            return 0;
        }
    }

    @Command(name = "crawl-add", mixinStandardHelpOptions = true,
            description = "Register a reusable crawl initialization profile.")
    public static class AddCrawlProfile implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = "--id", description = "Crawl profile ID. Defaults to a slug of the name or first source.")
        private String id;

        @Option(names = "--name", description = "Human-readable crawl job name.")
        private String name;

        @Option(names = "--description", description = "Crawl profile description.")
        private String description;

        @Option(names = "--source", required = true, split = ",",
                description = "Source path, file, or URL. Can be repeated or comma-separated.")
        private List<String> sources = new ArrayList<>();

        @Option(names = {"--depth", "-d"}, defaultValue = "3", description = "Max crawl depth.")
        private int maxDepth;

        @Option(names = {"--max-docs", "-n"}, defaultValue = "0", description = "Max documents, 0 = unlimited.")
        private int maxDocuments;

        @Option(names = "--include", split = ",", description = "Include patterns.")
        private List<String> includePatterns = new ArrayList<>();

        @Option(names = "--exclude", split = ",", description = "Exclude patterns.")
        private List<String> excludePatterns = new ArrayList<>();

        @Option(names = "--content-types", split = ",", description = "Allowed MIME types.")
        private List<String> contentTypes = new ArrayList<>();

        @Option(names = "--chunker", description = "Text chunker.")
        private String chunker;

        @Option(names = "--loader", description = "Document loader.")
        private String loader;

        @Option(names = "--collection", description = "Vector store collection.")
        private String collection;

        @Option(names = "--multimodal", description = "Enable multimodal crawl routing.")
        private boolean multimodal;

        @Option(names = "--vlm-model", description = "VLM model ID.")
        private String vlmModel;

        @Option(names = "--graph", description = "Enable graph extraction.")
        private boolean graphExtraction;

        @Option(names = "--schema-preset", description = "Graph schema preset ID.")
        private String schemaPresetId;

        @Option(names = "--graph-schema-mode", description = "Graph schema mode: NONE, LENIENT, STRICT.")
        private String graphSchemaMode;

        @Option(names = "--graph-model-provider", description = "Graph extraction LLM provider.")
        private String graphModelProvider;

        @Option(names = "--graph-model-name", description = "Graph extraction model name.")
        private String graphModelName;

        @Option(names = "--graph-local", description = "Run graph extraction locally from the CLI.")
        private boolean graphLocal;

        @Option(names = "--graph-auto-start", description = "Auto-start a local model server for graph extraction.")
        private boolean graphAutoStart;

        @Option(names = "--follow-links", description = "Follow links in directory HTML crawls.")
        private boolean followLinks;

        @Option(names = "--include-hidden", description = "Include hidden files and directories.")
        private boolean includeHidden;

        @Option(names = "--type", description = "Force source type: web, directory, file, excel.")
        private String sourceType;

        @Option(names = {"--watch", "-w"}, description = "Watch job progress when the profile is run.")
        private boolean watch;

        @Option(names = "--fact-sheet", description = "Fact sheet name to associate with crawled documents.")
        private String factSheetName;

        @Option(names = "--tag", split = ",", description = "Crawl profile tags. Can be repeated or comma-separated.")
        private List<String> tags = new ArrayList<>();

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectCrawlProfile profile = new KompileProjectCrawlProfile();
            profile.setId(id);
            profile.setName(name);
            profile.setDescription(description);
            profile.setSources(sources);
            profile.setMaxDepth(maxDepth);
            profile.setMaxDocuments(maxDocuments);
            profile.setIncludePatterns(includePatterns);
            profile.setExcludePatterns(excludePatterns);
            profile.setContentTypes(contentTypes);
            profile.setChunker(chunker);
            profile.setLoader(loader);
            profile.setCollection(collection);
            profile.setMultimodal(multimodal);
            profile.setVlmModel(vlmModel);
            profile.setGraphExtraction(graphExtraction);
            profile.setSchemaPresetId(schemaPresetId);
            profile.setGraphSchemaMode(graphSchemaMode);
            profile.setGraphModelProvider(graphModelProvider);
            profile.setGraphModelName(graphModelName);
            profile.setGraphLocal(graphLocal);
            profile.setGraphAutoStart(graphAutoStart);
            profile.setFollowLinks(followLinks);
            profile.setIncludeHidden(includeHidden);
            profile.setSourceType(sourceType);
            profile.setWatch(watch);
            profile.setFactSheetName(factSheetName);
            profile.setTags(tags);
            KompileProjectManifest manifest = store.registerCrawlProfile(projectRoot, profile);
            printCrawlProfiles(manifest);
            return 0;
        }
    }

    @Command(name = "markdown-list", mixinStandardHelpOptions = true,
            description = "List all markdown files in the project's data/markdown/ directory.")
    public static class ListMarkdown implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            List<KompileProjectMarkdownEntry> entries = store.listMarkdown(projectRoot);
            if (entries.isEmpty()) {
                System.out.println("No markdown files found in data/markdown/.");
                return 0;
            }
            System.out.println("Markdown files (" + entries.size() + "):");
            for (KompileProjectMarkdownEntry entry : entries) {
                System.out.println("  " + entry.getPath()
                        + "\t" + firstNonBlank(entry.getTitle(), "-")
                        + (entry.getTags() == null || entry.getTags().isBlank() ? "" : "\ttags=" + entry.getTags()));
            }
            return 0;
        }
    }

    @Command(name = "markdown-read", mixinStandardHelpOptions = true,
            description = "Read and print a markdown file from the project's data/markdown/ directory.")
    public static class ReadMarkdown implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Parameters(index = "0", description = "Relative path within data/markdown/, e.g. notes/intro.md.")
        private String filePath;

        @Option(names = "--with-frontmatter", description = "Include YAML frontmatter in output.")
        private boolean withFrontmatter;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectMarkdownEntry entry = store.readMarkdown(projectRoot, filePath)
                    .orElseThrow(() -> new IllegalArgumentException("Markdown file not found: " + filePath));
            if (withFrontmatter) {
                Path markdownDir = projectRoot.toAbsolutePath().normalize().resolve("data/markdown");
                Path file = markdownDir.resolve(filePath).normalize();
                try {
                    System.out.println(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    System.err.println("Failed to read file: " + e.getMessage());
                    return 1;
                }
            } else {
                System.out.println(entry.getBody());
            }
            return 0;
        }
    }

    @Command(name = "markdown-search", mixinStandardHelpOptions = true,
            description = "Search markdown files in the project's data/markdown/ directory by title, tags, or content.")
    public static class SearchMarkdown implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Parameters(index = "0", description = "Search query (case-insensitive substring match on title, tags, and body).")
        private String query;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            List<KompileProjectMarkdownEntry> results = store.searchMarkdown(projectRoot, query);
            if (results.isEmpty()) {
                System.out.println("No markdown files matched: " + query);
                return 0;
            }
            System.out.println("Search results for '" + query + "' (" + results.size() + "):");
            for (KompileProjectMarkdownEntry entry : results) {
                System.out.println("  " + entry.getPath()
                        + "\t" + firstNonBlank(entry.getTitle(), "-")
                        + (entry.getTags() == null || entry.getTags().isBlank() ? "" : "\ttags=" + entry.getTags()));
            }
            return 0;
        }
    }

    @Command(name = "crawl-result-list", mixinStandardHelpOptions = true,
            description = "List crawl results (completed crawl output artifacts) in the project.")
    public static class ListCrawlResults implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            List<KompileProjectCrawlResult> results = store.listCrawlResults(projectRoot);
            if (results.isEmpty()) {
                System.out.println("No crawl results found.");
                return 0;
            }
            System.out.println("Crawl results (" + results.size() + "):");
            for (KompileProjectCrawlResult r : results) {
                System.out.println("  " + firstNonBlank(r.getProfileId(), "-")
                        + "\t" + firstNonBlank(r.getName(), "-")
                        + "\tstatus=" + firstNonBlank(r.getStatus(), "unknown")
                        + "\tdocs=" + r.getDocumentCount()
                        + "\tmd=" + r.getMarkdownCount()
                        + "\tchunks=" + r.getChunkCount()
                        + (r.getCollection() != null ? "\tcollection=" + r.getCollection() : "")
                        + (r.getFactSheetName() != null ? "\tfactSheet=" + r.getFactSheetName() : ""));
            }
            return 0;
        }
    }

    @Command(name = "source-list", mixinStandardHelpOptions = true,
            description = "List source documents (uploaded/input files) in the project.")
    public static class ListSourceDocuments implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            List<KompileProjectSourceDocument> docs = store.listSourceDocuments(projectRoot);
            if (docs.isEmpty()) {
                System.out.println("No source documents found in data/input_documents/.");
                return 0;
            }
            System.out.println("Source documents (" + docs.size() + "):");
            for (KompileProjectSourceDocument doc : docs) {
                System.out.println("  " + doc.getPath()
                        + "\t" + doc.getFileName()
                        + "\t" + (doc.getContentType() != null ? doc.getContentType() : "unknown")
                        + "\t" + doc.getSizeBytes() + " bytes");
            }
            return 0;
        }
    }

    @Command(name = "prompt-list", mixinStandardHelpOptions = true,
            description = "List prompt templates in the project.")
    public static class ListPromptTemplates implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            List<KompileProjectPromptTemplate> templates = store.listPromptTemplates(projectRoot);
            if (templates.isEmpty()) {
                System.out.println("No prompt templates found in data/prompt-templates/.");
                return 0;
            }
            System.out.println("Prompt templates (" + templates.size() + "):");
            for (KompileProjectPromptTemplate t : templates) {
                System.out.println("  " + firstNonBlank(t.getName(), t.getId())
                        + (t.getDisplayName() != null ? " (" + t.getDisplayName() + ")" : "")
                        + (t.getCategory() != null ? "\tcategory=" + t.getCategory() : "")
                        + (t.isEnabled() ? "" : "\t[disabled]")
                        + (t.isBuiltIn() ? "\t[built-in]" : ""));
            }
            return 0;
        }
    }

    @Command(name = "fact-sheet-list", mixinStandardHelpOptions = true,
            description = "List fact sheets tracked by the project.")
    public static class ListFactSheets implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            List<KompileProjectFactSheet> sheets = store.listFactSheets(projectRoot);
            if (sheets.isEmpty()) {
                System.out.println("No fact sheets found. Start the app and open the project to export catalogs.");
                return 0;
            }
            System.out.println("Fact sheets (" + sheets.size() + "):");
            for (KompileProjectFactSheet fs : sheets) {
                System.out.println("  " + firstNonBlank(fs.getName(), String.valueOf(fs.getId()))
                        + (fs.isActive() ? " [active]" : "")
                        + "\tfacts=" + fs.getFactCount()
                        + (fs.getEmbeddingModel() != null ? "\tembedding=" + fs.getEmbeddingModel() : "")
                        + (fs.isRerankingEnabled() ? "\treranking=" + firstNonBlank(fs.getRerankerType(), "on") : "")
                        + (fs.isEnableGraphBuilding() ? "\tgraph=" + firstNonBlank(fs.getGraphBuilderType(), "on") : ""));
            }
            return 0;
        }
    }

    @Command(name = "chat-list", mixinStandardHelpOptions = true,
            description = "List chat sessions tracked by the project.")
    public static class ListChatSessions implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            List<KompileProjectChatSession> sessions = store.listChatSessions(projectRoot);
            if (sessions.isEmpty()) {
                System.out.println("No chat sessions found. Start the app and open the project to export catalogs.");
                return 0;
            }
            System.out.println("Chat sessions (" + sessions.size() + "):");
            for (KompileProjectChatSession cs : sessions) {
                System.out.println("  " + firstNonBlank(cs.getSessionId(), "-")
                        + "\t" + firstNonBlank(cs.getTitle(), "(untitled)")
                        + "\tmsgs=" + cs.getMessageCount()
                        + (cs.getSource() != null ? "\tsource=" + cs.getSource() : "")
                        + (cs.getFactSheetName() != null ? "\tfactSheet=" + cs.getFactSheetName() : "")
                        + (cs.getCodeProjectId() != null ? "\tcodeProject=" + cs.getCodeProjectId() : ""));
            }
            return 0;
        }
    }

    @Command(name = "note-sync-list", mixinStandardHelpOptions = true,
            description = "List note synchronization connections tracked by the project.")
    public static class ListNoteSyncConnections implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            List<KompileProjectNoteSyncConnection> connections = store.listNoteSyncConnections(projectRoot);
            if (connections.isEmpty()) {
                System.out.println("No note sync connections found. Start the app and open the project to export catalogs.");
                return 0;
            }
            System.out.println("Note sync connections (" + connections.size() + "):");
            for (KompileProjectNoteSyncConnection nc : connections) {
                System.out.println("  " + firstNonBlank(String.valueOf(nc.getId()), "-")
                        + "\t" + firstNonBlank(nc.getProvider(), "unknown")
                        + "\t" + firstNonBlank(nc.getDirection(), "-")
                        + (nc.isEnabled() ? "" : "\t[disabled]")
                        + (nc.getFactSheetName() != null ? "\tfactSheet=" + nc.getFactSheetName() : "")
                        + (nc.getLastSyncStatus() != null ? "\tstatus=" + nc.getLastSyncStatus() : ""));
            }
            return 0;
        }
    }

    @Command(name = "indexed-doc-list", mixinStandardHelpOptions = true,
            description = "List managed source documents (indexed documents tracked across keyword, vector, and graph indexes).")
    public static class ListIndexedDocuments implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            List<KompileProjectIndexedDocument> docs = store.listIndexedDocuments(projectRoot);
            if (docs.isEmpty()) {
                System.out.println("No indexed documents found. Start the app and open the project to export catalogs.");
                return 0;
            }
            System.out.println("Indexed documents (" + docs.size() + "):");
            for (KompileProjectIndexedDocument doc : docs) {
                System.out.println("  " + firstNonBlank(doc.getFileName(), doc.getSourceId())
                        + "\tstatus=" + firstNonBlank(doc.getOverallStatus(), "unknown")
                        + "\tkeyword=" + firstNonBlank(doc.getKeywordIndexStatus(), "-")
                        + "(" + doc.getKeywordPassageCount() + ")"
                        + "\tvector=" + firstNonBlank(doc.getVectorStoreStatus(), "-")
                        + "(" + doc.getVectorPassageCount() + ")"
                        + "\tgraph=" + firstNonBlank(doc.getGraphStatus(), "-")
                        + "(" + doc.getGraphNodeCount() + ")"
                        + (doc.getFactSheetName() != null ? "\tfactSheet=" + doc.getFactSheetName() : ""));
            }
            return 0;
        }
    }

    @Command(name = "tag", mixinStandardHelpOptions = true,
            description = "Add, remove, or replace project or component tags.")
    public static class Tag implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = "--component", description = "Component ID. Omit to update project tags.")
        private String componentId;

        @Option(names = "--set", split = ",", description = "Replace tags with this list.")
        private List<String> setTags = new ArrayList<>();

        @Option(names = "--add", split = ",", description = "Tags to add.")
        private List<String> addTags = new ArrayList<>();

        @Option(names = "--remove", split = ",", description = "Tags to remove.")
        private List<String> removeTags = new ArrayList<>();

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectManifest manifest = store.load(projectRoot);
            List<String> nextTags = new ArrayList<>(setTags);
            if (nextTags.isEmpty()) {
                nextTags.addAll(currentTags(manifest, componentId));
                LinkedHashSet<String> merged = new LinkedHashSet<>(nextTags);
                merged.addAll(addTags);
                merged.removeAll(removeTags);
                nextTags = new ArrayList<>(merged);
            }
            manifest = componentId == null || componentId.isBlank()
                    ? store.setProjectTags(projectRoot, nextTags)
                    : store.setComponentTags(projectRoot, componentId, nextTags);
            printManifest(manifest, store.status(projectRoot));
            return 0;
        }
    }

    @Command(name = "lifecycle", mixinStandardHelpOptions = true,
            description = "Set the project lifecycle state.")
    public static class Lifecycle implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = "--state", required = true, description = "Lifecycle state: draft, active, paused, archived, deprecated.")
        private String state;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectManifest manifest = store.setLifecycle(projectRoot, parseLifecycle(state));
            printManifest(manifest, store.status(projectRoot));
            return 0;
        }
    }

    @Command(name = "commit", mixinStandardHelpOptions = true,
            description = "Commit all project repository changes.")
    public static class Commit implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--message", "-m"}, description = "Commit message.", defaultValue = "Update Kompile project")
        private String message;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            KompileProjectGitResult result = store.gitCommitAll(resolveProjectRoot(store, root), message);
            System.out.println(result.getOutput().trim());
            return result.getExitCode();
        }
    }

    @Command(name = "pull", mixinStandardHelpOptions = true,
            description = "Pull project repository changes from origin.")
    public static class Pull implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            KompileProjectGitResult result = store.gitPull(resolveProjectRoot(store, root));
            System.out.println(result.getOutput().trim());
            return result.getExitCode();
        }
    }

    @Command(name = "push", mixinStandardHelpOptions = true,
            description = "Push project repository changes to origin.")
    public static class Push implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            KompileProjectGitResult result = store.gitPush(resolveProjectRoot(store, root));
            System.out.println(result.getOutput().trim());
            return result.getExitCode();
        }
    }

    private static VlmOcrPresetConfig applyInitPreset(KompileProjectInitRequest request,
                                                      String preset,
                                                      List<String> sources,
                                                      String crawlId,
                                                      String collection,
                                                      String vlmModel,
                                                      String pdfRoutingMode,
                                                      String vlmOutputFormat,
                                                      String ocrOutputFormat) {
        String presetId = normalizePresetId(preset);
        if (presetId == null || "generic".equals(presetId)) {
            return null;
        }
        if (!"vlm-ocr".equals(presetId)) {
            throw new IllegalArgumentException("Unsupported project preset: " + preset
                    + ". Supported presets: generic, vlm-ocr");
        }

        String resolvedCrawlId = ProjectCrawlCommand.localArtifactId(firstNonBlank(crawlId, "vlm-ocr-docs"));
        String resolvedCollection = firstNonBlank(collection, resolvedCrawlId);
        String resolvedModel = firstNonBlank(vlmModel, "smoldocling-256m");
        String resolvedPdfRouting = normalizeEnum(firstNonBlank(pdfRoutingMode, "AUTO"));
        String resolvedVlmOutput = normalizeEnum(firstNonBlank(vlmOutputFormat, "DOCTAGS"));
        String resolvedOcrOutput = normalizeEnum(firstNonBlank(ocrOutputFormat, "MARKDOWN"));
        List<String> resolvedSources = sources == null || sources.isEmpty()
                ? List.of("data/input_documents")
                : new ArrayList<>(sources);
        VlmOcrPresetConfig config = new VlmOcrPresetConfig(resolvedCrawlId, resolvedCollection, resolvedModel,
                resolvedPdfRouting, resolvedVlmOutput, resolvedOcrOutput, resolvedSources);

        addMissing(request.getTags(), "vlm", "ocr", "knowledge", "pipeline");
        addMissing(request.getModules(), "loader-pdf-extended", "ocr-core", "ocr-models", "ocr-postprocess",
                "ocr-integration", "ocr-datapipeline", "model-staging", "pipeline-management", "crawler-core");
        request.getComponents().add(vlmOcrComponent("ocr-output", KompileProjectComponentType.ARTIFACT,
                "OCR output", "data/ocr",
                "Project-owned OCR text, DocTags, page images, and derived markdown artifacts.",
                List.of("ocr", "vlm", "artifacts")));
        request.getModels().add(vlmOcrModel(config));
        request.getPipelines().add(vlmOcrPipeline(config));
        request.getCrawlProfiles().add(vlmOcrCrawlProfile(config));
        request.getScripts().add(vlmOcrScript());
        request.getWorkflows().add(vlmOcrWorkflow(config));
        return config;
    }

    private static String normalizePresetId(String preset) {
        String value = firstNonBlank(preset);
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static void addMissing(List<String> values, String... additions) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(values == null ? List.<String>of() : values);
        for (String addition : additions) {
            if (addition != null && !addition.isBlank()) {
                merged.add(addition);
            }
        }
        values.clear();
        values.addAll(merged);
    }

    private static KompileProjectComponent vlmOcrComponent(String id, KompileProjectComponentType type, String name,
                                                           String path, String description, List<String> tags) {
        KompileProjectComponent component = new KompileProjectComponent();
        component.setId(id);
        component.setType(type);
        component.setName(name);
        component.setPath(path);
        component.setDescription(description);
        component.setStorageBackend(KompileProjectStorageBackend.GIT);
        component.setTags(tags);
        return component;
    }

    private static KompileProjectModel vlmOcrModel(VlmOcrPresetConfig config) {
        KompileProjectModel model = new KompileProjectModel();
        model.setId(config.vlmModelId());
        model.setModelId(config.vlmModelId());
        model.setRole("VLM");
        model.setSource("huggingface");
        model.setSourceRepository(defaultVlmRepository(config.vlmModelId()));
        model.setRegistryModelId(config.vlmModelId());
        model.setPath("data/models/vlm-pipelines/" + ProjectCrawlCommand.localArtifactId(config.vlmModelId()) + "/pipeline.json");
        model.setRequired(true);
        model.setTags(List.of("vlm", "ocr", "staging", "autoconfig"));
        model.setMetadata(metadata(
                "registry.type", "vlm_pipeline",
                "registry.backend", "vlm",
                "registry.modelFile", "pipeline.json",
                "vlm.outputFormat", config.vlmOutputFormat(),
                "ocr.outputFormat", config.ocrOutputFormat(),
                "pdf.routingMode", config.pdfRoutingMode(),
                "staging.requiresDownload", "true"));
        return model;
    }

    private static String defaultVlmRepository(String modelId) {
        return "smoldocling-256m".equals(ProjectCrawlCommand.localArtifactId(modelId))
                ? "ds4sd/SmolDocling-256M-preview"
                : null;
    }

    private static KompileProjectPipeline vlmOcrPipeline(VlmOcrPresetConfig config) {
        KompileProjectPipeline pipeline = new KompileProjectPipeline();
        pipeline.setId("vlm-ocr-pdf");
        pipeline.setPipelineId("vlm-ocr-pdf");
        pipeline.setName("VLM OCR PDF and image extraction");
        pipeline.setRole("VLM_OCR");
        pipeline.setVersion("1.0.0");
        pipeline.setDefinitionPath("data/pipelines/vlm-ocr-pipeline.json");
        pipeline.setModelRefs(List.of(config.vlmModelId()));
        pipeline.setTags(List.of("vlm", "ocr", "pdf", "image", "markdown"));
        pipeline.setMetadata(metadata(
                "routeConfigPath", "data/pipelines/vlm-ocr-routing.json",
                "promptPath", "data/prompt-templates/vlm_ocr_extract.json",
                "pdf.routingMode", config.pdfRoutingMode(),
                "vlm.outputFormat", config.vlmOutputFormat(),
                "ocr.outputFormat", config.ocrOutputFormat(),
                "markdownPath", "data/markdown/" + config.crawlId(),
                "ocrPath", "data/ocr/" + config.crawlId()));
        return pipeline;
    }

    private static KompileProjectCrawlProfile vlmOcrCrawlProfile(VlmOcrPresetConfig config) {
        KompileProjectCrawlProfile profile = new KompileProjectCrawlProfile();
        profile.setId(config.crawlId());
        profile.setName("Multi-route document ingest");
        profile.setDescription("Multi-route pipeline: text PDFs→PDFBox+Tabula, scanned PDFs→VLM, "
                + "images→VLM, Office→Office loader, HTML→web loader, email→mail loader.");
        profile.setSources(config.sources());
        profile.setSourceType("file");
        // Include all rich document types — each routes to the appropriate pipeline
        profile.setIncludePatterns(List.of(
                // PDFs and images — classified per-page for text vs VLM routing
                "*.pdf", "*.png", "*.jpg", "*.jpeg", "*.tiff", "*.tif", "*.bmp",
                // Office documents — Office/Excel loader → standard-text pipeline
                "*.doc", "*.docx", "*.xls", "*.xlsx", "*.xlsm", "*.ods",
                "*.ppt", "*.pptx", "*.odt", "*.odp", "*.rtf",
                // HTML — web loader → standard-text pipeline
                "*.html", "*.htm", "*.xhtml",
                // Email files — mail/inbox loader → standard-text pipeline
                "*.eml", "*.msg", "*.mbox", "*.mbx", "*.pst", "*.ost", "*.emlx"));
        profile.setContentTypes(List.of(
                "application/pdf", "image/png", "image/jpeg", "image/tiff", "image/bmp",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/vnd.oasis.opendocument.text",
                "application/vnd.oasis.opendocument.spreadsheet",
                "application/vnd.oasis.opendocument.presentation",
                "application/rtf",
                "text/html",
                "message/rfc822",
                "application/vnd.ms-outlook",
                "application/mbox"));
        profile.setCollection(config.collection());
        profile.setMultimodal(true);
        profile.setVlmModel(config.vlmModelId());
        profile.setTags(List.of("multi-route", "vlm", "ocr", "office", "html", "email", "crawl"));
        profile.setMetadata(metadata(
                "pipelineId", "vlm-ocr-pdf",
                "routeConfigPath", "data/pipelines/vlm-ocr-routing.json",
                "pdf.routingMode", config.pdfRoutingMode(),
                "vlm.outputFormat", config.vlmOutputFormat(),
                "ocr.outputFormat", config.ocrOutputFormat(),
                "markdownPath", "data/markdown/" + config.crawlId(),
                "ocrPath", "data/ocr/" + config.crawlId()));
        return profile;
    }

    private static KompileProjectScript vlmOcrScript() {
        KompileProjectScript script = new KompileProjectScript();
        script.setId("run-vlm-ocr");
        script.setName("Run VLM OCR ingest");
        script.setPath("scripts/run-vlm-ocr.sh");
        script.setCommand("./scripts/run-vlm-ocr.sh");
        script.setWorkingDirectory(".");
        script.setPhase("crawl");
        script.setDescription("Run or dry-run the VLM OCR ingest workflow.");
        script.setGenerated(true);
        script.setPlatform("unix");
        script.setTags(List.of("vlm", "ocr", "crawl", "workflow"));
        return script;
    }

    private static KompileProjectWorkflow vlmOcrWorkflow(VlmOcrPresetConfig config) {
        KompileProjectWorkflow workflow = new KompileProjectWorkflow();
        workflow.setId("vlm-ocr-ingest");
        workflow.setName("VLM OCR ingest");
        workflow.setPhase("crawl");
        workflow.setDescription("Run the VLM OCR crawl profile after staging and serving are available.");
        workflow.setTags(List.of("vlm", "ocr", "crawl", "workflow"));
        KompileProjectWorkflowStep step = new KompileProjectWorkflowStep();
        step.setId("crawl-" + config.crawlId());
        step.setName("Crawl " + config.crawlId());
        step.setType("CRAWL");
        step.setRef(config.crawlId());
        step.setTimeoutSeconds(3600);
        step.setMetadata(metadata(
                "pipelineId", "vlm-ocr-pdf",
                "requiresServices", "staging,serving,app",
                "safeToDryRun", "true"));
        workflow.setSteps(List.of(step));
        return workflow;
    }

    private static Map<String, String> metadata(String... pairs) {
        Map<String, String> metadata = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            metadata.put(pairs[i], pairs[i + 1]);
        }
        return metadata;
    }

    private static void writeVlmOcrPresetFiles(Path projectRoot, VlmOcrPresetConfig config) throws IOException {
        Path pipelinesDir = projectRoot.resolve("data/pipelines").normalize();
        Path promptsDir = projectRoot.resolve("data/prompt-templates").normalize();
        Path scriptsDir = projectRoot.resolve("scripts").normalize();
        Path ocrDir = projectRoot.resolve("data/ocr").resolve(config.crawlId()).normalize();
        Files.createDirectories(pipelinesDir);
        Files.createDirectories(promptsDir);
        Files.createDirectories(scriptsDir);
        Files.createDirectories(ocrDir);
        Files.writeString(pipelinesDir.resolve("vlm-ocr-pipeline.json"), vlmOcrPipelineJson(config), StandardCharsets.UTF_8);
        Files.writeString(pipelinesDir.resolve("vlm-ocr-routing.json"), vlmOcrRoutingJson(config), StandardCharsets.UTF_8);
        Files.writeString(pipelinesDir.resolve("vlm-ocr-runbook.md"), vlmOcrRunbook(config), StandardCharsets.UTF_8);
        Files.writeString(promptsDir.resolve("vlm_ocr_extract.json"), vlmOcrPromptJson(config), StandardCharsets.UTF_8);
        Files.writeString(ocrDir.resolve("README.md"), vlmOcrOutputReadme(config), StandardCharsets.UTF_8);
        Path script = scriptsDir.resolve("run-vlm-ocr.sh");
        Files.writeString(script, vlmOcrScriptText(config), StandardCharsets.UTF_8);
        script.toFile().setExecutable(true, false);
        // Write processing route config — used as global fallback by the backend
        // and documents the multi-route PDF pipeline for the project
        Files.writeString(pipelinesDir.resolve("processing-route-config.json"),
                processingRouteConfigJson(config), StandardCharsets.UTF_8);
    }

    private static String vlmOcrPipelineJson(VlmOcrPresetConfig config) {
        return "{\n"
                + "  \"pipelineId\" : \"vlm-ocr-pdf\",\n"
                + "  \"displayName\" : \"VLM OCR PDF and image extraction\",\n"
                + "  \"pipelineType\" : \"VLM\",\n"
                + "  \"enableVlm\" : true,\n"
                + "  \"enableOcr\" : true,\n"
                + "  \"vlmModel\" : " + jsonString(config.vlmModelId()) + ",\n"
                + "  \"vlmOutputFormat\" : " + jsonString(config.vlmOutputFormat()) + ",\n"
                + "  \"ocrOutputFormat\" : " + jsonString(config.ocrOutputFormat()) + ",\n"
                + "  \"pdfRoutingMode\" : " + jsonString(config.pdfRoutingMode()) + ",\n"
                + "  \"textPipelineId\" : \"standard-text\",\n"
                + "  \"routeConfigPath\" : \"data/pipelines/vlm-ocr-routing.json\",\n"
                + "  \"promptPath\" : \"data/prompt-templates/vlm_ocr_extract.json\",\n"
                + "  \"outputs\" : {\n"
                + "    \"ocrDirectory\" : " + jsonString("data/ocr/" + config.crawlId()) + ",\n"
                + "    \"markdownDirectory\" : " + jsonString("data/markdown/" + config.crawlId()) + "\n"
                + "  }\n"
                + "}\n";
    }

    private static String vlmOcrRoutingJson(VlmOcrPresetConfig config) {
        // Multi-route pipeline definitions:
        //   - standard-text: PDFBox text extraction + Tabula tables (for TEXT_ONLY PDFs, Office, HTML)
        //   - vlm-ocr-pdf: VLM vision model extraction (for IMAGE_BASED / MIXED PDFs and standalone images)
        //
        // The pdfRoutingMode=AUTO in processingRoute controls per-PDF classification:
        //   PdfContentClassifier inspects each PDF's page resources and text density:
        //     TEXT_ONLY   → standard-text pipeline
        //     IMAGE_BASED → vlm-ocr-pdf pipeline
        //     MIXED       → vlm-ocr-pdf for image pages + text extraction for text pages
        //
        // Non-PDF documents are dispatched by the loader chain (supports() first-match):
        //   Office docs  → MicrosoftOfficeLoaderImpl / ExcelLoaderImpl → standard-text
        //   HTML files   → WebHtmlLoaderImpl → standard-text
        //   Other files  → TikaLoaderImpl (catch-all) → standard-text
        return "{\n"
                + "  \"defaultPipelineId\" : \"standard-text\",\n"
                + "  \"pdfRoutingMode\" : " + jsonString(config.pdfRoutingMode()) + ",\n"
                + "  \"pipelines\" : [\n"
                + "    {\n"
                + "      \"pipelineId\" : \"standard-text\",\n"
                + "      \"pipelineType\" : \"STANDARD_TEXT\",\n"
                + "      \"enableVlm\" : false,\n"
                + "      \"description\" : \"Text extraction for text-native PDFs (PDFBox+Tabula), Office docs, HTML, and other text formats.\"\n"
                + "    },\n"
                + "    {\n"
                + "      \"pipelineId\" : \"vlm-ocr-pdf\",\n"
                + "      \"pipelineType\" : \"VLM\",\n"
                + "      \"enableVlm\" : true,\n"
                + "      \"description\" : \"Vision-language model extraction for scanned/image-based PDFs and standalone images.\",\n"
                + "      \"options\" : {\n"
                + "        \"vlmModel\" : " + jsonString(config.vlmModelId()) + ",\n"
                + "        \"outputFormat\" : " + jsonString(config.vlmOutputFormat()) + "\n"
                + "      }\n"
                + "    }\n"
                + "  ],\n"
                + "  \"routeRules\" : [\n"
                + "    {\n"
                + "      \"description\" : \"PDFs classified by PdfContentClassifier (AUTO): TEXT_ONLY→standard-text, IMAGE_BASED/MIXED→vlm-ocr-pdf\",\n"
                + "      \"pipelineId\" : \"vlm-ocr-pdf\",\n"
                + "      \"priority\" : 10,\n"
                + "      \"contentTypes\" : [\"application/pdf\"],\n"
                + "      \"fileExtensions\" : [\".pdf\"],\n"
                + "      \"condition\" : \"pdf_route=vlm OR pdf_route=vlm_mixed\"\n"
                + "    },\n"
                + "    {\n"
                + "      \"description\" : \"Standalone images always go through VLM\",\n"
                + "      \"pipelineId\" : \"vlm-ocr-pdf\",\n"
                + "      \"priority\" : 15,\n"
                + "      \"fileExtensions\" : [\".png\", \".jpg\", \".jpeg\", \".tiff\", \".tif\", \".bmp\"]\n"
                + "    },\n"
                + "    {\n"
                + "      \"description\" : \"Office documents handled by Office/Excel loaders → standard-text\",\n"
                + "      \"pipelineId\" : \"standard-text\",\n"
                + "      \"priority\" : 20,\n"
                + "      \"fileExtensions\" : [\".doc\", \".docx\", \".xls\", \".xlsx\", \".xlsm\", \".ods\", \".ppt\", \".pptx\", \".odt\", \".odp\", \".rtf\"]\n"
                + "    },\n"
                + "    {\n"
                + "      \"description\" : \"HTML files handled by web loader → standard-text\",\n"
                + "      \"pipelineId\" : \"standard-text\",\n"
                + "      \"priority\" : 25,\n"
                + "      \"fileExtensions\" : [\".html\", \".htm\", \".xhtml\"]\n"
                + "    },\n"
                + "    {\n"
                + "      \"description\" : \"Email files handled by mail/inbox loaders → standard-text\",\n"
                + "      \"pipelineId\" : \"standard-text\",\n"
                + "      \"priority\" : 30,\n"
                + "      \"fileExtensions\" : [\".eml\", \".msg\", \".mbox\", \".mbx\", \".pst\", \".ost\", \".emlx\"]\n"
                + "    }\n"
                + "  ]\n"
                + "}\n";
    }

    /**
     * Generate the processing route config JSON that controls per-PDF classification.
     * This is the key config that enables multi-route PDF processing:
     *   - pdfRoutingMode=AUTO: PdfContentClassifier inspects each PDF
     *   - TEXT_ONLY PDFs → standard text extraction (PDFBox + Tabula)
     *   - IMAGE_BASED PDFs → VLM pipeline (scanned docs)
     *   - MIXED PDFs → VLM for image pages, text extraction for text pages
     */
    private static String processingRouteConfigJson(VlmOcrPresetConfig config) {
        return "{\n"
                + "  \"pdfRoutingMode\" : " + jsonString(config.pdfRoutingMode()) + ",\n"
                + "  \"vlmModelId\" : " + jsonString(config.vlmModelId()) + ",\n"
                + "  \"extractTablesFromTextPdfs\" : true,\n"
                + "  \"textThresholdCharsPerPage\" : 50,\n"
                + "  \"fallbackEnabled\" : false,\n"
                + "  \"backends\" : [\n"
                + "    {\n"
                + "      \"id\" : \"local-vlm\",\n"
                + "      \"displayName\" : \"Local VLM (" + config.vlmModelId() + ")\",\n"
                + "      \"type\" : \"LOCAL_MODEL\",\n"
                + "      \"priority\" : 1,\n"
                + "      \"maxConcurrent\" : 1,\n"
                + "      \"enabled\" : true,\n"
                + "      \"capabilities\" : [\"vlm\"]\n"
                + "    }\n"
                + "  ]\n"
                + "}\n";
    }

    private static String vlmOcrPromptJson(VlmOcrPresetConfig config) {
        return "{\n"
                + "  \"id\" : \"vlm_ocr_extract\",\n"
                + "  \"name\" : \"VLM OCR extraction\",\n"
                + "  \"model\" : " + jsonString(config.vlmModelId()) + ",\n"
                + "  \"outputFormat\" : " + jsonString(config.vlmOutputFormat()) + ",\n"
                + "  \"template\" : \"Extract readable text, tables, headings, captions, and page structure from this page image. Return DocTags when supported; otherwise return clean markdown. Preserve table cells and reading order.\"\n"
                + "}\n";
    }

    private static String vlmOcrRunbook(VlmOcrPresetConfig config) {
        return "# Multi-Route Document Pipeline\n\n"
                + "This project uses automatic document classification to route each file through the right pipeline.\n\n"
                + "## How Routing Works\n\n"
                + "| Document Type | Detection | Pipeline | Loader/Method |\n"
                + "|---|---|---|---|\n"
                + "| **Text-native PDF** | Extractable text, no image XObjects | `standard-text` | PDFBox + Tabula |\n"
                + "| **Scanned/image PDF** | Image XObjects on all pages, <50 chars/page | `vlm-ocr-pdf` | " + config.vlmModelId() + " |\n"
                + "| **Mixed PDF** | Some text pages, some image pages | `vlm-ocr-pdf` | VLM for image pages, text for text pages |\n"
                + "| **Standalone images** | .png, .jpg, .tiff, .bmp | `vlm-ocr-pdf` | " + config.vlmModelId() + " |\n"
                + "| **Word docs** | .doc, .docx | `standard-text` | MicrosoftOfficeLoader |\n"
                + "| **Excel sheets** | .xls, .xlsx, .xlsm, .ods | `standard-text` | ExcelLoader (tables, formulas, charts) |\n"
                + "| **PowerPoint** | .ppt, .pptx | `standard-text` | MicrosoftOfficeLoader |\n"
                + "| **OpenDocument** | .odt, .odp | `standard-text` | MicrosoftOfficeLoader |\n"
                + "| **RTF** | .rtf | `standard-text` | MicrosoftOfficeLoader |\n"
                + "| **HTML** | .html, .htm, .xhtml | `standard-text` | WebHtmlLoader |\n"
                + "| **Email (single)** | .eml, .msg | `standard-text` | MailLoader (mime4j/JavaMail) |\n"
                + "| **Email (archive)** | .mbox, .mbx, .pst, .ost, .emlx | `standard-text` | EmailInboxLoader |\n"
                + "| **Other files** | Any remaining format | `standard-text` | TikaLoader (catch-all) |\n\n"
                + "PDFs are classified per-page by `PdfContentClassifier`.\n"
                + "The scanned threshold is 50 chars/page (configurable in `processing-route-config.json`).\n\n"
                + "## Generated Config\n\n"
                + "- Processing route: `data/pipelines/processing-route-config.json`\n"
                + "- Pipeline routing: `data/pipelines/vlm-ocr-routing.json`\n"
                + "- VLM pipeline def: `data/pipelines/vlm-ocr-pipeline.json`\n"
                + "- Crawl profile: `" + config.crawlId() + "`\n"
                + "- VLM model: `" + config.vlmModelId() + "`\n"
                + "- OCR artifacts: `data/ocr/" + config.crawlId() + "`\n"
                + "- Markdown artifacts: `data/markdown/" + config.crawlId() + "`\n\n"
                + "## Dry Run\n\n"
                + "```bash\n"
                + "kompile project workflow-run --root . --id vlm-ocr-ingest --dry-run\n"
                + "```\n\n"
                + "## Run Later\n\n"
                + "Start the project services first, then run:\n\n"
                + "```bash\n"
                + "./scripts/run-vlm-ocr.sh\n"
                + "```\n";
    }

    private static String vlmOcrOutputReadme(VlmOcrPresetConfig config) {
        return "# OCR Output\n\n"
                + "Generated OCR and VLM text extraction artifacts for crawl profile `" + config.crawlId() + "` are written here.\n\n"
                + "Expected artifact families:\n"
                + "- page images and page metadata\n"
                + "- raw VLM output such as DocTags\n"
                + "- normalized OCR text\n"
                + "- derived markdown synchronized with `data/markdown/" + config.crawlId() + "`\n";
    }

    private static String vlmOcrScriptText(VlmOcrPresetConfig config) {
        return "#!/usr/bin/env bash\n"
                + "set -euo pipefail\n\n"
                + "ROOT=\"${KOMPILE_PROJECT_ROOT:-$(cd \"$(dirname \"${BASH_SOURCE[0]}\")/..\" && pwd)}\"\n"
                + "WORKFLOW_ID=\"${KOMPILE_VLM_OCR_WORKFLOW:-vlm-ocr-ingest}\"\n\n"
                + "KOMPILE_BIN=\"${KOMPILE_BIN:-kompile}\"\n\n"
                + "if [[ \"${1:-}\" == \"--dry-run\" ]]; then\n"
                + "  exec \"$KOMPILE_BIN\" project workflow-run --root \"$ROOT\" --id \"$WORKFLOW_ID\" --dry-run\n"
                + "fi\n\n"
                + "exec \"$KOMPILE_BIN\" project workflow-run --root \"$ROOT\" --id \"$WORKFLOW_ID\" \"$@\"\n";
    }

    private static void printVlmOcrPresetSummary(VlmOcrPresetConfig config) {
        System.out.println("Multi-route document pipeline:");
        System.out.println("  PDF routing: " + config.pdfRoutingMode()
                + " (text→PDFBox+Tabula, scanned→VLM, mixed→split)");
        System.out.println("  Office docs: Word/Excel/PowerPoint/ODF → Office loader");
        System.out.println("  HTML files:  .html/.htm/.xhtml → Web loader");
        System.out.println("  Email files: .eml/.msg/.mbox/.pst → Mail/inbox loader");
        System.out.println("  VLM model:   " + config.vlmModelId());
        System.out.println("  Scanned threshold: 50 chars/page");
        System.out.println("  Crawl profile: " + config.crawlId());
        System.out.println("  Processing route: data/pipelines/processing-route-config.json");
        System.out.println("  Pipeline routing: data/pipelines/vlm-ocr-routing.json");
        System.out.println("  Dry run: kompile project workflow-run --root . --id vlm-ocr-ingest --dry-run");
    }

    private record VlmOcrPresetConfig(String crawlId,
                                      String collection,
                                      String vlmModelId,
                                      String pdfRoutingMode,
                                      String vlmOutputFormat,
                                      String ocrOutputFormat,
                                      List<String> sources) {
    }

    private static KompileCodingProject findCodingProjectOrThrow(KompileProjectManifest manifest, String projectId) {
        return manifest.getCodingProjects().stream()
                .filter(project -> matchesCodingProject(project, projectId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown coding project: " + projectId));
    }

    private static List<KompileCodingProject> selectCodingProjects(KompileProjectManifest manifest,
                                                                   String projectId,
                                                                   boolean all) {
        if (projectId != null && !projectId.isBlank()) {
            return List.of(findCodingProjectOrThrow(manifest, projectId));
        }
        if (all) {
            return manifest.getCodingProjects();
        }
        List<KompileCodingProject> autoIndex = manifest.getCodingProjects().stream()
                .filter(KompileCodingProject::isAutoIndex)
                .toList();
        if (!autoIndex.isEmpty()) {
            return autoIndex;
        }
        return manifest.getCodingProjects().size() == 1 ? manifest.getCodingProjects() : List.of();
    }

    private static boolean matchesCodingProject(KompileCodingProject project, String projectId) {
        if (project == null || projectId == null || projectId.isBlank()) {
            return false;
        }
        return projectId.equals(project.getId())
                || projectId.equals(project.getCodeProjectId())
                || projectId.equals(project.getName());
    }

    private static int runCodeIndex(Path projectRoot, KompileCodingProject codingProject,
                                    boolean force, boolean dryRun) {
        Path sourceRoot = Path.of(codingProject.getRootPath()).toAbsolutePath().normalize();
        Path indexRoot = codeProjectPath(projectRoot, codingProject.getIndexPath(),
                firstNonBlank(codingProject.getContextPath(), "data/code-projects/" + codingProject.getId()) + "/indexes",
                "Coding project index path");
        Path latest = indexRoot.resolve("latest").normalize();
        Path metadataRoot = codeProjectPath(projectRoot, codingProject.getMetadataPath(),
                firstNonBlank(codingProject.getContextPath(), "data/code-projects/" + codingProject.getId()) + "/metadata",
                "Coding project metadata path");
        List<String> includes = splitCsv(codingProject.getIncludePatterns());
        LinkedHashSet<String> excludeSet = new LinkedHashSet<>(defaultCodeIndexExcludes());
        excludeSet.addAll(splitCsv(codingProject.getExcludePatterns()));
        List<String> excludes = new ArrayList<>(excludeSet);
        String codeIndexerProjectId = firstNonBlank(codingProject.getCodeProjectId(), codingProject.getId());
        String codeIndexerIncludes = codeIndexerPatternString(includes);
        String codeIndexerExcludes = codeIndexerPatternString(excludes);
        Path actualIndexDir = LocalCodeIndexer.getIndexDir(codeIndexerProjectId);

        if (dryRun) {
            System.out.println("kompile code-index " + sourceRoot
                    + " --project " + codeIndexerProjectId
                    + (codeIndexerIncludes == null ? "" : " --include " + codeIndexerIncludes)
                    + (codeIndexerExcludes == null ? "" : " --exclude " + codeIndexerExcludes)
                    + (force ? " --force" : ""));
            System.out.println("  source " + sourceRoot);
            System.out.println("  actual-index " + actualIndexDir);
            System.out.println("  project-run-metadata " + latest);
            System.out.println("  include " + (codeIndexerIncludes == null ? "<all supported languages>" : codeIndexerIncludes));
            System.out.println("  exclude " + (codeIndexerExcludes == null ? "<indexer defaults>" : codeIndexerExcludes));
            return 0;
        }
        try {
            if (!Files.isDirectory(sourceRoot)) {
                System.err.println("Coding project source root does not exist: " + sourceRoot);
                return 1;
            }
            Files.createDirectories(latest);
            Files.createDirectories(metadataRoot);
            LocalCodeIndexer indexer = new LocalCodeIndexer();
            LocalCodeIndexer.IndexResult result = indexer.index(sourceRoot, codeIndexerProjectId,
                    codeIndexerIncludes, codeIndexerExcludes, force, System.out);
            writeCodeIndexRunArtifacts(codingProject, sourceRoot, actualIndexDir, latest, metadataRoot,
                    includes, excludes, codeIndexerIncludes, codeIndexerExcludes, result);
            System.out.println("Code index complete: " + codingProject.getId());
            System.out.println("  Files: " + result.filesProcessed());
            System.out.println("  Entities: " + result.entitiesFound());
            System.out.println("  Actual index: " + actualIndexDir);
            System.out.println("  Run metadata: " + latest);
            return 0;
        } catch (IOException e) {
            System.err.println("Code index failed: " + e.getMessage());
            return 1;
        }
    }

    private static Path codeProjectPath(Path projectRoot, String configured, String fallback, String label) {
        Path path = projectRoot.resolve(firstNonBlank(configured, fallback)).normalize();
        if (!path.startsWith(projectRoot)) {
            throw new IllegalArgumentException(label + " escapes project root: " + path);
        }
        return path;
    }

    private static String codeIndexerPatternString(List<String> patterns) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String pattern : patterns == null ? List.<String>of() : patterns) {
            String value = firstNonBlank(pattern);
            if (value == null) {
                continue;
            }
            value = value.replace('\\', '/');
            while (value.startsWith("**/")) {
                value = value.substring(3);
            }
            int slash = value.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < value.length()) {
                value = value.substring(slash + 1);
            }
            if (value.startsWith("*.")) {
                normalized.add(value);
            } else if (value.startsWith(".")) {
                normalized.add(value);
            } else if (!value.isBlank() && !"**".equals(value) && !"*".equals(value)) {
                normalized.add(value);
            }
        }
        return normalized.isEmpty() ? null : String.join(",", normalized);
    }

    private static void writeCodeIndexRunArtifacts(KompileCodingProject codingProject, Path sourceRoot,
                                                   Path actualIndexDir, Path latest, Path metadataRoot,
                                                   List<String> requestedIncludes, List<String> effectiveExcludes,
                                                   String codeIndexerIncludes, String codeIndexerExcludes,
                                                   LocalCodeIndexer.IndexResult result) throws IOException {
        String summary = actualCodeIndexSummaryJson(codingProject, sourceRoot, actualIndexDir,
                requestedIncludes, effectiveExcludes, codeIndexerIncludes, codeIndexerExcludes, result);
        Files.writeString(latest.resolve("index-summary.json"), summary, StandardCharsets.UTF_8);
        Files.writeString(metadataRoot.resolve("index-state.json"), summary, StandardCharsets.UTF_8);
    }

    private static String actualCodeIndexSummaryJson(KompileCodingProject codingProject, Path sourceRoot,
                                                     Path actualIndexDir,
                                                     List<String> requestedIncludes,
                                                     List<String> effectiveExcludes,
                                                     String codeIndexerIncludes,
                                                     String codeIndexerExcludes,
                                                     LocalCodeIndexer.IndexResult result) {
        StringBuilder languages = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : result.languageCounts().entrySet()) {
            if (!first) {
                languages.append(", ");
            }
            languages.append(jsonString(entry.getKey())).append(" : ").append(entry.getValue());
            first = false;
        }
        languages.append("}");
        return "{\n"
                + "  \"codeProjectId\" : " + jsonString(result.projectId()) + ",\n"
                + "  \"projectId\" : " + jsonString(codingProject.getId()) + ",\n"
                + "  \"name\" : " + jsonString(codingProject.getName()) + ",\n"
                + "  \"status\" : " + jsonString(result.errors() == 0 ? "COMPLETED" : "COMPLETED_WITH_ERRORS") + ",\n"
                + "  \"indexedAt\" : " + jsonString(Instant.now().toString()) + ",\n"
                + "  \"sourceRoot\" : " + jsonString(sourceRoot.toString()) + ",\n"
                + "  \"actualIndexer\" : \"LocalCodeIndexer\",\n"
                + "  \"actualIndexPath\" : " + jsonString(actualIndexDir.toString()) + ",\n"
                + "  \"requestedIncludePatterns\" : " + jsonArray(requestedIncludes) + ",\n"
                + "  \"effectiveExcludePatterns\" : " + jsonArray(effectiveExcludes) + ",\n"
                + "  \"codeIndexerIncludePatterns\" : " + jsonString(codeIndexerIncludes) + ",\n"
                + "  \"codeIndexerExcludePatterns\" : " + jsonString(codeIndexerExcludes) + ",\n"
                + "  \"filesProcessed\" : " + result.filesProcessed() + ",\n"
                + "  \"filesSkipped\" : " + result.filesSkipped() + ",\n"
                + "  \"filesDeleted\" : " + result.filesDeleted() + ",\n"
                + "  \"entitiesFound\" : " + result.entitiesFound() + ",\n"
                + "  \"errors\" : " + result.errors() + ",\n"
                + "  \"languages\" : " + languages + "\n"
                + "}\n";
    }

    private static List<String> defaultCodeIndexExcludes() {
        return List.of(".git", ".svn", ".hg", ".kompile", ".claude", ".codex", ".gemini",
                ".opencode", ".cursor", ".idea", ".vscode", ".settings", ".gradle",
                "target", "build", "dist", "out", "node_modules", "__pycache__",
                ".tox", ".mypy_cache", ".pytest_cache", ".angular", ".next", ".nuxt",
                "coverage", ".cache", "bin", "obj");
    }

    private static List<String> splitCsv(String value) {
        List<String> values = new ArrayList<>();
        String trimmed = firstNonBlank(value);
        if (trimmed == null) {
            return values;
        }
        for (String part : trimmed.split(",")) {
            String item = firstNonBlank(part);
            if (item != null) {
                values.add(item);
            }
        }
        return values;
    }

    private static List<String> currentTags(KompileProjectManifest manifest, String componentId) {
        if (componentId == null || componentId.isBlank()) {
            return manifest.getTags();
        }
        return manifest.getComponents().stream()
                .filter(component -> componentId.equals(component.getId()))
                .findFirst()
                .map(KompileProjectComponent::getTags)
                .orElseGet(ArrayList::new);
    }

}
