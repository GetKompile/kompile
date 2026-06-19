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

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MapUtilsTest {

    @Test
    void getString_present() {
        Map<String, Object> m = Map.of("k", "hello");
        assertEquals("hello", MapUtils.getString(m, "k", "def"));
    }

    @Test
    void getString_missing() {
        assertEquals("def", MapUtils.getString(Map.of(), "k", "def"));
    }

    @Test
    void getString_nonStringValue() {
        Map<String, Object> m = Map.of("k", 42);
        assertEquals("42", MapUtils.getString(m, "k", "def"));
    }

    @Test
    void getString_nullValue() {
        Map<String, Object> m = new HashMap<>();
        m.put("k", null);
        assertEquals("def", MapUtils.getString(m, "k", "def"));
    }

    @Test
    void getStringNonBlank_present() {
        assertEquals("hello", MapUtils.getStringNonBlank(Map.of("k", "hello"), "k", "def"));
    }

    @Test
    void getStringNonBlank_blank() {
        assertEquals("def", MapUtils.getStringNonBlank(Map.of("k", "  "), "k", "def"));
    }

    @Test
    void getStringNonBlank_empty() {
        assertEquals("def", MapUtils.getStringNonBlank(Map.of("k", ""), "k", "def"));
    }

    @Test
    void getInt_fromInteger() {
        assertEquals(42, MapUtils.getInt(Map.of("k", 42), "k", 0));
    }

    @Test
    void getInt_fromLong() {
        assertEquals(42, MapUtils.getInt(Map.of("k", 42L), "k", 0));
    }

    @Test
    void getInt_fromDouble() {
        assertEquals(42, MapUtils.getInt(Map.of("k", 42.9), "k", 0));
    }

    @Test
    void getInt_fromString() {
        assertEquals(42, MapUtils.getInt(Map.of("k", "42"), "k", 0));
    }

    @Test
    void getInt_fromBadString() {
        assertEquals(99, MapUtils.getInt(Map.of("k", "not_a_number"), "k", 99));
    }

    @Test
    void getInt_missing() {
        assertEquals(99, MapUtils.getInt(Map.of(), "k", 99));
    }

    @Test
    void getLong_fromLong() {
        assertEquals(100L, MapUtils.getLong(Map.of("k", 100L), "k", 0L));
    }

    @Test
    void getLong_fromInteger() {
        assertEquals(100L, MapUtils.getLong(Map.of("k", 100), "k", 0L));
    }

    @Test
    void getLong_fromString() {
        assertEquals(100L, MapUtils.getLong(Map.of("k", "100"), "k", 0L));
    }

    @Test
    void getLong_fromStringWithWhitespace() {
        assertEquals(100L, MapUtils.getLong(Map.of("k", " 100 "), "k", 0L));
    }

    @Test
    void getDouble_fromDouble() {
        assertEquals(3.14, MapUtils.getDouble(Map.of("k", 3.14), "k", 0.0), 0.001);
    }

    @Test
    void getDouble_fromInteger() {
        assertEquals(3.0, MapUtils.getDouble(Map.of("k", 3), "k", 0.0), 0.001);
    }

    @Test
    void getDouble_fromString() {
        assertEquals(3.14, MapUtils.getDouble(Map.of("k", "3.14"), "k", 0.0), 0.001);
    }

    @Test
    void getBoolean_fromBoolean() {
        assertTrue(MapUtils.getBoolean(Map.of("k", true), "k", false));
    }

    @Test
    void getBoolean_fromString() {
        assertTrue(MapUtils.getBoolean(Map.of("k", "true"), "k", false));
        assertFalse(MapUtils.getBoolean(Map.of("k", "false"), "k", true));
    }

    @Test
    void getBoolean_missing() {
        assertTrue(MapUtils.getBoolean(Map.of(), "k", true));
    }

    @Test
    void toInt_withDefault_number() {
        assertEquals(42, MapUtils.toInt(42L, 0));
    }

    @Test
    void toInt_withDefault_string() {
        assertEquals(42, MapUtils.toInt("42", 0));
    }

    @Test
    void toInt_withDefault_null() {
        assertEquals(99, MapUtils.toInt(null, 99));
    }

    @Test
    void toInt_withDefault_badString() {
        assertEquals(99, MapUtils.toInt("bad", 99));
    }

    @Test
    void toInt_noDefault_number() {
        assertEquals(42, MapUtils.toInt(42));
    }

    @Test
    void toInt_noDefault_string() {
        assertEquals(42, MapUtils.toInt("42"));
    }

    @Test
    void toInt_noDefault_null() {
        assertThrows(NullPointerException.class, () -> MapUtils.toInt(null));
    }

    @Test
    void toLong_noDefault() {
        assertEquals(100L, MapUtils.toLong(100));
        assertEquals(100L, MapUtils.toLong("100"));
    }

    @Test
    void toDouble_noDefault() {
        assertEquals(3.14, MapUtils.toDouble(3.14), 0.001);
        assertEquals(3.14, MapUtils.toDouble("3.14"), 0.001);
    }

    @Test
    void toDouble_withDefault_null() {
        assertEquals(1.0, MapUtils.toDouble(null, 1.0), 0.001);
    }
}
