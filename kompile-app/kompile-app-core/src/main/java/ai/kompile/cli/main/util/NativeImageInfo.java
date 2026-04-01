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

import java.nio.file.Path;

/**
 * @deprecated Use {@link ai.kompile.cli.common.util.NativeImageInfo} from kompile-cli-common instead.
 */
@Deprecated
public final class NativeImageInfo {

    private NativeImageInfo() {
    }

    public static boolean isRunningInNativeImage() {
        return ai.kompile.cli.common.util.NativeImageInfo.isRunningInNativeImage();
    }

    public static String getExecutablePath() {
        return ai.kompile.cli.common.util.NativeImageInfo.getExecutablePath();
    }

    public static Path getExecutablePathAsPath() {
        return ai.kompile.cli.common.util.NativeImageInfo.getExecutablePathAsPath();
    }

    public static boolean hasClasspath() {
        return ai.kompile.cli.common.util.NativeImageInfo.hasClasspath();
    }

    public static SubprocessLaunchMode getRecommendedLaunchMode() {
        ai.kompile.cli.common.util.NativeImageInfo.SubprocessLaunchMode mode =
                ai.kompile.cli.common.util.NativeImageInfo.getRecommendedLaunchMode();
        return mode == ai.kompile.cli.common.util.NativeImageInfo.SubprocessLaunchMode.NATIVE_EXECUTABLE
                ? SubprocessLaunchMode.NATIVE_EXECUTABLE
                : SubprocessLaunchMode.JVM_CLASSPATH;
    }

    public enum SubprocessLaunchMode {
        JVM_CLASSPATH,
        NATIVE_EXECUTABLE
    }
}
