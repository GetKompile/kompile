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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMilestoneReaderTest {

    @TempDir
    Path tempDir;

    private Path write(String name, String json) throws IOException {
        return Files.writeString(tempDir.resolve(name), json);
    }

    private static Map<String, Object> byId(List<Map<String, Object>> milestones, String id) {
        return milestones.stream().filter(m -> id.equals(m.get("id"))).findFirst().orElseThrow();
    }

    @Test
    void readsBothLayoutsWithTheToolsFieldNames() throws IOException {
        write("0a1b2c3d.json", "{\"id\":\"0a1b2c3d\",\"module\":\"kompile-cli-main\",\"totalTests\":120,"
                + "\"passed\":118,\"commit\":\"0123456789abcdef\",\"commitShort\":\"0123456\"}");
        write("FooTest.json", "{\"id\":\"FooTest\",\"total\":5,\"passed\":4,\"commit\":\"fedcba9876543210\","
                + "\"branch\":\"main\"}");

        List<Map<String, Object>> milestones = TestMilestoneReader.readAll(tempDir);

        assertEquals(2, milestones.size());
        Map<String, Object> run = byId(milestones, "0a1b2c3d");
        assertEquals(120, run.get("totalTests"));
        assertEquals("0123456", run.get("commitShort"));
        Map<String, Object> sweep = byId(milestones, "FooTest");
        assertEquals(List.of("id", "totalTests", "passed", "commit", "commitShort", "branch"),
                new ArrayList<>(sweep.keySet()));
        assertEquals(5, sweep.get("totalTests"));
        assertEquals("fedcba9", sweep.get("commitShort"));
    }

    @Test
    void aMalformedFileIsListedAndDoesNotHideTheRest() throws IOException {
        write("good.json", "{\"id\":\"good\"}");
        Path truncated = write("truncated.json", "{\"id\":");
        Path array = write("array.json", "[1, 2]");
        Path nothing = write("null.json", "null");
        Path empty = write("empty.json", "");
        write("notes.txt", "not a milestone");
        Files.createDirectory(tempDir.resolve("folder.json"));

        TestMilestoneReader.Contents contents = TestMilestoneReader.readDir(tempDir);

        assertEquals(1, contents.milestones().size());
        assertEquals("good", contents.milestones().get(0).get("id"));
        assertEquals(List.of(array, empty, nothing, truncated),
                contents.unreadable().stream().sorted().toList());
    }

    @Test
    void aMissingDirectoryHoldsNothing() throws IOException {
        TestMilestoneReader.Contents contents = TestMilestoneReader.readDir(tempDir.resolve("none"));

        assertTrue(contents.milestones().isEmpty());
        assertTrue(contents.unreadable().isEmpty());
        assertTrue(TestMilestoneReader.readDir(null).milestones().isEmpty());
        assertTrue(TestMilestoneReader.readAll(write("file.json", "{}")).isEmpty());
    }

    @Test
    void theStoreIsUnderTheDotKompileDirectory() {
        assertEquals(tempDir.resolve(".kompile").resolve("test-milestones"), TestMilestoneReader.storeDir(tempDir));
    }

    @Test
    void runIdsAreEightLowerCaseHexDigits() {
        assertTrue(TestMilestoneReader.isRunId("0a1b2c3d"));
        assertFalse(TestMilestoneReader.isRunId("0A1B2C3D"));
        assertFalse(TestMilestoneReader.isRunId("0a1b2c3"));
        assertFalse(TestMilestoneReader.isRunId("0a1b2c3d4"));
        assertFalse(TestMilestoneReader.isRunId("FooTest"));
        assertFalse(TestMilestoneReader.isRunId(null));
        for (int i = 0; i < 20; i++) {
            assertTrue(TestMilestoneReader.isRunId(TestMilestoneReader.newRunId()));
        }
    }

    @Test
    void shortCommitCutsOnlyLongHashes() {
        assertEquals("0123456", TestMilestoneReader.shortCommit("0123456789abcdef"));
        assertEquals("ABCDEF0", TestMilestoneReader.shortCommit("ABCDEF0123"));
        assertEquals("abc1234", TestMilestoneReader.shortCommit("abc1234"));
        assertEquals("uncommitted", TestMilestoneReader.shortCommit("uncommitted"));
        assertEquals("WORKTREE", TestMilestoneReader.shortCommit("WORKTREE"));
        assertEquals("", TestMilestoneReader.shortCommit(""));
        assertNull(TestMilestoneReader.shortCommit(null));
    }

    @Test
    void fileForKeepsIdsInsideTheDirectory() {
        assertEquals(tempDir.resolve("FooTest.json"), TestMilestoneReader.fileFor(tempDir, "FooTest"));
        assertEquals(tempDir.resolve("..json"), TestMilestoneReader.fileFor(tempDir, "."));

        IllegalArgumentException blank =
                assertThrows(IllegalArgumentException.class, () -> TestMilestoneReader.fileFor(tempDir, " "));
        assertEquals("A milestone id is required", blank.getMessage());
        assertThrows(IllegalArgumentException.class, () -> TestMilestoneReader.fileFor(tempDir, null));
        IllegalArgumentException escape =
                assertThrows(IllegalArgumentException.class, () -> TestMilestoneReader.fileFor(tempDir, "../x"));
        assertEquals("Invalid milestone id '../x': ids are file names", escape.getMessage());
        for (String id : List.of("..", "a..b", "a/b", "a\\b", "C:x", "a\0b")) {
            assertThrows(IllegalArgumentException.class, () -> TestMilestoneReader.fileFor(tempDir, id), id);
        }
    }

    @Test
    void readFindsOneMilestone() throws IOException {
        write("FooTest.json", "{\"id\":\"FooTest\",\"total\":3}");
        write("broken.json", "{");
        write("null.json", "null");

        assertEquals(Map.of("id", "FooTest", "totalTests", 3), TestMilestoneReader.read(tempDir, "FooTest"));
        assertNull(TestMilestoneReader.read(tempDir, "0a1b2c3d"));
        assertThrows(IOException.class, () -> TestMilestoneReader.read(tempDir, "broken"));
        assertThrows(IOException.class, () -> TestMilestoneReader.read(tempDir, "null"));
        assertThrows(IllegalArgumentException.class, () -> TestMilestoneReader.read(tempDir, "../FooTest"));
    }

    @Test
    void readFileReadsOneFileByItsPath() throws IOException {
        Path sweep = write("FooTest.json", "{\"id\":\"FooTest\",\"total\":3}");

        assertEquals(Map.of("id", "FooTest", "totalTests", 3), TestMilestoneReader.readFile(sweep));
        assertThrows(IOException.class, () -> TestMilestoneReader.readFile(write("null.json", "null")));
        assertThrows(IOException.class, () -> TestMilestoneReader.readFile(tempDir.resolve("missing.json")));
    }

    @Test
    void readConfigIsNullWhenMissingOrMalformed() throws IOException {
        assertNull(TestMilestoneReader.readConfig(null));
        assertNull(TestMilestoneReader.readConfig(tempDir));

        write(TestMilestoneReader.CONFIG_FILE, "{\"knownRegressions\":[{\"test\":\"FooIT\"}]}");
        assertEquals(List.of(Map.of("test", "FooIT")),
                TestMilestoneReader.readConfig(tempDir).get("knownRegressions"));

        write(TestMilestoneReader.CONFIG_FILE, "{\"knownRegressions\":");
        assertNull(TestMilestoneReader.readConfig(tempDir));
    }

    @Test
    void countReadsNumbersAndNumericTextOnly() {
        Map<String, Object> milestone = new LinkedHashMap<>();
        milestone.put("totalTests", 12);
        milestone.put("passed", " 10 ");
        milestone.put("failed", "two");
        milestone.put("skipped", null);
        milestone.put("errors", List.of(1));

        assertEquals(12, TestMilestoneReader.count(milestone, "totalTests"));
        assertEquals(10, TestMilestoneReader.count(milestone, "passed"));
        assertNull(TestMilestoneReader.count(milestone, "failed"));
        assertNull(TestMilestoneReader.count(milestone, "skipped"));
        assertNull(TestMilestoneReader.count(milestone, "errors"));
        assertNull(TestMilestoneReader.count(milestone, "absent"));
        assertNull(TestMilestoneReader.count(null, "totalTests"));
    }

    @Test
    void normalizeRenamesTotalInPlace() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", "FooTest");
        raw.put("total", 7);
        raw.put("passed", 7);

        Map<String, Object> normalized = TestMilestoneReader.normalize(raw);

        assertEquals(List.of("id", "totalTests", "passed"), new ArrayList<>(normalized.keySet()));
        assertEquals(7, normalized.get("totalTests"));
        assertTrue(raw.containsKey("total"), "the input is not changed");
    }

    @Test
    void normalizeFollowsTheCommitWithItsShortForm() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("commit", "uncommitted");
        raw.put("branch", "main");

        Map<String, Object> normalized = TestMilestoneReader.normalize(raw);

        assertEquals(List.of("commit", "commitShort", "branch"), new ArrayList<>(normalized.keySet()));
        assertEquals("uncommitted", normalized.get("commitShort"));
    }

    @Test
    void normalizeReturnsAToolRecordAsIs() {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("totalTests", 4);
        tool.put("commit", "0123456789");
        tool.put("commitShort", "0123456");
        Map<String, Object> both = new LinkedHashMap<>();
        both.put("total", 4);
        both.put("totalTests", 5);
        Map<String, Object> numericCommit = new LinkedHashMap<>();
        numericCommit.put("commit", 1234567890);
        Map<String, Object> nullCommit = new LinkedHashMap<>();
        nullCommit.put("commit", null);

        assertSame(tool, TestMilestoneReader.normalize(tool));
        assertSame(both, TestMilestoneReader.normalize(both));
        assertSame(numericCommit, TestMilestoneReader.normalize(numericCommit));
        assertSame(nullCommit, TestMilestoneReader.normalize(nullCommit));
        assertNull(TestMilestoneReader.normalize(null));
    }
}
