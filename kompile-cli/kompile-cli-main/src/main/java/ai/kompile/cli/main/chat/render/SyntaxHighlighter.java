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

package ai.kompile.cli.main.chat.render;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight, dependency-free inline syntax highlighting for fenced code
 * blocks and file-content previews in the CLI chat renderer.
 * <p>
 * Rather than shipping per-language grammars, languages are mapped to a small
 * set of {@link Family families} (C-like braces languages, hash-comment
 * scripts, Python-style, SQL, and markup) and each family is scanned with a
 * simple single-pass tokenizer: strings, comments, numbers, keywords, types,
 * and literals receive distinct ANSI styles. Output degrades gracefully:
 * when the terminal cannot render ANSI (or the language is unrecognized) the
 * original text is returned untouched.
 * <p>
 * This class is intentionally allocation-light and reflection-free so it is
 * safe for the GraalVM native image without additional configuration.
 */
public class SyntaxHighlighter {

    /** Language families recognized by the highlighter. */
    public enum Family {
        /** Unknown/unstyled — output is passed through unchanged. */
        NONE,
        /** Braces languages: Java, Kotlin, C/C++, Go, Rust, JS/TS, JSON, … */
        CLIKE,
        /** Hash-comment scripts: Shell, Bash, YAML, TOML, Dockerfile, Ruby, Perl, … */
        HASH,
        /** Python-style: decorators, docstrings, snake-case builtins. */
        PYTHON,
        /** SQL: -- and block comments, case-insensitive keywords. */
        SQL,
        /** Markup: XML, HTML, SVG — tags, attributes, quoted values. */
        MARKUP
    }

    // ── Style codes (mirrors TerminalRenderer conventions) ────────────────
    private static final String RST = "\033[0m";
    private static final String KW_STYLE = "\033[1;34m";   // bold blue
    private static final String TYPE_STYLE = "\033[36m";   // cyan
    private static final String STR_STYLE = "\033[32m";    // green
    private static final String NUM_STYLE = "\033[35m";    // magenta
    private static final String COM_STYLE = "\033[2m";     // dim
    private static final String ANN_STYLE = "\033[1;35m";  // bold magenta

    private final boolean ansi;

    public SyntaxHighlighter(TerminalRenderer terminalRenderer) {
        this.ansi = terminalRenderer != null && terminalRenderer.isAnsiEnabled();
    }

    /**
     * Highlight a block of code tagged with a fence language such as
     * {@code java}, {@code py}, or {@code sh}. The hint may also be a
     * filename or full path ({@code src/main/Foo.java}, {@code Dockerfile});
     * unrecognized or null hints return the input unchanged. Never throws.
     */
    public String highlight(String code, String language) {
        if (!ansi || code == null || code.isEmpty()) {
            return code == null ? "" : code;
        }
        Family family = familyOf(language);
        if (family == Family.NONE) {
            // Renderers pass bare fence tags, filenames, and full paths;
            // only the fence-tag table matched before, so every tool-result
            // block hinting with a path silently skipped highlighting.
            family = familyForFilename(language);
            if (family == Family.NONE) {
                return code;
            }
        }
        try {
            if (family == Family.MARKUP) {
                return highlightMarkup(code);
            }
            return highlightCode(code, configFor(family));
        } catch (RuntimeException ignored) {
            return code; // rendering must never break the chat stream
        }
    }

    /**
     * Resolve a fence tag ({@code ```kotlin}) to its family.
     */
    public static Family familyOf(String fenceTag) {
        if (fenceTag == null) return Family.NONE;
        String tag = normalize(fenceTag);
        if (tag.isEmpty()) return Family.NONE;
        Family f = TAG_ALIASES.get(tag);
        return f != null ? f : Family.NONE;
    }

    /**
     * Resolve a filename (e.g. {@code FooService.java}) to its family,
     * checking well-known extension-less names first, then the extension.
     */
    public static Family familyForFilename(String filename) {
        if (filename == null) return Family.NONE;
        String name = normalize(filename);
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        Family byName = FILE_NAMES.get(name);
        if (byName != null) return byName;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return Family.NONE;
        Family f = TAG_ALIASES.get(name.substring(dot + 1));
        return f != null ? f : Family.NONE;
    }

