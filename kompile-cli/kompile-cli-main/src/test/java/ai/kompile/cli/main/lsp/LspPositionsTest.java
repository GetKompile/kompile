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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LspPositionsTest {

    @Test
    void offsetOfMapsLineAndColumn() {
        String content = "line0\nline1\nline2";
        assertEquals(0, LspPositions.offsetOf(content, 0, 0));
        assertEquals(8, LspPositions.offsetOf(content, 1, 2)); // 'n' of line1
        assertEquals('n', content.charAt(8));
        assertEquals(content.length(), LspPositions.offsetOf(content, 99, 0)); // past end clamps
    }

    @Test
    void offsetOfClampsOverlongColumnToLineEnd() {
        String content = "ab\ncd";
        assertEquals(2, LspPositions.offsetOf(content, 0, 50)); // end of first line, before '\n'
    }

    @Test
    void positionOfIsInverseOfOffsetOf() {
        String content = "alpha\nbeta\ngamma";
        for (int offset = 0; offset <= content.length(); offset++) {
            Position p = LspPositions.positionOf(content, offset);
            assertEquals(offset, LspPositions.offsetOf(content, p),
                    "round trip failed at offset " + offset);
        }
    }

    @Test
    void utf16SurrogatePairCountsAsTwoUnits() {
        // 😀 (U+1F600) is one code point but two UTF-16 code units.
        String content = "a😀b"; // a, 😀, b
        // Column 3 (UTF-16 units: a=0, high=1, low=2) addresses 'b'.
        int offset = LspPositions.offsetOf(content, 0, 3);
        assertEquals(3, offset);
        assertEquals('b', content.charAt(offset));

        // And the inverse: 'b' sits at UTF-16 column 3, NOT code-point column 2.
        Position p = LspPositions.positionOf(content, content.indexOf('b'));
        assertEquals(0, p.getLine());
        assertEquals(3, p.getCharacter());
    }
}
