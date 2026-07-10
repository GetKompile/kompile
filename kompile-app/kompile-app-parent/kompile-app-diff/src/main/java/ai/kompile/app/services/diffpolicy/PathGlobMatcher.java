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

package ai.kompile.app.services.diffpolicy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * File-path matching shared by diff search and diff policy. A filter containing
 * glob metacharacters ({@code *}/{@code ?}) is treated as a glob — {@code **}
 * spans directories, {@code *} stays within a path segment — so "file of concern"
 * queries like {@code **}{@code /*.env}, {@code secrets/}{@code **}, or {@code *.pem}
 * work. A plain filter falls back to a case-sensitive substring match.
 */
public final class PathGlobMatcher {

    private static final ConcurrentHashMap<String, Pattern> CACHE = new ConcurrentHashMap<>();

    private PathGlobMatcher() {
    }

    /** True if the filter uses glob metacharacters (and so is matched as a glob). */
    public static boolean isGlob(String filter) {
        return filter != null && (filter.indexOf('*') >= 0 || filter.indexOf('?') >= 0);
    }

    /** Match a path against a filter (glob if it has metacharacters, else substring). */
    public static boolean matches(String path, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        if (path == null) {
            return false;
        }
        if (isGlob(filter)) {
            return CACHE.computeIfAbsent(filter, PathGlobMatcher::compile).matcher(path).find();
        }
        return path.contains(filter);
    }

    /** Compile a glob filter to a regex matched anywhere in the path (via {@code find()}). */
    private static Pattern compile(String glob) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        sb.append(".*");      // ** → cross directory separators
                        i++;
                    } else {
                        sb.append("[^/]*");   // * → within a path segment
                    }
                    break;
                case '?':
                    sb.append("[^/]");
                    break;
                case '.': case '(': case ')': case '+': case '|': case '^':
                case '$': case '{': case '}': case '[': case ']': case '\\':
                    sb.append('\\').append(c);
                    break;
                default:
                    sb.append(c);
            }
        }
        // Anchor the end for concrete patterns (e.g. *.env must not match a.environment).
        if (!glob.endsWith("*")) {
            sb.append('$');
        }
        return Pattern.compile(sb.toString());
    }
}
