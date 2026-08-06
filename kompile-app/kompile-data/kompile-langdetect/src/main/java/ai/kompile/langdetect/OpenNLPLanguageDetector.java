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
import opennlp.tools.langdetect.LanguageDetectorContextGenerator;
import opennlp.tools.langdetect.LanguageDetectorFactory;
import opennlp.tools.ml.model.MaxentModel;
import opennlp.tools.util.model.GenericModelSerializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Language detector using the OpenNLP 183-language model.
 *
 * <p>Thread-safe via a per-thread OpenNLP context generator over an immutable
 * Maxent model. The model is downloaded on first use via {@link KompileModelManager} and cached
 * at {@code ~/.kompile/models/opennlp/langdetect/langdetect-183.bin}.</p>
 *
     * <p>When disabled or when detection fails, language detection returns the
     * configured fallback language (default: {@code "und"}).</p>
 */
@Slf4j
@Component("openNLPLanguageDetector")
public class OpenNLPLanguageDetector {

    private static final String UNDETERMINED_LANGUAGE = "und";
    private static final long MODEL_LOAD_RETRY_DELAY_MS = 30_000L;
    private static final int MAX_MODEL_ENTRY_BYTES = 128 * 1024 * 1024;

    private final KompileModelManager modelManager;
    private final LanguageDetectionConfigService configService;
    private volatile NativeSafeLanguageDetector detector;
    private volatile long modelLoadFailedAt;

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
        if (modelLoadFailedAt > 0
                && System.currentTimeMillis() - modelLoadFailedAt < MODEL_LOAD_RETRY_DELAY_MS) {
            return;
        }

        ModelDescriptor descriptor = LangDetectModelConstants.createLangDetectModelDescriptor();
        try {
            Path modelPath = modelManager.ensureModelAvailable(descriptor);
            if (!Files.exists(modelPath) || !Files.isRegularFile(modelPath)) {
                throw new RuntimeException("Model file not found at: " + modelPath);
            }

            log.info("Loading OpenNLP language detection model from: {}", modelPath);
            try (InputStream is = Files.newInputStream(modelPath)) {
                detector = loadModelArchive(is);
            }
            modelLoadFailedAt = 0L;
            log.info("OpenNLP language detection model loaded successfully (183 languages)");
        } catch (Exception e) {
            modelLoadFailedAt = System.currentTimeMillis();
            log.error("Failed to load OpenNLP language detection model", e);
        }
    }

    /**
     * Loads the standard OpenNLP model ZIP without constructing a BaseModel.
     * BaseModel always recreates its tool factory through ExtensionLoader, even
     * when callers pass an already-created factory. Keeping the Maxent model and
     * context generator explicit avoids that reflective path in native images.
     */
    static NativeSafeLanguageDetector loadModelArchive(InputStream inputStream) throws IOException {
        boolean manifestPresent = false;
        byte[] modelBytes = null;

        try (ZipInputStream zip = new ZipInputStream(inputStream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if ("manifest.properties".equals(name)) {
                    manifestPresent = true;
                } else if ("langdetect.model".equals(name)) {
                    modelBytes = readBoundedEntry(zip, name);
                }
                zip.closeEntry();
            }
        }

        if (!manifestPresent) {
            throw new IOException("OpenNLP language model is missing manifest.properties");
        }
        if (modelBytes == null) {
            throw new IOException("OpenNLP language model is missing langdetect.model");
        }

        MaxentModel maxentModel = new GenericModelSerializer()
                .create(new ByteArrayInputStream(modelBytes));
        return new NativeSafeLanguageDetector(maxentModel);
    }

    private static byte[] readBoundedEntry(ZipInputStream zip, String entryName) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = zip.read(buffer)) != -1) {
            total += read;
            if (total > MAX_MODEL_ENTRY_BYTES) {
                throw new IOException("OpenNLP model entry exceeds "
                        + MAX_MODEL_ENTRY_BYTES + " bytes: " + entryName);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    static final class NativeSafeLanguageDetector {
        private final MaxentModel model;
        private final ThreadLocal<LanguageDetectorContextGenerator> contextGenerator =
                ThreadLocal.withInitial(() -> new LanguageDetectorFactory().getContextGenerator());

        NativeSafeLanguageDetector(MaxentModel model) {
            this.model = Objects.requireNonNull(model, "model");
        }

        Language predictLanguage(CharSequence text) {
            Language[] languages = predictLanguages(text);
            return languages.length == 0
                    ? new Language(UNDETERMINED_LANGUAGE, 0.0)
                    : languages[0];
        }

        Language[] predictLanguages(CharSequence text) {
            CharSequence[] contexts = contextGenerator.get().getContext(text);
            Set<String> uniqueFeatures = new HashSet<>(contexts.length);
            for (CharSequence context : contexts) {
                uniqueFeatures.add(context.toString());
            }

            String[] features = uniqueFeatures.toArray(String[]::new);
            float[] weights = new float[features.length];
            Arrays.fill(weights, 1.0f);
            double[] probabilities = model.eval(features, weights);

            Language[] languages = new Language[probabilities.length];
            for (int i = 0; i < probabilities.length; i++) {
                languages[i] = new Language(model.getOutcome(i), probabilities[i]);
            }
            Arrays.sort(languages, (left, right) ->
                    Double.compare(right.getConfidence(), left.getConfidence()));
            return languages;
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
        if (detector == null) {
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
        String fallback = configService != null
                ? configService.getConfig().getFallbackLanguage()
                : UNDETERMINED_LANGUAGE;
        String normalized = normalizeLanguageCode(fallback);
        return normalized == null ? UNDETERMINED_LANGUAGE : normalized;
    }

    private String normalizeLanguageCode(String language) {
        if (language == null || language.isBlank()) {
            return null;
        }
        return language.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }

    /**
     * A detected language with its confidence score.
     */
    public record LanguageScore(String language, double confidence) {
    }
}