    /**
     * Grep/ripgrep-style result lines carry their source file inline:
     * {@code path/Foo.java:42:code} (match), {@code path/Foo.java-9-code}
     * (context), {@code path/Foo.java:7} (count), or a bare
     * {@code path/Foo.py} (files mode). Extract the embedded filename so
     * renderers can style such lines even when the tool INPUT names no file
     * (e.g. grep's {@code {"pattern":...}} JSON). Returns null when no
     * plausible filename is present; the result may still resolve to
     * {@link Family#NONE} (e.g. {@code notes.txt}) — callers must skip styling.
     */
    public static String filenameFromToolResultLine(String rawLine) {
        if (rawLine == null || rawLine.isBlank()) return null;
        String line = AsciiRenderer.stripAnsi(rawLine).strip();
        if (line.isEmpty()) return null;
        Matcher colon = LINE_NUMBER_COLON.matcher(line);
        if (colon.find()) return filenameCandidate(line.substring(0, colon.start()));
        Matcher dash = LINE_NUMBER_DASH.matcher(line);
        if (dash.find()) return filenameCandidate(line.substring(0, dash.start()));
        return filenameCandidate(line);
    }

    /** {@code path:42:} match prefix or {@code path:42} count suffix. */
    private static final Pattern LINE_NUMBER_COLON = Pattern.compile(":(\\d+)(?::|$)");
    /** {@code path-42-} context-line prefix (grep -B/-A style). */
    private static final Pattern LINE_NUMBER_DASH = Pattern.compile("-(\\d+)-");

    private static String filenameCandidate(String candidate) {
        String c = candidate.strip();
        // Null when the candidate is empty or maps to no highlightable family
        // (plain prose, extensionless names) so callers can skip styling.
        if (c.isEmpty() || familyForFilename(c) == Family.NONE) return null;
        return c;
    }

    private static String normalize(String s) {
        return s.trim().toLowerCase(Locale.ROOT);
    }

    // ════════════════════════════════════════════════════════════════════
    // Language tables
    // ════════════════════════════════════════════════════════════════════

    private static final Map<String, Family> TAG_ALIASES = buildAliases();
    private static final Map<String, Family> FILE_NAMES = buildFileNames();

    private static Map<String, Family> buildAliases() {
        Map<String, Family> m = new HashMap<>();
        for (String t : new String[]{
                "java", "kt", "kotlin", "kts", "scala", "groovy", "gradle",
                "c", "cpp", "c++", "cc", "cxx", "hpp", "hh", "hxx", "h",
                "cs", "csharp", "go", "golang", "rs", "rust", "swift",
                "dart", "m", "mm", "ino",
                "js", "javascript", "jsx", "mjs", "cjs",
                "ts", "typescript", "tsx",
                "json", "json5", "jsonc", "cuda", "cu", "cuh",
                "css", "scss", "less", "proto"}) {
            m.put(t, Family.CLIKE);
        }
        for (String t : new String[]{
                "py", "python", "python3", "ipython", "sage"}) {
            m.put(t, Family.PYTHON);
        }
        for (String t : new String[]{
                "sh", "bash", "zsh", "shell", "fish", "console", "terminal",
                "powershell", "pwsh", "ps1", "bat", "cmd",
                "dockerfile", "makefile", "make", "mk",
                "yaml", "yml", "toml", "ini", "cfg", "conf", "properties",
                "env", "rb", "ruby", "perl", "pl", "r", "lua", "vim",
                "nginx", "apache", "gitignore", "diff", "patch"}) {
            m.put(t, Family.HASH);
        }
        for (String t : new String[]{"sql", "psql", "mysql", "postgresql", "plsql", "tsql", "sqlite"}) {
            m.put(t, Family.SQL);
        }
        for (String t : new String[]{"xml", "html", "htm", "xhtml", "svg",
                "vue", "svelte", "astro", "ftl", "jsp", "xsd", "wsdl", "plist"}) {
            m.put(t, Family.MARKUP);
        }
        return Map.copyOf(m);
    }

