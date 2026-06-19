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

import ai.kompile.project.KompileProjectLifecycleState;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectModel;
import ai.kompile.project.KompileProjectPipeline;
import ai.kompile.project.KompileProjectStore;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

import static ai.kompile.cli.main.project.ProjectCommandUtils.firstNonBlank;
import static ai.kompile.cli.main.project.ProjectCommandUtils.jsonString;
import static ai.kompile.cli.main.project.ProjectCommandUtils.resolveProjectRoot;
import static ai.kompile.cli.main.project.ProjectPrintUtils.printModels;
import static ai.kompile.cli.main.project.ProjectPrintUtils.printPipelines;

/**
 * Picocli subcommand group for model and pipeline management.
 * Contains ListModels, AddModel, CloneModel, ListPipelines, AddPipeline subcommands.
 */
@Command(name = "model",
        mixinStandardHelpOptions = true,
        description = "Manage project models and pipelines.",
        subcommands = {
                ProjectModelCommand.ListModels.class,
                ProjectModelCommand.AddModel.class,
                ProjectModelCommand.CloneModel.class,
                ProjectModelCommand.ListPipelines.class,
                ProjectModelCommand.AddPipeline.class
        })
public class ProjectModelCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        new CommandLine(this).usage(System.out);
        return 0;
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

    // ==================== Staging registry writers ====================

    static void writeStagingRegistry(Path projectRoot, KompileProjectManifest manifest) throws IOException {
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

    static String stagingModelEntryJson(KompileProjectModel model) {
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

    static void materializeModelArtifact(Path projectRoot, KompileProjectModel model,
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

    // ==================== Registry type helpers ====================

    static String registryType(KompileProjectModel model) {
        return normalizeRegistryType(metadataValue(model, "registry.type", registryTypeForRole(model.getRole())));
    }

    static String metadataValue(KompileProjectModel model, String key, String fallback) {
        if (model != null && model.getMetadata() != null) {
            String value = model.getMetadata().get(key);
            if (firstNonBlank(value) != null) {
                return value;
            }
        }
        return fallback;
    }

    static String registryTypeForRole(String role) {
        String normalized = ProjectCommandUtils.normalizeEnum(role);
        return switch (normalized) {
            case "LLM" -> "llm_ggml";
            case "EMBEDDING", "MODEL" -> "dense_encoder";
            case "RERANKER" -> "cross_encoder";
            case "VLM" -> "vlm_pipeline";
            case "OCR" -> "ocr_pipeline";
            default -> "dense_encoder";
        };
    }

    static String normalizeRegistryType(String type) {
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

    static String defaultRegistryPath(String type, String modelId) {
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

    static String defaultModelFile(String type, String modelId, boolean placeholder) {
        return switch (normalizeRegistryType(type)) {
            case "llm_ggml" -> placeholder ? firstNonBlank(modelId, "model") + ".gguf" : "model.gguf";
            case "vlm_pipeline" -> "pipeline.json";
            default -> "model.sdz";
        };
    }

    static String defaultVocabFile(String type) {
        return "llm_ggml".equals(normalizeRegistryType(type)) ? "tokenizer.json" : "vocab.txt";
    }

    static String defaultFramework(String type) {
        return "llm_ggml".equals(normalizeRegistryType(type)) ? "ggml" : "samediff";
    }

    static String defaultMetadataModelType(String type) {
        return switch (normalizeRegistryType(type)) {
            case "llm_ggml" -> "llm";
            case "cross_encoder" -> "reranker";
            case "vlm_pipeline" -> "vlm";
            default -> "dense";
        };
    }

    static String normalizeModelRelativePath(String path) {
        String normalized = firstNonBlank(path, "model").replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith("data/models/")) {
            normalized = normalized.substring("data/models/".length());
        }
        return normalized;
    }

    static Path projectModelDirectory(Path projectRoot, KompileProjectModel model) {
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
}
