package ai.kompile.pipelines.framework.runtime.pipeline;

import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.PipelineExecutor;
import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory; // For loading factories
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.core.context.DefaultContext; // Default context implementation
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors; // For default async executor
import java.util.function.Consumer;

/**
 * An abstract base class for {@link PipelineExecutor} implementations.
 * It handles common tasks such as:
 * - Storing the associated {@link Pipeline}.
 * - Managing a pool of {@link PipelineStepRunner} instances, including their
 * instantiation via {@link PipelineStepRunnerFactory} and initialization.
 * - Providing a basic asynchronous execution capability using a default ExecutorService.
 * - Managing the closed state of the executor.
 */

public abstract class BasePipelineExecutor implements PipelineExecutor {

    @Getter
    protected final Pipeline pipeline;
    protected final List<PipelineStepRunner> runners;
    protected final Map<String, PipelineStepRunnerFactory> runnerFactories;
    protected volatile boolean closed = false;
    protected final ExecutorService asyncExecutorService; // For execAsync methods

    /**
     * Constructs a BasePipelineExecutor.
     *
     * @param pipeline The pipeline this executor will run.
     * @param initializeRunners If true, runners will be instantiated and initialized immediately.
     * @throws NullPointerException if pipeline is null.
     * @throws Exception if runner instantiation or initialization fails and initializeRunners is true.
     */
    protected BasePipelineExecutor(Pipeline pipeline, boolean initializeRunners) throws Exception {
        this.pipeline = Objects.requireNonNull(pipeline, "Pipeline cannot be null for executor.");
        this.runners = new ArrayList<>();
        this.runnerFactories = new ConcurrentHashMap<>();
        this.asyncExecutorService = Executors.newCachedThreadPool(); // Or configurable

        loadRunnerFactories();

        if (initializeRunners) {
            initializeRunners();
        }
    }

    /**
     * Loads available PipelineStepRunnerFactories using Java's ServiceLoader.
     */
    private void loadRunnerFactories() {
        ServiceLoader<PipelineStepRunnerFactory> loader = ServiceLoader.load(PipelineStepRunnerFactory.class);
        for (PipelineStepRunnerFactory factory : loader) {
            if (factory.getRunnerType() != null && !factory.getRunnerType().isEmpty()) {
                runnerFactories.put(factory.getRunnerType(), factory);
            }
        }

    }

    /**
     * Initializes all runners for the pipeline steps.
     * This involves creating each runner using its factory and then calling its init method.
     *
     * @throws Exception if any runner fails to be created or initialized.
     */
    protected void initializeRunners() throws Exception {
        if (pipeline.getSteps() == null) {
            return;
        }
        // Create a root context for initialization phase.
        // The originalInput for init context might be null or a placeholder.
        Context initRootContext = new DefaultContext(Data.empty(), pipeline.id() + "-init", "pipeline-init", null, null, null);

        for (StepConfig stepConfig : pipeline.getSteps()) {
            String runnerClassName = stepConfig.runnerClassName();
            PipelineStepRunnerFactory factory = runnerFactories.get(runnerClassName);
            if (factory == null) {
                throw new IllegalStateException(String.format(
                        "No PipelineStepRunnerFactory found for runner class '%s' specified in StepConfig. " +
                                "Ensure the factory is registered via ServiceLoader and its getRunnerType() matches.",
                        runnerClassName));
            }
            PipelineStepRunner runner = factory.create();
            if (runner == null) {
                throw new IllegalStateException(String.format(
                        "PipelineStepRunnerFactory '%s' returned a null runner for type '%s'.",
                        factory.getClass().getName(), runnerClassName));
            }
            try {
                // Each runner gets its own child context for initialization if desired,
                // or they can all share the same initRootContext.
                // For step-specific init context:
                // Context stepInitContext = initRootContext.child("step-" + runners.size() + "-init");
                // runner.init(stepConfig, stepInitContext);

                // For sharing root init context (simpler):
                runner.init(stepConfig, initRootContext);

                runners.add(runner);
            } catch (Exception e) {
                // Attempt to close already initialized runners before re-throwing
                closeRunnersQuietly();
                throw new Exception("Failed to initialize runner for step: " + runnerClassName, e);
            }
        }
    }

