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

import java.util.List;
import java.util.Set;

/**
 * Canonical set of directory names that are never worth searching: version-control
 * metadata, build output, dependency trees, and caches. Shared by {@link GrepTool}
 * (passed as {@code grep --exclude-dir}) and {@link GlobTool} (used to prune the file
 * walk) so both tools agree on what to skip.
 *
 * <p>Without this, the plain-{@code grep -r} fallback walks the entire working tree —
 * including {@code .git/} and every {@code target/} — which can take minutes on a large
 * repository and is the root cause of grep "stalls".</p>
 */
public final class SearchExclusions {

    private SearchExclusions() {}

    /** Named directories pruned from any code search. */
    public static final List<String> DIRS = List.of(
            ".git", ".svn", ".hg",
            "node_modules", "target", "build", "dist", "out",
            "__pycache__", ".gradle", ".idea", ".mvn", ".venv");

    private static final Set<String> DIR_SET = Set.copyOf(DIRS);

    /**
     * Returns true if a directory with this basename should be skipped while traversing.
     * Hidden directories (name starting with {@code .}) are skipped by default.
     */
    public static boolean isExcludedDir(String name) {
        return isExcludedDir(name, false);
    }

    /**
     * Returns true if a directory with this basename should be skipped while traversing.
     *
     * <p>The explicit {@link #DIRS} names — heavy VCS/build/dependency trees such as
     * {@code .git} and {@code target} — are <em>always</em> pruned; searching them is never
     * useful. The {@code includeHidden} flag only governs <em>other</em> dot-prefixed
     * directories (e.g. {@code .github}, {@code .config}): they are pruned when
     * {@code includeHidden} is false and traversed when it is true.</p>
     */
    public static boolean isExcludedDir(String name, boolean includeHidden) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (DIR_SET.contains(name)) {
            return true;
        }
        return !includeHidden && name.startsWith(".");
    }
}
