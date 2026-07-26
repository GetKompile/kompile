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

package ai.kompile.core.graphrag.passes;

import ai.kompile.core.graphrag.passes.ExtractionProposals.EvidenceRole;
import ai.kompile.core.graphrag.passes.ExtractionProposals.EvidenceSpan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EvidenceSpanValidator} — the engine's veto over fabricated citations.
 */
class EvidenceSpanValidatorTest {

    private static final String SOURCE =
            "Acme Corp acquired Initech in 2019. Bob said Initech was overvalued.";

    private static PassContext context() {
        return PassContext.forChunk("chunk-1", "doc-1", SOURCE);
    }

    @Test
    void acceptsSpanWhoseOffsetsAlreadyMatchTheSource() {
        int start = SOURCE.indexOf("Acme Corp acquired Initech");
        EvidenceSpan span = new EvidenceSpan("chunk-1", start,
                start + "Acme Corp acquired Initech".length(), "Acme Corp acquired Initech",
                EvidenceRole.DIRECT_SUPPORT);

        EvidenceSpanValidator.SpanCheck check = EvidenceSpanValidator.check(span, context());

        assertEquals(EvidenceSpanValidator.Status.VALID, check.status());
        assertTrue(check.usable());
        assertEquals(start, check.span().start());
    }

    @Test
    void repairsMiscountedOffsetsFromTheQuote() {
        EvidenceSpan span = new EvidenceSpan("chunk-1", 999, 1200, "acquired Initech in 2019",
                EvidenceRole.DIRECT_SUPPORT);

        EvidenceSpanValidator.SpanCheck check = EvidenceSpanValidator.check(span, context());

        assertEquals(EvidenceSpanValidator.Status.REPAIRED, check.status());
        assertTrue(check.usable());
        assertEquals(SOURCE.indexOf("acquired Initech in 2019"), check.span().start());
        assertEquals("acquired Initech in 2019",
                SOURCE.substring(check.span().start(), check.span().end()));
    }

    @Test
    void repairsAcrossWhitespaceAndCaseDifferences() {
        EvidenceSpan span = EvidenceSpan.ofQuote("chunk-1", "ACME   corp\n acquired");

        EvidenceSpanValidator.SpanCheck check = EvidenceSpanValidator.check(span, context());

        assertEquals(EvidenceSpanValidator.Status.REPAIRED, check.status());
        // The repaired span quotes the document verbatim, not the model's paraphrase of it.
        assertEquals("Acme Corp acquired", check.span().quote());
        assertEquals(0, check.span().start());
    }

    @Test
    void rejectsQuoteThatDoesNotOccurInTheSource() {
        EvidenceSpan span = EvidenceSpan.ofQuote("chunk-1", "Acme Corp acquired Globex in 2021");

        EvidenceSpanValidator.SpanCheck check = EvidenceSpanValidator.check(span, context());

        assertEquals(EvidenceSpanValidator.Status.NOT_FOUND, check.status());
        assertFalse(check.usable());
        assertTrue(check.detail().contains("not present"));
    }

    @Test
    void rejectsQuoteTooShortToVerify() {
        EvidenceSpan span = EvidenceSpan.ofQuote("chunk-1", "in");

        EvidenceSpanValidator.SpanCheck check = EvidenceSpanValidator.check(span, context());

        assertEquals(EvidenceSpanValidator.Status.TOO_SHORT, check.status());
        assertFalse(check.usable());
    }

    @Test
    void reportsMissingSpanSeparatelyFromFabricatedOne() {
        assertEquals(EvidenceSpanValidator.Status.MISSING,
                EvidenceSpanValidator.check(null, context()).status());
        assertEquals(EvidenceSpanValidator.Status.MISSING,
                EvidenceSpanValidator.check(EvidenceSpan.ofQuote("chunk-1", "  "), context())
                        .status());
    }

    @Test
    void stampsTheContextChunkIdOntoSpansThatOmitIt() {
        EvidenceSpan span = new EvidenceSpan(null, -1, -1, "Bob said Initech was overvalued",
                EvidenceRole.ATTRIBUTION);

        EvidenceSpanValidator.SpanCheck check = EvidenceSpanValidator.check(span, context());

        assertTrue(check.usable());
        assertEquals("chunk-1", check.span().chunkId());
        assertEquals(EvidenceRole.ATTRIBUTION, check.span().role());
    }

    @Test
    void treatsEmptySourceAsUnverifiable() {
        PassContext empty = PassContext.forChunk("chunk-1", "doc-1", null);

        EvidenceSpanValidator.SpanCheck check =
                EvidenceSpanValidator.check(EvidenceSpan.ofQuote("chunk-1", "anything"), empty);

        assertEquals(EvidenceSpanValidator.Status.NOT_FOUND, check.status());
        assertFalse(check.usable());
    }
}
