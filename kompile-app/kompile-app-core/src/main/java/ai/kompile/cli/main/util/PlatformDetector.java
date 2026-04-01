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

package ai.kompile.cli.main.util;

import java.util.Map;

/**
 * @deprecated Use {@link ai.kompile.cli.common.util.PlatformDetector} from kompile-cli-common instead.
 */
@Deprecated
public class PlatformDetector {

    public static class PlatformInfo {
        private final ai.kompile.cli.common.util.PlatformDetector.PlatformInfo delegate;

        PlatformInfo(ai.kompile.cli.common.util.PlatformDetector.PlatformInfo delegate) {
            this.delegate = delegate;
        }

        public String getOsName() { return delegate.getOsName(); }
        public String getOsKernel() { return delegate.getOsKernel(); }
        public String getOsArch() { return delegate.getOsArch(); }
        public String getOsFamily() { return delegate.getOsFamily(); }

        @Override
        public String toString() { return delegate.toString(); }
        public String getIdentifier() { return delegate.getIdentifier(); }
        public String getFileExtension() { return delegate.getFileExtension(); }
    }

    public static PlatformInfo detectPlatform() {
        return new PlatformInfo(ai.kompile.cli.common.util.PlatformDetector.detectPlatform());
    }

    public static boolean matchesPlatform(String expectedOsName, String expectedArch, String expectedFamily) {
        return ai.kompile.cli.common.util.PlatformDetector.matchesPlatform(expectedOsName, expectedArch, expectedFamily);
    }

    public static Map<String, Boolean> getActiveProfiles() {
        return ai.kompile.cli.common.util.PlatformDetector.getActiveProfiles();
    }
}
