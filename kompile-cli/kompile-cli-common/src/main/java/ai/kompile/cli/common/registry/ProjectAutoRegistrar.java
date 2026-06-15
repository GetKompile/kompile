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

package ai.kompile.cli.common.registry;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.common.mcp.InstanceDiscovery;

import java.nio.file.Path;
import java.util.Map;

/**
 * Auto-registers the current working directory as a code project
 * with a running kompile-app instance.
 *
 * <p>Best-effort and non-blocking: failures are silently ignored so
 * this never disrupts CLI operation. Designed to be called from a
 * background thread at CLI startup or after indexing completes.</p>
 *
 * <p>Uses the idempotent {@code PUT /api/code-projects/auto-register}
 * endpoint, so repeated calls for the same directory are safe.</p>
 */
public class ProjectAutoRegistrar {

    private ProjectAutoRegistrar() {
    }

    /**
     * Attempts to register a directory as a code project with a running app.
     *
     * @param directory  the absolute path to the project directory
     * @param projectId  project identifier (nullable; derived from directory name if null)
     * @param appUrl     explicit app URL (nullable; auto-discovered if null)
     */
    public static void register(Path directory, String projectId, String appUrl) {
        register(directory, projectId, appUrl, null, null);
    }

    /**
     * Attempts to register a directory as a code project with a running app,
     * and optionally links a CLI session to that project.
     *
     * @param directory  the absolute path to the project directory
     * @param projectId  project identifier (nullable; derived from directory name if null)
     * @param appUrl     explicit app URL (nullable; auto-discovered if null)
     * @param sessionId  CLI session ID to link to the project (nullable)
     * @param source     session source identifier, e.g. "kompile" (nullable)
     */
    public static void register(Path directory, String projectId, String appUrl,
                                String sessionId, String source) {
        try {
            String url = appUrl;
            if (url == null || url.isBlank()) {
                url = InstanceDiscovery.discover();
            }
            if (url == null) {
                return; // No app instance found — nothing to do
            }

            KompileHttpClient client = new KompileHttpClient(url);
            if (!client.isHealthy()) {
                return;
            }

            String absPath = directory.toAbsolutePath().toString();
            String pid = projectId;
            if (pid == null || pid.isBlank()) {
                pid = directory.toAbsolutePath().getFileName().toString();
            }

            // Register the project directory
            client.put("/api/code-projects/auto-register",
                    Map.of(
                            "directory", absPath,
                            "projectId", pid,
                            "name", pid
                    ),
                    Void.class);

            // Link the CLI session to the project if a sessionId was provided
            if (sessionId != null && !sessionId.isBlank()) {
                client.put("/api/chat-history/sessions/register-for-project",
                        Map.of(
                                "sessionId", sessionId,
                                "codeProjectId", pid,
                                "source", source != null ? source : "kompile",
                                "title", "CLI Session — " + pid
                        ),
                        Void.class);
            }
        } catch (Exception ignored) {
            // Best-effort — never disrupt CLI operation
        }
    }

    /**
     * Fires registration on a daemon thread so it never blocks the caller.
     */
    public static void registerAsync(Path directory, String projectId, String appUrl) {
        registerAsync(directory, projectId, appUrl, null, null);
    }

    /**
     * Fires registration on a daemon thread so it never blocks the caller.
     * Also links a CLI session to the project.
     */
    public static void registerAsync(Path directory, String projectId, String appUrl,
                                     String sessionId, String source) {
        Thread t = new Thread(() -> register(directory, projectId, appUrl, sessionId, source),
                "project-auto-register");
        t.setDaemon(true);
        t.start();
    }
}
