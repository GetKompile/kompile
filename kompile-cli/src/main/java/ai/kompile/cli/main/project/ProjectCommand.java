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

import ai.kompile.cli.common.registry.InstanceInfo;
import ai.kompile.cli.common.registry.InstanceRegistry;
import ai.kompile.cli.main.GlobalBootstrap;
import ai.kompile.cli.main.Info;
import ai.kompile.cli.main.app.CrawlCommand;
import ai.kompile.cli.main.chat.mcp.McpToolInjection;
import ai.kompile.cli.main.chat.enforcer.EnforcerConfig;
import ai.kompile.cli.main.codeindex.LocalCodeIndexer;
import ai.kompile.cli.main.install.registry.ComponentRegistry;
import ai.kompile.cli.main.manage.ServiceManager;
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
import ai.kompile.project.KompileProjectOpenState;
import ai.kompile.project.KompileProjectPipeline;
import ai.kompile.project.KompileProjectPromptTemplate;
import ai.kompile.project.KompileProjectScript;
import ai.kompile.project.KompileProjectSourceDocument;
import ai.kompile.project.KompileProjectStatus;
import ai.kompile.project.KompileProjectStorageBackend;
import ai.kompile.project.KompileProjectStore;
import ai.kompile.project.KompileProjectWorkflow;
import ai.kompile.project.KompileProjectWorkflowStep;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Command(name = "project",
        mixinStandardHelpOptions = true,
        description = "Manage a unified Kompile project repository.",
        subcommands = {
                ProjectCommand.Init.class,
                ProjectCommand.Status.class,
                ProjectCommand.Open.class,
                ProjectCommand.Start.class,
                ProjectCommand.Stop.class,
                ProjectCommand.Clone.class,
                ProjectCommand.ListComponents.class,
                ProjectCommand.AddComponent.class,
                ProjectCommand.ListCodeProjects.class,
                ProjectCommand.AddCodeProject.class,
                ProjectCommand.IndexCodeProject.class,
                ProjectCommand.EnforcerStart.class,
                ProjectCommand.JudgeConfig.class,
                ProjectCommand.ListModels.class,
                ProjectCommand.AddModel.class,
                ProjectCommand.CloneModel.class,
                ProjectCommand.ListPipelines.class,
                ProjectCommand.AddPipeline.class,
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
                ProjectCommand.Serve.class,
                ProjectCommand.Crawl.class,
                ProjectCommand.RunCrawlProfile.class,
                ProjectCommand.ListWorkflows.class,
                ProjectCommand.AddWorkflow.class,
                ProjectCommand.RunWorkflow.class,
                ProjectCommand.Tag.class,
                ProjectCommand.Lifecycle.class,
                ProjectCommand.Commit.class,
                ProjectCommand.Pull.class,
                ProjectCommand.Push.class
        })
public class ProjectCommand implements Callable<Integer> {
    private static final Set<String> LOCAL_KNOWLEDGE_STOP_WORDS = Set.of(
            "the", "and", "for", "that", "with", "this", "from", "are", "was", "were",
            "will", "you", "your", "have", "has", "had", "not", "but", "all", "can",
            "our", "into", "about", "their", "there", "these", "those", "then", "than",
            "also", "over", "under", "using", "use", "used", "per", "via", "its", "it"
    );

    private final KompileProjectStore store = new KompileProjectStore();

    @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
    private File root;

    @Override
    public Integer call() {
        return new Status().run(store, root);
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
                try (java.util.stream.Stream<Path> children = Files.list(projectRoot)) {
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
            try (java.util.stream.Stream<Path> files = Files.walk(dir, 2)) {
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
                try (java.util.stream.Stream<Path> files = Files.walk(dir, 4)) {
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
            try (java.util.stream.Stream<Path> files = Files.walk(Path.of(dirPath), 2)) {
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
            try (java.util.stream.Stream<Path> children = Files.list(projectRoot)) {
                List<Path> subdirs = children
                        .filter(Files::isDirectory)
                        .filter(p -> !CODE_DETECT_SKIP_DIRS.contains(p.getFileName().toString()))
                        .filter(p -> !p.getFileName().toString().startsWith("."))
                        .collect(java.util.stream.Collectors.toList());
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
            model.setCreatedAt(java.time.Instant.now());
            model.setUpdatedAt(java.time.Instant.now());
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
            profile.setCreatedAt(java.time.Instant.now());
            profile.setUpdatedAt(java.time.Instant.now());
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
            cp.setCreatedAt(java.time.Instant.now());
            cp.setUpdatedAt(java.time.Instant.now());
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
            java.io.Console console = System.console();
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

    @Command(name = "status", mixinStandardHelpOptions = true,
            description = "Show project manifest, component, Git, and Git Xet status.")
    public static class Status implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            return run(new KompileProjectStore(), root);
        }

        private Integer run(KompileProjectStore store, File root) {
            Path resolved = resolveProjectRoot(store, root);
            KompileProjectStatus status = store.status(resolved);
            if (!status.isManifestPresent()) {
                System.out.println("No Kompile project manifest found at " + status.getManifestPath());
                System.out.println("Run: kompile project init --name <name>");
                return 1;
            }
            printManifest(store.load(resolved), status);
            return 0;
        }
    }

    @Command(name = "open", mixinStandardHelpOptions = true,
            description = "Open a Kompile project directory and optionally start the web UI.%n%n" +
                    "Uses the pre-installed kompile-app-main JAR from ~/.kompile/components/%n" +
                    "so no Maven build is required. The project's application.properties and%n" +
                    "data/ directories are passed to the running JAR.%n%n" +
                    "Examples:%n" +
                    "  kompile project open .                   # open + start web UI%n" +
                    "  kompile project open /path/to/project    # open a specific directory%n" +
                    "  kompile project open . --no-serve        # metadata only, don't start%n" +
                    "  kompile project open . --port=9090       # custom port%n")
    public static class Open implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--no-serve"},
                description = "Only write open metadata, do not start services.",
                defaultValue = "false")
        private boolean noServe;

        @Option(names = {"--port", "-p"},
                description = "Port for the main web application. Default: 8080",
                defaultValue = "8080")
        private int appPort;

        @Option(names = {"--staging-port"},
                description = "Port for the model staging server. Default: 8090",
                defaultValue = "8090")
        private int stagingPort;

        @Option(names = {"--no-staging"},
                description = "Skip starting the model staging server.",
                defaultValue = "false")
        private boolean noStaging;

        @Option(names = {"--no-open"},
                description = "Do not automatically open the browser.",
                defaultValue = "false")
        private boolean noOpenBrowser;

        @Option(names = {"--crawl"},
                description = "Force-run auto-ingest crawl after services are healthy (skip prompt).",
                defaultValue = "false")
        private boolean crawl;

        @Option(names = {"--no-crawl"},
                description = "Suppress the crawl prompt entirely.",
                defaultValue = "false")
        private boolean noCrawl;

        @Option(names = {"--jvm-args"},
                description = "Additional JVM arguments for the main application (comma-separated).",
                split = ",")
        private List<String> jvmArgs;

        @Override
        public Integer call() throws Exception {
            // 1. Open the project (write metadata)
            KompileProjectStore store = new KompileProjectStore();
            Path resolved = resolveProjectRoot(store, root);
            KompileProjectOpenState state = store.openProject(resolved);
            KompileProjectStatus status = store.status(resolved);
            KompileProjectManifest manifest = store.load(resolved);
            System.out.println("Opened Kompile project: " + state.getName());
            System.out.println("  ID: " + state.getProjectId());
            System.out.println("  Root: " + resolved);
            System.out.println("  Modules: " + manifest.getModules().size());

            if (noServe) {
                System.out.println("  Open state: " + status.getOpenStatePath());
                return 0;
            }

            // 2. Ensure global bootstrap
            GlobalBootstrap.ensureHomeDirectory();
            GlobalBootstrap.ensureConfigs();

            // 3. Find the kompile-app-main JAR from ~/.kompile/components/
            File appJar = findInstalledAppJar();
            if (appJar == null) {
                System.err.println("\nkompile-app-main not installed.");
                System.err.println("Install it with: kompile install kompile-app");
                return 1;
            }

            ServiceManager serviceManager = new ServiceManager();
            File projectDir = resolved.toFile();

            // 4. Check if already running on the target port
            if (serviceManager.checkHealth(appPort)) {
                String url = "http://localhost:" + appPort;
                System.out.println("\n  kompile-app-main is already running at " + url);
                if (!noOpenBrowser) {
                    openBrowserUrl(url);
                }
                return 0;
            }

            System.out.println("  App JAR: " + appJar.getAbsolutePath());
            System.out.println("  App port: " + appPort);

            String projectName = manifest.getName() != null ? manifest.getName() : projectDir.getName();
            String webInstanceName = projectName + "-web";
            String stagingInstanceName = projectName + "-staging";
            File logDir = new File(projectDir, "data/logs");
            logDir.mkdirs();

            // 5. Start staging server (unless --no-staging)
            Process stagingProcess = null;
            if (!noStaging) {
                File stagingJar = findInstalledStagingJar();
                if (stagingJar != null) {
                    if (serviceManager.checkHealth(stagingPort)) {
                        System.out.println("  Staging: already running on port " + stagingPort);
                    } else {
                        System.out.println("  Starting staging server on port " + stagingPort + "...");
                        List<String> stagingArgs = buildStagingArgs(projectDir, appPort);
                        stagingProcess = serviceManager.startProjectComponent(
                                stagingInstanceName, "kompile-model-staging", stagingJar,
                                stagingPort, projectDir, null, stagingArgs, logDir, false);
                        // Brief wait for staging to initialize
                        boolean stagingHealthy = serviceManager.waitForHealth(stagingPort, 60);
                        if (stagingHealthy) {
                            configureStagingCallback(stagingPort, appPort);
                            System.out.println("  Staging: running on port " + stagingPort
                                    + " (PID: " + stagingProcess.pid() + ")");
                        } else {
                            System.out.println("  Staging: started but health check timed out"
                                    + " (PID: " + stagingProcess.pid() + ")");
                        }
                    }
                } else {
                    System.out.println("  Staging: not installed (install with: kompile install kompile-model-staging)");
                }
            }

            // 5b. Auto-register project models with staging
            boolean stagingAvailableForModels = !noStaging && serviceManager.checkHealth(stagingPort);
            if (stagingAvailableForModels && !manifest.getModels().isEmpty()) {
                autoStageProjectModels(manifest.getModels(), stagingPort, projectDir);
            }

            // 5c. Auto-index coding projects
            if (!manifest.getCodingProjects().isEmpty()) {
                autoIndexCodingProjects(manifest.getCodingProjects());
            }

            // 6. Build app arguments — point at project's config and data
            List<String> appArgs = buildProjectAppArgs(projectDir, appPort, stagingPort, stagingProcess != null || serviceManager.checkHealth(stagingPort));

            // 7. Write .mcp.json so CLI MCP tools and external agents point at this project's backend.
            //    - "kompile" entry: stdio CLI MCP server with --url pointing at this backend
            //    - "kompile-app" entry: SSE direct connection to backend
            //    - "kompile-model-staging" entry: SSE to staging (if running)
            String backendUrl = "http://localhost:" + appPort;
            String sseUrl = backendUrl + "/mcp/sse";
            Path mcpJsonFile = McpToolInjection.injectTools(resolved, "claude", null); // stdio entry
            addStdioUrlArg(mcpJsonFile, "kompile", backendUrl);
            addSseEntryToMcpJson(mcpJsonFile, "kompile-app", sseUrl);
            if (!noStaging && serviceManager.checkHealth(stagingPort)) {
                addSseEntryToMcpJson(mcpJsonFile, "kompile-model-staging",
                        "http://localhost:" + stagingPort + "/mcp/sse");
            }
            System.out.println("  MCP config: " + mcpJsonFile);

            // 8. Update open state with runtime service info
            state.getMetadata().put("appPort", String.valueOf(appPort));
            state.getMetadata().put("appUrl", backendUrl);
            state.getMetadata().put("sseUrl", sseUrl);
            state.getMetadata().put("mcpConfigPath", mcpJsonFile != null ? mcpJsonFile.toString() : "");
            if (!noStaging) {
                state.getMetadata().put("stagingPort", String.valueOf(stagingPort));
                state.getMetadata().put("stagingUrl", "http://localhost:" + stagingPort);
            }
            state.getMetadata().put("appJar", appJar.getAbsolutePath());
            state.setUpdatedAt(java.time.Instant.now());
            try {
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules()
                        .writeValue(store.openStatePath(resolved).toFile(), state);
            } catch (Exception e) {
                System.err.println("  Warning: could not update open state: " + e.getMessage());
            }

            // 9. Register shutdown hook
            final Process stagingRef = stagingProcess;
            final Path mcpJsonCleanup = mcpJsonFile;
            final KompileProjectStore storeRef = store;
            final Path resolvedRef = resolved;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down...");
                // Restore or remove .mcp.json
                try {
                    McpToolInjection.removeTools(mcpJsonCleanup);
                } catch (Exception e) {
                    System.err.println("  Warning: could not clean up .mcp.json: " + e.getMessage());
                }
                // Clear runtime metadata from open state
                clearRuntimeMetadata(storeRef, resolvedRef);
                if (stagingRef != null && stagingRef.isAlive()) {
                    stagingRef.destroy();
                    try { stagingRef.waitFor(); } catch (InterruptedException ignored) {}
                    System.out.println("  Staging server stopped.");
                }
                try {
                    InstanceRegistry.unregister(webInstanceName);
                    InstanceRegistry.unregister(stagingInstanceName);
                } catch (Exception ignored) {}
            }));

            // 10. Open browser after app starts
            if (!noOpenBrowser) {
                String url = "http://localhost:" + appPort;
                Thread browserThread = new Thread(() -> {
                    if (waitForAppReady(appPort, 120)) {
                        System.out.println("  Opening browser: " + url);
                        openBrowserUrl(url);
                    }
                });
                browserThread.setDaemon(true);
                browserThread.start();
            }

            // 10b. Crawl prompt — after app-main is healthy, ask user about document ingestion
            if (!noCrawl && !manifest.getCrawlProfiles().isEmpty()) {
                final int crawlAppPort = appPort;
                final KompileProjectManifest crawlManifest = manifest;
                Thread crawlThread = new Thread(() -> {
                    if (!waitForAppReady(crawlAppPort, 120)) return;
                    if (crawl) {
                        // --crawl flag: run immediately without prompting
                        triggerAutoCrawl(crawlAppPort, crawlManifest);
                        return;
                    }
                    // Interactive prompt
                    java.io.Console console = System.console();
                    if (console != null) {
                        System.out.println();
                        System.out.println("  Documents detected in this project.");
                        System.out.println("  Run a crawl to index them for RAG search?");
                        System.out.print("  [Y/n]: ");
                        String answer = console.readLine();
                        if (answer == null || answer.isBlank() || answer.trim().toLowerCase().startsWith("y")) {
                            triggerAutoCrawl(crawlAppPort, crawlManifest);
                        } else {
                            System.out.println("  To crawl later: kompile project workflow-run --id auto-ingest");
                        }
                    } else {
                        // Non-interactive: print manual command
                        System.out.println("\n  Documents detected. To index them: kompile project workflow-run --id auto-ingest");
                    }
                });
                crawlThread.setDaemon(true);
                crawlThread.start();
            }

            // 11. Start main app (foreground, blocks until Ctrl+C)
            System.out.println("\nStarting kompile-app-main on port " + appPort + "...");
            System.out.println("  Press Ctrl+C to stop.\n");

            Process appProcess = serviceManager.startProjectComponent(
                    webInstanceName, "kompile-app-main", appJar, appPort,
                    projectDir, jvmArgs, appArgs, null, true);

            System.out.println("  PID: " + appProcess.pid());

            int exitCode = appProcess.waitFor();

            // 12. Clean up
            McpToolInjection.removeTools(mcpJsonFile);
            clearRuntimeMetadata(store, resolved);
            InstanceRegistry.unregister(webInstanceName);
            if (stagingProcess != null) {
                InstanceRegistry.unregister(stagingInstanceName);
                if (stagingProcess.isAlive()) {
                    stagingProcess.destroy();
                }
            }

            return exitCode;
        }

