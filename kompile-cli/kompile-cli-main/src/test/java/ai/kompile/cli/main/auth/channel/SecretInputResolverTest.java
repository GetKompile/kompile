package ai.kompile.cli.main.auth.channel;

import ai.kompile.channel.api.ChannelProviderDescriptor;
import ai.kompile.channel.api.ChannelProviderDescriptor.Field;
import ai.kompile.channel.api.ChannelProviderDescriptor.FieldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretInputResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesEnvironmentFileAndStdinWithoutLiteralArgvValues() throws Exception {
        Path secretFile = tempDir.resolve("app-token");
        Files.writeString(secretFile, "from-file\n");
        SecretInputResolver resolver = new SecretInputResolver(
                new ByteArrayInputStream("from-stdin\n".getBytes(StandardCharsets.UTF_8)),
                name -> "BOT_ENV".equals(name) ? "from-env" : null,
                null);

        Map<String, String> resolved = resolver.resolve(
                provider(),
                Map.of("botToken", "BOT_ENV"),
                Map.of("appToken", secretFile),
                List.of("verifyToken"),
                false);

        assertEquals(Map.of(
                "botToken", "from-env",
                "appToken", "from-file",
                "verifyToken", "from-stdin"), resolved);
    }

    @Test
    void requiredPromptIsMaskedAndUnknownFieldsAreRejected() throws Exception {
        SecretInputResolver resolver = new SecretInputResolver(
                InputStreamNull.INSTANCE,
                name -> null,
                prompt -> "prompted".toCharArray());

        assertEquals("prompted", resolver.resolve(
                provider(), Map.of(), Map.of(), List.of(), true).get("botToken"));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                provider(), Map.of("typo", "ENV"), Map.of(), List.of(), false));
    }

    private static ChannelProviderDescriptor provider() {
        return new ChannelProviderDescriptor(
                "test", "Test", "test", Set.of(), List.of(), List.of(
                        new Field("botToken", "Bot token", FieldType.STRING, true, null, "", "BOT_ENV"),
                        new Field("appToken", "App token", FieldType.STRING, false, null, "", "APP_ENV"),
                        new Field("verifyToken", "Verify token", FieldType.STRING, false, null, "", "VERIFY_ENV")));
    }

    private static final class InputStreamNull extends java.io.InputStream {
        private static final InputStreamNull INSTANCE = new InputStreamNull();

        @Override
        public int read() {
            return -1;
        }
    }
}
