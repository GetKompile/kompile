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
package ai.kompile.core.language.translation;

import ai.kompile.core.language.translation.TranslationRequest.FailurePolicy;
import ai.kompile.core.language.translation.TranslationResult.SegmentStatus;
import ai.kompile.core.language.translation.TranslationResult.Status;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationServiceTest {

    @Test
    void translatesGermanAndJapaneseWithExactSelectionProvenance() throws Exception {
        for (String source : List.of("de", "ja")) {
            TranslationRequest request = TranslationRequest.builder()
                    .text("source text")
                    .sourceLanguage(source)
                    .targetLanguage("en")
                    .provider("host-chat")
                    .modelId("exact-model")
                    .build();
            TranslationResult result = new TranslationService((received, system, user) -> {
                assertEquals("host-chat", received.provider());
                assertEquals("exact-model", received.modelId());
                assertTrue(system.contains("faithful"));
                assertTrue(user.contains("The source locale is " + source + "."));
                assertTrue(user.contains("Translate the document segment below to locale en."));
                return "translated " + source;
            }).translate(request);

            assertEquals(Status.TRANSLATED, result.status());
            assertEquals("translated " + source, result.outputText());
            assertEquals(source, result.sourceLanguage());
            assertEquals("en", result.targetLanguage());
            assertEquals("host-chat", result.provider());
            assertEquals("exact-model", result.modelId());
        }
    }

    @Test
    void autoSourceUsesAnExplicitAutoSourcePromptWithoutClaimingDetection() throws Exception {
        AtomicReference<String> seenPrompt = new AtomicReference<>();
        TranslationRequest request = TranslationRequest.builder()
                .text("Bonjour le monde")
                .targetLanguage("en")
                .build();

        TranslationResult result = new TranslationService((received, system, user) -> {
            assertEquals("und", received.sourceLanguage());
            seenPrompt.set(user);
            return "Hello world";
        }).translate(request);

        assertEquals(Status.TRANSLATED, result.status());
        assertEquals("und", result.sourceLanguage());
        assertTrue(seenPrompt.get().contains("source locale is unknown"));
        assertFalse(seenPrompt.get().contains("source locale is en"));
    }

    @Test
    void emptyAndSameLanguageInputsSkipWithoutCallingGenerator() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TranslationService service = new TranslationService((request, system, user) -> {
            calls.incrementAndGet();
            return "unexpected";
        });

        TranslationResult empty = service.translate(TranslationRequest.builder()
                .text(" \n\t")
                .targetLanguage("en")
                .build());
        assertEquals(Status.SKIPPED, empty.status());
        assertEquals(" \n\t", empty.outputText());

        TranslationResult same = service.translate(TranslationRequest.builder()
                .text("regional text")
                .sourceLanguage("JA_jp")
                .targetLanguage("ja-JP")
                .build());
        assertEquals(Status.SKIPPED, same.status());
        assertEquals("regional text", same.outputText());
        assertEquals(0, calls.get());
        assertEquals(SegmentStatus.SKIPPED, same.segments().get(0).status());
    }

    @Test
    void invalidTargetAndBoundsAreRejectedBeforeAnyCallback() {
        AtomicInteger calls = new AtomicInteger();
        TranslationService service = new TranslationService((request, system, user) -> {
            calls.incrementAndGet();
            return "never";
        });

        assertThrows(IllegalArgumentException.class, () -> TranslationRequest.builder()
                .text("x").build());
        assertThrows(IllegalArgumentException.class, () -> TranslationRequest.builder()
                .text("x").targetLanguage("*").build());
        assertThrows(IllegalArgumentException.class, () -> TranslationRequest.builder()
                .text("x").targetLanguage("und").build());
        assertThrows(IllegalArgumentException.class, () -> TranslationRequest.builder()
                .text("x").targetLanguage("en").maxCharsPerRequest(0).build());
        assertThrows(IllegalArgumentException.class, () -> TranslationRequest.builder()
                .text("x").targetLanguage("en").maxResponseChars(0).build());
        assertThrows(IllegalArgumentException.class, () -> TranslationRequest.builder()
                .text("x").targetLanguage("en").maxOutputChars(0).build());
        assertThrows(IllegalArgumentException.class, () -> TranslationRequest.builder()
                .text("x").targetLanguage("en")
                .maxOutputChars(TranslationRequest.MAX_OUTPUT_CHARS + 1).build());
        assertThrows(IllegalArgumentException.class, () -> TranslationRequest.builder()
                .text("x".repeat(TranslationRequest.MAX_SOURCE_CHARS + 1))
                .targetLanguage("en").build());
        assertEquals(0, calls.get());
        assertNotNull(service);
    }

    @Test
    void jacksonRoundTripsAllRequestOptionsAndBuilderDefaults() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TranslationRequest original = TranslationRequest.builder()
                .text("source text")
                .sourceId("document-1")
                .sourceLanguage("de")
                .targetLanguage("en")
                .provider("host-chat")
                .modelId("exact-model")
                .maxCharsPerRequest(123)
                .maxResponseChars(456)
                .maxOutputChars(789)
                .preserveTerms(List.of("Kompile", "SDK"))
                .domainHint("technical")
                .customInstructions("Use the established glossary.")
                .failurePolicy(FailurePolicy.KEEP_ORIGINAL)
                .build();

        String json = mapper.writeValueAsString(original);
        TranslationRequest restored = mapper.readValue(json, TranslationRequest.class);

        assertEquals(original.fingerprint(), restored.fingerprint());
        assertEquals(original.text(), restored.text());
        assertEquals(original.sourceId(), restored.sourceId());
        assertEquals(original.sourceLanguage(), restored.sourceLanguage());
        assertEquals(original.targetLanguage(), restored.targetLanguage());
        assertEquals(original.provider(), restored.provider());
        assertEquals(original.modelId(), restored.modelId());
        assertEquals(original.maxCharsPerRequest(), restored.maxCharsPerRequest());
        assertEquals(original.maxResponseChars(), restored.maxResponseChars());
        assertEquals(original.maxOutputChars(), restored.maxOutputChars());
        assertEquals(original.preserveTerms(), restored.preserveTerms());
        assertEquals(original.domainHint(), restored.domainHint());
        assertEquals(original.customInstructions(), restored.customInstructions());
        assertEquals(original.failurePolicy(), restored.failurePolicy());

        TranslationRequest defaults = mapper.readValue(
                "{\"text\":\"x\",\"targetLanguage\":\"en\"}", TranslationRequest.class);
        assertEquals(TranslationRequest.DEFAULT_MAX_CHARS_PER_REQUEST,
                defaults.maxCharsPerRequest());
        assertEquals(TranslationRequest.DEFAULT_MAX_RESPONSE_CHARS, defaults.maxResponseChars());
        assertEquals(TranslationRequest.DEFAULT_MAX_OUTPUT_CHARS, defaults.maxOutputChars());
        assertEquals("und", defaults.sourceLanguage());
        assertEquals(FailurePolicy.FAIL, defaults.failurePolicy());
        assertEquals(List.of(), defaults.preserveTerms());
    }

    @Test
    void jacksonDeserializationRetainsValidationAndResultRoundTrips() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(JsonProcessingException.class, () -> mapper.readValue(
                "{\"text\":\"x\",\"targetLanguage\":\"en\",\"maxCharsPerRequest\":0}",
                TranslationRequest.class));
        assertThrows(JsonProcessingException.class, () -> mapper.readValue(
                "{\"text\":\"x\",\"targetLanguage\":\"en\",\"maxResponseChars\":1000001}",
                TranslationRequest.class));
        assertThrows(JsonProcessingException.class, () -> mapper.readValue(
                "{\"text\":\"x\",\"targetLanguage\":\"en\",\"maxOutputChars\":0}",
                TranslationRequest.class));
        assertThrows(JsonProcessingException.class, () -> mapper.readValue(
                "{\"text\":\"x\",\"targetLanguage\":\"en\",\"maxOutputChars\":20000001}",
                TranslationRequest.class));

        TranslationResult original = new TranslationResult(Status.PARTIAL, "translated", "source-1",
                "de", "en", "provider", "model", "partial",
                List.of(new TranslationResult.SegmentResult(0, 0, 6,
                        SegmentStatus.TRANSLATED, null)), "fingerprint");
        String json = mapper.writeValueAsString(original);
        assertEquals(original, mapper.readValue(json, TranslationResult.class));
    }

    @Test
    void segmentationPreservesOffsetsWhitespaceOrderAndSurrogateBoundaries() throws Exception {
        String source = "ab😀cd\n\n第三段\nend";
        TranslationRequest request = TranslationRequest.builder()
                .text(source)
                .sourceLanguage("de")
                .targetLanguage("en")
                .maxCharsPerRequest(3)
                .build();

        TranslationResult result = new TranslationService((received, system, user) -> segmentFromPrompt(user))
                .translate(request);

        assertEquals(Status.TRANSLATED, result.status());
        assertEquals(source, result.outputText());
        assertTrue(result.segments().size() > 1);
        int cursor = 0;
        for (var segment : result.segments()) {
            assertEquals(cursor, segment.sourceStart());
            assertTrue(segment.sourceEnd() > segment.sourceStart());
            if (segment.sourceEnd() < source.length()) {
                assertFalse(Character.isHighSurrogate(source.charAt(segment.sourceEnd() - 1))
                        && Character.isLowSurrogate(source.charAt(segment.sourceEnd())));
            }
            cursor = segment.sourceEnd();
        }
        assertEquals(source.length(), cursor);
    }

    @Test
    void trimmedModelOutputRestoresWhitespaceBoundariesAndSkipsWhitespaceOnlySpans() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> bodies = new ArrayList<>();
        TranslationRequest request = TranslationRequest.builder()
                .text("aa  bb")
                .sourceLanguage("de")
                .targetLanguage("en")
                .maxCharsPerRequest(2)
                .build();

        TranslationResult result = new TranslationService((received, system, user) -> {
            calls.incrementAndGet();
            String body = segmentFromPrompt(user);
            bodies.add(body);
            return body.strip().toUpperCase(Locale.ROOT);
        }).translate(request);

        assertEquals(Status.TRANSLATED, result.status());
        assertEquals("AA  BB", result.outputText());
        assertEquals(2, calls.get());
        assertEquals(List.of("aa", "bb"), bodies);
        assertEquals(List.of(SegmentStatus.TRANSLATED, SegmentStatus.SKIPPED,
                SegmentStatus.TRANSLATED), result.segments().stream().map(s -> s.status()).toList());
    }

    @Test
    void trimmedModelOutputPreservesDoubleNewlinesAndCrLfBoundaries() throws Exception {
        String source = "  aa  \n\n  bb  \r\n";
        List<String> bodies = new ArrayList<>();
        TranslationRequest request = TranslationRequest.builder()
                .text(source)
                .sourceLanguage("de")
                .targetLanguage("en")
                .maxCharsPerRequest(8)
                .build();

        TranslationResult result = new TranslationService((received, system, user) -> {
            String body = segmentFromPrompt(user);
            bodies.add(body);
            return body.strip().toUpperCase(Locale.ROOT);
        }).translate(request);

        assertEquals(Status.TRANSLATED, result.status());
        assertEquals("  AA  \n\n  BB  \r\n", result.outputText());
        assertEquals(List.of("aa", "bb"), bodies);
        assertEquals(source.length(), result.segments().get(result.segments().size() - 1).sourceEnd());
    }

    @Test
    void surrogateCodePointMayExceedUtf16SegmentBoundWithoutBeingSplit() throws Exception {
        String source = "😀x";
        TranslationRequest request = TranslationRequest.builder()
                .text(source)
                .sourceLanguage("de")
                .targetLanguage("en")
                .maxCharsPerRequest(1)
                .build();

        TranslationResult result = new TranslationService((received, system, user) -> {
            String body = segmentFromPrompt(user);
            assertFalse(body.isEmpty());
            return body.strip();
        }).translate(request);

        assertEquals(Status.TRANSLATED, result.status());
        assertEquals(source, result.outputText());
        assertEquals(List.of(0, 2), result.segments().stream().map(s -> s.sourceStart()).toList());
        assertEquals(List.of(2, 3), result.segments().stream().map(s -> s.sourceEnd()).toList());
    }

    @Test
    void segmentCountSafetyLimitCountsActualBoundariesAndBoundsOverflow() throws Exception {
        for (int length : List.of(9_999, 10_000)) {
            AtomicInteger calls = new AtomicInteger();
            TranslationRequest request = request("x".repeat(length)).toBuilder()
                    .maxCharsPerRequest(1)
                    .build();

            TranslationResult result = new TranslationService((received, system, user) -> {
                calls.incrementAndGet();
                return "x";
            }).translate(request);

            assertEquals(Status.TRANSLATED, result.status());
            assertEquals(length, calls.get());
            assertEquals(length, result.segments().size());
        }

        for (FailurePolicy policy : FailurePolicy.values()) {
            AtomicInteger calls = new AtomicInteger();
            TranslationRequest request = request("x".repeat(10_001)).toBuilder()
                    .maxCharsPerRequest(1)
                    .failurePolicy(policy)
                    .build();

            TranslationResult result = new TranslationService((received, system, user) -> {
                calls.incrementAndGet();
                return "x";
            }).translate(request);

            assertEquals(Status.FAILED, result.status());
            assertNull(result.outputText());
            assertTrue(result.reason().contains("maximum segment count of 10000"));
            assertTrue(result.segments().isEmpty());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void segmentationNeverSplitsCrLfPairWhenMaxCharsIsOne() throws Exception {
        String source = "a\r\nb";
        AtomicInteger calls = new AtomicInteger();
        TranslationRequest request = request(source).toBuilder()
                .maxCharsPerRequest(1)
                .build();

        TranslationResult result = new TranslationService((received, system, user) -> {
            calls.incrementAndGet();
            return segmentFromPrompt(user);
        }).translate(request);

        assertEquals(Status.TRANSLATED, result.status());
        assertEquals(source, result.outputText());
        assertEquals(List.of(0, 1, 3), result.segments().stream()
                .map(s -> s.sourceStart()).toList());
        assertEquals(List.of(1, 3, 4), result.segments().stream()
                .map(s -> s.sourceEnd()).toList());
        assertEquals(2, calls.get());
    }

    @Test
    void toBuilderPreservesNonDefaultMaxOutputChars() {
        TranslationRequest request = request("text").toBuilder()
                .maxOutputChars(1234)
                .build();

        assertEquals(1234, request.toBuilder().build().maxOutputChars());
    }

    @Test
    void promptCarriesTermsDomainCustomInstructionsAndKeepsSegmentOrder() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> prompts = new ArrayList<>();
        AtomicReference<String> firstPrompt = new AtomicReference<>();
        TranslationRequest request = TranslationRequest.builder()
                .text("first second")
                .sourceLanguage("de")
                .targetLanguage("en")
                .provider("managed")
                .modelId("gpt-5.6-luna")
                .maxCharsPerRequest(6)
                .preserveTerms(List.of("Kompile", "Kompile"))
                .domainHint("technical")
                .customInstructions("Prefer the established glossary.")
                .build();

        TranslationResult result = new TranslationService((received, system, user) -> {
            prompts.add(user);
            if (calls.getAndIncrement() == 0) {
                firstPrompt.set(user);
            }
            return "T";
        }).translate(request);

        assertEquals(Status.TRANSLATED, result.status());
        assertEquals(2, calls.get());
        assertTrue(firstPrompt.get().contains("Kompile"));
        assertTrue(firstPrompt.get().contains("technical"));
        assertTrue(firstPrompt.get().contains("Prefer the established glossary."));
        assertTrue(prompts.get(0).contains("first"));
        assertTrue(prompts.get(1).contains("second"));
        assertEquals(List.of(0, 1), result.segments().stream().map(s -> s.index()).toList());
    }

    @Test
    void blankNullAndExceptionResponsesRespectBothFailurePolicies() throws Exception {
        for (FailurePolicy policy : FailurePolicy.values()) {
            TranslationRequest request = request("text").toBuilder()
                    .failurePolicy(policy)
                    .build();
            TranslationResult blank = new TranslationService((received, system, user) -> " \n")
                    .translate(request);
            assertEquals(Status.FAILED, blank.status());
            assertEquals(policy == FailurePolicy.KEEP_ORIGINAL ? "text" : null, blank.outputText());

            TranslationResult nullResult = new TranslationService((received, system, user) -> null)
                    .translate(request);
            assertEquals(Status.FAILED, nullResult.status());
            assertEquals(policy == FailurePolicy.KEEP_ORIGINAL ? "text" : null, nullResult.outputText());

            TranslationResult exception = new TranslationService((received, system, user) -> {
                throw new IllegalStateException("backend failure");
            }).translate(request);
            assertEquals(Status.FAILED, exception.status());
            assertEquals(policy == FailurePolicy.KEEP_ORIGINAL ? "text" : null, exception.outputText());
        }
    }

    @Test
    void failurePoliciesReportTruthfulFailureAndPartialStatuses() throws Exception {
        AtomicInteger failOnSecond = new AtomicInteger();
        TranslationRequest partialRequest = TranslationRequest.builder()
                .text("abcd")
                .sourceLanguage("de")
                .targetLanguage("en")
                .maxCharsPerRequest(2)
                .failurePolicy(FailurePolicy.KEEP_ORIGINAL)
                .build();
        TranslationResult partial = new TranslationService((request, system, user) ->
                failOnSecond.getAndIncrement() == 0 ? "AB" : null).translate(partialRequest);

        assertEquals(Status.PARTIAL, partial.status());
        assertEquals("ABcd", partial.outputText());
        assertEquals(SegmentStatus.FAILED, partial.segments().get(1).status());

        TranslationRequest failRequest = partialRequest.toBuilder().failurePolicy(FailurePolicy.FAIL).build();
        TranslationResult failed = new TranslationService((request, system, user) -> null)
                .translate(failRequest);
        assertEquals(Status.FAILED, failed.status());
        assertNull(failed.outputText());
        assertEquals(2, failed.segments().size());

        TranslationResult retainedFailure = new TranslationService((request, system, user) -> {
            throw new IllegalStateException("secret must not be copied into diagnostics");
        }).translate(partialRequest);
        assertEquals(Status.FAILED, retainedFailure.status());
        assertEquals("abcd", retainedFailure.outputText());
        assertTrue(retainedFailure.segments().get(0).diagnostic().contains("original segment retained"));
        assertFalse(retainedFailure.segments().get(0).diagnostic().contains("secret"));
    }

    @Test
    void responseLimitFailsWithoutTruncatingModelOutput() throws Exception {
        TranslationRequest request = TranslationRequest.builder()
                .text("hello")
                .sourceLanguage("de")
                .targetLanguage("en")
                .maxResponseChars(3)
                .build();
        TranslationResult result = new TranslationService((received, system, user) -> "four").translate(request);

        assertEquals(Status.FAILED, result.status());
        assertNull(result.outputText());
        assertTrue(result.segments().get(0).diagnostic().contains("response limit"));
    }

    @Test
    void fingerprintIsStableAndInvalidatedBySourceLanguageModelProviderAndTerms() {
        TranslationRequest base = TranslationRequest.builder()
                .text("same")
                .sourceId("source-a")
                .sourceLanguage("de")
                .targetLanguage("en")
                .provider("p1")
                .modelId("m1")
                .preserveTerms(List.of("term"))
                .build();
        assertEquals(base.fingerprint(), base.fingerprint());
        assertNotEquals(base.fingerprint(), base.toBuilder().text("changed").build().fingerprint());
        assertNotEquals(base.fingerprint(), base.toBuilder().sourceLanguage("fr").build().fingerprint());
        assertNotEquals(base.fingerprint(), base.toBuilder().provider("p2").build().fingerprint());
        assertNotEquals(base.fingerprint(), base.toBuilder().modelId("m2").build().fingerprint());
        assertNotEquals(base.fingerprint(), base.toBuilder().preserveTerms(List.of("other")).build().fingerprint());
        assertNotEquals(base.toBuilder().provider(null).build().fingerprint(),
                base.toBuilder().provider("<null>").build().fingerprint());
        assertNotEquals(base.fingerprint(), base.toBuilder().maxOutputChars(1).build().fingerprint());
    }

    @Test
    void cancellationBeforeAndAfterCallbackPropagatesAndPreservesInterrupt() {
        try {
            Thread.currentThread().interrupt();
            TranslationService service = new TranslationService((request, system, user) -> "ignored");
            assertThrows(InterruptedException.class, () -> service.translate(request("text")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        try {
            TranslationService service = new TranslationService((request, system, user) -> {
                Thread.currentThread().interrupt();
                return "late result";
            });
            assertThrows(InterruptedException.class, () -> service.translate(request("text")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        try {
            AtomicInteger calls = new AtomicInteger();
            TranslationService service = new TranslationService((request, system, user) -> {
                calls.incrementAndGet();
                Thread.currentThread().interrupt();
                return "first result";
            });
            TranslationRequest segmented = request("abcd").toBuilder()
                    .maxCharsPerRequest(2)
                    .build();
            assertThrows(InterruptedException.class, () -> service.translate(segmented));
            assertEquals(1, calls.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptedCallbackIsNotDowngradedToFailedResult() {
        try {
            TranslationService service = new TranslationService((request, system, user) -> {
                throw new InterruptedException("cancel");
            });
            assertThrows(InterruptedException.class, () -> service.translate(request("text")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cancellationExceptionPropagatesInsteadOfBecomingKeepOriginalFailure() {
        TranslationRequest request = request("text").toBuilder()
                .failurePolicy(FailurePolicy.KEEP_ORIGINAL)
                .build();
        TranslationService service = new TranslationService((received, system, user) -> {
            throw new CancellationException("cancelled");
        });

        assertThrows(CancellationException.class, () -> service.translate(request));
    }

    @Test
    void totalOutputLimitFailsWithNullOutputAndStopsAfterOffendingCallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TranslationRequest request = request("abcd").toBuilder()
                .maxCharsPerRequest(2)
                .maxOutputChars(3)
                .build();

        TranslationResult result = new TranslationService((received, system, user) -> {
            calls.incrementAndGet();
            return "AB";
        }).translate(request);

        assertEquals(Status.FAILED, result.status());
        assertNull(result.outputText());
        assertTrue(result.reason().contains("total output limit"));
        assertEquals(2, calls.get());
        assertEquals(SegmentStatus.FAILED, result.segments().get(1).status());
    }

    @Test
    void totalOutputLimitAlsoBoundsKeepOriginalPreservation() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TranslationRequest request = request("abcd").toBuilder()
                .maxCharsPerRequest(2)
                .maxOutputChars(3)
                .failurePolicy(FailurePolicy.KEEP_ORIGINAL)
                .build();

        TranslationResult result = new TranslationService((received, system, user) -> {
            calls.incrementAndGet();
            return null;
        }).translate(request);

        assertEquals(Status.FAILED, result.status());
        assertNull(result.outputText());
        assertTrue(result.reason().contains("total output limit"));
        assertEquals(2, calls.get());
    }

    private static TranslationRequest request(String text) {
        return TranslationRequest.builder()
                .text(text)
                .sourceLanguage("de")
                .targetLanguage("en")
                .build();
    }

    private static String segmentFromPrompt(String prompt) {
        String begin = "<<<TRANSLATION_DOCUMENT>>>\n";
        String end = "\n<<<END_TRANSLATION_DOCUMENT>>>";
        int beginIndex = prompt.indexOf(begin);
        int endIndex = prompt.lastIndexOf(end);
        return prompt.substring(beginIndex + begin.length(), endIndex);
    }
}