        /**
         * Build application arguments that point the pre-installed JAR at this project's
         * configuration and data directories.
         * <p>
         * The key property is {@code kompile.data.dir} — all config services resolve their
         * JSON config files relative to this directory. Setting it to the project root makes
         * all configs (app-index, pipeline, nd4j-environment, feature-flags, etc.) per-project.
         */
        /**
         * Build staging server arguments with project-scoped directories and callback URL.
         *
         * @param projectDir the project root directory
         * @param appPort    the main app port (for callback URL)
         */
        static List<String> buildStagingArgs(File projectDir, int appPort) {
            List<String> stagingArgs = new ArrayList<>();
            stagingArgs.add("--server");
            File modelsDir = new File(projectDir, "data/models");
            modelsDir.mkdirs();
            stagingArgs.add("--kompile.staging.model-dir=" + modelsDir.getAbsolutePath());
            stagingArgs.add("--kompile.staging.staging-dir=" +
                    new File(modelsDir, ".staging").getAbsolutePath());
            stagingArgs.add("--kompile.staging.settings-dir=" +
                    new File(projectDir, "data").getAbsolutePath());
            stagingArgs.add("--kompile.staging.project-dir=" + projectDir.getAbsolutePath());
            stagingArgs.add("--kompile.staging.callback-url=http://localhost:" + appPort);
            return stagingArgs;
        }

