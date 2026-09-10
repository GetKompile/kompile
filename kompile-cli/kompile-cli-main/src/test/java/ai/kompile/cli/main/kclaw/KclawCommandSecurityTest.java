package ai.kompile.cli.main.kclaw;

import ai.kompile.channel.api.ChannelControlHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class KclawCommandSecurityTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path tempDir;

    @Test
    void attachesManagedBearerAndMutationProof() throws Exception {
        String originalHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toString());
        try {
            Path token = tempDir.resolve(".kompile/config/channel-admin.token");
            Files.createDirectories(token.getParent());
            Files.writeString(token, TOKEN);
            KclawCommand.ListCmd command = new KclawCommand.ListCmd();

            var request = command.request("http://127.0.0.1:8080/api/kclaw/tasks", true).GET().build();

            assertEquals(TOKEN, request.headers().firstValue(ChannelControlHeaders.TOKEN_HEADER).orElseThrow());
            assertEquals("1", request.headers().firstValue(ChannelControlHeaders.REQUEST_HEADER).orElseThrow());
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void rejectsDeceptiveLoopbackHostname() {
        KclawCommand.ListCmd command = new KclawCommand.ListCmd();
        assertThrows(IllegalStateException.class,
                () -> command.request("http://127.attacker.example/api/kclaw/tasks", false));
    }
}