    private static Map<String, Family> buildFileNames() {
        Map<String, Family> m = new HashMap<>();
        m.put("dockerfile", Family.HASH);
        m.put("makefile", Family.HASH);
        m.put("jenkinsfile", Family.CLIKE);
        m.put("gemfile", Family.HASH);
        m.put("rakefile", Family.HASH);
        m.put("brewfile", Family.HASH);
        m.put(".gitignore", Family.HASH);
        m.put(".gitattributes", Family.HASH);
        m.put(".editorconfig", Family.HASH);
        m.put(".env", Family.HASH);
        m.put(".bashrc", Family.HASH);
        m.put(".bash_profile", Family.HASH);
        m.put(".zshrc", Family.HASH);
        m.put("cmakelists.txt", Family.HASH);
        return Map.copyOf(m);
    }

    // ── Family configuration ─────────────────────────────────────────────

    private static final class FamilyConfig {
        final Set<String> keywords;
        final Set<String> types;
        final Set<String> literals;
        final boolean hashComments;
        final boolean slashSlashComments;
        final boolean dashDashComments;
        final boolean decorators;
        final boolean tripleQuotes;

        FamilyConfig(Set<String> keywords, Set<String> types, Set<String> literals,
                     boolean hashComments, boolean slashSlashComments,
                     boolean dashDashComments, boolean decorators, boolean tripleQuotes) {
            this.keywords = keywords;
            this.types = types;
            this.literals = literals;
            this.hashComments = hashComments;
            this.slashSlashComments = slashSlashComments;
            this.dashDashComments = dashDashComments;
            this.decorators = decorators;
            this.tripleQuotes = tripleQuotes;
        }
    }

    private static final FamilyConfig CLIKE_CFG = new FamilyConfig(
            set("abstract assert async await break case catch class const constexpr continue "
                    + "debugger declare default defer do else enum export extends final finally fn for "
                    + "foreach friend func function get goto if impl implements import in include inline "
                    + "instanceof interface internal is lazy let lock loop match mod module move mut namespace "
                    + "native new object open operator out override package params private protected pub public "
                    + "readonly record ref register reified require return sealed self set sizeof static strictfp "
                    + "struct super suspend switch synchronized template this throw throws trait transient try "
                    + "typealias typeof union unsafe unsized use using val var virtual volatile where while with yield"),
            set("bool boolean byte char double f32 f64 float i16 i32 i64 i8 int integer long short "
                    + "size_t ssize_t str string uint usize void any bigint never number symbol u16 u32 u64 u8 "
                    + "array arraylist boolean hashmap linkedhashmap list map optional pair set stringbuilder thread"),
            set("true false null nil none undefined"),
            false, true, false, true, false);

    private static final FamilyConfig PY_CFG = new FamilyConfig(
            set("and as assert async await break class continue def del elif else except finally for "
                    + "from global if import in is lambda match case nonlocal not or pass raise return "
                    + "try while with yield"),
            set("abs all any bin bool bytearray bytes callable chr classmethod compile complex delattr "
                    + "dict dir divmod enumerate eval exec filter float format frozenset getattr globals hasattr "
                    + "hash help hex id input int isinstance issubclass iter len list locals map max memoryview min "
                    + "next oct open ord pow print property range repr reversed round self cls set setattr slice "
                    + "sorted staticmethod str sum super tuple type vars zip"),
            set("true false none"),
            true, false, false, true, true);

