package ai.kompile.compute.graph.scripting.client.config;

import ai.kompile.compute.graph.scripting.client.ScriptingWorkerNodeExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.context.annotation.Bean;

/**
 * Registers the external scripting worker when the in-process scripting implementation is absent.
 *
 * <p>The classpath is the component boundary: JVM applications that deliberately include the
 * full scripting runtime keep its embedded executors, while native-image applications include
 * this lightweight client and invoke the separately distributed worker JAR.</p>
 */
@AutoConfiguration
@ConditionalOnMissingClass("ai.kompile.compute.graph.scripting.ScriptingNodeExecutor")
public class ScriptingWorkerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ScriptingWorkerNodeExecutor.class)
    public ScriptingWorkerNodeExecutor scriptingWorkerNodeExecutor() {
        return new ScriptingWorkerNodeExecutor();
    }
}
