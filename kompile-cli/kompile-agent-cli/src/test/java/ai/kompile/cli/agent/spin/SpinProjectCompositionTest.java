/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.cli.agent.spin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SpinProjectCompositionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    @Test void materializesCompleteInventoryInsideProjectAndPreservesUserModels() throws Exception {
        Path source = source();
        SpinDefinition definition = SpinDefinition.load(source);
        Path home = temp.resolve("home");
        var installed = SpinWorkspace.prepareForInstall(definition, home);
        Path workspace = installed.workspace();
        ObjectNode project = (ObjectNode) JSON.readTree(workspace.resolve("kompile.project.json").toFile());
        assertEquals(2, project.path("models").size());
        assertEquals(1, project.path("pipelines").size());
        for (var model : project.path("models")) {
            Path file = workspace.resolve(model.path("path").asText());
            assertTrue(file.startsWith(workspace));
            assertTrue(Files.isRegularFile(file));
        }
        assertEquals("chat", installed.defaultModel().id());
        assertTrue(installed.defaultModel().path().startsWith(workspace));
        assertFalse(Files.exists(workspace.resolve(".kompile/spin-models.json")));
        assertEquals("test-spin", project.path("metadata").path("spin.owner").asText());
        ((com.fasterxml.jackson.databind.node.ArrayNode) project.path("models")).addObject()
                .put("id", "user-model").put("path", "data/models/user.sdz");
        Files.writeString(workspace.resolve("kompile.project.json"), JSON.writeValueAsString(project));
        Files.createDirectories(workspace.resolve("config"));
        Files.writeString(workspace.resolve("config/preferences.json"), "user-owned");
        SpinWorkspace.prepareForInstall(definition, home);
        project = (ObjectNode) JSON.readTree(workspace.resolve("kompile.project.json").toFile());
        assertEquals(3, project.path("models").size());
        assertEquals("user-owned", Files.readString(workspace.resolve("config/preferences.json")));
    }

    @Test void upgradeAndRollbackRetainPreviousAssetBytes() throws Exception {
        Path source = source();
        Path home = temp.resolve("upgrade");
        var first = SpinWorkspace.prepareForInstall(SpinDefinition.load(source), home);
        Path oldModel = first.defaultModel().path();
        Files.writeString(source.resolve("project/data/models/chat.sdz"), "changed");
        var second = SpinWorkspace.prepareForInstall(SpinDefinition.load(source), home);
        assertNotEquals(oldModel, second.defaultModel().path());
        assertEquals("chat", Files.readString(oldModel));
        assertEquals("changed", Files.readString(second.defaultModel().path()));
        Files.writeString(source.resolve("project/data/models/chat.sdz"), "chat");
        var rollback = SpinWorkspace.prepareForInstall(SpinDefinition.load(source), home);
        assertEquals(oldModel, rollback.defaultModel().path());
    }

    @Test void rejectsUnownedProjectAndInventoryCollisions() throws Exception {
        Path source = source();
        Path home = temp.resolve("collision");
        Files.createDirectories(home.resolve("workspace"));
        Path manifest = home.resolve("workspace/kompile.project.json");
        Files.writeString(manifest, "{\"schemaVersion\":1}");
        assertThrows(IOException.class, () -> SpinWorkspace.prepareForInstall(SpinDefinition.load(source), home));
        Files.delete(manifest);
        var prepared = SpinWorkspace.prepareForInstall(SpinDefinition.load(source), home);
        ObjectNode project = (ObjectNode) JSON.readTree(manifest.toFile());
        ((ObjectNode) project.path("models").get(0)).remove("metadata");
        Files.writeString(manifest, JSON.writeValueAsString(project));
        assertThrows(IOException.class, () -> SpinWorkspace.prepareForInstall(SpinDefinition.load(source), home));
        assertTrue(Files.exists(prepared.defaultModel().path()));
    }

    @Test void packagesVerifiesAndInstallsV2WithExternalRuntimeDirectory() throws Exception {
        Path source = source();
        Path runtime = source.resolve("runtime");
        Path supplied = temp.resolve("supplied-runtime");
        Files.move(runtime, supplied);
        Path archive = temp.resolve("curated.kspin");
        SpinArchive.build(source, archive, supplied);
        Path home = temp.resolve("installed");
        var installed = SpinInstallation.install(archive, home);
        assertTrue(Files.isRegularFile(home.resolve("workspace/kompile.project.json")));
        assertEquals("test-spin", installed.definition().id());
    }

    @Test void rejectsMissingAssetsUnknownModelRefsAndUnimplementedAcquisition() throws Exception {
        Path source = source();
        Path manifest = source.resolve("project/kompile.project.json");
        String original = Files.readString(manifest);
        Files.writeString(manifest, original.replace("\"chat\",\"encoder\"", "\"missing\""));
        assertThrows(IOException.class, () -> SpinDefinition.load(source));
        Files.writeString(manifest, original.replace("\"bundle\"", "\"provision\""));
        assertThrows(IOException.class, () -> SpinDefinition.load(source));
        Files.writeString(manifest, original);
        Files.delete(source.resolve("project/data/models/encoder.onnx"));
        assertThrows(IOException.class, () -> SpinDefinition.load(source));
    }

    @Test void rejectsThinV2AndShellInsteadOfNativeExecutable() throws Exception {
        Path source = source();
        Path spin = source.resolve("spin.yaml");
        Files.writeString(spin, Files.readString(spin) + "runtime:\n  delivery: thin\n");
        assertThrows(IOException.class, () -> SpinDefinition.load(source));
        Files.writeString(spin, Files.readString(spin).replace("delivery: thin", "delivery: embedded"));
        Files.writeString(source.resolve("runtime/bin/kompile-chat"), "#!/bin/sh\nexit 0\n");
        assertThrows(IOException.class, () -> SpinDefinition.load(source));
    }

    @Test void rejectsUnsafePathAndStrictNestedDistribution() throws Exception {
        Path source = source();
        Path manifest = source.resolve("project/kompile.project.json");
        String original = Files.readString(manifest);
        Files.writeString(manifest, original.replace("data/models/chat.sdz", "data/../data/models/chat.sdz"));
        assertThrows(IOException.class, () -> SpinDefinition.load(source));
        Files.writeString(manifest, original.replace("\"materialization\":\"bundle\"", "\"typo\":true"));
        assertThrows(IOException.class, () -> SpinDefinition.load(source));
    }

    private Path source() throws Exception {
        Path source = Files.createTempDirectory(temp, "spin-");
        Files.writeString(source.resolve("spin.yaml"), "schemaVersion: 2\nmetadata:\n  id: test-spin\n  version: 1.0.0\nproject:\n  template: project\n");
        Files.writeString(source.resolve("agent.yaml"), "schemaVersion: '1'\nmetadata:\n  name: test-spin\nengine: cli-loop\nsystemPrompt: Be helpful.\nrole:\n  name: spin-helper\n");
        Path project = source.resolve("project");
        Files.createDirectories(project.resolve("data/models"));
        Files.createDirectories(project.resolve("data/pipelines"));
        Files.createDirectories(project.resolve("config"));
        Files.writeString(project.resolve("data/models/chat.sdz"), "chat");
        Files.writeString(project.resolve("data/models/tokenizer.json"), "{}");
        Files.writeString(project.resolve("data/models/encoder.onnx"), "encoder");
        Files.writeString(project.resolve("data/pipelines/encode.json"), "{}");
        Files.writeString(project.resolve("config/preferences.json"), "{}");
        Files.writeString(project.resolve("kompile.project.json"), """
                {"schemaVersion":1,"name":"Test spin",
                 "metadata":{"spin.defaultModel":"chat"},
                 "distribution":{"materialization":"bundle"},
                 "models":[{"id":"chat","path":"data/models/chat.sdz","metadata":{"tokenizer.path":"data/models/tokenizer.json"}},
                           {"id":"encoder","path":"data/models/encoder.onnx","role":"encoder"}],
                 "pipelines":[{"id":"encode","definitionPath":"data/pipelines/encode.json","modelRefs":["chat","encoder"]}]}
                """);
        Path bin = source.resolve("runtime/bin");
        Files.createDirectories(bin);
        for (String name : java.util.List.of("kompile", "kompile-agent", "kompile-chat", "kompile-model-serving")) {
            // Only tests the artifact contract; NOT a native startup/inference qualification.
            Files.write(bin.resolve(name), new byte[]{0x7f, 'E', 'L', 'F', 0});
        }
        return source;
    }
}
