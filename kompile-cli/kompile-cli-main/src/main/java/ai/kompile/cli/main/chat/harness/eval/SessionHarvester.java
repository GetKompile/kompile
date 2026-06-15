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

package ai.kompile.cli.main.chat.harness.eval;

import ai.kompile.cli.common.chat.sources.ToolCallExtractor;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Harvests tool call data from external agent session files after an eval run.
 * Reuses {@link ToolCallExtractor} for the actual JSONL parsing.
 *
 * <p>This enables TOOL_WAS_CALLED assertions to work against external agents
 * like Claude Code, Codex, Qwen, etc. by reading their session transcripts.
 */
public class SessionHarvester {

    /**
     * Attempt to harvest tool calls from the most recent agent session.
     *
     * @param agentName    the agent name (claude, codex, qwen, etc.)
     * @param workDir      the working directory the agent ran in
     * @param sessionStart when the agent was started (to find the right session file)
     * @return map of tool name to invocation count, or empty map if harvesting fails
     */
    public static Map<String, Integer> harvestToolCalls(String agentName, Path workDir,
                                                         Instant sessionStart) {
        Map<String, Integer> toolCounts = new LinkedHashMap<>();

        try {
            String name = agentName.toLowerCase();
            ToolCallExtractor.ExtractionResult result = null;

            if (name.contains("claude")) {
                result = harvestClaude(workDir, sessionStart);
            } else if (name.contains("codex")) {
                result = harvestCodex(sessionStart);
            } else if (name.contains("qwen")) {
                result = harvestQwen(workDir, sessionStart);
            } else if (name.contains("gemini")) {
                result = harvestGemini(workDir, sessionStart);
            } else if (name.contains("opencode")) {
                result = harvestOpenCode(sessionStart);
            } else if (name.contains("pi")) {
                result = harvestPi(workDir, sessionStart);
            } else if (name.contains("aider")) {
                result = harvestAider(workDir);
            } else if (name.contains("cline") || name.contains("roo")) {
                result = harvestCline(workDir, sessionStart);
            } else if (name.contains("cursor")) {
                result = harvestCursor(sessionStart);
            } else if (name.contains("continue")) {
                result = harvestContinue(sessionStart);
            } else if (name.contains("kompile")) {
                result = harvestKompile(sessionStart);
            }

            if (result != null && result.toolCalls() != null) {
                for (ToolCallExtractor.ExtractedToolCall tc : result.toolCalls()) {
                    toolCounts.merge(tc.toolName(), 1, Integer::sum);
                }
            }
        } catch (Exception e) {
            // Harvesting is best-effort — don't fail the eval
            System.err.println("  Warning: Could not harvest tool calls: " + e.getMessage());
        }

        return toolCounts;
    }

    private static ToolCallExtractor.ExtractionResult harvestClaude(Path workDir,
                                                                      Instant sessionStart) throws IOException {
        Path claudeProjectsDir = Path.of(System.getProperty("user.home"), ".claude", "projects");
        if (!Files.isDirectory(claudeProjectsDir)) return null;

        String absWorkDir = workDir.toAbsolutePath().toString();
        Path projectDir = findClaudeProjectDir(claudeProjectsDir, absWorkDir);
        if (projectDir == null) return null;

        File jsonlFile = findNewestJsonl(projectDir, sessionStart);
        if (jsonlFile == null) return null;

        String sessionId = jsonlFile.getName().replace(".jsonl", "");
        return ToolCallExtractor.extractClaude(jsonlFile.toPath(), sessionId);
    }

    private static ToolCallExtractor.ExtractionResult harvestCodex(Instant sessionStart) throws IOException {
        Path codexSessionsDir = Path.of(System.getProperty("user.home"), ".codex", "sessions");
        if (!Files.isDirectory(codexSessionsDir)) return null;

        File jsonlFile = findNewestJsonlRecursive(codexSessionsDir, sessionStart);
        if (jsonlFile == null) return null;

        String sessionId = jsonlFile.getName().replace(".jsonl", "");
        return ToolCallExtractor.extractCodex(jsonlFile.toPath(), sessionId);
    }

    private static ToolCallExtractor.ExtractionResult harvestQwen(Path workDir,
                                                                    Instant sessionStart) throws IOException {
        Path qwenProjectsDir = Path.of(System.getProperty("user.home"), ".qwen", "projects");
        if (!Files.isDirectory(qwenProjectsDir)) return null;

        String absWorkDir = workDir.toAbsolutePath().toString();
        // Qwen uses same project dir encoding as Claude
        Path projectDir = findClaudeProjectDir(qwenProjectsDir, absWorkDir);
        if (projectDir == null) return null;

        Path chatsDir = projectDir.resolve("chats");
        if (!Files.isDirectory(chatsDir)) return null;

        File jsonlFile = findNewestJsonl(chatsDir, sessionStart);
        if (jsonlFile == null) return null;

        String sessionId = jsonlFile.getName().replace(".jsonl", "");
        return ToolCallExtractor.extractQwen(jsonlFile.toPath(), sessionId);
    }

