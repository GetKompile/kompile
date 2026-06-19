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

import org.junit.jupiter.api.Test;

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
}
