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

import ai.kompile.project.KompileProjectModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filesystem signal collection used by one-command project bootstrapping.
 */
public final class ProjectAutoDetection {

    private ProjectAutoDetection() {}

    public enum ProjectScenario {
        DATA_ONLY, MODELS_ONLY, MODELS_AND_DATA,
        CODE_ONLY, CODE_AND_DATA, CODE_AND_MODELS, ALL,
        WIZARD
    }

    public record CodeProjectSignal(Path root, String buildFile, String language) {}

    public record DetectedSignals(
            List<String> docDirs,
            List<KompileProjectModel> models,
            CodeProjectSignal codeProject,
            boolean hasPdfOrImages
    ) {
        public boolean hasData() {
            return docDirs != null && !docDirs.isEmpty();
        }

        public boolean hasModels() {
            return models != null && !models.isEmpty();
        }

        public boolean hasCode() {
            return codeProject != null;
        }

        public boolean hasAnySignal() {
            return hasData() || hasModels() || hasCode();
        }
    }

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

    public static DetectedSignals collectSignals(Path rootPath) {
        Path normalized = rootPath.toAbsolutePath().normalize();
        List<String> docDirs = detectDocumentSources(normalized);
        List<KompileProjectModel> modelFiles = detectModelFiles(normalized);
        CodeProjectSignal codeProject = detectCodeProject(normalized);
        boolean hasPdfOrImages = docDirs.stream().anyMatch(ProjectAutoDetection::containsPdfOrImages);
        return new DetectedSignals(docDirs, modelFiles, codeProject, hasPdfOrImages);
    }

    public static ProjectScenario classifyScenario(DetectedSignals signals) {
        boolean hasCode = signals != null && signals.hasCode();
        boolean hasDocs = signals != null && signals.hasData();
        boolean hasModels = signals != null && signals.hasModels();

        if (hasCode && hasDocs && hasModels) return ProjectScenario.ALL;
        if (hasCode && hasDocs) return ProjectScenario.CODE_AND_DATA;
        if (hasCode && hasModels) return ProjectScenario.CODE_AND_MODELS;
        if (hasCode) return ProjectScenario.CODE_ONLY;
        if (hasDocs && hasModels) return ProjectScenario.MODELS_AND_DATA;
        if (hasDocs) return ProjectScenario.DATA_ONLY;
        if (hasModels) return ProjectScenario.MODELS_ONLY;
        return ProjectScenario.WIZARD;
    }

    public static List<String> detectDocumentSources(Path projectRoot) {
        List<String> sources = new ArrayList<>();
        String[] candidates = {"data/input_documents", "documents", "docs", "pdfs", "files", "sources"};
        for (String candidate : candidates) {
            Path dir = projectRoot.resolve(candidate);
            if (Files.isDirectory(dir) && containsDocuments(dir)) {
                sources.add(dir.toString());
            }
        }
        if (sources.isEmpty()) {
            try (java.util.stream.Stream<Path> children = Files.list(projectRoot)) {
                children.filter(Files::isDirectory)
                        .filter(p -> !p.getFileName().toString().startsWith("."))
                        .filter(p -> !p.getFileName().toString().equals("target"))
                        .filter(p -> !p.getFileName().toString().equals("node_modules"))
                        .filter(p -> !p.getFileName().toString().equals("scripts"))
                        .filter(ProjectAutoDetection::containsDocuments)
                        .forEach(p -> sources.add(p.toString()));
            } catch (IOException ignored) {}
        }
        return sources;
    }

