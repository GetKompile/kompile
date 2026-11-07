package ai.kompile.cli.main.extensions.process;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class NativeProcessRpcCleanupTest {
    @Test
    @Timeout(15)
    void stalledRpcTimeoutKillsHostBeforeClosingBlockedReader() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Process host = new ProcessBuilder(java, "-Xmx32m", "-cp", classpath, StalledHost.class.getName()).start();
        var reader = Executors.newSingleThreadExecutor();
        var input = new BufferedReader(new InputStreamReader(host.getInputStream()));
        var output = new BufferedWriter(new OutputStreamWriter(host.getOutputStream()));
        try {
            assertEquals("READY", input.readLine());
            assertThrows(TimeoutException.class, () -> ProcessExtensionProductionNativeTest.request(
                    input, output, reader, 1, "initialize", new ObjectMapper().createObjectNode(), 100));
        } finally {
            ProcessExtensionProductionNativeTest.closeRpc(host, reader, input, output);
        }
        assertFalse(host.isAlive());
        assertTrue(reader.isTerminated());
    }

    public static final class StalledHost {
        public static void main(String[] args) throws Exception {
            System.out.println("READY");
            System.out.flush();
            new CountDownLatch(1).await();
        }
    }
}
