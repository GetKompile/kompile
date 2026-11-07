/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.codeindex;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Comma-separated path globs matched against {@code /}-separated paths below an
 * index root: the include and exclude scope of a code index, and the file filter
 * of a find or replace.
 *
 * <p>The syntax is that of .gitignore. {@code *} and {@code ?} match within one
 * path segment, {@code [...]} is a character class, and a {@code **} segment
 * matches any number of directories, including none. A pattern with a slash at
 * its start or in its middle is anchored at the root; any other pattern matches
 * a file or directory name at any depth. A trailing slash matches directories
 * only, and a pattern that matches a directory matches everything below it.
 * {@code {a,b}} alternatives are expanded. A plain word, with no wildcard and no
 * slash, matches every name that contains it, as these patterns always have.</p>
 *
 * <p>Entries are trimmed, and empty or repeated entries are dropped. Malformed
 * syntax, such as an unclosed {@code [}, is matched literally rather than
 * rejected: an index records its patterns and re-reads them on every
 * maintenance pass.</p>
 */
final class PathPatterns {

    /** Alternatives one entry may expand to; past this, its braces are literal. */
    private static final int MAX_EXPANSIONS = 256;

    private static final PathPatterns NONE = new PathPatterns(List.of());

    private final List<Rule> rules;

    private PathPatterns(List<Rule> rules) {
        this.rules = rules;
    }

    /** Parses a comma-separated list; null or blank is the empty list. */
    static PathPatterns parse(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return NONE;
        }
        Set<String> entries = new LinkedHashSet<>();
        for (String entry : splitTopLevel(commaSeparated.replace('\\', '/'))) {
            if (!entry.isBlank()) {
                entries.add(entry.trim());
            }
        }
        List<Rule> rules = new ArrayList<>();
        for (String entry : entries) {
            for (String alternative : expandBraces(entry)) {
                Rule rule = Rule.compile(alternative);
                if (rule != null) {
                    rules.add(rule);
                }
            }
        }
        return new PathPatterns(List.copyOf(rules));
    }

    /** True if no entry can match a path. */
    boolean isEmpty() {
        return rules.isEmpty();
    }

    /** True if the file at {@code relativePath}, or a directory above it, matches. */
    boolean matchesFile(String relativePath) {
        return matches(relativePath, false);
    }

    /** True if the directory at {@code relativePath}, or one above it, matches. The root never does. */
    boolean matchesDirectory(String relativePath) {
        return matches(relativePath, true);
    }

    private boolean matches(String relativePath, boolean directory) {
        String path = relativePath == null ? "" : trimSlashes(relativePath.replace('\\', '/'));
        if (path.isEmpty()) {
            return false;
        }
        for (Rule rule : rules) {
            if (rule.matches(path, directory)) {
                return true;
            }
        }
        return false;
    }

    /**
     * One expanded entry. A plain {@code word} matches names containing it; otherwise
     * {@code glob} matches a whole name or, when anchored, the whole path.
     */
    private record Rule(String word, Pattern glob, boolean anchored, boolean directoryOnly) {

        static Rule compile(String entry) {
            String body = trimTrailingSlashes(entry);
            boolean directoryOnly = body.length() < entry.length();
            boolean anchored = body.indexOf('/') >= 0;
            List<String> segments = new ArrayList<>();
            for (String segment : body.split("/")) {
                boolean repeatedDoubleStar = segment.equals("**") && !segments.isEmpty()
                        && segments.get(segments.size() - 1).equals("**");
                if (!segment.isEmpty() && !repeatedDoubleStar) {
                    segments.add(segment);
                }
            }
            if (segments.isEmpty()) {
                return null;
            }
            // "dir/**" is everything inside dir, which for a walk is dir itself.
            if (segments.size() > 1 && segments.get(segments.size() - 1).equals("**")) {
                segments.remove(segments.size() - 1);
                directoryOnly = true;
            }
            if (!anchored) {
                String name = segments.get(0);
                if (!directoryOnly && isPlainWord(name)) {
                    return new Rule(name, null, false, false);
                }
                return new Rule(null, compileOrQuote(segmentRegex(name), name), false, directoryOnly);
            }
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < segments.size(); i++) {
                String segment = segments.get(i);
                boolean last = i == segments.size() - 1;
                if (segment.equals("**")) {
                    // Any number of directories, including none; on its own, any path.
                    regex.append(last ? ".+" : "(?:[^/]+/)*");
                } else {
                    regex.append(segmentRegex(segment));
                    if (!last) {
                        regex.append('/');
                    }
                }
            }
            return new Rule(null, compileOrQuote(regex.toString(), String.join("/", segments)), true, directoryOnly);
        }

        boolean matches(String path, boolean directory) {
            // A matching directory above the entry covers everything below it.
            for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
                if (matchesEntry(path.substring(0, slash))) {
                    return true;
                }
            }
            return (directory || !directoryOnly) && matchesEntry(path);
        }

        private boolean matchesEntry(String path) {
            if (anchored) {
                return glob.matcher(path).matches();
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            return word != null ? name.contains(word) : glob.matcher(name).matches();
        }
    }

    private static boolean isPlainWord(String name) {
        return name.indexOf('*') < 0 && name.indexOf('?') < 0 && name.indexOf('[') < 0;
    }

    /** The regex for one path segment; nothing in it matches a slash. */
    private static String segmentRegex(String segment) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == '*') {
                regex.append("[^/]*");
                while (i + 1 < segment.length() && segment.charAt(i + 1) == '*') {
                    i++;
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else if (c == '[' && classEnd(segment, i) > i) {
                int end = classEnd(segment, i);
                regex.append(characterClass(segment.substring(i + 1, end)));
                i = end;
            } else {
                appendLiteral(regex, c);
            }
        }
        return regex.toString();
    }

    /** The ']' closing the class opened at {@code open}, or -1; a ']' first in a class is a member. */
    private static int classEnd(String segment, int open) {
        int first = open + 1;
        if (first < segment.length() && (segment.charAt(first) == '!' || segment.charAt(first) == '^')) {
            first++;
        }
        return segment.indexOf(']', first + 1);
    }

    private static String characterClass(String body) {
        boolean negated = body.charAt(0) == '!' || body.charAt(0) == '^';
        String members = negated ? body.substring(1) : body;
        StringBuilder regex = new StringBuilder(negated ? "[^/" : "(?!/)[");
        for (int i = 0; i < members.length(); i++) {
            char c = members.charAt(i);
            boolean literalDash = c == '-' && (i == 0 || i == members.length() - 1);
            if (c == '[' || c == ']' || c == '&' || c == '^' || c == '\\' || literalDash) {
                regex.append('\\');
            }
            regex.append(c);
        }
        return regex.append(']').toString();
    }

    private static void appendLiteral(StringBuilder regex, char c) {
        boolean alphanumeric = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
        if (!alphanumeric && c < 128) {
            regex.append('\\');
        }
        regex.append(c);
    }

    private static Pattern compileOrQuote(String regex, String literal) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException malformed) {
            return Pattern.compile(Pattern.quote(literal));
        }
    }

    /** Splits at commas outside braces; with an unclosed brace, at every comma. */
    private static List<String> splitTopLevel(String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(text.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(text.substring(start));
        return depth == 0 ? parts : List.of(text.split(",", -1));
    }

    /** Expands {a,b} alternatives, nested ones included; an unclosed brace is literal. */
    private static List<String> expandBraces(String entry) {
        List<String> expanded = new ArrayList<>();
        expand("", entry, expanded);
        return expanded.size() > MAX_EXPANSIONS ? List.of(entry) : expanded;
    }

    private static void expand(String prefix, String rest, List<String> out) {
        if (out.size() > MAX_EXPANSIONS) {
            return;
        }
        int open = rest.indexOf('{');
        int close = -1;
        while (open >= 0 && (close = matchingBrace(rest, open)) < 0) {
            open = rest.indexOf('{', open + 1);
        }
        if (open < 0) {
            out.add(prefix + rest);
            return;
        }
        String head = prefix + rest.substring(0, open);
        String tail = rest.substring(close + 1);
        for (String alternative : splitTopLevel(rest.substring(open + 1, close))) {
            expand(head, alternative.trim() + tail, out);
        }
    }

    private static int matchingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String trimSlashes(String path) {
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') {
            start++;
        }
        return trimTrailingSlashes(path.substring(start));
    }

    private static String trimTrailingSlashes(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '/') {
            end--;
        }
        return text.substring(0, end);
    }
}
