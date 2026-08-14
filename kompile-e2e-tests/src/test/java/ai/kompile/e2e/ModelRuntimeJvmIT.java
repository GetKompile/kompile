/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.e2e;

import ai.kompile.app.llm.pipeline.SameDiffLanguageModelImpl;
import ai.kompile.cli.main.chat.agent.AgentConfig;
import ai.kompile.cli.main.chat.permission.PermissionService;
import ai.kompile.cli.main.chat.tools.ToolContext;
import ai.kompile.cli.main.chat.tools.ToolRegistry;
import ai.kompile.cli.main.chat.tools.ToolResult;
import ai.kompile.cli.main.chat.tools.grounding.ModelRuntimeTool;
import ai.kompile.project.KompileProjectInitRequest;
import ai.kompile.project.KompileProjectManifest;
import ai.kompile.project.KompileProjectModel;
import ai.kompile.project.KompileProjectStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM integration gate for the folder-local model runtime.
 *
 * <p>This is deliberately not a schema, mock, HTTP, or native-executable test. It invokes the real
 * {@code model_runtime} tool against a project-owned model registration, then loads the resolved
 * GGUF through the production SameDiff serving implementation and performs tokenization and text
 * generation in the same JVM.</p>
 */
@Tag("integration")
class ModelRuntimeJvmIT {
    private static final String MODEL_ID = "supra-50m-instruct";
    private static final String MODEL_PROPERTY = "kompile.model.runtime.it.model";
    private static final String TOKENIZER_PROPERTY = "kompile.model.runtime.it.tokenizer";
    private static final Path DEFAULT_MODEL = Path.of(
            System.getProperty("user.home"), ".kompile", "models", "llm-ggmls",
            MODEL_ID, "Supra-50M-f16.gguf");

    @TempDir
    Path projectRoot;

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void modelRuntimeResolvesLoadsGeneratesAndUnloadsARealModel() throws Exception {
        Path sourceModel = requiredFile(MODEL_PROPERTY, DEFAULT_MODEL);
        Path sourceTokenizer = requiredFile(
                TOKENIZER_PROPERTY, sourceModel.getParent().resolve("tokenizer.json"));
        Path projectModelDirectory = projectRoot.resolve("data/models/llm-ggmls").resolve(MODEL_ID);
        Files.createDirectories(projectModelDirectory);
        Path projectModel = projectModelDirectory.resolve(sourceModel.getFileName());
        Path projectTokenizer = projectModelDirectory.resolve("tokenizer.json");
        Files.createSymbolicLink(projectModel, sourceModel.toAbsolutePath().normalize());
        Files.createSymbolicLink(projectTokenizer, sourceTokenizer.toAbsolutePath().normalize());
        registerProjectModel(projectModel);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ToolRegistry registry = new ToolRegistry(mapper);
        ModelRuntimeTool modelRuntime = new ModelRuntimeTool(mapper);
        registry.register(modelRuntime);
        PermissionService permissions = new PermissionService();
        permissions.setUserOverride("model_runtime", PermissionService.PermissionLevel.ALLOW);
        ToolContext context = new ToolContext(
                "model-runtime-jvm-it",
                AgentConfig.builder("model-runtime-jvm-it").enabledTools(Set.of("model_runtime")).build(),
                permissions,
                projectRoot,
                registry);

        ToolResult status = modelRuntime.execute(
                mapper.createObjectNode().put("action", "status"), context);
        assertFalse(status.isError(), status.getOutput());
        assertTrue(status.getOutput().contains("\"ready\" : true"), status.getOutput());

        ObjectNode bootstrapRequest = mapper.createObjectNode();
        bootstrapRequest.put("action", "bootstrap");
        bootstrapRequest.put("modelId", MODEL_ID);
        bootstrapRequest.put("autoBootstrap", false);
        ToolResult bootstrap = modelRuntime.execute(bootstrapRequest, context);
        assertFalse(bootstrap.isError(), bootstrap.getOutput());
        JsonNode bootstrapJson = mapper.readTree(bootstrap.getOutput());
        Path resolvedModel = Path.of(bootstrapJson.path("model").path("modelPath").asText());
        Path resolvedTokenizer = Path.of(bootstrapJson.path("model").path("tokenizerPath").asText());
        assertTrue(Files.isRegularFile(resolvedModel), bootstrap.getOutput());
        assertTrue(Files.isRegularFile(resolvedTokenizer), bootstrap.getOutput());
        assertFalse(bootstrapJson.path("model").path("bootstrapped").asBoolean(true),
                "An already registered model must not launch staging: " + bootstrap.getOutput());

        SameDiffLanguageModelImpl languageModel =
                new SameDiffLanguageModelImpl(Optional.empty(), Optional.empty());
        try {
            languageModel.loadModel(
                    MODEL_ID,
                    resolvedModel,
                    resolvedTokenizer,
                    Map.of(
                            "maxNewTokens", 4,
                            "temperature", 0.0d,
                            "topK", 1));
            assertTrue(languageModel.isLoaded(), "The production runtime did not retain the model");
            assertTrue(languageModel.countPromptTokens("The sky is") > 0,
                    "The real tokenizer did not execute");
            String generated = languageModel.generateResponse("The sky is", List.of(), 4);
            assertFalse(generated.isBlank(), "The real model produced no text");
        } finally {
            languageModel.unloadModel();
        }
        assertFalse(languageModel.isLoaded(), "The production runtime did not unload the model");
    }

    private void registerProjectModel(Path projectModel) {
        KompileProjectStore store = new KompileProjectStore();
        KompileProjectInitRequest init = new KompileProjectInitRequest();
        init.setName("model-runtime-jvm-it");
        init.setDescription("JVM integration fixture for the folder model runtime");
        init.setInitializeGit(false);
        store.init(projectRoot, init);

        KompileProjectManifest manifest = store.load(projectRoot);
        KompileProjectModel model = new KompileProjectModel();
        model.setId(MODEL_ID);
        model.setModelId(MODEL_ID);
        model.setRegistryModelId(MODEL_ID);
        model.setRole("LLM");
        model.setSource("LOCAL");
        model.setPath(projectRoot.relativize(projectModel).toString());
        model.setRequired(true);
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        model.getMetadata().put("registry.type", "llm_ggml");
        manifest.getModels().add(model);
        store.save(projectRoot, manifest);
    }

    private static Path requiredFile(String property, Path defaultPath) {
        String configured = System.getProperty(property);
        Path path = configured == null || configured.isBlank()
                ? defaultPath : Path.of(configured.trim());
        Path normalized = path.toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(normalized),
                () -> "Required real model fixture is missing: " + normalized
                        + " (override with -D" + property + "=<path>)");
        return normalized;
    }
}
