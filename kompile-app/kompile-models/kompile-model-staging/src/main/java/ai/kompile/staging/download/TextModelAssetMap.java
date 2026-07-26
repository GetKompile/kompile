/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.download;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical source-asset map for a runnable SDX text model.
 *
 * <p>Values are repository-relative paths for JSON staging requests and canonical
 * bundle-relative paths after multipart upload. They are never interpreted as URLs;
 * remote URL sources must be packaged as an archive or uploaded as a local bundle.</p>
 *
 * <p>The required runnable-chat semantics are: model, tokenizer.json,
 * tokenizer_config.json or chat_template.jinja, and config.json or an authored
 * text-generation.json. special_tokens_map.json, added_tokens.json, and
 * generation_config.json are optional only when their semantics are already present
 * in the required assets. SdxTextModelStager performs the authoritative content
 * validation and consolidation.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TextModelAssetMap {

    public static final String MODEL = "model";
    public static final String TOKENIZER = "tokenizer";
    public static final String TOKENIZER_CONFIG = "tokenizer_config";
    public static final String SPECIAL_TOKENS_MAP = "special_tokens_map";
    public static final String ADDED_TOKENS = "added_tokens";
    public static final String CHAT_TEMPLATE = "chat_template";
    public static final String GENERATION_CONFIG = "generation_config";
    public static final String MODEL_CONFIG = "model_config";
    public static final String TEXT_GENERATION = "text_generation";

    public static final String TOKENIZER_FILE = "tokenizer.json";
    public static final String TOKENIZER_CONFIG_FILE = "tokenizer_config.json";
    public static final String SPECIAL_TOKENS_MAP_FILE = "special_tokens_map.json";
    public static final String ADDED_TOKENS_FILE = "added_tokens.json";
    public static final String CHAT_TEMPLATE_FILE = "chat_template.jinja";
    public static final String GENERATION_CONFIG_FILE = "generation_config.json";
    public static final String MODEL_CONFIG_FILE = "config.json";
    public static final String TEXT_GENERATION_FILE = "text-generation.json";

    private String model;
    private String tokenizer;
    private String tokenizerConfig;
    private String specialTokensMap;
    private String addedTokens;
    private String chatTemplate;
    private String generationConfig;
    private String modelConfig;
    private String textGeneration;

    /**
     * Return only recognized, non-blank paths using the stable downloader keys.
     */
    public Map<String, String> toFileMap() {
        Map<String, String> files = new LinkedHashMap<>();
        put(files, MODEL, model);
        put(files, TOKENIZER, tokenizer);
        put(files, TOKENIZER_CONFIG, tokenizerConfig);
        put(files, SPECIAL_TOKENS_MAP, specialTokensMap);
        put(files, ADDED_TOKENS, addedTokens);
        put(files, CHAT_TEMPLATE, chatTemplate);
        put(files, GENERATION_CONFIG, generationConfig);
        put(files, MODEL_CONFIG, modelConfig);
        put(files, TEXT_GENERATION, textGeneration);
        return files;
    }

    /**
     * Adapt the legacy generic files map without carrying unknown keys into the SDX contract.
     */
    public static TextModelAssetMap fromFileMap(Map<String, String> files) {
        Map<String, String> safe = files == null ? Map.of() : files;
        return TextModelAssetMap.builder()
                .model(safe.get(MODEL))
                .tokenizer(safe.get(TOKENIZER))
                .tokenizerConfig(safe.get(TOKENIZER_CONFIG))
                .specialTokensMap(safe.get(SPECIAL_TOKENS_MAP))
                .addedTokens(safe.get(ADDED_TOKENS))
                .chatTemplate(safe.get(CHAT_TEMPLATE))
                .generationConfig(safe.get(GENERATION_CONFIG))
                .modelConfig(safe.get(MODEL_CONFIG))
                .textGeneration(safe.get(TEXT_GENERATION))
                .build();
    }

    /**
     * Fill conventional Hugging Face companion paths while preserving explicit paths.
     */
    public TextModelAssetMap withHuggingFaceDefaults() {
        return TextModelAssetMap.builder()
                .model(model)
                .tokenizer(orDefault(tokenizer, TOKENIZER_FILE))
                .tokenizerConfig(orDefault(tokenizerConfig, TOKENIZER_CONFIG_FILE))
                .specialTokensMap(orDefault(specialTokensMap, SPECIAL_TOKENS_MAP_FILE))
                .addedTokens(orDefault(addedTokens, ADDED_TOKENS_FILE))
                .chatTemplate(orDefault(chatTemplate, CHAT_TEMPLATE_FILE))
                .generationConfig(orDefault(generationConfig, GENERATION_CONFIG_FILE))
                .modelConfig(blank(textGeneration)
                        ? orDefault(modelConfig, MODEL_CONFIG_FILE)
                        : modelConfig)
                .textGeneration(textGeneration)
                .build();
    }

    /**
     * Structural requirements checked before download/upload. Content remains SDX-validated.
     */
    public List<String> missingRunnableChatAssets() {
        List<String> missing = new ArrayList<>();
        if (blank(model)) {
            missing.add("model");
        }
        if (blank(tokenizer)) {
            missing.add(TOKENIZER_FILE);
        }
        if (blank(tokenizerConfig) && blank(chatTemplate)) {
            missing.add(TOKENIZER_CONFIG_FILE + " or " + CHAT_TEMPLATE_FILE);
        }
        if (blank(modelConfig) && blank(textGeneration)) {
            missing.add(MODEL_CONFIG_FILE + " or " + TEXT_GENERATION_FILE);
        }
        return List.copyOf(missing);
    }

    public boolean isRunnableChatDeclaration() {
        return missingRunnableChatAssets().isEmpty();
    }

    private static void put(Map<String, String> files, String key, String value) {
        if (!blank(value)) {
            files.put(key, value.trim());
        }
    }

    private static String orDefault(String value, String fallback) {
        return blank(value) ? fallback : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
