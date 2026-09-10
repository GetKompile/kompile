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

package ai.kompile.cli.common.logs;

import java.io.IOException;

import ai.kompile.cli.common.KompileHome;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Canonical paths for Kompile log aggregation under {@code ~/.kompile/logs}.
 *
 * <p>Layout:
 * <pre>
 *   ~/.kompile/logs/
 *     agents/&lt;orchestratorInstanceId&gt;/&lt;agentName&gt;/
 *         &lt;processId&gt;.log        JSON-lines stream of raw agent output
 *         &lt;processId&gt;.meta.json  Metadata sidecar (sessionId, cmd, timings, exit)
 *     cli/kompile-agent-YYYY-MM-DD.log    CLI-side rolled logs
 *     subprocesses/&lt;type&gt;/&lt;taskId&gt;.log  Subprocess launcher logs
 * </pre>
 *
 * <p>All methods return {@link File} instances; they do not create directories.
 * Callers that need to write should invoke {@link #ensureAgentLogDir}.
 */
public final class LogPaths {

    public static final String UNKNOWN_INSTANCE = "_default";
    public static final String UNKNOWN_AGENT = "_unknown";

    private LogPaths() {
    }

    /** Resolve the logs root for a specific working directory:
     * <ul>
     *   <li>Prefer the nearest ancestor {@code .kompile} directory.</li>
     *   <li>Fall back to {@code ~/.kompile}.</li>
     * </ul>
     */
    public static File logsDirectory(Path workingDirectory) {
        return resolveKompileRoot(workingDirectory).resolve("logs").toFile();
    }

    /** {@code ~/.kompile/logs} */
    public static File logsDirectory() {
        return new File(KompileHome.homeDirectory(), "logs");
    }

    /** {@code ~/.kompile/logs/agents} */
    public static File agentsRoot() {
        return new File(logsDirectory(), "agents");
    }

    /** {@code ~/.kompile/logs/cli} */
    public static File cliRoot() {
        return new File(logsDirectory(), "cli");
    }

    /** {@code ~/.kompile/logs/transcripts} — stable across project-directory changes on resume. */
    public static File transcriptsRoot() {
        return new File(logsDirectory(), "transcripts");
    }

    /** {@code ~/.kompile/logs/transcripts/<transcriptId>} */
    public static File transcriptDirectory(String transcriptId) {
        Path root = transcriptsRoot().toPath().toAbsolutePath().normalize();
        Path resolved = root.resolve(transcriptPathSegment(transcriptId)).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("Invalid transcript log identity");
        }
        return resolved.toFile();
    }

    /** Creates and returns {@code ~/.kompile/logs/transcripts/<transcriptId>}. */
    public static File ensureTranscriptDirectory(String transcriptId) throws IOException {
        File dir = transcriptDirectory(transcriptId);
        ensureDir(dir);
        return dir;
    }

    /** Restrict an arbitrary identifier to one safe filesystem path segment. */
    public static String safePathSegment(String value) {
        return safe(value, "_unknown");
    }

    /** {@code <kompile-home>/logs/subprocesses} where <kompile-home> is project-aware if available. */
    public static File subprocessesRoot(Path workingDirectory) {
        return new File(logsDirectory(workingDirectory), "subprocesses");
    }

    /** {@code ~/.kompile/logs/subprocesses} */
    public static File subprocessesRoot() {
        return new File(logsDirectory(), "subprocesses");
    }

    /** {@code ~/.kompile/logs/agents/<instanceId>/<agentName>} */
    public static File agentLogDir(String orchestratorInstanceId, String agentName) {
        String instance = safe(orchestratorInstanceId, UNKNOWN_INSTANCE);
        String agent = safe(agentName, UNKNOWN_AGENT);
        return new File(new File(agentsRoot(), instance), agent);
    }

    /** {@code <agentLogDir>/<processId>.log} */
    public static File agentLogFile(String orchestratorInstanceId, String agentName, String processId) {
        return new File(agentLogDir(orchestratorInstanceId, agentName), processId + ".log");
    }

    /** {@code <agentLogDir>/<processId>.meta.json} */
    public static File agentMetaFile(String orchestratorInstanceId, String agentName, String processId) {
        return new File(agentLogDir(orchestratorInstanceId, agentName), processId + ".meta.json");
    }

    /**
     * Creates the agent log directory if missing. Returns the directory.
     *
     * @throws IOException if creation fails
     */
    public static File ensureAgentLogDir(String orchestratorInstanceId, String agentName)
            throws IOException {
        File dir = agentLogDir(orchestratorInstanceId, agentName);
        ensureDir(dir);
        return dir;
    }

    /** {@code ~/.kompile/logs/subprocesses/<type>} */
    public static File subprocessTypeDir(String subprocessType) {
        return new File(subprocessesRoot(), safe(subprocessType, "_unknown"));
    }

    /** {@code <kompile-home>/logs/subprocesses/<type>} with project-aware root lookup. */
    public static File subprocessTypeDir(Path workingDirectory, String subprocessType) {
        return new File(subprocessesRoot(workingDirectory), safe(subprocessType, "_unknown"));
    }

    /** {@code <subprocessTypeDir>/<runId>.log} */
    public static File subprocessLogFile(String subprocessType, String runId) {
        return new File(subprocessTypeDir(subprocessType), safe(runId, "_unknown") + ".log");
    }

    /** {@code <subprocessTypeDir>/<runId>.log} with project-aware root lookup. */
    public static File subprocessLogFile(Path workingDirectory, String subprocessType, String runId) {
        return new File(subprocessTypeDir(workingDirectory, subprocessType), safe(runId, "_unknown") + ".log");
    }

    /** {@code <subprocessTypeDir>/<runId>.meta.json} */
    public static File subprocessMetaFile(String subprocessType, String runId) {
        return new File(subprocessTypeDir(subprocessType), safe(runId, "_unknown") + ".meta.json");
    }

    /** {@code <subprocessTypeDir>/<runId>.meta.json} with project-aware root lookup. */
    public static File subprocessMetaFile(Path workingDirectory, String subprocessType, String runId) {
        return new File(subprocessTypeDir(workingDirectory, subprocessType), safe(runId, "_unknown") + ".meta.json");
    }

    /** Creates the subprocess-type directory if missing. Returns the directory. */
    public static File ensureSubprocessDir(String subprocessType) throws IOException {
        File dir = subprocessTypeDir(subprocessType);
        ensureDir(dir);
        return dir;
    }

    /** Creates the project-aware subprocess-type directory if missing. Returns the directory. */
    public static File ensureSubprocessDir(Path workingDirectory, String subprocessType) throws IOException {
        File dir = subprocessTypeDir(workingDirectory, subprocessType);
        ensureDir(dir);
        return dir;
    }

    /** {@code ~/.kompile/logs/crawls} */
    public static File crawlsRoot() {
        return new File(logsDirectory(), "crawls");
    }

    /** {@code <crawlsRoot>/<jobId>.log} — one flat file per crawl job. */
    public static File crawlLogFile(String jobId) {
        return new File(crawlsRoot(), safe(jobId, "_unknown") + ".log");
    }

    /** Creates the crawls log directory if missing. Returns the directory. */
    public static File ensureCrawlsDir() throws IOException {
        File dir = crawlsRoot();
        ensureDir(dir);
        return dir;
    }

    /** Creates the root log directory tree ({@code logs/agents}, {@code logs/cli}). */
    public static void ensureRootDirs() throws IOException {
        ensureDir(agentsRoot());
        ensureDir(cliRoot());
        ensureDir(transcriptsRoot());
        ensureDir(subprocessesRoot());
        ensureDir(crawlsRoot());
    }

    private static void ensureDir(File dir) throws IOException {
        Path p = dir.toPath();
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("Failed to create directory: " + p);
        }
    }

    private static Path resolveKompileRoot(Path workingDirectory) {
        Path current = workingDirectory != null
                ? workingDirectory.toAbsolutePath().normalize()
                : Paths.get(System.getProperty("user.home"), ".kompile").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(".kompile");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return KompileHome.homeDirectory().toPath();
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return ".".equals(sanitized) || "..".equals(sanitized)
                ? "_" + sanitized.replace('.', '_') : sanitized;
    }

    private static String transcriptPathSegment(String value) {
        if (value == null || value.isBlank()) return "_unknown";
        String candidate = value.trim();
        if (candidate.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            return candidate.toLowerCase(Locale.ROOT);
        }
        // Canonical UUIDs stay readable. Legacy/custom IDs use lowercase UTF-8 hex,
        // which is injective and portable across case-insensitive filesystems.
        return "~" + HexFormat.of().formatHex(candidate.getBytes(StandardCharsets.UTF_8));
    }
}
