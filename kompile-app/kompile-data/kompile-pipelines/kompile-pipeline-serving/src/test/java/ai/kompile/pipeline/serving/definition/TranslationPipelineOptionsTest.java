package ai.kompile.pipeline.serving.definition;

import ai.kompile.core.language.translation.TranslationRequest;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TranslationPipelineOptionsTest {
    @Test
    void usesCoreDefaultsWithoutResolvingOrInventingModelIdentity() {
        Map<String, Object> processor = processor(Map.of("targetLanguage", "fr"));
        processor.put("provider", "configured-provider");
        processor.put("modelId", "manual-model");
        TranslationRequest request = request(processor);
        assertEquals("Bonjour source", request.text());
        assertEquals("und", request.sourceLanguage());
        assertEquals("fr", request.targetLanguage());
        assertEquals(8_000, request.maxCharsPerRequest());
        assertEquals(16_000, request.maxResponseChars());
        assertEquals(20_000_000, request.maxOutputChars());
        assertEquals(TranslationRequest.FailurePolicy.FAIL, request.failurePolicy());
        assertEquals(List.of(), request.preserveTerms());
        assertNull(request.sourceId());
        assertNull(request.provider());
        assertNull(request.modelId());
    }

    @Test
    void preservesExplicitOptionsAndUsesAdapterIdentity() {
        Map<String, Object> options = new LinkedHashMap<>(Map.of(
                "sourceId", "doc-1", "sourceLanguage", "EN-us", "targetLanguage", "FR-ca",
                "maxCharsPerRequest", 512, "maxResponseChars", 1_024, "maxOutputChars", 4_096,
                "domainHint", "medical", "customInstructions", "Keep headings",
                "failurePolicy", "KEEP_ORIGINAL"));
        List<String> terms = new ArrayList<>(List.of(" Kompile ", "Kompile", "SameDiff"));
        options.put("preserveTerms", terms);
        Map<String, Object> processor = processor(options);
        processor.put("modelSource", "chat");
        processor.put("timeoutMinutes", 2);
        processor.put("provider", "configured");
        processor.put("modelId", "configured-model");
        TranslationRequest request = TranslationPipelineOptions.toRequest(processor, "hello", null, "selected", "selected-model");
        terms.clear();
        assertEquals("doc-1", request.sourceId());
        assertEquals("en-us", request.sourceLanguage());
        assertEquals("fr-ca", request.targetLanguage());
        assertEquals(512, request.maxCharsPerRequest());
        assertEquals(1_024, request.maxResponseChars());
        assertEquals(4_096, request.maxOutputChars());
        assertEquals("medical", request.domainHint());
        assertEquals("Keep headings", request.customInstructions());
        assertEquals(List.of("Kompile", "SameDiff"), request.preserveTerms());
        assertEquals(TranslationRequest.FailurePolicy.KEEP_ORIGINAL, request.failurePolicy());
        assertEquals("selected", request.provider());
        assertEquals("selected-model", request.modelId());
    }

    @Test
    void runtimeSourceOverridesConfigurationWithoutSkippingValidation() {
        Map<String, Object> options = new LinkedHashMap<>(Map.of("targetLanguage", "fr", "sourceId", "configured"));
        Map<String, Object> processor = processor(options);
        assertEquals("runtime", TranslationPipelineOptions.toRequest(processor, "", "runtime", null, null).sourceId());
        options.put("sourceId", 42);
        assertThrows(IllegalArgumentException.class,
                () -> TranslationPipelineOptions.toRequest(processor, "", "runtime", null, null));
    }

    @Test
    void requiresAnExplicitConcreteTargetLanguage() {
        assertThrows(IllegalArgumentException.class, () -> request(processor(Map.of())));
        for (Object target : Arrays.asList(null, "", "  ", "und", "auto", "*", "not a locale", 17, List.of("fr"))) {
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("targetLanguage", target);
            assertThrows(IllegalArgumentException.class, () -> request(processor(options)), String.valueOf(target));
        }
    }

    @Test
    void rejectsMalformedOptionalStringsRatherThanCoercingThem() {
        for (String field : List.of("sourceId", "sourceLanguage", "domainHint", "customInstructions")) {
            for (Object value : List.of(" ", 42, List.of("text"), Map.of("value", "text"))) {
                Map<String, Object> options = new LinkedHashMap<>(Map.of("targetLanguage", "fr"));
                options.put(field, value);
                assertThrows(IllegalArgumentException.class, () -> request(processor(options)), field);
            }
        }
    }

    @Test
    void rejectsInvalidBudgetsWithoutNumericCoercionOrOverflow() {
        for (String field : List.of("maxCharsPerRequest", "maxResponseChars", "maxOutputChars")) {
            for (Object value : Arrays.asList(null, "100", 0, -1, 1.5, true, Double.NaN,
                    new BigInteger("999999999999999999999"), 20_000_001)) {
                Map<String, Object> options = new LinkedHashMap<>(Map.of("targetLanguage", "fr"));
                options.put(field, value);
                assertThrows(IllegalArgumentException.class, () -> request(processor(options)), field + "=" + value);
            }
        }
        for (String field : List.of("maxCharsPerRequest", "maxResponseChars")) {
            assertThrows(IllegalArgumentException.class,
                    () -> request(processor(Map.of("targetLanguage", "fr", field, 1_000_001))));
        }
    }

    @Test
    void rejectsInvalidTimeoutAndModelSelectors() {
        for (Object timeout : Arrays.asList(null, "2", 0, -1, 1.25, 1_441, Long.MAX_VALUE)) {
            Map<String, Object> processor = processor(Map.of("targetLanguage", "fr"));
            processor.put("timeoutMinutes", timeout);
            assertThrows(IllegalArgumentException.class, () -> request(processor));
        }
        for (String field : List.of("provider", "modelId")) {
            Map<String, Object> processor = processor(Map.of("targetLanguage", "fr"));
            processor.put(field, 17);
            assertThrows(IllegalArgumentException.class, () -> request(processor));
        }
        Map<String, Object> processor = processor(Map.of("targetLanguage", "fr"));
        processor.put("modelSource", "local");
        assertThrows(IllegalArgumentException.class, () -> request(processor));
    }

    @Test
    void rejectsUnknownOptionsCredentialsAndGenericMediaOrPromptOptions() {
        for (String field : List.of("apiKey", "endpointUrl", "prompt", "systemPrompt", "jsonSchema", "maxPages", "filePath")) {
            Map<String, Object> processor = processor(Map.of("targetLanguage", "fr"));
            processor.put(field, "not-allowed");
            assertThrows(IllegalArgumentException.class, () -> request(processor), field);
        }
        for (String field : List.of("targetLangauge", "apiKey", "provider", "modelId", "maxInputChars", "outputFormat")) {
            assertThrows(IllegalArgumentException.class,
                    () -> request(processor(Map.of("targetLanguage", "fr", field, "not-allowed"))), field);
        }
    }

    @Test
    void rejectsMalformedPreservedTermsAndFailurePolicy() {
        for (Object terms : Arrays.asList(null, "Kompile", List.of(1), List.of(" "), Arrays.asList("Kompile", null))) {
            Map<String, Object> options = new LinkedHashMap<>(Map.of("targetLanguage", "fr"));
            options.put("preserveTerms", terms);
            assertThrows(IllegalArgumentException.class, () -> request(processor(options)));
        }
        for (Object policy : Arrays.asList(null, "", "keep_original", "CONTINUE", true)) {
            Map<String, Object> options = new LinkedHashMap<>(Map.of("targetLanguage", "fr"));
            options.put("failurePolicy", policy);
            assertThrows(IllegalArgumentException.class, () -> request(processor(options)));
        }
    }

    @Test
    void onlyRecognizesTheExplicitTranslationOperationAndObject() {
        assertFalse(TranslationPipelineOptions.isTranslation(null));
        assertFalse(TranslationPipelineOptions.isTranslation(Map.of("operation", "text")));
        assertThrows(IllegalArgumentException.class, () -> request(null));
        assertThrows(IllegalArgumentException.class, () -> request(Map.of("type", "CHAT_MODEL", "operation", "text")));
        assertThrows(IllegalArgumentException.class, () -> request(Map.of("type", "LOCAL_MODEL", "operation", "translation")));
        for (Object options : Arrays.asList(null, "fr", List.of(Map.of("targetLanguage", "fr")))) {
            Map<String, Object> processor = processor(Map.of("targetLanguage", "fr"));
            processor.put("translation", options);
            assertThrows(IllegalArgumentException.class, () -> request(processor));
        }
    }

    @Test
    void leavesBlankTextAndSameLanguageDecisionsToCoreExecution() {
        TranslationRequest request = TranslationPipelineOptions.toRequest(
                processor(Map.of("sourceLanguage", "en", "targetLanguage", "en")), " \r\n", null, null, null);
        assertEquals(" \r\n", request.text());
        assertEquals(request.sourceLanguage(), request.targetLanguage());
    }

    private static Map<String, Object> processor(Map<String, Object> options) {
        return new LinkedHashMap<>(Map.of("type", "CHAT_MODEL", "operation", "translation", "translation", options));
    }

    private static TranslationRequest request(Map<String, Object> processor) {
        return TranslationPipelineOptions.toRequest(processor, "Bonjour source", null, null, null);
    }
}
