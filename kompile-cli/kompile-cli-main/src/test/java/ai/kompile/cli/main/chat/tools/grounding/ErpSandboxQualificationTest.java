package ai.kompile.cli.main.chat.tools.grounding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Never enabled by normal tests. Uses the operator's existing named ERP credential. */
@EnabledIfSystemProperty(named = "erp.qualification.live", matches = "true")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class ErpSandboxQualificationTest {
    @TempDir Path workspace;

    @Test
    void qualifiesExplicitlyConfiguredSandbox() throws Exception {
        Path receiptPath = Path.of("target", "erp-qualification", "qualification-" + UUID.randomUUID() + ".json");
        byte[] config = null;
        try {
            String path = System.getProperty("erp.qualification.config", "");
            if (!path.isBlank()) {
                try (var input = Files.newInputStream(Path.of(path))) {
                    config = input.readNBytes(65_537);
                }
            }
        } catch (Exception ignored) {
            // Let the runner emit a sanitized configuration failure instead of the raw path/cause.
        }
        var request = ErpSandboxQualificationRunner.parse(config);
        var receipt = ErpSandboxQualificationRunner.run(request, workspace, receiptPath, true);
        assertEquals("PASS_BOUNDED_READ", receipt.path("status").asText(),
                "ERP qualification failed; inspect the sanitized receipt under target/erp-qualification");
    }
}
