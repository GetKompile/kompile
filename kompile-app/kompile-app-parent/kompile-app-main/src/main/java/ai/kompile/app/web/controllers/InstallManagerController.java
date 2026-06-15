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

package ai.kompile.app.web.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.*;

/**
 * REST controller exposing the web equivalent of {@code kompile install} / {@code kompile uninstall}.
 *
 * <ul>
 *   <li>{@code GET    /api/install/tools}            — list all known tools with install status</li>
 *   <li>{@code POST   /api/install/tools/{toolId}}   — queue an install request for the given tool</li>
 *   <li>{@code DELETE /api/install/tools/{toolId}}   — queue an uninstall request for the given tool</li>
 * </ul>
 *
 * <p>Install/uninstall endpoints return {@code 202 Accepted}: actual filesystem operations are
 * server-side concerns that run outside the request lifecycle.
 */
@RestController
@RequestMapping("/api/install")
public class InstallManagerController {

    private static final Logger logger = LoggerFactory.getLogger(InstallManagerController.class);

    /** Canonical set of tools managed by Kompile, keyed by stable {@code toolId}. */
    private static final Map<String, ToolMetadata> KNOWN_TOOLS;

    static {
        Map<String, ToolMetadata> tools = new LinkedHashMap<>();
        tools.put("graalvm", new ToolMetadata(
                "graalvm",
                "GraalVM",
                "GraalVM CE JDK for native image compilation",
                "graalvm"));
        tools.put("maven", new ToolMetadata(
                "maven",
                "Apache Maven",
                "Build automation tool for Java projects",
                "mvn"));
        tools.put("python", new ToolMetadata(
                "python",
                "Python (Miniconda)",
                "Python distribution with conda package manager",
                "python"));
        tools.put("cmake", new ToolMetadata(
                "cmake",
                "CMake",
                "Cross-platform build system generator",
                "cmake"));
        KNOWN_TOOLS = Collections.unmodifiableMap(tools);
    }

    // No-arg constructor — no injected dependencies
    public InstallManagerController() {
    }

    // =========================================================================
    // GET /api/install/tools
    // =========================================================================