    public static boolean containsDocuments(Path dir) {
        try (java.util.stream.Stream<Path> files = Files.walk(dir, 2)) {
            return files.filter(Files::isRegularFile)
                    .anyMatch(p -> isDocumentFile(p.getFileName().toString()));
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean containsPdfOrImages(String dirPath) {
        return containsPdfOrImages(Path.of(dirPath));
    }

    public static boolean containsPdfOrImages(Path dir) {
        try (java.util.stream.Stream<Path> files = Files.walk(dir, 2)) {
            return files.filter(Files::isRegularFile).anyMatch(p -> {
                String lower = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return lower.endsWith(".pdf") || lower.endsWith(".png")
                        || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                        || lower.endsWith(".tiff") || lower.endsWith(".tif")
                        || lower.endsWith(".bmp");
            });
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isDocumentFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx")
                || lower.endsWith(".xls") || lower.endsWith(".xlsx")
                || lower.endsWith(".ppt") || lower.endsWith(".pptx")
                || lower.endsWith(".odt") || lower.endsWith(".ods") || lower.endsWith(".odp")
                || lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".rst")
                || lower.endsWith(".html") || lower.endsWith(".htm")
                || lower.endsWith(".csv") || lower.endsWith(".json") || lower.endsWith(".jsonl")
                || lower.endsWith(".xml") || lower.endsWith(".yaml") || lower.endsWith(".yml")
                || lower.endsWith(".rtf") || lower.endsWith(".epub");
    }

    public static boolean isModelFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".onnx") || lower.endsWith(".safetensors")
                || lower.endsWith(".bin") || lower.endsWith(".pt") || lower.endsWith(".pth")
                || lower.endsWith(".gguf") || lower.endsWith(".fb")
                || lower.endsWith(".ggml") || lower.endsWith(".sdz");
    }

    public static String[] inferModelType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gguf") || lower.endsWith(".ggml")) {
            return new String[]{"LLM", "ggml", "llm"};
        } else if (lower.endsWith(".onnx")) {
            return new String[]{"ENCODER", "onnx", "dense"};
        } else if (lower.endsWith(".fb") || lower.endsWith(".sdz")) {
            return new String[]{"ENCODER", "samediff", "dense"};
        } else if (lower.endsWith(".safetensors") || lower.endsWith(".bin")
                || lower.endsWith(".pt") || lower.endsWith(".pth")) {
            return new String[]{"MODEL", "pytorch", "unknown"};
        }
        return new String[]{"MODEL", "unknown", "unknown"};
    }

    public static List<KompileProjectModel> detectModelFiles(Path projectRoot) {
        List<KompileProjectModel> models = new ArrayList<>();
        String[] candidates = {"data/models", "models"};
        for (String candidate : candidates) {
            Path dir = projectRoot.resolve(candidate);
            if (!Files.isDirectory(dir)) continue;
            try (java.util.stream.Stream<Path> files = Files.walk(dir, 4)) {
                files.filter(Files::isRegularFile)
                        .filter(p -> isModelFile(p.getFileName().toString()))
                        .forEach(p -> models.add(modelFor(projectRoot, p)));
            } catch (IOException e) {
                System.err.println("Warning: could not scan " + dir + ": " + e.getMessage());
            }
            if (!models.isEmpty()) break;
        }
        return models;
    }

    private static KompileProjectModel modelFor(Path projectRoot, Path path) {
        String fileName = path.getFileName().toString();
        String[] typeInfo = inferModelType(fileName);
        String relativePath = projectRoot.relativize(path.getParent()).toString();
        String stem = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
        String parentName = path.getParent().getFileName().toString();
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
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        return model;
    }

    public static CodeProjectSignal detectCodeProject(Path projectRoot) {
        for (String buildFile : BUILD_FILES) {
            if (Files.isRegularFile(projectRoot.resolve(buildFile))) {
                return new CodeProjectSignal(projectRoot, buildFile,
                        LANGUAGE_MAP.getOrDefault(buildFile, "Unknown"));
            }
        }
        try (java.util.stream.Stream<Path> children = Files.list(projectRoot)) {
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

    public static String inferCrawlSourceType(String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        String lower = source.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.")) {
            return "web";
        }
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".xlsm")
                || lower.endsWith(".xlsb") || lower.endsWith(".ods")
                || lower.endsWith(".csv") || lower.endsWith(".tsv")) {
            return "excel";
        }
        Path path = Path.of(source);
        if (Files.isRegularFile(path)) {
            return "file";
        }
        if (Files.isDirectory(path)) {
            return "directory";
        }
        return null;
    }
}
