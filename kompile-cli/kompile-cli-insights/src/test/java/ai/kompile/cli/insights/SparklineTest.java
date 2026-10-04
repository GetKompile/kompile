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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SparklineTest {

    @Test
    void barsScaleToTheLargestValue() {
        assertEquals("▁▄▅█", Sparkline.of(new long[]{0, 1, 2, 4}));
    }

    @Test
    void anyNonZeroValueDrawsAboveTheBaseline() {
        assertEquals("▂█", Sparkline.of(new long[]{1, 1_000}));
    }

    @Test
    void allZeroDrawsTheBaseline() {
        assertEquals("▁▁▁", Sparkline.of(new long[]{0, 0, 0}));
        assertEquals("", Sparkline.of(new long[0]));
    }

    @Test
    void missingDataIsASpace() {
        assertEquals("█ ▁", Sparkline.of(new double[]{3, Double.NaN, 0}));
    }

    @Test
    void ratiosCanScaleToAFixedMaximum() {
        // Pass rates read as absolute: 50% is half height even when nothing reaches 100%.
        assertEquals("▅▅", Sparkline.of(new double[]{0.5, 0.5}, 1.0));
        assertEquals("██", Sparkline.of(new double[]{1.0, 2.0}, 1.0));
    }
}
