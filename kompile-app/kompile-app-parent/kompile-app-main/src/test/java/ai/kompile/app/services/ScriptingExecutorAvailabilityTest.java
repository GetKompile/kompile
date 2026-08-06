package ai.kompile.app.services;

import ai.kompile.compute.graph.engine.NodeExecutor;
import ai.kompile.compute.graph.model.NodeExecutionType;
import ai.kompile.compute.graph.scripting.client.ScriptingWorkerNodeExecutor;
import ai.kompile.compute.graph.scripting.client.config.ScriptingWorkerAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScriptingExecutorAvailabilityTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ScriptingWorkerAutoConfiguration.class));

    @Test
    void unifiedServerClasspathProvidesIsolatedJavaScriptAndPythonExecutor() {
        contextRunner.run(context -> {
            Map<String, NodeExecutor> executors = context.getBeansOfType(NodeExecutor.class);

            assertThat(executors.values())
                    .singleElement()
                    .isInstanceOf(ScriptingWorkerNodeExecutor.class)
                    .satisfies(executor -> assertThat(executor.supportedTypes())
                            .containsExactlyInAnyOrder(NodeExecutionType.JAVASCRIPT, NodeExecutionType.PYTHON));
        });
    }
}
