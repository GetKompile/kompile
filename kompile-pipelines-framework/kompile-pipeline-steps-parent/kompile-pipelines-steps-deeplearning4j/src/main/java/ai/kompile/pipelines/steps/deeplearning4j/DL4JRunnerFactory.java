package ai.kompile.pipelines.steps.deeplearning4j;

import ai.kompile.pipelines.framework.api.PipelineStepRunner;
import ai.kompile.pipelines.framework.api.PipelineStepRunnerFactory;

public class DL4JRunnerFactory implements PipelineStepRunnerFactory {
    @Override
    public String getRunnerType() {
        return DL4JRunnerConstants.RUNNER_FQCN;
    }

    @Override
    public PipelineStepRunner create() {
        return new DL4JRunner();
    }
}