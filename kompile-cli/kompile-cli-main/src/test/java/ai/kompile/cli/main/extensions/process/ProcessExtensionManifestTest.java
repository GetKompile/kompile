package ai.kompile.cli.main.extensions.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProcessExtensionManifestTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path temporary;

    private ObjectNode commandManifest() throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"schemaVersion":1,"id":"sample","version":"1.0","apiVersion":1,
                 "commands":{"sample":{"kind":"jar","path":"lib/main.jar","args":[]}}}
                """);
    }

    private ObjectNode toolManifest() throws Exception {
        ObjectNode node = commandManifest();
        node.set("provides", mapper.readTree("[\"feature.local\"]"));
        node.set("requires", mapper.readTree("[\"host.base\"]"));
        node.set("mcp", mapper.readTree("{\"kind\":\"executable\",\"path\":\"bin/server\"}"));
        node.set("tools", mapper.readTree("""
                [{"name":"do_work","description":"Local work","inputSchema":{"type":"object","properties":{}},
                  "requires":["feature.local"],"actionRequirements":{"run":["host.base"]},
                  "conditionalCapabilities":{"optional":["feature.extra"]}}]
                """));
        return node;
    }

    @Test
    void strictTreeParsingAndDescriptorPreserveOptionalRequirementsAndNamespace() throws Exception {
        var manifest = ProcessExtensionManifest.parse(toolManifest());
        assertEquals("sample", manifest.id());
        assertEquals("1.0", manifest.version());
        assertEquals(1, manifest.apiVersion());
        assertEquals(60, manifest.timeoutSeconds());
        assertEquals(Set.of("sample"), manifest.commands().keySet());
        assertTrue(manifest.mcp().isPresent());
        assertEquals(Set.of("feature.local"), manifest.provides());
        assertEquals(Set.of("host.base"), manifest.requires());
        var descriptor = manifest.descriptor();
        assertEquals(Set.of("sample"), descriptor.commands());
        assertEquals("mcp__extension_sample__do_work", descriptor.tools().get(0).id());
        assertEquals(Set.of("feature.local"), descriptor.tools().get(0).requires());
        assertEquals(Map.of("run", Set.of("host.base")), descriptor.tools().get(0).actionRequirements());
        assertEquals(Map.of("optional", Set.of("feature.extra")), descriptor.tools().get(0).conditionalCapabilities());
        assertNotEquals(manifest.toolId("a-b"), manifest.toolId("a_b"));
        assertThrows(IllegalArgumentException.class, () -> manifest.toolId("a.b"));
    }

    @Test
    void allExposedStateIsDefensivelyImmutable() throws Exception {
        ObjectNode node = toolManifest();
        var manifest = ProcessExtensionManifest.parse(node);
        ((ObjectNode) node.get("tools").get(0).get("inputSchema")).put("changed", true);
        var tool = manifest.tools().get(0);
        assertFalse(tool.inputSchema().has("changed"));
        ((ObjectNode) tool.inputSchema()).put("changed", true);
        assertFalse(tool.inputSchema().has("changed"));
        assertThrows(UnsupportedOperationException.class, () -> manifest.provides().add("other"));
        assertThrows(UnsupportedOperationException.class, () -> manifest.commands().clear());
        assertThrows(UnsupportedOperationException.class, () -> manifest.tools().clear());
        assertThrows(UnsupportedOperationException.class, () -> tool.requires().clear());
        assertThrows(UnsupportedOperationException.class, () -> tool.actionRequirements().clear());
        assertThrows(UnsupportedOperationException.class, () -> tool.actionRequirements().get("run").clear());
        assertThrows(UnsupportedOperationException.class, () -> tool.conditionalCapabilities().clear());
        var args = new ArrayList<>(List.of("literal"));
        var launch = new ProcessExtensionManifest.Launch("jar", "main.jar", args);
        args.clear();
        assertEquals(List.of("literal"), launch.args());
        assertThrows(UnsupportedOperationException.class, () -> launch.args().clear());
        ObjectNode nonJson = toolManifest();
        ((ObjectNode) nonJson.get("tools").get(0).get("inputSchema")).putPOJO("mutable", args);
        assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(nonJson));
    }

    @Test
    void rejectsUnknownFieldsAndUnsupportedOrIncorrectlyTypedMetadata() throws Exception {
        for (String field : List.of("schemaVersion", "apiVersion")) {
            for (String value : List.of("0", "2", "1.0", "\"1\"", "null", "true")) {
                ObjectNode node = commandManifest();
                node.set(field, mapper.readTree(value));
                assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(node), field + value);
            }
        }
        for (String value : List.of("0", "601", "1.5", "\"60\"", "null")) {
            ObjectNode node = commandManifest();
            node.set("timeoutSeconds", mapper.readTree(value));
            assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(node));
        }
        ObjectNode node = commandManifest();
        node.put("typo", true);
        assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(node));
        for (String field : List.of("provides", "requires", "commands", "tools", "mcp")) {
            ObjectNode bad = commandManifest();
            bad.putNull(field);
            assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(bad), field);
        }
        for (String value : List.of("null", "[]", "\"manifest\"", "{}")) {
            assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(mapper.readTree(value)));
        }
        ObjectNode launchTypo = commandManifest();
        ((ObjectNode) launchTypo.get("commands").get("sample")).put("shell", true);
        assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(launchTypo));
        ObjectNode toolTypo = toolManifest();
        ((ObjectNode) toolTypo.get("tools").get(0)).put("permission", "all");
        assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(toolTypo));
    }

    @Test
    void rejectsUnsafePathsNamesDuplicateIdentifiersAndInvalidArgumentValues() throws Exception {
        for (String path : List.of("/tmp/run", "../run", "a/../run", "./run", "a/./run", "a//run",
                "a/", "a\\run", "C:/run", "run\0bad", "")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ProcessExtensionManifest.Launch("executable", path, List.of()), path);
        }
        for (String id : List.of("Upper", "../escape", "a_b", "a".repeat(64), "")) {
            ObjectNode node = commandManifest();
            node.put("id", id);
            assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(node));
        }
        for (String version : List.of(".", "..", "../1", "v/1", "v".repeat(65), "")) {
            ObjectNode node = commandManifest();
            node.put("version", version);
            assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(node));
        }
        for (String toolName : List.of("a.b", "a b", "9tool", "tool__with.dot")) {
            ObjectNode node = toolManifest();
            ((ObjectNode) node.get("tools").get(0)).put("name", toolName);
            assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(node));
        }
        for (String field : List.of("provides", "requires")) {
            ObjectNode node = commandManifest();
            node.set(field, mapper.readTree("[\"same\",\"same\"]"));
            assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(node));
        }
        ObjectNode duplicates = toolManifest();
        ((com.fasterxml.jackson.databind.node.ArrayNode) duplicates.get("tools")).add(duplicates.get("tools").get(0).deepCopy());
        assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(duplicates));
        for (String args : List.of("[null]", "[1]", "[true]", "\"string\"", "[\"\\u0000\"]")) {
            ObjectNode node = commandManifest();
            ((ObjectNode) node.get("commands").get("sample")).set("args", mapper.readTree(args));
            assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(node));
        }
    }

    @Test
    void requiresToolsAndMcpTogetherAndObjectInputSchema() throws Exception {
        ObjectNode empty = commandManifest();
        empty.remove("commands");
        assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(empty));
        ObjectNode noMcp = toolManifest();
        noMcp.remove("mcp");
        assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(noMcp));
        ObjectNode noTools = toolManifest();
        noTools.remove("tools");
        assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(noTools));
        for (String schema : List.of("null", "[]", "true", "\"object\"")) {
            ObjectNode node = toolManifest();
            ((ObjectNode) node.get("tools").get(0)).set("inputSchema", mapper.readTree(schema));
            assertThrows(IllegalArgumentException.class, () -> ProcessExtensionManifest.parse(node));
        }
    }

    @Test
    void launchBuildsLiteralArgvAndRejectsNonRegularUnreadableOrNonExecutableTargets() throws Exception {
        Path jar = Files.writeString(temporary.resolve("main.jar"), "trusted jar placeholder");
        var jarLaunch = new ProcessExtensionManifest.Launch("jar", "main.jar", List.of("a b", "$(bad)", "", "--flag=*"));
        assertEquals(List.of("/explicit/java", "-jar", jar.toString(), "a b", "$(bad)", "", "--flag=*"),
                jarLaunch.command(temporary, "/explicit/java"));
        assertThrows(IllegalArgumentException.class, () -> jarLaunch.command(temporary, ""));
        assertThrows(IOException.class, () -> new ProcessExtensionManifest.Launch("jar", "missing.jar", List.of()).command(temporary, "java"));
        Files.createDirectory(temporary.resolve("dir.jar"));
        assertThrows(IOException.class, () -> new ProcessExtensionManifest.Launch("jar", "dir.jar", List.of()).command(temporary, "java"));
        Files.writeString(temporary.resolve("notjar"), "data");
        assertThrows(IOException.class, () -> new ProcessExtensionManifest.Launch("jar", "notjar", List.of()).command(temporary, "java"));
        if (Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
            Path exe = Files.writeString(temporary.resolve("run"), "not executed");
            Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("rw-------"));
            var executable = new ProcessExtensionManifest.Launch("executable", "run", List.of("literal arg"));
            assertThrows(IOException.class, () -> executable.command(temporary, null));
            Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("rwx------"));
            assertEquals(List.of(exe.toString(), "literal arg"), executable.command(temporary, null));
        }
    }

    @Test
    void launchRejectsSymlinkFileDirectoryAndPackageComponents() throws Exception {
        Files.writeString(temporary.resolve("real.jar"), "data");
        Files.createSymbolicLink(temporary.resolve("link.jar"), Path.of("real.jar"));
        assertThrows(IOException.class, () -> new ProcessExtensionManifest.Launch("jar", "link.jar", List.of()).command(temporary, "java"));
        Files.createDirectory(temporary.resolve("real"));
        Files.writeString(temporary.resolve("real/main.jar"), "data");
        Files.createSymbolicLink(temporary.resolve("linked"), Path.of("real"));
        assertThrows(IOException.class, () -> new ProcessExtensionManifest.Launch("jar", "linked/main.jar", List.of()).command(temporary, "java"));
        assertThrows(IOException.class, () -> new ProcessExtensionManifest.Launch("jar", "main.jar", List.of()).command(temporary.resolve("linked"), "java"));
    }
}
