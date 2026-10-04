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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogFollowerTest {

    @TempDir
    Path tempDir;

    /** Lines the parser was handed, malformed ones included. */
    private int parsed;

    /** Collects each line as text; a line starting "bad" is malformed. */
    private LogFollower<List<String>> follower(Path file, long maxBytes) {
        return new LogFollower<>(file, maxBytes, ArrayList::new, (lines, buffer, offset, length) -> {
            parsed++;
            String line = new String(buffer, offset, length, StandardCharsets.UTF_8);
            if (line.startsWith("bad")) {
                throw new IOException("malformed");
            }
            lines.add(line);
        });
    }

    private static void append(Path file, String text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    void eachReadFoldsOnlyTheLinesAppendedSinceThePreviousOne() throws IOException {
        Path file = tempDir.resolve("log.jsonl");
        append(file, "one\ntwo\n");
        LogFollower<List<String>> follower = follower(file, 0);

        assertEquals(List.of("one", "two"), follower.read());
        assertEquals(List.of("one", "two"), follower.read());
        append(file, "three\n");
        assertEquals(List.of("one", "two", "three"), follower.read());

        assertEquals(3, parsed);
        assertFalse(follower.partial());
    }

    @Test
    void aLineWaitsForItsNewline() throws IOException {
        Path file = tempDir.resolve("log.jsonl");
        append(file, "one\ntw");
        LogFollower<List<String>> follower = follower(file, 0);

        assertEquals(List.of("one"), follower.read());
        append(file, "o\r\nthr");
        assertEquals(List.of("one", "two"), follower.read());
        append(file, "ee\n");
        assertEquals(List.of("one", "two", "three"), follower.read());
    }

    @Test
    void theFirstReadOfALongFileTakesTheWholeLinesWithinItsBudget() throws IOException {
        // Lines start at bytes 0, 5 and 10 of the 15.
        Path file = tempDir.resolve("log.jsonl");
        append(file, "aaaa\nbbbb\ncccc\n");

        // Ten bytes back is where "bbbb" starts; the newline before it shows the line is whole.
        LogFollower<List<String>> whole = follower(file, 10);
        assertEquals(List.of("bbbb", "cccc"), whole.read());
        assertTrue(whole.partial());

        // Eight bytes back is inside "bbbb", which is dropped.
        LogFollower<List<String>> cut = follower(file, 8);
        assertEquals(List.of("cccc"), cut.read());
        assertTrue(cut.partial());

        LogFollower<List<String>> all = follower(file, 15);
        assertEquals(List.of("aaaa", "bbbb", "cccc"), all.read());
        assertFalse(all.partial());
    }

    @Test
    void aFileThatShrankOrWasReplacedStartsANewTally() throws IOException {
        Path file = tempDir.resolve("log.jsonl");
        append(file, "one\ntwo\n");
        LogFollower<List<String>> follower = follower(file, 0);
        assertEquals(List.of("one", "two"), follower.read());

        Files.writeString(file, "new\n", StandardCharsets.UTF_8);
        assertEquals(List.of("new"), follower.read());

        // A replacement no shorter than the old file is told apart by its file key.
        Path next = Files.writeString(tempDir.resolve("next.jsonl"), "abc\n", StandardCharsets.UTF_8);
        Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        assertEquals(List.of("abc"), follower.read());
    }

    @Test
    void aMissingFileIsAnEmptyTallyUntilItAppears() throws IOException {
        Path file = tempDir.resolve("log.jsonl");
        LogFollower<List<String>> follower = follower(file, 0);

        assertEquals(List.of(), follower.read());
        append(file, "one\n");
        assertEquals(List.of("one"), follower.read());

        Files.delete(file);
        assertEquals(List.of(), follower.read());
        append(file, "two\n");
        assertEquals(List.of("two"), follower.read());
    }

    @Test
    void blankMalformedAndOverlongLinesAreSkipped() throws IOException {
        Path file = tempDir.resolve("log.jsonl");
        append(file, "one\n \t\n\nbad line\n" + "x".repeat(LogFollower.MAX_LINE + 1) + "\ntwo\n");
        LogFollower<List<String>> follower = follower(file, 0);

        assertEquals(List.of("one", "two"), follower.read());
        // Blank lines never reach the parser; the malformed one does, and is dropped.
        assertEquals(3, parsed);
    }

    @Test
    void aLineIsKeptUpToTheLimitWhenItsNewlineComesInALaterRead() throws IOException {
        Path kept = tempDir.resolve("kept.jsonl");
        append(kept, "z".repeat(LogFollower.MAX_LINE));
        LogFollower<List<String>> follower = follower(kept, 0);
        assertEquals(List.of(), follower.read());

        append(kept, "\n");
        List<String> lines = follower.read();
        assertEquals(1, lines.size());
        assertEquals(LogFollower.MAX_LINE, lines.get(0).length());

        Path dropped = tempDir.resolve("dropped.jsonl");
        append(dropped, "z".repeat(LogFollower.MAX_LINE));
        LogFollower<List<String>> past = follower(dropped, 0);
        assertEquals(List.of(), past.read());

        append(dropped, "zz\ntwo\n");
        assertEquals(List.of("two"), past.read());
    }
}
