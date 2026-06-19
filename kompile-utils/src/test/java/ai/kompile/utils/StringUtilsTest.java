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

import static org.junit.jupiter.api.Assertions.*;

class StringUtilsTest {

    @Test
    void truncate_null() {
        assertEquals("", StringUtils.truncate(null, 10));
    }

    @Test
    void truncate_shortString() {
        assertEquals("hello", StringUtils.truncate("hello", 10));
    }

    @Test
    void truncate_exactLength() {
        assertEquals("hello", StringUtils.truncate("hello", 5));
    }

    @Test
    void truncate_longString() {
        assertEquals("hel...", StringUtils.truncate("hello world", 3));
    }

    @Test
    void truncateEllipsis_null() {
        assertEquals("", StringUtils.truncateEllipsis(null, 10));
    }

    @Test
    void truncateEllipsis_shortString() {
        assertEquals("hello", StringUtils.truncateEllipsis("hello", 10));
    }

    @Test
    void truncateEllipsis_longString() {
        String result = StringUtils.truncateEllipsis("hello world", 3);
        assertEquals("hel\u2026", result);
    }

    @Test
    void truncateWithSize_null() {
        assertEquals("", StringUtils.truncateWithSize(null, 10));
    }

    @Test
    void truncateWithSize_shortString() {
        assertEquals("hello", StringUtils.truncateWithSize("hello", 10));
    }

    @Test
    void truncateWithSize_longString() {
        String result = StringUtils.truncateWithSize("hello world", 3);
        assertEquals("hel\n... (truncated, 11 chars total)", result);
    }
}
