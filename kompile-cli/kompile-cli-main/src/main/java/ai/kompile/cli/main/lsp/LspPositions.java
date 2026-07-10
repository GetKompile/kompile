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

package ai.kompile.cli.main.lsp;

import org.eclipse.lsp4j.Position;

/**
 * Conversions between LSP {@link Position}s and flat character offsets into a
 * document's content.
 *
 * <p>LSP positions are <b>0-based line</b> + <b>UTF-16 code-unit column</b>. Java
 * {@code String} is itself UTF-16 (each {@code char} is one code unit, and a code
 * point above {@code U+FFFF} occupies two chars via a surrogate pair), so a raw
 * {@code char} offset <i>is</i> a UTF-16 offset — no code-point arithmetic is needed
 * or wanted. Counting columns in Unicode code points instead of UTF-16 units is the
 * classic off-by-N bug this class exists to avoid (see the surrogate-pair test).</p>
 */
public final class LspPositions {

    private LspPositions() {
    }

    /** Character offset into {@code content} for an LSP position. */
    public static int offsetOf(String content, Position position) {
        return offsetOf(content, position.getLine(), position.getCharacter());
    }

    /**
     * Character offset into {@code content} for a 0-based {@code line} and a
     * 0-based UTF-16 {@code character} column. Out-of-range lines clamp to the end
     * of content; an over-long column clamps to the end of its line.
     */
    public static int offsetOf(String content, int line, int character) {
        int length = content.length();
        int offset = 0;
        int currentLine = 0;
        while (currentLine < line && offset < length) {
            if (content.charAt(offset) == '\n') {
                currentLine++;
            }
            offset++;
        }
        if (currentLine < line) {
            return length; // requested line is past the end of the document
        }
        int lineEnd = offset;
        while (lineEnd < length && content.charAt(lineEnd) != '\n') {
            lineEnd++;
        }
        int target = offset + Math.max(0, character);
        return Math.min(target, lineEnd);
    }

    /** The LSP position of a character offset into {@code content}. */
    public static Position positionOf(String content, int offset) {
        int clamped = Math.max(0, Math.min(offset, content.length()));
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < clamped; i++) {
            if (content.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, clamped - lineStart);
    }
}
