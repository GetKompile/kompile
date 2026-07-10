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

package ai.kompile.cli.main.chat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression test for grep glob handling.
 *
 * <p>The grep fallback forwarded a ripgrep/git-style glob straight to {@code grep --include},
 * which matches the file <em>basename</em> with fnmatch. Any glob containing {@code /}
 * (e.g. {@code **}{@code /pom.xml}) could never match a basename, so the search returned a
 * silent "No matches found" even though the pattern was in every file. {@link
 * GrepTool#globToGrepIncludes(String)} translates the glob to basename include(s) so grep can
 * actually match.</p>
 */
class GrepToolGlobTest {

    @Test
    void recursiveGlobReducesToBasename() {
        assertEquals(List.of("pom.xml"), GrepTool.globToGrepIncludes("**/pom.xml"));
        assertEquals(List.of("*.xml"), GrepTool.globToGrepIncludes("**/*.xml"));
        assertEquals(List.of("*.java"), GrepTool.globToGrepIncludes("src/**/*.java"));
    }

    @Test
    void plainBasenameGlobIsUnchanged() {
        assertEquals(List.of("pom.xml"), GrepTool.globToGrepIncludes("pom.xml"));
        assertEquals(List.of("*.xml"), GrepTool.globToGrepIncludes("*.xml"));
    }

    @Test
    void braceGroupExpandsToMultipleIncludes() {
        assertEquals(List.of("*.ts", "*.tsx"), GrepTool.globToGrepIncludes("*.{ts,tsx}"));
        assertEquals(List.of("*.ts", "*.tsx"), GrepTool.globToGrepIncludes("**/*.{ts,tsx}"));
    }

    @Test
    void commaSeparatedGlobListSplitsOutsideBraceGroups() {
        assertEquals(List.of("*.cpp", "*.cu", "*.h"),
                GrepTool.splitGlobPatterns("*.cpp,*.cu,*.h"));
        assertEquals(List.of("**/*.{ts,tsx}", "*.java"),
                GrepTool.splitGlobPatterns("**/*.{ts,tsx},*.java"));
    }

    @Test
    void commaSeparatedGlobListExpandsToMultipleIncludes() {
        assertEquals(List.of("*.cpp", "*.cu", "*.h"),
                GrepTool.globToGrepIncludes("*.cpp,*.cu,*.h"));
        assertEquals(List.of("*.ts", "*.tsx", "*.java"),
                GrepTool.globToGrepIncludes("**/*.{ts,tsx},*.java"));
    }

    @Test
    void schemaConstrainsClientFacingContract() {
        JsonNode schema = new GrepTool().parameterSchema();
        assertFalse(schema.path("additionalProperties").asBoolean(true));
        JsonNode outputMode = schema.path("properties").path("output_mode");
        assertEquals("content", outputMode.path("default").asText());
        assertEquals(List.of("content", "files", "count"),
                List.of(outputMode.path("enum").get(0).asText(),
                        outputMode.path("enum").get(1).asText(),
                        outputMode.path("enum").get(2).asText()));
        assertEquals(0, schema.path("properties").path("context_lines").path("minimum").asInt());
        assertTrue(new GrepTool().compactHint().length() <= 200);
        assertTrue(new GrepTool().compactHint().contains("output_mode=content|files|count"));
    }

    /** The core invariant: no translated include may contain '/', or grep --include silently drops it. */
    @Test
    void noTranslatedIncludeContainsSlash() {
        for (String glob : List.of("**/pom.xml", "**/*.xml", "src/**/*.java", "*.{ts,tsx}", "a/b/c.txt")) {
            List<String> includes = GrepTool.globToGrepIncludes(glob);
            assertFalse(includes.isEmpty(), "no includes produced for glob: " + glob);
            for (String inc : includes) {
                assertFalse(inc.contains("/"), "include still contains '/': '" + inc + "' from glob '" + glob + "'");
            }
        }
    }

    @Test
    void rgCommandIncludesBinaryExtensionExcludes() throws Exception {
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("rg-binary-extension-filter-test");
            List<String> cmd = GrepTool.buildRipgrepCommand(
                    "TOKEN", tmp, List.of("*.java"), "content", false, 0, false,
                    SearchExclusions.fromLines(List.of("ignored")));

            for (String binaryGlob : SearchExclusions.binaryFileGlobs()) {
                assertTrue(cmd.contains("!" + binaryGlob),
                        "missing binary extension exclude pattern: " + binaryGlob);
            }
            assertTrue(cmd.contains("*.java"), "caller glob should be present");
            assertTrue(cmd.contains("--no-ignore"));
            assertTrue(cmd.contains("-I"), "rg should skip extensionless binary files without warning output");
            assertTrue(cmd.contains("--line-buffered"), "rg output should stream promptly to the MCP server");
            assertFalse(cmd.contains("!*.txt"), "text files must remain searchable");
        } finally {
            if (tmp != null) {
                Files.deleteIfExists(tmp);
            }
        }
    }

    @Test
    void rgCommandRemapsGitignoreExcludesForSubdirectorySearchRoot() {
        Path repo = Path.of("/repo");
        Path project = repo.resolve("kompile-fpna-v8");
        List<String> cmd = GrepTool.buildRipgrepCommand(
                "TOKEN", project, List.of(), "content", false, 0, false,
                SearchExclusions.fromLines(List.of("kompile-fpna-v8/log-archives/"), repo));

        assertTrue(cmd.contains("!log-archives"), "subdirectory search should prune the ignored child dir");
        assertFalse(cmd.contains("!kompile-fpna-v8/log-archives"),
                "repo-relative exclude would not match paths when rg searches from the subdirectory");
    }

    @Test
    void rgContentCommandAlwaysIncludesFilenames() {
        List<String> cmd = GrepTool.buildRipgrepCommand(
                "TOKEN", Path.of("/repo/src/File.java"), List.of(), "content", false, 0, false,
                SearchExclusions.fromLines(List.of()));

        assertTrue(cmd.contains("--with-filename"),
                "single-file rg searches otherwise return line:content instead of file:line:content");
    }

    @Test
    void rgCountCommandDoesNotCapMatchesPerFile() {
        List<String> cmd = GrepTool.buildRipgrepCommand(
                "TOKEN", Path.of("/repo"), List.of(), "count", false, 0, false,
                SearchExclusions.fromLines(List.of()));

        assertTrue(cmd.contains("--with-filename"));
        assertFalse(cmd.contains("--max-count"),
                "rg --max-count is per-file and would undercount high-hit files in count mode");
    }

    // -----------------------------------------------------------------------
    // New regression tests for fix #1 (isRipgrepVersion) and fix #2 (detectGrepFlavor)
    // -----------------------------------------------------------------------

    /** Fix #1: deep-path Java file glob must reduce to just the basename. */
    @Test
    void deepPathJavaGlobReducesToBasename() {
        assertEquals(List.of("AgentChatRequest.java"),
                GrepTool.globToGrepIncludes("**/web/dto/AgentChatRequest.java"),
                "**/dir/File.java must reduce to exactly File.java so grep --include can match");
    }

    /** Fix #1: brace expansion of *.{ts,tsx} must yield exactly [*.ts, *.tsx]. */
    @Test
    void tsTsxBraceExpandsToTwoIncludes() {
        assertEquals(List.of("*.ts", "*.tsx"),
                GrepTool.globToGrepIncludes("*.{ts,tsx}"),
                "*.{ts,tsx} must expand to exactly [*.ts, *.tsx]");
    }

    // isRipgrepVersion ---

    /** Fix #1: real ripgrep version string is accepted. */
    @Test
    void isRipgrepVersion_realRipgrepAccepted() {
        assertTrue(GrepTool.isRipgrepVersion("ripgrep 14.1.0"),
                "real ripgrep version line must be accepted");
    }

    /** Fix #1: ugrep version string is rejected (not ripgrep). */
    @Test
    void isRipgrepVersion_ugrepRejected() {
        assertFalse(GrepTool.isRipgrepVersion("ugrep 7.5.0 x86_64-pc-linux-gnu +sse2 +avx2; -P:pcre2jit"),
                "ugrep version must be rejected as not-ripgrep");
    }

    /** Fix #1: empty / null version string is rejected. */
    @Test
    void isRipgrepVersion_emptyRejected() {
        assertFalse(GrepTool.isRipgrepVersion(""),  "empty version must be rejected");
        assertFalse(GrepTool.isRipgrepVersion(null), "null version must be rejected");
    }

    // detectGrepFlavor ---

    /** Fix #2: ugrep version line is classified as UGREP. */
    @Test
    void detectGrepFlavor_ugrepRecognised() {
        assertEquals(GrepTool.GrepFlavor.UGREP,
                GrepTool.detectGrepFlavor("ugrep 7.5.0 x86_64-pc-linux-gnu +sse2; -P:pcre2jit"),
                "ugrep version must be classified as UGREP");
    }

    /** Fix #2: GNU grep version line is classified as GNU. */
    @Test
    void detectGrepFlavor_gnuRecognised() {
        assertEquals(GrepTool.GrepFlavor.GNU,
                GrepTool.detectGrepFlavor("grep (GNU grep) 3.7"),
                "GNU grep version must be classified as GNU");
    }

    /** Fix #2: unknown/empty version yields OTHER. */
    @Test
    void detectGrepFlavor_unknownYieldsOther() {
        assertEquals(GrepTool.GrepFlavor.OTHER,
                GrepTool.detectGrepFlavor(""),
                "empty version must yield OTHER");
        assertEquals(GrepTool.GrepFlavor.OTHER,
                GrepTool.detectGrepFlavor(null),
                "null version must yield OTHER");
        assertEquals(GrepTool.GrepFlavor.OTHER,
                GrepTool.detectGrepFlavor("some-other-grep 1.0"),
                "unrecognised version must yield OTHER");
    }
}
