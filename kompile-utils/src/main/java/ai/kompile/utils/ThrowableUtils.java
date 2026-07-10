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

package ai.kompile.utils;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Stack-trace rendering helpers. Canonical home for the {@code truncateStackTrace}
 * logic previously copy-pasted as private helpers across modules.
 */
public final class ThrowableUtils {

    private ThrowableUtils() {}

    /** Full stack trace of the throwable (including causes) as a string. */
    public static String stackTraceString(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /**
     * Stack trace capped at {@code maxLines} lines; appends a
     * {@code "... (N more lines)"} marker when truncated.
     */
    public static String stackTraceString(Throwable t, int maxLines) {
        String full = stackTraceString(t);
        String[] lines = full.split("\n", -1);
        if (maxLines <= 0 || lines.length <= maxLines) {
            return full;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines; i++) {
            sb.append(lines[i]).append('\n');
        }
        sb.append("... (").append(lines.length - maxLines).append(" more lines)");
        return sb.toString();
    }
}
