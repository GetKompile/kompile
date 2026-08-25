/*
 * Copyright 2025 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.modelmanager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provider-neutral manifests for models whose source artifacts are managed directly by Kompile.
 * Both folder-local model_runtime and scale-out staging consume these exact component definitions.
 */
public final class ManagedModelArtifactCatalog {

    public record Component(
            String key,
            String remotePath,
            String localFileName,
            String sha256,
            long expectedBytes,
            boolean required) {
    }

    public record Definition(
            String modelId,
            String source,
            String repository,
            String revision,
            String format,
            String modelType,
            String role,
            String primaryComponentKey,
            String tokenizerComponentKey,
            List<Component> components,
            Map<String, String> metadata) {
        public Definition {
            components = List.copyOf(components);
            metadata = Map.copyOf(metadata);
        }

        public Optional<Component> component(String key) {
            return components.stream().filter(component -> component.key().equals(key)).findFirst();
        }
    }

    private static final Map<String, Definition> DEFINITIONS = definitions();

    private ManagedModelArtifactCatalog() {
    }

    public static Optional<Definition> find(String modelId) {
        return Optional.ofNullable(modelId == null ? null : DEFINITIONS.get(modelId));
    }

    public static Map<String, Definition> all() {
        return DEFINITIONS;
    }

    private static Map<String, Definition> definitions() {
        Map<String, Definition> definitions = new LinkedHashMap<>();
        definitions.put("multilingual-e5-small", new Definition(
                "multilingual-e5-small",
                "HUGGINGFACE",
                "intfloat/multilingual-e5-small",
                "614241f622f53c4eeff9890bdc4f31cfecc418b3",
                "ONNX",
                "dense_encoder",
                "ENCODER",
                "model",
                "tokenizer",
                List.of(
                        new Component(
                                "model", "onnx/model.onnx", "model.onnx",
                                "ca456c06b3a9505ddfd9131408916dd79290368331e7d76bb621f1cba6bc8665",
                                470_268_510L, true),
                        new Component(
                                "tokenizer", "onnx/tokenizer.json", "tokenizer.json",
                                "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39",
                                17_082_730L, true),
                        new Component(
                                "tokenizer_config", "onnx/tokenizer_config.json",
                                "tokenizer_config.json",
                                "a1d6bc8734a6f635dc158508bef000f8e2e5a759c7d92f984b2c86e5ff53425b",
                                443L, true),
                        new Component(
                                "pooling_config", "1_Pooling/config.json",
                                "pooling_config.json",
                                "987f7a67a38fa564c849bb5d277c52ab9088a84368fc0be31a354125aebb12a0",
                                200L, true)),
                Map.ofEntries(
                        Map.entry("embedding_dim", "384"),
                        Map.entry("max_sequence_length", "512"),
                        Map.entry("encoder_type", "GENERIC_DENSE"),
                        Map.entry("pooling_strategy", "MEAN"),
                        Map.entry("normalize_output", "true"),
                        Map.entry("input_prefix", "query: "),
                        Map.entry("supported_languages", "multilingual"),
                        Map.entry("license", "MIT"))));
        return Map.copyOf(definitions);
    }
}
