package ai.kompile.pipelines.steps.documentparser;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;

public class DocumentParserRunnerFactory implements PipelineStepRunnerFactory {
    @Override
    public String getRunnerType() {
        // This FQCN must match what users put in their StepConfig's "runnerClassName"
        return DocumentParserConstants.RUNNER_FQCN;
    }

    @Override
    public PipelineStepRunner create() {
        return new DocumentParserRunner();
    }
}