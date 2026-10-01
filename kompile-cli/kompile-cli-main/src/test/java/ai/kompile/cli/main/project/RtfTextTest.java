/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.cli.main.project;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Exact-output coverage of {@link RtfText#toPlainText}, the native-image-safe RTF-to-text
 * converter used by {@link LocalDocumentLoaderRegistry} for {@code .rtf} attachments.
 *
 * <p>Every expected value below was cross-checked against a standalone run of the same
 * production class outside of Maven (no AWT/Swing RTF reader involved anywhere, by design), so
 * these assertions pin actual behavior rather than a guess at intended behavior. RTF control
 * words are always written with a doubled backslash ({@code \\par}, {@code \\u}, ...) in this
 * file's string literals and comments - a single {@code \} followed by {@code u} and four hex
 * digits is a real Java source-level unicode escape, and javac evaluates those even inside
 * comments, so a lone backslash here would either fail to compile or silently mistranslate.</p>
 */
class RtfTextTest {

    @Test
    void nullAndEmptyInputReturnEmptyString() {
        assertEquals("", RtfText.toPlainText(null));
        assertEquals("", RtfText.toPlainText(""));
    }

    @Test
    void plainAsciiTextIsUnchanged() {
        assertEquals("Hello World", RtfText.toPlainText("{\\rtf1\\ansi Hello World}"));
    }

    @Test
    void parLineTabCellAndRowProduceTheirTextSeparators() {
        String rtf = "{\\rtf1 A\\par B\\line C\\tab D\\cell E\\row F}";

        assertEquals("A\nB\nC\tD | E\nF", RtfText.toPlainText(rtf));
    }

    @Test
    void trailingSpaceAfterAControlWordIsConsumedNotEmitted() {
        // The single space between "\par" and "B" terminates the control word per the RTF spec
        // and must never surface as a literal leading space on the next line of text.
        assertEquals("A\nB", RtfText.toPlainText("{\\rtf1 A\\par B}"));
    }

    @Test
    void formattingControlWordsWithNoTextEquivalentAreIgnored() {
        assertEquals("Bold text", RtfText.toPlainText("{\\rtf1\\b\\fs24 Bold text\\b0}"));
    }

    @Test
    void hexEscapeDecodesThroughDefaultWindows1252CodePage() {
        assertEquals("Café", RtfText.toPlainText("{\\rtf1\\ansi Caf\\'e9}"));
    }

    @Test
    void hexEscapeDecodesThroughAnExplicitlyDeclaredCodePage() {
        // 0xB3 is "0141 LATIN SMALL LETTER L WITH STROKE" in windows-1250, not windows-1252.
        assertEquals("abcł", RtfText.toPlainText("{\\rtf1\\ansi\\ansicpg1250 abc\\'b3}"));
    }

    @Test
    void consecutiveHexEscapesDecodeTogetherAsOneByteRun() {
        assertEquals("Hello",
                RtfText.toPlainText("{\\rtf1\\ansi \\'48\\'65\\'6c\\'6c\\'6f}"));
    }

    @Test
    void unicodeEscapeEmitsTheCharacterAndSkipsItsFallbackText() {
        // \\uc1 (the default even without being set) means exactly one fallback unit follows a
        // \\u escape and must be discarded - here the literal "?" fallback for the Euro sign.
        assertEquals("€", RtfText.toPlainText("{\\rtf1\\uc1 \\u8364?}"));
    }

    @Test
    void unicodeEscapeHandlesNegativeSignedValueByAdding65536() {
        assertEquals("", RtfText.toPlainText("{\\rtf1\\uc1 \\u-3913?}"));
    }

    @Test
    void unicodeSkipCountDefaultsToOneWithoutAnExplicitUcControlWord() {
        assertEquals("A", RtfText.toPlainText("{\\rtf1 \\u65 X}"));
    }

    @Test
    void unicodeSkipCountZeroSkipsNoFallbackText() {
        assertEquals("A", RtfText.toPlainText("{\\rtf1\\uc0 \\u65}"));
    }

    @Test
    void unicodeSkipCountAppliesToMultipleFallbackUnits() {
        // \\uc2 discards the next two units ("a" and "b") that follow the \\u escape.
        assertEquals("A", RtfText.toPlainText("{\\rtf1\\uc2 \\u65 ab}"));
    }

    @Test
    void unicodeSkipCountIsInheritedByANestedGroup() {
        String rtf = "{\\rtf1\\uc2{\\u65 XY}Z}";

        assertEquals("AZ", RtfText.toPlainText(rtf));
    }

    @Test
    void unicodeSkipCountChangeInsideAGroupDoesNotLeakToTheParentGroup() {
        String rtf = "{\\rtf1{\\uc2\\u65 XY}\\u66 W}";

        // The inner group's \\uc2 only affects \\u65's own two-unit fallback ("X","Y"); once that
        // group closes, the outer group is still on its own default \\uc1, so \\u66's fallback
        // ("W") is a single skipped unit, not zero and not two.
        assertEquals("AB", RtfText.toPlainText(rtf));
    }

    @Test
    void skipDestinationsDiscardEntireGroupIncludingNestedContent() {
        assertEquals("Hello",
                RtfText.toPlainText("{\\rtf1{\\fonttbl{\\f0 Times New Roman;}}Hello}"));
        assertEquals("Text",
                RtfText.toPlainText("{\\rtf1{\\colortbl;\\red0\\green0\\blue0;}Text}"));
    }

    @Test
    void starPrefixedUnknownDestinationIsSkippedEvenWhenNotInTheNamedList() {
        // \\* marks the group that follows as a skip-destination regardless of whether its name
        // (here a made-up "unknowndest") is one of the well-known destinations.
        assertEquals("Visible",
                RtfText.toPlainText("{\\rtf1{\\*\\unknowndest secret text}Visible}"));
    }

    @Test
    void nestedSkipDestinationInsideAnotherSkipDestinationStaysDiscarded() {
        String rtf = "{\\rtf1{\\fonttbl{\\f0{\\*\\falt Alt Font;}Times;}}After}";

        assertEquals("After", RtfText.toPlainText(rtf));
    }

    @Test
    void escapedLiteralBracesAndBackslashAreEmittedAsPlainText() {
        String rtf = "{\\rtf1 \\{not a group\\} and \\\\backslash}";

        assertEquals("{not a group} and \\backslash", RtfText.toPlainText(rtf));
    }

    @Test
    void tildeUnderscoreAndOptionalHyphenEscapesMapToTheirLiterals() {
        assertEquals("a b", RtfText.toPlainText("{\\rtf1 a\\~b}"));
        assertEquals("a-b", RtfText.toPlainText("{\\rtf1 a\\_b}"));
        assertEquals("ab", RtfText.toPlainText("{\\rtf1 a\\-b}"));
    }

    @Test
    void unrecognizedControlSymbolIsDroppedWithoutAffectingSurroundingText() {
        assertEquals("ab", RtfText.toPlainText("{\\rtf1 a\\:b}"));
    }

    @Test
    void unbalancedBracesRecoverGracefullyInsteadOfThrowing() {
        assertEquals("Hello", RtfText.toPlainText("{\\rtf1 Hello"));
    }

    @Test
    void truncatedHexEscapeAtEndOfInputNeverThrows() {
        String result = assertDoesNotThrow(() -> RtfText.toPlainText("{\\rtf1 \\'4"));

        assertNotNull(result);
    }
}
