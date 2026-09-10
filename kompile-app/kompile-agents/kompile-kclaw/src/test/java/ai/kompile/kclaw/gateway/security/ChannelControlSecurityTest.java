package ai.kompile.kclaw.gateway.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelControlSecurityTest {

    @TempDir
    Path tempDir;

    @Test
    void atomicallyCreatesAndReusesARestrictedLocalToken() throws Exception {
        ChannelControlSecurity first = new ChannelControlSecurity("", tempDir.toString());
        first.initialize();
        String token = Files.readString(first.tokenPath()).trim();

        ChannelControlSecurity second = new ChannelControlSecurity("", tempDir.toString());
        second.initialize();

        assertTrue(first.matches(token));
        assertTrue(second.matches(token));
        assertFalse(second.matches("wrong"));
        assertTrue(Files.readString(tempDir.resolve(".gitignore"))
                .contains("config/channel-admin.token"));
        try (var files = Files.list(first.tokenPath().getParent())) {
            assertEquals(1, files
                    .filter(path -> path.getFileName().toString().equals("channel-admin.token"))
                    .count());
        }
    }

    @Test
    void rejectsWeakConfiguredTokens() {
        ChannelControlSecurity security = new ChannelControlSecurity("short", tempDir.toString());
        assertThrows(IllegalStateException.class, security::initialize);
    }

}
