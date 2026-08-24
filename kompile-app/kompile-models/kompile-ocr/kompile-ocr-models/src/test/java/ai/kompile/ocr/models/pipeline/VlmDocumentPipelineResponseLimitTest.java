package ai.kompile.ocr.models.pipeline;

import org.eclipse.deeplearning4j.llm.generation.GenerationResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VlmDocumentPipelineResponseLimitTest {
    @Test
    void responseLimitCountsUtf8BytesAcrossPagesWithoutTruncating() {
        long firstPage = VlmDocumentPipeline.enforceResponseLimit(0L, "é", 5L);
        assertEquals(2L, firstPage);
        assertEquals(5L, VlmDocumentPipeline.enforceResponseLimit(firstPage, "abc", 5L));

        IllegalStateException overflow = assertThrows(IllegalStateException.class,
                () -> VlmDocumentPipeline.enforceResponseLimit(firstPage, "abcd", 5L));
        assertTrue(overflow.getMessage().contains("actual=6"), overflow.getMessage());
    }

    @Test
    void zeroResponseLimitDisablesOnlyTheByteGuard() {
        assertEquals(3L, VlmDocumentPipeline.enforceResponseLimit(0L, "abc", 0L));
    }

    @Test
    void adaptivePrefixStopsAtLastClosedElement() {
        String text = "<doctag>" + "x".repeat(240)
                + "<text>one</text><text>two</text><text>three</text><text>unfinished";
        assertEquals("<doctag>" + "x".repeat(240)
                        + "<text>one</text><text>two</text><text>three</text>",
                VlmDocumentPipeline.trimUsableAdaptivePrefix(text));
        assertNull(VlmDocumentPipeline.trimUsableAdaptivePrefix("<doctag><text>short</text>"));
        assertNull(VlmDocumentPipeline.trimUsableAdaptivePrefix(
                "<doctag>" + "x".repeat(240)
                        + "<table><text>one</text><text>two</text><text>three</text></row>"));
    }

    @Test
    void failedAndCancelledGenerationsAreNeverParsedAsSuccessfulOcr() {
        for (GenerationResult.FinishReason reason : new GenerationResult.FinishReason[]{
                GenerationResult.FinishReason.ERROR,
                GenerationResult.FinishReason.CANCELLED}) {
            GenerationResult result = GenerationResult.builder()
                    .text("partial")
                    .tokenIds(new int[]{1, 2})
                    .generatedTokenCount(2)
                    .finishReason(reason)
                    .build();
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> VlmDocumentPipeline.rejectFailedGeneration(result, "test"));
            assertTrue(failure.getMessage().contains(reason.name()), failure.getMessage());
        }
    }
}
