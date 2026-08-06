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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.graphrag.passes;

import ai.kompile.core.graphrag.GraphConstructor.SourceSpan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceEventSegmenterTest {

    @Test
    void paragraphsStayOrderedAndCarryExactOriginalOffsets() {
        String source = "  First local event.  \n\nSecond local event.\n\n  Third event.  ";

        List<SourceEventSegmenter.SourceEvent> events = SourceEventSegmenter.segment(source, 128);

        assertEquals(List.of("First local event.", "Second local event.", "Third event."),
                events.stream().map(SourceEventSegmenter.SourceEvent::text).toList());
        for (int i = 0; i < events.size(); i++) {
            SourceEventSegmenter.SourceEvent event = events.get(i);
            assertEquals(i + 1, event.ordinal());
            assertEquals("event-" + (i + 1), event.id());
            assertEquals(event.text(), source.substring(event.start(), event.end()));
        }
    }

    @Test
    void aCompactHeaderBlockRemainsOneEventAndBodyIsSeparate() {
        String source = "From: sender@example.test\nTo: receiver@example.test\nSubject: Status"
                + "\n\nThe body reports one result.";

        List<SourceEventSegmenter.SourceEvent> events = SourceEventSegmenter.segment(source, 256);

        assertEquals(2, events.size());
        assertTrue(events.get(0).text().contains("From:"));
        assertTrue(events.get(0).text().contains("Subject:"));
        assertEquals("The body reports one result.", events.get(1).text());
    }

    @Test
    void oversizedProseIsPackedAtSentenceBoundariesWithinTheLimit() {
        String source = "One sentence has several ordinary words. "
                + "A second sentence also has several ordinary words. "
                + "A third sentence completes the paragraph.";

        List<SourceEventSegmenter.SourceEvent> events = SourceEventSegmenter.segment(source, 64);

        assertTrue(events.size() >= 2);
        assertTrue(events.stream().allMatch(event -> event.text().length() <= 128),
                "the production floor is 128 characters");
        assertEquals(source, String.join(" ", events.stream()
                .map(SourceEventSegmenter.SourceEvent::text).toList()));
    }

    @Test
    void compactProseStillSplitsIntoOneSentencePerModelEvent() {
        String source = "Maya exported the forecast. Erin reviewed it. Finance published it.";

        List<SourceEventSegmenter.SourceEvent> events = SourceEventSegmenter.segment(source, 512);

        assertEquals(List.of("Maya exported the forecast.", "Erin reviewed it.",
                        "Finance published it."),
                events.stream().map(SourceEventSegmenter.SourceEvent::text).toList());
        events.forEach(event -> assertEquals(event.text(),
                source.substring(event.start(), event.end())));
    }

    @Test
    void numericFactListsBecomeIndependentExactFocusEvents() {
        String source = "HYD-110 (the new sleep serum) launches july 1. "
                + "i have it at conservative phasing — 4200 units in jul, 6800 in aug.";

        List<SourceEventSegmenter.SourceEvent> events = SourceEventSegmenter.segment(source, 512);

        assertEquals(List.of(
                        "HYD-110 (the new sleep serum) launches july 1.",
                        "i have it at conservative phasing",
                        "4200 units in jul",
                        "6800 in aug."),
                events.stream().map(SourceEventSegmenter.SourceEvent::text).toList());
        events.forEach(event -> assertEquals(event.text(),
                source.substring(event.start(), event.end())));
    }

    @Test
    void ordinaryCommasAndThousandsSeparatorsDoNotCreateFalseEvents() {
        String source = "Sarah Chen, VP FP&A, approved 4,200 units in July.";

        assertEquals(List.of(source), SourceEventSegmenter.segment(source, 512).stream()
                .map(SourceEventSegmenter.SourceEvent::text).toList());
    }

    @Test
    void personInitialsDoNotDestroyEmailOrPublicationEvents() {
        String email = "At 2026-06-03T09:00:00Z the email 'AMER forecast June' was sent by "
                + "Sarah Chen (S. Chen, sarah.chen@northstargoods.com).";
        String publication = "At 09:40 J. Park published the June forecast. "
                + "Finance reviewed it.";
        String escalation = "Channel mismatches always escalate to M. Chen.";

        assertEquals(List.of(email), SourceEventSegmenter.segment(email, 512).stream()
                .map(SourceEventSegmenter.SourceEvent::text).toList());
        assertEquals(List.of("At 09:40 J. Park published the June forecast.",
                        "Finance reviewed it."),
                SourceEventSegmenter.segment(publication, 512).stream()
                        .map(SourceEventSegmenter.SourceEvent::text).toList());
        assertEquals(List.of(escalation), SourceEventSegmenter.segment(escalation, 512).stream()
                .map(SourceEventSegmenter.SourceEvent::text).toList());
    }

    @Test
    void aStandaloneLetterEndingDoesNotMergeTheNextSentence() {
        String source = "Finance selected option A. Next step begins Monday.";

        assertEquals(List.of("Finance selected option A.", "Next step begins Monday."),
                SourceEventSegmenter.segment(source, 512).stream()
                        .map(SourceEventSegmenter.SourceEvent::text).toList());
    }

    @Test
    void aCompleteSourceSpanPlanOverridesHeuristicSentenceBoundaries() {
        String source = "Finance selected option A. Next step begins Monday.";

        List<SourceEventSegmenter.SourceEvent> events =
                SourceEventSegmenter.segmentSourceSpans(source, 512,
                        List.of(new SourceSpan(0, source.length(), "PARSED_MESSAGE")));

        assertEquals(List.of(source), events.stream()
                .map(SourceEventSegmenter.SourceEvent::text).toList());
        assertEquals(source, source.substring(events.get(0).start(), events.get(0).end()));
    }

    @Test
    void anIncompleteSourceSpanPlanIsRejectedInsteadOfDroppingSourceText() {
        String source = "First fact. Second fact.";
        int firstEnd = source.indexOf(" Second");

        assertTrue(SourceEventSegmenter.segmentSourceSpans(source, 512,
                List.of(new SourceSpan(0, firstEnd, "PARTIAL"))).isEmpty());
    }

    @Test
    void blankInputProducesNoModelWork() {
        assertTrue(SourceEventSegmenter.segment(null).isEmpty());
        assertTrue(SourceEventSegmenter.segment("  \n\n ").isEmpty());
    }
}
