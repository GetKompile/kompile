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

package ai.kompile.cli.main.chat.activity;

import ai.kompile.utils.AnsiConstants;

import java.util.regex.Pattern;

/** Sanitizes the bounded text retained by the live activity dashboard. */
final class ActivityToolText {

    static final int MAX_SUMMARY_CHARS = 500;
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|secret|credential)s?"
                    + "[\\s\"']*[:=][\\s\"']*)([^,}\\s\"']+|\"[^\"]*\"|'[^']*')");
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)(authorization[\\s\"']*[:=][\\s\"']*)(?:bearer|basic|token)?\\s*[^,}\\s\"']+");
    private static final Pattern SECRET_ARGUMENT = Pattern.compile(
            "(?i)((?:--)?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|secret|credential)"
                    + "(?:[\\s_-]+))(?!<redacted>)[^,}\\s\"']+");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\t]]");

    private ActivityToolText() {
    }

    static String summary(String value) {
        String redacted = SECRET_ASSIGNMENT.matcher(clean(value)).replaceAll("$1<redacted>");
        redacted = AUTHORIZATION.matcher(redacted).replaceAll("$1<redacted>");
        redacted = SECRET_ARGUMENT.matcher(redacted).replaceAll("$1<redacted>");
        redacted = BEARER.matcher(redacted).replaceAll("$1<redacted>");
        return bounded(redacted, MAX_SUMMARY_CHARS);
    }

    static String boundedClean(String value, int maximum) {
        return bounded(clean(value), maximum);
    }

    private static String clean(String value) {
        if (value == null) return "";
        String plain = AnsiConstants.stripAnsi(value).replace('\n', ' ').replace('\r', ' ');
        return CONTROL.matcher(plain).replaceAll("").replaceAll("[ \\t]+", " ").strip();
    }

    private static String bounded(String value, int maximum) {
        if (maximum <= 0 || value == null || value.isEmpty()) return "";
        if (value.length() <= maximum) return value;
        if (maximum == 1) return "…";
        return value.substring(0, maximum - 1) + "…";
    }
}