    private static ToolCallExtractor.ExtractionResult harvestPi(Path workDir,
                                                                     Instant sessionStart) throws IOException {
        // Pi stores sessions at ~/.pi/agent/sessions/--encoded-path--/timestamp_uuid.jsonl
        Path piSessionsDir = Path.of(System.getProperty("user.home"), ".pi", "agent", "sessions");
        if (!Files.isDirectory(piSessionsDir)) return null;

        // Find project dir matching the working directory
        String absWorkDir = workDir.toAbsolutePath().toString();
        String encoded = absWorkDir.replaceAll("^[/\\\\]", "").replaceAll("[/\\\\:]", "-");
        Path projectDir = piSessionsDir.resolve("--" + encoded + "--");
        if (!Files.isDirectory(projectDir)) return null;

        File jsonlFile = findNewestJsonl(projectDir, sessionStart);
        if (jsonlFile == null) return null;

        String sessionId = jsonlFile.getName().replace(".jsonl", "");
        return ToolCallExtractor.extractPi(jsonlFile.toPath(), sessionId);
    }

    private static ToolCallExtractor.ExtractionResult harvestGemini(Path workDir,
                                                                       Instant sessionStart) throws IOException {
        // Gemini CLI stores sessions under ~/.gemini/sessions/
        Path geminiSessionsDir = Path.of(System.getProperty("user.home"), ".gemini", "sessions");
        if (!Files.isDirectory(geminiSessionsDir)) return null;

        // Look for newest JSON or JSONL session file
        File sessionFile = findNewestSessionFile(geminiSessionsDir, sessionStart);
        if (sessionFile == null) return null;

        String sessionId = sessionFile.getName().replaceAll("\\.(json|jsonl)$", "");
        return ToolCallExtractor.extractGemini(sessionFile.toPath(), sessionId);
    }

    private static ToolCallExtractor.ExtractionResult harvestOpenCode(Instant sessionStart) throws IOException {
        // OpenCode stores data in a SQLite database; extractOpenCode reads it directly.
        // We use a timestamp-based session ID as a best-effort match.
        String sessionId = "latest";
        return ToolCallExtractor.extractOpenCode(sessionId);
    }

