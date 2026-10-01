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

package ai.kompile.loader.mail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct unit tests for {@link MailLoaderImpl}'s dependency-free HTML- and RTF-to-text helpers.
 *
 * <p>These are exercised directly (the methods are package-private for this purpose) rather than
 * only indirectly through a .msg or .eml file, because POI's HSMF reader is read-only - there is
 * no way to synthesize a valid Outlook .msg (MAPI/CFBF) fixture in-memory the way XWPF/XSLF
 * documents can be built for the Office loaders, and no real .msg fixture exists in this repo or
 * the local Maven repository (both were searched before writing these tests).
 */
class MailLoaderImplTextConversionTest {

    private final MailLoaderImpl loader = new MailLoaderImpl();

    // =========================================================================
    // htmlToText
    // =========================================================================

    @Test
    void htmlToText_stripsTagsAndKeepsText() {
        String html = "<html><body><p>Hello <b>World</b></p></body></html>";
        String text = loader.htmlToText(html);
        assertEquals("Hello World", text);
    }

    @Test
    void htmlToText_convertsBreaksAndBlockClosesToNewlines() {
        String html = "<p>Line one<br>Line two</p><p>Line three</p>";
        String text = loader.htmlToText(html);
        String[] lines = text.split("\n");
        assertEquals("Line one", lines[0]);
        assertEquals("Line two", lines[1]);
        assertTrue(text.contains("Line three"));
    }

    @Test
    void htmlToText_removesScriptAndStyleBlocksEntirely() {
        String html = "<html><head><style>body{color:red}</style>"
                + "<script>alert('should not appear');</script></head>"
                + "<body><p>Visible text</p></body></html>";
        String text = loader.htmlToText(html);
        assertTrue(text.contains("Visible text"));
        assertFalse(text.contains("color:red"));
        assertFalse(text.contains("should not appear"));
    }

    @Test
    void htmlToText_decodesCommonEntities() {
        String html = "<p>Tom &amp; Jerry &lt;tag&gt; &quot;quoted&quot; &#39;apos&#39;</p>";
        String text = loader.htmlToText(html);
        assertEquals("Tom & Jerry <tag> \"quoted\" 'apos'", text);
    }

    @Test
    void htmlToText_doesNotMisDecodeDoubleEscapedAmpersand() {
        // "&amp;lt;" is a literal "&" followed by literal text "lt;" - it must NOT become "<".
        String html = "<p>&amp;lt;not-a-tag&amp;gt;</p>";
        String text = loader.htmlToText(html);
        assertEquals("&lt;not-a-tag&gt;", text);
    }

    @Test
    void htmlToText_nullInputReturnsNull() {
        assertNull(loader.htmlToText(null));
    }

    // =========================================================================
    // stripRtfControlWords
    // =========================================================================

    @Test
    void stripRtfControlWords_extractsPlainTextAndConvertsPar() {
        String rtf = "{\\rtf1\\ansi\\deff0 Hello World\\par Second paragraph}";
        String text = loader.stripRtfControlWords(rtf);
        assertTrue(text.contains("Hello World"));
        assertTrue(text.contains("Second paragraph"));
        assertTrue(text.contains("\n"), "\\par should become a newline");
    }

    @Test
    void stripRtfControlWords_dropsFontAndColorTableContent() {
        String rtf = "{\\rtf1\\ansi{\\fonttbl{\\f0 Times New Roman;}}"
                + "{\\colortbl ;\\red255\\green0\\blue0;}"
                + "\\f0 Actual visible text}";
        String text = loader.stripRtfControlWords(rtf);
        assertTrue(text.contains("Actual visible text"));
        assertFalse(text.contains("Times New Roman"),
                "font table destination group content must not leak into the text");
        assertFalse(text.contains("red255"),
                "color table destination group content must not leak into the text");
    }

    @Test
    void stripRtfControlWords_decodesHexEscapes() {
        // \'e9 is 0xE9 which is 'é' under the default ANSI (cp1252/Latin-1) RTF code page.
        String rtf = "{\\rtf1\\ansi caf\\'e9}";
        String text = loader.stripRtfControlWords(rtf);
        assertTrue(text.contains("café"), "expected decoded 'café', got: " + text);
    }

    @Test
    void stripRtfControlWords_convertsTabAndKeepsEscapedBraces() {
        // The space after "\tab" is the control word's own delimiter (consumed, not emitted) -
        // RTF requires it since a control word is otherwise unterminated before a letter.
        String rtf = "{\\rtf1 one\\tab two \\{literal\\} brace}";
        String text = loader.stripRtfControlWords(rtf);
        assertTrue(text.contains("one\ttwo"), "\\tab should become a literal tab, got: " + text);
        assertTrue(text.contains("{literal}"), "escaped braces should be kept literally, got: " + text);
    }

    @Test
    void stripRtfControlWords_nullInputReturnsNull() {
        assertNull(loader.stripRtfControlWords(null));
    }
}
