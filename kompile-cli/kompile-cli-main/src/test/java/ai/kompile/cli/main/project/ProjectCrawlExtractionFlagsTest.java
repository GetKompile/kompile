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
package ai.kompile.cli.main.project;

import ai.kompile.cli.main.MainCommand;
import ai.kompile.project.KompileProjectCrawlProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies:
 * <ul>
 *   <li>{@code kompile project crawl} exposes {@code --graph-extraction},
 *       {@code --schema-preset}, and {@code --schema-mode} options.</li>
 *   <li>{@link ProjectCrawlCommand#buildCrawlArgs} translates those settings
 *       into the correct {@code --graph} / {@code --schema-preset} /
 *       {@code --graph-schema-mode} app-crawl args.</li>
 *   <li>{@link ProjectCrawlCommand#isPortInUse} correctly detects occupied ports.</li>
 *   <li>Auto-ingest profiles generated at init have graphExtraction=true and
 *       graphSchemaMode=LENIENT by default.</li>
 * </ul>
 */
class ProjectCrawlExtractionFlagsTest {

    // ── Option exposure ───────────────────────────────────────────────────────

    private static CommandSpec crawlCommandSpec() {
        CommandLine root = new CommandLine(new MainCommand());
        CommandLine project = root.getSubcommands().get("project");
        assertNotNull(project, "project subcommand should exist");
        CommandLine crawlGroup = project.getSubcommands().get("crawl");
        assertNotNull(crawlGroup, "project crawl subcommand should exist");
        return crawlGroup.getCommandSpec();
    }

    @Test
    void crawlCommand_exposesGraphExtractionOption() {
        CommandSpec spec = crawlCommandSpec();
        OptionSpec opt = spec.findOption("--graph-extraction");
        assertNotNull(opt, "kompile project crawl should expose --graph-extraction");
    }

    @Test
    void crawlCommand_exposesSchemaPresetOption() {
        CommandSpec spec = crawlCommandSpec();
        OptionSpec opt = spec.findOption("--schema-preset");
        assertNotNull(opt, "kompile project crawl should expose --schema-preset");
    }

    @Test
    void crawlCommand_exposesGraphSchemaModeOption() {
        CommandSpec spec = crawlCommandSpec();
        OptionSpec opt = spec.findOption("--schema-mode");
        assertNotNull(opt, "kompile project crawl should expose --schema-mode");
    }

    // ── buildCrawlArgs reflects overrides ─────────────────────────────────────

    @Test
    void buildCrawlArgs_includesGraphFlag_whenProfileHasGraphExtractionTrue() {
        KompileProjectCrawlProfile profile = new KompileProjectCrawlProfile();
        profile.setId("test");
        profile.setSources(List.of("data/docs"));
        profile.setGraphExtraction(true);

        List<String> args = ProjectCrawlCommand.buildCrawlArgs(profile, null, null, null);
        assertTrue(args.contains("--graph"),
                "buildCrawlArgs should include --graph when graphExtraction=true");
    }

    @Test
    void buildCrawlArgs_includesGraphFlag_whenProfileHasLegacyGraphExtractionFalse() {
        KompileProjectCrawlProfile profile = new KompileProjectCrawlProfile();
        profile.setId("test");
        profile.setSources(List.of("data/docs"));
        profile.setGraphExtraction(false);

        List<String> args = ProjectCrawlCommand.buildCrawlArgs(profile, null, null, null);
        assertTrue(args.contains("--graph"),
                "buildCrawlArgs should include --graph even when a stale profile says graphExtraction=false");
    }

    @Test
    void crawlCommand_doesNotExposeNoGraphOption() {
        CommandSpec spec = crawlCommandSpec();
        assertNull(spec.findOption("--no-graph"), "kompile project crawl should not expose --no-graph");
    }

    @Test
    void buildCrawlArgs_includesSchemaPreset_whenSet() {
        KompileProjectCrawlProfile profile = new KompileProjectCrawlProfile();
        profile.setId("test");
        profile.setSources(List.of("data/docs"));
        profile.setSchemaPresetId("my-preset");

        List<String> args = ProjectCrawlCommand.buildCrawlArgs(profile, null, null, null);
        int idx = args.indexOf("--schema-preset");
        assertTrue(idx >= 0 && idx + 1 < args.size() && "my-preset".equals(args.get(idx + 1)),
                "buildCrawlArgs should emit --schema-preset my-preset");
    }

    @Test
    void buildCrawlArgs_includesSchemaMode_whenSet() {
        KompileProjectCrawlProfile profile = new KompileProjectCrawlProfile();
        profile.setId("test");
        profile.setSources(List.of("data/docs"));
        profile.setGraphSchemaMode("STRICT");

        List<String> args = ProjectCrawlCommand.buildCrawlArgs(profile, null, null, null);
        int idx = args.indexOf("--graph-schema-mode");
        assertTrue(idx >= 0 && idx + 1 < args.size() && "STRICT".equals(args.get(idx + 1)),
                "buildCrawlArgs should emit --graph-schema-mode STRICT");
    }

    @Test
    void buildCrawlArgs_includesLanguagePreprocessingFlagsFromMetadata() {
        KompileProjectCrawlProfile profile = new KompileProjectCrawlProfile();
        profile.setId("test");
        profile.setSources(List.of("data/docs"));
        profile.getMetadata().put("preprocessing.languageDetection", "true");
        profile.getMetadata().put("preprocessing.translation", "true");
        profile.getMetadata().put("preprocessing.translationTarget", "en");
        profile.getMetadata().put("preprocessing.translationDualIndex", "true");

        List<String> args = ProjectCrawlCommand.buildCrawlArgs(profile, null, null, null);

        assertTrue(args.contains("--language-detection"), "language detection metadata should emit --language-detection");
        int idx = args.indexOf("--translate-to");
        assertTrue(idx >= 0 && idx + 1 < args.size() && "en".equals(args.get(idx + 1)),
                "translation metadata should emit --translate-to en");
        assertTrue(args.contains("--translation-dual-index"),
                "dual-index metadata should emit --translation-dual-index");
    }

    // ── isPortInUse ───────────────────────────────────────────────────────────

    @Test
    void isPortInUse_returnsFalse_whenPortIsFree() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        assertFalse(ProjectCrawlCommand.isPortInUse(port),
                "Newly-freed port should not appear in-use");
    }

    @Test
    void isPortInUse_returnsTrue_whenPortIsOccupied() throws Exception {
        try (ServerSocket s = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            assertTrue(ProjectCrawlCommand.isPortInUse(s.getLocalPort()),
                    "Port held by an open ServerSocket should appear in-use");
        }
    }

    // ── Init profile defaults ─────────────────────────────────────────────────

    @Test
    void initProfileHasGraphExtractionAndLenientSchemaByDefault(@TempDir Path tmp) {
        // Create a project with a standard text-doc directory
        Path docs = tmp.resolve("data/input_documents");
        try {
            Files.createDirectories(docs);
            Files.writeString(docs.resolve("sample.txt"), "Sample document for graph extraction test.");
        } catch (Exception e) {
            fail("Setup failed: " + e.getMessage());
        }

        int exitCode = execute("project", "init",
                "--root", tmp.toString(),
                "--name", "graph-default-test",
                "--backend", "local");
        assertEquals(0, exitCode, "project init should succeed");

        // Load the manifest and check the auto-ingest profile
        ai.kompile.project.KompileProjectStore store = new ai.kompile.project.KompileProjectStore();
        ai.kompile.project.KompileProjectManifest manifest = store.load(tmp);
        assertFalse(manifest.getCrawlProfiles().isEmpty(), "init should generate at least one crawl profile");

        KompileProjectCrawlProfile autoIngest = manifest.getCrawlProfiles().stream()
                .filter(p -> "auto-ingest".equals(p.getId()))
                .findFirst()
                .orElse(null);
        assertNotNull(autoIngest, "auto-ingest profile should be generated");
        assertTrue(autoIngest.isGraphExtraction(),
                "auto-ingest profile should have graphExtraction=true by default");
        assertEquals("LENIENT", autoIngest.getGraphSchemaMode(),
                "auto-ingest profile should have graphSchemaMode=LENIENT by default");
        assertEquals("true", autoIngest.getMetadata().get("preprocessing.languageDetection"),
                "auto-ingest profile should enable language detection preprocessing");
        assertEquals("false", autoIngest.getMetadata().get("preprocessing.translation"),
                "auto-ingest profile should leave translation disabled by default");
        assertFalse(autoIngest.isGraphAutoStart(),
                "graphAutoStart should remain false");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int execute(String... args) {
        CommandLine commandLine = new CommandLine(new MainCommand());
        commandLine.setOut(new PrintWriter(new StringWriter()));
        commandLine.setErr(new PrintWriter(new StringWriter()));
        return commandLine.execute(args);
    }
}