    static File findNewestSessionFile(Path dir, Instant sessionStart) {
        if (!Files.isDirectory(dir)) return null;
        long cutoff = sessionStart.toEpochMilli() - 30_000;
        try (Stream<Path> files = Files.walk(dir, 2)) {
            return files
                    .filter(p -> {
                        String name = p.toString();
                        return name.endsWith(".jsonl") || name.endsWith(".json");
                    })
                    .map(Path::toFile)
                    .filter(f -> f.lastModified() >= cutoff)
                    .max((a, b) -> Long.compare(a.lastModified(), b.lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static ToolCallExtractor.ExtractionResult harvestAider(Path workDir) throws IOException {
        // Aider stores history in <projectDir>/.aider.chat.history.md
        Path historyFile = workDir.resolve(".aider.chat.history.md");
        if (!Files.isRegularFile(historyFile)) {
            // Also check home directory
            historyFile = Path.of(System.getProperty("user.home"), ".aider.chat.history.md");
            if (!Files.isRegularFile(historyFile)) return null;
        }
        return ToolCallExtractor.extractAider(historyFile, "latest");
    }

    private static ToolCallExtractor.ExtractionResult harvestCline(Path workDir,
                                                                      Instant sessionStart) throws IOException {
        // Cline stores tasks under VSCode globalStorage
        Path configRoot = Path.of(System.getProperty("user.home"));
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("mac")) {
            configRoot = configRoot.resolve("Library").resolve("Application Support");
        } else if (osName.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null) configRoot = Path.of(appData);
        } else {
            configRoot = configRoot.resolve(".config");
        }

        String[] codeVariants = {"Code", "Code - Insiders", "Cursor", "VSCodium"};
        String[] extIds = {"saoudrizwan.claude-dev", "RooVeterinaryInc.roo-cline"};

        for (String variant : codeVariants) {
            for (String ext : extIds) {
                Path tasks = configRoot.resolve(variant).resolve("User")
                        .resolve("globalStorage").resolve(ext).resolve("tasks");
                if (!Files.isDirectory(tasks)) continue;

                // Find newest task directory with api_conversation_history.json
                File taskDir = findNewestTaskDir(tasks, sessionStart);
                if (taskDir == null) continue;

                Path convFile = taskDir.toPath().resolve("api_conversation_history.json");
                if (Files.exists(convFile)) {
                    return ToolCallExtractor.extractCline(convFile, taskDir.getName());
                }
            }
        }
        return null;
    }

    private static ToolCallExtractor.ExtractionResult harvestCursor(Instant sessionStart) throws IOException {
        // Cursor extraction reads directly from SQLite — delegate to the extractor
        // which scans all state.vscdb files for the most recent session
        return ToolCallExtractor.extractCursor("latest");
    }

    private static ToolCallExtractor.ExtractionResult harvestContinue(Instant sessionStart) throws IOException {
        Path sessionsDir = Path.of(System.getProperty("user.home"), ".continue", "sessions");
        if (Files.isDirectory(sessionsDir)) {
            // Find newest JSON session file
            File sessionFile = findNewestSessionFile(sessionsDir, sessionStart);
            if (sessionFile != null) {
                String sessionId = sessionFile.getName().replaceAll("\\.json$", "");
                return ToolCallExtractor.extractContinue(sessionFile.toPath(), sessionId);
            }
        }

        // Fall back to SQLite
        Path db = Path.of(System.getProperty("user.home"), ".continue", "session.db");
        if (!Files.exists(db)) {
            db = Path.of(System.getProperty("user.home"), ".continue", "index", "session.db");
        }
        if (Files.exists(db)) {
            return ToolCallExtractor.extractContinueSqlite(db, "latest");
        }
        return null;
    }

    private static ToolCallExtractor.ExtractionResult harvestKompile(Instant sessionStart) throws IOException {
        Path convDir = Path.of(System.getProperty("user.home"), ".kompile", "conversations");
        if (!Files.isDirectory(convDir)) return null;

        long cutoff = sessionStart.toEpochMilli() - 30_000;
        File newest = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(convDir, "*.txt")) {
            for (Path p : stream) {
                File f = p.toFile();
                if (f.lastModified() >= cutoff) {
                    if (newest == null || f.lastModified() > newest.lastModified()) {
                        newest = f;
                    }
                }
            }
        }
        if (newest == null) return null;

        String sessionId = newest.getName().replace(".txt", "");
        return ToolCallExtractor.extractKompile(newest.toPath(), sessionId);
    }

    static File findNewestTaskDir(Path tasksRoot, Instant sessionStart) {
        if (!Files.isDirectory(tasksRoot)) return null;
        long cutoff = sessionStart.toEpochMilli() - 30_000;
        try (Stream<Path> dirs = Files.list(tasksRoot)) {
            return dirs.filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve("api_conversation_history.json")))
                    .map(Path::toFile)
                    .filter(f -> {
                        File conv = new File(f, "api_conversation_history.json");
                        return conv.lastModified() >= cutoff;
                    })
                    .max((a, b) -> {
                        File ca = new File(a, "api_conversation_history.json");
                        File cb = new File(b, "api_conversation_history.json");
                        return Long.compare(ca.lastModified(), cb.lastModified());
                    })
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    // ── Session file discovery (simplified from PassthroughCommand) ────

    static Path findClaudeProjectDir(Path projectsDir, String absWorkDir) {
        // Encode path: replace / with -
        String encoded = absWorkDir.replace("/", "-");
        if (encoded.startsWith("-")) encoded = encoded.substring(0); // keep leading -

        Path exact = projectsDir.resolve(encoded);
        if (Files.isDirectory(exact)) return exact;

        // Fallback: scan for matching dirs
        try (Stream<Path> dirs = Files.list(projectsDir)) {
            return dirs.filter(Files::isDirectory)
                    .filter(d -> {
                        String name = d.getFileName().toString();
                        String decoded = name.replace("-", "/");
                        return absWorkDir.startsWith(decoded) || decoded.startsWith(absWorkDir);
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    static File findNewestJsonl(Path dir, Instant sessionStart) {
        if (!Files.isDirectory(dir)) return null;
        long cutoff = sessionStart.toEpochMilli() - 30_000; // 30s tolerance

        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .map(Path::toFile)
                    .filter(f -> f.lastModified() >= cutoff)
                    .max((a, b) -> Long.compare(a.lastModified(), b.lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    static File findNewestJsonlRecursive(Path rootDir, Instant sessionStart) {
        if (!Files.isDirectory(rootDir)) return null;
        long cutoff = sessionStart.toEpochMilli() - 30_000;

        try (Stream<Path> files = Files.walk(rootDir)) {
            return files
                    .filter(p -> p.toString().endsWith(".jsonl"))
                    .map(Path::toFile)
                    .filter(f -> f.lastModified() >= cutoff)
                    .max((a, b) -> Long.compare(a.lastModified(), b.lastModified()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
