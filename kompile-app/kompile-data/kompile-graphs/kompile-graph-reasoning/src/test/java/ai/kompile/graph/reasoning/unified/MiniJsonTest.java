/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.graph.reasoning.unified;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniJsonTest {

    @Test
    void writesScalars() {
        assertEquals("null", MiniJson.write(null));
        assertEquals("true", MiniJson.write(Boolean.TRUE));
        assertEquals("false", MiniJson.write(Boolean.FALSE));
        assertEquals("123", MiniJson.write(123L));
        assertEquals("-7", MiniJson.write(-7));
        assertEquals("\"hi\"", MiniJson.write("hi"));
    }

    @Test
    void integralNumbersParseAsLong() {
        assertEquals(42L, MiniJson.parse("42"));
        assertInstanceOf(Long.class, MiniJson.parse("42"));
    }

    @Test
    void realNumbersParseAsDouble() {
        assertEquals(3.5, MiniJson.parse("3.5"));
        assertInstanceOf(Double.class, MiniJson.parse("3.5"));
        assertEquals(1.0e-4, (double) MiniJson.parse("1.0E-4"), 0.0);
    }

    @Test
    void roundTripsNestedStructure() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("a", 1L);
        nested.put("b", List.of(1L, 2L, 3L));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("str", "hello");
        root.put("long", 100L);
        root.put("double", 2.75);
        root.put("bool", true);
        root.put("nil", null);
        root.put("list", new ArrayList<>(Arrays.asList("x", 1L, false, null)));
        root.put("nested", nested);

        Object back = MiniJson.parse(MiniJson.write(root));
        assertEquals(root, back);
    }

    @Test
    void roundTripsStringEscapesAndUnicode() {
        String tricky = "quote:\" backslash:\\ slash:/ newline:\n tab:\t ctrl: unicode:café ☕ 日本";
        String json = MiniJson.write(tricky);
        Object back = MiniJson.parse(json);
        assertEquals(tricky, back);
    }

    @Test
    void roundTripsEmptyContainers() {
        assertEquals(Map.of(), MiniJson.parse("{}"));
        assertEquals(List.of(), MiniJson.parse("[]"));
    }

    @Test
    void keepsNullValuesButDropsNullKeys() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("present", null);
        String json = MiniJson.write(m);
        assertEquals("{\"present\":null}", json);
        Map<String, Object> back = MiniJson.parseObject(json);
        assertTrue(back.containsKey("present"));
        assertNull(back.get("present"));
    }

    @Test
    void nonFiniteDoublesEmittedAsStrings() {
        assertEquals("\"NaN\"", MiniJson.write(Double.NaN));
        assertEquals("\"Infinity\"", MiniJson.write(Double.POSITIVE_INFINITY));
        assertEquals("\"-Infinity\"", MiniJson.write(Double.NEGATIVE_INFINITY));
        assertEquals("NaN", MiniJson.parse("\"NaN\""));
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("[1,2"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("nul"));
        assertThrows(IllegalArgumentException.class, () -> MiniJson.parse("{\"a\":1} trailing"));
    }

    @Test
    void writesPrimitiveDoubleArray() {
        assertEquals("[1.0,2.5,-3.0]", MiniJson.write(new double[] {1.0, 2.5, -3.0}));
    }
}