    protected void checkIfClosed() {
        if (closed) {
            throw new IllegalStateException("PipelineExecutor for pipeline '" +
                    (pipeline != null ? pipeline.id() : "unknown") + "' is closed.");
        }
    }

    @Override
    public Data exec(Data input) throws Exception {
        checkIfClosed();
        Objects.requireNonNull(input, "Input Data cannot be null.");
        // Create a new root context for this specific execution.
        Context executionContext = new DefaultContext(input); // originalInput is set here
        return exec(input, executionContext);
    }

    public abstract void ensureRunnersInitialized() throws Exception;

    // Abstract method to be implemented by concrete executors (Sequence, Graph)
    @Override
    public abstract Data exec(Data input, Context context) throws Exception;


    @Override
    public CompletableFuture<Data> execAsync(Data input) {
        checkIfClosed();
        Objects.requireNonNull(input, "Input Data cannot be null for async execution.");
        Context executionContext = new DefaultContext(input); // New context for this async execution
        return execAsync(input, executionContext);
    }

    @Override
    public CompletableFuture<Data> execAsync(Data input, Context context) {
        checkIfClosed();
        Objects.requireNonNull(input, "Input Data cannot be null for async execution.");
        Objects.requireNonNull(context, "Context cannot be null for async execution.");
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Ensure context passed to exec is the one provided or derived correctly
                return exec(input, context);
            } catch (Exception e) {
                throw new RuntimeException(e); // CompletableFuture will handle this by completing exceptionally
            }
        }, asyncExecutorService);
    }

    @Override
    public void execAsync(Data input, Consumer<Data> onSuccess, Consumer<Throwable> onFailure) {
        checkIfClosed();
        Objects.requireNonNull(input, "Input Data cannot be null.");
        Objects.requireNonNull(onSuccess, "onSuccess callback cannot be null.");
        Objects.requireNonNull(onFailure, "onFailure callback cannot be null.");
        Context executionContext = new DefaultContext(input);
        execAsync(input, executionContext, onSuccess, onFailure);
    }

    @Override
    public void execAsync(Data input, Context context, Consumer<Data> onSuccess, Consumer<Throwable> onFailure) {
        checkIfClosed();
        Objects.requireNonNull(input, "Input Data cannot be null.");
        Objects.requireNonNull(context, "Context cannot be null.");
        Objects.requireNonNull(onSuccess, "onSuccess callback cannot be null.");
        Objects.requireNonNull(onFailure, "onFailure callback cannot be null.");

        asyncExecutorService.submit(() -> {
            try {
                Data result = exec(input, context);
                onSuccess.accept(result);
            } catch (Throwable t) { // Catch Throwable to include Errors
                onFailure.accept(t);
            }
        });
    }


    @Override
    public Pipeline getPipeline() {
        return this.pipeline;
    }

    private void closeRunnersQuietly() {
        for (PipelineStepRunner runner : runners) {
            try {
                if (runner.isInitialized()) {
                    runner.close();
                }
            } catch (Exception e) {
            }
        }
        runners.clear();
    }

    @Override
    public void close() throws Exception {
        if (closed) {
            return;
        }
        closed = true;

        // Close all runners
        Exception firstException = null;
        for (PipelineStepRunner runner : runners) {
            try {
                if (runner.isInitialized()) {
                    runner.close();
                }
            } catch (Exception e) {
                if (firstException == null) {
                    firstException = e;
                } else {
                    firstException.addSuppressed(e);
                }
            }
        }
        runners.clear(); // Clear the list after attempting to close all

        // Shutdown the async executor service
        asyncExecutorService.shutdown();
        try {
            if (!asyncExecutorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                asyncExecutorService.shutdownNow();
                if (!asyncExecutorService.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                }
            }
        } catch (InterruptedException ie) {
            asyncExecutorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (firstException != null) {
            throw firstException;
        }
    }
}