        /**
         * Configure the staging server's callback URL via REST after it's healthy.
         * Non-fatal — staging works without it, just won't auto-notify app of model changes.
         *
         * @param stagingPort the staging server port
         * @param appPort     the main app port (callback target)
         */
        static void configureStagingCallback(int stagingPort, int appPort) {
            try {
                String settingsUrl = "http://localhost:" + stagingPort + "/api/staging/settings";
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();

                // GET current settings
                HttpRequest getReq = HttpRequest.newBuilder()
                        .uri(URI.create(settingsUrl))
                        .GET()
                        .build();
                HttpResponse<String> getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString());
                if (getResp.statusCode() != 200) {
                    System.out.println("  Staging callback: could not read settings (HTTP " + getResp.statusCode() + ")");
                    return;
                }

                // Parse, update callback URL, PUT back
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode settings =
                        (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(getResp.body());
                settings.put("callbackUrl", "http://localhost:" + appPort);
                settings.put("autoReloadEnabled", true);

                HttpRequest putReq = HttpRequest.newBuilder()
                        .uri(URI.create(settingsUrl))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(settings)))
                        .build();
                HttpResponse<String> putResp = client.send(putReq, HttpResponse.BodyHandlers.ofString());
                if (putResp.statusCode() == 200) {
                    System.out.println("  Staging callback: configured → http://localhost:" + appPort);
                } else {
                    System.out.println("  Staging callback: update failed (HTTP " + putResp.statusCode() + ")");
                }
            } catch (Exception e) {
                System.out.println("  Staging callback: could not configure (" + e.getMessage() + ")");
            }
        }

        /**
         * Build application arguments that point the pre-installed JAR at this project's
         * configuration and data directories.
         * <p>
         * The key property is {@code kompile.data.dir} — all config services resolve their
         * JSON config files relative to this directory. Setting it to the project root makes
         * all configs (app-index, pipeline, nd4j-environment, feature-flags, etc.) per-project.
         *
         * @param projectDir       the project root directory
         * @param appPort          the application port (unused currently, reserved for future use)
         * @param stagingPort      the staging server port
         * @param stagingAvailable whether staging is running
         */
        static List<String> buildProjectAppArgs(File projectDir, int appPort, int stagingPort, boolean stagingAvailable) {
            List<String> args = new ArrayList<>();

            // Per-project config: all config services read from <projectDir>/config/
            args.add("--kompile.data.dir=" + projectDir.getAbsolutePath());

            // Point Spring Boot at the project's application.properties
            File propsFile = new File(projectDir, "src/main/resources/application.properties");
            if (propsFile.isFile()) {
                args.add("--spring.config.additional-location=file:" + propsFile.getAbsolutePath());
            }

            // Model and data directories — eagerly create so args are always passed
            File modelsDir = new File(projectDir, "data/models");
            modelsDir.mkdirs();
            args.add("--kompile.staging.model-dir=" + modelsDir.getAbsolutePath());

            // Document sources
            File uploadsDir = new File(projectDir, "data/input_documents/uploads");
            if (uploadsDir.isDirectory()) {
                args.add("--app.document.uploads-path=" + uploadsDir.getAbsolutePath());
            }

            // Shared files root
            File sharedFiles = new File(projectDir, "data/shared_files");
            if (sharedFiles.isDirectory()) {
                args.add("--mcp.filesystem.roots.default.path=" + sharedFiles.getAbsolutePath());
            }

            // MCP config directory (so backend reads project-level mcp-config.json)
            File mcpConfigDir = new File(projectDir, "data");
            args.add("--kompile.mcp.config.path=" + mcpConfigDir.getAbsolutePath());

            // Staging server connection
            if (stagingAvailable) {
                args.add("--kompile.staging.url=http://localhost:" + stagingPort);
                args.add("--kompile.staging.port=" + stagingPort);
            }

            return args;
        }

        /**
         * Add {@code --url <backendUrl>} to a stdio MCP server entry's args in .mcp.json.
         * This tells the CLI stdio MCP server which backend instance to connect to,
         * avoiding the port auto-probe (which would miss non-standard ports).
         */
        private static void addStdioUrlArg(Path mcpJsonFile, String serverName, String backendUrl) {
            if (mcpJsonFile == null || !Files.exists(mcpJsonFile)) return;
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode root =
                        (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(Files.readString(mcpJsonFile));
                com.fasterxml.jackson.databind.JsonNode servers = root.path("mcpServers").path(serverName);
                if (servers.isMissingNode() || !servers.has("args")) return;
                com.fasterxml.jackson.databind.node.ArrayNode args =
                        (com.fasterxml.jackson.databind.node.ArrayNode) servers.get("args");
                args.add("--url");
                args.add(backendUrl);
                Files.writeString(mcpJsonFile, om.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            } catch (Exception e) {
                System.err.println("  Warning: could not add --url to " + serverName + " args: " + e.getMessage());
            }
        }

        /**
         * Add an SSE MCP server entry to an existing .mcp.json file.
         * Used to register the kompile-app backend and staging server alongside the
         * CLI stdio entry that McpToolInjection already wrote.
         */
        private static void addSseEntryToMcpJson(Path mcpJsonFile, String serverName, String sseUrl) {
            if (mcpJsonFile == null || !Files.exists(mcpJsonFile)) return;
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode root =
                        (com.fasterxml.jackson.databind.node.ObjectNode) om.readTree(Files.readString(mcpJsonFile));
                com.fasterxml.jackson.databind.node.ObjectNode mcpServers;
                if (root.has("mcpServers") && root.get("mcpServers").isObject()) {
                    mcpServers = (com.fasterxml.jackson.databind.node.ObjectNode) root.get("mcpServers");
                } else {
                    mcpServers = root.putObject("mcpServers");
                }
                com.fasterxml.jackson.databind.node.ObjectNode entry = mcpServers.putObject(serverName);
                entry.put("type", "sse");
                entry.put("url", sseUrl);
                Files.writeString(mcpJsonFile, om.writerWithDefaultPrettyPrinter().writeValueAsString(root));
            } catch (Exception e) {
                System.err.println("  Warning: could not add " + serverName + " to .mcp.json: " + e.getMessage());
            }
        }

        /**
         * Remove runtime service metadata from the open state file so stale port/URL
         * information isn't left behind after the server shuts down.
         */
        static void clearRuntimeMetadata(KompileProjectStore store, Path projectRoot) {
            try {
                Optional<KompileProjectOpenState> opt = store.readOpenState(projectRoot);
                if (opt.isEmpty()) return;
                KompileProjectOpenState state = opt.get();
                Map<String, String> meta = state.getMetadata();
                if (meta == null) return;
                meta.remove("appPort");
                meta.remove("appUrl");
                meta.remove("sseUrl");
                meta.remove("mcpConfigPath");
                meta.remove("stagingPort");
                meta.remove("stagingUrl");
                meta.remove("appJar");
                state.setUpdatedAt(java.time.Instant.now());
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .findAndRegisterModules()
                        .writeValue(store.openStatePath(projectRoot).toFile(), state);
            } catch (Exception e) {
                System.err.println("  Warning: could not clear runtime metadata: " + e.getMessage());
            }
        }

        /**
         * Find the kompile-app-main JAR from global install locations.
         * Delegates to ComponentRegistry.findInstalledJar() which searches
         * dist, canonical, exec, any-version, and native exe locations.
         */
        static File findInstalledAppJar() {
            return new ComponentRegistry().findInstalledJar(ComponentRegistry.KOMPILE_APP_MAIN);
        }

        /**
         * Find the kompile-model-staging JAR from global install locations.
         * Delegates to ComponentRegistry.findInstalledJar() which searches
         * dist, canonical, exec, any-version, and native exe locations.
         */
        static File findInstalledStagingJar() {
            return new ComponentRegistry().findInstalledJar(ComponentRegistry.KOMPILE_MODEL_STAGING);
        }

        /**
         * Auto-index coding projects that have autoIndex=true.
         * Runs LocalCodeIndexer.index() for each, which is incremental
         * (only re-parses files changed since last run).
         */
        static void autoIndexCodingProjects(List<KompileCodingProject> codingProjects) {
            for (KompileCodingProject cp : codingProjects) {
                if (!cp.isAutoIndex()) continue;
                String projectId = ProjectCommand.firstNonBlank(cp.getCodeProjectId(), cp.getId(), cp.getName());
                String rootPath = cp.getRootPath();
                if (projectId == null || rootPath == null) continue;

                Path root = Path.of(rootPath);
                if (!Files.isDirectory(root)) {
                    System.out.println("  Code index: skipping " + projectId + " (root not found: " + rootPath + ")");
                    continue;
                }

                System.out.println("  Indexing code project: " + projectId + " (" + rootPath + ")");
                try {
                    LocalCodeIndexer indexer = new LocalCodeIndexer();
                    LocalCodeIndexer.IndexResult result = indexer.index(
                            root, projectId, cp.getIncludePatterns(), cp.getExcludePatterns(), System.out);
                    System.out.println("    Indexed " + result.filesProcessed() + " files, "
                            + result.entitiesFound() + " entities"
                            + (result.filesSkipped() > 0 ? " (" + result.filesSkipped() + " unchanged)" : ""));
                } catch (Exception e) {
                    System.out.println("    Index failed: " + e.getMessage());
                }
            }
        }

        /**
         * Automatically register project models with the staging server.
         * For each model in the project manifest:
         *   1. Check if staging already has it (skip if so)
         *   2. Try catalog staging first (for well-known model IDs)
         *   3. Fall back to local file staging if model files exist on disk
         */
        static void autoStageProjectModels(List<KompileProjectModel> models, int stagingPort, File projectDir) {
            String stagingBase = "http://localhost:" + stagingPort + "/api/staging";
            System.out.println("  Auto-registering " + models.size() + " model(s) with staging...");

            for (KompileProjectModel model : models) {
                String modelId = model.getModelId();
                if (modelId == null || modelId.isBlank()) continue;

                try {
                    // Check if already staged
                    java.net.HttpURLConnection statusConn = (java.net.HttpURLConnection)
                            new java.net.URL(stagingBase + "/status/" + modelId).openConnection();
                    statusConn.setConnectTimeout(3000);
                    statusConn.setReadTimeout(3000);
                    int statusCode = statusConn.getResponseCode();
                    if (statusCode == 200) {
                        System.out.println("    " + modelId + ": already in staging, skipping");
                        statusConn.disconnect();
                        continue;
                    }
                    statusConn.disconnect();
                } catch (Exception e) {
                    // Not found or error — proceed to stage
                }

                String framework = model.getMetadata().getOrDefault("registry.framework", "onnx");
                String modelFile = model.getMetadata().getOrDefault("registry.modelFile", "");

                // Try catalog staging first
                boolean catalogStaged = false;
                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                            new java.net.URL(stagingBase + "/stage/catalog/" + modelId + "?autoPromote=true").openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(10000);
                    int rc = conn.getResponseCode();
                    if (rc == 202 || rc == 200) {
                        System.out.println("    " + modelId + ": staging from catalog (async)");
                        catalogStaged = true;
                    }
                    conn.disconnect();
                } catch (Exception e) {
                    // Catalog staging failed — try local
                }

                // If catalog didn't work, try local file staging
                if (!catalogStaged && model.getPath() != null) {
                    Path localModelDir = projectDir.toPath().resolve(model.getPath());
                    Path localModelFile = modelFile.isEmpty()
                            ? localModelDir
                            : localModelDir.resolve(modelFile);

                    if (Files.exists(localModelFile)) {
                        try {
                            String json = String.format(
                                    "{\"modelId\":\"%s\",\"inputPath\":\"%s\",\"format\":\"%s\",\"autoPromote\":true}",
                                    modelId,
                                    localModelFile.toAbsolutePath().toString().replace("\\", "\\\\"),
                                    framework);
                            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                                    new java.net.URL(stagingBase + "/convert").openConnection();
                            conn.setRequestMethod("POST");
                            conn.setRequestProperty("Content-Type", "application/json");
                            conn.setDoOutput(true);
                            conn.setConnectTimeout(5000);
                            conn.setReadTimeout(30000);
                            conn.getOutputStream().write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            int rc = conn.getResponseCode();
                            if (rc == 202 || rc == 200) {
                                System.out.println("    " + modelId + ": staging from local path " + localModelFile);
                            } else {
                                System.out.println("    " + modelId + ": local staging returned " + rc);
                            }
                            conn.disconnect();
                        } catch (Exception e) {
                            System.out.println("    " + modelId + ": local staging failed: " + e.getMessage());
                        }
                    } else {
                        System.out.println("    " + modelId + ": model file not found at " + localModelFile);
                    }
                }
            }
        }

        /**
         * Wait for the app-main server to be ready. Tries /actuator/health first,
         * then falls back to a simple HTTP GET on the root path (some installs
         * don't bundle Spring Boot Actuator).
         */
        static boolean waitForAppReady(int port, int timeoutSeconds) {
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                            new java.net.URL("http://localhost:" + port + "/actuator/health").openConnection();
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    int rc = conn.getResponseCode();
                    conn.disconnect();
                    if (rc == 200) return true;
                } catch (Exception ignored) {}
                // Fallback: simple root GET — if server responds at all, it's up
                try {
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                            new java.net.URL("http://localhost:" + port + "/").openConnection();
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    int rc = conn.getResponseCode();
                    conn.disconnect();
                    if (rc >= 200 && rc < 500) return true;
                } catch (Exception ignored) {}
                try { Thread.sleep(2000); } catch (InterruptedException e) { return false; }
            }
            return false;
        }

        /**
         * Trigger auto-ingest crawl via the unified crawl REST API.
         * Builds a UnifiedCrawlRequest from manifest crawl profiles and POSTs
         * to /api/unified-crawl/start. Runs asynchronously on the server.
         *
         * When the crawl profile is multimodal (VLM-enabled), includes a
         * processingRoute block with pdfRoutingMode=AUTO so the backend
         * classifies each PDF — text-only PDFs go through standard text
         * extraction while scanned/image PDFs route to the VLM pipeline.
         */
        static void triggerAutoCrawl(int appPort, KompileProjectManifest manifest) {
            System.out.println("  Starting document crawl...");
            for (KompileProjectCrawlProfile profile : manifest.getCrawlProfiles()) {
                String profileId = profile.getId() != null ? profile.getId() : "auto-ingest";
                try {
                    List<String> args = buildCrawlArgs(profile, "http://localhost:" + appPort, null, null);
                    int exitCode = new CommandLine(new CrawlCommand()).execute(args.toArray(String[]::new));
                    if (exitCode == 0) {
                        if (profile.isMultimodal()) {
                            System.out.println("    Crawl '" + profileId + "' started (async, multi-route PDF: text->extraction, scanned->VLM).");
                        } else {
                            System.out.println("    Crawl '" + profileId + "' started (async).");
                        }
                    } else {
                        System.out.println("    Crawl '" + profileId + "' failed with exit code " + exitCode);
                    }
                } catch (Exception e) {
                    System.out.println("    Crawl '" + profileId + "' failed: " + e.getMessage());
                }
            }
        }

        private static void openBrowserUrl(String url) {
            try {
                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("mac")) {
                    new ProcessBuilder("open", url).start();
                } else if (os.contains("win")) {
                    new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
                } else {
                    new ProcessBuilder("xdg-open", url).start();
                }
            } catch (Exception ignored) {}
        }
    }

    @Command(name = "start", aliases = "launch", mixinStandardHelpOptions = true,
            description = "Start a Kompile project — find the installed app JAR, point it at this " +
                    "project's config/data, start staging and the main app.%n%n" +
                    "This is the simple way to launch a project. For the full interactive experience%n" +
                    "(browser open, crawl prompts, MCP injection), use 'kompile project open'.%n%n" +
                    "Examples:%n" +
                    "  kompile project start%n" +
                    "  kompile project start --port 9090%n" +
                    "  kompile project start --root /path/to/project --crawl%n" +
                    "  kompile project start --no-staging --port 8082%n")
    public static class Start implements Callable<Integer> {

        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--port", "-p"},
                description = "Port for the main web application. Default: 8080",
                defaultValue = "8080")
        private int appPort;

        @Option(names = {"--staging-port"},
                description = "Port for the model staging server. Default: 8090",
                defaultValue = "8090")
        private int stagingPort;

        @Option(names = {"--no-staging"},
                description = "Skip starting the model staging server.",
                defaultValue = "false")
        private boolean noStaging;

        @Option(names = {"--crawl"},
                description = "Auto-run crawl profiles after app is healthy.",
                defaultValue = "false")
        private boolean crawl;

        @Option(names = {"--jvm-args"},
                description = "Additional JVM arguments for the main application (comma-separated).",
                split = ",")
        private List<String> jvmArgs;

        @Override
        public Integer call() throws Exception {
            // 1. Resolve project and load manifest
            KompileProjectStore store = new KompileProjectStore();
            Path resolved = resolveProjectRoot(store, root);
            KompileProjectManifest manifest = store.load(resolved);
            File projectDir = resolved.toFile();
            String projectName = manifest.getName() != null ? manifest.getName() : projectDir.getName();

            System.out.println("Starting project: " + projectName);
            System.out.println("  Root: " + resolved);

            // 2. Ensure global bootstrap
            GlobalBootstrap.ensureHomeDirectory();
            GlobalBootstrap.ensureConfigs();

            // 3. Find installed app (JAR or native executable)
            File appJar = Open.findInstalledAppJar();
            if (appJar == null) {
                System.err.println("\nkompile-app-main not installed.");
                System.err.println("Install it with: kompile install kompile-app");
                return 1;
            }

            boolean appIsNative = !appJar.getName().endsWith(".jar");
            if (appIsNative && jvmArgs != null && !jvmArgs.isEmpty()) {
                System.err.println("  Warning: --jvm-args ignored for native executable: " + appJar.getName());
            }

            ServiceManager serviceManager = new ServiceManager();

            // 4. Check if already running
            if (serviceManager.checkHealth(appPort)) {
                System.out.println("  Already running at http://localhost:" + appPort);
                return 0;
            }

            System.out.println("  " + (appIsNative ? "Executable" : "JAR") + ": " + appJar.getName());
            System.out.println("  Port: " + appPort);

            File logDir = new File(projectDir, "data/logs");
            logDir.mkdirs();

            // 5. Start staging server (background)
            Process stagingProcess = null;
            if (!noStaging) {
                File stagingJar = Open.findInstalledStagingJar();
                if (stagingJar != null) {
                    if (serviceManager.checkHealth(stagingPort)) {
                        System.out.println("  Staging: already running on port " + stagingPort);
                    } else {
                        System.out.println("  Starting staging on port " + stagingPort + "...");
                        List<String> stagingArgs = Open.buildStagingArgs(projectDir, appPort);
                        stagingProcess = serviceManager.startProjectComponent(
                                projectName + "-staging", "kompile-model-staging", stagingJar,
                                stagingPort, projectDir, null, stagingArgs, logDir, false);
                        boolean stagingHealthy = serviceManager.waitForHealth(stagingPort, 60);
                        if (stagingHealthy) {
                            Open.configureStagingCallback(stagingPort, appPort);
                            System.out.println("  Staging: ready (PID: " + stagingProcess.pid() + ")");
                        } else {
                            System.out.println("  Staging: started but health check timed out (PID: " + stagingProcess.pid() + ")");
                        }
                    }
                } else {
                    System.out.println("  Staging: not installed (install with: kompile install kompile-model-staging)");
                }
            }

            // 6. Auto-register models with staging
            boolean stagingAvailableForModels = !noStaging && serviceManager.checkHealth(stagingPort);
            if (stagingAvailableForModels && !manifest.getModels().isEmpty()) {
                Open.autoStageProjectModels(manifest.getModels(), stagingPort, projectDir);
            }

            // 7. Auto-index coding projects
            if (!manifest.getCodingProjects().isEmpty()) {
                Open.autoIndexCodingProjects(manifest.getCodingProjects());
            }

            // 8. Build app arguments
            List<String> appArgs = Open.buildProjectAppArgs(projectDir, appPort, stagingPort,
                    stagingProcess != null || serviceManager.checkHealth(stagingPort));

            // 9. Shutdown hook
            final Process stagingRef = stagingProcess;
            String webInstanceName = projectName + "-web";
            String stagingInstanceName = projectName + "-staging";
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down...");
                if (stagingRef != null && stagingRef.isAlive()) {
                    stagingRef.destroy();
                    try { stagingRef.waitFor(); } catch (InterruptedException ignored) {}
                    System.out.println("  Staging stopped.");
                }
                try {
                    InstanceRegistry.unregister(webInstanceName);
                    InstanceRegistry.unregister(stagingInstanceName);
                } catch (Exception ignored) {}
            }));

            // 10. Crawl trigger (after app starts, in background)
            if (crawl && !manifest.getCrawlProfiles().isEmpty()) {
                final int crawlPort = appPort;
                final KompileProjectManifest crawlManifest = manifest;
                Thread crawlThread = new Thread(() -> {
                    if (!Open.waitForAppReady(crawlPort, 120)) return;
                    Open.triggerAutoCrawl(crawlPort, crawlManifest);
                });
                crawlThread.setDaemon(true);
                crawlThread.start();
            }

            // 11. Start app (foreground — blocks until Ctrl+C)
            System.out.println("\nStarting kompile-app-main...");
            System.out.println("  http://localhost:" + appPort);
            System.out.println("  Press Ctrl+C to stop.\n");

            Process appProcess = serviceManager.startProjectComponent(
                    webInstanceName, "kompile-app-main", appJar, appPort,
                    projectDir, jvmArgs, appArgs, null, true);

            int exitCode = appProcess.waitFor();

            // 12. Cleanup
            InstanceRegistry.unregister(webInstanceName);
            if (stagingProcess != null) {
                InstanceRegistry.unregister(stagingInstanceName);
                if (stagingProcess.isAlive()) {
                    stagingProcess.destroy();
                }
            }

            return exitCode;
        }
    }

    @Command(name = "stop", aliases = "shutdown", mixinStandardHelpOptions = true,
            description = "Stop a running Kompile project — kills the app and staging processes.%n%n" +
                    "Finds running instances by project directory (from the instance registry at%n" +
                    "~/.kompile/instances/) and gracefully shuts them down. Also cleans up MCP%n" +
                    "config and open-state metadata.%n%n" +
                    "Examples:%n" +
                    "  kompile project stop%n" +
                    "  kompile project stop --root /path/to/project%n" +
                    "  kompile project stop --port 8082%n" +
                    "  kompile project stop --all%n")
    public static class Stop implements Callable<Integer> {

        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--port", "-p"},
                description = "Stop the instance running on this port (ignores --root).")
        private Integer port;

        @Option(names = {"--all"},
                description = "Stop all registered Kompile instances.",
                defaultValue = "false")
        private boolean all;

        @Option(names = {"--force", "-f"},
                description = "Force-kill processes instead of graceful shutdown.",
                defaultValue = "false")
        private boolean force;

        @Override
        public Integer call() throws Exception {
            ServiceManager serviceManager = new ServiceManager();
            List<InstanceInfo> targets = new ArrayList<>();

            if (all) {
                // Stop everything
                targets.addAll(InstanceRegistry.listAll());
                if (targets.isEmpty()) {
                    System.out.println("No running instances found.");
                    return 0;
                }
                System.out.println("Stopping all " + targets.size() + " instance(s)...");
            } else if (port != null) {
                // Stop by port
                InstanceInfo info = InstanceRegistry.findByPort(port);
                if (info != null) {
                    targets.add(info);
                } else {
                    System.err.println("No registered instance on port " + port);
                    return 1;
                }
            } else {
                // Stop by project directory
                KompileProjectStore store = new KompileProjectStore();
                Path resolved = resolveProjectRoot(store, root);
                String projectDir = resolved.toFile().getAbsolutePath();

                targets.addAll(InstanceRegistry.findByProjectDir(projectDir));

                if (targets.isEmpty()) {
                    // Fallback: look for instances whose name matches the project name
                    KompileProjectManifest manifest = null;
                    try {
                        manifest = store.load(resolved);
                    } catch (Exception ignored) {}

                    if (manifest != null) {
                        String projectName = manifest.getName() != null ? manifest.getName() : resolved.toFile().getName();
                        for (InstanceInfo info : InstanceRegistry.listAll()) {
                            if (info.getName() != null && info.getName().startsWith(projectName)) {
                                targets.add(info);
                            }
                        }
                    }
                }

                if (targets.isEmpty()) {
                    System.out.println("No running instances found for project at " + resolved);
                    // Show all registered instances as a hint
                    List<InstanceInfo> allInstances = InstanceRegistry.listAll();
                    if (!allInstances.isEmpty()) {
                        System.out.println("\nRegistered instances:");
                        for (InstanceInfo info : allInstances) {
                            boolean alive = ProcessHandle.of(info.getPid()).map(ProcessHandle::isAlive).orElse(false);
                            System.out.println("  " + info.getName() + " [" + info.getType() + "] "
                                    + "port=" + info.getPort() + " pid=" + info.getPid()
                                    + (alive ? " (running)" : " (dead)"));
                        }
                        System.out.println("\nUse --port, --all, or --root to target specific instances.");
                    }
                    return 1;
                }
            }

            int stopped = 0;
            int alreadyDead = 0;
            for (InstanceInfo info : targets) {
                Optional<ProcessHandle> ph = ProcessHandle.of(info.getPid());
                if (ph.isEmpty() || !ph.get().isAlive()) {
                    System.out.println("  " + info.getName() + ": not running (cleaning up stale registry entry)");
                    InstanceRegistry.unregister(info.getName());
                    alreadyDead++;
                    continue;
                }

                System.out.println("  Stopping " + info.getName()
                        + " [" + info.getType() + "] (PID: " + info.getPid()
                        + ", port: " + info.getPort() + ")...");

                ProcessHandle process = ph.get();
                if (force) {
                    process.destroyForcibly();
                } else {
                    process.destroy();
                    try {
                        process.onExit().get(15, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (Exception e) {
                        System.out.println("    Graceful shutdown timed out, force-killing...");
                        process.destroyForcibly();
                    }
                }

                InstanceRegistry.unregister(info.getName());
                System.out.println("    Stopped.");
                stopped++;
            }

            // Clean up MCP config and open-state if we stopped a project
            if (!all && port == null) {
                KompileProjectStore store = new KompileProjectStore();
                Path resolved = resolveProjectRoot(store, root);
                // Clean up MCP .mcp.json if present
                Path mcpJson = resolved.resolve(".mcp.json");
                try {
                    McpToolInjection.removeTools(mcpJson);
                } catch (Exception ignored) {}
                // Clear runtime metadata from open state
                Open.clearRuntimeMetadata(store, resolved);
            }

            System.out.println("\n" + stopped + " stopped"
                    + (alreadyDead > 0 ? ", " + alreadyDead + " already dead" : "") + ".");
            return 0;
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
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
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

    @Command(name = "model-list", mixinStandardHelpOptions = true,
            description = "List project model role/version references.")
    public static class ListModels implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            KompileProjectManifest manifest = store.load(resolveProjectRoot(store, root));
            printModels(manifest);
            return 0;
        }
    }

    @Command(name = "model-add", mixinStandardHelpOptions = true,
            description = "Register a project model role, version, source, and staging registry reference.")
    public static class AddModel implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = "--id", description = "Project model ID. Defaults to --model-id.")
        private String id;

        @Option(names = "--model-id", required = true, description = "Model ID used by model-staging or serving.")
        private String modelId;

        @Option(names = "--role", description = "Model role: llm, embedding, reranker, vlm, graph, tokenizer, ocr.", defaultValue = "model")
        private String role;

        @Option(names = "--version", description = "Project-pinned model version.")
        private String version;

        @Option(names = "--source", description = "Model source type: staging, huggingface, github, http, local.", defaultValue = "staging")
        private String source;

        @Option(names = "--repo", description = "Source repository or URL.")
        private String sourceRepository;

        @Option(names = "--revision", description = "Source revision, tag, branch, or archive version.")
        private String sourceRevision;

        @Option(names = "--path", description = "Project-relative model path under data/models.")
        private String path;

        @Option(names = "--registry-id", description = "Model ID in data/models/registry.json. Defaults to --model-id.")
        private String registryModelId;

        @Option(names = "--type", description = "Staging registry model type, such as llm_ggml, dense_encoder, cross_encoder, or vlm_pipeline.")
        private String registryType;

        @Option(names = "--backend", description = "Preferred serving backend recorded in project metadata.", defaultValue = "cpu")
        private String backend;

        @Option(names = "--framework", description = "Framework recorded in staging metadata. Defaults from --type.")
        private String framework;

        @Option(names = "--model-file", description = "Model file name in the staged model directory.")
        private String modelFile;

        @Option(names = "--vocab-file", description = "Tokenizer/vocab file name in the staged model directory.")
        private String vocabFile;

        @Option(names = "--artifact", description = "Local artifact file to copy into data/models/<path>/ as the model file.")
        private File artifact;

        @Option(names = "--placeholder", description = "Create a tiny CPU placeholder model artifact for smoke/no-op serving.")
        private boolean placeholder;

        @Option(names = "--description", description = "Model description recorded in project and staging metadata.")
        private String description;

        @Option(names = "--staging", negatable = true, defaultValue = "true",
                description = "Update data/models/registry.json for model-staging.")
        private Boolean updateStagingRegistry;

        @Option(names = "--required", negatable = true,
                description = "Whether this model is required to serve the project.")
        private Boolean required;

        @Option(names = "--tag", split = ",", description = "Model tags. Can be repeated or comma-separated.")
        private List<String> tags = new ArrayList<>();

        @Override
        public Integer call() throws IOException {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            String type = normalizeRegistryType(firstNonBlank(registryType, registryTypeForRole(role)));
            String resolvedPath = firstNonBlank(path, defaultRegistryPath(type, modelId));
            String resolvedModelFile = firstNonBlank(modelFile,
                    artifact == null ? defaultModelFile(type, modelId, placeholder) : artifact.toPath().getFileName().toString());
            String resolvedVocabFile = firstNonBlank(vocabFile, defaultVocabFile(type));
            KompileProjectModel model = new KompileProjectModel();
            model.setId(id);
            model.setModelId(modelId);
            model.setRole(role);
            model.setVersion(version);
            model.setSource(source);
            model.setSourceRepository(sourceRepository);
            model.setSourceRevision(sourceRevision);
            model.setPath(resolvedPath);
            model.setRegistryModelId(registryModelId);
            model.getMetadata().put("registry.type", type);
            model.getMetadata().put("registry.backend", backend);
            model.getMetadata().put("registry.framework", firstNonBlank(framework, defaultFramework(type)));
            model.getMetadata().put("registry.modelFile", resolvedModelFile);
            model.getMetadata().put("registry.vocabFile", resolvedVocabFile);
            model.getMetadata().put("registry.modelType", defaultMetadataModelType(type));
            if (description != null) {
                model.getMetadata().put("description", description);
            }
            if (required != null) {
                model.setRequired(required);
            }
            model.setTags(tags);
            KompileProjectManifest manifest = store.registerModel(projectRoot, model);
            KompileProjectModel registered = store.findModel(manifest, firstNonBlank(id, modelId))
                    .orElseGet(() -> store.findModel(manifest, modelId).orElse(model));
            materializeModelArtifact(projectRoot, registered, artifact, placeholder);
            if (updateStagingRegistry == null || updateStagingRegistry) {
                writeStagingRegistry(projectRoot, manifest);
            }
            printModels(manifest);
            return 0;
        }
    }

    @Command(name = "model-clone", mixinStandardHelpOptions = true,
            description = "Clone a model repository via git/git-xet into the project's data/models/ directory.")
    public static class CloneModel implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Parameters(index = "0", description = "Model repo (e.g., 'org/model' for HuggingFace, or a full git URL).")
        private String repo;

        @Option(names = {"--name"}, description = "Local directory name under data/models/. Defaults to repo basename.")
        private String modelName;

        @Option(names = {"--branch", "-b"}, description = "Branch or revision to clone.")
        private String branch;

        @Option(names = {"--xet"}, description = "Use git-xet for efficient large file transfer.")
        private boolean useXet;

        @Option(names = {"--token"}, description = "Authentication token (e.g., HuggingFace token).")
        private String token;

        @Option(names = {"--depth"}, description = "Shallow clone depth.", defaultValue = "1")
        private int depth;

        @Override
        public Integer call() throws Exception {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);

            // Resolve clone URL
            String cloneUrl = repo.startsWith("http") || repo.startsWith("git@")
                    ? repo : "https://huggingface.co/" + repo;

            // Resolve target directory inside project
            String dirName = modelName;
            if (dirName == null || dirName.isBlank()) {
                dirName = repo.contains("/") ? repo.substring(repo.lastIndexOf('/') + 1) : repo;
                if (dirName.endsWith(".git")) dirName = dirName.substring(0, dirName.length() - 4);
            }
            Path targetDir = projectRoot.resolve("data/models/" + dirName);

            if (Files.isDirectory(targetDir) && Files.isDirectory(targetDir.resolve(".git"))) {
                System.out.println("Model already cloned at: " + targetDir);
                System.out.println("  To update: cd " + targetDir + " && git pull");
                return 0;
            }

            // Build clone command
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.add("clone");
            if (depth > 0) { cmd.add("--depth"); cmd.add(String.valueOf(depth)); }
            if (branch != null && !branch.isBlank()) { cmd.add("--branch"); cmd.add(branch); }

            String effectiveUrl = cloneUrl;
            if (token != null && !token.isBlank() && cloneUrl.contains("huggingface.co")) {
                effectiveUrl = cloneUrl.replace("https://", "https://hf_user:" + token + "@");
            }
            cmd.add(effectiveUrl);
            cmd.add(targetDir.toString());

            System.out.println("Cloning " + repo + " → " + targetDir);
            Files.createDirectories(targetDir.getParent());

            ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO().directory(targetDir.getParent().toFile());
            int exitCode = pb.start().waitFor();
            if (exitCode != 0) {
                System.err.println("Clone failed with exit code " + exitCode);
                return exitCode;
            }

            if (useXet) {
                ProcessBuilder xetPb = new ProcessBuilder("git", "xet", "install")
                        .inheritIO().directory(targetDir.toFile());
                xetPb.start().waitFor();
            }

            System.out.println("Model cloned to project: " + targetDir);
            return 0;
        }
    }

    @Command(name = "pipeline-list", mixinStandardHelpOptions = true,
            description = "List project pipeline versions and model bindings.")
    public static class ListPipelines implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            KompileProjectManifest manifest = store.load(resolveProjectRoot(store, root));
            printPipelines(manifest);
            return 0;
        }
    }

    @Command(name = "pipeline-add", mixinStandardHelpOptions = true,
            description = "Register a project pipeline version and its model bindings.")
    public static class AddPipeline implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = "--id", description = "Project pipeline ID. Defaults to --pipeline-id.")
        private String id;

        @Option(names = "--pipeline-id", required = true, description = "Pipeline registry ID.")
        private String pipelineId;

        @Option(names = "--name", description = "Pipeline display name.")
        private String name;

        @Option(names = "--role", description = "Pipeline role: rag, ingest, vlm_ingest, graph, serving.", defaultValue = "pipeline")
        private String role;

        @Option(names = "--version", description = "Project-pinned pipeline version.")
        private String version;

        @Option(names = "--definition", description = "Project-relative pipeline definition path.")
        private String definitionPath;

        @Option(names = "--model", split = ",", description = "Model IDs or project model refs used by this pipeline.")
        private List<String> modelRefs = new ArrayList<>();

        @Option(names = "--required", negatable = true,
                description = "Whether this pipeline is required to serve the project.")
        private Boolean required;

        @Option(names = "--inactive", description = "Register the pipeline as inactive.")
        private boolean inactive;

        @Option(names = "--tag", split = ",", description = "Pipeline tags. Can be repeated or comma-separated.")
        private List<String> tags = new ArrayList<>();

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectPipeline pipeline = new KompileProjectPipeline();
            pipeline.setId(id);
            pipeline.setPipelineId(pipelineId);
            pipeline.setName(name);
            pipeline.setRole(role);
            pipeline.setVersion(version);
            pipeline.setDefinitionPath(definitionPath);
            pipeline.setModelRefs(modelRefs);
            if (required != null) {
                pipeline.setRequired(required);
            }
            pipeline.setActive(!inactive);
            pipeline.setTags(tags);
            KompileProjectManifest manifest = store.registerPipeline(projectRoot, pipeline);
            printPipelines(manifest);
            return 0;
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
                    System.out.println(java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
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

    @Command(name = "serve", mixinStandardHelpOptions = true,
            description = "Start model staging, serving, and app services for this project based on the manifest.")
    public static class Serve implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--workflow", "--id"}, description = "Workflow to run. Defaults to start-services.")
        private String workflowId;

        @Option(names = "--staging-only", description = "Only start the model-staging service.")
        private boolean stagingOnly;

        @Option(names = "--serving-only", description = "Only start the model-serving subprocess.")
        private boolean servingOnly;

        @Option(names = "--app-only", description = "Only start the main app.")
        private boolean appOnly;

        @Option(names = "--url", description = "Base URL of kompile-app for health checks.")
        private String appUrl;

        @Option(names = {"--port", "-p"}, description = "Localhost kompile-app port.")
        private Integer port;

        @Option(names = "--dry-run", description = "Print selected steps without running them.")
        private boolean dryRun;

        @Override
        public Integer call() throws Exception {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectManifest manifest = autoconfigureModels(store, projectRoot, store.load(projectRoot));
            store.syncProjectRegistries(projectRoot);
            printServePlan(manifest, projectRoot);
            return runServeSelection(store, manifest, projectRoot, workflowId,
                    stagingOnly, servingOnly, appOnly, appUrl, port, dryRun);
        }
    }

    @Command(name = "crawl", mixinStandardHelpOptions = true,
            description = "Start the project if needed and run the best available crawl workflow or profile.")
    public static class Crawl implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = "--workflow", description = "Crawl workflow ID or name. Auto-selected when omitted.")
        private String workflowId;

        @Option(names = {"--profile", "--id"}, description = "Crawl profile ID or name. Auto-selected when omitted.")
        private String profileId;

        @Option(names = "--url", description = "Base URL of kompile-app, such as http://localhost:8080.")
        private String appUrl;

        @Option(names = {"--port", "-p"}, description = "Localhost kompile-app port.")
        private Integer port;

        @Option(names = "--watch", description = "Override profile watch setting.")
        private Boolean watch;

        @Option(names = "--local", negatable = true,
                description = "Run a local directory/file crawl and write project crawl artifacts when possible.")
        private Boolean local;

        @Option(names = "--serve", negatable = true,
                description = "Start project services before running a raw crawl profile.")
        private Boolean serve;

        @Option(names = "--dry-run", description = "Print selected steps without running them.")
        private boolean dryRun;

        @Override
        public Integer call() throws Exception {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectManifest manifest = autoconfigureModels(store, projectRoot, store.load(projectRoot));
            store.syncProjectRegistries(projectRoot);

            KompileProjectWorkflow workflow = selectCrawlWorkflow(store, manifest, workflowId);
            if (workflow != null) {
                printCrawlPlan(store, manifest, workflow, projectRoot);
                return runWorkflow(store, manifest, workflow, projectRoot, appUrl, port, dryRun);
            }

            KompileProjectCrawlProfile profile = selectCrawlProfile(store, manifest, profileId);
            if (profile == null) {
                System.err.println("No crawl workflow or crawl profile found in this project.");
                System.err.println("Add one with: kompile project crawl-add --source <path-or-url>");
                return 1;
            }

            if (shouldRunLocalCrawl(profile, local, appUrl, port)) {
                return runLocalCrawl(profile, projectRoot, dryRun);
            }

            boolean shouldServe = serve == null || serve;
            if (shouldServe) {
                int serveExit = runServeSelection(store, manifest, projectRoot, null,
                        false, false, false, appUrl, port, dryRun);
                if (serveExit != 0) {
                    return serveExit;
                }
            }

            List<String> args = buildCrawlArgs(profile, appUrl, port, watch);
            if (dryRun) {
                System.out.println("kompile app crawl " + String.join(" ", quoteArgs(args)));
                return 0;
            }
            return new CommandLine(new CrawlCommand()).execute(args.toArray(String[]::new));
        }
    }

    @Command(name = "crawl-run", mixinStandardHelpOptions = true,
            description = "Run a managed crawl profile through the existing app crawl command.")
    public static class RunCrawlProfile implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--id", "--profile"}, required = true, description = "Crawl profile ID or name.")
        private String profileId;

        @Option(names = "--url", description = "Base URL of kompile-app, such as http://localhost:8080.")
        private String appUrl;

        @Option(names = {"--port", "-p"}, description = "Localhost kompile-app port.")
        private Integer port;

        @Option(names = "--watch", description = "Override profile watch setting.")
        private Boolean watch;

        @Option(names = "--local", negatable = true,
                description = "Run a local directory/file crawl and write project crawl artifacts when possible.")
        private Boolean local;

        @Option(names = "--dry-run", description = "Print the app crawl command without running it.")
        private boolean dryRun;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectManifest manifest = store.load(projectRoot);
            KompileProjectCrawlProfile profile = store.findCrawlProfile(manifest, profileId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown crawl profile: " + profileId));
            if (shouldRunLocalCrawl(profile, local, appUrl, port)) {
                return runLocalCrawl(profile, projectRoot, dryRun);
            }
            List<String> args = buildCrawlArgs(profile, appUrl, port, watch);
            if (dryRun) {
                System.out.println("kompile app crawl " + String.join(" ", quoteArgs(args)));
                return 0;
            }
            return new CommandLine(new CrawlCommand()).execute(args.toArray(String[]::new));
        }
    }

    @Command(name = "workflow-list", mixinStandardHelpOptions = true,
            description = "List managed project workflows.")
    public static class ListWorkflows implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            KompileProjectManifest manifest = store.load(resolveProjectRoot(store, root));
            printWorkflows(manifest);
            return 0;
        }
    }

    @Command(name = "workflow-add", mixinStandardHelpOptions = true,
            description = "Register a simple workflow with one step.")
    public static class AddWorkflow implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = "--id", description = "Workflow ID. Defaults to a slug of the name.")
        private String id;

        @Option(names = "--name", required = true, description = "Workflow display name.")
        private String name;

        @Option(names = "--phase", description = "Workflow phase: init, start, crawl, verify, analyze, stop, run.", defaultValue = "run")
        private String phase;

        @Option(names = "--description", description = "Workflow description.")
        private String description;

        @Option(names = "--step-type", required = true,
                description = "Step type: SCRIPT, COMMAND, CRAWL, HTTP, HEALTH_CHECK, WAIT.")
        private String stepType;

        @Option(names = "--step-ref", description = "Referenced script ID or crawl profile ID.")
        private String stepRef;

        @Option(names = "--command", description = "Command for COMMAND steps.")
        private String command;

        @Option(names = "--url", description = "URL for HTTP or HEALTH_CHECK steps.")
        private String url;

        @Option(names = "--method", description = "HTTP method.", defaultValue = "GET")
        private String method;

        @Option(names = "--body", description = "HTTP request body.")
        private String body;

        @Option(names = "--expected-status", description = "Expected HTTP status.")
        private Integer expectedStatus;

        @Option(names = "--timeout-seconds", description = "Step timeout seconds.")
        private Integer timeoutSeconds;

        @Option(names = "--wait-seconds", description = "Wait duration for WAIT steps.")
        private Integer waitSeconds;

        @Option(names = "--continue-on-failure", description = "Continue workflow when this step fails.")
        private boolean continueOnFailure;

        @Option(names = "--tag", split = ",", description = "Workflow tags. Can be repeated or comma-separated.")
        private List<String> tags = new ArrayList<>();

        @Override
        public Integer call() {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectWorkflowStep step = new KompileProjectWorkflowStep();
            step.setName(name + " step");
            step.setType(stepType);
            step.setRef(stepRef);
            step.setCommand(command);
            step.setUrl(url);
            step.setMethod(method);
            step.setBody(body);
            step.setExpectedStatus(expectedStatus);
            step.setTimeoutSeconds(timeoutSeconds);
            step.setWaitSeconds(waitSeconds);
            step.setContinueOnFailure(continueOnFailure);

            KompileProjectWorkflow workflow = new KompileProjectWorkflow();
            workflow.setId(id);
            workflow.setName(name);
            workflow.setPhase(phase);
            workflow.setDescription(description);
            workflow.setTags(tags);
            workflow.setSteps(List.of(step));
            KompileProjectManifest manifest = store.registerWorkflow(projectRoot, workflow);
            printWorkflows(manifest);
            return 0;
        }
    }

    @Command(name = "workflow-run", mixinStandardHelpOptions = true,
            description = "Run a managed project workflow.")
    public static class RunWorkflow implements Callable<Integer> {
        @Option(names = {"--root", "-r"}, description = "Project root. Defaults to current directory.", defaultValue = ".")
        private File root;

        @Option(names = {"--id", "--workflow"}, required = true, description = "Workflow ID or name.")
        private String workflowId;

        @Option(names = "--url", description = "Base URL of kompile-app for crawl and HTTP template expansion.")
        private String appUrl;

        @Option(names = {"--port", "-p"}, description = "Localhost kompile-app port.")
        private Integer port;

        @Option(names = "--dry-run", description = "Print workflow steps without running them.")
        private boolean dryRun;

        @Override
        public Integer call() throws Exception {
            KompileProjectStore store = new KompileProjectStore();
            Path projectRoot = resolveProjectRoot(store, root);
            KompileProjectManifest manifest = store.load(projectRoot);
            KompileProjectWorkflow workflow = store.findWorkflow(manifest, workflowId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown workflow: " + workflowId));
            return runWorkflow(store, manifest, workflow, projectRoot, appUrl, port, dryRun);
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

    private static Path resolveProjectRoot(KompileProjectStore store, File root) {
        Path candidate = root.toPath().toAbsolutePath().normalize();
        return store.findProjectRoot(candidate).orElse(candidate);
    }

    private static void printManifest(KompileProjectManifest manifest, KompileProjectStatus status) {
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

    private static void printComponents(KompileProjectManifest manifest) {
        System.out.println("  Components (" + manifest.getComponents().size() + "):");
        for (KompileProjectComponent component : manifest.getComponents()) {
            System.out.println("    " + component.getId()
                    + " [" + component.getType() + ", " + component.getStorageBackend() + "] "
                    + component.getPath()
                    + (component.getTags().isEmpty() ? "" : " tags=" + String.join(",", component.getTags())));
        }
    }

    private static void printCodingProjects(KompileProjectManifest manifest) {
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

    private static void printModels(KompileProjectManifest manifest) {
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

    private static void printPipelines(KompileProjectManifest manifest) {
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

    private static void printScripts(KompileProjectManifest manifest) {
        System.out.println("  Scripts (" + manifest.getScripts().size() + "):");
        for (KompileProjectScript script : manifest.getScripts()) {
            System.out.println("    " + script.getId()
                    + " [" + script.getPhase() + ", " + script.getPlatform() + "] "
                    + firstNonBlank(script.getPath(), script.getCommand(), "-")
                    + (script.getTags().isEmpty() ? "" : " tags=" + String.join(",", script.getTags())));
        }
    }

    private static void printCrawlProfiles(KompileProjectManifest manifest) {
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

    private static void printWorkflows(KompileProjectManifest manifest) {
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

    private static void printServePlan(KompileProjectManifest manifest, Path projectRoot) {
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

    private static void printCrawlPlan(KompileProjectStore store, KompileProjectManifest manifest,
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

    private static int runServeSelection(KompileProjectStore store, KompileProjectManifest manifest,
                                         Path projectRoot, String workflowId,
                                         boolean stagingOnly, boolean servingOnly, boolean appOnly,
                                         String appUrl, Integer port, boolean dryRun) throws Exception {
        if (workflowId != null && !workflowId.isBlank()) {
            KompileProjectWorkflow workflow = store.findWorkflow(manifest, workflowId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown serve workflow: " + workflowId));
            return runWorkflow(store, manifest, workflow, projectRoot, appUrl, port, dryRun);
        }

        String scriptId = null;
        if (stagingOnly) {
            scriptId = "start-staging";
        } else if (servingOnly) {
            scriptId = "start-serving";
        } else if (appOnly) {
            scriptId = "start-app";
        }
        if (scriptId != null) {
            return runSyntheticWorkflow(store, manifest, projectRoot, "serve-" + scriptId,
                    "Serve " + scriptId, List.of(scriptStep(scriptId, scriptId)), appUrl, port, dryRun);
        }

        KompileProjectWorkflow workflow = store.findWorkflow(manifest, "start-services")
                .or(() -> manifest.getWorkflows().stream()
                        .filter(candidate -> "START".equals(normalizeEnum(candidate.getPhase())))
                        .findFirst())
                .orElse(null);
        if (workflow != null) {
            return runWorkflow(store, manifest, workflow, projectRoot, appUrl, port, dryRun);
        }

        if (store.findScript(manifest, "start-all").isPresent()) {
            return runSyntheticWorkflow(store, manifest, projectRoot, "serve-start-all", "Serve project",
                    List.of(scriptStep("start-all", "start-all")), appUrl, port, dryRun);
        }
        System.err.println("No start workflow or start-all script found in this project.");
        return 1;
    }

    private static int runSyntheticWorkflow(KompileProjectStore store, KompileProjectManifest manifest,
                                            Path projectRoot, String id, String name,
                                            List<KompileProjectWorkflowStep> steps,
                                            String appUrl, Integer port, boolean dryRun) throws Exception {
        KompileProjectWorkflow workflow = new KompileProjectWorkflow();
        workflow.setId(id);
        workflow.setName(name);
        workflow.setPhase("run");
        workflow.setSteps(steps);
        return runWorkflow(store, manifest, workflow, projectRoot, appUrl, port, dryRun);
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

        String resolvedCrawlId = localArtifactId(firstNonBlank(crawlId, "vlm-ocr-docs"));
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
        model.setPath("data/models/vlm-pipelines/" + localArtifactId(config.vlmModelId()) + "/pipeline.json");
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
        return "smoldocling-256m".equals(localArtifactId(modelId))
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

    private static KompileProjectWorkflowStep scriptStep(String id, String ref) {
        KompileProjectWorkflowStep step = new KompileProjectWorkflowStep();
        step.setId(id);
        step.setName(id);
        step.setType("SCRIPT");
        step.setRef(ref);
        return step;
    }

    private static KompileProjectWorkflow selectCrawlWorkflow(KompileProjectStore store,
                                                              KompileProjectManifest manifest,
                                                              String workflowId) {
        if (workflowId != null && !workflowId.isBlank()) {
            return store.findWorkflow(manifest, workflowId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown crawl workflow: " + workflowId));
        }
        return store.findWorkflow(manifest, "pdf-vlm-ingest")
                .or(() -> store.findWorkflow(manifest, "initial-crawl"))
                .or(() -> manifest.getWorkflows().stream()
                        .filter(workflow -> "CRAWL".equals(normalizeEnum(workflow.getPhase()))
                                || workflow.getSteps().stream().anyMatch(step -> "CRAWL".equals(normalizeEnum(step.getType()))))
                        .findFirst())
                .orElse(null);
    }

    private static KompileProjectCrawlProfile selectCrawlProfile(KompileProjectStore store,
                                                                 KompileProjectManifest manifest,
                                                                 String profileId) {
        if (profileId != null && !profileId.isBlank()) {
            return store.findCrawlProfile(manifest, profileId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown crawl profile: " + profileId));
        }
        return manifest.getCrawlProfiles().stream()
                .filter(KompileProjectCrawlProfile::isMultimodal)
                .findFirst()
                .or(() -> manifest.getCrawlProfiles().stream().findFirst())
                .orElse(null);
    }

    private static KompileProjectManifest autoconfigureModels(KompileProjectStore store, Path projectRoot,
                                                              KompileProjectManifest manifest) {
        boolean changed = false;
        for (KompileProjectCrawlProfile profile : manifest.getCrawlProfiles()) {
            if (profile.isMultimodal() && firstNonBlank(profile.getVlmModel()) != null
                    && store.findModel(manifest, profile.getVlmModel()).isEmpty()) {
                KompileProjectModel model = inferredModel(profile.getVlmModel(), "VLM",
                        List.of("vlm", "crawl", "autoconfig"));
                model.setMetadata(Map.of("inferredFromCrawlProfile", profile.getId()));
                manifest = store.registerModel(projectRoot, model);
                changed = true;
            }
            if (firstNonBlank(profile.getGraphModelName()) != null
                    && store.findModel(manifest, profile.getGraphModelName()).isEmpty()) {
                KompileProjectModel model = inferredModel(profile.getGraphModelName(), "GRAPH",
                        List.of("graph", "crawl", "autoconfig"));
                model.setSource(firstNonBlank(profile.getGraphModelProvider(), "runtime"));
                model.setMetadata(Map.of("inferredFromCrawlProfile", profile.getId()));
                manifest = store.registerModel(projectRoot, model);
                changed = true;
            }
            if (profile.isMultimodal() && firstNonBlank(profile.getVlmModel()) != null
                    && store.findPipeline(manifest, profile.getId() + "-vlm-ingest").isEmpty()) {
                KompileProjectPipeline pipeline = new KompileProjectPipeline();
                pipeline.setId(profile.getId() + "-vlm-ingest");
                pipeline.setPipelineId(profile.getId() + "-vlm-ingest");
                pipeline.setName(profile.getName() + " VLM ingest");
                pipeline.setRole("VLM_INGEST");
                pipeline.setVersion("1.0.0");
                pipeline.setModelRefs(List.of(profile.getVlmModel()));
                pipeline.setTags(List.of("vlm", "crawl", "autoconfig"));
                pipeline.setMetadata(Map.of("inferredFromCrawlProfile", profile.getId()));
                manifest = store.registerPipeline(projectRoot, pipeline);
                changed = true;
            }
        }
        return changed ? store.load(projectRoot) : manifest;
    }

    private static KompileProjectModel inferredModel(String modelId, String role, List<String> tags) {
        KompileProjectModel model = new KompileProjectModel();
        model.setModelId(modelId);
        model.setRole(role);
        model.setSource("project");
        model.setRegistryModelId(modelId);
        model.setRequired(true);
        model.setTags(tags);
        return model;
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

    private static int runWorkflow(KompileProjectStore store, KompileProjectManifest manifest,
                                   KompileProjectWorkflow workflow, Path projectRoot,
                                   String appUrl, Integer port, boolean dryRun) throws Exception {
        String baseUrl = firstNonBlank(appUrl, port == null ? null : "http://localhost:" + port, "http://localhost:8080");
        System.out.println("Workflow: " + workflow.getName() + " (" + workflow.getId() + ")");
        int index = 1;
        for (KompileProjectWorkflowStep step : workflow.getSteps()) {
            System.out.println("[" + index + "/" + workflow.getSteps().size() + "] " + step.getName()
                    + " <" + step.getType() + ">");
            int exitCode = runWorkflowStep(store, manifest, step, projectRoot, baseUrl, dryRun);
            if (exitCode != 0 && !step.isContinueOnFailure()) {
                System.err.println("Workflow failed at step " + step.getId() + " with exit code " + exitCode);
                return exitCode;
            }
            index++;
        }
        return 0;
    }

    private static int runWorkflowStep(KompileProjectStore store, KompileProjectManifest manifest,
                                       KompileProjectWorkflowStep step, Path projectRoot,
                                       String baseUrl, boolean dryRun) throws Exception {
        String type = normalizeEnum(step.getType());
        return switch (type) {
            case "SCRIPT" -> runScriptStep(store, manifest, step, projectRoot, dryRun);
            case "COMMAND" -> runCommandStep(step, projectRoot, dryRun);
            case "CRAWL" -> runCrawlStep(store, manifest, step, projectRoot, baseUrl, dryRun);
            case "HTTP" -> runHttpStep(step, projectRoot, baseUrl, dryRun);
            case "HEALTH_CHECK" -> runHealthCheckStep(step, projectRoot, baseUrl, dryRun);
            case "WAIT" -> runWaitStep(step, dryRun);
            default -> throw new IllegalArgumentException("Unsupported workflow step type: " + step.getType());
        };
    }

    private static int runScriptStep(KompileProjectStore store, KompileProjectManifest manifest,
                                     KompileProjectWorkflowStep step, Path projectRoot,
                                     boolean dryRun) throws IOException, InterruptedException {
        KompileProjectScript script = store.findScript(manifest, step.getRef())
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflow script ref: " + step.getRef()));
        String command = firstNonBlank(script.getCommand(), script.getPath());
        KompileProjectWorkflowStep commandStep = new KompileProjectWorkflowStep();
        commandStep.setCommand(command);
        commandStep.setWorkingDirectory(firstNonBlank(script.getWorkingDirectory(), step.getWorkingDirectory(), "."));
        Map<String, String> environment = new LinkedHashMap<>(defaultServeEnvironment(manifest, script, projectRoot));
        environment.putAll(step.getEnvironment());
        commandStep.setEnvironment(environment);
        return runCommandStep(commandStep, projectRoot, dryRun);
    }

    private static void materializeModelArtifact(Path projectRoot, KompileProjectModel model,
                                                 File artifact, boolean placeholder) throws IOException {
        if (artifact == null && !placeholder) {
            return;
        }
        Path modelDir = projectModelDirectory(projectRoot, model);
        Files.createDirectories(modelDir);
        String modelFile = metadataValue(model, "registry.modelFile",
                defaultModelFile(registryType(model), model.getModelId(), placeholder));
        Path modelPath = modelDir.resolve(modelFile).normalize();
        if (!modelPath.startsWith(modelDir)) {
            throw new IllegalArgumentException("Model file escapes project model directory: " + modelFile);
        }
        if (artifact != null) {
            Path source = artifact.toPath().toAbsolutePath().normalize();
            if (!Files.isRegularFile(source)) {
                throw new IllegalArgumentException("Model artifact does not exist: " + source);
            }
            Files.copy(source, modelPath, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.writeString(modelPath, "KOMPILE_CPU_PLACEHOLDER_MODEL\n"
                    + "model_id=" + firstNonBlank(model.getModelId(), model.getId(), "model") + "\n"
                    + "backend=" + metadataValue(model, "registry.backend", "cpu") + "\n"
                    + "parameters=0\n", StandardCharsets.UTF_8);
        }

        String vocabFile = metadataValue(model, "registry.vocabFile", defaultVocabFile(registryType(model)));
        Path vocabPath = modelDir.resolve(vocabFile).normalize();
        if (!vocabPath.startsWith(modelDir)) {
            throw new IllegalArgumentException("Vocab file escapes project model directory: " + vocabFile);
        }
        if (placeholder && !Files.exists(vocabPath)) {
            if (vocabFile.endsWith(".json")) {
                Files.writeString(vocabPath, "{\"type\":\"placeholder\",\"tokens\":[\"<pad>\",\"<unk>\"]}\n",
                        StandardCharsets.UTF_8);
            } else {
                Files.writeString(vocabPath, "[PAD]\n[UNK]\nhello\nworld\n", StandardCharsets.UTF_8);
            }
        }
    }

    private static void writeStagingRegistry(Path projectRoot, KompileProjectManifest manifest) throws IOException {
        Path modelsDir = projectRoot.resolve("data/models").normalize();
        Path registryPath = modelsDir.resolve("registry.json").normalize();
        if (!registryPath.startsWith(modelsDir)) {
            throw new IllegalArgumentException("Staging registry path escapes data/models: " + registryPath);
        }
        Files.createDirectories(modelsDir);
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"version\" : \"1.0\",\n");
        json.append("  \"updated_at\" : ").append(jsonString(Instant.now().toString())).append(",\n");
        json.append("  \"models\" : {");
        boolean first = true;
        for (KompileProjectModel model : manifest.getModels()) {
            if (!first) {
                json.append(",");
            }
            json.append("\n    ")
                    .append(jsonString(firstNonBlank(model.getRegistryModelId(), model.getModelId(), model.getId())))
                    .append(" : ")
                    .append(stagingModelEntryJson(model));
            first = false;
        }
        if (!first) {
            json.append("\n  ");
        }
        json.append("},\n");
        json.append("  \"installed_archives\" : { }\n");
        json.append("}\n");
        Files.writeString(registryPath, json.toString(), StandardCharsets.UTF_8);
    }

    private static String stagingModelEntryJson(KompileProjectModel model) {
        String type = registryType(model);
        String modelId = firstNonBlank(model.getRegistryModelId(), model.getModelId(), model.getId());
        String modelFile = metadataValue(model, "registry.modelFile", defaultModelFile(type, modelId, false));
        String vocabFile = metadataValue(model, "registry.vocabFile", defaultVocabFile(type));
        String framework = metadataValue(model, "registry.framework", defaultFramework(type));
        String modelType = metadataValue(model, "registry.modelType", defaultMetadataModelType(type));
        String description = metadataValue(model, "description", null);
        String source = firstNonBlank(model.getSource(), "project");
        String sourceRepository = model.getSourceRepository();
        String status = model.getLifecycle() == KompileProjectLifecycleState.ACTIVE ? "active" : "staged";
        String version = model.getVersion();

        return "{"
                + "\"model_id\":" + jsonString(modelId) + ","
                + "\"type\":" + jsonString(type) + ","
                + "\"path\":" + jsonString(normalizeModelRelativePath(firstNonBlank(model.getPath(), defaultRegistryPath(type, modelId)))) + ","
                + "\"model_file\":" + jsonString(modelFile) + ","
                + "\"vocab_file\":" + jsonString(vocabFile) + ","
                + "\"status\":" + jsonString(status) + ","
                + "\"promoted_at\":" + jsonString(Instant.now().toString()) + ","
                + "\"metadata\":{"
                + "\"version\":" + jsonString(version) + ","
                + "\"framework\":" + jsonString(framework) + ","
                + "\"model_type\":" + jsonString(modelType) + ","
                + "\"description\":" + jsonString(description) + ","
                + "\"source_origin\":" + jsonString(source) + ","
                + "\"source_repository\":" + jsonString(sourceRepository)
                + "},"
                + "\"tokenizer\":{"
                + "\"do_lower_case\":true,"
                + "\"add_special_tokens\":true,"
                + "\"strip_accents\":true,"
                + "\"max_length\":512,"
                + "\"padding\":\"max_length\","
                + "\"truncation\":true"
                + "}"
                + "}";
    }

    private static Path projectModelDirectory(Path projectRoot, KompileProjectModel model) {
        Path modelsDir = projectRoot.resolve("data/models").normalize();
        String type = registryType(model);
        String relative = normalizeModelRelativePath(firstNonBlank(model.getPath(),
                defaultRegistryPath(type, firstNonBlank(model.getRegistryModelId(), model.getModelId(), model.getId()))));
        Path modelDir = modelsDir.resolve(relative).normalize();
        if (!modelDir.startsWith(modelsDir)) {
            throw new IllegalArgumentException("Model path escapes data/models: " + relative);
        }
        return modelDir;
    }

    private static String registryType(KompileProjectModel model) {
        return normalizeRegistryType(metadataValue(model, "registry.type", registryTypeForRole(model.getRole())));
    }

    private static String metadataValue(KompileProjectModel model, String key, String fallback) {
        if (model != null && model.getMetadata() != null) {
            String value = model.getMetadata().get(key);
            if (firstNonBlank(value) != null) {
                return value;
            }
        }
        return fallback;
    }

    private static String registryTypeForRole(String role) {
        String normalized = normalizeEnum(role);
        return switch (normalized) {
            case "LLM" -> "llm_ggml";
            case "EMBEDDING", "MODEL" -> "dense_encoder";
            case "RERANKER" -> "cross_encoder";
            case "VLM" -> "vlm_pipeline";
            case "OCR" -> "ocr_pipeline";
            default -> "dense_encoder";
        };
    }

    private static String normalizeRegistryType(String type) {
        String normalized = firstNonBlank(type, "dense_encoder").trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "llm", "ggml", "llm_ggml" -> "llm_ggml";
            case "encoder", "dense", "dense_encoder" -> "dense_encoder";
            case "sparse", "sparse_encoder" -> "sparse_encoder";
            case "reranker", "cross_encoder" -> "cross_encoder";
            case "vlm", "vlm_pipeline" -> "vlm_pipeline";
            case "ocr", "ocr_pipeline" -> "ocr_pipeline";
            default -> normalized;
        };
    }

    private static String defaultRegistryPath(String type, String modelId) {
        String id = firstNonBlank(modelId, "model");
        return switch (normalizeRegistryType(type)) {
            case "llm_ggml" -> "llm-ggmls/" + id;
            case "cross_encoder" -> "cross-encoders/" + id;
            case "vlm_pipeline" -> "vlm-pipelines/" + id;
            case "ocr_pipeline" -> "ocr-pipelines/" + id;
            case "sparse_encoder" -> "sparse-encoders/" + id;
            default -> "encoders/" + id;
        };
    }

    private static String defaultModelFile(String type, String modelId, boolean placeholder) {
        return switch (normalizeRegistryType(type)) {
            case "llm_ggml" -> placeholder ? firstNonBlank(modelId, "model") + ".gguf" : "model.gguf";
            case "vlm_pipeline" -> "pipeline.json";
            default -> "model.sdz";
        };
    }

    private static String defaultVocabFile(String type) {
        return "llm_ggml".equals(normalizeRegistryType(type)) ? "tokenizer.json" : "vocab.txt";
    }

    private static String defaultFramework(String type) {
        return "llm_ggml".equals(normalizeRegistryType(type)) ? "ggml" : "samediff";
    }

    private static String defaultMetadataModelType(String type) {
        return switch (normalizeRegistryType(type)) {
            case "llm_ggml" -> "llm";
            case "cross_encoder" -> "reranker";
            case "vlm_pipeline" -> "vlm";
            default -> "dense";
        };
    }

    private static String normalizeModelRelativePath(String path) {
        String normalized = firstNonBlank(path, "model").replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("data/models/")) {
            normalized = normalized.substring("data/models/".length());
        }
        return normalized;
    }

    private static Map<String, String> defaultServeEnvironment(KompileProjectManifest manifest,
                                                               KompileProjectScript script,
                                                               Path projectRoot) throws IOException {
        Map<String, String> environment = new LinkedHashMap<>();
        String scriptId = normalizeScriptId(script);
        if ("start-staging".equals(scriptId) && firstNonBlank(System.getenv("KOMPILE_STAGING_COMMAND")) == null) {
            defaultStagingCommand(projectRoot).ifPresent(command -> environment.put("KOMPILE_STAGING_COMMAND", command));
        } else if ("start-serving".equals(scriptId) && firstNonBlank(System.getenv("KOMPILE_SERVING_COMMAND")) == null) {
            defaultServingCommand(manifest, projectRoot).ifPresent(command -> environment.put("KOMPILE_SERVING_COMMAND", command));
        } else if ("start-app".equals(scriptId) && firstNonBlank(System.getenv("KOMPILE_APP_COMMAND")) == null) {
            defaultAppCommand(projectRoot).ifPresentOrElse(
                    command -> environment.put("KOMPILE_APP_COMMAND", command),
                    () -> environment.put("KOMPILE_APP_COMMAND",
                            "echo 'Kompile app command not configured for this project; set KOMPILE_APP_COMMAND to launch kompile-app.'; sleep 300"));
        }
        return environment;
    }

    private static String normalizeScriptId(KompileProjectScript script) {
        return firstNonBlank(script.getId(), script.getName(), script.getPath());
    }

    private static Optional<String> defaultStagingCommand(Path projectRoot) throws IOException {
        Path modelDir = projectRoot.resolve("data/models").normalize();
        Optional<Path> stagingJar = configuredPath("KOMPILE_MODEL_STAGING_JAR", "kompile.modelStaging.jar")
                .or(() -> findSourceRoot(projectRoot).flatMap(ProjectCommand::findModelStagingExecutableJar));
        if (stagingJar.isEmpty()) {
            System.err.println("No model staging jar found. Set KOMPILE_STAGING_COMMAND or KOMPILE_MODEL_STAGING_JAR.");
            return Optional.empty();
        }
        String port = firstNonBlank(System.getenv("KOMPILE_STAGING_PORT"),
                System.getProperty("kompile.staging.port"), "19090");
        return Optional.of("exec java -jar " + shellQuote(stagingJar.get().toString())
                + " --server.port=" + shellQuote(port)
                + " --kompile.staging.models-dir=" + shellQuote(modelDir.toString())
                + " --spring.main.banner-mode=off");
    }

    private static Optional<String> defaultServingCommand(KompileProjectManifest manifest,
                                                         Path projectRoot) throws IOException {
        Path argsPath = resolveServingArgsPath(manifest, projectRoot);
        String configuredCommand = firstNonBlank(System.getenv("KOMPILE_PIPELINE_SERVING_COMMAND"),
                System.getProperty("kompile.pipelineServing.command"));
        if (configuredCommand != null) {
            return Optional.of(configuredCommand.replace("{args}", shellQuote(argsPath.toString())));
        }
        String configuredClasspath = firstNonBlank(System.getenv("KOMPILE_PIPELINE_SERVING_CLASSPATH"),
                System.getProperty("kompile.pipelineServing.classpath"));
        if (configuredClasspath != null) {
            return Optional.of("exec java -cp " + shellQuote(configuredClasspath)
                    + " ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessMain "
                    + shellQuote(argsPath.toString()));
        }
        Optional<Path> sourceRoot = findSourceRoot(projectRoot);
        if (sourceRoot.isEmpty()) {
            System.err.println("No pipeline serving launcher found. Set KOMPILE_SERVING_COMMAND, "
                    + "KOMPILE_PIPELINE_SERVING_COMMAND, or KOMPILE_PIPELINE_SERVING_CLASSPATH.");
            return Optional.empty();
        }
        return Optional.of("cd " + shellQuote(sourceRoot.get().toString())
                + " && exec ./mvnw -q -pl kompile-app/kompile-pipeline-serving exec:java"
                + " -Dexec.mainClass=ai.kompile.pipeline.serving.subprocess.PipelineServingSubprocessMain"
                + " -Dexec.args=" + shellQuote(argsPath.toString())
                + " -DskipTests -Dskip.ui");
    }

    private static Optional<String> defaultAppCommand(Path projectRoot) throws IOException {
        Optional<Path> appJar = configuredPath("KOMPILE_APP_JAR", "kompile.app.jar")
                .or(() -> findSourceRoot(projectRoot).flatMap(ProjectCommand::findAppExecutableJar));
        String port = firstNonBlank(System.getenv("KOMPILE_APP_PORT"),
                System.getProperty("kompile.app.port"), "8080");
        if (appJar.isPresent()) {
            return Optional.of("exec java -jar " + shellQuote(appJar.get().toString())
                    + " --server.port=" + shellQuote(port)
                    + " --spring.main.banner-mode=off");
        }
        Optional<Path> sourceRoot = findSourceRoot(projectRoot);
        if (sourceRoot.isPresent()
                && Files.isRegularFile(sourceRoot.get().resolve("kompile-app/kompile-app-main/pom.xml"))) {
            return Optional.of("cd " + shellQuote(sourceRoot.get().toString())
                    + " && exec ./mvnw -q -pl kompile-app/kompile-app-main spring-boot:run"
                    + " -DskipTests -Dskip.ui"
                    + " -Dspring-boot.run.arguments="
                    + shellQuote("--server.port=" + port + " --spring.main.banner-mode=off"));
        }
        return Optional.empty();
    }

    private static Path resolveServingArgsPath(KompileProjectManifest manifest, Path projectRoot) throws IOException {
        Optional<Path> configuredArgs = configuredPath("KOMPILE_PIPELINE_SERVING_ARGS", "kompile.pipelineServing.args");
        if (configuredArgs.isPresent()) {
            return configuredArgs.get();
        }
        Optional<KompileProjectPipeline> servingPipeline = manifest.getPipelines().stream()
                .filter(KompileProjectPipeline::isActive)
                .filter(pipeline -> "SERVING".equals(normalizeEnum(pipeline.getRole()))
                        || hasTag(pipeline.getTags(), "serving"))
                .findFirst();
        if (servingPipeline.isPresent() && firstNonBlank(servingPipeline.get().getDefinitionPath()) != null) {
            Path definition = projectRoot.resolve(servingPipeline.get().getDefinitionPath()).normalize();
            if (!definition.startsWith(projectRoot)) {
                throw new IllegalArgumentException("Serving pipeline definition escapes project root: " + definition);
            }
            if (Files.isRegularFile(definition)) {
                String definitionJson = Files.readString(definition, StandardCharsets.UTF_8);
                if (definitionJson.contains("\"pipelineDefinitionJson\"")) {
                    return definition;
                }
                return writeServingArgs(projectRoot, servingPipeline.get(), definitionJson);
            }
        }
        return writeServingArgs(projectRoot, servingPipeline.orElse(null), defaultCpuPipelineDefinition(manifest, servingPipeline.orElse(null)));
    }

    private static Path writeServingArgs(Path projectRoot, KompileProjectPipeline pipeline,
                                         String pipelineDefinitionJson) throws IOException {
        Path argsPath = projectRoot.resolve(".kompile/state/project-serving-args.json").normalize();
        if (!argsPath.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Serving args path escapes project root: " + argsPath);
        }
        Files.createDirectories(argsPath.getParent());
        String pipelineId = pipeline == null ? "project-cpu-noop" : firstNonBlank(pipeline.getPipelineId(), pipeline.getId(), "project-cpu-noop");
        String port = firstNonBlank(System.getenv("KOMPILE_SERVING_PORT"),
                System.getProperty("kompile.serving.port"), "9090");
        String argsJson = "{\n"
                + "  \"taskId\" : " + jsonString(pipelineId) + ",\n"
                + "  \"pipelineDefinitionJson\" : " + jsonString(pipelineDefinitionJson) + ",\n"
                + "  \"executionMode\" : \"PERSISTENT_SERVING\",\n"
                + "  \"requestDataJson\" : null,\n"
                + "  \"servingPort\" : " + port + ",\n"
                + "  \"nd4jConfigJson\" : \"{\\\"backend\\\":\\\"cpu\\\"}\",\n"
                + "  \"memoryStopPercent\" : 80,\n"
                + "  \"memoryCriticalPercent\" : 90,\n"
                + "  \"memoryKillPercent\" : 95,\n"
                + "  \"memoryCheckIntervalMs\" : 1000,\n"
                + "  \"gpuMemoryStopPercent\" : 100,\n"
                + "  \"gpuMemoryCriticalPercent\" : 100,\n"
                + "  \"gpuMemoryKillPercent\" : 100,\n"
                + "  \"heartbeatIntervalMs\" : 3000,\n"
                + "  \"callbackBaseUrl\" : null\n"
                + "}\n";
        Files.writeString(argsPath, argsJson, StandardCharsets.UTF_8);
        return argsPath;
    }

    private static String defaultCpuPipelineDefinition(KompileProjectManifest manifest, KompileProjectPipeline pipeline) {
        String pipelineId = pipeline == null ? "project-cpu-noop" : firstNonBlank(pipeline.getPipelineId(), pipeline.getId(), "project-cpu-noop");
        String displayName = pipeline == null ? "Project CPU no-op" : firstNonBlank(pipeline.getName(), pipelineId);
        String modelSetId = manifest.getModels().stream()
                .filter(KompileProjectModel::isRequired)
                .findFirst()
                .or(() -> manifest.getModels().stream().findFirst())
                .map(model -> firstNonBlank(model.getRegistryModelId(), model.getModelId(), model.getId()))
                .orElse("project-default");
        String port = firstNonBlank(System.getenv("KOMPILE_SERVING_PORT"),
                System.getProperty("kompile.serving.port"), "9090");
        return "{"
                + "\"pipelineId\":" + jsonString(pipelineId) + ","
                + "\"displayName\":" + jsonString(displayName) + ","
                + "\"description\":\"Generated CPU no-op pipeline for Kompile project serve.\","
                + "\"kind\":\"GENERIC\","
                + "\"topology\":\"SEQUENCE\","
                + "\"pipelineSpec\":{"
                + "\"@class\":\"ai.kompile.pipelines.framework.runtime.pipeline.SequencePipeline\","
                + "\"id\":" + jsonString(pipelineId) + ","
                + "\"steps\":[]"
                + "},"
                + "\"modelSetId\":" + jsonString(modelSetId) + ","
                + "\"serving\":{"
                + "\"heapSize\":\"512m\","
                + "\"port\":" + port + ","
                + "\"replicas\":1,"
                + "\"gpuDeviceId\":\"cpu\","
                + "\"memoryStopPercent\":80,"
                + "\"memoryCriticalPercent\":90,"
                + "\"memoryKillPercent\":95,"
                + "\"gpuStopPercent\":100,"
                + "\"gpuCriticalPercent\":100,"
                + "\"gpuKillPercent\":100,"
                + "\"heartbeatIntervalMs\":3000"
                + "},"
                + "\"builtin\":false,"
                + "\"enabled\":true,"
                + "\"tags\":{\"generated\":true,\"cpu\":true,\"projectServe\":true}"
                + "}";
    }

    private static Optional<Path> configuredPath(String envName, String propertyName) {
        String configured = firstNonBlank(System.getenv(envName), System.getProperty(propertyName));
        return configured == null ? Optional.empty() : Optional.of(Path.of(configured).toAbsolutePath().normalize());
    }

    private static Optional<Path> findModelStagingExecutableJar(Path sourceRoot) {
        Path target = sourceRoot.resolve("kompile-app/kompile-model-staging/target");
        Path expected = target.resolve("kompile-model-staging-0.1.0-SNAPSHOT-exec.jar");
        if (Files.isRegularFile(expected)) {
            return Optional.of(expected);
        }
        if (!Files.isDirectory(target)) {
            return Optional.empty();
        }
        try {
            return Files.list(target)
                    .filter(path -> path.getFileName().toString().startsWith("kompile-model-staging-"))
                    .filter(path -> path.getFileName().toString().endsWith("-exec.jar"))
                    .filter(Files::isRegularFile)
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Path> findAppExecutableJar(Path sourceRoot) {
        Path target = sourceRoot.resolve("kompile-app/kompile-app-main/target");
        if (!Files.isDirectory(target)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(target)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith("-exec.jar")
                                || name.endsWith(".jar") && !name.endsWith("-sources.jar");
                    })
                    .sorted()
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Path> findSourceRoot(Path projectRoot) {
        List<Path> seeds = new ArrayList<>();
        String configured = firstNonBlank(System.getenv("KOMPILE_SOURCE_ROOT"),
                System.getenv("KOMPILE_DEV_ROOT"), System.getProperty("kompile.sourceRoot"));
        if (configured != null) {
            seeds.add(Path.of(configured));
        }
        seeds.add(Path.of("").toAbsolutePath());
        ProcessHandle.current().info().command().map(Path::of).ifPresent(seeds::add);
        seeds.add(projectRoot);
        for (Path seed : seeds) {
            Optional<Path> found = findSourceRootFrom(seed.toAbsolutePath().normalize());
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<Path> findSourceRootFrom(Path seed) {
        Path current = Files.isDirectory(seed) ? seed : seed.getParent();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("kompile-cli/pom.xml"))
                    && Files.isRegularFile(current.resolve("kompile-app/kompile-model-staging/pom.xml"))) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static boolean hasTag(List<String> tags, String expectedTag) {
        if (tags == null) {
            return false;
        }
        return tags.stream().anyMatch(tag -> expectedTag.equalsIgnoreCase(tag));
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        builder.append('"');
        return builder.toString();
    }

    private static int runCommandStep(KompileProjectWorkflowStep step, Path projectRoot,
                                      boolean dryRun) throws IOException, InterruptedException {
        String command = firstNonBlank(step.getCommand(), step.getRef());
        if (command == null) {
            throw new IllegalArgumentException("COMMAND workflow step requires command");
        }
        Path workdir = projectRoot.resolve(firstNonBlank(step.getWorkingDirectory(), ".")).normalize();
        if (!workdir.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Workflow step working directory escapes project root: " + workdir);
        }
        if (dryRun) {
            System.out.println("  " + command + "  (cwd=" + workdir + ")");
            return 0;
        }
        ProcessBuilder builder = new ProcessBuilder("bash", "-lc", command);
        builder.directory(workdir.toFile());
        builder.inheritIO();
        builder.environment().putAll(step.getEnvironment());
        return builder.start().waitFor();
    }

    private static int runCrawlStep(KompileProjectStore store, KompileProjectManifest manifest,
                                    KompileProjectWorkflowStep step, Path projectRoot, String baseUrl,
                                    boolean dryRun) {
        KompileProjectCrawlProfile profile = store.findCrawlProfile(manifest, step.getRef())
                .orElseThrow(() -> new IllegalArgumentException("Unknown workflow crawl ref: " + step.getRef()));
        if (canRunLocalCrawl(profile) && isDefaultLocalAppUrl(baseUrl)) {
            return runLocalCrawl(profile, projectRoot, dryRun);
        }
        List<String> args = buildCrawlArgs(profile, baseUrl, null, null);
        if (dryRun) {
            System.out.println("  kompile app crawl " + String.join(" ", quoteArgs(args)));
            return 0;
        }
        return new CommandLine(new CrawlCommand()).execute(args.toArray(String[]::new));
    }

    private static boolean shouldRunLocalCrawl(KompileProjectCrawlProfile profile, Boolean local,
                                               String appUrl, Integer port) {
        if (Boolean.FALSE.equals(local)) {
            return false;
        }
        boolean canRunLocal = canRunLocalCrawl(profile);
        if (Boolean.TRUE.equals(local) && !canRunLocal) {
            throw new IllegalArgumentException("Crawl profile requires kompile-app or model services and cannot run locally: "
                    + profile.getId());
        }
        return canRunLocal && (Boolean.TRUE.equals(local) || (appUrl == null && port == null));
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
            String lower = firstNonBlank(source, "").toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDefaultLocalAppUrl(String baseUrl) {
        return baseUrl == null || baseUrl.isBlank() || "http://localhost:8080".equals(baseUrl)
                || "http://127.0.0.1:8080".equals(baseUrl);
    }

    private static int runLocalCrawl(KompileProjectCrawlProfile profile, Path projectRoot, boolean dryRun) {
        String crawlId = localArtifactId(profile);
        Path outputDir = projectRoot.resolve("data/crawls").resolve(crawlId).normalize();
        Path markdownDir = projectRoot.resolve("data/markdown").resolve(crawlId).normalize();
        if (!outputDir.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Local crawl output escapes project root: " + outputDir);
        }
        if (!markdownDir.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Local crawl markdown output escapes project root: " + markdownDir);
        }
        if (dryRun) {
            System.out.println("kompile project crawl local " + profile.getId());
            System.out.println("  output " + outputDir);
            System.out.println("  markdown " + markdownDir);
            for (String source : profile.getSources()) {
                System.out.println("  source " + resolveLocalCrawlSource(projectRoot, source));
            }
            return 0;
        }
        try {
            Files.createDirectories(outputDir);
            Files.createDirectories(markdownDir);
            KompileProjectStore store = new KompileProjectStore();
            String projectName = null;
            try {
                KompileProjectManifest manifest = store.load(projectRoot);
                if (manifest != null) {
                    projectName = manifest.getName();
                }
            } catch (Exception ignored) { }
            LocalCrawlResult result = collectLocalCrawl(profile, projectRoot, outputDir, markdownDir, projectName);
            writeLocalCrawlArtifacts(profile, projectRoot, outputDir, markdownDir, result);
            // Sync catalogs so crawled files are discoverable in the project
            try {
                store.syncMarkdownCatalog(projectRoot);
                store.syncCrawlCatalog(projectRoot);
            } catch (Exception ignored) { }
            System.out.println("Local crawl complete: " + profile.getId());
            System.out.println("  Documents: " + result.documents().size());
            System.out.println("  Chunks: " + result.chunks().size());
            System.out.println("  Markdown: " + result.markdownCount());
            System.out.println("  Output: " + outputDir);
            System.out.println("  Markdown output: " + markdownDir);
            if (profile.getFactSheetName() != null && !profile.getFactSheetName().isBlank()) {
                System.out.println("  Fact sheet: " + profile.getFactSheetName());
                // Best-effort: register markdown as facts via running backend
                tryRegisterMarkdownAsFacts(profile.getFactSheetName());
            }
            return 0;
        } catch (IOException e) {
            System.err.println("Local crawl failed: " + e.getMessage());
            return 1;
        }
    }

    private static LocalCrawlResult collectLocalCrawl(KompileProjectCrawlProfile profile,
                                                      Path projectRoot,
                                                      Path outputDir,
                                                      Path markdownDir) throws IOException {
        return collectLocalCrawl(profile, projectRoot, outputDir, markdownDir, null);
    }

    private static LocalCrawlResult collectLocalCrawl(KompileProjectCrawlProfile profile,
                                                      Path projectRoot,
                                                      Path outputDir,
                                                      Path markdownDir,
                                                      String projectName) throws IOException {
        List<LocalCrawlDocument> documents = new ArrayList<>();
        List<LocalCrawlChunk> chunks = new ArrayList<>();
        int maxDocuments = profile.getMaxDocuments();
        for (String source : profile.getSources()) {
            Path sourcePath = resolveLocalCrawlSource(projectRoot, source);
            if (!Files.exists(sourcePath)) {
                throw new IOException("Crawl source does not exist: " + sourcePath);
            }
            List<Path> sourceFiles = localCrawlFiles(sourcePath, projectRoot, outputDir, markdownDir, profile);
            for (Path file : sourceFiles) {
                if (maxDocuments > 0 && documents.size() >= maxDocuments) {
                    break;
                }
                LocalCrawlDocument document = localCrawlDocument(projectRoot, sourcePath, file);
                LocalMarkdownArtifact markdown = writeLocalCrawlMarkdown(projectRoot, markdownDir,
                        document, file, profile, projectName);
                document = document.withMarkdown(markdown);
                documents.add(document);
                if (markdown.markdown() != null && !markdown.markdown().isBlank()) {
                    chunks.addAll(localCrawlChunks(document, markdown.markdown()));
                }
            }
            if (maxDocuments > 0 && documents.size() >= maxDocuments) {
                break;
            }
        }
        return new LocalCrawlResult(documents, chunks);
    }

    private static List<Path> localCrawlFiles(Path sourcePath, Path projectRoot, Path outputDir, Path markdownDir,
                                              KompileProjectCrawlProfile profile) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(sourcePath)) {
            if (includeLocalCrawlFile(sourcePath, sourcePath.getFileName(), projectRoot, outputDir, markdownDir, profile)) {
                files.add(sourcePath);
            }
            return files;
        }
        try (Stream<Path> stream = Files.walk(sourcePath)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> includeLocalCrawlFile(path, sourcePath.relativize(path), projectRoot, outputDir,
                            markdownDir, profile))
                    .forEach(files::add);
        }
        files.sort(Path::compareTo);
        return files;
    }

    private static boolean includeLocalCrawlFile(Path file, Path relative, Path projectRoot, Path outputDir,
                                                 Path markdownDir,
                                                 KompileProjectCrawlProfile profile) {
        Path normalized = file.toAbsolutePath().normalize();
        Path root = projectRoot.toAbsolutePath().normalize();
        if (normalized.startsWith(outputDir.toAbsolutePath().normalize())) {
            return false;
        }
        if (normalized.startsWith(markdownDir.toAbsolutePath().normalize())) {
            return false;
        }
        Path projectRelative = normalized.startsWith(root) ? root.relativize(normalized) : relative;
        if (!profile.isIncludeHidden() && hasHiddenPathSegment(projectRelative)) {
            return false;
        }
        if (matchesAny(projectRelative, List.of(".git/**", ".kompile/state/**", "data/crawls/**"))) {
            return false;
        }
        if (!profile.getExcludePatterns().isEmpty() && matchesAny(projectRelative, profile.getExcludePatterns())) {
            return false;
        }
        return profile.getIncludePatterns().isEmpty() || matchesAny(projectRelative, profile.getIncludePatterns());
    }

    private static boolean hasHiddenPathSegment(Path path) {
        for (Path segment : path) {
            if (segment.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(Path path, List<String> patterns) {
        for (String pattern : patterns) {
            if (matchesGlob(path, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesGlob(Path path, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String normalizedPattern = pattern.replace('\\', '/');
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + normalizedPattern);
        if (matcher.matches(path) || path.getFileName() != null && matcher.matches(path.getFileName())) {
            return true;
        }
        if (normalizedPattern.startsWith("**/")) {
            PathMatcher basenameMatcher = FileSystems.getDefault().getPathMatcher("glob:" + normalizedPattern.substring(3));
            return basenameMatcher.matches(path)
                    || path.getFileName() != null && basenameMatcher.matches(path.getFileName());
        }
        return false;
    }

    private static LocalCrawlDocument localCrawlDocument(Path projectRoot, Path sourcePath, Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        Path root = projectRoot.toAbsolutePath().normalize();
        Path relative = normalized.startsWith(root) ? root.relativize(normalized) : sourcePath.relativize(normalized);
        if (relative.toString().isBlank()) {
            relative = normalized.getFileName();
        }
        String documentId = localArtifactId(relative.toString());
        String contentType = firstNonBlank(Files.probeContentType(normalized), "application/octet-stream");
        return new LocalCrawlDocument(documentId, normalized.toString(), relative.toString().replace('\\', '/'),
                Files.size(normalized), Files.getLastModifiedTime(normalized).toInstant().toString(), contentType,
                null, null, "PENDING", null, 0, 0);
    }

    private static LocalMarkdownArtifact writeLocalCrawlMarkdown(Path projectRoot, Path markdownDir,
                                                                 LocalCrawlDocument document, Path file) throws IOException {
        return writeLocalCrawlMarkdown(projectRoot, markdownDir, document, file, null, null);
    }

    private static LocalMarkdownArtifact writeLocalCrawlMarkdown(Path projectRoot, Path markdownDir,
                                                                 LocalCrawlDocument document, Path file,
                                                                 KompileProjectCrawlProfile profile,
                                                                 String projectName) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!isKnowledgeSource(name)) {
            return LocalMarkdownArtifact.skipped("Unsupported knowledge source type");
        }
        try {
            LocalMarkdownContent content = localMarkdownContent(file, name, document, profile, projectName);
            if (content.markdown() == null || content.markdown().isBlank()) {
                return LocalMarkdownArtifact.skipped("No extractable text");
            }
            Path markdownPath = markdownDir.resolve(document.documentId() + ".md").normalize();
            if (!markdownPath.startsWith(markdownDir)) {
                throw new IllegalArgumentException("Markdown artifact escapes markdown directory: " + markdownPath);
            }
            Files.writeString(markdownPath, content.markdown(), StandardCharsets.UTF_8);
            String relativeMarkdown = projectRoot.toAbsolutePath().normalize()
                    .relativize(markdownPath.toAbsolutePath().normalize())
                    .toString()
                    .replace('\\', '/');
            return LocalMarkdownArtifact.extracted(content.title(), relativeMarkdown, content.markdown());
        } catch (Exception e) {
            return LocalMarkdownArtifact.failed(e.getMessage());
        }
    }

    private static boolean isKnowledgeSource(String name) {
        return name.endsWith(".pdf") || isTextKnowledgeSource(name);
    }

    private static boolean isTextKnowledgeSource(String name) {
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".markdown")
                || name.endsWith(".json") || name.endsWith(".jsonl") || name.endsWith(".yaml")
                || name.endsWith(".yml") || name.endsWith(".csv") || name.endsWith(".html")
                || name.endsWith(".htm") || name.endsWith(".xml") || name.endsWith(".properties");
    }

    private static LocalMarkdownContent localMarkdownContent(Path file, String name,
                                                             LocalCrawlDocument document) throws IOException {
        return localMarkdownContent(file, name, document, null, null);
    }

    private static LocalMarkdownContent localMarkdownContent(Path file, String name,
                                                             LocalCrawlDocument document,
                                                             KompileProjectCrawlProfile profile,
                                                             String projectName) throws IOException {
        String title = file.getFileName().toString();
        String body;
        if (name.endsWith(".pdf")) {
            body = extractPdfText(file);
        } else if (name.endsWith(".html") || name.endsWith(".htm")) {
            org.jsoup.nodes.Document html = Jsoup.parse(file.toFile(), StandardCharsets.UTF_8.name());
            title = firstNonBlank(html.title(), title);
            body = htmlToMarkdown(html);
        } else if (name.endsWith(".md") || name.endsWith(".markdown")) {
            body = Files.readString(file, StandardCharsets.UTF_8);
        } else {
            body = normalizeKnowledgeText(Files.readString(file, StandardCharsets.UTF_8));
        }
        String markdown = knowledgeMarkdown(title, document, body, profile, projectName);
        return new LocalMarkdownContent(title, markdown);
    }

    private static String extractPdfText(Path file) throws IOException {
        if (isNativeImageRuntime()) {
            return extractPdfTextWithPdftotext(file);
        }
        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
            return normalizeKnowledgeText(new PDFTextStripper().getText(pdf));
        } catch (IOException e) {
            String fallback = extractPdfTextWithPdftotext(file);
            if (!fallback.isBlank()) {
                return fallback;
            }
            throw e;
        }
    }

    private static String extractPdfTextWithPdftotext(Path file) throws IOException {
        Process process = new ProcessBuilder("pdftotext", "-layout", file.toString(), "-")
                .redirectErrorStream(true)
                .start();
        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("pdftotext timed out for " + file);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while extracting PDF text: " + file, e);
        }
        if (process.exitValue() != 0) {
            throw new IOException("pdftotext failed for " + file + ": " + output.strip());
        }
        return normalizeKnowledgeText(output);
    }

    private static boolean isNativeImageRuntime() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }

    private static String htmlToMarkdown(org.jsoup.nodes.Document html) {
        html.select("script, style, noscript, svg, canvas").remove();
        StringBuilder markdown = new StringBuilder();
        Element body = html.body();
        if (body == null) {
            return "";
        }
        for (Element element : body.select("h1, h2, h3, h4, h5, h6, p, li, blockquote, pre, table")) {
            String tag = element.tagName().toLowerCase(Locale.ROOT);
            if ("table".equals(tag)) {
                appendHtmlTable(markdown, element);
                continue;
            }
            String text = normalizeKnowledgeText(element.text());
            if (text.isBlank()) {
                continue;
            }
            if (tag.matches("h[1-6]")) {
                int level = Math.max(1, Math.min(6, Integer.parseInt(tag.substring(1))));
                markdown.append("#".repeat(level)).append(' ').append(text).append("\n\n");
            } else if ("li".equals(tag)) {
                markdown.append("- ").append(text).append("\n");
            } else if ("blockquote".equals(tag)) {
                markdown.append("> ").append(text).append("\n\n");
            } else if ("pre".equals(tag)) {
                markdown.append("```\n").append(element.text()).append("\n```\n\n");
            } else {
                markdown.append(text).append("\n\n");
            }
        }
        if (markdown.isEmpty()) {
            return normalizeKnowledgeText(body.text());
        }
        return markdown.toString().trim();
    }

    private static void appendHtmlTable(StringBuilder markdown, Element table) {
        for (Element row : table.select("tr")) {
            List<String> cells = new ArrayList<>();
            for (Element cell : row.select("th, td")) {
                String text = normalizeKnowledgeText(cell.text());
                if (!text.isBlank()) {
                    cells.add(text);
                }
            }
            if (!cells.isEmpty()) {
                markdown.append("| ").append(String.join(" | ", cells)).append(" |\n");
            }
        }
        markdown.append('\n');
    }

    private static String knowledgeMarkdown(String title, LocalCrawlDocument document, String body) {
        return knowledgeMarkdown(title, document, body, null, null);
    }

    private static String knowledgeMarkdown(String title, LocalCrawlDocument document, String body,
                                            KompileProjectCrawlProfile profile, String projectName) {
        String normalizedBody = normalizeKnowledgeText(body);
        if (normalizedBody.isBlank()) {
            return "";
        }
        String resolvedTitle = firstNonBlank(title, document.relativePath(), document.documentId());
        StringBuilder fm = new StringBuilder("---\n");
        fm.append("title: \"").append(escapeYaml(resolvedTitle)).append("\"\n");
        fm.append("source: \"").append(escapeYaml(document.source())).append("\"\n");
        fm.append("source_path: \"").append(escapeYaml(document.relativePath())).append("\"\n");
        fm.append("content_type: \"").append(escapeYaml(document.contentType())).append("\"\n");
        fm.append("converter: kompile-project-crawl\n");
        if (profile != null) {
            fm.append("crawl_profile: \"").append(escapeYaml(firstNonBlank(profile.getId(), profile.getName()))).append("\"\n");
            if (profile.getFactSheetName() != null && !profile.getFactSheetName().isBlank()) {
                fm.append("fact_sheet: \"").append(escapeYaml(profile.getFactSheetName())).append("\"\n");
            }
            if (profile.getCollection() != null && !profile.getCollection().isBlank()) {
                fm.append("collection: \"").append(escapeYaml(profile.getCollection())).append("\"\n");
            }
            if (profile.getTags() != null && !profile.getTags().isEmpty()) {
                fm.append("tags:\n");
                for (String tag : profile.getTags()) {
                    fm.append("  - ").append(tag).append("\n");
                }
            }
        }
        if (projectName != null && !projectName.isBlank()) {
            fm.append("project: \"").append(escapeYaml(projectName)).append("\"\n");
        }
        fm.append("---\n\n");

        return fm.toString()
                + "# " + resolvedTitle + "\n\n"
                + normalizedBody + "\n";
    }

    private static String escapeYaml(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String normalizeKnowledgeText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t ]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static String readLocalCrawlText(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!isTextKnowledgeSource(name)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static List<LocalCrawlChunk> localCrawlChunks(LocalCrawlDocument document, String text) {
        List<LocalCrawlChunk> chunks = new ArrayList<>();
        int chunkSize = 2_000;
        int overlap = 200;
        int index = 0;
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkSize);
            String chunkText = text.substring(start, end).trim();
            if (!chunkText.isBlank()) {
                chunks.add(new LocalCrawlChunk(document.documentId() + "#chunk-" + index,
                        document.documentId(), index, start, end, chunkText));
                index++;
            }
            if (end == text.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

    private static void writeLocalCrawlArtifacts(KompileProjectCrawlProfile profile, Path projectRoot, Path outputDir,
                                                 Path markdownDir,
                                                 LocalCrawlResult result) throws IOException {
        Instant finishedAt = Instant.now();
        Path analysisPath = outputDir.resolve("analysis.json");
        writeLocalKnowledgeAnalysis(profile, projectRoot, markdownDir, analysisPath, result, finishedAt);
        StringBuilder documents = new StringBuilder();
        for (LocalCrawlDocument document : result.documents()) {
            documents.append("{")
                    .append("\"documentId\":").append(jsonString(document.documentId())).append(",")
                    .append("\"source\":").append(jsonString(document.source())).append(",")
                    .append("\"relativePath\":").append(jsonString(document.relativePath())).append(",")
                    .append("\"sizeBytes\":").append(document.sizeBytes()).append(",")
                    .append("\"lastModified\":").append(jsonString(document.lastModified())).append(",")
                    .append("\"contentType\":").append(jsonString(document.contentType())).append(",")
                    .append("\"title\":").append(jsonString(document.title())).append(",")
                    .append("\"markdownPath\":").append(jsonString(document.markdownPath())).append(",")
                    .append("\"extractionStatus\":").append(jsonString(document.extractionStatus())).append(",")
                    .append("\"extractionMessage\":").append(jsonString(document.extractionMessage())).append(",")
                    .append("\"markdownChars\":").append(document.markdownChars()).append(",")
                    .append("\"wordCount\":").append(document.wordCount())
                    .append("}\n");
        }
        Files.writeString(outputDir.resolve("documents.jsonl"), documents.toString(), StandardCharsets.UTF_8);

        StringBuilder chunks = new StringBuilder();
        for (LocalCrawlChunk chunk : result.chunks()) {
            chunks.append("{")
                    .append("\"chunkId\":").append(jsonString(chunk.chunkId())).append(",")
                    .append("\"documentId\":").append(jsonString(chunk.documentId())).append(",")
                    .append("\"index\":").append(chunk.index()).append(",")
                    .append("\"start\":").append(chunk.start()).append(",")
                    .append("\"end\":").append(chunk.end()).append(",")
                    .append("\"text\":").append(jsonString(chunk.text()))
                    .append("}\n");
        }
        Files.writeString(outputDir.resolve("chunks.jsonl"), chunks.toString(), StandardCharsets.UTF_8);

        String summary = "{\n"
                + "  \"profileId\" : " + jsonString(profile.getId()) + ",\n"
                + "  \"name\" : " + jsonString(profile.getName()) + ",\n"
                + "  \"status\" : \"COMPLETED\",\n"
                + "  \"finishedAt\" : " + jsonString(finishedAt.toString()) + ",\n"
                + "  \"sources\" : " + jsonArray(profile.getSources()) + ",\n"
                + "  \"includePatterns\" : " + jsonArray(profile.getIncludePatterns()) + ",\n"
                + "  \"excludePatterns\" : " + jsonArray(profile.getExcludePatterns()) + ",\n"
                + "  \"loader\" : " + jsonString(firstNonBlank(profile.getLoader(), "local-text")) + ",\n"
                + "  \"chunker\" : " + jsonString(firstNonBlank(profile.getChunker(), "local-fixed")) + ",\n"
                + "  \"collection\" : " + jsonString(firstNonBlank(profile.getCollection(), profile.getId())) + ",\n"
                + "  \"factSheetName\" : " + jsonString(profile.getFactSheetName()) + ",\n"
                + "  \"markdownPath\" : " + jsonString(projectRelativePath(projectRoot, markdownDir)) + ",\n"
                + "  \"analysisPath\" : " + jsonString(projectRelativePath(projectRoot, analysisPath)) + ",\n"
                + "  \"documentCount\" : " + result.documents().size() + ",\n"
                + "  \"markdownCount\" : " + result.markdownCount() + ",\n"
                + "  \"chunkCount\" : " + result.chunks().size() + "\n"
                + "}\n";
        Files.writeString(outputDir.resolve("crawl-result.json"), summary, StandardCharsets.UTF_8);
    }

    /**
     * Best-effort call to register crawled markdown as facts via the running backend.
     * Silently skips if the backend is not reachable.
     */
    private static void tryRegisterMarkdownAsFacts(String factSheetName) {
        try {
            String body = "{\"factSheetName\":" + jsonString(factSheetName) + "}";
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:8080/api/projects/current/markdown/register-facts"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(5))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                System.out.println("  Registered markdown as facts in backend");
            }
        } catch (Exception ignored) {
            // Backend not running — facts can be registered later via:
            //   kompile project markdown-register-facts --fact-sheet <name>
        }
    }

    private static void writeLocalKnowledgeAnalysis(KompileProjectCrawlProfile profile, Path projectRoot, Path markdownDir,
                                                    Path analysisPath, LocalCrawlResult result,
                                                    Instant finishedAt) throws IOException {
        int totalWords = 0;
        Map<String, Integer> terms = new HashMap<>();
        for (LocalCrawlChunk chunk : result.chunks()) {
            for (String token : chunk.text().toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                if (token.length() < 3 || LOCAL_KNOWLEDGE_STOP_WORDS.contains(token)) {
                    continue;
                }
                totalWords++;
                terms.merge(token, 1, Integer::sum);
            }
        }
        List<LocalTerm> topTerms = terms.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(20)
                .map(entry -> new LocalTerm(entry.getKey(), entry.getValue()))
                .toList();
        String analysis = "{\n"
                + "  \"profileId\" : " + jsonString(profile.getId()) + ",\n"
                + "  \"analyzedAt\" : " + jsonString(finishedAt.toString()) + ",\n"
                + "  \"markdownPath\" : " + jsonString(projectRelativePath(projectRoot, markdownDir)) + ",\n"
                + "  \"documentCount\" : " + result.documents().size() + ",\n"
                + "  \"markdownCount\" : " + result.markdownCount() + ",\n"
                + "  \"chunkCount\" : " + result.chunks().size() + ",\n"
                + "  \"wordCount\" : " + totalWords + ",\n"
                + "  \"topTerms\" : " + topTermsJson(topTerms) + "\n"
                + "}\n";
        Files.writeString(analysisPath, analysis, StandardCharsets.UTF_8);
    }

    private static String projectRelativePath(Path projectRoot, Path path) {
        return projectRoot.toAbsolutePath().normalize()
                .relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    private static String topTermsJson(List<LocalTerm> terms) {
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (LocalTerm term : terms) {
            if (!first) {
                builder.append(", ");
            }
            builder.append("{\"term\":").append(jsonString(term.term()))
                    .append(",\"count\":").append(term.count())
                    .append("}");
            first = false;
        }
        builder.append("]");
        return builder.toString();
    }

    private static Path resolveLocalCrawlSource(Path projectRoot, String source) {
        Path path = Path.of(source);
        if (!path.isAbsolute()) {
            path = projectRoot.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static String localArtifactId(KompileProjectCrawlProfile profile) {
        return localArtifactId(firstNonBlank(profile.getId(), profile.getName(), "crawl"));
    }

    private static String localArtifactId(String value) {
        String id = firstNonBlank(value, "crawl")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return id.isBlank() ? "crawl" : id;
    }

    private static String jsonArray(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        boolean first = true;
        for (String value : values == null ? List.<String>of() : values) {
            if (!first) {
                builder.append(", ");
            }
            builder.append(jsonString(value));
            first = false;
        }
        builder.append("]");
        return builder.toString();
    }

    private record LocalCrawlResult(List<LocalCrawlDocument> documents, List<LocalCrawlChunk> chunks) {
        int markdownCount() {
            int count = 0;
            for (LocalCrawlDocument document : documents) {
                if (document.markdownPath() != null && !document.markdownPath().isBlank()) {
                    count++;
                }
            }
            return count;
        }
    }

    private record LocalCrawlDocument(String documentId, String source, String relativePath,
                                      long sizeBytes, String lastModified, String contentType,
                                      String title, String markdownPath, String extractionStatus,
                                      String extractionMessage, int markdownChars, int wordCount) {
        LocalCrawlDocument withMarkdown(LocalMarkdownArtifact artifact) {
            return new LocalCrawlDocument(documentId, source, relativePath, sizeBytes, lastModified, contentType,
                    artifact.title(), artifact.markdownPath(), artifact.status(), artifact.message(),
                    artifact.markdownChars(), artifact.wordCount());
        }
    }

    private record LocalCrawlChunk(String chunkId, String documentId, int index, int start, int end, String text) {
    }

    private record LocalMarkdownContent(String title, String markdown) {
    }

    private record LocalMarkdownArtifact(String title, String markdownPath, String markdown,
                                         String status, String message, int markdownChars, int wordCount) {
        static LocalMarkdownArtifact extracted(String title, String markdownPath, String markdown) {
            return new LocalMarkdownArtifact(title, markdownPath, markdown, "EXTRACTED", null,
                    markdown.length(), countWords(markdown));
        }

        static LocalMarkdownArtifact skipped(String message) {
            return new LocalMarkdownArtifact(null, null, null, "SKIPPED", message, 0, 0);
        }

        static LocalMarkdownArtifact failed(String message) {
            return new LocalMarkdownArtifact(null, null, null, "FAILED", message, 0, 0);
        }
    }

    private record LocalTerm(String term, int count) {
    }

    private static int countWords(String text) {
        int count = 0;
        for (String token : firstNonBlank(text, "").split("[^\\p{Alnum}]+")) {
            if (!token.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static int runHttpStep(KompileProjectWorkflowStep step, Path projectRoot,
                                   String baseUrl, boolean dryRun) throws IOException, InterruptedException {
        String url = resolveTemplate(firstNonBlank(step.getUrl(), step.getRef()), projectRoot, baseUrl);
        if (url == null) {
            throw new IllegalArgumentException("HTTP workflow step requires url");
        }
        String method = firstNonBlank(step.getMethod(), "GET").toUpperCase(Locale.ROOT);
        String body = resolveTemplate(step.getBody(), projectRoot, baseUrl);
        int expected = step.getExpectedStatus() == null ? 0 : step.getExpectedStatus();
        if (dryRun) {
            System.out.println("  " + method + " " + url + (body == null ? "" : " body=" + body));
            return 0;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(step.getTimeoutSeconds() == null ? 60 : step.getTimeoutSeconds()));
        if (body == null || body.isBlank()) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (expected > 0) {
            return response.statusCode() == expected ? 0 : response.statusCode();
        }
        return response.statusCode() >= 200 && response.statusCode() < 300 ? 0 : response.statusCode();
    }

    private static int runHealthCheckStep(KompileProjectWorkflowStep step, Path projectRoot,
                                          String baseUrl, boolean dryRun) throws Exception {
        String url = resolveTemplate(firstNonBlank(step.getUrl(), step.getRef(), "${appUrl}/actuator/health"),
                projectRoot, baseUrl);
        int expected = step.getExpectedStatus() == null ? 200 : step.getExpectedStatus();
        int timeoutSeconds = step.getTimeoutSeconds() == null ? 120 : step.getTimeoutSeconds();
        if (dryRun) {
            System.out.println("  wait for " + url + " status=" + expected + " timeout=" + timeoutSeconds + "s");
            return 0;
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                KompileProjectWorkflowStep httpStep = new KompileProjectWorkflowStep();
                httpStep.setUrl(url);
                httpStep.setExpectedStatus(expected);
                httpStep.setTimeoutSeconds(5);
                if (runHttpStep(httpStep, projectRoot, baseUrl, false) == 0) {
                    return 0;
                }
            } catch (Exception ignored) {
                // Retry until timeout.
            }
            Thread.sleep(2_000L);
        }
        return 1;
    }

    private static int runWaitStep(KompileProjectWorkflowStep step, boolean dryRun) throws InterruptedException {
        int seconds = step.getWaitSeconds() == null ? 1 : step.getWaitSeconds();
        if (dryRun) {
            System.out.println("  sleep " + seconds + "s");
            return 0;
        }
        Thread.sleep(Duration.ofSeconds(seconds).toMillis());
        return 0;
    }

    private static String resolveTemplate(String value, Path projectRoot, String baseUrl) {
        if (value == null) {
            return null;
        }
        return value.replace("${appUrl}", baseUrl)
                .replace("${projectRoot}", projectRoot.toString());
    }

    private static List<String> buildCrawlArgs(KompileProjectCrawlProfile profile, String appUrl,
                                               Integer port, Boolean watchOverride) {
        List<String> args = new ArrayList<>();
        args.add("start");
        if (appUrl != null && !appUrl.isBlank()) {
            args.add("--url");
            args.add(appUrl);
        } else if (port != null) {
            args.add("--port");
            args.add(String.valueOf(port));
        }

        args.addAll(profile.getSources());
        args.add("--depth");
        args.add(String.valueOf(profile.getMaxDepth()));
        if (profile.getMaxDocuments() > 0) {
            args.add("--max-docs");
            args.add(String.valueOf(profile.getMaxDocuments()));
        }
        if (!profile.isSameDomain()) {
            args.add("--no-same-domain");
        }
        if (!profile.isRobots()) {
            args.add("--no-robots");
        }
        if (profile.getDelayMs() > 0 && profile.getDelayMs() != 500) {
            args.add("--delay");
            args.add(String.valueOf(profile.getDelayMs()));
        }
        if (profile.getTimeoutMin() > 0 && profile.getTimeoutMin() != 60) {
            args.add("--timeout");
            args.add(String.valueOf(profile.getTimeoutMin()));
        }
        addJoined(args, "--include", profile.getIncludePatterns());
        addJoined(args, "--exclude", profile.getExcludePatterns());
        addJoined(args, "--content-types", profile.getContentTypes());
        addValue(args, "--chunker", profile.getChunker());
        addValue(args, "--loader", profile.getLoader());
        addValue(args, "--collection", profile.getCollection());
        if (profile.isMultimodal()) {
            args.add("--multimodal");
        }
        addValue(args, "--vlm-model", profile.getVlmModel());
        if (profile.isGraphExtraction()) {
            args.add("--graph");
        }
        addJoined(args, "--graph-entities", profile.getGraphEntityTypes());
        addJoined(args, "--graph-relations", profile.getGraphRelationTypes());
        addValue(args, "--graph-model-provider", profile.getGraphModelProvider());
        addValue(args, "--graph-model-name", profile.getGraphModelName());
        addValue(args, "--graph-temperature", profile.getGraphTemperature());
        addValue(args, "--graph-min-confidence", profile.getGraphMinConfidence());
        if (profile.getGraphAutoAccept() != null) {
            args.add("--graph-auto-accept");
            args.add(String.valueOf(profile.getGraphAutoAccept()));
        }
        addValue(args, "--graph-auto-accept-threshold", profile.getGraphAutoAcceptThreshold());
        addValue(args, "--graph-schema-mode", profile.getGraphSchemaMode());
        addValue(args, "--schema-preset", profile.getSchemaPresetId());
        addValue(args, "--graph-prompt", profile.getGraphCustomPrompt());
        if (profile.isGraphLocal()) {
            args.add("--graph-local");
        }
        if (profile.isGraphAutoStart()) {
            args.add("--graph-auto-start");
        }
        if (profile.isFollowLinks()) {
            args.add("--follow-links");
        }
        if (profile.isIncludeHidden()) {
            args.add("--include-hidden");
        }
        addValue(args, "--type", profile.getSourceType());
        addValue(args, "--fact-sheet", profile.getFactSheetName());
        addValue(args, "--name", profile.getName());
        boolean shouldWatch = watchOverride == null ? profile.isWatch() : watchOverride;
        if (shouldWatch) {
            args.add("--watch");
        }
        return args;
    }

    private static void addJoined(List<String> args, String option, List<String> values) {
        if (values != null && !values.isEmpty()) {
            args.add(option);
            args.add(String.join(",", values));
        }
    }

    private static void addValue(List<String> args, String option, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            args.add(option);
            args.add(String.valueOf(value));
        }
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

    private static List<String> quoteArgs(List<String> args) {
        List<String> quoted = new ArrayList<>();
        for (String arg : args) {
            if (arg.indexOf(' ') >= 0 || arg.indexOf('&') >= 0) {
                quoted.add("'" + arg.replace("'", "'\"'\"'") + "'");
            } else {
                quoted.add(arg);
            }
        }
        return quoted;
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

    private static KompileProjectStorageBackend parseBackend(String value) {
        return KompileProjectStorageBackend.valueOf(normalizeEnum(value));
    }

    private static KompileProjectComponentType parseType(String value) {
        return KompileProjectComponentType.valueOf(normalizeEnum(value));
    }

    private static KompileProjectLifecycleState parseLifecycle(String value) {
        return KompileProjectLifecycleState.valueOf(normalizeEnum(value));
    }

    private static String normalizeEnum(String value) {
        return firstNonBlank(value, "local")
                .trim()
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
    }

    private static String defaultDirectoryName(String remoteUrl) {
        String cleaned = remoteUrl;
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        if (cleaned.endsWith(".git")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }
        return cleaned.isBlank() ? "kompile-project" : cleaned;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
