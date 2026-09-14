package ai.kompile.cli.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeCliProcessTest {

    @Test
    void nullDeviceRedirectTargetsThePlatformNullDevice() {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder.Redirect redirect = NativeCliProcess.nullDeviceRedirect();

        assertNotNull(redirect.file(), "null-device redirect must carry a File target");
        assertEquals(windows ? "NUL" : "/dev/null", redirect.file().getPath());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void spawnedProcessSeesStdinAtEofImmediately() throws Exception {
        // read -t distinguishes a live pipe (exit > 128 on timeout) from EOF
        // (exit 1), so a regression fails in seconds instead of hanging the suite.
        Process process = NativeCliProcess.processBuilder(List.of("/bin/sh", "-c",
                "read -t 3 -r _ < /proc/self/fd/0; rc=$?; "
                        + "if [ $rc -gt 128 ]; then echo PIPED_OPEN; "
                        + "else echo EOF_IMMEDIATE; fi"), null)
                .start();

        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), "UTF-8");
        }
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "process must terminate");

        assertEquals("EOF_IMMEDIATE", output.strip(),
                "native CLI spawns must see stdin at EOF immediately");
    }
}
