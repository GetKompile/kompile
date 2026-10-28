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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.chat.tools;

import ai.kompile.cli.main.coordination.CoordinationStateManager;
import ai.kompile.cli.main.coordination.EditLockEntry;
import ai.kompile.cli.main.coordination.EditEnforcement;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static, deterministic shell file-write gate of edit-coordination enforcement
 * ({@link EditEnforcement#ENFORCE}): the file-mutating native tools all guard through
 * {@link EditCoordinatorTool#guardEdit}, but {@code bash} / {@code process launch} write
 * files too — redirects, {@code tee}, in-place editors, {@code rm}/{@code mv} of targets —
 * and would silently bypass the invariant. This class extracts, conservatively, the
 * filesystem targets a shell command would create, replace, move onto or delete, and
 * reports the ones another live owner holds an edit lock on.
 *
 * <p>Conservative by design: this is a best-effort static analysis, not a shell parser.
 * It can miss targets (heredocs through variables, exotic in-place tools, indirection) —
 * in that residual case the coordination invariant degrades to the tool-level guarantee,
 * which is exactly what pre-gate kompile did. It must never false-positive on a file that
 * is not locked: a refusal is issued ONLY when the coordinator currently shows a live
 * conflicting lock on an extracted target.
 *
 * <p>Command splitting follows the same bounded model as {@code ShellMandatePolicy}:
 * split on control operators and newlines, ignore quoted content, then classify each
 * segment by its first word.
 */
final class ShellEditLockGate {

    private ShellEditLockGate() {
    }

    /** First-word commands whose later operands are file writes/deletes when present. */
    private static final Pattern IN_PLACE_EDIT = Pattern.compile(
            "^(sed|perl|awk|ruby|python3?|gawk|mawk|gsed|sd)\\b");
    /** Commands whose LAST path-like operand is the destination written onto. */
    private static final Pattern COPY_LIKE = Pattern.compile("^(cp|rsync|install)\\b");
    private static final Pattern DESTRUCTIVE = Pattern.compile(
            "^(rm|rmdir|shred|truncate|unlink|ln|chmod|chown|touch)\\b");
    private static final Pattern PATCH_TOOLS = Pattern.compile("^(patch|git|apply_patch)\\b");
    private static final Pattern BUILD_TOOLS = Pattern.compile(
            "^(mvn|gradle|make|cmake|npm|pnpm|yarn|tsc|javac|gcc|clang)\\b");

    /** Word-level token scan of an unquoted segment. */
    private static final Pattern TOKEN = Pattern.compile("[^\\s]+");

    /**
     * Every path this command may create, replace, move onto or delete, best effort.
     * Empty when nothing suspicious is found — the common case for builds and greps.
     */
    static List<String> extractWriteTargets(String command) {
        List<String> targets = new ArrayList<>();
        if (command == null || command.isBlank()) return targets;
        for (String segment : splitSegments(command)) {
            extractFromSegment(segment, targets);
        }
        return targets;
    }

    /**
     * The refusal error for this shell command — naming every locked target found — or
     * null when the command may run. {@code agent} is the caller's coordination identity
     * (null = session semantics); locks held by the caller itself never refuse.
     */
    static String checkCommand(CoordinationStateManager coordinator, String agent,
                               String command, Path workingDirectory) {
        if (coordinator == null
                || coordinator.getEditEnforcement() != EditEnforcement.ENFORCE
                || command == null || command.isBlank()) {
            return null;
        }
        List<String> targets = extractWriteTargets(command);
        if (targets.isEmpty()) return null;
        List<String> locked = new ArrayList<>();
        for (String target : targets) {
            Path resolved = resolve(workingDirectory, target);
            if (resolved == null) continue;
            EditLockEntry holder = coordinator.findConflictingLock(
                    resolved.toAbsolutePath().normalize().toString(), agent);
            if (holder != null) {
                locked.add(resolved + " — locked by " + holder.getAgentName()
                        + " (session " + holder.getSessionId() + ")");
            }
        }
        if (locked.isEmpty()) return null;
        return "CONFLICT: this command would write or delete coordinator-locked files — "
                + String.join("; ", locked)
                + ". Coordinate with the holder, wait for the lock to free, or edit with the "
                + "native edit tool after registering the file (register_edits). "
                + "The command was NOT run.";
    }

    // ── segment model ────────────────────────────────────────────────────────

    private static List<String> splitSegments(String command) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(c);
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(c);
            } else if (!inSingle && !inDouble
                    && (c == '\n' || c == ';' || c == '|' || c == '&' || c == '(' || c == ')')) {
                if (!current.toString().isBlank()) segments.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) segments.add(current.toString());
        return segments;
    }

    private static void extractFromSegment(String segment, List<String> targets) {
        // 1. Redirect targets: >, >>, 2>, &>, <(...) excluded; <> and >& skipped.
        //    Whitespace before the target is allowed ("echo hi > out.txt").
        Matcher redirect = Pattern.compile("(?<![>])[1&2]?>>?(?!&)(\\s*)([^\\s<>|;&)]+)")
                .matcher(segment);
        while (redirect.find()) {
            String target = redirect.group(2).replaceAll("^\"|\"$|^'|'$", "");
            if (isPathLike(target)) targets.add(target);
        }

        // 2. In-place flags: -i / --in-place (and sed-style -i'' variants) — the NEXT
        //    path-like token is the edited file. Split around -i so token 0 stays the verb.
        String withoutInPlaceFlags = segment
                .replaceAll("(?<![\\w-])--in-place(=?\\S*)", " ")
                .replaceAll("(?<![\\w-])-i\\w{0,3}b?['\"]?[^\\s'\"]*['\"]?", " ");
        String[] words = words(withoutInPlaceFlags);
        if (words.length == 0) return;
        String verb = words[0];
        if (BUILD_TOOLS.matcher(verb).matches()) return; // builds write only their out dirs

        if ("mv".equals(verb)) {
            // mv DESTROYS its source and replaces its destination: every path operand.
            for (int i = 1; i < words.length; i++) {
                if (!words[i].startsWith("-") && isPathLike(words[i])) targets.add(words[i]);
            }
        } else if (COPY_LIKE.matcher(verb).matches()) {
            // cp/rsync/install keep the source; only the destination is written onto.
            String dest = lastPathLike(words);
            if (dest != null) targets.add(dest);
        } else if (DESTRUCTIVE.matcher(verb).matches()) {
            // rm/rmdir/shred/truncate/unlink/ln/chmod/chown/touch: every path operand is
            // being removed or overwritten in place.
            for (int i = 1; i < words.length; i++) {
                if (!words[i].startsWith("-") && isPathLike(words[i])) targets.add(words[i]);
            }
        } else if (IN_PLACE_EDIT.matcher(verb).matches()) {
            // In-place editors: every non-option path operand may be rewritten in place
            // (sed 's/a/b/' A.txt B.txt edits BOTH). The sed SCRIPT operand ('s/a/b/')
            // is not a path but is conservatively extracted too: the gate only refuses
            // when the coordinator shows a real lock, so a script-shaped token that is
            // never locked costs nothing, while treating it as a path misses nothing.
            for (int i = 1; i < words.length; i++) {
                if (!words[i].startsWith("-") && isPathLike(words[i])) targets.add(words[i]);
            }
        } else if (PATCH_TOOLS.matcher(verb).matches()) {
            String target = patchTarget(words);
            if (target != null) targets.add(target);
        } else if ("dd".equals(verb)) {
            // dd of=TARGET writes the target directly.
            Matcher of = Pattern.compile("\\bof=(\\S+)").matcher(segment);
            if (of.find() && isPathLike(of.group(1))) targets.add(of.group(1));
        }
        // tee: every path-like operand is a write target.
        if ("tee".equals(verb) || segment.contains(" tee ")) {
            for (int i = 1; i < words.length; i++) {
                if (!words[i].startsWith("-") && isPathLike(words[i])) targets.add(words[i]);
            }
        }
    }

    /** patch/git-apply target: first -p-adjusted path or the operand of --force-style runs. */
    private static String patchTarget(String[] words) {
        for (int i = 1; i < words.length; i++) {
            String w = words[i];
            if (w.equals("-i") || w.startsWith("--input=")) continue;
            if (!w.startsWith("-") && isPathLike(w)) return w;
        }
        return null;
    }

    /** Last path-like, non-flag token (destination of cp/rsync/install). */
    private static String lastPathLike(String[] words) {
        for (int i = words.length - 1; i >= 1; i--) {
            if (!words[i].startsWith("-") && isPathLike(words[i])) return words[i];
        }
        return null;
    }

    private static String[] words(String segment) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(segment);
        while (m.find()) out.add(m.group());
        return out.toArray(String[]::new);
    }

    private static boolean isPathLike(String token) {
        if (token == null || token.isBlank()) return false;
        String bare = token.replaceAll("^\"|\"$|^'|'$", "");
        if (bare.isEmpty()) return false;
        if (bare.startsWith("$")) return false;                    // variable indirection
        if (bare.startsWith("`") || bare.startsWith("(")) return false;
        if (bare.equals("-") || bare.equals("--")) return false;
        if (bare.contains("*") || bare.contains("?")) return false; // glob: unresolvable
        return true;
    }

    private static Path resolve(Path workingDirectory, String target) {
        String bare = target.replaceAll("^\"|\"$|^'|'$", "");
        if (bare.startsWith("~/")) {
            bare = System.getProperty("user.home") + bare.substring(1);
        }
        try {
            return workingDirectory.toAbsolutePath().resolve(bare).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
