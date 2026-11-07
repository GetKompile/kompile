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

package ai.kompile.cli.main.codeindex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scope patterns read like .gitignore lines, matched against paths below the
 * index root, and a plain word keeps matching every name that contains it.
 */
class PathPatternsTest {

    @Test
    void entriesAreTrimmedAndEmptyOrRepeatedEntriesIgnored() {
        PathPatterns patterns = PathPatterns.parse(" *.java, ,*.py,*.java,");

        assertTrue(patterns.matchesFile("A.java"));
        assertTrue(patterns.matchesFile("b.py"));
        assertFalse(patterns.matchesFile("c.js"));
    }

    @Test
    void nullBlankOrOnlyCommasIsEmpty() {
        assertTrue(PathPatterns.parse(null).isEmpty());
        assertTrue(PathPatterns.parse("  ").isEmpty());
        assertTrue(PathPatterns.parse(" , ,").isEmpty());
        assertFalse(PathPatterns.parse(null).matchesFile("A.java"));
    }

    @Test
    void anEmptyEntryMatchesNothing() {
        PathPatterns patterns = PathPatterns.parse(",zzz");

        assertFalse(patterns.matchesFile("A.java"));
        assertFalse(patterns.matchesDirectory("src"));
        assertTrue(patterns.matchesFile("zzz.java"));
    }

    @Test
    void doubleStarSegmentSpansAnyNumberOfDirectories() {
        PathPatterns generated = PathPatterns.parse("**/generated/**");

        assertTrue(generated.matchesDirectory("generated"));
        assertTrue(generated.matchesDirectory("a/b/generated"));
        assertTrue(generated.matchesFile("a/generated/B.java"));
        assertFalse(generated.matchesDirectory("a/generatedX"));
        assertFalse(generated.matchesFile("generated"), "a pattern ending in /** names a directory");

        PathPatterns repeated = PathPatterns.parse("a/**/**/b");
        assertTrue(repeated.matchesFile("a/b"));
        assertTrue(repeated.matchesFile("a/x/y/b"));
    }

    @Test
    void aSlashAnchorsThePatternAtTheRoot() {
        PathPatterns sources = PathPatterns.parse("src/**/*.java");
        assertTrue(sources.matchesFile("src/A.java"));
        assertTrue(sources.matchesFile("src/a/b/A.java"));
        assertFalse(sources.matchesFile("lib/src/A.java"));
        assertFalse(sources.matchesFile("src/A.py"));

        assertFalse(PathPatterns.parse("src/*.java").matchesFile("src/a/A.java"));

        PathPatterns rootBuild = PathPatterns.parse("/build");
        assertTrue(rootBuild.matchesDirectory("build"));
        assertFalse(rootBuild.matchesDirectory("a/build"));
    }

    @Test
    void aPatternMatchingADirectoryMatchesEverythingBelowIt() {
        PathPatterns main = PathPatterns.parse("src/main");

        assertTrue(main.matchesFile("src/main/A.java"));
        assertTrue(main.matchesFile("src/main/a/B.java"));
        assertFalse(main.matchesFile("src/mainline/A.java"));
    }

    @Test
    void aTrailingSlashMatchesDirectoriesOnly() {
        PathPatterns gen = PathPatterns.parse("gen/");

        assertTrue(gen.matchesDirectory("x/gen"));
        assertTrue(gen.matchesFile("x/gen/A.java"));
        assertFalse(gen.matchesFile("x/gen"));
        assertFalse(gen.matchesDirectory("x/generated"));
    }

    @Test
    void aPlainWordMatchesAnyNameContainingIt() {
        assertTrue(PathPatterns.parse("Test").matchesFile("src/FooTest.java"));
        assertTrue(PathPatterns.parse("generated").matchesDirectory("generated-sources"));
        assertTrue(PathPatterns.parse("build").matchesDirectory("a/build"));
        assertFalse(PathPatterns.parse("Test").matchesFile("src/Foo.java"));
    }

    @Test
    void wildcardsStayWithinOneSegment() {
        PathPatterns tests = PathPatterns.parse("*Test.java");
        assertTrue(tests.matchesFile("FooTest.java"));
        assertTrue(tests.matchesFile("a/b/BarTest.java"));
        assertFalse(tests.matchesFile("FooTest.java.bak"));

        assertTrue(PathPatterns.parse("Foo?.java").matchesFile("Foo1.java"));
        assertFalse(PathPatterns.parse("Foo?.java").matchesFile("Foo.java"));
        assertFalse(PathPatterns.parse("a/*/c").matchesDirectory("a/b/x/c"));
        assertTrue(PathPatterns.parse("Foo**.java").matchesFile("FooBar.java"));
        assertFalse(PathPatterns.parse("Foo**.java").matchesFile("Foo/Bar.java"));
    }

    @Test
    void characterClassesMatchOneCharacter() {
        PathPatterns digits = PathPatterns.parse("v[0-9].txt");
        assertTrue(digits.matchesFile("v1.txt"));
        assertFalse(digits.matchesFile("vx.txt"));

        PathPatterns notA = PathPatterns.parse("[!a]x");
        assertTrue(notA.matchesFile("bx"));
        assertFalse(notA.matchesFile("ax"));
    }

    @Test
    void bracesExpandToAlternatives() {
        PathPatterns patterns = PathPatterns.parse("*.{java,kt},*.py");
        assertTrue(patterns.matchesFile("A.java"));
        assertTrue(patterns.matchesFile("A.kt"));
        assertTrue(patterns.matchesFile("a.py"));
        assertFalse(patterns.matchesFile("A.js"));

        assertTrue(PathPatterns.parse("src/{main,test/{java,kotlin}}/**").matchesFile("src/test/kotlin/A.kt"));
        assertTrue(PathPatterns.parse("*.{java, py}").matchesFile("a.py"));
    }

    @Test
    void malformedSyntaxIsMatchedLiterally() {
        assertTrue(PathPatterns.parse("[abc").matchesFile("x/[abc"));
        assertFalse(PathPatterns.parse("[abc").matchesFile("a"));
        assertTrue(PathPatterns.parse("a{b").matchesFile("a{b"));
        assertTrue(PathPatterns.parse("[z-a]x").matchesFile("[z-a]x"));
    }

    @Test
    void backslashesAreSeparators() {
        PathPatterns sources = PathPatterns.parse("src\\**\\*.java");

        assertTrue(sources.matchesFile("src/a/A.java"));
        assertTrue(sources.matchesFile("src\\a\\A.java"));
    }

    @Test
    void theRootItselfNeverMatches() {
        assertFalse(PathPatterns.parse("**").matchesDirectory(""));
        assertFalse(PathPatterns.parse("**").matchesFile(""));
        assertTrue(PathPatterns.parse("**").matchesFile("a/b/C.java"));
    }

    @Test
    void anOverlongExpansionKeepsItsBracesLiteral() {
        String nine = "{a,b,c,d,e,f,g,h,i}";
        PathPatterns patterns = PathPatterns.parse(nine + nine + nine);

        assertFalse(patterns.matchesFile("aaa"), "729 alternatives is past the expansion limit");
        assertTrue(patterns.matchesFile(nine + nine + nine));
    }
}
