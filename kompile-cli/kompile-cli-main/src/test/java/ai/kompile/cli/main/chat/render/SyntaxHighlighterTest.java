package ai.kompile.cli.main.chat.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the dependency-free {@link SyntaxHighlighter}: language family
 * resolution (fence tags and filenames), per-family token styling, ANSI
 * passthrough behavior, multi-line state (block comments, triple quotes),
 * and content preservation guarantees.
 */
class SyntaxHighlighterTest {

    private final SyntaxHighlighter ansi = new SyntaxHighlighter(new TerminalRenderer(true));
    private final SyntaxHighlighter plain = new SyntaxHighlighter(new TerminalRenderer(false));

    // ── Family resolution ───────────────────────────────────────────────

    @Test
    void fenceTagsResolveToFamilies() {
        assertEquals(SyntaxHighlighter.Family.CLIKE,
                SyntaxHighlighter.familyOf("java"));
        assertEquals(SyntaxHighlighter.Family.CLIKE,
                SyntaxHighlighter.familyOf("Kotlin")); // case-insensitive
        assertEquals(SyntaxHighlighter.Family.PYTHON,
                SyntaxHighlighter.familyOf("py"));
        assertEquals(SyntaxHighlighter.Family.HASH,
                SyntaxHighlighter.familyOf("bash"));
        assertEquals(SyntaxHighlighter.Family.SQL,
                SyntaxHighlighter.familyOf("sql"));
        assertEquals(SyntaxHighlighter.Family.MARKUP,
                SyntaxHighlighter.familyOf("xml"));
    }

    @Test
    void unknownOrNullFenceTagsAreNone() {
        assertEquals(SyntaxHighlighter.Family.NONE, SyntaxHighlighter.familyOf(null));
        assertEquals(SyntaxHighlighter.Family.NONE, SyntaxHighlighter.familyOf(""));
        assertEquals(SyntaxHighlighter.Family.NONE, SyntaxHighlighter.familyOf("brainfuck"));
    }

    @Test
    void filenamesResolveViaExtensionAndSpecialNames() {
        assertEquals(SyntaxHighlighter.Family.CLIKE,
                SyntaxHighlighter.familyForFilename("src/main/FooService.java"));
        assertEquals(SyntaxHighlighter.Family.PYTHON,
                SyntaxHighlighter.familyForFilename("script.py"));
        assertEquals(SyntaxHighlighter.Family.HASH,
                SyntaxHighlighter.familyForFilename("Dockerfile"));
        assertEquals(SyntaxHighlighter.Family.HASH,
                SyntaxHighlighter.familyForFilename(".gitignore"));
        assertEquals(SyntaxHighlighter.Family.MARKUP,
                SyntaxHighlighter.familyForFilename("/a/b/pom.xml"));
        assertEquals(SyntaxHighlighter.Family.NONE,
                SyntaxHighlighter.familyForFilename("README"));
        assertEquals(SyntaxHighlighter.Family.NONE,
                SyntaxHighlighter.familyForFilename(null));
    }

    // ── Styling ─────────────────────────────────────────────────────────

    @Test
    void javaKeywordsStyled() {
        String out = ansi.highlight("public class Foo {", "java");
        assertTrue(out.contains("\033[1;34mpublic\033[0m"), "keyword span: " + out);
        assertTrue(out.contains("\033[1;34mclass\033[0m"), "keyword span: " + out);
    }

    @Test
    void stringsAndNumbersStyled() {
        String out = ansi.highlight("String s = \"hi\";\nint n = 42;", "java");
        assertTrue(out.contains("\033[32m\"hi\"\033[0m"), "string span: " + out);
        assertTrue(out.contains("\033[35m42\033[0m"), "number span: " + out);
    }

    @Test
    void pythonTripleQuoteSpansLines() {
        String code = "def f():\n    \"\"\"doc\n    string\"\"\"\n    return 1";
        String out = ansi.highlight(code, "python");
        // Triple-quoted strings are STRING-styled (green), spanning lines
        assertTrue(out.contains("\033[32m\"\"\"doc"), "opening line string-green: " + out);
        assertTrue(out.contains("string\"\"\"\033[0m"), "closing line string-green: " + out);
        assertTrue(out.contains("\033[1;34mreturn\033[0m"), "code after docstring styled: " + out);
    }

    @Test
    void blockCommentStateCarriesAcrossLines() {
        String code = "int x = 1; /* start\nstill comment */ int y = 2;";
        String out = ansi.highlight(code, "c");
        // Inside the block comment: dimmed. After it closes: normal styling resumes.
        assertTrue(out.contains("\033[2mstill comment */"), "comment span dimmed: " + out);
        assertFalse(out.contains("\033[2mint y = 2;"), "code after close must NOT stay dimmed: " + out);
    }

    @Test
    void shellHashCommentToEndOfLine() {
        String out = ansi.highlight("export PATH=/bin # bootstrap", "sh");
        assertTrue(out.contains("\033[1;34mexport\033[0m"), "shell keyword styled: " + out);
        assertTrue(out.contains("# bootstrap\033[0m"), "hash comment dimmed: " + out);
    }

    /**
     * Regression: renderers pass file paths (not just fence tags) as the
     * language hint for read/grep tool results. Before the filename fallback
     * existed in {@link SyntaxHighlighter#highlight}, every such block was
     * silently passed through unstyled — "nothing actually worked".
     */
    @Test
    void fileAndPathHintsResolveInHighlight() {
        String code = "public class Foo {}";
        String out = ansi.highlight(code, "/proj/src/main/Foo.java");
        assertTrue(out.contains("\033[1;34mpublic\033[0m"), "full-path hint styled: " + out);

        String docker = ansi.highlight("docker build .", "Dockerfile");
        assertTrue(docker.contains("\033[1;34mdocker\033[0m"), "extensionless name styled: " + docker);

        String plainOut = ansi.highlight(code, "README");
        assertSame(code, plainOut, "extensionless non-code name stays passthrough: " + plainOut);
    }

