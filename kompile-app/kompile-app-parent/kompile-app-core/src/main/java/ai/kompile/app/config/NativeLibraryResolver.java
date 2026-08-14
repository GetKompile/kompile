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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.app.config;

import java.nio.file.Path;
import java.util.List;

/**
 * Compatibility facade for the shared native side-loading resolver.
 *
 * <p>The implementation lives in {@code kompile-utils} so every model-bearing
 * executable can bootstrap native libraries before loading Spring, ND4J, or
 * JavaCPP classes without depending on application-core.</p>
 */
public final class NativeLibraryResolver {

    private NativeLibraryResolver() {
    }

    public static boolean bootstrap() {
        return ai.kompile.utils.NativeLibraryResolver.bootstrap();
    }

    public static void bootstrapOrThrow() {
        ai.kompile.utils.NativeLibraryResolver.bootstrapOrThrow();
    }

    public static List<Path> resolve() {
        return ai.kompile.utils.NativeLibraryResolver.resolve();
    }

    public static void configureJavaCpp(List<Path> libDirs) {
        ai.kompile.utils.NativeLibraryResolver.configureJavaCpp(libDirs);
    }
}
