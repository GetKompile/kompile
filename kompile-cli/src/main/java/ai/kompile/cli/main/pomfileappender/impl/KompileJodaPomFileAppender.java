/*
 * Copyright 2025 Kompile Inc. (derived from Konduit K.K. original)
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
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package ai.kompile.cli.main.pomfileappender.impl; // Assuming package remains the same

import ai.kompile.cli.main.pomfileappender.PomFileAppender;

import java.util.Arrays;
import java.util.List;

/**
 * GraalVM native image configuration appender for Joda-Time library.
 * Joda-Time classes are generally safe for build-time initialization.
 */
public class KompileJodaPomFileAppender implements PomFileAppender { // Renamed class
    @Override
    public DependencyType dependencyType() {
        // Using the existing JODA type from the PomFileAppender interface.
        // If the DependencyType enum is updated with more Kompile-specific categories,
        // this could be mapped to a more general TIME_UTILS or similar if JODA is too specific.
        return DependencyType.JODA_TIME;
    }

    @Override
    public List<String> classesToAppend() {
        // These are specific Joda-Time classes that GraalVM might need hints for,
        // especially internal ones or those involved in static initialization of timezones.
        // This list is from the original JodaPomFileAppender and should remain valid
        // as Joda-Time's internal structure is independent of Kompile changes.
        return Arrays.asList(
                "org.joda.time.DateTimeFieldType$StandardDateTimeFieldType", // Inner class
                "org.joda.time.tz.FixedDateTimeZone",
                "org.joda.time.DateTimeFieldType",
                "org.joda.time.tz.CachedDateTimeZone", // Timezone caching
                "org.joda.time.DateTimeZone",          // Core timezone class
                "org.joda.time.DateTimeUtils",         // For current millis, etc.
                "org.joda.time.format.DateTimeFormatterBuilder", // If formatters are built statically
                "org.joda.time.format.DateTimeFormat"  // For default formats
                // Consider "org.joda.time.chrono.GregorianChronology", "org.joda.time.chrono.ISOChronology"
                // if specific chronologies are heavily used and cause issues.
                // For most common uses, the above should be a good starting point.
                // "org.joda.time.*" could be used if Joda-Time proves very problematic,
                // but it's better to be specific if possible.
        );
    }

    @Override
    public InitializeType initializeType() {
        // Joda-Time, especially its timezone data and default formatters,
        // is often safe and beneficial to initialize at build time.
        return InitializeType.BUILD_TIME;
    }

    @Override
    public boolean isNative() {
        // Joda-Time is a pure Java library, not directly JNI-related.
        return false;
    }
}
