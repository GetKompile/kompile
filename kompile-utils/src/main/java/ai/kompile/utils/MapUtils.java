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

import java.util.Map;

/**
 * Type-safe value extraction from {@code Map<String, Object>}.
 *
 * <p>Handles JSON-deserialized maps where numeric types may arrive as Integer, Long,
 * Double, or String depending on the parser. All methods accept Number and String
 * representations, returning a default when the key is missing or unparseable.</p>
 */
public final class MapUtils {

    private MapUtils() {}

    /**
     * Extract a String value from the map.
     * Returns {@code String.valueOf(val)} for non-null values, or defaultValue if missing.
     * Blank strings are treated as present (use {@link #getStringNonBlank} to reject blanks).
     */
    public static String getString(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val != null ? String.valueOf(val) : defaultValue;
    }

    /**
     * Extract a non-blank String value from the map.
     * Returns defaultValue if the key is missing or the value is blank.
     */
    public static String getStringNonBlank(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        if (val instanceof String s && !s.isBlank()) return s;
        return defaultValue;
    }

    /**
     * Extract an int value from the map.
     * Accepts Number instances and parseable Strings.
     */
    public static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }

    /**
     * Extract a long value from the map.
     * Accepts Number instances and parseable Strings.
     */
    public static long getLong(Map<String, Object> map, String key, long defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.longValue();
        if (val instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }

    /**
     * Extract a double value from the map.
     * Accepts Number instances and parseable Strings.
     */
    public static double getDouble(Map<String, Object> map, String key, double defaultValue) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.doubleValue();
        if (val instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }

    /**
     * Extract a boolean value from the map.
     * Accepts Boolean instances and parseable Strings ("true"/"false").
     */
    public static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object val = map.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return Boolean.parseBoolean(s);
        return defaultValue;
    }

    /**
     * Coerce a raw Object to int, without a map key lookup.
     * Useful when you already have the value from {@code map.get(key)}.
     */
    public static int toInt(Object val, int defaultValue) {
        if (val == null) return defaultValue;
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); } catch (NumberFormatException e) { return defaultValue; }
    }

    /**
     * Coerce a raw Object to long, without a map key lookup.
     */
    public static long toLong(Object val, long defaultValue) {
        if (val == null) return defaultValue;
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(val.toString()); } catch (NumberFormatException e) { return defaultValue; }
    }

    /**
     * Coerce a raw Object to double, without a map key lookup.
     */
    public static double toDouble(Object val, double defaultValue) {
        if (val == null) return defaultValue;
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (NumberFormatException e) { return defaultValue; }
    }

    /**
     * Coerce a raw Object to int. Throws on null or unparseable values.
     */
    public static int toInt(Object val) {
        if (val instanceof Number n) return n.intValue();
        return Integer.parseInt(val.toString());
    }

    /**
     * Coerce a raw Object to long. Throws on null or unparseable values.
     */
    public static long toLong(Object val) {
        if (val instanceof Number n) return n.longValue();
        return Long.parseLong(val.toString());
    }

    /**
     * Coerce a raw Object to double. Throws on null or unparseable values.
     */
    public static double toDouble(Object val) {
        if (val instanceof Number n) return n.doubleValue();
        return Double.parseDouble(val.toString());
    }
}