    /**
     * Returns the list of known developer tools together with their current install status,
     * resolved installation path, and detected version (where available).
     */
    @GetMapping("/tools")
    public ResponseEntity<List<Map<String, Object>>> getTools() {
        try {
            List<Map<String, Object>> result = new ArrayList<>();
            for (ToolMetadata meta : KNOWN_TOOLS.values()) {
                result.add(buildToolEntry(meta));
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error building tools list", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // =========================================================================
    // POST /api/install/tools/{toolId}
    // =========================================================================

    /**
     * Accepts an install request for the specified tool. Returns {@code 202 Accepted}.
     *
     * @param toolId the stable tool identifier (e.g. {@code graalvm}, {@code maven})
     */
    @PostMapping("/tools/{toolId}")
    public ResponseEntity<Map<String, Object>> installTool(@PathVariable String toolId) {
        ToolMetadata meta = KNOWN_TOOLS.get(toolId.toLowerCase(Locale.ROOT));
        if (meta == null) {
            return unknownToolResponse(toolId);
        }

        logger.info("Install request received for tool '{}'", toolId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("toolId", meta.id);
        response.put("toolName", meta.name);
        response.put("status", "accepted");
        response.put("message", "Install request queued for " + meta.name
                + ". This operation runs server-side.");
        return ResponseEntity.accepted().body(response);
    }

    // =========================================================================
    // DELETE /api/install/tools/{toolId}
    // =========================================================================

    /**
     * Accepts an uninstall request for the specified tool. Returns {@code 202 Accepted}.
     *
     * @param toolId the stable tool identifier
     */
    @DeleteMapping("/tools/{toolId}")
    public ResponseEntity<Map<String, Object>> uninstallTool(@PathVariable String toolId) {
        ToolMetadata meta = KNOWN_TOOLS.get(toolId.toLowerCase(Locale.ROOT));
        if (meta == null) {
            return unknownToolResponse(toolId);
        }

        logger.info("Uninstall request received for tool '{}'", toolId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("toolId", meta.id);
        response.put("toolName", meta.name);
        response.put("status", "accepted");
        response.put("message", "Uninstall request queued for " + meta.name
                + ". This operation runs server-side.");
        return ResponseEntity.accepted().body(response);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private String kompileHomePath() {
        return System.getProperty("user.home") + "/.kompile";
    }

    /**
     * Builds the JSON object for a single tool entry, probing the filesystem for install state
     * and version information.
     */
    private Map<String, Object> buildToolEntry(ToolMetadata meta) {
        String installPath = kompileHomePath() + "/" + meta.subDir;
        File installDir = new File(installPath);
        boolean installed = isInstalled(meta, installDir);
        String version = installed ? detectVersion(meta, installDir) : null;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", meta.id);
        entry.put("name", meta.name);
        entry.put("description", meta.description);
        entry.put("installed", installed);
        entry.put("path", installPath);
        if (version != null) {
            entry.put("version", version);
        } else if (installed) {
            entry.put("version", "unknown");
        }
        return entry;
    }

    /**
     * Determines whether a tool is considered installed based on tool-specific heuristics.
     */
    private boolean isInstalled(ToolMetadata meta, File installDir) {
        if (!installDir.exists() || !installDir.isDirectory()) {
            return false;
        }
        switch (meta.id) {
            case "graalvm":
                // Must have the release metadata file
                return new File(installDir, "release").isFile();
            case "maven":
                // Must have at least one maven jar in the lib directory
                File libDir = new File(installDir, "lib");
                if (!libDir.isDirectory()) return false;
                File[] mavenJars = libDir.listFiles(
                        (dir, name) -> name.startsWith("maven-") && name.endsWith(".jar"));
                return mavenJars != null && mavenJars.length > 0;
            case "python":
                return new File(installDir, "bin/python3").isFile();
            case "cmake":
                return new File(installDir, "bin/cmake").isFile();
            default:
                return installDir.exists();
        }
    }

    /**
     * Attempts to detect a human-readable version string for an installed tool.
     * Returns {@code null} if detection is not possible or the tool is not installed.
     */
    private String detectVersion(ToolMetadata meta, File installDir) {
        try {
            switch (meta.id) {
                case "graalvm":
                    return parseGraalVmVersion(new File(installDir, "release"));
                case "maven":
                    // Parse version from a jar filename like maven-3.9.6-uber.jar or maven-3.9.6.jar
                    File lib = new File(installDir, "lib");
                    if (lib.isDirectory()) {
                        File[] jars = lib.listFiles(
                                (dir, name) -> name.startsWith("maven-") && name.endsWith(".jar"));
                        if (jars != null && jars.length > 0) {
                            String jarName = jars[0].getName(); // e.g. maven-3.9.6-uber.jar
                            // Extract the version segment between first and second dash
                            String stripped = jarName.substring("maven-".length());
                            int dashIdx = stripped.indexOf('-');
                            String candidate = dashIdx > 0 ? stripped.substring(0, dashIdx)
                                    : stripped.replace(".jar", "");
                            if (!candidate.isEmpty()) return candidate;
                        }
                    }
                    return null;
                case "python":
                    // Check for a pyvenv.cfg which may contain a version line
                    File pyVerFile = new File(installDir, "pyvenv.cfg");
                    if (pyVerFile.isFile()) {
                        try (BufferedReader reader = new BufferedReader(new FileReader(pyVerFile))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.startsWith("version")) {
                                    String[] parts = line.split("=", 2);
                                    if (parts.length == 2) return parts[1].trim();
                                }
                            }
                        }
                    }
                    return null;
                case "cmake":
                    // cmake --version output is not easily readable without exec; return null
                    return null;
                default:
                    return null;
            }
        } catch (Exception e) {
            logger.debug("Could not detect version for tool '{}': {}", meta.id, e.getMessage());
            return null;
        }
    }

    /**
     * Reads the {@code JAVA_VERSION} line from a GraalVM {@code release} file.
     */
    private String parseGraalVmVersion(File releaseFile) {
        if (!releaseFile.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(releaseFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("JAVA_VERSION=")) {
                    return line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                }
            }
        } catch (Exception e) {
            logger.debug("Could not parse GraalVM release file: {}", e.getMessage());
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> unknownToolResponse(String toolId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "error");
        response.put("message", "Unknown tool: " + toolId
                + ". Valid tool IDs: " + String.join(", ", KNOWN_TOOLS.keySet()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // =========================================================================
    // Inner type
    // =========================================================================

    /**
     * Immutable metadata record for a known managed tool.
     */
    private static final class ToolMetadata {
        final String id;
        final String name;
        final String description;
        /** Sub-directory name under {@code ~/.kompile/}. */
        final String subDir;

        ToolMetadata(String id, String name, String description, String subDir) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.subDir = subDir;
        }
    }
}