    private static final FamilyConfig HASH_CFG = new FamilyConfig(
            set("alias bg bind break builtin caller case cd command compgen complete continue coproc "
                    + "declare dirs disown do done echo elif else enable esac eval exec exit export fc fg fi for "
                    + "function getopts hash help history if in jobs kill let local logout mapfile popd printf pushd "
                    + "pushln pwd read readonly return select set shift shopt source suspend test time times trap "
                    + "type typeset ulimit umask unalias unset until wait while sudo apt yum dnf brew port npm npx "
                    + "yarn pnpm pip pip3 conda python python3 java javac mvn gradle git docker kubectl helm terraform "
                    + "make cmake curl wget tar gzip gunzip zip unzip ssh scp rsync chmod chown chgrp ln mkdir rmdir "
                    + "rm cp mv touch cat head tail grep egrep fgrep rg sed awk find xargs sort uniq wc tee which env "
                    + "printenv uname date sleep nohup xargs jq openssl"),
            set(""),
            set("true false yes no on off"),
            true, false, false, false, false);

    private static final FamilyConfig SQL_CFG = new FamilyConfig(
            set("add all alter analyze and as asc begin between by case cast collate column commit constraint "
                    + "convert create cross current_date current_time current_timestamp cursor database declare "
                    + "default delete desc distinct drop else end escape except execute exists explain fetch from "
                    + "full function grant group having if ignore ilike in index inner insert intersect into is "
                    + "join key left like limit lock merge natural not null offset on or order outer over partition "
                    + "primary procedure recursive references returning revoke right rollback rollup row rows schema "
                    + "select session set table temp temporary then to top transaction trigger truncate union unique "
                    + "update user using values view when where while window with"),
            set("bigint binary bit blob bool boolean char clob date datetime dec decimal double float int "
                    + "integer interval nchar numeric nvarchar precision real serial serial8 smallint text time "
                    + "timestamp timestamptz tinyint varbinary varchar"),
            set("null true false"),
            false, true, true, false, false);

    private static FamilyConfig configFor(Family f) {
        switch (f) {
            case CLIKE: return CLIKE_CFG;
            case PYTHON: return PY_CFG;
            case HASH: return HASH_CFG;
            case SQL: return SQL_CFG;
            default: return CLIKE_CFG;
        }
    }

    /** Build an immutable set from a space-separated word list, ignoring duplicates. */
    private static Set<String> set(String spaceSeparated) {
        String trimmed = spaceSeparated.trim();
        if (trimmed.isEmpty()) return Set.of();
        return Set.copyOf(new java.util.LinkedHashSet<>(java.util.Arrays.asList(trimmed.split("\\s+"))));
    }

    // ════════════════════════════════════════════════════════════════════
    // Generic code scanner (CLIKE / HASH / PYTHON / SQL)
    // ════════════════════════════════════════════════════════════════════

