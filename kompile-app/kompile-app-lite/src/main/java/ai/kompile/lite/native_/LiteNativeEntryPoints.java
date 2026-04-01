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

package ai.kompile.lite.native_;

/**
 * GraalVM @CEntryPoint exports for the Python SDK shared library build.
 * <p>
 * These methods are exported as C-callable functions when building as a shared library
 * (as opposed to a standalone executable). They wrap the Lite services and are called
 * from the C library wrappers in kompile-c-library.
 * <p>
 * To build as a shared library:
 * <pre>
 *   native-image --shared -jar kompile-app-lite.jar -o libkompile_lite
 * </pre>
 * <p>
 * NOTE: The actual @CEntryPoint annotations require the GraalVM SDK on the classpath
 * and are only active during native image compilation. This class is a placeholder
 * that documents the expected entry points. The actual annotated methods would use:
 * <pre>
 *   @CEntryPoint(name = "initLite")
 *   @CEntryPoint(name = "liteChat")
 *   @CEntryPoint(name = "liteIngestDocument")
 *   @CEntryPoint(name = "liteRagQuery")
 *   @CEntryPoint(name = "liteGraphQuery")
 *   @CEntryPoint(name = "liteBuildGraph")
 * </pre>
 */
public class LiteNativeEntryPoints {

    // Entry points will be implemented when GraalVM shared library build is needed.
    // The C wrappers in kompile-c-library/library.c call these exported symbols:
    //
    // int initLite(graal_isolatethread_t *thread, handles *h, char *configPath)
    // char* liteChat(graal_isolatethread_t *thread, handles *h, char *message, char *sessionId)
    // int liteIngestDocument(graal_isolatethread_t *thread, handles *h, char *filePath)
    // char* liteRagQuery(graal_isolatethread_t *thread, handles *h, char *query, int maxResults)
    // char* liteGraphQuery(graal_isolatethread_t *thread, handles *h, char *query, int k)
    // int liteBuildGraph(graal_isolatethread_t *thread, handles *h)

    private LiteNativeEntryPoints() {}
}
