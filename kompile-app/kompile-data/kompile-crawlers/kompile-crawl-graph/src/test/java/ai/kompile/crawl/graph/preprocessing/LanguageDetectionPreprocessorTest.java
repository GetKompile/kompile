/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 */
package ai.kompile.crawl.graph.preprocessing;

import ai.kompile.core.crawl.graph.PreprocessingConfig;
import ai.kompile.core.language.LanguageMetadata;
import ai.kompile.core.language.LanguageSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageDetectionPreprocessorTest {

    private final LanguageDetectionPreprocessor preprocessor = new LanguageDetectionPreprocessor();

    private static PreprocessingConfig config() {
        return PreprocessingConfig.builder()
                .enabled(true)
                .languageDetection(PreprocessingConfig.LanguageDetectionConfig.builder()
                        .enabled(true)
                        .minTextLength(10)
                        .build())
                .build();
    }

    @Test
    void detectsJapaneseAndWritesCanonicalAliases() {
        Document document = new Document("これは日本語の文章です。売上予測とチャネル在庫についての会議メモです。追加の説明も含みます。");

        List<Document> result = preprocessor.process(List.of(document), config());

        assertThat(result).hasSize(1);
        assertThat(LanguageMetadata.canonicalLanguage(result.get(0).getMetadata())).isEqualTo("ja");
        assertThat(result.get(0).getMetadata())
                .containsEntry(LanguageMetadata.LANGUAGE, "ja")
                .containsEntry(LanguageMetadata.DETECTED_LANGUAGE, "ja")
                .containsEntry(LanguageMetadata.LANGUAGE_SOURCE, LanguageMetadata.SOURCE_DETECTED);
        assertThat(LanguageMetadata.languageConfidence(result.get(0).getMetadata())).isPresent();
    }

    @Test
    void ambiguousLatinTextDoesNotDefaultToEnglish() {
        Document document = new Document("qzxv qzxv qzxv qzxv qzxv qzxv qzxv qzxv qzxv qzxv qzxv qzxv");

        List<Document> result = preprocessor.process(List.of(document), config());

        assertThat(result).hasSize(1);
        assertThat(LanguageMetadata.canonicalLanguage(result.get(0).getMetadata()))
                .isEqualTo(LanguageSupport.UNDETERMINED_LANGUAGE);
        assertThat(result.get(0).getMetadata())
                .containsEntry(LanguageMetadata.LANGUAGE_SOURCE, LanguageMetadata.SOURCE_UNDETERMINED);
    }

    @Test
    void existingLanguageMetadataIsNormalizedAndAliased() {
        Document document = new Document("already classified document with enough text to pass length checks");
        document.getMetadata().put(LanguageMetadata.LANGUAGE, "EN_us");

        List<Document> result = preprocessor.process(List.of(document), config());

        assertThat(LanguageMetadata.canonicalLanguage(result.get(0).getMetadata())).isEqualTo("en-us");
        assertThat(result.get(0).getMetadata())
                .containsEntry(LanguageMetadata.DETECTED_LANGUAGE, "en-us")
                .containsEntry(LanguageMetadata.LANGUAGE_SOURCE, LanguageMetadata.SOURCE_HEADER);
    }
}
