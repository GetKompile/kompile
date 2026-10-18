package ai.kompile.cli.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ProductChatPolicyTest {
    @TempDir Path root;

    private Path manifest() throws Exception {
        Files.writeString(root.resolve("role.md"), "---\nname: elthoria-gm\ntools: read\n---\nFrozen GM prompt");
        Files.writeString(root.resolve("mcp.json"), "{\"mcpServers\":{\"elthoria\":{\"command\":\"installed-elthoria\"}}}");
        Path manifest = root.resolve("product.json");
        Files.writeString(manifest, """
                {"schemaVersion":"kompile.product-chat-policy.v1","id":"elthoria-play","title":"Elthoria",
                "role":"elthoria-gm","roleFile":"role.md","mcpConfigFile":"mcp.json","assetsDirectory":"assets",
                "nativeTools":["read"],"mcpServers":["elthoria"],
                "readCapabilities":{"connection":"mcp__elthoria__get_play_connection","dashboard":"mcp__elthoria__get_play_dashboard"},
                "idleTimeoutSeconds":1800,"turnTimeoutSeconds":300}
                """);
        return manifest;
    }

    @Test void snapshotsInstalledIdentityAndIncludesBothSnapshotsInDigest() throws Exception {
        Path file = manifest();
        ProductChatPolicy first = ProductChatPolicy.load(file, root);
        assertEquals(root.toRealPath(), first.workspace());
        assertEquals("elthoria-gm", first.role());
        assertEquals(Set.of("read"), first.nativeTools());
        assertFalse(Files.exists(first.assetsDirectory())); // CLI-only installation is valid.
        assertEquals(64, first.digest().length());
        Files.writeString(root.resolve("role.md"), "Changed prompt");
        ProductChatPolicy second = ProductChatPolicy.load(file, root);
        assertTrue(first.roleSnapshot().contains("Frozen GM"));
        assertNotEquals(first.digest(), second.digest());
        Files.writeString(root.resolve("mcp.json"), "{\"mcpServers\":{}}");
        assertNotEquals(second.digest(), ProductChatPolicy.load(file, root).digest());
        assertThrows(UnsupportedOperationException.class, () -> first.nativeTools().add("bash"));
        assertThrows(UnsupportedOperationException.class, () -> first.readCapabilities().put("escape", "bash"));
    }

    @Test void rejectsUnknownDuplicateAndIllTypedFieldsAndNamespaceEscapes() throws Exception {
        Path file = manifest();
        String valid = Files.readString(file);
        for (String invalid : new String[] {
                valid.replace("\"id\":", "\"extra\":true,\"id\":"),
                valid.replace("\"id\":", "\"id\":\"one\",\"id\":"),
                valid.replace("1800", "1800.5"), valid.replace("300", "0"),
                valid.replace("[\"read\"]", "[\"*\"]"),
                valid.replace("[\"read\"]", "[\"read\",\"read\"]"),
                valid.replace("mcp__elthoria__get_play_connection", "mcp__foreign__get_play_connection"),
                valid + " {}"}) {
            Files.writeString(file, invalid);
            assertThrows(Exception.class, () -> ProductChatPolicy.load(file, root));
        }
    }

    @Test void rejectsContainmentAndSymlinkEscapes() throws Exception {
        Path file = manifest();
        String valid = Files.readString(file);
        Files.writeString(file, valid.replace("\"role.md\"", "\"../role.md\""));
        assertThrows(IllegalArgumentException.class, () -> ProductChatPolicy.load(file, root));
        Files.writeString(file, valid);
        Files.createSymbolicLink(root.resolve("linked.md"), root.resolve("role.md"));
        Files.writeString(file, valid.replace("\"role.md\"", "\"linked.md\""));
        assertThrows(IllegalArgumentException.class, () -> ProductChatPolicy.load(file, root));
    }

    @Test void nativeActionPolicyIsOptionalStrictAndPartOfTheFrozenWorkspaceDigest() throws Exception {
        Path file = manifest();
        String legacy = Files.readString(file);
        ProductChatPolicy old = ProductChatPolicy.load(file, root);
        assertNull(old.nativeActionPolicy());
        Files.writeString(file, legacy.replace("\"id\":", "\"nativeActionPolicy\":\"elthoria.content.v1\",\"id\":"));
        ProductChatPolicy content = ProductChatPolicy.load(file, root);
        assertEquals(ProductChatPolicy.ELTHORIA_CONTENT, content.nativeActionPolicy());
        assertNotEquals(old.digest(), content.digest());
        Path other = Files.createDirectory(root.resolve("other-workspace"));
        assertNotEquals(content.digest(), ProductChatPolicy.load(file, other).digest());
        for (String value : new String[] {"null", "true", "7", "\"\"", "\"admin\"", "\"elthoria.content.v2\""}) {
            Files.writeString(file, legacy.replace("\"id\":", "\"nativeActionPolicy\":" + value + ",\"id\":"));
            assertThrows(Exception.class, () -> ProductChatPolicy.load(file, root));
        }
        assertEquals(ProductChatPolicy.ELTHORIA_CONTENT, content.nativeActionPolicy());
    }

    @Test void configuredPropertyIsExplicitAndNotBrowserSessionData() throws Exception {
        Path file = manifest();
        String prior = System.getProperty(ProductChatPolicy.PROPERTY);
        try {
            System.setProperty(ProductChatPolicy.PROPERTY, file.toString());
            assertEquals(file, ProductChatPolicy.configuredPath().orElseThrow());
            assertEquals(file.toRealPath(), ProductChatPolicy.configured(root).orElseThrow().manifestPath());
        } finally {
            if (prior == null) System.clearProperty(ProductChatPolicy.PROPERTY);
            else System.setProperty(ProductChatPolicy.PROPERTY, prior);
        }
    }
}
