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

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

/**
 * Shared exact-string edit applier used by {@code edit} and {@code edit_batch}.
 *
 * <p>Matching ladder (mirrors {@link ApplyPatchFormat}'s hunk locator so edit and
 * patch tolerate the same drift):
 * <ol>
 *   <li>exact substring match</li>
 *   <li>line-block match ignoring trailing whitespace (also absorbs CRLF files)</li>
 *   <li>line-block match ignoring leading+trailing whitespace</li>
 * </ol>
 *
 * <p>Fuzzy tiers enforce the same uniqueness contract as the exact tier. On no
 * match the thrown message carries a diagnostic (nearest candidate line, pasted
 * line-number-prefix detection) so the caller can fix the next attempt without
 * re-reading the whole file.
 */
final class EditEngine {

    /** Result of a successful application. */
    record Applied(String newContent, String matchType, int replacements) {}

    /** The edit could not be applied; the message is agent-facing guidance. */
    static final class NoMatchException extends Exception {
        NoMatchException(String message) { super(message); }
    }

    private static final Pattern LINE_NUMBER_PREFIX =
            Pattern.compile("(?m)^\\s*[0-9]+(\\t|\\u21E5| {2})");

    private EditEngine() {
    }

    static Applied apply(String content, String oldString, String newString, boolean replaceAll)
            throws NoMatchException {
        int exactCount = countOccurrences(content, oldString);

        if (exactCount > 0) {
            if (!replaceAll && exactCount > 1) {
                throw new NoMatchException("old_string found " + exactCount + " times (lines "
                        + matchLineNumbers(content, oldString) + "). Provide more surrounding "
                        + "context to make it unique, or set replace_all=true.");
            }
            String replaced = replaceAll
                    ? content.replace(oldString, newString)
                    : replaceFirst(content, oldString, newString);
            return new Applied(replaced, "exact", replaceAll ? exactCount : 1);
        }

        // Fuzzy line-block ladder: trailing-whitespace-insensitive first (also
        // covers CRLF files), then fully whitespace-insensitive.
        List<String> contentLines = splitLines(content);
        List<String> oldLines = splitLines(oldString);
        List<Integer> matches = findBlocks(contentLines, oldLines,
                (a, b) -> a.stripTrailing().equals(b.stripTrailing()));
        String matchType = "whitespace-tolerant";
        if (matches.isEmpty()) {
            matches = findBlocks(contentLines, oldLines, (a, b) -> a.strip().equals(b.strip()));
            matchType = "trimmed";
        }

        if (matches.isEmpty()) {
            throw new NoMatchException("old_string not found in file. " + diagnose(contentLines, oldLines));
        }
        if (!replaceAll && matches.size() > 1) {
            throw new NoMatchException("old_string matches " + matches.size()
                    + " blocks after whitespace normalization (starting at lines "
                    + firstLineNumbers(matches) + "). Provide more surrounding context "
                    + "to make it unique, or set replace_all=true.");
        }

        List<String> newLines = splitLines(newString);
        List<String> result = new ArrayList<>(contentLines);
        // Replace from the last match backwards so earlier indices stay valid.
        int applied = 0;
        for (int m = matches.size() - 1; m >= 0; m--) {
            if (!replaceAll && applied == 1) break;
            int at = matches.get(m);
            List<String> head = new ArrayList<>(result.subList(0, at));
            List<String> tail = new ArrayList<>(result.subList(at + oldLines.size(), result.size()));
            head.addAll(newLines);
            head.addAll(tail);
            result = head;
            applied++;
        }
        boolean trailingNewline = content.endsWith("\n");
        String joined = String.join("\n", result);
        return new Applied(trailingNewline && !joined.endsWith("\n") ? joined + "\n" : joined,
                matchType, applied);
    }

    // ── diagnostics ─────────────────────────────────────────────────────────

    /**
     * Explain WHY nothing matched, precisely enough that the caller can correct
     * the next attempt without re-reading the file.
     */
    private static String diagnose(List<String> contentLines, List<String> oldLines) {
        if (LINE_NUMBER_PREFIX.matcher(String.join("\n", oldLines)).find()) {
            return "old_string looks like it includes read's line-number prefixes "
                    + "(e.g. '123\\t...'); strip the prefixes and retry.";
        }

        String anchor = oldLines.stream()
                .map(String::strip)
                .filter(l -> !l.isEmpty())
                .findFirst()
                .orElse(null);
        if (anchor == null) {
            return "old_string is blank/whitespace-only; provide real content to match.";
        }
        for (int i = 0; i < contentLines.size(); i++) {
            if (contentLines.get(i).strip().equals(anchor)) {
                int from = Math.max(0, i - 1);
                int to = Math.min(contentLines.size(), i + oldLines.size() + 1);
                StringBuilder excerpt = new StringBuilder();
                for (int j = from; j < to; j++) {
                    excerpt.append(j + 1).append(": ").append(contentLines.get(j)).append('\n');
                }
                return "The first line of old_string appears at line " + (i + 1)
                        + " but the following lines diverge. Actual content there:\n" + excerpt
                        + "Adjust old_string to match this exactly.";
            }
        }
        return "Not even the first non-blank line of old_string ('"
                + truncate(anchor, 80) + "') exists in the file. "
                + "Re-read the file — it likely changed or this is the wrong file.";
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static int countOccurrences(String content, String search) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(search, idx)) != -1) {
            count++;
            idx += search.length();
        }
        return count;
    }

    private static String replaceFirst(String content, String oldString, String newString) {
        int idx = content.indexOf(oldString);
        return content.substring(0, idx) + newString + content.substring(idx + oldString.length());
    }

    /** 1-based line numbers of the first few exact matches, for uniqueness errors. */
    private static String matchLineNumbers(String content, String search) {
        List<Integer> lines = new ArrayList<>();
        int idx = 0;
        while ((idx = content.indexOf(search, idx)) != -1 && lines.size() < 5) {
            int line = 1;
            for (int i = 0; i < idx; i++) {
                if (content.charAt(i) == '\n') line++;
            }
            lines.add(line);
            idx += search.length();
        }
        return joinNumbers(lines);
    }

    private static String firstLineNumbers(List<Integer> zeroBasedStarts) {
        List<Integer> lines = new ArrayList<>();
        for (int i = 0; i < zeroBasedStarts.size() && i < 5; i++) {
            lines.add(zeroBasedStarts.get(i) + 1);
        }
        return joinNumbers(lines);
    }

    private static String joinNumbers(List<Integer> numbers) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < numbers.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(numbers.get(i));
        }
        return sb.toString();
    }

    private static List<String> splitLines(String text) {
        return new ArrayList<>(List.of(text.split("\n", -1)));
    }

    private static List<Integer> findBlocks(List<String> lines, List<String> pattern,
                                            BiPredicate<String, String> eq) {
        List<Integer> matches = new ArrayList<>();
        if (pattern.isEmpty()) return matches;
        outer:
        for (int i = 0; i + pattern.size() <= lines.size(); i++) {
            for (int j = 0; j < pattern.size(); j++) {
                if (!eq.test(lines.get(i + j), pattern.get(j))) continue outer;
            }
            matches.add(i);
            // Skip past this block so overlapping matches don't double-count.
            i += pattern.size() - 1;
        }
        return matches;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
