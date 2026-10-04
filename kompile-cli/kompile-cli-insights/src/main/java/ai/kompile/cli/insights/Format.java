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

package ai.kompile.cli.insights;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/** Number, duration, text and time formatting shared by the insight reports. */
public final class Format {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);
    /** Whitespace and control characters: log text must not move the terminal cursor. */
    private static final Pattern BLANKS = Pattern.compile("[\\p{Cntrl}\\s]+");

    private Format() {
    }

    public static String count(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** "1 verdict", "2 verdicts". */
    public static String count(long n, String noun) {
        return count(n, noun, noun + "s");
    }

    public static String count(long n, String singular, String plural) {
        return count(n) + " " + (n == 1 ? singular : plural);
    }

    /** 512 B, 4 MiB, 1.5 GiB. */
    public static String bytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return value == Math.rint(value)
                ? String.format(Locale.ROOT, "%d %s", (long) value, units[unit])
                : String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    /** An upper-case status such as {@code OVERRIDE_ARMED} as words: "override armed". */
    public static String words(String status) {
        return status == null ? "" : status.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** {@code part} of {@code whole} as a percentage with one decimal, or "-" when whole is 0. */
    public static String percent(long part, long whole) {
        return whole <= 0 ? "-" : percent((double) part / whole);
    }

    public static String percent(double ratio) {
        return Double.isNaN(ratio) ? "-" : String.format(Locale.ROOT, "%.1f%%", ratio * 100);
    }

    /** 850ms, 1.8s, 2m05s, 1h02m. */
    public static String duration(long ms) {
        if (ms < 0) {
            return "-";
        }
        if (ms < 1_000) {
            return ms + "ms";
        }
        if (ms < 60_000) {
            return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
        }
        long seconds = ms / 1000;
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%dm%02ds", seconds / 60, seconds % 60);
        }
        long minutes = seconds / 60;
        return String.format(Locale.ROOT, "%dh%02dm", minutes / 60, minutes % 60);
    }

    /** One line with whitespace and control characters collapsed, at most {@code max} characters. */
    public static String clamp(String text, int max) {
        if (text == null) {
            return "";
        }
        String line = BLANKS.matcher(text).replaceAll(" ").trim();
        if (line.length() <= max) {
            return line;
        }
        int cut = Math.max(0, max - 1);
        if (cut > 0 && Character.isHighSurrogate(line.charAt(cut - 1))) {
            cut--;
        }
        return line.substring(0, cut) + "…";
    }

    /** How long before {@code now}: "just now", "3m ago", "2h ago", "3d ago"; a future time is "just now". */
    public static String ago(Instant at, Instant now) {
        if (at == null || now == null) {
            return "-";
        }
        long seconds = Duration.between(at, now).getSeconds();
        if (seconds < 60) {
            return "just now";
        }
        if (seconds < 3600) {
            return seconds / 60 + "m ago";
        }
        if (seconds < 86_400) {
            return seconds / 3600 + "h ago";
        }
        return seconds / 86_400 + "d ago";
    }

    public static String date(Instant at, ZoneId zone) {
        return at == null ? "-" : DATE.format(at.atZone(zone));
    }

    public static String dateTime(Instant at, ZoneId zone) {
        return at == null ? "-" : DATE_TIME.format(at.atZone(zone));
    }
}
