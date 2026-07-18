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

/**
 * Parser and in-memory applier for the "apply_patch" (V4A) patch format that
 * OpenAI-family CLIs and models trained on them emit:
 *
 * <pre>
 * *** Begin Patch
 * *** Add File: relative/path
 * +new file content, every line prefixed with +
 * *** Update File: relative/path
 * *** Move to: other/path          (optional)
 * @@ optional locator hint
 *  context line
 * -removed line
 * +added line
 * *** End of File                  (optional EOF marker)
 * *** Delete File: relative/path
 * *** End Patch
 * </pre>
 *
 * Hunks carry no line numbers: each hunk is located by matching its context
 * and removed lines against the file content — exact match first, then
 * trailing-whitespace-insensitive, then fully whitespace-insensitive.
 */
final class ApplyPatchFormat {

    enum OpType { ADD, UPDATE, DELETE }

    static final class Hunk {
        final List<String> oldLines = new ArrayList<>();
        final List<String> newLines = new ArrayList<>();
        boolean endOfFile;

        boolean isEmpty() { return oldLines.isEmpty() && newLines.isEmpty(); }
    }

    static final class FileOp {
        final OpType type;
        final String path;
        String moveTo;
        final List<Hunk> hunks = new ArrayList<>();
        final List<String> addLines = new ArrayList<>();

        FileOp(OpType type, String path) {
            this.type = type;
            this.path = path;
        }
    }

    static final class FormatException extends Exception {
        FormatException(String message) { super(message); }
    }

    private ApplyPatchFormat() {
    }

    static boolean isApplyPatchFormat(String patch) {
        return patch.lines().anyMatch(l -> l.strip().equals("*** Begin Patch"));
    }

    static List<FileOp> parse(String patch) throws FormatException {
        List<String> lines = patch.lines().toList();
        int i = 0;
        while (i < lines.size() && !lines.get(i).strip().equals("*** Begin Patch")) i++;
        if (i == lines.size()) throw new FormatException("No '*** Begin Patch' marker found");
        i++;

        List<FileOp> ops = new ArrayList<>();
        FileOp current = null;
        Hunk hunk = null;
        for (; i < lines.size(); i++) {
            String line = lines.get(i);
            String stripped = line.strip();
            if (stripped.equals("*** End Patch")) break;

            if (stripped.startsWith("*** ")) {
                String directive = stripped.substring(4);
                if (directive.startsWith("Add File:")) {
                    current = new FileOp(OpType.ADD, directive.substring("Add File:".length()).strip());
                    ops.add(current);
                    hunk = null;
                } else if (directive.startsWith("Update File:")) {
                    current = new FileOp(OpType.UPDATE, directive.substring("Update File:".length()).strip());
                    ops.add(current);
                    hunk = null;
                } else if (directive.startsWith("Delete File:")) {
                    current = new FileOp(OpType.DELETE, directive.substring("Delete File:".length()).strip());
                    ops.add(current);
                    hunk = null;
                } else if (directive.startsWith("Move to:")) {
                    if (current == null || current.type != OpType.UPDATE) {
                        throw new FormatException("'*** Move to:' outside an Update File section (line " + (i + 1) + ")");
                    }
                    current.moveTo = directive.substring("Move to:".length()).strip();
                } else if (directive.equals("End of File")) {
                    if (hunk != null) hunk.endOfFile = true;
                } else {
                    throw new FormatException("Unknown apply_patch directive '" + stripped + "' (line " + (i + 1) + ")");
                }
                continue;
            }

            if (current == null) {
                if (stripped.isEmpty()) continue;
                throw new FormatException(
                        "Content before any '*** Add/Update/Delete File:' directive (line " + (i + 1) + "): " + line);
            }

            switch (current.type) {
                case ADD -> {
                    if (line.startsWith("+")) current.addLines.add(line.substring(1));
                    else if (stripped.isEmpty()) current.addLines.add("");
                    else throw new FormatException(
                            "Add File lines must start with '+' (line " + (i + 1) + "): " + line);
                }
                case DELETE -> {
                    if (!stripped.isEmpty()) {
                        throw new FormatException("Delete File takes no body (line " + (i + 1) + "): " + line);
                    }
                }
                case UPDATE -> {
                    if (line.startsWith("@@")) {
                        hunk = null;
                        continue;
                    }
                    if (hunk == null) {
                        hunk = new Hunk();
                        current.hunks.add(hunk);
                    }
                    if (line.startsWith("+")) hunk.newLines.add(line.substring(1));
                    else if (line.startsWith("-")) hunk.oldLines.add(line.substring(1));
                    else if (line.startsWith(" ")) {
                        String context = line.substring(1);
                        hunk.oldLines.add(context);
                        hunk.newLines.add(context);
                    } else if (line.isEmpty()) {
                        // Models often emit blank context lines without the leading space.
                        hunk.oldLines.add("");
                        hunk.newLines.add("");
                    } else {
                        throw new FormatException(
                                "Update hunk lines must start with ' ', '-', '+' or '@@' (line " + (i + 1) + "): " + line);
                    }
                }
            }
        }

        for (FileOp op : ops) op.hunks.removeIf(Hunk::isEmpty);
        if (ops.isEmpty()) throw new FormatException("Patch contains no file operations");
        return ops;
    }