    @Test
    void sqlDashDashComment() {
        String out = ansi.highlight("SELECT id -- primary key\nFROM users;", "sql");
        assertTrue(out.contains("\033[1;34mSELECT\033[0m"), "SQL keyword styled: " + out);
        assertTrue(out.contains("-- primary key\033[0m"), "dash-dash comment dimmed: " + out);
    }

    @Test
    void xmlTagNamesStyled() {
        String out = ansi.highlight("<project>\n  <name>demo</name>\n</project>", "xml");
        assertTrue(out.contains("\033[36mproject\033[0m"), "tag name styled: " + out);
        assertTrue(out.contains("\033[36mname\033[0m"), "nested tag name styled: " + out);
    }

    @Test
    void decoratorsStyledInPython() {
        String out = ansi.highlight("@staticmethod\ndef f(): pass", "python");
        assertTrue(out.contains("\033[1;35m@staticmethod\033[0m"), "decorator styled: " + out);
    }

    /**
     * Regression: a bare {@code @} is an identifier-start char but NOT an
     * identifier-part char, and the HASH family (shell/ini/yaml) has
     * decorators disabled. The identifier branch previously made no progress
     * on it — {@code j == i} — and spun forever at 100% CPU on the chat
     * dispatch thread while rendering a grep result over shell scripts
     * (bash array expansions like {@code "${RUNTIME_SOURCE_ROOTS[@]}"}).
     * Highlighting such a line must terminate quickly and preserve the text.
     */
    @Test
    void bareAtSignInHashFamilyTerminates() {
        String line = "SOURCE_MANIFEST_SHA256=\"$(sdx_git_source_manifest_sha256 \"$DL4J_ROOT\" "
                + "\"${RUNTIME_SOURCE_ROOTS[@]}\")\"";
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            String out = ansi.highlight(line, "build-android-cpu-importer-sdk.sh");
            assertEquals(line, AsciiRenderer.stripAnsi(out),
                    "content preserved around bare '@': " + out);
        });
    }

    @Test
    void unknownLanguageReturnsInputUnchanged() {
        String code = "fn main() {}";
        assertSame(code, ansi.highlight(code, "brainfuck"),
                "Unrecognized language must be passed through untouched");
        assertSame(code, plain.highlight(code, "java"),
                "ANSI-off must be passed through untouched");
        assertEquals("", ansi.highlight(null, "java"));
        assertEquals("", ansi.highlight("", "java"));
    }

    @Test
    void plainContentAlwaysPreserved() {
        String code = "if (x == null) return;";
        String out = ansi.highlight(code, "kotlin");
        for (String token : new String[]{"if", "(x == ", "null)", "return;"}) {
            assertTrue(stripAnsi(out).contains(token), "token lost: " + token);
        }
    }

    @Test
    void lineCountPreserved() {
        String code = "a\nb\nc\nd";
        String out = ansi.highlight(code, "java");
        assertEquals(4, out.split("\n", -1).length, "line count must not change");
    }

    // ── Tool-result line filename inference ─────────────────────────────

    @Test
    void grepMatchLinesExposeEmbeddedFilename() {
        assertEquals("src/App.java",
                SyntaxHighlighter.filenameFromToolResultLine("src/App.java:42:public class Foo {"));
        assertEquals("tools/GrepTool.java",
                SyntaxHighlighter.filenameFromToolResultLine("tools/GrepTool.java-9-// context line"));
    }

    @Test
    void countAndFilesModeLinesExposeFilename() {
        // count mode: path:matchCount
        assertEquals("pom.xml", SyntaxHighlighter.filenameFromToolResultLine("pom.xml:12"));
        // files mode: bare path
        assertEquals("script.py", SyntaxHighlighter.filenameFromToolResultLine("script.py"));
    }

    @Test
    void nonFilenameLinesReturnNull() {
        assertNull(SyntaxHighlighter.filenameFromToolResultLine("plain narrative line"));
        assertNull(SyntaxHighlighter.filenameFromToolResultLine(""));
        assertNull(SyntaxHighlighter.filenameFromToolResultLine(null));
        assertNull(SyntaxHighlighter.filenameFromToolResultLine(":42:orphan"));
    }

    /**
     * Regression: grep tool results embed their source path per line, so the
     * renderer can style them even though the grep INPUT ({"pattern":...})
     * names no file. Each line must resolve through the filename tables.
     */
    @Test
    void grepStyleResultLinesHighlightWithEmbeddedHint() {
        String matchLine = "src/App.java:42:public class Foo {";
        String out = ansi.highlight(matchLine,
                SyntaxHighlighter.filenameFromToolResultLine(matchLine));
        assertTrue(out.contains("\033[1;34mpublic\033[0m"), "embedded-path match styled: " + out);

        String pyLine = "app/main.py:8:def run():";
        String pyOut = ansi.highlight(pyLine,
                SyntaxHighlighter.filenameFromToolResultLine(pyLine));
        assertTrue(pyOut.contains("\033[1;34mdef\033[0m"), "python context styled: " + pyOut);

        // Bare count line ("path:count") carries no stylable tokens — the
        // text must survive untouched even though the filename resolves.
        String countLine = "pom.xml:12";
        String xmlOut = ansi.highlight(countLine,
                SyntaxHighlighter.filenameFromToolResultLine(countLine));
        assertEquals(countLine, AsciiRenderer.stripAnsi(xmlOut),
                "count line passthrough: " + xmlOut);
    }

    private static String stripAnsi(String s) {
        return AsciiRenderer.stripAnsi(s);
    }
}
