/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.cli.main.pomfileappender.impl;

import ai.kompile.cli.main.pomfileappender.PomFileAppender;

import java.util.Arrays;
import java.util.List;

public class JodaPomFileAppender implements PomFileAppender {
    @Override
    public DependencyType dependencyType() {
        return DependencyType.JODA;
    }

    @Override
    public List<String> classesToAppend() {
        return Arrays.asList("org.joda.time.DateTimeFieldType$StandardDateTimeFieldType",
        "org.joda.time.tz.FixedDateTimeZone",
        "org.joda.time.DateTimeFieldType",
         "org.joda.time.tz.CachedDateTimeZone",
         "org.joda.time.DateTimeZone");
    }

    @Override
    public InitializeType initializeType() {
        return InitializeType.BUILD_TIME;
    }
}
