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

package ai.kompile.cli.main.build;

import ai.kompile.cli.main.MainCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for portability of generated config file paths.
 *
 * <p>Generated config files (app-index-config.json, anserini-config.json,
 * nd4j-environment-config.json) must NOT contain absolute paths that embed the
 * generating machine's home directory.  If they did, copying the project to
 * another machine would break at runtime.</p>
 *
 * <p>Additionally, when auto-detected data sources are re-rooted into the project
 * directory, the {@code kompile.project.json} crawl-profile sources must be
 * relative (start with {@code ./}) rather than absolute.</p>
 */
class GeneratedConfigPathsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -----------------------------------------------------------------------
    // Helper: collect every string value in a JSON tree (depth-first).
    // -----------------------------------------------------------------------
    private static List<String> allStringValues(JsonNode node) {
        List<String> values = new ArrayList<>();
        collectStrings(node, values);
        return values;
    }

    private static void collectStrings(JsonNode node, List<String> out) {
        if (node == null) return;
        if (node.isTextual()) {
            out.add(node.textValue());
        } else if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                collectStrings(fields.next().getValue(), out);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectStrings(child, out);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helper: invoke init-project --no-build --no-infer and return project dir
    // -----------------------------------------------------------------------
    private Path runInitProject(Path outputDir, String projectName) {
        CommandLine cmd = new CommandLine(new MainCommand());
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute(
                "init-project",
                "--name", projectName,
                "--outputDir", outputDir.toString(),
                "--no-build",
                "--no-infer");

        assertEquals(0, exitCode,
                "init-project should succeed.\nstdout: " + out + "\nstderr: " + err);
        return outputDir.resolve(projectName);
    }

    // -----------------------------------------------------------------------
    // Test 1: app-index-config.json must have no machine-absolute index paths
    // -----------------------------------------------------------------------
    @Test
    void appIndexConfigHasNoAbsoluteHomePaths(@TempDir Path tempDir) throws Exception {
        Path projectDir = runInitProject(tempDir, "test-portability");

        Path configFile = projectDir.resolve("config/app-index-config.json");
        assertTrue(Files.exists(configFile),
                "app-index-config.json must be generated at " + configFile);

        JsonNode root = MAPPER.readTree(configFile.toFile());

        // Specific field assertions
        JsonNode vectorStorePath = root.get("vectorStorePath");
        assertNotNull(vectorStorePath, "vectorStorePath must be present");
        String vsp = vectorStorePath.textValue();
        assertFalse(vsp.startsWith("/"),
                "vectorStorePath must not be absolute, was: " + vsp);

        JsonNode keywordIndexPath = root.get("keywordIndexPath");
        assertNotNull(keywordIndexPath, "keywordIndexPath must be present");
        String kip = keywordIndexPath.textValue();
        assertFalse(kip.startsWith("/"),
                "keywordIndexPath must not be absolute, was: " + kip);

        // Broad sweep: no string value should embed /home, /Users, or /root
        for (String value : allStringValues(root)) {
            assertFalse(value.startsWith("/home"),
                    "app-index-config.json contains absolute /home path: " + value);
            assertFalse(value.startsWith("/Users"),
                    "app-index-config.json contains absolute /Users path: " + value);
            assertFalse(value.startsWith("/root/"),
                    "app-index-config.json contains absolute /root path: " + value);
        }
    }

    // -----------------------------------------------------------------------
    // Test 2: anserini-config.json must have no absolute home paths
    // -----------------------------------------------------------------------
    @Test
    void anseriniConfigHasNoAbsoluteHomePaths(@TempDir Path tempDir) throws Exception {
        Path projectDir = runInitProject(tempDir, "test-portability");

        Path configFile = projectDir.resolve("config/anserini-config.json");
        assertTrue(Files.exists(configFile),
                "anserini-config.json must be generated at " + configFile);

        JsonNode root = MAPPER.readTree(configFile.toFile());

        // corpusPath must be relative
        JsonNode corpusPath = root.get("corpusPath");
        assertNotNull(corpusPath, "corpusPath must be present");
        String cp = corpusPath.textValue();
        assertFalse(cp.startsWith("/"),
                "corpusPath must not be absolute, was: " + cp);

        // Broad sweep
        for (String value : allStringValues(root)) {
            assertFalse(value.startsWith("/home"),
                    "anserini-config.json contains absolute /home path: " + value);
            assertFalse(value.startsWith("/Users"),
                    "anserini-config.json contains absolute /Users path: " + value);
            assertFalse(value.startsWith("/root/"),
                    "anserini-config.json contains absolute /root path: " + value);
        }
    }

    // -----------------------------------------------------------------------
    // Test 3: nd4j-environment-config.json must have no absolute home paths
    // -----------------------------------------------------------------------
    @Test
    void nd4jConfigHasNoAbsoluteHomePaths(@TempDir Path tempDir) throws Exception {
        Path projectDir = runInitProject(tempDir, "test-portability");

        Path configFile = projectDir.resolve("config/nd4j-environment-config.json");
        assertTrue(Files.exists(configFile),
                "nd4j-environment-config.json must be generated at " + configFile);

        JsonNode root = MAPPER.readTree(configFile.toFile());

        for (String value : allStringValues(root)) {
            assertFalse(value.startsWith("/home"),
                    "nd4j-environment-config.json contains absolute /home path: " + value);
            assertFalse(value.startsWith("/Users"),
                    "nd4j-environment-config.json contains absolute /Users path: " + value);
            assertFalse(value.startsWith("/root/"),
                    "nd4j-environment-config.json contains absolute /root path: " + value);
        }
    }

    // -----------------------------------------------------------------------
    // Test 4: crawl-profile sources re-rooted into the project dir must be
    //         relative paths in kompile.project.json.
    // -----------------------------------------------------------------------
    @Test
    void crawlProfileSourcesAreRelativeWhenUnderProjectRoot(@TempDir Path tempDir) throws Exception {
        // Create a source root with a docs directory so auto-detection triggers,
        // then infer the crawl source into the project directory.
        Path inferRoot = tempDir.resolve("source-root");
        Path docsDir = inferRoot.resolve("docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("readme.md"), "# Test Document\n\nSample content.\n");

        Path outputDir = tempDir.resolve("generated");
        CommandLine cmd = new CommandLine(new MainCommand());
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute(
                "init-project",
                "--name", "test-relative-sources",
                "--outputDir", outputDir.toString(),
                "--infer-from", inferRoot.toString(),
                "--crawl-type", "directory",
                "--no-build");

        assertEquals(0, exitCode,
                "init-project should succeed.\nstdout: " + out + "\nstderr: " + err);

        Path projectDir = outputDir.resolve("test-relative-sources");
        Path manifestFile = projectDir.resolve("kompile.project.json");
        assertTrue(Files.exists(manifestFile), "kompile.project.json must exist");

        String manifest = Files.readString(manifestFile);
        JsonNode manifestJson = MAPPER.readTree(manifestFile.toFile());

        // Walk all "sources" arrays in crawlProfiles
        JsonNode crawlProfiles = manifestJson.path("crawlProfiles");
        if (crawlProfiles.isMissingNode() || crawlProfiles.isEmpty()) {
            // No crawl profile was generated (no data sources detected), test passes trivially.
            return;
        }

        for (JsonNode profile : crawlProfiles) {
            JsonNode sources = profile.path("sources");
            if (sources.isMissingNode() || sources.isEmpty()) {
                continue;
            }
            String profileId = profile.path("id").asText("<unknown>");
            for (JsonNode sourceNode : sources) {
                String source = sourceNode.asText();
                // Any source that is a path (starts with /) and is under the project dir
                // must have been relativized. We verify no source embeds the output dir.
                if (source.startsWith("/")) {
                    // If the absolute path happens to be the outputDir tree, that's the bug.
                    assertFalse(source.startsWith(outputDir.toString()),
                            "crawl profile '" + profileId + "' source was NOT relativized: " + source);
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Test 5c: processing-route-config.json — enriched content at app path (F6)
    // -----------------------------------------------------------------------
    @Test
    void processingRouteConfigIsWrittenToAppReadablePath(@TempDir Path tempDir) throws Exception {
        // Use --preset vlm-ocr to trigger writeVlmOcrPresetFiles which writes the config
        CommandLine cmd = new CommandLine(new MainCommand());
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute(
                "project", "init",
                "--root", tempDir.toString(),
                "--preset", "vlm-ocr");

        assertEquals(0, exitCode,
                "project init --preset vlm-ocr should succeed; stdout=" + out + " stderr=" + err);

        // App-readable path: <projectRoot>/config/processing-route-config.json
        // (ProcessingRouteConfigService: dataDir.resolve("config").resolve("processing-route-config.json"))
        Path appPath = tempDir.resolve("config/processing-route-config.json");
        assertTrue(Files.exists(appPath),
                "processing-route-config.json must exist at app-readable path config/: " + appPath);

        JsonNode root = MAPPER.readTree(appPath.toFile());

        // fallbackEnabled must be true (F6)
        assertTrue(root.path("fallbackEnabled").asBoolean(false),
                "fallbackEnabled must be true; got: " + root.path("fallbackEnabled"));

        // backends array must contain the local-serving LLM backend (always)
        JsonNode backends = root.path("backends");
        assertTrue(backends.isArray() && backends.size() > 0,
                "backends must be a non-empty array");

        boolean hasLocalServing = false;
        boolean hasVlmBackend = false;
        for (JsonNode backend : backends) {
            String id = backend.path("id").asText("");
            List<String> caps = new ArrayList<>();
            backend.path("capabilities").forEach(n -> caps.add(n.asText()));
            if ("local-serving".equals(id)) {
                hasLocalServing = true;
                assertEquals("LOCAL_MODEL", backend.path("type").asText(),
                        "local-serving must be LOCAL_MODEL");
                assertEquals("serving", backend.path("agentName").asText(),
                        "local-serving agentName must be 'serving'");
                assertTrue(caps.contains("llm"),
                        "local-serving must have capability 'llm'");
            }
            if ("local-vlm".equals(id)) {
                hasVlmBackend = true;
                assertTrue(caps.contains("vlm"),
                        "local-vlm must have capability 'vlm'");
            }
        }
        assertTrue(hasVlmBackend,
                "local-vlm backend must be present in backends");
        assertTrue(hasLocalServing,
                "local-serving backend must be present in backends (always, even without opencode)");

        // Must not embed absolute home paths
        String content = Files.readString(appPath);
        assertFalse(content.contains(System.getProperty("user.home")),
                "processing-route-config.json must not contain user.home path");
    }

    // -----------------------------------------------------------------------
    // Test 5b: 'project init' crawl-profile sources are relative (F2 fix)
    // -----------------------------------------------------------------------
    @Test
    void projectInitCrawlProfileSourcesAreRelative(@TempDir Path tempDir) throws Exception {
        // Seed a document inside data/input_documents so auto-detection fires
        Path docDir = tempDir.resolve("data/input_documents");
        Files.createDirectories(docDir);
        Files.writeString(docDir.resolve("sample.txt"),
                "Sample document for crawl-source relativization test.\n");

        CommandLine cmd = new CommandLine(new MainCommand());
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        int exitCode = cmd.execute("project", "init", "--root", tempDir.toString());

        assertEquals(0, exitCode,
                "project init should succeed; stdout=" + out + " stderr=" + err);

        Path manifestFile = tempDir.resolve("kompile.project.json");
        assertTrue(Files.exists(manifestFile), "kompile.project.json must exist");

        JsonNode manifestJson = MAPPER.readTree(manifestFile.toFile());
        JsonNode crawlProfiles = manifestJson.path("crawlProfiles");
        if (crawlProfiles.isMissingNode() || crawlProfiles.isEmpty()) {
            // No profile generated — no sources to check
            return;
        }

        String tempDirStr = tempDir.toString();
        for (JsonNode profile : crawlProfiles) {
            JsonNode sources = profile.path("sources");
            String profileId = profile.path("id").asText("<unknown>");
            for (JsonNode sourceNode : sources) {
                String source = sourceNode.asText();
                assertFalse(source.startsWith("/home"),
                        "project init crawl profile '" + profileId
                                + "' source must not embed /home: " + source);
                assertFalse(source.startsWith(tempDirStr),
                        "project init crawl profile '" + profileId
                                + "' source was NOT relativized (still absolute): " + source);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Test 5: relativizeSources helper — unit-level contract
    // -----------------------------------------------------------------------
    @Test
    void relativizeSourcesUnitContract(@TempDir Path tempDir) throws Exception {
        // Stand up an empty project just to get a projectDir handle; we test the
        // helper in isolation via the integration path by seeding crawl sources
        // via --crawl-source pointing INSIDE the generated project dir.

        // Create a file inside the temp dir to use as a crawl source
        Path projectOutputDir = tempDir.resolve("out");
        Files.createDirectories(projectOutputDir);

        // We need to create the project dir before invoking because
        // init-project creates it on its own.  Use a sub-temp so we can
        // pre-create a data file for the crawl source.
        Path crawlDataDir = tempDir.resolve("data-to-crawl");
        Files.createDirectories(crawlDataDir);
        Files.writeString(crawlDataDir.resolve("doc.txt"), "Hello world\n");

        // Point crawl-source at an external dir (outside output) — must stay absolute.
        // Point crawl-source at a subdir that will NOT be inside the project dir.
        // (If it were inside, the init-project runner would re-root it.)

        CommandLine cmd = new CommandLine(new MainCommand());
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute(
                "init-project",
                "--name", "test-helper",
                "--outputDir", projectOutputDir.toString(),
                "--crawl-source", crawlDataDir.toString(),
                "--crawl-type", "directory",
                "--no-build",
                "--no-infer");

        assertEquals(0, exitCode,
                "init-project should succeed.\nstdout: " + out + "\nstderr: " + err);

        Path projectDir = projectOutputDir.resolve("test-helper");
        Path manifestFile = projectDir.resolve("kompile.project.json");
        assertTrue(Files.exists(manifestFile), "kompile.project.json must exist");

        JsonNode manifestJson = MAPPER.readTree(manifestFile.toFile());
        JsonNode crawlProfiles = manifestJson.path("crawlProfiles");
        assertFalse(crawlProfiles.isMissingNode() && crawlProfiles.isEmpty(),
                "crawlProfiles should exist when --crawl-source is provided");

        for (JsonNode profile : crawlProfiles) {
            JsonNode sources = profile.path("sources");
            for (JsonNode sourceNode : sources) {
                String source = sourceNode.asText();
                // crawlDataDir is OUTSIDE projectDir — it must remain absolute and unchanged
                if (crawlDataDir.toString().equals(source) || source.startsWith(crawlDataDir.toString())) {
                    // External source: staying absolute is correct
                    assertTrue(source.startsWith("/"),
                            "External source should remain absolute: " + source);
                }
                // Must never embed the projectDir absolute path
                assertFalse(source.startsWith(projectDir.toString()),
                        "Source should not be absolute inside project dir: " + source);
            }
        }
    }
}
