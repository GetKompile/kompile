package ai.kompile.pipelines.framework.api;

import java.util.ServiceLoader; // For Javadoc reference

/**
 * A factory responsible for creating instances of {@link PipelineStepRunner}.
 * <p>
 * Implementations of this factory typically correspond to a specific type of pipeline step
 * (e.g., a {@code PythonRunnerFactory} for Python steps, a {@code TensorFlowRunnerFactory}
 * for TensorFlow model steps). Each factory knows how to create an instance of its
 * associated runner.
 * <p>
 * The pipeline runtime (specifically, the {@link PipelineExecutor} implementations)
 * uses these factories to instantiate the appropriate runners based on the
 * {@link StepConfig#runnerClassName()} information.
 * <p>
 * Factories are typically discovered using Java's {@link ServiceLoader} mechanism.
 * To make a factory discoverable, its fully qualified class name should be listed in a file named
 * {@code META-INF/services/ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory}
 * within the JAR that provides the factory implementation.
 */
public interface PipelineStepRunnerFactory {

    /**
     * Returns the fully qualified class name of the {@link PipelineStepRunner}
     * that this factory creates. This name is used by the pipeline runtime to
     * match a {@link StepConfig#runnerClassName()} to the correct factory.
     * <p>
     * The returned string must exactly match the class name that will be specified
     * in the pipeline configurations.
     *
     * @return The fully qualified class name of the {@link PipelineStepRunner} this factory produces.
     * Should not be null or empty.
     */
    String getRunnerType();

    /**
     * Creates a new, uninitialized instance of the {@link PipelineStepRunner}.
     * <p>
     * The created runner will subsequently have its
     * {@link PipelineStepRunner#init(StepConfig, ai.kompile.pipelines.framework.api.context.Context)}
     * method called by the pipeline runtime before it is used for execution.
     *
     * @return A new, uninitialized {@link PipelineStepRunner} instance.
     * Should not return null.
     * @throws RuntimeException if an instance cannot be created for some reason
     * (though typically, configuration or resource loading issues are handled in the init phase).
     */
    PipelineStepRunner create();
}