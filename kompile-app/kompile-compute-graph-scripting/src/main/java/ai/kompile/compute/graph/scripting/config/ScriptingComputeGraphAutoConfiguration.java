package ai.kompile.compute.graph.scripting.config;

import ai.kompile.compute.graph.scripting.ExpressionNodeExecutor;
import ai.kompile.compute.graph.scripting.Python4jNodeExecutor;
import ai.kompile.compute.graph.scripting.PythonSubprocessNodeExecutor;
import ai.kompile.compute.graph.scripting.ScriptingNodeExecutor;
import ai.kompile.compute.graph.engine.NodeExecutor;
import org.graalvm.polyglot.Engine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the GraalVM polyglot scripting compute graph backend
 * and Python execution backends.
 *
 * <p>Python execution has two runtime options:
 * <ul>
 *   <li><b>Python4J</b> (primary) — embedded CPython via JavaCPP. Full native library
 *       support (numpy, pandas, etc.) but requires platform-specific native binaries.</li>
 *   <li><b>Subprocess</b> (fallback) — shells out to system {@code python3}. Works on
 *       any platform with Python 3 installed, but slower per-invocation.</li>
 * </ul>
 * Python4J is preferred when available; the subprocess executor registers only when
 * Python4J is not on the classpath.
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.graalvm.polyglot.Context")
public class ScriptingComputeGraphAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Engine graalvmPolyglotEngine() {
        return Engine.newBuilder()
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(ScriptingNodeExecutor.class)
    public ScriptingNodeExecutor scriptingNodeExecutor(Engine engine) {
        return new ScriptingNodeExecutor(engine);
    }

    @Bean
    @ConditionalOnMissingBean(ExpressionNodeExecutor.class)
    public ExpressionNodeExecutor expressionNodeExecutor() {
        return new ExpressionNodeExecutor();
    }

    @Bean
    @ConditionalOnMissingBean(Python4jNodeExecutor.class)
    @ConditionalOnClass(name = "org.nd4j.python4j.PythonExecutioner")
    public Python4jNodeExecutor python4jNodeExecutor() {
        return new Python4jNodeExecutor();
    }

    /**
     * Fallback: register the subprocess-based Python executor when Python4J
     * is not on the classpath (unsupported platform, missing native binaries, etc.).
     */
    @Bean
    @ConditionalOnMissingBean(Python4jNodeExecutor.class)
    public PythonSubprocessNodeExecutor pythonSubprocessNodeExecutor() {
        return new PythonSubprocessNodeExecutor();
    }
}
