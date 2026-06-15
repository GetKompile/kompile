package ai.kompile.cli.main.chat.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ChatConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void passthroughConfigIsValidWithoutProviderCredentialsAndDoesNotPersistComputedGetters() throws Exception {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            ChatConfig config = new ChatConfig(null, null, null, null);
            config.setChatMode("passthrough");
            config.setPassthroughAgent("opencode");

            assertTrue(config.isValid());
            config.save();

            String json = Files.readString(tempDir.resolve(".kompile").resolve("chat-config.json"));
            assertFalse(json.contains("\"valid\""));
            assertFalse(json.contains("\"kompileServer\""));
            assertFalse(json.contains("\"anthropicFormat\""));
            assertFalse(json.contains("\"openAiCompatible\""));

            ChatConfig loaded = ChatConfig.loadOrFromEnv();
            assertNotNull(loaded);
            assertEquals("passthrough", loaded.getChatMode());
            assertEquals("opencode", loaded.getPassthroughAgent());
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }
}
