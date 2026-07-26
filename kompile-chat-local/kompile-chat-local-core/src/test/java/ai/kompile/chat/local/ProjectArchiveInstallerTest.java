package ai.kompile.chat.local;

import ai.kompile.project.archive.ProjectArchiveService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectArchiveInstallerTest {
    @TempDir
    Path temp;

    @Test
    void installsExactTargetModelAndCompletePortableKnowledge() throws Exception {
        Path project = Files.createDirectory(temp.resolve("project"));
        write(project, "kompile.project.json", """
                {
                  "schemaVersion": 1,
                  "projectId": "mobile-research",
                  "name": "Mobile Research",
                  "models": [
                    {
                      "id": "tensor-g3",
                      "path": "data/models/tensor-g3/model.sdz",
                      "metadata": {
                        "sdxTargetProfile": "android-arm64-nnapi-accelerator",
                        "quantization": "int8"
                      }
                    },
                    {
                      "id": "vulkan",
                      "path": "data/models/vulkan/model.sdz",
                      "metadata": {
                        "sdxTargetProfile": "android-arm64-vulkan"
                      }
                    }
                  ]
                }
                """);
        write(project, "data/models/tensor-g3/model.sdz", "g3-sdz");
        write(project, "data/models/vulkan/model.sdz", "vulkan-sdz");
        write(project, "data/graph/project.kgraph", "graph");
        write(project, "data/markdown/facts/acme.md", "# Acme\nSource facts");
        write(project, "data/markdown/index.json", "{\"version\":1}");
        write(project, "data/fact-sheets/project-fact-sheets.json", "{\"sheets\":[]}");
        write(project, "data/indexes/project-markdown-index.json", "{\"documents\":[]}");
        write(project, "data/documents/private.json", "not installed");

        Path archive = temp.resolve("mobile-research.kproject");
        new ProjectArchiveService().exportProject(project, archive);
        Path installs = temp.resolve("installs");

        ProjectArchiveInstaller.InstalledProject installed = ProjectArchiveInstaller.install(
                archive, "android-arm64-nnapi-accelerator", installs);

        assertEquals("mobile-research", installed.projectId());
        assertEquals("Mobile Research", installed.projectName());
        assertEquals("android-arm64-nnapi-accelerator", installed.targetProfile());
        assertTrue(installed.revision().matches("[0-9a-f]{64}"));
        assertTrue(installed.knowledgeRevision().matches("[0-9a-f]{64}"));
        assertEquals(installed.installationRoot(), installed.knowledgeRoot());
        assertEquals("g3-sdz", Files.readString(installed.modelPath()));
        assertEquals("graph", Files.readString(installed.graphPath()));
        assertEquals("# Acme\nSource facts",
                Files.readString(installed.sourcesRoot().resolve("facts/acme.md")));
        assertEquals("{\"version\":1}",
                Files.readString(installed.sourcesRoot().resolve("index.json")));
        assertEquals(2, installed.sourcePaths().size());
        assertEquals("{\"sheets\":[]}", Files.readString(installed.knowledgeRoot()
                .resolve("data/fact-sheets/project-fact-sheets.json")));
        assertEquals("{\"documents\":[]}", Files.readString(installed.knowledgeRoot()
                .resolve("data/indexes/project-markdown-index.json")));
        assertFalse(Files.exists(installed.installationRoot()
                .resolve("data/models/vulkan/model.sdz")));
        assertFalse(Files.exists(installed.installationRoot()
                .resolve("data/documents/private.json")));

        Path root = installed.installationRoot();
        installed.delete();
        assertFalse(Files.exists(root));
    }

    @Test
    void rejectsMissingTargetWithoutLeavingPartialInstallation() throws Exception {
        Path archive = projectArchive("""
                {
                  "schemaVersion": 1,
                  "projectId": "vulkan-only",
                  "name": "Vulkan Only",
                  "models": [{
                    "path": "data/models/model.sdz",
                    "metadata": {"sdxTargetProfile": "android-arm64-vulkan"}
                  }]
                }
                """, List.of("data/models/model.sdz"));
        Path installs = temp.resolve("installs");

        Exception failure = assertThrows(
                Exception.class,
                () -> ProjectArchiveInstaller.install(
                        archive, "android-arm64-nnapi-accelerator", installs));

        assertTrue(failure.getMessage().contains("available targets"));
        try (var entries = Files.list(installs)) {
            assertTrue(entries.findAny().isEmpty());
        }
    }

    @Test
    void acceptsOneLegacyUnprofiledSdzButRejectsAmbiguousModels() throws Exception {
        Path legacy = projectArchive("""
                {
                  "schemaVersion": 1,
                  "projectId": "legacy",
                  "name": "Legacy",
                  "models": [{"path": "data/models/model.sdz"}]
                }
                """, List.of("data/models/model.sdz"));
        ProjectArchiveInstaller.InstalledProject installed = ProjectArchiveInstaller.install(
                legacy, "android-arm64-vulkan", temp.resolve("legacy-installs"));
        assertEquals("model", Files.readString(installed.modelPath()));

        Path ambiguous = projectArchive("""
                {
                  "schemaVersion": 1,
                  "projectId": "ambiguous",
                  "name": "Ambiguous",
                  "models": [
                    {"path": "data/models/one.sdz"},
                    {"path": "data/models/two.sdz"}
                  ]
                }
                """, List.of("data/models/one.sdz", "data/models/two.sdz"));
        Exception failure = assertThrows(
                Exception.class,
                () -> ProjectArchiveInstaller.install(
                        ambiguous, "android-arm64-vulkan", temp.resolve("ambiguous-installs")));
        assertTrue(failure.getMessage().contains("multiple unprofiled .sdz models"));
    }

    private Path projectArchive(String descriptor, List<String> modelPaths) throws Exception {
        Path project = Files.createDirectory(temp.resolve("source-" + System.nanoTime()));
        write(project, "kompile.project.json", descriptor);
        write(project, "data/graph/project.kgraph", "graph");
        write(project, "data/markdown/source.md", "source");
        for (String modelPath : modelPaths) {
            write(project, modelPath, "model");
        }
        Path archive = temp.resolve("archive-" + System.nanoTime() + ".kproject");
        new ProjectArchiveService().exportProject(project, archive);
        return archive;
    }

    private static void write(Path root, String relative, String value) throws Exception {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, value);
    }
}