    private String highlightCode(String code, FamilyConfig cfg) {
        String[] lines = code.split("\n", -1);
        StringBuilder out = new StringBuilder(code.length() + 64);
        boolean inStarComment = false;   // inside /* … */ or SQL block comment
        String openTriple = null;        // inside python """…""" / '''…'''

        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            int n = line.length();
            int i = 0;
            StringBuilder sb = new StringBuilder(Math.max(16, n));

            // Resume multi-line states first
            if (openTriple != null) {
                int end = line.indexOf(openTriple);
                if (end >= 0) {
                    sb.append(str(line, 0, end + openTriple.length()));
                    i = end + openTriple.length();
                    openTriple = null;
                } else {
                    sb.append(com(line, 0, n));
                    appendLine(out, sb, li < lines.length - 1);
                    continue;
                }
            }
            if (inStarComment) {
                int end = line.indexOf("*/");
                if (end >= 0) {
                    sb.append(com(line, 0, end + 2));
                    i = end + 2;
                    inStarComment = false;
                } else {
                    sb.append(com(line, 0, n));
                    appendLine(out, sb, li < lines.length - 1);
                    continue;
                }
            }

            while (i < n) {
                char c = line.charAt(i);

                // Comments
                if (cfg.hashComments && c == '#') {
                    sb.append(com(line, i, n));
                    i = n;
                    continue;
                }
                if (cfg.dashDashComments && c == '-' && i + 1 < n && line.charAt(i + 1) == '-') {
                    sb.append(com(line, i, n));
                    i = n;
                    continue;
                }
                if (cfg.slashSlashComments && c == '/' && i + 1 < n) {
                    char nx = line.charAt(i + 1);
                    if (nx == '/') {
                        sb.append(com(line, i, n));
                        i = n;
                        continue;
                    }
                    if (nx == '*') {
                        int end = line.indexOf("*/", i + 2);
                        if (end >= 0) {
                            sb.append(com(line, i, end + 2));
                            i = end + 2;
                        } else {
                            sb.append(com(line, i, n));
                            inStarComment = true;
                            i = n;
                        }
                        continue;
                    }
                }

                // Triple-quoted python strings
                if (cfg.tripleQuotes && (c == '"' || c == '\'') && startsWith(line, i, repeat(c, 3))) {
                    String triple = repeat(c, 3);
                    int end = line.indexOf(triple, i + 3);
                    if (end >= 0) {
                        sb.append(str(line, i, end + 3));
                        i = end + 3;
                    } else {
                        sb.append(str(line, i, n));
                        openTriple = triple;
                        i = n;
                    }
                    continue;
                }

                // Quoted strings / char literals
                if (c == '"' || c == '\'') {
                    int j = scanQuoted(line, i, c);
                    sb.append(str(line, i, j));
                    i = j;
                    continue;
                }

                // Numbers (incl. leading-dot floats)
                if (isNumberStart(line, i, n)) {
                    int j = scanNumber(line, i);
                    sb.append(num(line, i, j));
                    i = j;
                    continue;
                }

                // Identifiers / keywords / decorators
                if (isIdentStart(c)) {
                    if (cfg.decorators && c == '@' && i + 1 < n && isIdentPart(line.charAt(i + 1))) {
                        int j = i + 1;
                        while (j < n && isIdentPart(line.charAt(j))) j++;
                        sb.append(ann(line, i, j));
                        i = j;
                        continue;
                    }
                    int j = i;
                    while (j < n && isIdentPart(line.charAt(j))) j++;
                    if (j == i) {
                        // Liveness guard: a start-char that is not a part-char (a bare '@' —
                        // e.g. bash "${arr[@]}" in the HASH family, where decorators are off)
                        // must still advance the cursor or this loop never terminates.
                        // It used to spin at 100% CPU on the dispatch thread while the chat
                        // renderer highlighted a grep result over shell scripts.
                        sb.append(c);
                        i++;
                        continue;
                    }
                    String word = line.substring(i, j);
                    String lower = word.toLowerCase(Locale.ROOT);
                    if (cfg.keywords.contains(lower)) {
                        sb.append(styled(word, KW_STYLE));
                    } else if (cfg.literals.contains(lower)) {
                        sb.append(styled(word, NUM_STYLE));
                    } else if (cfg.types.contains(lower)) {
                        sb.append(styled(word, TYPE_STYLE));
                    } else {
                        sb.append(word);
                    }
                    i = j;
                    continue;
                }

                sb.append(c);
                i++;
            }

            appendLine(out, sb, li < lines.length - 1);
        }
        return out.toString();
    }

    private static void appendLine(StringBuilder out, StringBuilder sb, boolean more) {
        out.append(sb);
        if (more) out.append('\n');
    }

    /** Scan a quoted literal starting at {@code start}; returns end index (exclusive). */
    private static int scanQuoted(String line, int start, char quote) {
        int n = line.length();
        int j = start + 1;
        while (j < n) {
            char c = line.charAt(j);
            if (c == '\\' && j + 1 < n) {
                j += 2;
                continue;
            }
            if (c == quote) {
                return j + 1;
            }
            j++;
        }
        return n; // unterminated on this line
    }

    private static boolean isNumberStart(String line, int i, int n) {
        char c = line.charAt(i);
        if (Character.isDigit(c)) return true;
        return c == '.' && i + 1 < n && Character.isDigit(line.charAt(i + 1));
    }

    private static int scanNumber(String line, int start) {
        int n = line.length();
        int j = start + 1;
        while (j < n) {
            char d = line.charAt(j);
            boolean expSign = (d == '+' || d == '-')
                    && "eEpP".indexOf(Character.toLowerCase(line.charAt(j - 1))) >= 0;
            if (expSign || Character.isLetterOrDigit(d) || d == '_' || d == '.') {
                j++;
            } else {
                break;
            }
        }
        return j;
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$' || c == '@';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static boolean startsWith(String s, int offset, String prefix) {
        return s.startsWith(prefix, offset);
    }

    // ════════════════════════════════════════════════════════════════════
    // Markup scanner (XML / HTML)
    // ════════════════════════════════════════════════════════════════════

    private String highlightMarkup(String code) {
        String[] lines = code.split("\n", -1);
        StringBuilder out = new StringBuilder(code.length() + 64);
        boolean inComment = false;

        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            int n = line.length();
            int i = 0;
            StringBuilder sb = new StringBuilder(Math.max(16, n));

            if (inComment) {
                int end = line.indexOf("-->");
                if (end >= 0) {
                    sb.append(com(line, 0, end + 3));
                    i = end + 3;
                    inComment = false;
                } else {
                    sb.append(com(line, 0, n));
                    appendLine(out, sb, li < lines.length - 1);
                    continue;
                }
            }

            boolean inTag = false;
            while (i < n) {
                if (!inTag && line.startsWith("<!--", i)) {
                    int end = line.indexOf("-->", i + 4);
                    if (end >= 0) {
                        sb.append(com(line, i, end + 3));
                        i = end + 3;
                    } else {
                        sb.append(com(line, i, n));
                        inComment = true;
                        i = n;
                    }
                    continue;
                }
                char c = line.charAt(i);
                if (!inTag && c == '<' && i + 1 < n
                        && (Character.isLetter(line.charAt(i + 1)) || line.charAt(i + 1) == '/'
                        || line.charAt(i + 1) == '?' || line.charAt(i + 1) == '!')) {
                    inTag = true;
                    sb.append(c);
                    i++;
                    continue;
                }
                if (inTag && (c == '"' || c == '\'')) {
                    int j = scanQuoted(line, i, c);
                    sb.append(str(line, i, j));
                    i = j;
                    continue;
                }
                if (inTag && (c == '>' || (c == '/' && i + 1 < n && line.charAt(i + 1) == '>'))) {
                    inTag = false;
                    sb.append(c);
                    i++;
                    if (c == '/') {
                        sb.append('>');
                        i++;
                    }
                    continue;
                }
                if (inTag && (Character.isLetter(c) || c == '_' || c == ':')
                        && sb.length() > 0 && sb.charAt(sb.length() - 1) == '<') {
                    // Tag name right after '<'
                    int j = i;
                    while (j < n && (Character.isLetterOrDigit(line.charAt(j))
                            || line.charAt(j) == '_' || line.charAt(j) == ':' || line.charAt(j) == '-')) {
                        j++;
                    }
                    sb.append(type(line, i, j));
                    i = j;
                    continue;
                }
                sb.append(c);
                i++;
            }

            appendLine(out, sb, li < lines.length - 1);
        }
        return out.toString();
    }

    // ── Styled-span helpers ──────────────────────────────────────────────

    private String styled(String word, String style) {
        return style + word + RST;
    }

    private String com(String line, int from, int to) {
        return COM_STYLE + line.substring(from, Math.min(to, line.length())) + RST;
    }

    private String str(String line, int from, int to) {
        return STR_STYLE + line.substring(from, Math.min(to, line.length())) + RST;
    }

    private String num(String line, int from, int to) {
        return NUM_STYLE + line.substring(from, Math.min(to, line.length())) + RST;
    }

    private String ann(String line, int from, int to) {
        return ANN_STYLE + line.substring(from, Math.min(to, line.length())) + RST;
    }

    private String type(String line, int from, int to) {
        return TYPE_STYLE + line.substring(from, Math.min(to, line.length())) + RST;
    }

    private static String repeat(char c, int count) {
        return String.valueOf(c).repeat(count);
    }
}
