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

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class FormatUtilsTest {

    @Test
    void formatDuration_hours() {
        assertEquals("2h 15m 30s", FormatUtils.formatDuration(Duration.ofSeconds(2 * 3600 + 15 * 60 + 30)));
    }

    @Test
    void formatDuration_minutes() {
        assertEquals("45m 12s", FormatUtils.formatDuration(Duration.ofSeconds(45 * 60 + 12)));
    }

    @Test
    void formatDuration_seconds() {
        assertEquals("8s", FormatUtils.formatDuration(Duration.ofSeconds(8)));
    }

    @Test
    void formatDuration_millis() {
        assertEquals("150ms", FormatUtils.formatDuration(Duration.ofMillis(150)));
    }

    @Test
    void formatDuration_fromMillis() {
        assertEquals("1m 30s", FormatUtils.formatDuration(90_000L));
    }

    @Test
    void formatBytes_bytes() {
        assertEquals("512 B", FormatUtils.formatBytes(512));
    }

    @Test
    void formatBytes_kilobytes() {
        assertEquals("1.5 KB", FormatUtils.formatBytes(1536));
    }

    @Test
    void formatBytes_megabytes() {
        assertEquals("10.0 MB", FormatUtils.formatBytes(10 * 1024 * 1024));
    }

    @Test
    void formatBytes_gigabytes() {
        assertEquals("1.50 GB", FormatUtils.formatBytes((long) (1.5 * 1024 * 1024 * 1024)));
    }

    @Test
    void formatBytes_negative() {
        assertEquals("unknown", FormatUtils.formatBytes(-1));
    }

    @Test
    void formatNumber_small() {
        assertEquals("42", FormatUtils.formatNumber(42));
    }

    @Test
    void formatNumber_large() {
        assertEquals("1,234,567", FormatUtils.formatNumber(1_234_567));
    }

    @Test
    void formatNumber_negative() {
        assertEquals("-500", FormatUtils.formatNumber(-500));
    }

    @Test
    void formatNumber_thousand() {
        assertEquals("1,000", FormatUtils.formatNumber(1000));
    }
}