    /**
     * Applies update hunks to file content and returns the new content.
     * Throws when a hunk's context cannot be located in the content.
     */
    static String applyHunks(String path, String content, List<Hunk> hunks) throws FormatException {
        boolean trailingNewline = content.endsWith("\n");
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        if (trailingNewline && !lines.isEmpty()) lines.remove(lines.size() - 1);

        int cursor = 0;
        for (Hunk hunk : hunks) {
            if (hunk.oldLines.isEmpty()) {
                if (hunk.endOfFile || cursor == 0 || cursor >= lines.size()) {
                    lines.addAll(hunk.newLines);
                    cursor = lines.size();
                } else {
                    lines.addAll(cursor, hunk.newLines);
                    cursor += hunk.newLines.size();
                }
                continue;
            }
            int at = locate(lines, hunk.oldLines, cursor);
            if (at < 0) at = locate(lines, hunk.oldLines, 0);
            if (at < 0) {
                throw new FormatException("Could not locate hunk in " + path
                        + " — first unmatched line: '" + hunk.oldLines.get(0)
                        + "'. The file content differs from what the patch expects.");
            }
            List<String> head = new ArrayList<>(lines.subList(0, at));
            List<String> tail = new ArrayList<>(lines.subList(at + hunk.oldLines.size(), lines.size()));
            head.addAll(hunk.newLines);
            head.addAll(tail);
            lines = head;
            cursor = at + hunk.newLines.size();
        }

        String result = String.join("\n", lines);
        return trailingNewline ? result + "\n" : result;
    }

    static String joinAddLines(List<String> addLines) {
        return addLines.isEmpty() ? "" : String.join("\n", addLines) + "\n";
    }

    /**
     * Lenient parser for a SINGLE file's update hunks, used by {@code edit_patch}
     * where the file is named per entry and the patch body arrives without (or
     * with partial) wrapping. Accepts both dialects and their common corruptions:
     * <ul>
     *   <li>V4A bodies: {@code @@ locator} separators, ' '/'-'/'+' lines</li>
     *   <li>unified diffs: {@code @@ -l,c +l,c @@} headers (line numbers ignored —
     *       hunks are located by context), {@code ---/+++/diff --git/index} header
     *       lines skipped, {@code \ No newline at end of file} skipped</li>
     *   <li>wrapper noise: {@code *** Begin/End Patch}, {@code *** Update File: x}
     *       lines skipped; Add/Delete/Move directives are rejected (those need the
     *       {@code patch} tool)</li>
     *   <li>models dropping the leading space on context lines: bare lines are
     *       treated as context</li>
     * </ul>
     */
    static List<Hunk> parseHunksBody(String body) throws FormatException {
        List<Hunk> hunks = new ArrayList<>();
        Hunk hunk = null;
        int lineNo = 0;
        for (String line : body.lines().toList()) {
            lineNo++;
            String stripped = line.strip();

            if (stripped.equals("*** Begin Patch") || stripped.equals("*** End Patch")
                    || stripped.startsWith("*** Update File:")) {
                continue;
            }
            if (stripped.startsWith("*** Add File:") || stripped.startsWith("*** Delete File:")
                    || stripped.startsWith("*** Move to:")) {
                throw new FormatException("edit_patch only updates the named file — '" + stripped
                        + "' (line " + lineNo + ") needs the patch tool");
            }
            if (stripped.equals("*** End of File")) {
                if (hunk != null) hunk.endOfFile = true;
                continue;
            }
            if (stripped.startsWith("diff --git") || stripped.startsWith("index ")
                    || stripped.startsWith("--- ") || stripped.startsWith("+++ ")
                    || stripped.startsWith("\\ No newline")) {
                continue;
            }
            if (line.startsWith("@@")) {
                hunk = null;
                continue;
            }

            if (hunk == null) {
                if (stripped.isEmpty()) continue;
                hunk = new Hunk();
                hunks.add(hunk);
            }
            if (line.startsWith("+")) {
                hunk.newLines.add(line.substring(1));
            } else if (line.startsWith("-")) {
                hunk.oldLines.add(line.substring(1));
            } else if (line.startsWith(" ")) {
                String context = line.substring(1);
                hunk.oldLines.add(context);
                hunk.newLines.add(context);
            } else if (line.isEmpty()) {
                hunk.oldLines.add("");
                hunk.newLines.add("");
            } else {
                // Models often drop the leading space on context lines; a wrongly
                // classified line fails hunk location (safe) rather than corrupting.
                hunk.oldLines.add(line);
                hunk.newLines.add(line);
            }
        }
        hunks.removeIf(Hunk::isEmpty);
        if (hunks.isEmpty()) {
            throw new FormatException("patch contains no hunks (expected ' '/'-'/'+' prefixed "
                    + "lines, optionally separated by @@ markers)");
        }
        return hunks;
    }

    private static int locate(List<String> lines, List<String> pattern, int from) {
        int exact = find(lines, pattern, from, String::equals);
        if (exact >= 0) return exact;
        int rstrip = find(lines, pattern, from, (a, b) -> a.stripTrailing().equals(b.stripTrailing()));
        if (rstrip >= 0) return rstrip;
        return find(lines, pattern, from, (a, b) -> a.strip().equals(b.strip()));
    }

    private static int find(List<String> lines, List<String> pattern, int from, BiPredicate<String, String> eq) {
        outer:
        for (int i = Math.max(0, from); i + pattern.size() <= lines.size(); i++) {
            for (int j = 0; j < pattern.size(); j++) {
                if (!eq.test(lines.get(i + j), pattern.get(j))) continue outer;
            }
            return i;
        }
        return -1;
    }
}
