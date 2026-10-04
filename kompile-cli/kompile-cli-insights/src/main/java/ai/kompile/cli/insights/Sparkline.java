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

/**
 * One-line bar charts from block characters. Zero always draws the lowest bar and any
 * non-zero value at least the second, so a quiet bucket never looks empty; a NaN value
 * (no data) draws a space.
 */
public final class Sparkline {

    private static final char[] BARS = "▁▂▃▄▅▆▇█".toCharArray();

    private Sparkline() {
    }

    public static String of(long[] values) {
        long max = 0;
        for (long value : values) {
            max = Math.max(max, value);
        }
        StringBuilder line = new StringBuilder(values.length);
        for (long value : values) {
            line.append(bar(value, max));
        }
        return line.toString();
    }

    /** Scaled to the largest value. */
    public static String of(double[] values) {
        double max = 0;
        for (double value : values) {
            if (!Double.isNaN(value)) {
                max = Math.max(max, value);
            }
        }
        return of(values, max);
    }

    /** Scaled to {@code max}; ratios pass 1.0 so the bars read as absolute rates. */
    public static String of(double[] values, double max) {
        StringBuilder line = new StringBuilder(values.length);
        for (double value : values) {
            line.append(Double.isNaN(value) ? ' ' : bar(value, max));
        }
        return line.toString();
    }

    private static char bar(double value, double max) {
        if (value <= 0 || max <= 0) {
            return BARS[0];
        }
        int index = 1 + (int) Math.round(Math.min(1.0, value / max) * (BARS.length - 2));
        return BARS[Math.min(BARS.length - 1, index)];
    }
}
