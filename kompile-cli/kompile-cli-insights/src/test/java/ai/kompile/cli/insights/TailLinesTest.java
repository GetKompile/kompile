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

package ai.kompile.cli.insights;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailLinesTest {

    @TempDir
    Path tempDir;

    private Path file(String content) throws IOException {
        return Files.writeString(tempDir.resolve("log.jsonl"), content, StandardCharsets.UTF_8);
    }

    private static List<String> collect(Path file, long maxBytes, boolean[] cut) throws IOException {
        List<String> lines = new ArrayList<>();
        cut[0] = TailLines.newestFirst(file, maxBytes, (buffer, offset, length) -> {
            lines.add(new String(buffer, offset, length, StandardCharsets.UTF_8));
            return true;
        });
        return lines;
    }

    @Test
    void linesComeNewestFirst() throws IOException {
        boolean[] cut = new boolean[1];
        assertEquals(List.of("c", "b", "a"), collect(file("a\nb\nc\n"), 0, cut));
        assertFalse(cut[0]);
    }

    @Test
    void aLastLineWithoutANewlineIsRead() throws IOException {
        boolean[] cut = new boolean[1];
        assertEquals(List.of("b", "a"), collect(file("a\nb"), 0, cut));
    }

    @Test
    void carriageReturnsAndBlankLinesAreDropped() throws IOException {
        boolean[] cut = new boolean[1];
        assertEquals(List.of("b", "a"), collect(file("a\r\n\r\n  \n\t\nb\r\n"), 0, cut));
    }

    @Test
    void lineSplitAcrossReadChunksIsWhole() throws IOException {
        // Lines of 1000 bytes over about three chunks, so several lines straddle a chunk edge.
        List<String> written = new ArrayList<>();
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < 3 * TailLines.CHUNK / 1000; i++) {
            String line = String.format(Locale.ROOT, "%06d", i) + "x".repeat(993);
            written.add(line);
            content.append(line).append('\n');
        }
        boolean[] cut = new boolean[1];
        List<String> read = collect(file(content.toString()), 0, cut);

        assertEquals(written.size(), read.size());
        for (int i = 0; i < read.size(); i++) {
            assertEquals(written.get(written.size() - 1 - i), read.get(i));
        }
        assertFalse(cut[0]);
    }

    @Test
    void budgetEndingMidLineSkipsThePartialLine() throws IOException {
        boolean[] cut = new boolean[1];
        // 12 bytes; the last 7 start at the second "b".
        List<String> lines = collect(file("aaa\nbbb\nccc\n"), 7, cut);

        assertEquals(List.of("ccc"), lines);
        assertTrue(cut[0]);
    }

    @Test
    void budgetEndingAtALineStartKeepsThatLine() throws IOException {
        boolean[] cut = new boolean[1];
        // The last 8 bytes start exactly at "bbb".
        List<String> lines = collect(file("aaa\nbbb\nccc\n"), 8, cut);

        assertEquals(List.of("ccc", "bbb"), lines);
        assertTrue(cut[0]);
    }

    @Test
    void budgetCoveringTheWholeFileIsNotACut() throws IOException {
        boolean[] cut = new boolean[1];
        assertEquals(List.of("ccc", "bbb", "aaa"), collect(file("aaa\nbbb\nccc\n"), 12, cut));
        assertFalse(cut[0]);
    }

    @Test
    void visitorCanStopEarly() throws IOException {
        Path log = file("aaa\nbbb\nccc\n");
        List<String> seen = new ArrayList<>();

        boolean cut = TailLines.newestFirst(log, 8, (buffer, offset, length) -> {
            seen.add(new String(buffer, offset, length, StandardCharsets.UTF_8));
            return false;
        });

        assertEquals(List.of("ccc"), seen);
        // Stopping is not a budget cut, even though the budget was smaller than the file.
        assertFalse(cut);
    }

    @Test
    void aMissingFileHasNoLines() throws IOException {
        boolean[] cut = new boolean[1];
        assertEquals(List.of(), collect(tempDir.resolve("missing.jsonl"), 0, cut));
        assertFalse(cut[0]);
    }
}
