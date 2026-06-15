/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.langdetect;

import ai.kompile.modelmanager.KompileModelManager;
import ai.kompile.modelmanager.ModelDescriptor;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import opennlp.tools.langdetect.Language;
import opennlp.tools.langdetect.LanguageDetectorModel;
import opennlp.tools.langdetect.ThreadSafeLanguageDetectorME;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Language detector using the OpenNLP 183-language model.
 *
 * <p>Thread-safe via {@link ThreadSafeLanguageDetectorME}. The model is
 * downloaded on first use via {@link KompileModelManager} and cached
 * at {@code ~/.kompile/models/opennlp/langdetect/langdetect-183.bin}.</p>
 *
 * <p>When disabled or when detection fails, all methods return the
 * configured fallback language (default: {@code "en"}).</p>
 */
@Slf4j
@Component("openNLPLanguageDetector")
public class OpenNLPLanguageDetector {

    private final KompileModelManager modelManager;
    private final LanguageDetectionConfigService configService;
    private volatile ThreadSafeLanguageDetectorME detector;
    private volatile boolean modelLoadFailed = false;

    @Autowired
    public OpenNLPLanguageDetector(@Autowired(required = false) LanguageDetectionConfigService configService) {
        this.modelManager = new KompileModelManager();
        this.configService = configService;
    }

    @PostConstruct
    public void init() {
        if (configService != null && !configService.isEnabled()) {
            log.info("Language detection is disabled via config");
            return;
        }
        try {
            loadModel();
        } catch (Exception e) {
            log.warn("Failed to load OpenNLP language detection model at startup: {}. " +
                    "Will retry on first use.", e.getMessage());
        }
    }

    private synchronized void loadModel() {
        if (detector != null) return;
        if (modelLoadFailed) return;

        ModelDescriptor descriptor = LangDetectModelConstants.createLangDetectModelDescriptor();
        try {
            Path modelPath = modelManager.ensureModelAvailable(descriptor);
            if (!Files.exists(modelPath) || !Files.isRegularFile(modelPath)) {
                throw new RuntimeException("Model file not found at: " + modelPath);
            }

            log.info("Loading OpenNLP language detection model from: {}", modelPath);
            try (InputStream is = Files.newInputStream(modelPath)) {
                LanguageDetectorModel model = new LanguageDetectorModel(is);
                detector = new ThreadSafeLanguageDetectorME(model);
            }
            log.info("OpenNLP language detection model loaded successfully (183 languages)");
        } catch (Exception e) {
            modelLoadFailed = true;
            log.error("Failed to load OpenNLP language detection model: {}", e.getMessage());
        }
    }

    /**
     * Detects the language of the given text.
     *
     * @param text the text to analyze
     * @return ISO 639-1/639-3 language code (e.g., "en", "de", "zh")
     */
    public String detectLanguage(String text) {
        if (text == null || text.isBlank()) {
            return getFallbackLanguage();
        }
        ensureModel();
        if (detector == null) {
            return getFallbackLanguage();
        }

        String sample = truncateForDetection(text);
        try {
            Language bestLanguage = detector.predictLanguage(sample);
            double confidence = bestLanguage.getConfidence();
            double threshold = configService != null
                    ? configService.getConfig().getMinConfidenceThreshold()
                    : 0.50;

            if (confidence < threshold) {
                log.debug("Language detection confidence {} below threshold {} for best guess '{}', " +
                        "falling back to '{}'", confidence, threshold, bestLanguage.getLang(), getFallbackLanguage());
                return getFallbackLanguage();
            }
            return bestLanguage.getLang();
        } catch (Exception e) {
            log.warn("Language detection failed: {}", e.getMessage());
            return getFallbackLanguage();
        }
    }

    /**
     * Detects the language and returns the confidence score.
     *
     * @param text the text to analyze
     * @return confidence score between 0.0 and 1.0
     */
    public double detectLanguageConfidence(String text) {
        if (text == null || text.isBlank()) {
            return 0.0;
        }
        ensureModel();
        if (detector == null) {
            return 0.0;
        }

        String sample = truncateForDetection(text);
        try {
            Language bestLanguage = detector.predictLanguage(sample);
            return bestLanguage.getConfidence();
        } catch (Exception e) {
            log.warn("Language confidence detection failed: {}", e.getMessage());
            return 0.0;
        }
    }

    /**
     * Detects the top N most likely languages for the given text.
     *
     * @param text the text to analyze
     * @param n    maximum number of candidates to return
     * @return ranked list of language scores, best first
     */
    public List<LanguageScore> detectTopN(String text, int n) {
        List<LanguageScore> results = new ArrayList<>();
        if (text == null || text.isBlank() || n <= 0) {
            return results;
        }
        ensureModel();
        if (detector == null) {
            return results;
        }

        String sample = truncateForDetection(text);
        try {
            Language[] languages = detector.predictLanguages(sample);
            int limit = Math.min(n, languages.length);
            for (int i = 0; i < limit; i++) {
                results.add(new LanguageScore(languages[i].getLang(), languages[i].getConfidence()));
            }
        } catch (Exception e) {
            log.warn("Top-N language detection failed: {}", e.getMessage());
        }
        return results;
    }

    /**
     * Returns true if the language detection model is loaded and ready.
     */
    public boolean isReady() {
        return detector != null;
    }

    private void ensureModel() {
        if (detector == null && !modelLoadFailed) {
            loadModel();
        }
    }

    private String truncateForDetection(String text) {
        int maxChars = configService != null
                ? configService.getConfig().getMaxCharsForDetection()
                : 2000;
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    private String getFallbackLanguage() {
        return configService != null
                ? configService.getConfig().getFallbackLanguage()
                : "en";
    }

    /**
     * A detected language with its confidence score.
     */
    public record LanguageScore(String language, double confidence) {
    }
}
