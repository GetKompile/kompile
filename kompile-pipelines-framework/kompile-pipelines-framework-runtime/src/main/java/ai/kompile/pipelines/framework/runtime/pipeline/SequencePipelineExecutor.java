// Location: kompile-pipelines-framework/kompile-pipelines-framework-runtime/src/main/java/ai/kompile/pipelines/framework/runtime/pipeline/
package ai.kompile.pipelines.framework.runtime.pipeline;

import ai.kompile.pipelines.framework.api.Pipeline;
import ai.kompile.pipelines.framework.api.PipelineExecutor;
import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.StepConfig;
import ai.kompile.pipelines.framework.api.context.Context;
import ai.kompile.pipelines.framework.api.data.Data;
import ai.kompile.pipelines.framework.api.data.DataFactory;
import ai.kompile.pipelines.framework.api.data.PipelineDataConstants;
import ai.kompile.pipelines.framework.api.data.PipelineToolCallRequest;
import ai.kompile.pipelines.framework.api.data.PipelineToolCallResponse;
import ai.kompile.pipelines.framework.api.data.ValueType;
import ai.kompile.pipelines.framework.api.llm.LLMStepConfig;
import ai.kompile.pipelines.framework.core.context.DefaultContext;
import ai.kompile.pipelines.framework.core.context.NoOpMetrics;
import ai.kompile.pipelines.framework.core.context.NoOpProfiler;
import ai.kompile.pipelines.framework.runtime.tooling.PipelineToolCallOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class SequencePipelineExecutor extends BasePipelineExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SequencePipelineExecutor.class);

    private final Map<String, PausedExecutionState> pausedExecutions = new ConcurrentHashMap<>();
    private final PipelineToolCallOrchestrator toolOrchestrator;
    private final int maxToolCallingDepthConfig;
    private final Supplier<Context> defaultContextFactory; // Keep a reference if Base doesn't expose its

    private static class PausedExecutionState {
        final int nextStepIndexToExecute;
        final Data dataAtPause;
        final Context originalContext;
        final String pausedByStepName;

        PausedExecutionState(int nextStepIndexToExecute, Data dataAtPause, Context originalContext, String pausedByStepName) {
            this.nextStepIndexToExecute = nextStepIndexToExecute;
            this.dataAtPause = dataAtPause.dup(); // Store a copy
            this.originalContext = originalContext; // This context might be stateful
            this.pausedByStepName = pausedByStepName;
        }
    }

    public SequencePipelineExecutor(
            Pipeline pipeline,
            boolean initializeRunnersInBase,
            DataFactory dataFactory, // DataFactory is not directly used by Base's constructor
            ExecutorService executorService,
            Supplier<Context> defaultContextFactory
    ) throws Exception {
        super(pipeline, initializeRunnersInBase);
        int maxToolCallingDepthConfig1;
        // BasePipelineExecutor constructor from uploaded file: (Pipeline pipeline, boolean initializeRunners)
        // It does not take dataFactory, executorService, defaultContextFactory.
        // We assume BasePipelineExecutor makes its asyncExecutorService available if needed (it does as a protected field).
        // And it has its own default way of creating Context in exec(Data) if one isn't provided.

        this.defaultContextFactory = (defaultContextFactory != null) ? defaultContextFactory :
                () -> new DefaultContext(Data.empty(), "seq-exec-" + System.nanoTime(), "seq-ctx-" + System.nanoTime(), null, NoOpMetrics.INSTANCE, NoOpProfiler.INSTANCE);


        // Max tool depth should ideally be from a central configuration.
        // Using a system property or a default here.
        String maxDepthStr = System.getProperty("kompile.pipelines.toolcalling.maxDepth", "5");
        try {
            maxToolCallingDepthConfig1 = Integer.parseInt(maxDepthStr);
        } catch (NumberFormatException e) {
            maxToolCallingDepthConfig1 = 5;
        }

        this.maxToolCallingDepthConfig = maxToolCallingDepthConfig1;
        this.toolOrchestrator = new PipelineToolCallOrchestrator(
                this.pipeline,
                super.runnerFactories, // From BasePipelineExecutor
                new ObjectMapper(),
                this.maxToolCallingDepthConfig
        );

        // Ensure runners are initialized if BasePipelineExecutor didn't do it
        // (BasePipelineExecutor's initializeRunners populates `super.runners`)
        if (super.runners.isEmpty() && !super.pipeline.getSteps().isEmpty() && !initializeRunnersInBase) {
            super.initializeRunners(); // This uses a DefaultContext internally in Base
        }
    }

    public SequencePipelineExecutor(Pipeline pipeline, boolean ownRunners) throws Exception {
        this(pipeline, true, Data.Factory.get(), null, // DataFactory.get() for default
                () -> new DefaultContext(Data.empty(), "seq-exec-" + System.nanoTime(), "seq-ctx-" + System.nanoTime(), null, NoOpMetrics.INSTANCE, NoOpProfiler.INSTANCE));
    }

    @Override
    public void ensureRunnersInitialized() throws Exception {
        // This method is in BasePipelineExecutor, but if it wasn't called by constructor,
        // it might need to be called before exec.
        if (super.runners.isEmpty() && !super.pipeline.getSteps().isEmpty()) {
            super.initializeRunners();
        }
    }

    @Override
    public Data exec(Data input, Context context) throws Exception {
        super.checkIfClosed();
        Objects.requireNonNull(input, "Input data cannot be null.");
        Objects.requireNonNull(context, "Context cannot be null.");
        ensureRunnersInitialized();

        String executionId = context.executionId().orElseThrow(() ->
                new IllegalArgumentException("Context must provide an executionId for resumable executions."));

        if (pausedExecutions.containsKey(executionId)) {
            throw new IllegalStateException("Execution with ID '" + executionId + "' is already paused. Use resume() to continue.");
        }
        return processPipeline(input, context, 0, executionId);
    }

    @Override
    public Data resume(Context context, Data toolResponsesFromExternal) throws Exception {
        super.checkIfClosed();
        Objects.requireNonNull(context, "Context cannot be null for resume.");
        Objects.requireNonNull(toolResponsesFromExternal, "Tool responses data cannot be null for resume.");
        ensureRunnersInitialized();

        String executionId = context.executionId().orElseThrow(() ->
                new IllegalArgumentException("Context must provide an executionId to resume an execution."));

        PausedExecutionState pausedState = pausedExecutions.remove(executionId);
        if (pausedState == null) {
            throw new IllegalStateException("No paused execution found for ID '" + executionId + "'. Cannot resume.");
        }

        int llmStepIndexToResume = pausedState.nextStepIndexToExecute - 1;
        if (llmStepIndexToResume < 0 || llmStepIndexToResume >= runners.size()) { // runners from BasePipelineExecutor
            throw new IllegalStateException("Invalid step index (" + llmStepIndexToResume + ") in PausedExecutionState for resume. Max index: " + (runners.size() -1));
        }

        PipelineStepRunner llmRunner = runners.get(llmStepIndexToResume);
        StepConfig llmBaseConfig = pipeline.getSteps().get(llmStepIndexToResume);

        if (!(llmBaseConfig instanceof LLMStepConfig)) {
            throw new IllegalStateException("Step '" + pausedState.pausedByStepName + "' at resume index " + llmStepIndexToResume +
                    " is not an LLMStepConfig, cannot process tool response with orchestrator.");
        }
        LLMStepConfig llmConfig = (LLMStepConfig) llmBaseConfig;
        Data llmResumptionInput = Data.empty();

        // Restore conversation context from the data that was present when the LLM step paused.
        if (pausedState.dataAtPause.has(llmConfig.getConversationContextName())) {
            Object convContextRaw = pausedState.dataAtPause.get(llmConfig.getConversationContextName());
            // Ensure it's a List and elements are String before putting
            if (convContextRaw instanceof List && pausedState.dataAtPause.listType(llmConfig.getConversationContextName()) == ValueType.STRING) {
                llmResumptionInput.putList(llmConfig.getConversationContextName(), (List<String>) convContextRaw, ValueType.STRING);
            }
        }

        // Add the external tool response(s).
        if (toolResponsesFromExternal.has(llmConfig.getToolCallResponseInputName())) {
            Object responseObj = toolResponsesFromExternal.get(llmConfig.getToolCallResponseInputName());
            if (responseObj instanceof PipelineToolCallResponse) {
                llmResumptionInput.put(llmConfig.getToolCallResponseInputName(), responseObj);
            } else if (responseObj instanceof Data) { // Attempt to reconstruct if passed as Data
                Data responseData = (Data) responseObj;
                try {
                    String id = responseData.getString("id");
                    String name = responseData.getString("name");
                    String content = responseData.getString("content"); // This should be a JSON string
                    boolean isError = responseData.getBoolean("isError", false);
                    if (id != null && name != null && content != null) {
                        PipelineToolCallResponse reconstructed = new PipelineToolCallResponse(id, name, content, isError);
                        llmResumptionInput.put(llmConfig.getToolCallResponseInputName(), reconstructed);
                    } else {

                    }
                } catch (Exception e) {

                }
            }
        }


        Context resumedLlmTurnContext = pausedState.originalContext.child(pausedState.pausedByStepName + "-resumedTurn-" + System.nanoTime());

        Data llmFinalOutput = toolOrchestrator.executeLLMStepWithInternalToolLoop(
                llmRunner, llmConfig, llmResumptionInput, resumedLlmTurnContext);

        return processPipeline(llmFinalOutput, context, pausedState.nextStepIndexToExecute, executionId);
    }

    private Data processPipeline(Data currentInputData, Context currentPipelineContext, int startIndex, String executionId) throws Exception {
        Data stepInput = currentInputData;

        for (int i = startIndex; i < runners.size(); i++) { // runners from BasePipelineExecutor
            PipelineStepRunner runner = runners.get(i);
            StepConfig currentStepConfig = pipeline.getSteps().get(i);
            String stepName = currentStepConfig.runnerClassName() != null ? currentStepConfig.runnerClassName() :
                    (runner.getClass().getSimpleName() + "[" + i + "]");
            Context stepContext = currentPipelineContext.child(stepName + "-exec-" + System.nanoTime());

            Data stepOutput;
            try {

                if (currentStepConfig instanceof LLMStepConfig && runner.isInitialized()) {
                    LLMStepConfig llmConfig = (LLMStepConfig) currentStepConfig;
                    stepOutput = toolOrchestrator.executeLLMStepWithInternalToolLoop(
                            runner, llmConfig, stepInput, stepContext);
                } else {
                    stepOutput = runner.exec(stepInput, stepContext);
                }

                Objects.requireNonNull(stepOutput, "Step " + stepName + " returned null data.");

                if (stepOutput.getBoolean(PipelineDataConstants.IS_TOOL_CALL_REQUEST_FLAG_KEY, false)) {
                    pausedExecutions.put(executionId, new PausedExecutionState(i + 1, stepOutput.dup(), currentPipelineContext, stepName));
                    return stepOutput;
                }
                stepInput = stepOutput;

            } catch (Exception e) {
                throw new RuntimeException("Error in step " + stepName + " of pipeline " + pipeline.id(), e);
            }
        }
        pausedExecutions.remove(executionId);
        return stepInput;
    }

    @Override
    public CompletableFuture<Data> resumeAsync(Context context, Data toolResponses) {
        super.checkIfClosed();
        Objects.requireNonNull(context, "Context cannot be null for async resume.");
        Objects.requireNonNull(toolResponses, "Tool responses data cannot be null for async resume.");
        String executionId = context.executionId().orElseThrow(() ->
                new IllegalArgumentException("Context must provide an executionId for async resume."));

        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureRunnersInitialized();
                return resume(context, toolResponses);
            } catch (Exception e) {
                // Wrap in a RuntimeException for CompletableFuture's exceptional completion
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new RuntimeException("Async resume failed for " + executionId, e);
            }
        }, super.asyncExecutorService); // Use asyncExecutorService from BasePipelineExecutor
    }

    @Override
    public void resumeAsync(Context context, Data toolResponses, Consumer<Data> onSuccess, Consumer<Throwable> onFailure) {
        super.checkIfClosed();
        Objects.requireNonNull(context, "Context cannot be null.");
        Objects.requireNonNull(toolResponses, "Tool responses cannot be null.");
        Objects.requireNonNull(onSuccess, "onSuccess callback cannot be null.");
        Objects.requireNonNull(onFailure, "onFailure callback cannot be null.");

        resumeAsync(context, toolResponses)
                .thenAccept(result -> {
                    try {
                        onSuccess.accept(result);
                    } catch (Exception e) {
                        onFailure.accept(e);
                    }
                })
                .exceptionally(err -> {
                    Throwable cause = err.getCause() != null ? err.getCause() : err;
                    onFailure.accept(cause);
                    return null;
                });
    }

    @Override
    public Iterator<Data> execStream(Data initialInput) throws Exception {
        Context streamRootContext = this.defaultContextFactory.get(); // Use the factory from this instance
        return execStream(initialInput, streamRootContext);
    }

    @Override
    public Iterator<Data> execStream(Data initialInput, Context context) throws Exception {
        super.checkIfClosed();
        Objects.requireNonNull(initialInput, "Initial input data cannot be null for execStream.");
        Objects.requireNonNull(context, "Context cannot be null for execStream.");
        ensureRunnersInitialized();

        String executionId = context.executionId().orElse("stream-" + System.nanoTime());
        Context streamContext = context.executionId().isPresent() ? context :
                context.child("execStream-" + executionId + "-" + System.nanoTime());

        return new Iterator<Data>() {
            private Data currentStepInput = initialInput.dup(); // Work on a copy
            private int currentStepIndex = 0;
            private Data nextOutputToYield = null;
            private boolean iterationFinished = false;
            private boolean pipelinePausedForExternalTool = false;

            @Override
            public boolean hasNext() {
                if (iterationFinished) return false;
                if (nextOutputToYield != null) return true;

                if (pipelinePausedForExternalTool) {
                    iterationFinished = true; // The stream effectively ends here, yielding the pause request.
                    return false;
                }

                if (currentStepIndex >= runners.size()) {
                    iterationFinished = true;
                    return false;
                }

                PipelineStepRunner runner = runners.get(currentStepIndex);
                StepConfig currentStepConfig = pipeline.getSteps().get(currentStepIndex);
                String stepName = currentStepConfig.runnerClassName() != null ? currentStepConfig.runnerClassName() :
                        (runner.getClass().getSimpleName() + "[" + currentStepIndex + "]");
                Context stepContext = streamContext.child(stepName + "-streamTurn-" + System.nanoTime());

                try {
                    Data stepOutput;
                    if (currentStepConfig instanceof LLMStepConfig && runner.isInitialized()) {
                        LLMStepConfig llmConfig = (LLMStepConfig) currentStepConfig;
                        stepOutput = toolOrchestrator.executeLLMStepWithInternalToolLoop(
                                runner, llmConfig, currentStepInput, stepContext);
                    } else {
                        stepOutput = runner.exec(currentStepInput, stepContext);
                    }
                    Objects.requireNonNull(stepOutput, "Stream: Step " + stepName + " returned null data.");

                    nextOutputToYield = stepOutput.dup(); // Yield a copy
                    currentStepInput = stepOutput; // Output of this step is input to next in the stream

                    if (stepOutput.getBoolean(PipelineDataConstants.IS_TOOL_CALL_REQUEST_FLAG_KEY, false)) {
                        pipelinePausedForExternalTool = true; // This stream iterator instance will stop after yielding this.
                    }
                    return true;

                } catch (Exception e) {
                    iterationFinished = true;
                    throw new RuntimeException("Error processing stream at step " + stepName, e); // Propagate as unchecked
                }
            }

            @Override
            public Data next() {
                if (!hasNext()) {
                    throw new NoSuchElementException("No more elements in pipeline stream for execution ID " + executionId);
                }
                Data toReturn = nextOutputToYield;
                nextOutputToYield = null;
                if (!pipelinePausedForExternalTool) {
                    currentStepIndex++;
                }
                return toReturn;
            }
        };
    }

    @Override
    public Iterator<Data> execStream(Pipeline pipelineToStream, Data initialInput, Context context) throws Exception {
        if (this.pipeline != pipelineToStream && !Objects.equals(this.pipeline.id(), pipelineToStream.id())) {
            throw new IllegalArgumentException("SequencePipelineExecutor is bound to a specific pipeline instance for streaming due to initialized runners.");
        }
        return execStream(initialInput, context);
    }
}
