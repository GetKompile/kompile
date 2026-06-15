package ai.kompile.cli.main.chat.enforcer;

import ai.kompile.cli.main.chat.harness.HarnessConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnforcerRuntimePolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsPolicyFileForChildProcesses() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HarnessConfig config = new HarnessConfig();
        config.setJudgeMode("remote");
        config.setJudgeProvider("openai");
        config.setJudgeModel("gpt-test");
        config.setJudgeApiKey("sk-test");

        EnforcerPolicy policy = new EnforcerPolicy("Never call bash.", 3, true);
        EnforcerRuntimePolicy runtime = EnforcerRuntimePolicy.create(
                tempDir, "enforcer-test", policy, config, mapper);

        assertTrue(runtime.getPolicyFile().toFile().exists());
        assertTrue(runtime.getContextFile().toFile().exists());
        assertEquals("true", runtime.toEnvironment().get(EnforcerRuntimePolicy.ENV_ACTIVE));
        assertEquals(runtime.getContextFile().toAbsolutePath().toString(),
                runtime.toEnvironment().get(EnforcerRuntimePolicy.ENV_CONTEXT_FILE));

        EnforcerRuntimePolicy loaded = EnforcerRuntimePolicy.load(runtime.getPolicyFile(), mapper);
        assertNotNull(loaded);
        assertEquals("enforcer-test", loaded.getSessionId());
        assertEquals(runtime.getContextFile().toAbsolutePath().normalize(), loaded.getContextFile());
        assertEquals("Never call bash.", loaded.getPolicy().getRules());
        assertEquals(3, loaded.getPolicy().getMaxCorrections());
        assertEquals("remote", loaded.getHarnessConfig().getJudgeMode());
        assertEquals("openai", loaded.getHarnessConfig().getJudgeProvider());
        assertEquals("gpt-test", loaded.getHarnessConfig().getJudgeModel());
        assertEquals("sk-test", loaded.getHarnessConfig().getJudgeApiKey());
    }
}
