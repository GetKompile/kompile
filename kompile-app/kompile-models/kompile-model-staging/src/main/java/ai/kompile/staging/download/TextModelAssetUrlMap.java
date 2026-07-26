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
 * Explicit public HTTPS sources for the canonical runnable-text assets.
 *
 * <p>This is deliberately separate from {@link TextModelAssetMap}: paths in that
 * class are repository-relative, while values here are absolute network
 * locations. Explicit URL values override Hugging Face discovery. The downloader
 * validates every URI, applies per-asset byte limits, writes atomically, and
 * never forwards a Hugging Face token to a different origin.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TextModelAssetUrlMap {
    private String model;
    private String tokenizer;
    private String tokenizerConfig;
    private String specialTokensMap;
    private String addedTokens;
    private String chatTemplate;
    private String generationConfig;
    private String modelConfig;
    private String textGeneration;

    public static TextModelAssetUrlMap fromUrlMap(Map<String, String> urls) {
        Map<String, String> source = urls == null ? Map.of() : urls;
        return TextModelAssetUrlMap.builder()
                .model(source.get(TextModelAssetMap.MODEL))
                .tokenizer(source.get(TextModelAssetMap.TOKENIZER))
                .tokenizerConfig(source.get(TextModelAssetMap.TOKENIZER_CONFIG))
                .specialTokensMap(source.get(TextModelAssetMap.SPECIAL_TOKENS_MAP))
                .addedTokens(source.get(TextModelAssetMap.ADDED_TOKENS))
                .chatTemplate(source.get(TextModelAssetMap.CHAT_TEMPLATE))
                .generationConfig(source.get(TextModelAssetMap.GENERATION_CONFIG))
                .modelConfig(source.get(TextModelAssetMap.MODEL_CONFIG))
                .textGeneration(source.get(TextModelAssetMap.TEXT_GENERATION))
                .build();
    }

    public Map<String, String> toUrlMap() {
        Map<String, String> urls = new LinkedHashMap<>();
        put(urls, TextModelAssetMap.MODEL, model);
        put(urls, TextModelAssetMap.TOKENIZER, tokenizer);
        put(urls, TextModelAssetMap.TOKENIZER_CONFIG, tokenizerConfig);
        put(urls, TextModelAssetMap.SPECIAL_TOKENS_MAP, specialTokensMap);
        put(urls, TextModelAssetMap.ADDED_TOKENS, addedTokens);
        put(urls, TextModelAssetMap.CHAT_TEMPLATE, chatTemplate);
        put(urls, TextModelAssetMap.GENERATION_CONFIG, generationConfig);
        put(urls, TextModelAssetMap.MODEL_CONFIG, modelConfig);
        put(urls, TextModelAssetMap.TEXT_GENERATION, textGeneration);
        return urls;
    }

    public boolean isEmpty() {
        return toUrlMap().isEmpty();
    }

    /**
     * Return the canonical assets that are required to build a runnable offline
     * chat package from independent public HTTPS components.
     */
    public List<String> missingRunnableChatAssets() {
        List<String> missing = new ArrayList<>();
        if (blank(model)) {
            missing.add("GGUF/GGML model URL");
        }
        if (blank(tokenizer)) {
            missing.add(TextModelAssetMap.TOKENIZER_FILE + " URL");
        }
        if (blank(tokenizerConfig) && blank(chatTemplate)) {
            missing.add(TextModelAssetMap.TOKENIZER_CONFIG_FILE + " or "
                    + TextModelAssetMap.CHAT_TEMPLATE_FILE + " URL");
        }
        if (blank(modelConfig) && blank(textGeneration)) {
            missing.add(TextModelAssetMap.MODEL_CONFIG_FILE + " or "
                    + TextModelAssetMap.TEXT_GENERATION_FILE + " URL");
        }
        return missing;
    }

    public boolean isRunnableChatBundle() {
        return missingRunnableChatAssets().isEmpty();
    }

    private static void put(Map<String, String> urls, String key, String value) {
        if (value != null && !value.isBlank()) {
            urls.put(key, value.trim());
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
